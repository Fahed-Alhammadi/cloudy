import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, open, rename, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import type { Router, Ctx } from '../http.ts';
import { json, noContent, HttpError } from '../http.ts';
import { config } from '../config.ts';
import { q, one } from '../db.ts';
import type { User } from '../auth.ts';
import { streamToTemp } from '../files.ts';
import { contentDisposition } from '../download.ts';

type Authed = (ctx: Ctx, user: User) => Promise<void>;
type Wrap = (h: Authed) => (ctx: Ctx) => Promise<void>;

interface Release {
  id: number;
  version_code: number;
  version_name: string;
  notes: string;
  size: number;
  sha256: string;
  file: string;
  created_at: Date;
}

const releaseDir = () => path.join(config.dataDir, 'releases');
const KEEP_RELEASES = 3;
const MAX_APK = 500 * 1024 * 1024;

const releaseJson = (r: Release) => ({
  id: r.id,
  versionCode: r.version_code,
  versionName: r.version_name,
  notes: r.notes,
  size: r.size,
  sha256: r.sha256,
  url: '/download/android',
  publishedAt: r.created_at,
});

async function latestAndroid(): Promise<Release | undefined> {
  return one<Release>(`SELECT * FROM app_releases WHERE platform = 'android' ORDER BY version_code DESC LIMIT 1`);
}

// ---------------------------------------------------------------- server update check

let cache: { at: number; data: any } | null = null;

/** Compares dotted versions ("1.10.0" > "1.9.2"). */
export function newer(a: string, b: string): boolean {
  const pa = a.replace(/^v/, '').split(/[.-]/).map((x) => parseInt(x, 10) || 0);
  const pb = b.replace(/^v/, '').split(/[.-]/).map((x) => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) > (pb[i] ?? 0);
  }
  return false;
}

async function checkServerUpdate(force: boolean) {
  const base = { version: config.version, repo: config.updateRepo || null };
  if (!config.updateRepo) return { ...base, configured: false };
  if (!force && cache && Date.now() - cache.at < 3600_000) return cache.data;
  let data;
  try {
    const res = await fetch(`https://api.github.com/repos/${config.updateRepo}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'FileCloud' },
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) throw new Error(`GitHub answered HTTP ${res.status}`);
    const r = await res.json();
    const latest = String(r.tag_name ?? '').replace(/^v/, '');
    data = { ...base, configured: true, latest, updateAvailable: !!latest && newer(latest, config.version), url: r.html_url, notes: String(r.body ?? '').slice(0, 4000), checkedAt: new Date() };
  } catch (e: any) {
    data = { ...base, configured: true, error: e.message, checkedAt: new Date() };
  }
  cache = { at: Date.now(), data };
  return data;
}

// ---------------------------------------------------------------- routes

export function registerUpdates(r: Router, admin: Wrap) {
  r.get('/api/admin/updates', admin(async ({ res, url }) => {
    json(res, await checkServerUpdate(url.searchParams.has('refresh')));
  }));

  // Public: lets the Android app (and the login page) discover the newest APK.
  r.get('/api/app/android', async ({ res }) => {
    const rel = await latestAndroid();
    if (!rel) throw new HttpError(404, 'No Android release published');
    json(res, releaseJson(rel));
  });

  r.get('/download/android', async ({ req, res }) => {
    const rel = await latestAndroid();
    if (!rel) throw new HttpError(404, 'No Android release published');
    const file = path.join(releaseDir(), rel.file);
    const st = await stat(file).catch(() => null);
    if (!st) throw new HttpError(404, 'Release file missing');
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': st.size,
      'Content-Disposition': contentDisposition('attachment', `FileCloud-${rel.version_name}.apk`),
      'X-Content-SHA256': rel.sha256,
      'Cache-Control': 'no-cache',
    });
    if (req.method === 'HEAD') return void res.end();
    createReadStream(file).pipe(res);
  });

  r.get('/api/admin/app/android', admin(async ({ res }) => {
    json(res, (await q<Release>(`SELECT * FROM app_releases WHERE platform = 'android' ORDER BY version_code DESC`)).map(releaseJson));
  }));

  // Body: the raw APK. Query: versionCode, versionName, notes.
  r.post('/api/admin/app/android', admin(async ({ req, res, url }) => {
    const versionCode = Number(url.searchParams.get('versionCode'));
    const versionName = (url.searchParams.get('versionName') ?? '').trim();
    const notes = (url.searchParams.get('notes') ?? '').slice(0, 4000);
    if (!Number.isInteger(versionCode) || versionCode < 1) throw new HttpError(400, 'versionCode must be a positive integer');
    if (!/^[\w.+-]{1,40}$/.test(versionName)) throw new HttpError(400, 'versionName required (e.g. 1.2.0)');
    const latest = await latestAndroid();
    if (latest && versionCode <= latest.version_code) {
      throw new HttpError(409, `versionCode must be greater than the latest published (${latest.version_code})`);
    }

    const { file: tmp, size } = await streamToTemp(req);
    try {
      if (size < 1024 || size > MAX_APK) throw new HttpError(400, 'Not a valid APK file');
      const fh = await open(tmp, 'r');
      const magic = Buffer.alloc(4);
      await fh.read(magic, 0, 4, 0);
      await fh.close();
      if (magic.readUInt32LE(0) !== 0x04034b50) throw new HttpError(400, 'Not a valid APK file (expected a ZIP archive)');
      const hash = createHash('sha256');
      for await (const chunk of createReadStream(tmp)) hash.update(chunk);
      const sha256 = hash.digest('hex');
      await mkdir(releaseDir(), { recursive: true });
      const name = `android-${versionCode}.apk`;
      await rename(tmp, path.join(releaseDir(), name));
      const rel = await one<Release>(
        `INSERT INTO app_releases (platform, version_code, version_name, notes, size, sha256, file)
         VALUES ('android', $1, $2, $3, $4, $5, $6) RETURNING *`,
        [versionCode, versionName, notes, size, sha256, name],
      );
      // Keep only the newest few releases on disk.
      const old = await q<Release>(
        `SELECT * FROM app_releases WHERE platform = 'android' ORDER BY version_code DESC OFFSET $1`, [KEEP_RELEASES]);
      for (const o of old) {
        await rm(path.join(releaseDir(), o.file), { force: true });
        await q(`DELETE FROM app_releases WHERE id = $1`, [o.id]);
      }
      json(res, releaseJson(rel!), 201);
    } finally {
      await rm(tmp, { force: true });
    }
  }));

  r.delete('/api/admin/app/android/:id', admin(async ({ res, params }) => {
    const rel = await one<Release>(`DELETE FROM app_releases WHERE id = $1 RETURNING *`, [params.id]);
    if (rel) await rm(path.join(releaseDir(), rel.file), { force: true });
    noContent(res);
  }));
}
