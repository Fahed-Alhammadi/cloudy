import type { IncomingMessage, ServerResponse } from 'node:http';
import { config } from './config.ts';

export class HttpError extends Error {
  status: number;
  /** Extra fields returned with the JSON error (e.g. { twoFactor: true }). */
  data?: Record<string, unknown>;
  constructor(status: number, message: string, data?: Record<string, unknown>) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

export interface Ctx {
  req: IncomingMessage;
  res: ServerResponse;
  url: URL;
  params: Record<string, string>;
}

type Handler = (ctx: Ctx) => Promise<void> | void;

interface Route {
  method: string;
  re: RegExp;
  keys: string[];
  handler: Handler;
}

/** Minimal router: patterns like "/api/nodes/:id/shares". */
export class Router {
  routes: Route[] = [];

  add(method: string, pattern: string, handler: Handler): void {
    const keys: string[] = [];
    const re = new RegExp(
      '^' + pattern.replace(/:(\w+)/g, (_, k) => (keys.push(k), '([^/]+)')) + '$',
    );
    this.routes.push({ method, re, keys, handler });
  }
  get(p: string, h: Handler) { this.add('GET', p, h); }
  post(p: string, h: Handler) { this.add('POST', p, h); }
  put(p: string, h: Handler) { this.add('PUT', p, h); }
  patch(p: string, h: Handler) { this.add('PATCH', p, h); }
  delete(p: string, h: Handler) { this.add('DELETE', p, h); }

  match(method: string, path: string): { handler: Handler; params: Record<string, string> } | null {
    let pathMatched = false;
    for (const r of this.routes) {
      const m = r.re.exec(path);
      if (!m) continue;
      pathMatched = true;
      if (r.method !== method && !(method === 'HEAD' && r.method === 'GET')) continue;
      const params: Record<string, string> = {};
      r.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
      return { handler: r.handler, params };
    }
    if (pathMatched) throw new HttpError(405, 'Method not allowed');
    return null;
  }
}

export function json(res: ServerResponse, data: unknown, status = 200): void {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

export function noContent(res: ServerResponse): void {
  res.writeHead(204);
  res.end();
}

export async function readBuffer(req: IncomingMessage, limit = 10 * 1024 * 1024): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > limit) throw new HttpError(413, 'Request body too large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

export async function readText(req: IncomingMessage, limit?: number): Promise<string> {
  return (await readBuffer(req, limit)).toString('utf8');
}

export async function readJson<T = any>(req: IncomingMessage): Promise<T> {
  const text = await readText(req, 1024 * 1024);
  if (!text) return {} as T;
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, 'Invalid JSON');
  }
}

export function parseCookies(req: IncomingMessage): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (req.headers.cookie ?? '').split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}

export function isHttps(req: IncomingMessage): boolean {
  if (config.trustProxy) return req.headers['x-forwarded-proto'] === 'https';
  return 'encrypted' in req.socket && !!req.socket.encrypted;
}

export function setCookie(
  req: IncomingMessage,
  res: ServerResponse,
  name: string,
  value: string,
  maxAgeSec: number,
): void {
  const parts = [
    `${name}=${encodeURIComponent(value)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${maxAgeSec}`,
  ];
  if (isHttps(req)) parts.push('Secure');
  const prev = res.getHeader('Set-Cookie');
  const list = Array.isArray(prev) ? prev : prev ? [String(prev)] : [];
  res.setHeader('Set-Cookie', [...list, parts.join('; ')]);
}

/**
 * Origin the browser sees, e.g. https://cloud.example.com. PUBLIC_URL wins; otherwise Host
 * (or X-Forwarded-Host behind a trusted proxy) with the request's scheme.
 */
export function publicOrigin(req: IncomingMessage): string {
  if (config.publicUrl) return new URL(config.publicUrl).origin;
  const fwd = config.trustProxy ? String(req.headers['x-forwarded-host'] ?? '').split(',')[0].trim() : '';
  const host = fwd || req.headers.host || 'localhost';
  return new URL(`${isHttps(req) ? 'https' : 'http'}://${host}`).origin;
}

/** Client IP for rate limiting; forwarded headers are spoofable, so only trusted behind a proxy. */
export function clientIp(req: IncomingMessage): string {
  if (config.trustProxy) {
    // The proxy appends the real client address last.
    const fwd = String(req.headers['x-forwarded-for'] ?? '').split(',').map((s) => s.trim()).filter(Boolean).pop();
    if (fwd) return fwd;
  }
  return req.socket.remoteAddress || 'unknown';
}

/** Validates a single file/folder name. */
export function checkName(name: unknown): string {
  if (typeof name !== 'string') throw new HttpError(400, 'Name required');
  const n = name.trim();
  if (!n || n === '.' || n === '..' || n.length > 255 || /[\/\\\0]/.test(n)) {
    throw new HttpError(400, 'Invalid name');
  }
  return n;
}
