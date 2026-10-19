import { randomBytes, scrypt as scryptCb, timingSafeEqual, createHash, createHmac } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { config } from './config.ts';
import { q, one } from './db.ts';
import { HttpError, parseCookies, setCookie, clientIp } from './http.ts';

export interface User {
  id: string;
  display_name: string;
  email: string;
  is_admin: boolean;
  disabled: boolean;
  quota_bytes: number | null;
}

const USER_COLS = 'id, display_name, email, is_admin, disabled, quota_bytes';
const SESSION_COOKIE = 'fc_session';

function scrypt(pw: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) =>
    scryptCb(pw.normalize('NFKC'), salt, 32, { N: 16384, r: 8, p: 1 }, (err, key) =>
      err ? reject(err) : resolve(key),
    ),
  );
}

export async function hashPassword(pw: string): Promise<string> {
  const salt = randomBytes(16);
  return `scrypt$${salt.toString('base64')}$${(await scrypt(pw, salt)).toString('base64')}`;
}

export async function verifyPassword(pw: string, stored: string): Promise<boolean> {
  const [algo, salt, hash] = stored.split('$');
  if (algo !== 'scrypt' || !salt || !hash) return false;
  const expected = Buffer.from(hash, 'base64');
  const actual = await scrypt(pw, Buffer.from(salt, 'base64'));
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

export const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');
export const newToken = (bytes = 32) => randomBytes(bytes).toString('base64url');

export function checkPasswordStrength(pw: unknown): string {
  if (typeof pw !== 'string' || pw.length < 8) throw new HttpError(400, 'Password must be at least 8 characters');
  return pw;
}

// ---- brute-force protection (per IP, in memory) ----
const failures = new Map<string, { n: number; first: number }>();
const WINDOW_MS = 15 * 60 * 1000;
const MAX_FAILURES = 10;

export function checkRate(req: IncomingMessage): void {
  const f = failures.get(clientIp(req));
  if (f && Date.now() - f.first < WINDOW_MS && f.n >= MAX_FAILURES) {
    throw new HttpError(429, 'Too many failed attempts, try again later');
  }
}

export function recordFailure(req: IncomingMessage): void {
  const ip = clientIp(req);
  const f = failures.get(ip);
  if (!f || Date.now() - f.first >= WINDOW_MS) failures.set(ip, { n: 1, first: Date.now() });
  else f.n++;
}

// ---- server secret for signing link-share cookies ----
let secret: string | undefined;
export async function getSecret(): Promise<string> {
  if (secret) return secret;
  await q(`INSERT INTO settings (key, value) VALUES ('secret', $1) ON CONFLICT DO NOTHING`, [newToken()]);
  secret = (await one<{ value: string }>(`SELECT value FROM settings WHERE key = 'secret'`))!.value;
  return secret;
}

export async function sign(value: string): Promise<string> {
  return createHmac('sha256', await getSecret()).update(value).digest('base64url');
}

// ---- sessions (browser) ----
export async function login(req: IncomingMessage, id: string, password: string): Promise<User> {
  checkRate(req);
  const row = await one<User & { password_hash: string }>(
    `SELECT ${USER_COLS}, password_hash FROM users WHERE id = $1`,
    [id],
  );
  if (!row || row.disabled || !(await verifyPassword(password, row.password_hash))) {
    recordFailure(req);
    throw new HttpError(401, 'Invalid username or password');
  }
  const { password_hash: _, ...user } = row;
  return user;
}

export async function createSession(req: IncomingMessage, res: ServerResponse, userId: string): Promise<void> {
  const token = newToken();
  await q(
    `INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($1, $2, now() + make_interval(days => $3))`,
    [sha256(token), userId, config.sessionDays],
  );
  setCookie(req, res, SESSION_COOKIE, token, config.sessionDays * 86400);
}

export async function destroySession(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const token = parseCookies(req)[SESSION_COOKIE];
  if (token) await q(`DELETE FROM sessions WHERE token_hash = $1`, [sha256(token)]);
  setCookie(req, res, SESSION_COOKIE, '', 0);
}

export async function sessionUser(req: IncomingMessage): Promise<User | null> {
  const token = parseCookies(req)[SESSION_COOKIE];
  if (!token) return null;
  const u = await one<User>(
    `SELECT ${USER_COLS.split(', ').map((c) => 'u.' + c).join(', ')}
       FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = $1 AND s.expires_at > now() AND NOT u.disabled`,
    [sha256(token)],
  );
  return u ?? null;
}

// ---- Bearer tokens (native mobile apps) ----
// Tokens are app passwords, so they are listed and revocable in Settings and also work for DAV.

export function bearerToken(req: IncomingMessage): string | null {
  const h = req.headers.authorization ?? '';
  return h.startsWith('Bearer ') ? h.slice(7).trim() : null;
}

export async function bearerUser(req: IncomingMessage): Promise<User | null> {
  const token = bearerToken(req);
  if (!token) return null;
  const u = await one<User>(
    `UPDATE app_passwords a SET last_used_at = now() FROM users u
      WHERE a.token_hash = $1 AND u.id = a.user_id AND NOT u.disabled
      RETURNING ${USER_COLS.split(', ').map((c) => 'u.' + c).join(', ')}`,
    [sha256(token)],
  );
  return u ?? null;
}

export async function createAppToken(userId: string, name: string): Promise<string> {
  const token = [newToken(6), newToken(6), newToken(6)].join('-');
  await q(`INSERT INTO app_passwords (user_id, name, token_hash) VALUES ($1, $2, $3)`, [userId, name, sha256(token)]);
  return token;
}

// ---- HTTP Basic (WebDAV / CalDAV / CardDAV clients) ----
// Successful checks are cached briefly: DAV clients authenticate every request
// and scrypt is deliberately slow.
const basicCache = new Map<string, { user: string; until: number }>();

export async function basicUser(req: IncomingMessage): Promise<User | null> {
  const h = req.headers.authorization ?? '';
  if (!h.startsWith('Basic ')) return null;
  const key = sha256(h);
  const cached = basicCache.get(key);
  if (cached && cached.until > Date.now()) {
    return (await one<User>(`SELECT ${USER_COLS} FROM users WHERE id = $1 AND NOT disabled`, [cached.user])) ?? null;
  }
  checkRate(req);
  const decoded = Buffer.from(h.slice(6), 'base64').toString('utf8');
  const i = decoded.indexOf(':');
  if (i < 0) return null;
  const id = decoded.slice(0, i);
  const pw = decoded.slice(i + 1);
  const row = await one<User & { password_hash: string }>(
    `SELECT ${USER_COLS}, password_hash FROM users WHERE id = $1 AND NOT disabled`,
    [id],
  );
  let ok = false;
  if (row) {
    const app = await one(
      `UPDATE app_passwords SET last_used_at = now() WHERE user_id = $1 AND token_hash = $2 RETURNING id`,
      [id, sha256(pw)],
    );
    ok = !!app || (await verifyPassword(pw, row.password_hash));
  }
  if (!ok || !row) {
    recordFailure(req);
    return null;
  }
  basicCache.set(key, { user: id, until: Date.now() + 5 * 60 * 1000 });
  const { password_hash: _, ...user } = row;
  return user;
}

export function forgetBasicCache(): void {
  basicCache.clear();
}
