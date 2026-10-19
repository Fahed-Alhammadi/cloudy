// Contacts app over CardDAV-backed address books.
import { get, post, put, patch, del } from './api.js';
import { h, icon, toast, showError, confirmDialog, promptDialog, showMenu, copyText } from './ui.js';

let currentBook = null;
let currentUri = null;
let filter = '';

const initials = (c) => (c.fn || '?').split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase();

export async function renderContacts(main, ctx) {
  const books = await get('/api/addressbooks');
  const rerender = () => renderContacts(main, ctx).catch(showError);
  if (!books.length) {
    main.replaceChildren(h('section', { class: 'empty' }, icon('contacts'), h('p', {}, 'No address books'),
      h('button', { class: 'btn primary', onclick: async () => { await post('/api/addressbooks', { name: 'Contacts' }).catch(showError); rerender(); } }, 'Create address book')));
    return;
  }
  if (!books.some((b) => b.id === currentBook)) currentBook = books[0].id;
  const book = books.find((b) => b.id === currentBook);
  const contacts = await get(`/api/addressbooks/${book.id}/contacts`);

  const list = h('ul', { class: 'contact-list' });
  const detail = h('div', { class: 'contact-detail' });
  const search = h('input', { type: 'search', placeholder: 'Search contacts', value: filter, 'aria-label': 'Search contacts' });

  const drawList = () => {
    const q = filter.toLowerCase();
    const shown = contacts.filter((c) => !q || [c.fn, c.org, ...c.emails, ...c.phones].join(' ').toLowerCase().includes(q));
    list.replaceChildren(...(shown.length ? shown.map((c) => h('li', {},
      h('button', { class: `contact-item ${c.uri === currentUri ? 'active' : ''}`, onclick: () => { currentUri = c.uri; drawList(); drawDetail(c); } },
        h('span', { class: 'avatar' }, initials(c)),
        h('span', { class: 'grow' }, h('strong', {}, c.fn), h('small', { class: 'muted' }, c.emails[0] || c.phones[0] || c.org || ''))))) : [h('li', { class: 'muted pad' }, contacts.length ? 'No matches' : 'No contacts yet')]));
  };
  search.addEventListener('input', () => { filter = search.value; drawList(); });

  const drawDetail = (c) => {
    const isNew = !c;
    c = c || { fn: '', givenName: '', familyName: '', emails: [], phones: [], org: '', title: '', address: '', birthday: '', note: '' };
    const f = {
      givenName: h('input', { type: 'text', value: c.givenName, autocomplete: 'off' }),
      familyName: h('input', { type: 'text', value: c.familyName, autocomplete: 'off' }),
      org: h('input', { type: 'text', value: c.org }),
      title: h('input', { type: 'text', value: c.title }),
      emails: h('textarea', { rows: 2, value: c.emails.join('\n'), placeholder: 'One per line' }),
      phones: h('textarea', { rows: 2, value: c.phones.join('\n'), placeholder: 'One per line' }),
      address: h('input', { type: 'text', value: c.address }),
      birthday: h('input', { type: 'date', value: /^\d{4}-\d{2}-\d{2}/.test(c.birthday) ? c.birthday.slice(0, 10) : '' }),
      note: h('textarea', { rows: 3, value: c.note }),
    };
    const save = async (e) => {
      e.preventDefault();
      const data = {
        givenName: f.givenName.value, familyName: f.familyName.value, org: f.org.value, title: f.title.value,
        emails: f.emails.value.split('\n'), phones: f.phones.value.split('\n'), address: f.address.value,
        birthday: f.birthday.value, note: f.note.value,
      };
      try {
        const saved = isNew
          ? await post(`/api/addressbooks/${book.id}/contacts`, data)
          : await put(`/api/addressbooks/${book.id}/contacts/${encodeURIComponent(c.uri)}`, data);
        currentUri = saved.uri;
        toast('Contact saved');
        rerender();
      } catch (err) { showError(err); }
    };
    detail.replaceChildren(h('form', { class: 'form-grid', onsubmit: save },
      h('div', { class: 'contact-head wide' }, h('span', { class: 'avatar big' }, isNew ? icon('plus') : initials(c)), h('h2', {}, isNew ? 'New contact' : c.fn)),
      h('label', { class: 'field' }, h('span', {}, 'First name'), f.givenName),
      h('label', { class: 'field' }, h('span', {}, 'Last name'), f.familyName),
      h('label', { class: 'field' }, h('span', {}, 'Organization'), f.org),
      h('label', { class: 'field' }, h('span', {}, 'Job title'), f.title),
      h('label', { class: 'field' }, h('span', {}, 'Email'), f.emails),
      h('label', { class: 'field' }, h('span', {}, 'Phone'), f.phones),
      h('label', { class: 'field wide' }, h('span', {}, 'Address'), f.address),
      h('label', { class: 'field' }, h('span', {}, 'Birthday'), f.birthday),
      h('label', { class: 'field wide' }, h('span', {}, 'Notes'), f.note),
      h('div', { class: 'wide row end' },
        !isNew && h('button', { type: 'button', class: 'btn danger', onclick: async () => {
          if (!(await confirmDialog('Delete contact', `Delete ${c.fn}?`, 'Delete', true))) return;
          await del(`/api/addressbooks/${book.id}/contacts/${encodeURIComponent(c.uri)}`).catch(showError);
          currentUri = null;
          rerender();
        } }, icon('trash'), 'Delete'),
        h('button', { type: 'submit', class: 'btn primary' }, icon('save'), 'Save')),
    ));
    f.givenName.focus();
  };

  const bookSelect = h('select', { 'aria-label': 'Address book', onchange: (e) => { currentBook = Number(e.target.value); currentUri = null; rerender(); } },
    books.map((b) => h('option', { value: b.id, selected: b.id === book.id }, b.name)));

  main.replaceChildren(h('section', { class: 'contacts-view' },
    h('div', { class: 'contacts-side' },
      h('div', { class: 'row' }, bookSelect, h('button', { class: 'icon-btn', 'aria-label': 'Address book options', onclick: (e) => showMenu(e.currentTarget, [
        { label: 'New address book', icon: 'plus', onclick: async () => {
          const name = await promptDialog('New address book', 'Name', '', 'Create');
          if (name) { const b = await post('/api/addressbooks', { name }).catch(showError); if (b) currentBook = b.id; rerender(); }
        } },
        { label: 'Rename', icon: 'edit', onclick: async () => {
          const name = await promptDialog('Rename address book', 'Name', book.name, 'Save');
          if (name) await patch(`/api/addressbooks/${book.id}`, { name }).then(rerender, showError);
        } },
        { label: 'Copy CardDAV link', icon: 'link', onclick: () => copyText(`${location.origin}/dav/addressbooks/${encodeURIComponent(ctx.me.id)}/${encodeURIComponent(book.uri)}/`) },
        '-',
        { label: 'Delete address book', icon: 'trash', danger: true, onclick: async () => {
          if (await confirmDialog('Delete address book', `Delete "${book.name}" and all its contacts?`, 'Delete', true)) await del(`/api/addressbooks/${book.id}`).then(rerender, showError);
        } },
      ]) }, icon('more'))),
      search,
      h('button', { class: 'btn primary block', onclick: () => { currentUri = null; drawList(); drawDetail(null); } }, icon('plus'), 'New contact'),
      list),
    detail));
  drawList();
  const selected = contacts.find((c) => c.uri === currentUri);
  if (selected) drawDetail(selected);
  else detail.replaceChildren(h('div', { class: 'empty' }, icon('contacts'), h('p', {}, `${contacts.length} contact(s)`)));
}
