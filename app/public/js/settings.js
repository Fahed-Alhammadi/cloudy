// Personal settings and the admin console.
import { api, get, post, patch, del } from './api.js';
import { h, icon, formatSize, fullDate, toast, showError, modal, confirmDialog, promptDialog, copyText } from './ui.js';

export async function renderSettings(main, ctx) {
  const me = await get('/api/me');
  const appPasswords = await get('/api/me/app-passwords');
  const rerender = () => renderSettings(main, ctx).catch(showError);
  const davBase = `${location.origin}/dav`;

  const name = h('input', { type: 'text', value: me.displayName });
  const email = h('input', { type: 'email', value: me.email });
  const cur = h('input', { type: 'password', autocomplete: 'current-password', required: true });
  const pw1 = h('input', { type: 'password', autocomplete: 'new-password', minlength: 8, required: true });
  const pct = me.quotaBytes ? Math.min(100, (me.used / me.quotaBytes) * 100) : 0;

  const urlRow = (label, url) => h('div', { class: 'url-row' }, h('span', { class: 'muted' }, label),
    h('code', {}, url), h('button', { class: 'icon-btn', 'aria-label': `Copy ${label}`, onclick: () => copyText(url) }, icon('copy')));

  main.replaceChildren(h('section', { class: 'settings-view' },
    h('h1', { class: 'view-title' }, icon('settings'), 'Settings'),

    h('div', { class: 'card' }, h('h2', {}, 'Storage'),
      me.quotaBytes ? h('progress', { max: 100, value: pct, class: 'quota' }) : null,
      h('p', {}, `${formatSize(me.used)} used${me.quotaBytes ? ` of ${formatSize(me.quotaBytes)}` : ' (no quota)'}, including trash and versions.`)),

    h('form', { class: 'card form-grid', onsubmit: async (e) => {
      e.preventDefault();
      await patch('/api/me', { displayName: name.value, email: email.value }).then(() => { toast('Profile saved'); ctx.refreshMe(); }, showError);
    } },
      h('h2', { class: 'wide' }, 'Profile'),
      h('label', { class: 'field' }, h('span', {}, 'Display name'), name),
      h('label', { class: 'field' }, h('span', {}, 'Email'), email),
      h('div', { class: 'wide row end' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save profile'))),

    h('form', { class: 'card form-grid', onsubmit: async (e) => {
      e.preventDefault();
      await post('/api/me/password', { current: cur.value, password: pw1.value }).then(() => { toast('Password changed'); e.target.reset(); }, showError);
    } },
      h('h2', { class: 'wide' }, 'Password'),
      h('label', { class: 'field' }, h('span', {}, 'Current password'), cur),
      h('label', { class: 'field' }, h('span', {}, 'New password (8+ characters)'), pw1),
      h('div', { class: 'wide row end' }, h('button', { class: 'btn primary', type: 'submit' }, 'Change password'))),

    h('div', { class: 'card' }, h('h2', {}, 'Connect apps and devices'),
      h('p', {}, 'Use these addresses in a WebDAV client (Finder, Windows Explorer, rclone, Cyberduck) or a calendar/contacts app (Thunderbird, DAVx⁵, Apple Calendar/Contacts). Sign in with your username and an app password.'),
      urlRow('Files (WebDAV)', `${davBase}/files/${encodeURIComponent(me.id)}/`),
      urlRow('Calendars & contacts', `${davBase}/`),
      urlRow('Username', me.id),
      h('p', {}, icon('download'), ' ', h('a', { href: '/download/android' }, 'Download the FileCloud Android app'), h('span', { class: 'muted' }, ' (when your administrator has published it)')),
      h('h3', {}, 'App passwords'),
      appPasswords.length ? h('ul', { class: 'plain-list' }, appPasswords.map((p) => h('li', {}, icon('key'),
        h('span', { class: 'grow' }, p.name, h('small', { class: 'muted' }, ` · created ${fullDate(p.createdAt)}${p.lastUsedAt ? ` · last used ${fullDate(p.lastUsedAt)}` : ''}`)),
        h('button', { class: 'btn ghost danger', onclick: async () => {
          if (await confirmDialog('Revoke app password', `Devices using "${p.name}" will be signed out.`, 'Revoke', true)) await del(`/api/me/app-passwords/${p.id}`).then(rerender, showError);
        } }, 'Revoke')))) : h('p', { class: 'muted' }, 'No app passwords yet.'),
      h('button', { class: 'btn', onclick: async () => {
        const label = await promptDialog('New app password', 'Device name', 'My laptop', 'Create');
        if (!label) return;
        try {
          const r = await post('/api/me/app-passwords', { name: label });
          await modal({ title: 'App password created', body: h('div', {},
            h('p', {}, 'Copy it now — it will not be shown again.'),
            h('div', { class: 'url-row' }, h('code', { class: 'secret' }, r.password), h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Copy', onclick: () => copyText(r.password) }, icon('copy')))) });
          rerender();
        } catch (e) { showError(e); }
      } }, icon('plus'), 'Create app password')),
  ));
}

// ---------------------------------------------------------------- admin

const GB = 1024 ** 3;

async function userDialog(user, groups, rerender) {
  const isNew = !user;
  const f = {
    id: h('input', { type: 'text', required: true, value: user?.id ?? '', disabled: !isNew, pattern: '[A-Za-z0-9._@-]+' }),
    displayName: h('input', { type: 'text', value: user?.displayName ?? '' }),
    email: h('input', { type: 'email', value: user?.email ?? '' }),
    password: h('input', { type: 'password', autocomplete: 'new-password', minlength: 8, required: isNew, placeholder: isNew ? '' : 'Leave empty to keep' }),
    quota: h('input', { type: 'number', min: 0, step: 0.5, value: user?.quotaBytes ? +(user.quotaBytes / GB).toFixed(2) : '', placeholder: 'Unlimited' }),
    isAdmin: h('input', { type: 'checkbox', checked: !!user?.isAdmin }),
    disabled: h('input', { type: 'checkbox', checked: !!user?.disabled }),
    groups: h('select', { multiple: true, size: Math.min(6, Math.max(2, groups.length)) }, groups.map((g) => h('option', { value: g.id, selected: user?.groups.includes(g.id) }, g.id))),
  };
  await modal({
    title: isNew ? 'New user' : `Edit ${user.id}`, wide: true,
    body: h('div', { class: 'form-grid' },
      h('label', { class: 'field' }, h('span', {}, 'Username'), f.id),
      h('label', { class: 'field' }, h('span', {}, 'Password'), f.password),
      h('label', { class: 'field' }, h('span', {}, 'Display name'), f.displayName),
      h('label', { class: 'field' }, h('span', {}, 'Email'), f.email),
      h('label', { class: 'field' }, h('span', {}, 'Quota (GB)'), f.quota),
      h('label', { class: 'field' }, h('span', {}, 'Groups'), groups.length ? f.groups : h('span', { class: 'muted' }, 'No groups yet')),
      h('label', { class: 'check' }, f.isAdmin, 'Administrator'),
      !isNew && h('label', { class: 'check' }, f.disabled, 'Disabled'),
    ),
    buttons: [{ label: 'Cancel', value: null }, { label: isNew ? 'Create' : 'Save', value: 'save', primary: true }],
    onSubmit: async () => {
      const data = {
        displayName: f.displayName.value, email: f.email.value, isAdmin: f.isAdmin.checked,
        quotaBytes: f.quota.value ? Math.round(Number(f.quota.value) * GB) : null,
        groups: [...f.groups.selectedOptions].map((o) => o.value),
      };
      try {
        if (isNew) await post('/api/admin/users', { ...data, id: f.id.value.trim(), password: f.password.value });
        else await patch(`/api/admin/users/${encodeURIComponent(user.id)}`, { ...data, disabled: f.disabled.checked, password: f.password.value || undefined });
        rerender();
        return true;
      } catch (e) {
        showError(e);
        return false;
      }
    },
  });
}

export async function renderAdmin(main, ctx) {
  const [users, groups] = await Promise.all([get('/api/admin/users'), get('/api/admin/groups')]);
  const rerender = () => renderAdmin(main, ctx).catch(showError);
  main.replaceChildren(h('section', { class: 'settings-view' },
    h('div', { class: 'toolbar' }, h('h1', { class: 'view-title' }, icon('shield'), 'Administration'), h('div', { class: 'grow' }),
      h('button', { class: 'btn primary', onclick: () => userDialog(null, groups, rerender) }, icon('plus'), 'New user')),
    h('div', { class: 'card' }, h('h2', {}, `Users (${users.length})`),
      h('div', { class: 'table-wrap' }, h('table', { class: 'data-table' },
        h('thead', {}, h('tr', {}, ['User', 'Email', 'Groups', 'Storage', 'Role', ''].map((t) => h('th', {}, t)))),
        h('tbody', {}, users.map((u) => h('tr', { class: u.disabled ? 'dim' : '' },
          h('td', {}, h('strong', {}, u.displayName), h('div', { class: 'muted small' }, u.id)),
          h('td', {}, u.email),
          h('td', {}, u.groups.join(', ')),
          h('td', { class: 'nowrap' }, `${formatSize(u.used)}${u.quotaBytes ? ` / ${formatSize(u.quotaBytes)}` : ''}`),
          h('td', {}, u.isAdmin ? h('span', { class: 'pill' }, 'admin') : '', u.disabled ? h('span', { class: 'pill warn' }, 'disabled') : ''),
          h('td', { class: 'nowrap' },
            h('button', { class: 'icon-btn', 'aria-label': `Edit ${u.id}`, onclick: () => userDialog(u, groups, rerender) }, icon('edit')),
            u.id !== ctx.me.id && h('button', { class: 'icon-btn', 'aria-label': `Delete ${u.id}`, onclick: async () => {
              if (await confirmDialog('Delete user', `Delete ${u.id} and ALL their files, calendars and contacts? This cannot be undone.`, 'Delete', true)) {
                await del(`/api/admin/users/${encodeURIComponent(u.id)}`).then(rerender, showError);
              }
            } }, icon('trash'))),
        )))))),
    h('div', { class: 'card' }, h('h2', {}, 'Groups'),
      groups.length ? h('ul', { class: 'plain-list' }, groups.map((g) => h('li', {}, icon('users'),
        h('span', { class: 'grow' }, g.id, h('small', { class: 'muted' }, ` · ${g.members} member(s)`)),
        h('button', { class: 'icon-btn', 'aria-label': `Delete group ${g.id}`, onclick: async () => {
          if (await confirmDialog('Delete group', `Delete group "${g.id}"? Shares with this group are removed.`, 'Delete', true)) await del(`/api/admin/groups/${encodeURIComponent(g.id)}`).then(rerender, showError);
        } }, icon('trash'))))) : h('p', { class: 'muted' }, 'No groups yet.'),
      h('button', { class: 'btn', onclick: async () => {
        const id = await promptDialog('New group', 'Group name', '', 'Create');
        if (id) await post('/api/admin/groups', { id }).then(rerender, showError);
      } }, icon('plus'), 'New group')),
    await androidCard(rerender),
  ));
}

// ---------------------------------------------------------------- Android releases

async function androidCard(rerender) {
  const releases = await get('/api/admin/app/android').catch(() => []);
  const file = h('input', { type: 'file', accept: '.apk,application/vnd.android.package-archive', required: true });
  const code = h('input', { type: 'number', min: 1, required: true, value: (releases[0]?.versionCode ?? 0) + 1 });
  const name = h('input', { type: 'text', required: true, placeholder: '1.2.0' });
  const notes = h('textarea', { rows: 2, placeholder: "What's new (shown in the app)" });
  const status = h('span', { class: 'muted' });
  return h('div', { class: 'card' },
    h('h2', {}, 'Android app (over-the-air updates)'),
    h('p', { class: 'muted' }, 'The newest APK published here is offered to every installed FileCloud Android app, and can be downloaded at ',
      h('a', { href: '/download/android' }, '/download/android'), '. It must be signed with the same key as the installed app and have a higher version code.'),
    releases.length ? h('ul', { class: 'plain-list' }, releases.map((r, i) => h('li', {}, icon('download'),
      h('span', { class: 'grow' }, h('strong', {}, `${r.versionName} (${r.versionCode})`), i === 0 ? h('span', { class: 'pill' }, 'latest') : null,
        h('div', { class: 'muted small' }, `${formatSize(r.size)} · ${fullDate(r.publishedAt)}${r.notes ? ` · ${r.notes}` : ''}`)),
      h('button', { class: 'icon-btn', 'aria-label': `Delete release ${r.versionName}`, onclick: async () => {
        if (await confirmDialog('Delete release', `Remove version ${r.versionName}?`, 'Delete', true)) await del(`/api/admin/app/android/${r.id}`).then(rerender, showError);
      } }, icon('trash'))))) : h('p', { class: 'muted' }, 'No release published yet.'),
    h('form', { class: 'form-grid', onsubmit: async (e) => {
      e.preventDefault();
      if (!file.files[0]) return;
      status.textContent = 'Uploading…';
      const qs = new URLSearchParams({ versionCode: code.value, versionName: name.value.trim(), notes: notes.value.trim() });
      try {
        await api('POST', `/api/admin/app/android?${qs}`, file.files[0]);
        toast('Release published');
        rerender();
      } catch (err) { status.textContent = ''; showError(err); }
    } },
      h('label', { class: 'field wide' }, h('span', {}, 'APK file'), file),
      h('label', { class: 'field' }, h('span', {}, 'Version code'), code),
      h('label', { class: 'field' }, h('span', {}, 'Version name'), name),
      h('label', { class: 'field wide' }, h('span', {}, 'Release notes'), notes),
      h('div', { class: 'wide row end' }, status, h('button', { class: 'btn primary', type: 'submit' }, icon('upload'), 'Publish release'))),
  );
}
