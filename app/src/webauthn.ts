// Passkeys (WebAuthn Level 2) with node:crypto only: just enough CBOR/COSE to read a new credential's
// public key, and signature checks for sign-in. Attestation is not requested ('none'), so which
// authenticator made a key is not verified — only that the same key signs later.
import { createHash, createPublicKey, verify, randomBytes } from 'node:crypto';
import type { IncomingMessage } from 'node:http';
import { HttpError, publicOrigin } from './http.ts';

/** COSE algorithms we offer: ES256, EdDSA (Ed25519), RS256 — what browsers and authenticators support. */
export const ALGORITHMS = [-7, -8, -257];

const FLAG_UP = 0x01; // user present
const FLAG_UV = 0x04; // user verified (PIN, fingerprint, face)
const FLAG_AT = 0x40; // attested credential data included

const b64url = (b: Buffer) => b.toString('base64url');
const sha256 = (b: Buffer | string) => createHash('sha256').update(b).digest();

// ---------------------------------------------------------------- relying party

/** RP ID = hostname of the public origin, so passkeys work only on the address they were created on. */
export function relyingParty(req: IncomingMessage): { id: string; origin: string } {
  const origin = publicOrigin(req);
  return { id: new URL(origin).hostname, origin };
}

// ---------------------------------------------------------------- one-time challenges (in memory, 5 min)

const challenges = new Map<string, { userId: string | null; until: number }>();
const CHALLENGE_MS = 5 * 60 * 1000;

export function newChallenge(userId: string | null): string {
  const now = Date.now();
  for (const [k, v] of challenges) if (v.until < now || challenges.size > 10_000) challenges.delete(k); else break;
  const c = b64url(randomBytes(32));
  challenges.set(c, { userId, until: now + CHALLENGE_MS });
  return c;
}

// ---------------------------------------------------------------- minimal CBOR (definite lengths only, as CTAP2 emits)

function cbor(buf: Buffer, pos = 0): [unknown, number] {
  if (pos >= buf.length) throw new Error('truncated CBOR');
  const major = buf[pos] >> 5;
  const info = buf[pos] & 31;
  pos++;
  let n: number;
  if (info < 24) n = info;
  else if (info === 24) { n = buf.readUInt8(pos); pos += 1; }
  else if (info === 25) { n = buf.readUInt16BE(pos); pos += 2; }
  else if (info === 26) { n = buf.readUInt32BE(pos); pos += 4; }
  else if (info === 27) { n = Number(buf.readBigUInt64BE(pos)); pos += 8; }
  else throw new Error('unsupported CBOR length');
  switch (major) {
    case 0: return [n, pos];
    case 1: return [-1 - n, pos];
    case 2:
      if (pos + n > buf.length) throw new Error('truncated CBOR');
      return [buf.subarray(pos, pos + n), pos + n];
    case 3:
      if (pos + n > buf.length) throw new Error('truncated CBOR');
      return [buf.toString('utf8', pos, pos + n), pos + n];
    case 4: {
      const arr: unknown[] = [];
      for (let i = 0; i < n; i++) { const [v, p] = cbor(buf, pos); arr.push(v); pos = p; }
      return [arr, pos];
    }
    case 5: {
      const map = new Map<unknown, unknown>();
      for (let i = 0; i < n; i++) {
        const [k, p1] = cbor(buf, pos);
        const [v, p2] = cbor(buf, p1);
        map.set(k, v);
        pos = p2;
      }
      return [map, pos];
    }
    case 7:
      if (info === 20) return [false, pos];
      if (info === 21) return [true, pos];
      if (info === 22 || info === 23) return [null, pos];
  }
  throw new Error('unsupported CBOR item');
}

// ---------------------------------------------------------------- authenticator data, client data

interface AuthData {
  rpIdHash: Buffer;
  flags: number;
  signCount: number;
  credentialId?: Buffer;
  coseKey?: Map<unknown, unknown>;
}

function parseAuthData(buf: Buffer): AuthData {
  if (buf.length < 37) throw new HttpError(400, 'Invalid authenticator data');
  const ad: AuthData = { rpIdHash: buf.subarray(0, 32), flags: buf[32], signCount: buf.readUInt32BE(33) };
  if (ad.flags & FLAG_AT) {
    let key: unknown;
    try {
      const len = buf.readUInt16BE(53);
      ad.credentialId = buf.subarray(55, 55 + len);
      [key] = cbor(buf, 55 + len);
    } catch { throw new HttpError(400, 'Invalid authenticator data'); }
    if (!(key instanceof Map)) throw new HttpError(400, 'Invalid credential public key');
    ad.coseKey = key;
  }
  return ad;
}

function checkAuthData(ad: AuthData, rpId: string): void {
  if (!ad.rpIdHash.equals(sha256(rpId))) throw new HttpError(400, 'Passkey belongs to a different site');
  if (!(ad.flags & FLAG_UP) || !(ad.flags & FLAG_UV)) throw new HttpError(400, 'The passkey did not verify you (PIN or biometrics)');
}

/** Checks type, origin and challenge; consumes the challenge and returns who it was issued for. */
function checkClientData(raw: Buffer, type: string, origin: string): { userId: string | null } {
  let c: { type?: string; challenge?: string; origin?: string; crossOrigin?: boolean };
  try { c = JSON.parse(raw.toString('utf8')); } catch { throw new HttpError(400, 'Invalid client data'); }
  if (c.type !== type || c.crossOrigin) throw new HttpError(400, 'Invalid client data');
  if (c.origin !== origin) throw new HttpError(400, `Passkey request came from ${c.origin}, expected ${origin}. Set PUBLIC_URL if Cloudy runs behind a proxy.`);
  const entry = challenges.get(String(c.challenge));
  challenges.delete(String(c.challenge));
  if (!entry || entry.until < Date.now()) throw new HttpError(400, 'The passkey request expired, please try again');
  return { userId: entry.userId };
}

function coseToSpki(key: Map<unknown, unknown>): { alg: number; spki: Buffer } {
  const kty = key.get(1);
  const alg = key.get(3) as number;
  const bytes = (label: number) => {
    const v = key.get(label);
    if (!Buffer.isBuffer(v)) throw new HttpError(400, 'Invalid credential public key');
    return b64url(v);
  };
  let jwk: Record<string, string>;
  if (alg === -7 && kty === 2 && key.get(-1) === 1) jwk = { kty: 'EC', crv: 'P-256', x: bytes(-2), y: bytes(-3) };
  else if (alg === -8 && kty === 1 && key.get(-1) === 6) jwk = { kty: 'OKP', crv: 'Ed25519', x: bytes(-2) };
  else if (alg === -257 && kty === 3) jwk = { kty: 'RSA', n: bytes(-1), e: bytes(-2) };
  else throw new HttpError(400, 'Unsupported passkey algorithm');
  const spki = createPublicKey({ key: jwk, format: 'jwk' }).export({ format: 'der', type: 'spki' });
  return { alg, spki };
}

const fromB64url = (v: unknown, what: string): Buffer => {
  if (typeof v !== 'string' || !/^[A-Za-z0-9_-]*$/.test(v) || !v) throw new HttpError(400, `Missing ${what}`);
  return Buffer.from(v, 'base64url');
};

// ---------------------------------------------------------------- ceremonies

export interface NewCredential {
  credentialId: string;
  publicKey: Buffer;
  alg: number;
  signCount: number;
}

/** Verifies a navigator.credentials.create() response for `userId` (attestation statement ignored). */
export function verifyRegistration(
  req: IncomingMessage,
  userId: string,
  body: { clientDataJSON?: unknown; attestationObject?: unknown },
): NewCredential {
  const rp = relyingParty(req);
  const issued = checkClientData(fromB64url(body.clientDataJSON, 'client data'), 'webauthn.create', rp.origin);
  if (issued.userId !== userId) throw new HttpError(400, 'The passkey request expired, please try again');
  let att: unknown;
  try { [att] = cbor(fromB64url(body.attestationObject, 'attestation')); } catch { throw new HttpError(400, 'Invalid attestation'); }
  const authData = att instanceof Map ? att.get('authData') : null;
  if (!Buffer.isBuffer(authData)) throw new HttpError(400, 'Invalid attestation');
  const ad = parseAuthData(authData);
  checkAuthData(ad, rp.id);
  if (!ad.credentialId?.length || !ad.coseKey) throw new HttpError(400, 'No credential in the response');
  const { alg, spki } = coseToSpki(ad.coseKey);
  return { credentialId: b64url(ad.credentialId), publicKey: spki, alg, signCount: ad.signCount };
}

/** Parses a navigator.credentials.get() response; pass the stored key to `verifyAssertion`. */
export function assertionCredentialId(body: { id?: unknown }): string {
  return b64url(fromB64url(body.id, 'credential'));
}

/**
 * Verifies a sign-in assertion against the stored public key and returns the new signature counter.
 * A counter that fails to increase (when the authenticator keeps one) suggests a cloned key.
 */
export function verifyAssertion(
  req: IncomingMessage,
  stored: { publicKey: Buffer; alg: number; signCount: number; userHandle: string },
  body: { clientDataJSON?: unknown; authenticatorData?: unknown; signature?: unknown; userHandle?: unknown },
): number {
  const rp = relyingParty(req);
  const clientData = fromB64url(body.clientDataJSON, 'client data');
  const authData = fromB64url(body.authenticatorData, 'authenticator data');
  const signature = fromB64url(body.signature, 'signature');
  checkClientData(clientData, 'webauthn.get', rp.origin);
  const ad = parseAuthData(authData);
  checkAuthData(ad, rp.id);
  if (body.userHandle && body.userHandle !== stored.userHandle) throw new HttpError(400, 'Passkey does not match its account');
  const key = createPublicKey({ key: stored.publicKey, format: 'der', type: 'spki' });
  const ok = verify(stored.alg === -8 ? null : 'sha256', Buffer.concat([authData, sha256(clientData)]), key, signature);
  if (!ok) throw new HttpError(401, 'Passkey signature is not valid');
  if ((ad.signCount > 0 || stored.signCount > 0) && ad.signCount <= stored.signCount) {
    throw new HttpError(401, 'This passkey may have been copied; remove it in Settings and add it again');
  }
  return ad.signCount;
}
