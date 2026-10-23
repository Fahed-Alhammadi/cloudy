// Calendar app: month grid over CalDAV-backed calendars.
import { get, post, put, patch, del } from './api.js';
import { h, icon, toast, showError, modal, confirmDialog, promptDialog, showMenu, copyText } from './ui.js';
import { t, locale } from './i18n.js';

const HIDDEN_KEY = 'cloudy-hidden-calendars';
const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const hm = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const parseDay = (s) => { const [y, m, d] = s.slice(0, 10).split('-').map(Number); return new Date(y, m - 1, d); };
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const toDate = (v, allDay) => (allDay ? parseDay(v) : new Date(v));

function loadHidden() {
  try { return new Set(JSON.parse(localStorage.getItem(HIDDEN_KEY) || '[]')); } catch { return new Set(); }
}
function saveHidden(set) {
  try { localStorage.setItem(HIDDEN_KEY, JSON.stringify([...set])); } catch { /* storage unavailable */ }
}

let month = (() => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1); })();

export async function renderCalendar(main, ctx) {
  const calendars = await get('/api/calendars');
  const hidden = loadHidden();
  const rerender = () => renderCalendar(main, ctx).catch(showError);

  // Grid starts on Monday.
  const gridStart = addDays(month, -((month.getDay() + 6) % 7));
  const gridEnd = addDays(gridStart, 42);
  const visible = calendars.filter((c) => !hidden.has(c.id));
  const events = (await Promise.all(visible.map((c) =>
    get(`/api/calendars/${c.id}/events?start=${gridStart.toISOString()}&end=${gridEnd.toISOString()}`).catch(() => []),
  ))).flat();
  const colorOf = Object.fromEntries(calendars.map((c) => [c.id, c.color || '#0082c9']));

  const side = h('aside', { class: 'cal-side' },
    h('button', { class: 'btn primary block', onclick: () => eventDialog(null, calendars, rerender, new Date()) }, icon('plus'), t('New event')),
    h('h3', {}, t('Calendars')),
    h('ul', { class: 'cal-list' }, calendars.map((c) => h('li', {},
      h('label', { class: 'check' },
        h('input', { type: 'checkbox', checked: !hidden.has(c.id), onchange: (e) => { e.target.checked ? hidden.delete(c.id) : hidden.add(c.id); saveHidden(hidden); rerender(); } }),
        h('span', { class: 'dot', style: { background: c.color || '#0082c9' } }), c.name),
      h('button', { class: 'icon-btn', 'aria-label': t('Options for {name}', { name: c.name }), onclick: (e) => showMenu(e.currentTarget, [
        { label: t('Rename'), icon: 'edit', onclick: async () => {
          const name = await promptDialog(t('Rename calendar'), t('Name'), c.name, t('Save'));
          if (name) await patch(`/api/calendars/${c.id}`, { name }).then(rerender, showError);
        } },
        { label: t('Change color'), icon: 'settings', onclick: () => colorDialog(c, rerender) },
        { label: t('Copy CalDAV link'), icon: 'link', onclick: () => copyText(`${location.origin}/dav/calendars/${encodeURIComponent(ctx.me.id)}/${encodeURIComponent(c.uri)}/`) },
        '-',
        { label: t('Delete'), icon: 'trash', danger: true, onclick: async () => {
          if (await confirmDialog(t('Delete calendar'), t('Delete "{name}" and all its events?', { name: c.name }), t('Delete'), true)) await del(`/api/calendars/${c.id}`).then(rerender, showError);
        } },
      ]) }, icon('more')),
    ))),
    h('button', { class: 'btn ghost block', onclick: async () => {
      const name = await promptDialog(t('New calendar'), t('Name'), '', t('Create'));
      if (name) await post('/api/calendars', { name }).then(rerender, showError);
    } }, icon('plus'), t('New calendar')),
  );

  const title = month.toLocaleDateString(locale(), { month: 'long', year: 'numeric' });
  const header = h('div', { class: 'toolbar' },
    h('h1', { class: 'view-title' }, icon('calendar'), title), h('div', { class: 'grow' }),
    h('button', { class: 'icon-btn', 'aria-label': t('Previous month'), onclick: () => { month = new Date(month.getFullYear(), month.getMonth() - 1, 1); rerender(); } }, icon('left')),
    h('button', { class: 'btn', onclick: () => { const d = new Date(); month = new Date(d.getFullYear(), d.getMonth(), 1); rerender(); } }, t('Today')),
    h('button', { class: 'icon-btn', 'aria-label': t('Next month'), onclick: () => { month = new Date(month.getFullYear(), month.getMonth() + 1, 1); rerender(); } }, icon('right')),
  );

  const weekdays = [...Array(7)].map((_, i) => addDays(gridStart, i).toLocaleDateString(locale(), { weekday: 'short' }));
  const grid = h('div', { class: 'month-grid', role: 'grid' }, weekdays.map((w) => h('div', { class: 'weekday', role: 'columnheader' }, w)));
  const today = ymd(new Date());
  for (let i = 0; i < 42; i++) {
    const day = addDays(gridStart, i);
    const next = addDays(day, 1);
    const dayEvents = events
      .filter((e) => toDate(e.start, e.allDay) < next && toDate(e.end, e.allDay) > day)
      .sort((a, b) => (b.allDay - a.allDay) || (toDate(a.start, a.allDay) - toDate(b.start, b.allDay)));
    const cell = h('div', {
      class: `day ${day.getMonth() !== month.getMonth() ? 'other' : ''} ${ymd(day) === today ? 'today' : ''}`,
      role: 'gridcell', tabindex: 0,
      onclick: () => eventDialog(null, calendars, rerender, day),
      onkeydown: (e) => { if (e.key === 'Enter') eventDialog(null, calendars, rerender, day); },
    },
      h('span', { class: 'day-num' }, day.getDate()),
      dayEvents.slice(0, 4).map((ev) => h('button', {
        class: `event ${ev.allDay ? 'all-day' : ''}`,
        style: ev.allDay ? { background: colorOf[ev.calendarId] } : { '--cal': colorOf[ev.calendarId] },
        title: ev.summary,
        onclick: (e) => { e.stopPropagation(); eventDialog(ev, calendars, rerender); },
      }, !ev.allDay && h('span', { class: 'time' }, hm(new Date(ev.start))), ev.summary || t('(no title)'))),
      dayEvents.length > 4 && h('span', { class: 'more-events' }, t('+{n} more', { n: dayEvents.length - 4 })),
    );
    grid.append(cell);
  }

  main.replaceChildren(h('section', { class: 'calendar-view' }, side, h('div', { class: 'cal-main' }, header, grid)));
}

async function colorDialog(c, rerender) {
  const input = h('input', { type: 'color', value: c.color || '#0082c9', 'aria-label': t('Color') });
  if ((await modal({ title: t('Color of "{name}"', { name: c.name }), body: input, buttons: [{ label: t('Cancel'), value: null }, { label: t('Save'), value: 'ok', primary: true }] })) === 'ok') {
    await patch(`/api/calendars/${c.id}`, { color: input.value }).then(rerender, showError);
  }
}

async function eventDialog(ev, calendars, rerender, day) {
  if (!calendars.length) return toast(t('Create a calendar first'), 'error');
  const allDay = ev ? ev.allDay : false;
  // Recurring events are edited as a series, so show the series' own times.
  const start = ev ? toDate(ev.recurring ? ev.masterStart : ev.start, allDay) : new Date(day.getFullYear(), day.getMonth(), day.getDate(), 9);
  let end = ev ? toDate(ev.recurring ? ev.masterEnd : ev.end, allDay) : new Date(start.getTime() + 3600000);
  if (allDay) end = addDays(end, -1); // all-day ends are exclusive

  const f = {
    summary: h('input', { type: 'text', required: true, value: ev?.summary ?? '', placeholder: t('Event title') }),
    allDay: h('input', { type: 'checkbox', checked: allDay }),
    startDate: h('input', { type: 'date', required: true, value: ymd(start) }),
    startTime: h('input', { type: 'time', value: hm(start) }),
    endDate: h('input', { type: 'date', required: true, value: ymd(end) }),
    endTime: h('input', { type: 'time', value: hm(end) }),
    rrule: h('select', {}, [['', t('Does not repeat')], ['DAILY', t('Daily')], ['WEEKLY', t('Weekly')], ['MONTHLY', t('Monthly')], ['YEARLY', t('Yearly')]]
      .map(([v, l]) => h('option', { value: v, selected: (ev?.rrule ?? '') === v }, l))),
    calendar: h('select', { disabled: !!ev }, calendars.map((c) => h('option', { value: c.id, selected: ev ? ev.calendarId === c.id : false }, c.name))),
    location: h('input', { type: 'text', value: ev?.location ?? '' }),
    description: h('textarea', { rows: 3, value: ev?.description ?? '' }),
  };
  const syncTimes = () => { f.startTime.hidden = f.endTime.hidden = f.allDay.checked; };
  f.allDay.addEventListener('change', syncTimes);
  syncTimes();

  const body = h('div', { class: 'form-grid' },
    h('label', { class: 'field wide' }, h('span', {}, t('Title')), f.summary),
    h('label', { class: 'check wide' }, f.allDay, t('All day')),
    h('label', { class: 'field' }, h('span', {}, t('Starts')), h('div', { class: 'row' }, f.startDate, f.startTime)),
    h('label', { class: 'field' }, h('span', {}, t('Ends')), h('div', { class: 'row' }, f.endDate, f.endTime)),
    h('label', { class: 'field' }, h('span', {}, t('Repeat')), f.rrule),
    h('label', { class: 'field' }, h('span', {}, t('Calendar')), f.calendar),
    h('label', { class: 'field wide' }, h('span', {}, t('Location')), f.location),
    h('label', { class: 'field wide' }, h('span', {}, t('Description')), f.description),
    ev?.recurring && h('p', { class: 'muted wide' }, t('Changes apply to every occurrence of this repeating event.')),
  );

  const payload = () => {
    if (f.allDay.checked) {
      return { allDay: true, start: f.startDate.value, end: ymd(addDays(parseDay(f.endDate.value), 1)) };
    }
    return {
      allDay: false,
      start: new Date(`${f.startDate.value}T${f.startTime.value || '00:00'}`).toISOString(),
      end: new Date(`${f.endDate.value}T${f.endTime.value || '00:00'}`).toISOString(),
    };
  };

  const buttons = [{ label: t('Cancel'), value: null }];
  if (ev) buttons.push({ label: t('Delete'), value: 'delete', danger: true });
  buttons.push({ label: t('Save'), value: 'save', primary: true });
  await modal({
    title: ev ? t('Edit event') : t('New event'), body, buttons, wide: true,
    onSubmit: async (v) => {
      try {
        if (v === 'delete') {
          if (!(await confirmDialog(t('Delete event'), ev.recurring ? t('Delete all occurrences of this event?') : t('Delete this event?'), t('Delete'), true))) return false;
          await del(`/api/calendars/${ev.calendarId}/events/${encodeURIComponent(ev.uri)}`);
        } else {
          const data = { summary: f.summary.value, location: f.location.value, description: f.description.value, rrule: f.rrule.value, ...payload() };
          if (ev) await put(`/api/calendars/${ev.calendarId}/events/${encodeURIComponent(ev.uri)}`, data);
          else await post(`/api/calendars/${f.calendar.value}/events`, data);
        }
        rerender();
        return true;
      } catch (e) {
        showError(e);
        return false;
      }
    },
  });
}
