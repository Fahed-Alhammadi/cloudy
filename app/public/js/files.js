// Files app: folder browser, special lists (recent, favorites, shares, search, trash) and dialogs.
import { get, post, patch, del, api } from './api.js';
import {
  h, icon, fileIcon, formatSize, formatDate, fullDate, toast, showError, modal, confirmDialog,
  promptDialog, showMenu, copyText,
} from './ui.js';
import { openViewer, kindOf } from './viewer.js';
import { uploadAll, filesFromDrop, filesFromInput } from './upload.js';

const nodeUrl = (node, inline) => `/api/nodes/${node.id}/download${inline ? '?inline' : ''}`;

export function userUploadTarget(parentId) {
  return {
    parentId,
    startUrl: '/api/uploads',
    listChildren: async (id) => (await get(`/api/nodes/${id}`)).children,
    mkdir: (id, name) => post(`/api/nodes/${id}/folders`, { name }),
  };
}

// ---------------------------------------------------------------- generic list

/**
 * Renders a file table.
 * opts: { items, me, canModify(item), onOpen(item), extraColumn?: {title, render(item)},
 *         reload(), dropTargetId?, emptyText, selectable }
 */
function fileTable(opts) {
  const selected = new Set();
  const bar = h('div', { class: 'selection-bar', hidden: true });
  const tbody = h('tbody');
  const allBox = h('input', { type: 'checkbox', 'aria-label': 'Select all' });

  const updateBar = () => {
    bar.hidden = selected.size === 0;
    allBox.checked = selected.size > 0 && selected.size === opts.items.length;
    bar.replaceChildren(
      h('span', {}, `${selected.size} selected`),
      h('button', { class: 'btn ghost', onclick: () => {
        for (const it of opts.items.filter((i) => selected.has(i.id))) triggerDownload(nodeUrl(it, false), it.name + (it.isDir ? '.zip' : ''));
      } }, icon('download'), 'Download'),
      opts.items.some((i) => selected.has(i.id) && opts.canModify(i)) && h('button', { class: 'btn ghost', onclick: () => moveCopy(opts.items.filter((i) => selected.has(i.id)), opts) }, icon('move'), 'Move or copy'),
      opts.items.some((i) => selected.has(i.id) && opts.canModify(i)) && h('button', { class: 'btn ghost danger', onclick: async () => {
        const list = opts.items.filter((i) => selected.has(i.id) && opts.canModify(i));
        if (!(await confirmDialog('Delete items', `Move ${list.length} item(s) to the trash?`, 'Delete', true))) return;
        for (const it of list) await del(`/api/nodes/${it.id}`).catch(showError);
        opts.reload();
      } }, icon('trash'), 'Delete'),
    );
  };
  allBox.addEventListener('change', () => {
    selected.clear();
    if (allBox.checked) opts.items.forEach((i) => selected.add(i.id));
    tbody.querySelectorAll('input[type=checkbox]').forEach((c) => (c.checked = allBox.checked));
    updateBar();
  });

  for (const item of opts.items) {
    const box = h('input', { type: 'checkbox', 'aria-label': `Select ${item.name}`, onclick: (e) => e.stopPropagation(), onchange: (e) => {
      e.target.checked ? selected.add(item.id) : selected.delete(item.id);
      updateBar();
    } });
    const more = h('button', { class: 'icon-btn', 'aria-label': `Actions for ${item.name}`, onclick: (e) => {
      e.stopPropagation();
      showMenu(e.currentTarget, nodeActions(item, opts));
    } }, icon('more'));
    const tr = h('tr', {
      class: 'file-row', tabindex: 0, draggable: opts.canModify(item) ? 'true' : null,
      onclick: () => opts.onOpen(item),
      onkeydown: (e) => { if (e.key === 'Enter') opts.onOpen(item); },
      oncontextmenu: (e) => { e.preventDefault(); showMenu({ x: e.clientX, y: e.clientY }, nodeActions(item, opts)); },
    },
      h('td', { class: 'col-check' }, box),
      h('td', { class: 'col-name' },
        h('div', { class: 'name-cell' }, fileIcon(item),
          h('span', { class: 'name', title: item.name }, item.name),
          item.favorite && icon('star', 'badge fav'),
          item.shared && icon('share', 'badge'),
        )),
      opts.extraColumn ? h('td', { class: 'col-extra' }, opts.extraColumn.render(item)) : null,
      h('td', { class: 'col-size' }, formatSize(item.size)),
      h('td', { class: 'col-date', title: fullDate(item.mtime) }, formatDate(item.mtime)),
      h('td', { class: 'col-actions' }, more),
    );
    // Drag rows onto folders to move them.
    tr.addEventListener('dragstart', (e) => {
      const ids = selected.has(item.id) ? [...selected] : [item.id];
      e.dataTransfer.setData('application/x-cloudy', JSON.stringify(ids));
      e.dataTransfer.effectAllowed = 'move';
    });
    if (item.isDir) {
      tr.addEventListener('dragover', (e) => {
        if (e.dataTransfer.types.includes('application/x-cloudy')) { e.preventDefault(); tr.classList.add('drop-target'); }
      });
      tr.addEventListener('dragleave', () => tr.classList.remove('drop-target'));
      tr.addEventListener('drop', async (e) => {
        const raw = e.dataTransfer.getData('application/x-cloudy');
        tr.classList.remove('drop-target');
        if (!raw) return;
        e.preventDefault();
        e.stopPropagation();
        for (const id of JSON.parse(raw)) {
          if (id === item.id) continue;
          await patch(`/api/nodes/${id}`, { parentId: item.id }).catch(showError);
        }
        opts.reload();
      });
    }
    tbody.append(tr);
  }

  const table = h('table', { class: 'file-table' },
    h('thead', {}, h('tr', {},
      h('th', { class: 'col-check' }, allBox),
      h('th', { class: 'col-name' }, 'Name'),
      opts.extraColumn ? h('th', { class: 'col-extra' }, opts.extraColumn.title) : null,
      h('th', { class: 'col-size' }, 'Size'),
      h('th', { class: 'col-date' }, 'Modified'),
      h('th', { class: 'col-actions' }, h('span', { class: 'sr-only' }, 'Actions')),
    )),
    tbody,
  );
  if (!opts.items.length) {
    return h('div', {}, h('div', { class: 'empty' }, icon(opts.emptyIcon || 'folder'), h('p', {}, opts.emptyText || 'Nothing here yet')));
  }
  return h('div', {}, bar, table);
}

function triggerDownload(url, name) {
  const a = h('a', { href: url, download: name || '' });
  document.body.append(a);
  a.click();
  a.remove();
}

function nodeActions(item, opts) {
  const { me } = opts;
  const isOwner = item.owner === me.id;
  return [
    { label: item.isDir ? 'Open' : 'View', icon: item.isDir ? 'folder' : 'eye', onclick: () => opts.onOpen(item) },
    { label: item.isDir ? 'Download as ZIP' : 'Download', icon: 'download', onclick: () => triggerDownload(nodeUrl(item, false), item.name) },
    opts.canModify(item) && { label: 'Rename', icon: 'edit', onclick: () => rename(item, opts.reload) },
    opts.canModify(item) && { label: 'Move or copy', icon: 'move', onclick: () => moveCopy([item], opts) },
    !opts.canModify(item) && { label: 'Copy to my files', icon: 'copy', onclick: () => moveCopy([item], { ...opts, copyOnly: true }) },
    isOwner && { label: 'Share', icon: 'share', onclick: () => shareDialog(item, opts.reload) },
    !item.isDir && { label: 'Versions', icon: 'history', onclick: () => versionsDialog(item, opts) },
    { label: item.favorite ? 'Remove from favorites' : 'Add to favorites', icon: 'star', onclick: async () => {
      await (item.favorite ? del : post)(`/api/nodes/${item.id}/favorite`).catch(showError);
      opts.reload();
    } },
    item.shareId && item.shareType === 'user' && { label: 'Leave share', icon: 'logout', danger: true, onclick: async () => {
      if (!(await confirmDialog('Leave share', `Remove "${item.name}" from your shared items?`, 'Leave', true))) return;
      await del(`/api/shares/${item.shareId}`).catch(showError);
      opts.reload();
    } },
    opts.canModify(item) && '-',
    opts.canModify(item) && { label: 'Delete', icon: 'trash', danger: true, onclick: async () => {
      if (!(await confirmDialog('Delete', `Move "${item.name}" to the trash?`, 'Delete', true))) return;
      try {
        await del(`/api/nodes/${item.id}`);
        toast(`"${item.name}" moved to trash`);
        opts.reload();
      } catch (e) { showError(e); }
    } },
  ];
}

async function rename(item, reload) {
  const name = await promptDialog('Rename', 'New name', item.name, 'Rename');
  if (!name || name === item.name) return;
  try {
    await patch(`/api/nodes/${item.id}`, { name });
    reload();
  } catch (e) { showError(e); }
}

// ---------------------------------------------------------------- dialogs

async function moveCopy(items, opts) {
  let current = opts.me.rootId;
  const list = h('ul', { class: 'picker-list' });
  const crumbs = h('nav', { class: 'breadcrumb small' });
  const load = async () => {
    const data = await get(`/api/nodes/${current}`);
    crumbs.replaceChildren(...data.breadcrumb.map((c, i) => [
      i ? h('span', { class: 'sep' }, '/') : null,
      h('button', { type: 'button', class: 'crumb', onclick: () => { current = c.id; load(); } }, c.name || 'Files'),
    ]));
    const dirs = data.children.filter((c) => c.isDir && !items.some((i) => i.id === c.id));
    list.replaceChildren(...(dirs.length ? dirs.map((d) => h('li', {},
      h('button', { type: 'button', onclick: () => { current = d.id; load(); } }, icon('folder', 'ic-folder'), d.name))) : [h('li', { class: 'muted' }, 'No subfolders')]));
  };
  await load();
  const buttons = [{ label: 'Cancel', value: null }, { label: 'Copy here', value: 'copy', primary: !!opts.copyOnly }];
  if (!opts.copyOnly) buttons.push({ label: 'Move here', value: 'move', primary: true });
  const choice = await modal({ title: `${opts.copyOnly ? 'Copy' : 'Move or copy'} ${items.length === 1 ? `"${items[0].name}"` : `${items.length} items`}`, body: h('div', {}, crumbs, list), buttons });
  if (!choice) return;
  for (const it of items) {
    try {
      if (choice === 'move') await patch(`/api/nodes/${it.id}`, { parentId: current });
      else await post(`/api/nodes/${it.id}/copy`, { parentId: current });
    } catch (e) { showError(e); }
  }
  toast(choice === 'move' ? 'Moved' : 'Copied');
  opts.reload();
}

async function versionsDialog(item, opts) {
  const body = h('div', {});
  const load = async () => {
    const versions = await get(`/api/nodes/${item.id}/versions`);
    body.replaceChildren(
      h('p', { class: 'muted' }, `Current version: ${formatSize(item.size)}, ${fullDate(item.mtime)}`),
      versions.length ? h('ul', { class: 'plain-list' }, versions.map((v) => h('li', {},
        icon('history'),
        h('span', { class: 'grow' }, fullDate(v.mtime), h('span', { class: 'muted' }, ` · ${formatSize(v.size)}`)),
        h('a', { class: 'btn ghost', href: `/api/nodes/${item.id}/versions/${v.id}/download`, download: item.name }, 'Download'),
        opts.canModify(item) || item.owner === opts.me.id ? h('button', { type: 'button', class: 'btn', onclick: async () => {
          try {
            Object.assign(item, await post(`/api/nodes/${item.id}/versions/${v.id}/restore`));
            toast('Version restored');
            await load();
            opts.reload();
          } catch (e) { showError(e); }
        } }, 'Restore') : null,
      ))) : h('p', {}, 'No older versions. A version is kept each time the file is overwritten.'),
    );
  };
  await load();
  await modal({ title: `Versions of "${item.name}"`, body, wide: true });
}

export async function shareDialog(item, reload) {
  const body = h('div', { class: 'share-dialog' });
  const datalist = h('datalist', { id: 'sharee-options' });
  const input = h('input', { type: 'text', list: 'sharee-options', placeholder: 'Username or group:name', autocomplete: 'off', 'aria-label': 'Share with' });
  const canEditNew = h('input', { type: 'checkbox' });
  let timer;
  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const { users, groups } = await get(`/api/sharees?q=${encodeURIComponent(input.value.replace(/^group:/, ''))}`);
      datalist.replaceChildren(
        ...users.map((u) => h('option', { value: u.id }, u.displayName)),
        ...groups.map((g) => h('option', { value: `group:${g.id}` }, `Group ${g.id}`)),
      );
    }, 200);
  });

  const add = async () => {
    const v = input.value.trim();
    if (!v) return;
    const isGroup = v.startsWith('group:');
    try {
      await post(`/api/nodes/${item.id}/shares`, { type: isGroup ? 'group' : 'user', shareWith: isGroup ? v.slice(6) : v, canEdit: canEditNew.checked });
      input.value = '';
      await refresh();
    } catch (e) { showError(e); }
  };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } });

  const shareRow = (s) => h('li', {},
    icon(s.type === 'group' ? 'users' : 'contacts'),
    h('span', { class: 'grow' }, s.type === 'group' ? `Group ${s.shareWith}` : s.shareWith),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: s.canEdit, onchange: async (e) => {
      await patch(`/api/shares/${s.id}`, { canEdit: e.target.checked }).catch(showError);
    } }), 'Can edit'),
    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Remove share', onclick: async () => { await del(`/api/shares/${s.id}`).catch(showError); refresh(); } }, icon('x')),
  );

  const linkCard = (s) => {
    const url = location.origin + s.url;
    const pw = h('input', { type: 'password', placeholder: s.hasPassword ? '•••••• (set)' : 'No password', autocomplete: 'new-password', 'aria-label': 'Link password' });
    const expiry = h('input', { type: 'date', value: s.expiresAt ? s.expiresAt.slice(0, 10) : '', 'aria-label': 'Expiry date', onchange: async (e) => {
      await patch(`/api/shares/${s.id}`, { expiresAt: e.target.value ? `${e.target.value}T23:59:59` : null }).then(() => toast('Expiry updated')).catch(showError);
    } });
    return h('li', { class: 'link-card' },
      h('div', { class: 'link-row' }, icon('link'), h('input', { type: 'text', readonly: true, value: url, 'aria-label': 'Link URL', onclick: (e) => e.target.select() }),
        h('button', { type: 'button', class: 'btn', onclick: () => copyText(url) }, icon('copy'), 'Copy'),
        h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Delete link', onclick: async () => { await del(`/api/shares/${s.id}`).catch(showError); refresh(); } }, icon('trash'))),
      h('div', { class: 'link-opts' },
        item.isDir && h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: s.canEdit, onchange: async (e) => {
          await patch(`/api/shares/${s.id}`, { canEdit: e.target.checked }).catch(showError);
        } }), 'Allow upload'),
        h('label', { class: 'inline' }, 'Expires', expiry),
        h('span', { class: 'inline' }, pw,
          h('button', { type: 'button', class: 'btn ghost', onclick: async () => {
            await patch(`/api/shares/${s.id}`, { password: pw.value || null }).then(() => { toast(pw.value ? 'Password set' : 'Password removed'); refresh(); }).catch(showError);
          } }, icon('lock'), 'Set')),
      ),
    );
  };

  async function refresh() {
    const shares = await get(`/api/nodes/${item.id}/shares`);
    const direct = shares.filter((s) => s.type !== 'link');
    const links = shares.filter((s) => s.type === 'link');
    body.replaceChildren(
      h('h3', {}, 'People and groups'),
      h('div', { class: 'share-add' }, input, datalist, h('label', { class: 'check' }, canEditNew, 'Can edit'), h('button', { type: 'button', class: 'btn primary', onclick: add }, 'Share')),
      direct.length ? h('ul', { class: 'plain-list' }, direct.map(shareRow)) : h('p', { class: 'muted' }, 'Not shared with anyone yet.'),
      h('h3', {}, 'Public links'),
      links.length ? h('ul', { class: 'plain-list' }, links.map(linkCard)) : h('p', { class: 'muted' }, 'Anyone with a link can view the item.'),
      h('button', { type: 'button', class: 'btn', onclick: async () => {
        try { await post(`/api/nodes/${item.id}/shares`, { type: 'link' }); await refresh(); } catch (e) { showError(e); }
      } }, icon('plus'), 'Create link'),
    );
  }
  await refresh();
  await modal({ title: `Share "${item.name}"`, body, wide: true });
  reload?.();
}

// ---------------------------------------------------------------- views

function openItem(item, siblings, opts) {
  if (item.isDir) return void (location.hash = `#/files/${item.id}`);
  const files = siblings.filter((s) => !s.isDir);
  openViewer(files, Math.max(0, files.findIndex((f) => f.id === item.id)), {
    url: nodeUrl,
    canEdit: opts.canModify(item) || item.owner === opts.me.id || opts.editable || !!item.canEdit,
    onSaved: opts.reload,
  });
}

export async function renderFolder(main, id, ctx) {
  const data = await get(`/api/nodes/${id}`);
  if (!data.node.isDir) {
    location.hash = `#/files/${data.node.parentId ?? ctx.me.rootId}`;
    return;
  }
  const canEdit = data.perm !== 'read';
  const reload = () => renderFolder(main, id, ctx).catch(showError);
  const opts = {
    items: data.children, me: ctx.me, reload, editable: canEdit,
    canModify: () => canEdit,
    onOpen: (item) => openItem(item, data.children, opts),
    emptyText: canEdit ? 'Drop files here or use the Upload button' : 'This folder is empty',
  };

  const fileInput = h('input', { type: 'file', multiple: true, hidden: true, onchange: (e) => { uploadAll(filesFromInput(e.target), userUploadTarget(id), reload); e.target.value = ''; } });
  const dirInput = h('input', { type: 'file', multiple: true, webkitdirectory: true, hidden: true, onchange: (e) => { uploadAll(filesFromInput(e.target), userUploadTarget(id), reload); e.target.value = ''; } });

  const crumbs = h('nav', { class: 'breadcrumb', 'aria-label': 'Folder path' },
    data.breadcrumb.map((c, i) => [
      i ? h('span', { class: 'sep' }, icon('right')) : null,
      h('a', { class: 'crumb', href: `#/files/${c.id}` }, i === 0 && c.name === '' ? 'Files' : c.name),
    ]));

  const toolbar = h('div', { class: 'toolbar' }, crumbs, h('div', { class: 'grow' }),
    canEdit && h('button', { class: 'btn', onclick: (e) => showMenu(e.currentTarget, [
      { label: 'New folder', icon: 'folderPlus', onclick: async () => {
        const name = await promptDialog('New folder', 'Folder name', 'New folder', 'Create');
        if (name) await post(`/api/nodes/${id}/folders`, { name }).then(reload, showError);
      } },
      { label: 'New text file', icon: 'filePlus', onclick: async () => {
        const name = await promptDialog('New text file', 'File name', 'Notes.md', 'Create');
        if (!name) return;
        try {
          const node = await post(`/api/nodes/${id}/files`, { name, content: '' });
          await reload();
          openViewer([node], 0, { url: nodeUrl, canEdit: true, onSaved: reload });
        } catch (e) { showError(e); }
      } },
    ]) }, icon('plus'), 'New'),
    canEdit && h('button', { class: 'btn primary', onclick: (e) => showMenu(e.currentTarget, [
      { label: 'Upload files', icon: 'upload', onclick: () => fileInput.click() },
      { label: 'Upload folder', icon: 'folder', onclick: () => dirInput.click() },
    ]) }, icon('upload'), 'Upload'),
    fileInput, dirInput,
  );

  const view = h('section', { class: 'files-view' }, toolbar, !canEdit && h('p', { class: 'notice' }, icon('lock'), 'Shared with you as read-only'), fileTable(opts));
  if (canEdit) attachDropZone(view, async (items) => uploadAll(items, userUploadTarget(id), reload));
  main.replaceChildren(view);
}

export function attachDropZone(el, onFiles) {
  let depth = 0;
  el.addEventListener('dragenter', (e) => {
    if (!e.dataTransfer.types.includes('Files')) return;
    depth++;
    el.classList.add('dragging');
  });
  el.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; el.classList.remove('dragging'); } });
  el.addEventListener('dragover', (e) => { if (e.dataTransfer.types.includes('Files')) e.preventDefault(); });
  el.addEventListener('drop', async (e) => {
    depth = 0;
    el.classList.remove('dragging');
    if (!e.dataTransfer.types.includes('Files')) return;
    e.preventDefault();
    onFiles(await filesFromDrop(e.dataTransfer));
  });
}

async function listView(main, ctx, { title, iconName, items, emptyText, extraColumn, canModify }) {
  const opts = {
    items, me: ctx.me, extraColumn, emptyText, emptyIcon: iconName,
    canModify: canModify || ((i) => i.owner === ctx.me.id),
    reload: () => ctx.rerender(),
    onOpen: (item) => openItem(item, items, opts),
  };
  main.replaceChildren(h('section', { class: 'files-view' },
    h('div', { class: 'toolbar' }, h('h1', { class: 'view-title' }, icon(iconName), title)),
    fileTable(opts)));
}

export async function renderRecent(main, ctx) {
  await listView(main, ctx, { title: 'Recent', iconName: 'clock', items: await get('/api/recent'), emptyText: 'No recently changed files' });
}

export async function renderFavorites(main, ctx) {
  await listView(main, ctx, { title: 'Favorites', iconName: 'star', items: await get('/api/favorites'), emptyText: 'Mark files with the star to find them here' });
}

export async function renderSharedIn(main, ctx) {
  await listView(main, ctx, {
    title: 'Shared with you', iconName: 'users', items: await get('/api/shares/incoming'), emptyText: 'Nothing has been shared with you yet',
    canModify: () => false,
    extraColumn: { title: 'Shared by', render: (i) => h('span', {}, i.ownerName, i.canEdit ? h('span', { class: 'pill' }, 'can edit') : null) },
  });
}

export async function renderSharedOut(main, ctx) {
  const shares = await get('/api/shares/outgoing');
  const byNode = new Map();
  for (const s of shares) {
    const entry = byNode.get(s.node.id) || { ...s.node, targets: [] };
    entry.targets.push(s.type === 'link' ? 'link' : s.type === 'group' ? `group ${s.shareWith}` : s.shareWith);
    byNode.set(s.node.id, entry);
  }
  await listView(main, ctx, {
    title: 'Shared by you', iconName: 'share', items: [...byNode.values()], emptyText: 'You have not shared anything yet',
    extraColumn: { title: 'Shared with', render: (i) => h('span', { class: 'targets' }, i.targets.join(', ')) },
  });
}

export async function renderSearch(main, ctx, query) {
  const items = query ? await get(`/api/search?q=${encodeURIComponent(query)}`) : [];
  await listView(main, ctx, {
    title: `Search: “${query}”`, iconName: 'search', items, emptyText: 'No matching files',
    extraColumn: { title: 'Location', render: (i) => h('a', { href: `#/files/${i.parentId}`, onclick: (e) => e.stopPropagation() }, i.path) },
  });
}

export async function renderTrash(main, ctx) {
  const items = await get('/api/trash');
  const reload = () => renderTrash(main, ctx).catch(showError);
  const rows = items.map((it) => h('tr', {},
    h('td', { class: 'col-name' }, h('div', { class: 'name-cell' }, fileIcon(it), h('span', { class: 'name' }, it.name))),
    h('td', { class: 'col-extra muted' }, it.originalPath),
    h('td', { class: 'col-size' }, formatSize(it.size)),
    h('td', { class: 'col-date', title: fullDate(it.deletedAt) }, formatDate(it.deletedAt)),
    h('td', { class: 'col-actions nowrap' },
      h('button', { class: 'btn ghost', onclick: async () => { await post(`/api/trash/${it.id}/restore`).then(() => toast('Restored'), showError); reload(); } }, icon('restore'), 'Restore'),
      h('button', { class: 'icon-btn', 'aria-label': 'Delete permanently', onclick: async () => {
        if (await confirmDialog('Delete permanently', `"${it.name}" will be deleted forever.`, 'Delete', true)) { await del(`/api/trash/${it.id}`).catch(showError); reload(); }
      } }, icon('trash'))),
  ));
  main.replaceChildren(h('section', { class: 'files-view' },
    h('div', { class: 'toolbar' }, h('h1', { class: 'view-title' }, icon('trash'), 'Deleted files'), h('div', { class: 'grow' }),
      items.length && h('button', { class: 'btn danger', onclick: async () => {
        if (await confirmDialog('Empty trash', 'All deleted files will be removed permanently.', 'Empty trash', true)) { await del('/api/trash').catch(showError); reload(); }
      } }, 'Empty trash')),
    items.length
      ? h('table', { class: 'file-table' }, h('thead', {}, h('tr', {}, h('th', { class: 'col-name' }, 'Name'), h('th', { class: 'col-extra' }, 'Original location'), h('th', { class: 'col-size' }, 'Size'), h('th', { class: 'col-date' }, 'Deleted'), h('th', {}))), h('tbody', {}, rows))
      : h('div', { class: 'empty' }, icon('trash'), h('p', {}, 'Trash is empty')),
    h('p', { class: 'muted small' }, `Deleted items are removed permanently after ${ctx.trashDays ?? 30} days.`),
  ));
}
