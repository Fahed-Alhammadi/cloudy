// Full-screen HTML5 viewer: images, video, audio, PDF and an editable text view.
import { api } from './api.js';
import { h, icon, formatSize, toast, showError, confirmDialog } from './ui.js';

const TEXT_LIMIT = 2 * 1024 * 1024;

export function kindOf(node) {
  const m = node.mime || '';
  if (/^image\/(png|jpeg|gif|webp|avif|bmp|x-icon)$/.test(m)) return 'image';
  if (m.startsWith('video/')) return 'video';
  if (m.startsWith('audio/')) return 'audio';
  if (m === 'application/pdf') return 'pdf';
  if (m.startsWith('text/') || /json|xml|javascript|yaml/.test(m)) return 'text';
  return null;
}

/**
 * files: the sibling files (for prev/next), start: index.
 * src = { url(node, inline) -> string, canEdit: bool, onSaved?() }
 */
export function openViewer(files, start, src) {
  let index = start;
  let dirty = false;
  const stage = h('div', { class: 'viewer-stage' });
  const title = h('div', { class: 'viewer-title' });
  const actions = h('div', { class: 'viewer-actions' });
  const prev = h('button', { class: 'icon-btn viewer-nav prev', 'aria-label': 'Previous', onclick: () => go(-1) }, icon('left'));
  const next = h('button', { class: 'icon-btn viewer-nav next', 'aria-label': 'Next', onclick: () => go(1) }, icon('right'));
  const dlg = h('dialog', { class: 'viewer' },
    h('header', {}, title, actions, h('button', { class: 'icon-btn', 'aria-label': 'Close', onclick: () => close() }, icon('x'))),
    stage, prev, next,
  );

  async function close() {
    if (dirty && !(await confirmDialog('Discard changes?', 'You have unsaved changes.', 'Discard', true))) return;
    dlg.close();
  }

  async function go(delta) {
    if (dirty && !(await confirmDialog('Discard changes?', 'You have unsaved changes.', 'Discard', true))) return;
    const n = files.length;
    index = (index + delta + n) % n;
    show();
  }

  async function show() {
    dirty = false;
    const node = files[index];
    const kind = kindOf(node);
    title.replaceChildren(h('strong', {}, node.name), h('span', { class: 'muted' }, ` · ${formatSize(node.size)}`));
    actions.replaceChildren(
      h('a', { class: 'btn ghost', href: src.url(node, false), download: node.name }, icon('download'), 'Download'),
    );
    prev.hidden = next.hidden = files.length < 2;
    stage.replaceChildren();
    const inline = src.url(node, true);
    if (kind === 'image') stage.append(h('img', { src: inline, alt: node.name }));
    else if (kind === 'video') stage.append(h('video', { src: inline, controls: true, autoplay: true, playsinline: true }));
    else if (kind === 'audio') stage.append(h('div', { class: 'audio-card' }, icon('audio'), h('div', {}, node.name), h('audio', { src: inline, controls: true, autoplay: true })));
    else if (kind === 'pdf') stage.append(h('iframe', { src: inline, title: node.name }));
    else if (kind === 'text' && node.size <= TEXT_LIMIT) await showText(node, inline);
    else stage.append(h('div', { class: 'no-preview' }, icon('file'), h('p', {}, 'No preview available for this file type.'),
      h('a', { class: 'btn primary', href: src.url(node, false), download: node.name }, icon('download'), 'Download')));
  }

  async function showText(node, inline) {
    const res = await fetch(inline, { credentials: 'same-origin' });
    const text = await res.text();
    if (!src.canEdit) {
      stage.append(h('pre', { class: 'text-view' }, text));
      return;
    }
    const area = h('textarea', { class: 'text-editor', spellcheck: false, 'aria-label': node.name, value: text });
    const save = h('button', { class: 'btn primary', disabled: true, onclick: async () => {
      try {
        save.disabled = true;
        const updated = await api('PUT', `/api/nodes/${node.id}/content`, new Blob([area.value], { type: 'text/plain' }));
        files[index] = { ...node, ...updated };
        dirty = false;
        toast('Saved');
        src.onSaved?.();
      } catch (e) {
        save.disabled = false;
        showError(e);
      }
    } }, icon('save'), 'Save');
    area.addEventListener('input', () => { dirty = true; save.disabled = false; });
    area.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); if (!save.disabled) save.click(); }
    });
    actions.prepend(save);
    stage.append(area);
    area.focus();
  }

  dlg.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
  dlg.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'TEXTAREA') return;
    if (e.key === 'ArrowLeft') go(-1);
    if (e.key === 'ArrowRight') go(1);
  });
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  show().catch(showError);
}
