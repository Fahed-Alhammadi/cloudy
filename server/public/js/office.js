// Office documents are shown read-only: the server converts them to PDF with LibreOffice.
import { get } from './api.js';

let extensions = new Set();

/** Loads which file types have PDF previews; call after login. */
export async function loadOfficeInfo() {
  extensions = new Set(await get('/api/office').then((r) => r.extensions || [], () => []));
}

const extOf = (name) => (name.includes('.') ? name.split('.').pop().toLowerCase() : '');
export const isOfficeFile = (node) => !node.isDir && extensions.has(extOf(node.name));
export const officePreviewUrl = (node) => `/api/nodes/${node.id}/preview`;
