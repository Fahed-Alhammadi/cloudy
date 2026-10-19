// App shell: login, navigation and hash routing.
import { get, post } from './api.js';
import { h, icon, showError, showMenu, formatSize } from './ui.js';
import { renderFolder, renderRecent, renderFavorites, renderSharedIn, renderSharedOut, renderSearch, renderTrash } from './files.js';
import { renderCalendar } from './calendar.js';
import { renderContacts } from './contacts.js';
import { renderSettings, renderAdmin } from './settings.js';

const app = document.getElementById('app');
const ctx = { me: null, rerender: () => route(), refreshMe };

async function refreshMe() {
  ctx.me = await get('/api/me');
  renderShell();
  route();
}

function renderLogin(message) {
  const user = h('input', { type: 'text', name: 'username', autocomplete: 'username', required: true, autofocus: true, 'aria-label': 'Username' });
  const pass = h('input', { type: 'password', name: 'password', autocomplete: 'current-password', required: true, 'aria-label': 'Password' });
  const error = h('p', { class: 'error', role: 'alert' }, message || '');
  const btn = h('button', { class: 'btn primary block', type: 'submit' }, 'Log in');
  app.replaceChildren(h('main', { class: 'login' },
    h('form', { class: 'login-card', onsubmit: async (e) => {
      e.preventDefault();
      btn.disabled = true;
      error.textContent = '';
      try {
        await post('/api/login', { user: user.value.trim(), password: pass.value });
        await refreshMe();
      } catch (err) {
        error.textContent = err.message;
        btn.disabled = false;
      }
    } },
      h('div', { class: 'brand big' }, icon('cloud'), 'FileCloud'),
      h('label', { class: 'field' }, h('span', {}, 'Username'), user),
      h('label', { class: 'field' }, h('span', {}, 'Password'), pass),
      error, btn,
      h('a', { class: 'android-link', href: '/download/android', hidden: true }, icon('download'), 'Get the Android app'))));
  get('/api/app/android').then((r) => { const a = app.querySelector('.android-link'); if (a) { a.hidden = false; a.title = `Version ${r.versionName}`; } }, () => {});
  user.focus();
}

const NAV = [
  ['files', 'Files', 'folder'],
  ['recent', 'Recent', 'clock'],
  ['favorites', 'Favorites', 'star'],
  ['shared-in', 'Shared with you', 'users'],
  ['shared-out', 'Shared by you', 'share'],
  ['trash', 'Deleted files', 'trash'],
  '-',
  ['calendar', 'Calendar', 'calendar'],
  ['contacts', 'Contacts', 'contacts'],
];

let main;
let nav;

function renderShell() {
  const search = h('input', { type: 'search', placeholder: 'Search files', 'aria-label': 'Search files', onkeydown: (e) => {
    if (e.key === 'Enter' && search.value.trim()) location.hash = `#/search/${encodeURIComponent(search.value.trim())}`;
  } });
  nav = h('nav', { class: 'sidebar', 'aria-label': 'Main' },
    h('ul', {}, NAV.map((item) => item === '-' ? h('li', { class: 'sep' }) : h('li', {},
      h('a', { href: `#/${item[0]}`, 'data-view': item[0] }, icon(item[2]), h('span', {}, item[1]))))),
    h('div', { class: 'grow' }),
    h('ul', {},
      h('li', {}, h('a', { href: '#/settings', 'data-view': 'settings' }, icon('settings'), h('span', {}, 'Settings'))),
      ctx.me.isAdmin && h('li', {}, h('a', { href: '#/admin', 'data-view': 'admin' }, icon('shield'), h('span', {}, 'Administration')))),
    h('div', { class: 'usage' }, `${formatSize(ctx.me.used)}${ctx.me.quotaBytes ? ` of ${formatSize(ctx.me.quotaBytes)}` : ''} used`),
  );
  main = h('main', { class: 'content', id: 'content', tabindex: -1 });
  const header = h('header', { class: 'topbar' },
    h('button', { class: 'icon-btn menu-toggle', 'aria-label': 'Toggle menu', onclick: () => document.body.classList.toggle('nav-open') }, icon('menu')),
    h('a', { class: 'brand', href: '#/files' }, icon('cloud'), 'FileCloud'),
    h('div', { class: 'search' }, icon('search'), search),
    h('button', { class: 'avatar-btn', 'aria-label': 'Account menu', onclick: (e) => showMenu(e.currentTarget, [
      { label: ctx.me.displayName, icon: 'contacts', onclick: () => (location.hash = '#/settings') },
      { label: 'Log out', icon: 'logout', onclick: async () => { await post('/api/logout').catch(() => {}); ctx.me = null; renderLogin(); } },
    ]) }, (ctx.me.displayName || ctx.me.id).slice(0, 1).toUpperCase()),
  );
  app.replaceChildren(header, h('div', { class: 'layout' }, nav, main));
}

async function route() {
  if (!ctx.me || !main) return;
  const [, view = 'files', arg] = location.hash.replace(/^#/, '').split('/');
  document.body.classList.remove('nav-open');
  nav.querySelectorAll('a').forEach((a) => a.classList.toggle('active', a.dataset.view === view));
  main.setAttribute('aria-busy', 'true');
  try {
    switch (view) {
      case 'files': await renderFolder(main, arg || ctx.me.rootId, ctx); break;
      case 'recent': await renderRecent(main, ctx); break;
      case 'favorites': await renderFavorites(main, ctx); break;
      case 'shared-in': await renderSharedIn(main, ctx); break;
      case 'shared-out': await renderSharedOut(main, ctx); break;
      case 'search': await renderSearch(main, ctx, decodeURIComponent(arg || '')); break;
      case 'trash': await renderTrash(main, ctx); break;
      case 'calendar': await renderCalendar(main, ctx); break;
      case 'contacts': await renderContacts(main, ctx); break;
      case 'settings': await renderSettings(main, ctx); break;
      case 'admin': await renderAdmin(main, ctx); break;
      default: location.hash = '#/files';
    }
    document.title = `${nav.querySelector('a.active span')?.textContent || 'Files'} – FileCloud`;
  } catch (e) {
    if (e.status === 404 && view === 'files') location.hash = '#/files';
    else if (e.status !== 401) showError(e);
  } finally {
    main.removeAttribute('aria-busy');
  }
}

window.addEventListener('hashchange', route);
window.addEventListener('fc:unauthorized', () => { ctx.me = null; renderLogin('Your session has expired. Please log in again.'); });

get('/api/me').then((me) => { ctx.me = me; renderShell(); route(); }, () => renderLogin());
