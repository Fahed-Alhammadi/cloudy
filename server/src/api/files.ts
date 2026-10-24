import { createWriteStream } from 'node:fs';
import { writeFile as fsWriteFile, truncate, rm } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { Router, Ctx } from '../http.ts';
import { json, noContent, readJson, checkName, HttpError } from '../http.ts';
import { config } from '../config.ts';
import { q, one } from '../db.ts';
import type { User } from '../auth.ts';
import { newToken } from '../auth.ts';
import * as F from '../files.ts';
import { sendNodeFile, sendBlob, sendZip } from '../download.ts';

type Authed = (ctx: Ctx, user: User) => Promise<void>;
type Wrap = (h: Authed) => (ctx: Ctx) => Promise<void>;

export function registerFiles(r: Router, authed: Wrap) {
  r.get('/api/nodes/:id', authed(async ({ res, params }, user) => {
    const { node, acc } = await F.requireAccess(user.id, params.id, 'read');
    const [self] = await F.toJson([node], user.id);
    json(res, {
      node: self,
      perm: acc.perm,
      breadcrumb: await F.breadcrumb(node, acc),
      children: node.is_dir ? await F.toJson(await F.children(node.id), user.id) : undefined,
    });
  }));

  r.post('/api/nodes/:id/folders', authed(async ({ req, res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'edit');
    const dir = await F.createDir(node, checkName((await readJson(req)).name));
    json(res, (await F.toJson([dir], user.id))[0], 201);
  }));

  // New (text) file with optional initial content.
  r.post('/api/nodes/:id/files', authed(async ({ req, res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'edit');
    const b = await readJson(req);
    const name = checkName(b.name);
    if (await F.childByName(node.id, name)) throw new HttpError(409, 'An item with that name already exists');
    const file = F.tmpPath(randomUUID());
    const content = Buffer.from(String(b.content ?? ''), 'utf8');
    await fsWriteFile(file, content);
    const { node: created } = await F.writeFile(node, name, file, content.length);
    json(res, (await F.toJson([created], user.id))[0], 201);
  }));

  // Replace a file's content (text editor save).
  r.put('/api/nodes/:id/content', authed(async ({ req, res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'edit');
    if (node.is_dir) throw new HttpError(400, 'Not a file');
    const parent = await F.mustNode(node.parent_id!);
    const { file, size } = await F.streamToTemp(req);
    const { node: saved } = await F.writeFile(parent, node.name, file, size);
    json(res, (await F.toJson([saved], user.id))[0]);
  }));

  // Rename and/or move.
  r.patch('/api/nodes/:id', authed(async ({ req, res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'read');
    await F.requireParentEdit(user.id, node);
    const b = await readJson(req);
    const dest = b.parentId != null
      ? (await F.requireAccess(user.id, b.parentId, 'edit')).node
      : await F.mustNode(node.parent_id!);
    const name = b.name != null ? checkName(b.name) : node.name;
    const moved = await F.moveNode(node, dest, name);
    json(res, (await F.toJson([moved], user.id))[0]);
  }));

  r.post('/api/nodes/:id/copy', authed(async ({ req, res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'read');
    const b = await readJson(req);
    const { node: dest } = await F.requireAccess(user.id, b.parentId, 'edit');
    const name = b.name != null ? checkName(b.name) : await F.uniqueName(dest.id, node.name);
    const copy = await F.copyNode(node, dest, name);
    json(res, (await F.toJson([copy], user.id))[0], 201);
  }));

  r.delete('/api/nodes/:id', authed(async ({ res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'read');
    await F.requireParentEdit(user.id, node);
    await F.trashNode(node);
    noContent(res);
  }));

  r.get('/api/nodes/:id/download', authed(async ({ req, res, params, url }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'read');
    if (node.is_dir) await sendZip(res, node);
    else sendNodeFile(req, res, node, url.searchParams.has('inline'));
  }));

  r.post('/api/nodes/:id/favorite', authed(async ({ res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'read');
    await q(`INSERT INTO favorites (user_id, node_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, [user.id, node.id]);
    noContent(res);
  }));

  r.delete('/api/nodes/:id/favorite', authed(async ({ res, params }, user) => {
    await q(`DELETE FROM favorites WHERE user_id = $1 AND node_id = $2`, [user.id, params.id]);
    noContent(res);
  }));

  // ---------------------------------------------------------------- versions
  r.get('/api/nodes/:id/versions', authed(async ({ res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'read');
    json(res, (await F.listVersions(node.id)).map((v) => ({ id: v.id, size: v.size, mtime: v.mtime })));
  }));

  r.post('/api/nodes/:id/versions/:vid/restore', authed(async ({ res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'edit');
    json(res, (await F.toJson([await F.restoreVersion(node, params.vid)], user.id))[0]);
  }));

  r.get('/api/nodes/:id/versions/:vid/download', authed(async ({ req, res, params }, user) => {
    const { node } = await F.requireAccess(user.id, params.id, 'read');
    const v = (await F.listVersions(node.id)).find((x) => String(x.id) === params.vid);
    if (!v) throw new HttpError(404, 'Version not found');
    sendBlob(req, res, v.blob, { size: v.size, mime: node.mime, etag: v.blob, mtime: v.mtime, name: node.name });
  }));

  // ---------------------------------------------------------------- views
  r.get('/api/search', authed(async ({ res, url }, user) => {
    const text = (url.searchParams.get('q') ?? '').trim();
    if (!text) return json(res, []);
    const nodes = await F.searchNodes(user.id, text);
    const out = await F.toJson(nodes, user.id);
    for (const item of out) (item as any).path = await F.nodePath(item.parentId!);
    json(res, out);
  }));

  r.get('/api/recent', authed(async ({ res }, user) => json(res, await F.toJson(await F.recentFiles(user.id), user.id))));
  r.get('/api/favorites', authed(async ({ res }, user) => json(res, await F.toJson(await F.favoriteNodes(user.id), user.id))));

  // ---------------------------------------------------------------- trash
  r.get('/api/trash', authed(async ({ res }, user) => {
    json(res, (await F.listTrash(user.id)).map((n) => ({
      id: n.id, name: n.name, isDir: n.is_dir, size: n.size, mime: n.mime,
      deletedAt: n.deleted_at, originalPath: n.trash_path,
    })));
  }));

  const ownTrashed = async (user: User, id: string) => {
    const n = await F.getNode(id);
    if (!n || n.owner !== user.id || !n.trashed_root) throw new HttpError(404, 'Not in trash');
    return n;
  };

  r.post('/api/trash/:id/restore', authed(async ({ res, params }, user) => {
    const restored = await F.restoreNode(await ownTrashed(user, params.id));
    json(res, (await F.toJson([restored], user.id))[0]);
  }));

  r.delete('/api/trash/:id', authed(async ({ res, params }, user) => {
    await F.purgeNode((await ownTrashed(user, params.id)).id);
    noContent(res);
  }));

  r.delete('/api/trash', authed(async ({ res }, user) => {
    for (const n of await F.listTrash(user.id)) await F.purgeNode(n.id);
    noContent(res);
  }));

  // ---------------------------------------------------------------- chunked uploads
  r.post('/api/uploads', authed(async ({ req, res }, user) => {
    const b = await readJson(req);
    const { node: parent } = await F.requireAccess(user.id, b.parentId, 'edit');
    json(res, await startUpload(parent, b.name, b.size), 201);
  }));
}

export async function startUpload(parent: F.Node, rawName: unknown, rawSize: unknown) {
  if (!parent.is_dir) throw new HttpError(400, 'Parent is not a folder');
  const name = checkName(rawName);
  const size = Number(rawSize);
  if (!Number.isSafeInteger(size) || size < 0) throw new HttpError(400, 'Invalid size');
  const existing = await F.childByName(parent.id, name);
  if (existing?.is_dir) throw new HttpError(409, 'A folder with that name exists');
  const id = newToken(24);
  await fsWriteFile(F.uploadPath(id), '');
  await q(`INSERT INTO uploads (id, owner, parent_id, name, size) VALUES ($1, $2, $3, $4, $5)`,
    [id, parent.owner, parent.id, name, size]);
  return { id, chunkSize: config.chunkSize, received: 0, size };
}

/**
 * Upload ids are 192-bit random capabilities issued only after an access
 * check, so the chunk endpoints need no further authentication. This also
 * lets public "upload into link" shares reuse them.
 */
export function registerUploadChunks(r: Router) {
  const busy = new Set<string>();

  r.get('/api/uploads/:id', async ({ res, params }) => {
    const u = await one(`SELECT received, size FROM uploads WHERE id = $1`, [params.id]);
    if (!u) throw new HttpError(404, 'Upload not found');
    json(res, u);
  });

  r.put('/api/uploads/:id', async ({ req, res, params }) => {
    const u = await one<{ id: string; parent_id: number; name: string; size: number; received: number }>(
      `SELECT id, parent_id, name, size, received FROM uploads WHERE id = $1`, [params.id]);
    if (!u) throw new HttpError(404, 'Upload not found');
    if (busy.has(u.id)) throw new HttpError(409, 'Chunk already in progress');
    const offset = Number(req.headers['upload-offset'] ?? u.received);
    if (offset !== u.received) return json(res, { error: 'Offset mismatch', received: u.received }, 409);

    busy.add(u.id);
    const file = F.uploadPath(u.id);
    let received = u.received;
    try {
      const limit = new Transform({
        transform(chunk, _e, cb) {
          received += chunk.length;
          cb(received > u.size ? new HttpError(400, 'More data than declared') : null, chunk);
        },
      });
      await pipeline(req, limit, createWriteStream(file, { flags: 'a' }));
    } catch (e) {
      await truncate(file, u.received).catch(() => {});
      throw e instanceof HttpError ? e : new HttpError(400, 'Upload interrupted');
    } finally {
      busy.delete(u.id);
    }
    await q(`UPDATE uploads SET received = $2 WHERE id = $1`, [u.id, received]);

    if (received < u.size) return json(res, { received, done: false });
    const parent = await F.getNode(u.parent_id);
    await q(`DELETE FROM uploads WHERE id = $1`, [u.id]);
    if (!parent || parent.deleted_at) {
      await rm(file, { force: true });
      throw new HttpError(409, 'Target folder no longer exists');
    }
    const { node } = await F.writeFile(parent, u.name, file, received);
    json(res, { received, done: true, node: { id: node.id, name: node.name, size: node.size } });
  });

  r.delete('/api/uploads/:id', async ({ res, params }) => {
    await q(`DELETE FROM uploads WHERE id = $1`, [params.id]);
    await rm(F.uploadPath(params.id), { force: true });
    noContent(res);
  });
}

export async function expireUploads(): Promise<void> {
  const old = await q<{ id: string }>(`DELETE FROM uploads WHERE created_at < now() - interval '1 day' RETURNING id`);
  await Promise.all(old.map((u) => rm(F.uploadPath(u.id), { force: true })));
}
