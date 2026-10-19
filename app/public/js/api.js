// Thin fetch wrapper for the FileCloud JSON API.

export class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

export async function api(method, path, body) {
  const headers = { 'X-FC-Request': '1' };
  let payload;
  if (body instanceof Blob || typeof body === 'string') {
    payload = body;
    headers['Content-Type'] = 'application/octet-stream';
  } else if (body !== undefined) {
    payload = JSON.stringify(body);
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(path, { method, headers, body: payload, credentials: 'same-origin' });
  const isJson = (res.headers.get('content-type') || '').includes('json');
  const data = res.status === 204 ? null : isJson ? await res.json() : await res.text();
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith('/api/public') && path !== '/api/login') {
      window.dispatchEvent(new CustomEvent('fc:unauthorized'));
    }
    throw new ApiError(res.status, (data && data.error) || res.statusText, data);
  }
  return data;
}

export const get = (p) => api('GET', p);
export const post = (p, b) => api('POST', p, b ?? {});
export const put = (p, b) => api('PUT', p, b);
export const patch = (p, b) => api('PATCH', p, b);
export const del = (p) => api('DELETE', p);

/** PUT with upload progress (fetch has no upload progress events). */
export function xhrPut(url, blob, headers, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    xhr.setRequestHeader('X-FC-Request', '1');
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    for (const [k, v] of Object.entries(headers || {})) xhr.setRequestHeader(k, v);
    xhr.upload.onprogress = (e) => onProgress && onProgress(e.loaded);
    xhr.onload = () => {
      let data = null;
      try { data = JSON.parse(xhr.responseText); } catch { /* not json */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(new ApiError(xhr.status, (data && data.error) || xhr.statusText, data));
    };
    xhr.onerror = () => reject(new ApiError(0, 'Network error'));
    xhr.onabort = () => reject(new ApiError(-1, 'Cancelled'));
    xhr.send(blob);
    if (onProgress) onProgress(0);
    blob._xhr = xhr;
  });
}
