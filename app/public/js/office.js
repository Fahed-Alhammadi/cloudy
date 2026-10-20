// Office documents in the built-in Collabora Online editor (full-screen iframe, WOPI).
import { get, post } from './api.js';
import { h, showError } from './ui.js';

let extensions = {}; // ext -> 'edit' | 'view'

/** Loads which file types open in the editor; call after login. Empty when the editor is off. */
export async function loadOfficeInfo() {
  extensions = await get('/api/office').then((r) => r.extensions || {}, () => ({}));
  return extensions;
}

const extOf = (name) => (name.includes('.') ? name.split('.').pop().toLowerCase() : '');
export const isOfficeFile = (node) => !node.isDir && !!extensions[extOf(node.name)];
export const officeAvailable = () => Object.keys(extensions).length > 0;

/** New-document types for the "New" menu (empty files; the editor creates the document on first save). */
const NEW_DOCS = [
  { ext: 'docx', label: 'New document', name: 'Document.docx' },
  { ext: 'xlsx', label: 'New spreadsheet', name: 'Spreadsheet.xlsx' },
  { ext: 'pptx', label: 'New presentation', name: 'Presentation.pptx' },
];
export const newDocTypes = () => NEW_DOCS.filter((d) => extensions[d.ext] === 'edit');

/** Opens `node` in the editor. `onClose` runs when the user closes it. */
export async function openOffice(node, { onClose } = {}) {
  let launch;
  try { launch = await post(`/api/nodes/${node.id}/office`); } catch (e) { return showError(e); }
  const frameName = `office-${node.id}-${Date.now()}`;
  const frame = h('iframe', { name: frameName, class: 'office-frame', title: node.name, allow: 'clipboard-read; clipboard-write; fullscreen', allowfullscreen: true });
  // WOPI: the access token is POSTed to the editor page, so it never appears in a URL or history.
  const form = h('form', { action: launch.url, method: 'post', target: frameName, hidden: true },
    h('input', { type: 'hidden', name: 'access_token', value: launch.accessToken }),
    h('input', { type: 'hidden', name: 'access_token_ttl', value: String(launch.accessTokenTtl) }));
  const dlg = h('dialog', { class: 'office', 'aria-label': node.name }, frame, form);

  const onMessage = (e) => {
    if (e.source !== frame.contentWindow || e.origin !== location.origin) return;
    let msg;
    try { msg = typeof e.data === 'string' ? JSON.parse(e.data) : e.data; } catch { return; }
    if (msg?.MessageId === 'App_LoadingStatus' && msg.Values?.Status === 'Frame_Ready') {
      frame.contentWindow.postMessage(JSON.stringify({ MessageId: 'Host_PostmessageReady', SendTime: Date.now(), Values: {} }), location.origin);
    } else if (msg?.MessageId === 'UI_Close') {
      dlg.close();
    }
  };
  window.addEventListener('message', onMessage);
  dlg.addEventListener('cancel', (e) => e.preventDefault()); // Esc belongs to the editor
  dlg.addEventListener('close', () => {
    window.removeEventListener('message', onMessage);
    dlg.remove();
    onClose?.();
  });
  document.body.append(dlg);
  dlg.showModal();
  form.submit();
}
