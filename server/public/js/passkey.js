// WebAuthn glue: converts the server's JSON options (base64url strings) to the binary form the
// browser API wants, and the browser's credential back to JSON for the server.

const toBytes = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
const toB64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

export const passkeysSupported = () => !!(window.PublicKeyCredential && navigator.credentials && window.isSecureContext);

/** Browser offers passkeys in the username field's autofill. */
export async function passkeyAutofillAvailable() {
  return passkeysSupported() && !!(await PublicKeyCredential.isConditionalMediationAvailable?.().catch(() => false));
}

export async function createPasskey(o) {
  const cred = await navigator.credentials.create({ publicKey: {
    ...o,
    challenge: toBytes(o.challenge),
    user: { ...o.user, id: toBytes(o.user.id) },
    excludeCredentials: o.excludeCredentials.map((c) => ({ ...c, id: toBytes(c.id) })),
  } });
  return {
    clientDataJSON: toB64url(cred.response.clientDataJSON),
    attestationObject: toB64url(cred.response.attestationObject),
    transports: cred.response.getTransports?.() ?? [],
  };
}

/** `conditional`: wait for the user to pick a passkey from autofill instead of showing a dialog. */
export async function getPasskey(o, { signal, conditional = false } = {}) {
  const cred = await navigator.credentials.get({
    publicKey: { ...o, challenge: toBytes(o.challenge) },
    signal,
    ...(conditional ? { mediation: 'conditional' } : {}),
  });
  return {
    id: toB64url(cred.rawId),
    clientDataJSON: toB64url(cred.response.clientDataJSON),
    authenticatorData: toB64url(cred.response.authenticatorData),
    signature: toB64url(cred.response.signature),
    userHandle: cred.response.userHandle ? toB64url(cred.response.userHandle) : null,
  };
}

/** User cancelled, timed out, or a newer request replaced this one: not worth an error message. */
export const isPasskeyCancel = (e) => e?.name === 'NotAllowedError' || e?.name === 'AbortError';
