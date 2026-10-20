// Office documents with the built-in Collabora Online (coolwsd in the same container).
//
//   browser ──(Cloudy port)──▶ /browser, /hosting, /cool  ──proxy──▶ coolwsd 127.0.0.1:9980
//   coolwsd ──WOPI──▶ http://127.0.0.1:PORT/wopi/files/<id>?access_token=…  (this file)
//
// Cloudy is the WOPI host (CheckFileInfo, GetFile, PutFile) like Nextcloud's richdocuments app.
// Access tokens are HMAC-signed, short-lived and bound to one file and user; every WOPI call checks
// the user's current access again, so revoking a share also stops an open editor from saving.
import http from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Duplex } from 'node:stream';
import { createReadStream } from 'node:fs';
import { timingSafeEqual } from 'node:crypto';
import type { Router, Ctx } from './http.ts';
import { json, HttpError, publicOrigin } from './http.ts';
import { config } from './config.ts';
import { one } from './db.ts';
import { sign } from './auth.ts';
import type { User } from './auth.ts';
import * as F from './files.ts';

type Authed = (ctx: Ctx, user: User) => Promise<void>;

/** Extensions offered for office editing (others in Collabora's discovery, e.g. images, use Cloudy's viewer). */
const OFFICE_EXT = new Set([
  'doc', 'docx', 'docm', 'dot', 'dotx', 'odt', 'ott', 'rtf',
  'xls', 'xlsx', 'xlsm', 'xlt', 'xltx', 'ods', 'ots',
  'ppt', 'pptx', 'pps', 'ppsx', 'pot', 'potx', 'odp', 'otp', 'odg',
]);
const TOKEN_HOURS = 10;
const ext = (name: string) => (name.includes('.') ? name.split('.').pop()!.toLowerCase() : '');

// ---------------------------------------------------------------- discovery (which URL opens which extension)

interface Discovery { actions: Map<string, { action: 'edit' | 'view'; path: string }>; until: number }
let discovery: Discovery | null = null;
let discovering: Promise<Discovery | null> | null = null;

async function loadDiscovery(): Promise<Discovery | null> {
  if (!config.office) return null;
  if (discovery && discovery.until > Date.now()) return discovery;
  discovering ??= (async () => {
    try {
      const res = await fetch(`${config.collaboraUrl}/hosting/discovery`, { signal: AbortSignal.timeout(5000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const xml = await res.text();
      const actions = new Map<string, { action: 'edit' | 'view'; path: string }>();
      for (const m of xml.matchAll(/<action\s+([^>]*?)\/?>/g)) {
        const a = Object.fromEntries([...m[1].matchAll(/(\w+)="([^"]*)"/g)].map((x) => [x[1], x[2].replace(/&amp;/g, '&')]));
        if (!OFFICE_EXT.has(a.ext) || (a.name !== 'edit' && a.name !== 'view') || !a.urlsrc) continue;
        if (actions.get(a.ext)?.action === 'edit') continue;
        const u = new URL(a.urlsrc); // host is coolwsd's own; browsers use the same path through our proxy
        actions.set(a.ext, { action: a.name, path: u.pathname + u.search });
      }
      discovery = { actions, until: Date.now() + 10 * 60 * 1000 };
    } catch (e: any) {
      // Collabora still starting (or OFFICE disabled in the entrypoint): try again in 30 s.
      discovery = { actions: new Map(), until: Date.now() + 30 * 1000 };
      if (!/ECONNREFUSED|fetch failed/.test(String(e?.cause?.code ?? e?.message))) console.error('office discovery failed:', e.message);
    } finally {
      discovering = null;
    }
    return discovery;
  })();
  return discovering;
}

// ---------------------------------------------------------------- access tokens

interface Token { n: number; u: string; w: boolean; e: number; o: string }

async function makeToken(t: Token): Promise<string> {
  const body = Buffer.from(JSON.stringify(t)).toString('base64url');
  return `${body}.${await sign(`wopi:${body}`)}`;
}

async function readToken(token: string, nodeId: string): Promise<Token> {
  const [body, mac] = token.split('.');
  const good = body && mac ? Buffer.from(await sign(`wopi:${body}`)) : null;
  const given = Buffer.from(mac ?? '');
  if (!good || good.length !== given.length || !timingSafeEqual(good, given)) throw new HttpError(401, 'Invalid access token');
  let t: Token;
  try { t = JSON.parse(Buffer.from(body, 'base64url').toString()); } catch { throw new HttpError(401, 'Invalid access token'); }
  if (t.e < Date.now()) throw new HttpError(401, 'Access token expired');
  if (String(t.n) !== nodeId) throw new HttpError(401, 'Token is for another file');
  return t;
}

// ---------------------------------------------------------------- JSON API for clients

export function registerOffice(r: Router, authed: (h: Authed) => (ctx: Ctx) => Promise<void>) {
  // Which extensions open in the office editor (empty while Collabora is off or still starting).
  r.get('/api/office', authed(async ({ res }) => {
    const d = await loadDiscovery();
    json(res, { enabled: !!d?.actions.size, extensions: Object.fromEntries([...(d?.actions ?? [])].map(([k, v]) => [k, v.action])) });
  }));

  // Launch: the client POSTs access_token + access_token_ttl to `url` in an iframe (WOPI convention).
  r.post('/api/nodes/:id/office', authed(async ({ req, res, params }, user) => {
    const { node, acc } = await F.requireAccess(user.id, params.id, 'read');
    if (node.is_dir) throw new HttpError(400, 'Not a file');
    const d = await loadDiscovery();
    const action = d?.actions.get(ext(node.name));
    if (!action) throw new HttpError(d?.actions.size ? 415 : 503, d?.actions.size ? 'This file type cannot be opened in the office editor' : 'The office editor is not available');
    const canWrite = acc.perm !== 'read' && action.action === 'edit';
    const expires = Date.now() + TOKEN_HOURS * 3600 * 1000;
    const origin = publicOrigin(req);
    const wopiSrc = `http://127.0.0.1:${config.port}/wopi/files/${node.id}`;
    const sep = action.path.includes('?') ? (action.path.endsWith('?') || action.path.endsWith('&') ? '' : '&') : '?';
    const lang = String(req.headers['accept-language'] ?? 'en').split(/[,;]/)[0].trim() || 'en';
    json(res, {
      url: `${action.path}${sep}WOPISrc=${encodeURIComponent(wopiSrc)}&lang=${encodeURIComponent(lang)}&closebutton=1`,
      accessToken: await makeToken({ n: node.id, u: user.id, w: canWrite, e: expires, o: origin }),
      accessTokenTtl: expires,
      canWrite,
      name: node.name,
    });
  }));
}

// ---------------------------------------------------------------- WOPI host (called by coolwsd only)

const isLoopback = (a: string | undefined) => a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
const collaboraIsLocal = () => ['127.0.0.1', 'localhost', '[::1]'].includes(new URL(config.collaboraUrl).hostname);
const iso = (d: Date | string) => new Date(d).toISOString();

export async function handleWopi(req: IncomingMessage, res: ServerResponse, url: URL): Promise<boolean> {
  const m = /^\/wopi\/files\/(\d+)(\/contents)?$/.exec(url.pathname);
  if (!m) return false;
  // The embedded Collabora reaches us over loopback; nobody else needs this endpoint.
  if (!config.office || (collaboraIsLocal() && !isLoopback(req.socket.remoteAddress))) throw new HttpError(404, 'Not found');
  const auth = req.headers.authorization ?? '';
  const t = await readToken(url.searchParams.get('access_token') ?? (auth.startsWith('Bearer ') ? auth.slice(7) : ''), m[1]);
  const { node, acc } = await F.requireAccess(t.u, t.n, 'read').catch(() => { throw new HttpError(404, 'Not found'); });
  if (node.is_dir) throw new HttpError(404, 'Not found');
  const canWrite = t.w && acc.perm !== 'read';

  if (!m[2] && req.method === 'GET') { // CheckFileInfo
    const u = await one<{ display_name: string }>(`SELECT display_name FROM users WHERE id = $1`, [t.u]);
    json(res, {
      BaseFileName: node.name,
      Size: node.size,
      Version: node.etag,
      OwnerId: node.owner,
      UserId: t.u,
      UserFriendlyName: u?.display_name || t.u,
      LastModifiedTime: iso(node.mtime),
      UserCanWrite: canWrite,
      ReadOnly: !canWrite,
      UserCanNotWriteRelative: true, // no "Save as" into other folders
      UserCanRename: false,
      SupportsUpdate: true,
      SupportsLocks: false,
      SupportsRename: false,
      PostMessageOrigin: t.o,
      EnableOwnerTermination: false,
    });
    return true;
  }

  if (m[2] && req.method === 'GET') { // GetFile
    res.writeHead(200, {
      'Content-Type': 'application/octet-stream',
      'Content-Length': node.size,
      'X-WOPI-ItemVersion': node.etag,
    });
    if (node.blob && node.size) createReadStream(F.blobPath(node.blob)).pipe(res);
    else res.end();
    return true;
  }

  if (m[2] && req.method === 'POST' && req.headers['x-wopi-override'] === 'PUT') { // PutFile
    if (!canWrite) throw new HttpError(401, 'Read-only');
    // Someone else changed the file since Collabora loaded it: let the user choose (COOL status 1010).
    const seen = req.headers['x-cool-wopi-timestamp'] ?? req.headers['x-lool-wopi-timestamp'];
    if (seen && seen !== iso(node.mtime)) {
      json(res, { COOLStatusCode: 1010 }, 409);
      return true;
    }
    const parent = await F.mustNode(node.parent_id!);
    const { file, size } = await F.streamToTemp(req);
    const { node: saved } = await F.writeFile(parent, node.name, file, size);
    json(res, { LastModifiedTime: iso(saved.mtime) });
    return true;
  }

  // Locks, PutRelativeFile, RenameFile: not offered in CheckFileInfo.
  throw new HttpError(501, 'Not implemented');
}

// ---------------------------------------------------------------- browser → Collabora proxy (HTTP + WebSocket)

const PROXIED = /^\/(browser|cool|lool)\/|^\/hosting\/(discovery|capabilities)$/;
// Collabora endpoints that must not be public: anonymous conversion/thumbnail REST APIs and the admin console.
const BLOCKED = /^\/(cool|lool)\/(convert-to|extract-link-targets|extract-document-structure|transform-document-structure|get-thumbnail|render-search-result|getMetrics|adminws)|^\/browser\/[^/]+\/admin|^\/browser\/dist\/admin/i;

export const isCollaboraPath = (pathname: string) => config.office && PROXIED.test(pathname) && !BLOCKED.test(pathname);

function upstreamOptions(req: IncomingMessage): http.RequestOptions {
  const u = new URL(config.collaboraUrl);
  return {
    hostname: u.hostname, port: u.port || 80, method: req.method, path: req.url,
    // Keep the browser's Host so Collabora builds its URLs (and wss://) for the public address.
    headers: { ...req.headers, 'x-forwarded-for': req.socket.remoteAddress ?? '' },
  };
}

/** Collabora names the WOPI host (127.0.0.1) in frame-ancestors; the real embedder is our own origin. */
function fixHeaders(headers: http.IncomingHttpHeaders): http.OutgoingHttpHeaders {
  const out: http.OutgoingHttpHeaders = { ...headers };
  delete out['x-frame-options'];
  const csp = out['content-security-policy'];
  if (typeof csp === 'string') out['content-security-policy'] = csp.replace(/frame-ancestors[^;]*/i, "frame-ancestors 'self'");
  return out;
}

export function proxyCollabora(req: IncomingMessage, res: ServerResponse): void {
  const up = http.request(upstreamOptions(req), (ur) => {
    res.writeHead(ur.statusCode ?? 502, fixHeaders(ur.headers));
    ur.pipe(res);
  });
  up.on('error', () => {
    if (res.headersSent) return void res.destroy();
    res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('The office editor is starting or not available. Try again in a moment.\n');
  });
  req.pipe(up);
}

export function proxyCollaboraUpgrade(req: IncomingMessage, socket: Duplex, head: Buffer): void {
  const up = http.request(upstreamOptions(req));
  up.on('upgrade', (ur, upSocket, upHead) => {
    const lines = [`HTTP/1.1 ${ur.statusCode} ${ur.statusMessage}`];
    for (let i = 0; i < ur.rawHeaders.length; i += 2) lines.push(`${ur.rawHeaders[i]}: ${ur.rawHeaders[i + 1]}`);
    socket.write(lines.join('\r\n') + '\r\n\r\n');
    if (upHead.length) socket.write(upHead);
    if (head.length) upSocket.write(head);
    upSocket.pipe(socket).pipe(upSocket);
    upSocket.on('error', () => socket.destroy());
    socket.on('error', () => upSocket.destroy());
  });
  up.on('response', (ur) => { // upstream refused the upgrade
    socket.end(`HTTP/1.1 ${ur.statusCode} ${ur.statusMessage}\r\nConnection: close\r\n\r\n`);
  });
  up.on('error', () => socket.destroy());
  up.end();
}
