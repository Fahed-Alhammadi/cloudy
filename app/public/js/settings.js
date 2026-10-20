// Personal settings and the admin console.
import { get, post, put, patch, del } from './api.js';
import { h, icon, formatSize, fullDate, toast, showError, modal, confirmDialog, promptDialog, copyText } from './ui.js';
import { passkeysSupported, createPasskey, isPasskeyCancel } from './passkey.js';

export async function renderSettings(main, ctx) {
  const me = await get('/api/me');
  const [appPasswords, passkeys] = await Promise.all([get('/api/me/app-passwords'), get('/api/me/passkeys')]);
  const rerender = () => renderSettings(main, ctx).catch(showError);
  const davBase = `${location.origin}/dav`;

  const name = h('input', { type: 'text', value: me.displayName });
  const email = h('input', { type: 'email', value: me.email });
  const notifyShares = h('input', { type: 'checkbox', checked: me.notifyShares !== false });
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
      await patch('/api/me', { displayName: name.value, email: email.value, notifyShares: notifyShares.checked }).then(() => { toast('Profile saved'); ctx.refreshMe(); }, showError);
    } },
      h('h2', { class: 'wide' }, 'Profile'),
      h('label', { class: 'field' }, h('span', {}, 'Display name'), name),
      h('label', { class: 'field' }, h('span', {}, 'Email'), email),
      h('label', { class: 'check wide' }, notifyShares, 'Email me when something is shared with me'),
      h('p', { class: 'muted small wide' }, 'Your email is also used for password reset links and security notices.'),
      h('div', { class: 'wide row end' }, h('button', { class: 'btn primary', type: 'submit' }, 'Save profile'))),

    h('form', { class: 'card form-grid', onsubmit: async (e) => {
      e.preventDefault();
      await post('/api/me/password', { current: cur.value, password: pw1.value }).then(() => { toast('Password changed'); e.target.reset(); }, showError);
    } },
      h('h2', { class: 'wide' }, 'Password'),
      h('label', { class: 'field' }, h('span', {}, 'Current password'), cur),
      h('label', { class: 'field' }, h('span', {}, 'New password (8+ characters)'), pw1),
      h('div', { class: 'wide row end' }, h('button', { class: 'btn primary', type: 'submit' }, 'Change password'))),

    passkeysCard(passkeys, rerender),
    twoFactorCard(me, rerender),

    h('div', { class: 'card' }, h('h2', {}, 'Connect apps and devices'),
      h('p', {}, 'Use these addresses in a WebDAV client (Finder, Windows Explorer, rclone, Cyberduck) or a calendar/contacts app (Thunderbird, DAVx⁵, Apple Calendar/Contacts). Sign in with your username and an app password.'),
      urlRow('Files (WebDAV)', `${davBase}/files/${encodeURIComponent(me.id)}/`),
      urlRow('Calendars & contacts', `${davBase}/`),
      urlRow('Username', me.id),
      h('p', {}, icon('download'), ' ', h('a', { href: '/download/android' }, 'Download the Cloudy Android app'), h('span', { class: 'muted' }, ' (newest release; the app updates itself)')),
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

// ---------------------------------------------------------------- passkeys

function passkeysCard(passkeys, rerender) {
  const add = async () => {
    const name = h('input', { type: 'text', maxlength: 60, placeholder: 'e.g. MacBook, iPhone, YubiKey', 'aria-label': 'Passkey name' });
    const pw = h('input', { type: 'password', autocomplete: 'current-password', required: true, 'aria-label': 'Password' });
    let added = false;
    await modal({
      title: 'Add a passkey',
      body: h('div', {},
        h('p', {}, 'Your device will ask you to confirm with your fingerprint, face, screen lock or security key.'),
        h('label', { class: 'field' }, h('span', {}, 'Name'), name),
        h('label', { class: 'field' }, h('span', {}, 'Confirm with your password'), pw)),
      buttons: [{ label: 'Cancel', value: null }, { label: 'Continue', value: 'ok', primary: true }],
      onSubmit: async () => {
        try {
          const options = await post('/api/me/passkeys/options', { password: pw.value });
          const cred = await createPasskey(options);
          await post('/api/me/passkeys', { ...cred, name: name.value.trim() });
          added = true;
          return true;
        } catch (e) {
          if (e?.name === 'InvalidStateError') toast('This device already has a passkey for your account', 'error');
          else if (!isPasskeyCancel(e)) showError(e);
          return false;
        }
      },
    });
    if (added) { toast('Passkey added'); rerender(); }
  };

  return h('div', { class: 'card' }, h('h2', {}, 'Passkeys'),
    h('p', {}, 'Sign in without a password using your fingerprint, face, screen lock or a security key. A passkey replaces both your password and the two-factor code.'),
    passkeys.length ? h('ul', { class: 'plain-list' }, passkeys.map((p) => h('li', {}, icon('key'),
      h('span', { class: 'grow' }, p.name, h('small', { class: 'muted' }, ` · added ${fullDate(p.createdAt)}${p.lastUsedAt ? ` · last used ${fullDate(p.lastUsedAt)}` : ''}`)),
      h('button', { class: 'icon-btn', 'aria-label': `Rename ${p.name}`, onclick: async () => {
        const name = await promptDialog('Rename passkey', 'Name', p.name, 'Rename');
        if (name) await patch(`/api/me/passkeys/${p.id}`, { name }).then(rerender, showError);
      } }, icon('edit')),
      h('button', { class: 'btn ghost danger', onclick: async () => {
        if (await confirmDialog('Remove passkey', `You will no longer be able to sign in with "${p.name}". Also delete it from the device or password manager.`, 'Remove', true)) await del(`/api/me/passkeys/${p.id}`).then(rerender, showError);
      } }, 'Remove')))) : h('p', { class: 'muted' }, 'No passkeys yet.'),
    passkeysSupported()
      ? h('button', { class: 'btn', onclick: add }, icon('plus'), 'Add a passkey')
      : h('p', { class: 'muted small' }, 'This browser cannot create passkeys here. Passkeys need HTTPS (or localhost) and a recent browser.'));
}

// ---------------------------------------------------------------- two-factor authentication

function recoveryCodesBody(codes) {
  const text = codes.join('\n');
  return h('div', {},
    h('p', {}, 'Save these recovery codes somewhere safe. Each one signs you in once if you lose your phone. They will not be shown again.'),
    h('pre', { class: 'recovery-codes' }, text),
    h('div', { class: 'row' },
      h('button', { type: 'button', class: 'btn', onclick: () => copyText(text) }, icon('copy'), 'Copy'),
      h('a', { class: 'btn', href: URL.createObjectURL(new Blob([`Cloudy recovery codes\n\n${text}\n`], { type: 'text/plain' })), download: 'cloudy-recovery-codes.txt' }, icon('download'), 'Download')));
}

function twoFactorCard(me, rerender) {
  const enable = async () => {
    let setup;
    try { setup = await post('/api/me/2fa/setup'); } catch (e) { return showError(e); }
    const code = h('input', { type: 'text', inputmode: 'numeric', autocomplete: 'one-time-code', required: true, placeholder: '123 456', 'aria-label': 'Code from the app' });
    let codes = null;
    await modal({
      title: 'Turn on two-factor authentication',
      body: h('div', { class: 'twofa-setup' },
        h('p', {}, '1. Scan this QR code with an authenticator app (Google Authenticator, Microsoft Authenticator, Authy, 1Password…).'),
        h('img', { class: 'qr', alt: 'QR code for your authenticator app', src: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(setup.qrSvg) }),
        h('p', { class: 'muted small' }, "Can't scan? Enter this key manually: ", h('code', { class: 'secret' }, setup.secret.replace(/(.{4})/g, '$1 ').trim())),
        h('label', { class: 'field' }, h('span', {}, '2. Enter the 6-digit code the app shows'), code)),
      buttons: [{ label: 'Cancel', value: null }, { label: 'Turn on', value: 'ok', primary: true }],
      onSubmit: async () => {
        try {
          codes = (await post('/api/me/2fa/enable', { code: code.value })).recoveryCodes;
          return true;
        } catch (e) { showError(e); code.select(); return false; }
      },
    });
    if (codes) {
      await modal({ title: 'Two-factor authentication is on', body: recoveryCodesBody(codes), buttons: [{ label: "I've saved them", value: 'ok', primary: true }] });
      rerender();
    }
  };

  const withPassword = async (title, okLabel, danger, path) => {
    const pw = h('input', { type: 'password', autocomplete: 'current-password', required: true, 'aria-label': 'Password' });
    let result = null;
    await modal({
      title, body: h('label', { class: 'field' }, h('span', {}, 'Confirm with your password'), pw),
      buttons: [{ label: 'Cancel', value: null }, { label: okLabel, value: 'ok', primary: true, danger }],
      onSubmit: async () => {
        try { result = (await post(path, { password: pw.value })) ?? {}; return true; } catch (e) { showError(e); return false; }
      },
    });
    return result;
  };

  return h('div', { class: 'card' }, h('h2', {}, 'Two-factor authentication'),
    me.twoFactor
      ? h('div', {},
          h('p', {}, h('span', { class: 'pill' }, 'on'), ' Signing in requires a code from your authenticator app. Apps that use WebDAV, CalDAV or CardDAV must use an app password.'),
          h('div', { class: 'row' },
            h('button', { class: 'btn', onclick: async () => {
              const r = await withPassword('New recovery codes', 'Create new codes', false, '/api/me/2fa/recovery-codes');
              if (r?.recoveryCodes) await modal({ title: 'New recovery codes', body: recoveryCodesBody(r.recoveryCodes), buttons: [{ label: 'Done', value: 'ok', primary: true }] });
            } }, icon('key'), 'New recovery codes'),
            h('button', { class: 'btn danger', onclick: async () => {
              if (await withPassword('Turn off two-factor authentication', 'Turn off', true, '/api/me/2fa/disable')) { toast('Two-factor authentication is off'); rerender(); }
            } }, 'Turn off')))
      : h('div', {},
          h('p', {}, 'Protect your account with a code from your phone in addition to your password.'),
          h('button', { class: 'btn primary', onclick: enable }, icon('lock'), 'Turn on two-factor authentication')),
  );
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
    resetTwoFactor: h('input', { type: 'checkbox' }),
    removePasskeys: h('input', { type: 'checkbox' }),
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
      user?.twoFactor && h('label', { class: 'check' }, f.resetTwoFactor, 'Reset two-factor authentication (lost phone)'),
      user?.passkeys > 0 && h('label', { class: 'check' }, f.removePasskeys, `Remove all passkeys (${user.passkeys})`),
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
        else await patch(`/api/admin/users/${encodeURIComponent(user.id)}`, { ...data, disabled: f.disabled.checked, password: f.password.value || undefined, resetTwoFactor: f.resetTwoFactor.checked || undefined, removePasskeys: f.removePasskeys.checked || undefined });
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
  const [users, groups, mail] = await Promise.all([get('/api/admin/users'), get('/api/admin/groups'), get('/api/admin/mail')]);
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
          h('td', {}, u.isAdmin ? h('span', { class: 'pill' }, 'admin') : '', u.twoFactor ? h('span', { class: 'pill' }, '2FA') : '', u.passkeys ? h('span', { class: 'pill' }, 'passkey') : '', u.disabled ? h('span', { class: 'pill warn' }, 'disabled') : ''),
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
    mailCard(mail, ctx, rerender),
  ));
}

// ---------------------------------------------------------------- email (SMTP)

function mailCard(mail, ctx, rerender) {
  const f = {
    host: h('input', { type: 'text', value: mail?.host ?? '', placeholder: 'smtp.example.com' }),
    port: h('input', { type: 'number', min: 1, max: 65535, value: mail?.port ?? 587 }),
    security: h('select', {}, [['starttls', 'STARTTLS (port 587)'], ['tls', 'SSL/TLS (port 465)'], ['none', 'None (local relay only)']]
      .map(([v, l]) => h('option', { value: v, selected: (mail?.security ?? 'starttls') === v }, l))),
    user: h('input', { type: 'text', value: mail?.user ?? '', autocomplete: 'off' }),
    password: h('input', { type: 'password', autocomplete: 'new-password', placeholder: mail?.hasPassword ? 'Unchanged' : '' }),
    fromName: h('input', { type: 'text', value: mail?.fromName ?? 'Cloudy' }),
    fromEmail: h('input', { type: 'email', value: mail?.fromEmail ?? '', placeholder: 'cloudy@example.com' }),
  };
  f.security.addEventListener('change', () => { f.port.value = f.security.value === 'tls' ? 465 : f.security.value === 'none' ? 25 : 587; });
  const status = h('span', { class: 'muted' }, mail?.host ? 'Email is on.' : 'Email is off.');
  const save = () => put('/api/admin/mail', {
    host: f.host.value.trim(), port: Number(f.port.value), security: f.security.value, user: f.user.value.trim(),
    password: f.password.value, fromName: f.fromName.value.trim(), fromEmail: f.fromEmail.value.trim(),
  });
  return h('form', { class: 'card form-grid', onsubmit: async (e) => {
    e.preventDefault();
    try { await save(); toast(f.host.value.trim() ? 'Email settings saved' : 'Email turned off'); rerender(); } catch (err) { showError(err); }
  } },
    h('h2', { class: 'wide' }, 'Email (SMTP)'),
    h('p', { class: 'muted wide' }, 'Lets Cloudy send password reset links, share notifications and security notices to the email address in each user\'s profile. Leave the server empty to turn email off.'),
    h('label', { class: 'field' }, h('span', {}, 'SMTP server'), f.host),
    h('label', { class: 'field' }, h('span', {}, 'Port'), f.port),
    h('label', { class: 'field' }, h('span', {}, 'Encryption'), f.security),
    h('label', { class: 'field' }, h('span', {}, 'Username'), f.user),
    h('label', { class: 'field' }, h('span', {}, 'Password'), f.password),
    h('label', { class: 'field' }, h('span', {}, 'From name'), f.fromName),
    h('label', { class: 'field wide' }, h('span', {}, 'From email'), f.fromEmail),
    h('div', { class: 'wide row end' }, status,
      h('button', { class: 'btn', type: 'button', onclick: async () => {
        const to = await promptDialog('Send test email', 'Send to', ctx.me.email || '', 'Send');
        if (!to) return;
        try {
          await save();
          status.textContent = 'Sending…';
          const r = await post('/api/admin/mail/test', { to });
          status.textContent = `Test email sent to ${r.to}.`;
          toast('Test email sent');
        } catch (err) { status.textContent = ''; showError(err); }
      } }, 'Save and send test'),
      h('button', { class: 'btn primary', type: 'submit' }, 'Save')),
  );
}
