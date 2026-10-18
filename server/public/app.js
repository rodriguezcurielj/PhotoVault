'use strict';
const $ = id => document.getElementById(id);
const h = (tag, attrs = {}, ...kids) => {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (v != null && v !== false) el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(kid));
  return el;
};
const store = {
  get(k, d) { try { const v = localStorage.getItem('pv.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('pv.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

const state = {
  assets: new Map(),     // id -> asset (everything except the locked folder)
  view: 'photos',
  list: [],              // what the current grid shows, in order (used by the viewer)
  sel: new Set(),
  lastClicked: null,
  lockedPw: null,
  locked: [],
};
const thumb = a => `/api/assets/${a.id}/thumb?v=${a.updatedAt || 0}`;
const preview = a => `/api/assets/${a.id}/preview`;
const original = a => `/api/assets/${a.id}/original`;
const fmtBytes = b => b > 1e9 ? (b / 1e9).toFixed(1) + ' GB' : b > 1e6 ? (b / 1e6).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1e3)) + ' KB';
const fmtDur = s => { s = Math.round(s || 0); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
const place = a => [a.city, a.region, a.country].filter(Boolean).join(', ');

async function api(url, opts = {}) {
  const headers = { ...(opts.body && !(opts.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}), ...(opts.headers || {}) };
  if (state.lockedPw) headers['X-Unlock'] = state.lockedPw;
  const r = await fetch(url, { ...opts, headers, body: opts.body && !(opts.body instanceof FormData) ? JSON.stringify(opts.body) : opts.body });
  if (r.status === 401 && !url.includes('/login')) { showAuth(false); throw new Error('Not logged in'); }
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || `Request failed (${r.status})`);
  return data;
}
function toast(msg, ms = 2600) {
  const t = $('toast');
  t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toast.timer); toast.timer = setTimeout(() => t.classList.add('hidden'), ms);
}

// ---------------------------------------------------------------- auth
let setupMode = false;
function showAuth(needsSetup) {
  setupMode = needsSetup;
  $('app').classList.add('hidden');
  $('auth').classList.remove('hidden');
  $('authMsg').textContent = needsSetup ? 'Create a password for PhotoVault.' : 'Enter your password.';
  $('authBtn').textContent = needsSetup ? 'Create password' : 'Log in';
  $('pw').focus();
}
$('authForm').onsubmit = async e => {
  e.preventDefault();
  $('authErr').textContent = '';
  $('authBtn').disabled = true;
  try {
    await api(setupMode ? '/api/setup' : '/api/login', { method: 'POST', body: { password: $('pw').value, device: 'browser' } });
    $('pw').value = '';
    start();
  } catch (err) { $('authErr').textContent = err.message; }
  $('authBtn').disabled = false;
};
$('logoutBtn').onclick = async () => { await api('/api/logout', { method: 'POST' }); state.assets.clear(); showAuth(false); };

// ---------------------------------------------------------------- data
let lastSync = 0;
async function sync(full = false) {
  const data = await api('/api/assets' + (full || !lastSync ? '' : `?since=${lastSync}`));
  if (full || !lastSync) state.assets.clear();
  for (const a of data.assets) state.assets.set(a.id, a);
  for (const id of data.deleted) state.assets.delete(id);
  lastSync = data.now;
}
const all = () => [...state.assets.values()].sort((a, b) => b.takenAt - a.takenAt);
const viewFilters = {
  photos: a => !a.trashedAt && !a.archived,
  favorites: a => !a.trashedAt && a.favorite,
  videos: a => !a.trashedAt && a.type === 'video',
  archive: a => !a.trashedAt && a.archived,
  trash: a => !!a.trashedAt,
};
async function refreshStats() {
  try {
    const s = await api('/api/stats');
    $('storage').textContent = `${(s.photos || 0).toLocaleString()} photos · ${(s.videos || 0).toLocaleString()} videos · ${fmtBytes(s.bytes || 0)}` +
      (s.freeBytes ? ` · ${fmtBytes(s.freeBytes)} free` : '');
  } catch { /* ignore */ }
}

// ---------------------------------------------------------------- routing
function go(hash) { if (location.hash !== '#' + hash) location.hash = hash; else route(); }
window.addEventListener('hashchange', route);
function route() {
  const [view, ...rest] = (location.hash.slice(1) || 'photos').split('/');
  const arg = decodeURIComponent(rest.join('/'));
  state.view = view;
  clearSelection();
  document.querySelectorAll('#nav a[data-view]').forEach(a => a.classList.toggle('active', a.dataset.view === view));
  $('nav').classList.remove('open');
  const c = $('content');
  c.innerHTML = '';
  c.scrollTop = 0;
  ({
    photos: viewPhotos, favorites: () => viewSimple('Favorites', 'favorites', 'Tap ♡ on any photo to add it here.'),
    videos: () => viewSimple('Videos', 'videos', 'Videos you upload show up here.'),
    archive: () => viewSimple('Archive', 'archive', 'Archived photos are hidden from Photos but stay in albums and search.'),
    trash: viewTrash, albums: viewAlbums, album: () => viewAlbum(Number(arg)), explore: viewExplore,
    search: () => viewSearch(arg), place: () => viewSearch(arg, true), map: viewMap, locked: viewLocked,
  }[view] || viewPhotos)();
}
document.querySelectorAll('#nav a[data-view]').forEach(a => (a.onclick = () => go(a.dataset.view)));
$('navBtn').onclick = () => $('nav').classList.toggle('open');
$('searchForm').onsubmit = e => { e.preventDefault(); const q = $('search').value.trim(); if (q) go('search/' + encodeURIComponent(q)); };

// ---------------------------------------------------------------- grid
const tileSize = () => store.get('tile', 180);
function applyTileSize() {
  document.documentElement.style.setProperty('--tile', tileSize() + 'px');
  document.documentElement.style.setProperty('--cols', Math.max(2, Math.min(8, Math.round(900 / tileSize()))));
}
applyTileSize();
// Ctrl + mouse wheel or pinch on a trackpad changes the grid size, like Google Photos.
$('content').addEventListener('wheel', e => {
  if (!e.ctrlKey) return;
  e.preventDefault();
  store.set('tile', Math.max(90, Math.min(360, tileSize() * (e.deltaY > 0 ? 0.9 : 1.1))));
  applyTileSize();
}, { passive: false });

function groupKey(t) { const d = new Date(t); return `${d.getFullYear()}-${d.getMonth()}`; }
function dayKey(t) { const d = new Date(t); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; }
function dayLabel(t) {
  const d = new Date(t), today = new Date();
  const diff = Math.round((new Date(today.toDateString()) - new Date(d.toDateString())) / 864e5);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  return d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', ...(d.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}) });
}

function renderGrid(container, list, { groups = true, empty } = {}) {
  state.list = list;
  if (!list.length) { container.append(empty || h('div', { class: 'empty' }, h('strong', {}, 'Nothing here'))); return; }
  const index = new Map(list.map((a, i) => [a.id, i]));
  let lastMonth = null, grid = null, lastDay = null;
  const frag = document.createDocumentFragment();
  const tileFor = a => {
    const t = h('div', { class: 'tile' + (state.sel.has(a.id) ? ' sel' : ''), 'data-id': a.id },
      h('img', { src: thumb(a), loading: 'lazy', decoding: 'async', alt: '' }),
      h('div', { class: 'check' }, '✓'),
      a.type === 'video' ? h('div', { class: 'badge' }, '▶ ' + fmtDur(a.duration)) : null,
      a.favorite && state.view !== 'favorites' ? h('div', { class: 'fav' }, '♥') : null);
    return t;
  };
  if (!groups) {
    grid = h('div', { class: 'grid' });
    list.forEach(a => grid.append(tileFor(a)));
    frag.append(grid);
  } else {
    // Apple Photos style: one continuous grid per month, titled with the month and its places.
    let places = null;
    for (const a of list) {
      const mk = groupKey(a.takenAt);
      if (mk !== lastDay) {
        const d = new Date(a.takenAt);
        const month = d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
        places = h('div', { class: 'places' });
        const head = h('div', { class: 'dayhead' },
          h('div', { class: 'grow' }, h('div', { class: 'month' }, month), places),
          h('div', { class: 'check', 'data-day': mk }, '✓'));
        grid = h('div', { class: 'grid' });
        frag.append(h('div', { class: 'day', 'data-day': mk }, head, grid));
        places._seen = [];
        lastDay = mk;
      }
      if (a.city && !places._seen.includes(a.city) && places._seen.length < 3) {
        places._seen.push(a.city);
        places.textContent = places._seen.join(' · ');
      }
      grid.append(tileFor(a));
    }
  }
  container.append(frag);

  container.onclick = e => {
    const dayCheck = e.target.closest('.dayhead .check');
    if (dayCheck) {
      const dayList = list.filter(a => groupKey(a.takenAt) === dayCheck.dataset.day);
      const allOn = dayList.every(a => state.sel.has(a.id));
      dayList.forEach(a => (allOn ? state.sel.delete(a.id) : state.sel.add(a.id)));
      syncSelection();
      return;
    }
    const tile = e.target.closest('.tile');
    if (!tile) return;
    const id = Number(tile.dataset.id);
    if (state.sel.size || e.target.closest('.check') || e.ctrlKey || e.metaKey || e.shiftKey) {
      if (e.shiftKey && state.lastClicked != null) {
        const [x, y] = [index.get(state.lastClicked), index.get(id)].sort((p, q) => p - q);
        for (let i = x; i <= y; i++) state.sel.add(list[i].id);
      } else if (state.sel.has(id)) state.sel.delete(id);
      else state.sel.add(id);
      state.lastClicked = id;
      syncSelection();
    } else {
      openViewer(list, index.get(id));
    }
  };
  // Long-press on touch screens starts selecting.
  let pressTimer = null;
  container.ontouchstart = e => {
    const tile = e.target.closest('.tile');
    if (!tile) return;
    pressTimer = setTimeout(() => { state.sel.add(Number(tile.dataset.id)); state.lastClicked = Number(tile.dataset.id); syncSelection(); navigator.vibrate?.(20); }, 450);
  };
  container.ontouchend = container.ontouchmove = () => clearTimeout(pressTimer);
}

// ---------------------------------------------------------------- selection
function clearSelection() { state.sel.clear(); syncSelection(); }
$('selClear').onclick = clearSelection;
function syncSelection() {
  document.querySelectorAll('.tile').forEach(t => t.classList.toggle('sel', state.sel.has(Number(t.dataset.id))));
  document.querySelectorAll('.dayhead .check').forEach(c => {
    const tiles = [...document.querySelectorAll(`.day[data-day="${c.dataset.day}"] .tile`)];
    c.classList.toggle('on', tiles.length > 0 && tiles.every(t => state.sel.has(Number(t.dataset.id))));
  });
  const n = state.sel.size;
  $('content').classList.toggle('selecting', n > 0);
  $('selbar').classList.toggle('hidden', !n);
  $('selCount').textContent = `${n} selected`;
  const acts = $('selActions');
  acts.innerHTML = '';
  if (!n) return;
  const ids = [...state.sel];
  const btn = (label, fn, cls) => acts.append(h('button', { class: cls || '', onclick: fn }, label));
  const v = state.view;
  if (v === 'trash') {
    btn('Restore', () => bulk('restore', ids));
    btn('Delete forever', () => confirmDo(`Delete ${n} item${n > 1 ? 's' : ''} forever?`, 'This can’t be undone.', 'Delete', () => bulk('delete', ids)), 'danger');
    return;
  }
  if (v === 'locked') {
    btn('Move out of locked folder', () => bulk('unlock', ids));
    btn('Download', () => download(ids));
    return;
  }
  const allFav = ids.every(id => state.assets.get(id)?.favorite);
  btn(allFav ? '♥ Unfavorite' : '♡ Favorite', () => bulk(allFav ? 'unfavorite' : 'favorite', ids));
  btn('＋ Add to album', () => pickAlbum(ids));
  btn('⤓ Download', () => download(ids));
  if (v === 'album') btn('Remove from album', async () => { await api(`/api/albums/${state.albumId}/remove`, { method: 'POST', body: { ids } }); route(); });
  btn(v === 'archive' ? 'Unarchive' : 'Archive', () => bulk(v === 'archive' ? 'unarchive' : 'archive', ids));
  btn('🔒 Lock', () => withUnlock(() => bulk('lock', ids)));
  btn('Adjust date', () => adjustDate(ids));
  btn('🗑 Trash', () => bulk('trash', ids), 'danger');
}
async function bulk(action, ids, quiet) {
  await api('/api/assets/bulk', { method: 'POST', body: { action, ids } });
  const msgs = { trash: 'Moved to trash', restore: 'Restored', delete: 'Deleted forever', archive: 'Archived', unarchive: 'Moved back to Photos',
    favorite: 'Added to favorites', unfavorite: 'Removed from favorites', lock: 'Moved to locked folder', unlock: 'Moved out of locked folder' };
  await sync();
  if (state.view === 'locked') await loadLocked();
  if (!quiet) toast(`${msgs[action] || 'Done'} · ${ids.length} item${ids.length > 1 ? 's' : ''}`);
  if (action === 'trash') undoable(() => bulk('restore', ids));
  clearSelection();
  route();
  refreshStats();
}
function undoable(fn) {
  const t = $('toast');
  t.append(' ', h('button', { onclick: () => { t.classList.add('hidden'); fn(); } }, 'Undo'));
  clearTimeout(toast.timer); toast.timer = setTimeout(() => t.classList.add('hidden'), 6000);
}
function download(ids) {
  if (ids.length === 1) { location.href = original(state.assets.get(ids[0]) || { id: ids[0] }) + '?download=1'; return; }
  location.href = `/api/download?ids=${ids.join(',')}`;
}

// ---------------------------------------------------------------- modal helpers
function modal(...content) {
  const box = $('modalBox');
  box.innerHTML = '';
  box.append(...content);
  $('modal').classList.remove('hidden');
  const first = box.querySelector('input,textarea');
  if (first) setTimeout(() => first.focus(), 30);
}
const closeModal = () => $('modal').classList.add('hidden');
$('modal').onclick = e => { if (e.target === $('modal')) closeModal(); };
function confirmDo(title, body, label, fn) {
  modal(h('h3', {}, title), h('div', { class: 'muted' }, body),
    h('div', { class: 'actions' }, h('button', { onclick: closeModal }, 'Cancel'), h('button', { class: 'primary', onclick: () => { closeModal(); fn(); } }, label)));
}
function prompt2(title, value, label, fn, type = 'text') {
  const input = h('input', { type, value: value || '' });
  const form = h('form', { onsubmit: e => { e.preventDefault(); closeModal(); fn(input.value); } },
    h('div', { style: 'display:grid;gap:12px' }, h('h3', {}, title), input,
      h('div', { class: 'actions' }, h('button', { type: 'button', onclick: closeModal }, 'Cancel'), h('button', { class: 'primary' }, label))));
  modal(form);
}
function menu(anchor, items) {
  document.querySelectorAll('.menu').forEach(m => m.remove());
  const m = h('div', { class: 'menu' }, items.filter(Boolean).map(([label, fn]) => h('button', { onclick: () => { m.remove(); fn(); } }, label)));
  document.body.append(m);
  const r = anchor.getBoundingClientRect();
  m.style.top = Math.min(r.bottom + 4, innerHeight - m.offsetHeight - 8) + 'px';
  m.style.left = Math.max(8, Math.min(r.right - m.offsetWidth, innerWidth - m.offsetWidth - 8)) + 'px';
  setTimeout(() => document.addEventListener('click', function off(e) { if (!m.contains(e.target)) { m.remove(); document.removeEventListener('click', off); } }), 0);
}
async function pickAlbum(ids) {
  const albums = await api('/api/albums');
  const add = async (id, name) => {
    closeModal();
    await api(`/api/albums/${id}/assets`, { method: 'POST', body: { ids } });
    toast(`Added ${ids.length} to “${name}”`);
    clearSelection();
  };
  modal(h('h3', {}, 'Add to album'),
    h('div', { class: 'pick', onclick: () => { closeModal(); prompt2('New album', '', 'Create', async name => {
      const al = await api('/api/albums', { method: 'POST', body: { name, ids } });
      toast(`Created “${al.name}”`); clearSelection();
    }); } }, h('div', { class: 'ph', style: 'display:grid;place-items:center;font-size:22px' }, '＋'), 'New album'),
    albums.map(al => h('div', { class: 'pick', onclick: () => add(al.id, al.name) },
      al.cover ? h('img', { src: `/api/assets/${al.cover}/thumb` }) : h('div', { class: 'ph' }),
      h('div', {}, h('div', {}, al.name), h('div', { class: 'muted' }, `${al.count} items`)))));
}
function adjustDate(ids) {
  const first = state.assets.get(ids[0]);
  const local = new Date(first.takenAt - new Date(first.takenAt).getTimezoneOffset() * 6e4).toISOString().slice(0, 16);
  const input = h('input', { type: 'datetime-local', value: local });
  modal(h('h3', {}, ids.length > 1 ? `Shift dates of ${ids.length} items` : 'Edit date & time'),
    h('div', { class: 'muted' }, ids.length > 1 ? 'Set the new date for the first item; the others move by the same amount.' : ''), input,
    h('div', { class: 'actions' }, h('button', { onclick: closeModal }, 'Cancel'), h('button', { class: 'primary', onclick: async () => {
      closeModal();
      const shiftMs = new Date(input.value).getTime() - first.takenAt;
      await api('/api/assets/bulk', { method: 'POST', body: { action: 'setDate', ids, shiftMs } });
      await sync(); clearSelection(); route(); toast('Date updated');
    } }, 'Save')));
}

// ---------------------------------------------------------------- views
async function viewPhotos() {
  const c = $('content');
  const mem = h('div', { class: 'memories' });
  c.append(mem);
  const list = all().filter(viewFilters.photos);
  renderGrid(c, list, { empty: h('div', { class: 'empty' }, h('strong', {}, 'Your library is empty'), 'Click Upload, drag photos here, or turn on backup in the PhotoVault Android app.') });
  try {
    const groups = await api('/api/memories');
    if (state.view !== 'photos') return;
    if (!groups.length) mem.remove();
    for (const g of groups) {
      const cover = g.assets.find(a => a.type === 'image') || g.assets[0];
      mem.append(h('div', { class: 'memory', onclick: () => playStory(g) }, h('img', { src: thumb(cover), alt: '' }), h('span', {}, g.title)));
    }
  } catch { mem.remove(); }
}
function viewSimple(title, filter, emptyText) {
  const c = $('content');
  c.append(h('div', { class: 'toolbar' }, h('h2', {}, title)));
  renderGrid(c, all().filter(viewFilters[filter]), { empty: h('div', { class: 'empty' }, h('strong', {}, `No ${title.toLowerCase()} yet`), emptyText) });
}
function viewTrash() {
  const c = $('content');
  const list = all().filter(viewFilters.trash).sort((a, b) => b.trashedAt - a.trashedAt);
  c.append(h('div', { class: 'toolbar' }, h('h2', {}, 'Trash'),
    list.length ? h('button', { class: 'danger', onclick: () => confirmDo('Empty trash?', `${list.length} items will be deleted forever.`, 'Empty trash', async () => {
      await api('/api/trash/empty', { method: 'POST' }); await sync(true); route(); refreshStats(); toast('Trash emptied');
    }) }, 'Empty trash') : null),
    h('div', { class: 'muted', style: 'margin:0 4px 8px' }, 'Items are deleted forever after 30 days.'));
  renderGrid(c, list, { groups: false, empty: h('div', { class: 'empty' }, h('strong', {}, 'Trash is empty')) });
}

async function viewAlbums() {
  const c = $('content');
  c.append(h('div', { class: 'toolbar' }, h('h2', {}, 'Albums'),
    h('button', { class: 'primary', onclick: () => prompt2('New album', '', 'Create', async name => { const al = await api('/api/albums', { method: 'POST', body: { name } }); go('album/' + al.id); }) }, '＋ New album')));
  const albums = await api('/api/albums');
  const cards = h('div', { class: 'cards' });
  cards.append(
    card('♥', 'Favorites', all().filter(viewFilters.favorites).length + ' items', () => go('favorites'), all().find(viewFilters.favorites)),
    card('▶', 'Videos', all().filter(viewFilters.videos).length + ' items', () => go('videos'), all().find(viewFilters.videos)));
  for (const al of albums) cards.append(card('❐', al.name, `${al.count} items${al.shareToken ? ' · shared' : ''}`, () => go('album/' + al.id), al.cover && { id: al.cover }));
  c.append(cards);
}
function card(icon, title, sub, onclick, coverAsset) {
  return h('div', { class: 'card', onclick },
    h('div', { class: 'cover' }, coverAsset ? h('img', { src: `/api/assets/${coverAsset.id}/thumb`, loading: 'lazy' }) : icon),
    h('div', { class: 't' }, title), h('div', { class: 's' }, sub));
}
async function viewAlbum(id) {
  state.albumId = id;
  const c = $('content');
  let al;
  try { al = await api('/api/albums/' + id); } catch { c.append(h('div', { class: 'empty' }, h('strong', {}, 'Album not found'))); return; }
  const shareUrl = al.shareToken ? `${location.origin}/s/${al.shareToken}` : null;
  const more = h('button', { class: 'icon' }, '⋮');
  more.onclick = () => menu(more, [
    ['Rename', () => prompt2('Rename album', al.name, 'Save', async name => { await api('/api/albums/' + id, { method: 'PATCH', body: { name } }); route(); })],
    ['Add photos', () => addToAlbumFromLibrary(id)],
    ['Upload into this album', () => { uploadAlbum = id; $('fileInput').click(); }],
    ['Download all', () => download(al.assets.map(a => a.id))],
    ['Slideshow', () => al.assets.length && playStory({ title: al.name, assets: al.assets })],
    ['Delete album', () => confirmDo('Delete album?', 'The photos stay in your library.', 'Delete', async () => { await api('/api/albums/' + id, { method: 'DELETE' }); go('albums'); })],
  ]);
  c.append(h('div', { class: 'toolbar' }, h('button', { class: 'icon', onclick: () => go('albums') }, '←'), h('h2', {}, al.name),
    h('button', { onclick: () => shareAlbum(al) }, shareUrl ? '🔗 Shared' : '🔗 Share'), more),
    h('div', { class: 'muted', style: 'margin:0 4px 8px' }, `${al.count} items`));
  renderGrid(c, al.assets, { empty: h('div', { class: 'empty' }, h('strong', {}, 'This album is empty'), 'Select photos anywhere and choose “Add to album”.') });
}
function shareAlbum(al) {
  const render = token => {
    const url = token ? `${location.origin}/s/${token}` : null;
    modal(h('h3', {}, 'Share “' + al.name + '”'),
      h('div', { class: 'muted' }, url ? 'Anyone with this link can view and download the photos in this album (no password). Only people who can reach this server can open it.' : 'Create a link so others can view this album without your password.'),
      url ? h('input', { type: 'text', value: url, readonly: true, onclick: e => e.target.select() }) : null,
      h('div', { class: 'actions' },
        url ? h('button', { class: 'danger', onclick: async () => { await api(`/api/albums/${al.id}/share`, { method: 'POST', body: { enabled: false } }); closeModal(); route(); } }, 'Turn off link') : null,
        url ? h('button', { class: 'primary', onclick: () => { navigator.clipboard?.writeText(url); toast('Link copied'); } }, 'Copy link')
          : h('button', { class: 'primary', onclick: async () => { const r = await api(`/api/albums/${al.id}/share`, { method: 'POST', body: {} }); al.shareToken = r.shareToken; render(r.shareToken); } }, 'Create link'),
        h('button', { onclick: () => { closeModal(); route(); } }, 'Done')));
  };
  render(al.shareToken);
}
function addToAlbumFromLibrary(id) {
  toast('Select photos, then press “Add here”', 4000);
  go('photos');
  setTimeout(() => {
    const acts = $('selActions');
    const obs = new MutationObserver(() => {
      if (!state.sel.size || acts.querySelector('.addhere')) return;
      acts.prepend(h('button', { class: 'primary addhere', onclick: async () => {
        await api(`/api/albums/${id}/assets`, { method: 'POST', body: { ids: [...state.sel] } }); obs.disconnect(); go('album/' + id);
      } }, 'Add here'));
    });
    obs.observe(acts, { childList: true });
  }, 50);
}

async function viewExplore() {
  const c = $('content');
  const list = all().filter(viewFilters.photos);
  const chips = [['Videos', 'videos'], ['Favorites', 'favorites'], ['Screenshots', 'screenshots'], ['Selfies', 'selfie'], ['Recently added', null]];
  c.append(h('h2', { class: 'section' }, 'Categories'),
    h('div', { class: 'chips' }, chips.map(([label, q]) => h('button', { class: 'chip', onclick: () => (q ? go('search/' + q) : go('search/__recent')) }, label)),
      h('button', { class: 'chip', onclick: () => go('map') }, '⌖ Map')));
  const places = await api('/api/places');
  if (places.length) {
    c.append(h('h2', { class: 'section' }, 'Places'),
      h('div', { class: 'cards' }, places.slice(0, 60).map(p => card('⌖', p.name, `${p.count} · ${[p.region, p.country].filter(Boolean).join(', ')}`, () => go('place/' + p.city), { id: p.cover }))));
  }
  const cameras = new Map();
  for (const a of list) if (a.model) cameras.set(a.model, (cameras.get(a.model) || 0) + 1);
  if (cameras.size) c.append(h('h2', { class: 'section' }, 'Cameras'), h('div', { class: 'chips' }, [...cameras].sort((a, b) => b[1] - a[1]).map(([m, n]) => h('button', { class: 'chip', onclick: () => go('search/' + m) }, `${m} · ${n}`))));
  const years = new Map();
  for (const a of list) { const y = new Date(a.takenAt).getFullYear(); if (!years.has(y)) years.set(y, a); }
  if (years.size) c.append(h('h2', { class: 'section' }, 'Years'), h('div', { class: 'cards' }, [...years].map(([y, a]) => card('', String(y), '', () => go('search/' + y), a))));
}
async function viewSearch(q, isPlace) {
  const c = $('content');
  $('search').value = q === '__recent' ? '' : q;
  let list;
  if (q === '__recent') list = all().filter(viewFilters.photos).sort((a, b) => b.uploadedAt - a.uploadedAt).slice(0, 500);
  else if (isPlace) list = all().filter(a => !a.trashedAt && a.city === q);
  else list = await api('/api/search?q=' + encodeURIComponent(q));
  c.append(h('div', { class: 'toolbar' }, h('h2', {}, q === '__recent' ? 'Recently added' : isPlace ? q : `“${q}”`), h('span', { class: 'muted' }, `${list.length} results`)));
  renderGrid(c, list, { groups: q !== '__recent', empty: h('div', { class: 'empty' }, h('strong', {}, 'No results'), 'Try a place, a year, a month, a camera, “videos” or “favorites”.') });
}

function viewMap() {
  const c = $('content');
  const el = h('div', { id: 'mapView' });
  c.append(h('div', { class: 'toolbar' }, h('h2', {}, 'Map')), el);
  if (!window.L) { el.replaceWith(h('div', { class: 'empty' }, 'The map needs an internet connection to load.')); return; }
  const map = L.map(el, { worldCopyJump: true }).setView([20, 0], 2);
  L.tileLayer('/tiles/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
  const geo = all().filter(a => !a.trashedAt && a.lat != null);
  if (!geo.length) { c.append(h('div', { class: 'empty' }, 'No photos with a location yet.')); return; }
  const cluster = L.markerClusterGroup({
    iconCreateFunction: cl => {
      const first = cl.getAllChildMarkers()[0].asset;
      return L.divIcon({ html: `<img class="map-thumb" src="${thumb(first)}"><b style="position:absolute;right:-6px;top:-6px;background:#2f6fde;color:#fff;border-radius:9px;padding:0 5px;font-size:11px">${cl.getChildCount()}</b>`, className: '', iconSize: [44, 44] });
    },
  });
  for (const a of geo) {
    const m = L.marker([a.lat, a.lon], { icon: L.divIcon({ html: `<img class="map-thumb" src="${thumb(a)}">`, className: '', iconSize: [44, 44] }) });
    m.asset = a;
    m.on('click', () => openViewer(geo, geo.indexOf(a)));
    cluster.addLayer(m);
  }
  cluster.on('clusterclick', e => {
    const kids = e.layer.getAllChildMarkers().map(m => m.asset);
    if (map.getZoom() >= 16) { e.originalEvent?.preventDefault(); openViewer(kids, 0); }
  });
  map.addLayer(cluster);
  map.fitBounds(cluster.getBounds(), { padding: [30, 30], maxZoom: 12 });
}

function withUnlock(fn) {
  if (state.lockedPw) return fn();
  prompt2('Enter your password', '', 'Unlock', pw => { state.lockedPw = pw; fn(); }, 'password');
}
async function loadLocked() { state.locked = (await api('/api/assets?locked=1')).assets; }
function viewLocked() {
  const c = $('content');
  c.append(h('div', { class: 'toolbar' }, h('h2', {}, '🔒 Locked folder'), state.lockedPw ? h('button', { onclick: () => { state.lockedPw = null; route(); } }, 'Lock now') : null));
  if (!state.lockedPw) {
    c.append(h('div', { class: 'empty' }, h('strong', {}, 'Locked folder'), 'Photos here are hidden from Photos, search, albums and the map.', h('br'), h('br'),
      h('button', { class: 'primary', onclick: () => withUnlock(route) }, 'Unlock')));
    return;
  }
  loadLocked().then(() => {
    if (state.view !== 'locked') return;
    renderGrid(c, state.locked, { empty: h('div', { class: 'empty' }, h('strong', {}, 'Nothing locked'), 'Select photos and choose 🔒 Lock to move them here.') });
  }).catch(e => { state.lockedPw = null; toast(e.message); route(); });
}

// ---------------------------------------------------------------- memories story
function playStory(group) {
  const items = group.assets.filter(a => a.type === 'image');
  if (!items.length) return openViewer(group.assets, 0);
  let i = 0, timer;
  const bars = h('div', { class: 'bars' }, items.slice(0, 40).map(() => h('div', {}, h('i'))));
  const img = h('img');
  const el = h('div', { class: 'story' }, img, bars, h('div', { class: 'title' }, group.title));
  const close = () => { clearTimeout(timer); el.remove(); removeEventListener('keydown', key); };
  const show = n => {
    if (n < 0) n = 0;
    if (n >= items.length) return close();
    i = n;
    img.src = preview(items[i]);
    img.style.animation = 'none'; void img.offsetWidth; img.style.animation = '';
    [...bars.children].forEach((b, k) => { const fill = b.firstChild; fill.style.transition = 'none'; fill.style.width = k < i ? '100%' : '0'; });
    const cur = bars.children[i]?.firstChild;
    if (cur) requestAnimationFrame(() => requestAnimationFrame(() => { cur.style.transition = 'width 5s linear'; cur.style.width = '100%'; }));
    clearTimeout(timer); timer = setTimeout(() => show(i + 1), 5000);
  };
  const key = e => { if (e.key === 'Escape') close(); if (e.key === 'ArrowRight') show(i + 1); if (e.key === 'ArrowLeft') show(i - 1); };
  el.onclick = e => (e.clientX < innerWidth / 3 ? show(i - 1) : e.clientY < 80 && e.clientX > innerWidth - 80 ? close() : show(i + 1));
  addEventListener('keydown', key);
  document.body.append(el);
  show(0);
}

// ---------------------------------------------------------------- upload
let uploadAlbum = null;
$('uploadBtn').onclick = () => { uploadAlbum = state.view === 'album' ? state.albumId : null; $('fileInput').click(); };
$('fileInput').onchange = e => { uploadFiles([...e.target.files], uploadAlbum); e.target.value = ''; };
let dragDepth = 0;
const appVisible = () => !$('app').classList.contains('hidden');
addEventListener('dragenter', e => { if ([...e.dataTransfer.types].includes('Files') && appVisible()) { dragDepth++; $('drop').classList.remove('hidden'); } });
addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; $('drop').classList.add('hidden'); } });
addEventListener('dragover', e => e.preventDefault());
addEventListener('drop', e => {
  e.preventDefault(); dragDepth = 0; $('drop').classList.add('hidden');
  if (appVisible()) uploadFiles([...e.dataTransfer.files], state.view === 'album' ? state.albumId : null);
});

async function uploadFiles(files, albumId, extra = {}) {
  files = files.filter(f => /^(image|video)\//.test(f.type) || /\.(heic|heif|dng|mov|mkv)$/i.test(f.name));
  if (!files.length) return;
  $('progress').classList.remove('hidden');
  const total = files.reduce((s, f) => s + f.size, 0) || 1;
  let sent = 0, done = 0, failed = 0, dupes = 0;
  const batches = [];
  for (let i = 0, size = 0, batch = []; i < files.length; i++) {
    batch.push(files[i]); size += files[i].size;
    if (batch.length >= 10 || size > 200e6 || i === files.length - 1) { batches.push(batch); batch = []; size = 0; }
  }
  for (const batch of batches) {
    const fd = new FormData();
    if (albumId) fd.append('albumId', albumId);
    for (const [k, v] of Object.entries(extra)) fd.append(k, v);
    for (const f of batch) { fd.append('lastModified', extra.lastModified || f.lastModified); fd.append('files', f, f.name); }
    const size = batch.reduce((s, f) => s + f.size, 0);
    try {
      const res = await new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/upload');
        xhr.upload.onprogress = e => {
          $('progressBar').style.width = Math.min(100, ((sent + (e.loaded / (e.total || 1)) * size) / total) * 100) + '%';
          $('progressText').textContent = `Uploading ${Math.min(done + batch.length, files.length)} of ${files.length}…`;
        };
        xhr.onload = () => (xhr.status < 300 ? resolve(JSON.parse(xhr.responseText)) : reject(new Error(xhr.status)));
        xhr.onerror = () => reject(new Error('network'));
        xhr.send(fd);
      });
      for (const r of res.results) { if (r.error) failed++; else if (r.duplicate) dupes++; }
    } catch { failed += batch.length; }
    sent += size; done += batch.length;
  }
  $('progressBar').style.width = '100%';
  $('progressText').textContent = [`Uploaded ${files.length - failed - dupes}`, dupes && `${dupes} already in your library`, failed && `${failed} failed`].filter(Boolean).join(' · ');
  setTimeout(() => { $('progress').classList.add('hidden'); $('progressBar').style.width = '0'; }, 3500);
  await sync();
  route();
  refreshStats();
}

// ---------------------------------------------------------------- viewer
const V = { list: [], i: -1, zoom: 1, x: 0, y: 0 };
function openViewer(list, i) {
  V.list = list; show(i);
  $('viewer').classList.remove('hidden');
  document.body.style.overflow = 'hidden';
}
function closeViewer() {
  $('viewer').classList.add('hidden');
  $('stage').innerHTML = '';
  V.i = -1;
  document.body.style.overflow = '';
}
function current() { return V.list[V.i]; }
function show(i) {
  if (!V.list.length) return closeViewer();
  V.i = (i + V.list.length) % V.list.length;
  const a = current();
  const s = $('stage');
  s.innerHTML = '';
  resetZoom();
  if (a.type === 'video') {
    s.append(h('video', { src: original(a), controls: true, autoplay: true, playsinline: true, poster: preview(a) }));
  } else {
    const img = h('img', { src: preview(a), alt: '', draggable: 'false' });
    s.append(img);
  }
  $('vFav').textContent = a.favorite ? '♥' : '♡';
  $('vFav').style.color = a.favorite ? '#ff6b81' : '';
  $('vEdit').classList.toggle('hidden', a.type !== 'image' || !!a.trashedAt);
  $('vDelete').title = a.trashedAt ? 'Delete forever' : 'Delete (Del)';
  if (!$('info').classList.contains('hidden')) renderInfo();
  // Warm up the neighbours so swiping feels instant.
  for (const d of [1, -1]) { const n = V.list[(V.i + d + V.list.length) % V.list.length]; if (n && n.type === 'image') new Image().src = preview(n); }
}
async function renderInfo() {
  const a = current();
  const box = $('info');
  box.innerHTML = '';
  const d = new Date(a.takenAt);
  const desc = h('textarea', { placeholder: 'Add a description', rows: 2 }, a.description || '');
  desc.onchange = async () => { const r = await api('/api/assets/' + a.id, { method: 'PATCH', body: { description: desc.value } }); Object.assign(a, r); state.assets.set(a.id, a); };
  const row = (i, main, sub) => h('div', { class: 'row' }, h('div', { class: 'i' }, i), h('div', {}, h('div', {}, main), sub ? h('div', { class: 's' }, sub) : null));
  box.append(h('h3', {}, 'Info'), desc,
    row('📅', d.toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric' }), d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })),
    row(a.type === 'video' ? '🎞' : '🖼', a.name, [a.width && `${a.width} × ${a.height}`, a.width && a.type === 'image' && `${(a.width * a.height / 1e6).toFixed(1)} MP`, fmtBytes(a.size), a.duration && fmtDur(a.duration)].filter(Boolean).join(' · ')),
    a.model ? row('📷', [a.make, a.model].filter(Boolean).join(' ')) : null,
    place(a) ? row('⌖', place(a), `${a.lat.toFixed(5)}, ${a.lon.toFixed(5)}`) : null);
  if (a.lat != null && window.L) {
    const mm = h('div', { class: 'minimap' });
    box.append(mm);
    setTimeout(() => {
      const map = L.map(mm, { zoomControl: false, attributionControl: false }).setView([a.lat, a.lon], 13);
      L.tileLayer('/tiles/{z}/{x}/{y}.png').addTo(map);
      L.marker([a.lat, a.lon]).addTo(map);
    }, 0);
  }
  try {
    const full = await api('/api/assets/' + a.id);
    if (full.albums.length) box.append(row('❐', 'In albums', full.albums.map(x => x.name).join(', ')));
  } catch { /* ignore */ }
}
function resetZoom() { V.zoom = 1; V.x = 0; V.y = 0; applyZoom(); }
function applyZoom() {
  const el = $('stage').firstChild;
  if (el && el.tagName === 'IMG') el.style.transform = V.zoom === 1 ? '' : `translate(${V.x}px, ${V.y}px) scale(${V.zoom})`;
}
function zoomAt(factor, cx, cy) {
  const el = $('stage').firstChild;
  if (!el || el.tagName !== 'IMG') return;
  const r = el.getBoundingClientRect();
  const nz = Math.max(1, Math.min(8, V.zoom * factor));
  // Keep the point under the cursor fixed while zooming (transform-origin is the top-left corner).
  const left = r.left - V.x, top = r.top - V.y;
  const u = (cx - left - V.x) / V.zoom, v = (cy - top - V.y) / V.zoom;
  V.x = cx - left - u * nz;
  V.y = cy - top - v * nz;
  V.zoom = nz;
  if (nz === 1) { V.x = 0; V.y = 0; }
  applyZoom();
}
$('stage').addEventListener('wheel', e => { e.preventDefault(); zoomAt(e.deltaY < 0 ? 1.2 : 1 / 1.2, e.clientX, e.clientY); }, { passive: false });
$('stage').addEventListener('dblclick', e => (V.zoom > 1 ? resetZoom() : zoomAt(2.5, e.clientX, e.clientY)));
// Pointer gestures: drag to pan when zoomed, swipe to change photo, pinch to zoom, swipe down to close.
const pointers = new Map();
let gesture = null;
$('stage').addEventListener('pointerdown', e => {
  if (e.target.tagName === 'VIDEO') return;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  $('stage').setPointerCapture(e.pointerId);
  if (pointers.size === 1) gesture = { sx: e.clientX, sy: e.clientY, x: V.x, y: V.y, t: Date.now() };
  if (pointers.size === 2) { const [p, q] = [...pointers.values()]; gesture = { pinch: Math.hypot(p.x - q.x, p.y - q.y), zoom: V.zoom }; }
});
$('stage').addEventListener('pointermove', e => {
  if (!pointers.has(e.pointerId) || !gesture) return;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pointers.size === 2 && gesture.pinch) {
    const [p, q] = [...pointers.values()];
    const f = (Math.hypot(p.x - q.x, p.y - q.y) / gesture.pinch) * gesture.zoom / V.zoom;
    zoomAt(f, (p.x + q.x) / 2, (p.y + q.y) / 2);
  } else if (V.zoom > 1) {
    V.x = gesture.x + e.clientX - gesture.sx; V.y = gesture.y + e.clientY - gesture.sy; applyZoom();
  }
});
const endPointer = e => {
  if (!pointers.has(e.pointerId)) return;
  pointers.delete(e.pointerId);
  if (gesture && !gesture.pinch && V.zoom === 1 && pointers.size === 0) {
    const dx = e.clientX - gesture.sx, dy = e.clientY - gesture.sy;
    if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy)) show(V.i + (dx < 0 ? 1 : -1));
    else if (dy > 120 && Math.abs(dy) > Math.abs(dx)) closeViewer();
  }
  if (!pointers.size) gesture = null;
};
$('stage').addEventListener('pointerup', endPointer);
$('stage').addEventListener('pointercancel', endPointer);

$('vClose').onclick = closeViewer;
$('vPrev').onclick = () => show(V.i - 1);
$('vNext').onclick = () => show(V.i + 1);
$('vInfo').onclick = () => { $('info').classList.toggle('hidden'); $('viewer').classList.toggle('info-open', !$('info').classList.contains('hidden')); if (!$('info').classList.contains('hidden')) renderInfo(); };
$('vDownload').onclick = () => download([current().id]);
$('vFav').onclick = async () => {
  const a = current();
  const r = await api('/api/assets/' + a.id, { method: 'PATCH', body: { favorite: !a.favorite } });
  Object.assign(a, r);
  if (state.assets.has(a.id)) state.assets.set(a.id, a);
  show(V.i);
};
$('vDelete').onclick = async () => {
  const a = current();
  if (a.trashedAt) return confirmDo('Delete forever?', 'This can’t be undone.', 'Delete', async () => { await viewerRemove('delete'); });
  await viewerRemove('trash');
};
async function viewerRemove(action) {
  const a = current();
  await api('/api/assets/bulk', { method: 'POST', body: { action, ids: [a.id] } });
  toast(action === 'trash' ? 'Moved to trash' : 'Deleted forever');
  if (action === 'trash') undoable(async () => { await api('/api/assets/bulk', { method: 'POST', body: { action: 'restore', ids: [a.id] } }); await sync(); route(); });
  V.list = V.list.filter(x => x.id !== a.id);
  await sync();
  route();
  refreshStats();
  if (V.list.length) show(Math.min(V.i, V.list.length - 1)); else closeViewer();
}
$('vMore').onclick = () => {
  const a = current();
  menu($('vMore'), [
    ['Add to album', () => pickAlbum([a.id])],
    state.view === 'album' && ['Use as album cover', async () => { await api('/api/albums/' + state.albumId, { method: 'PATCH', body: { cover: a.id } }); toast('Cover updated'); }],
    navigator.share && ['Share…', () => shareFile(a)],
    a.type === 'image' && ['Open full resolution', () => window.open(original(a), '_blank')],
    ['Edit date & time', () => adjustDate([a.id])],
    a.trashedAt ? ['Restore', async () => { await api('/api/assets/bulk', { method: 'POST', body: { action: 'restore', ids: [a.id] } }); await sync(); route(); closeViewer(); }]
      : [a.archived ? 'Unarchive' : 'Archive', async () => { await api('/api/assets/bulk', { method: 'POST', body: { action: a.archived ? 'unarchive' : 'archive', ids: [a.id] } }); await sync(); route(); toast(a.archived ? 'Unarchived' : 'Archived'); a.archived = !a.archived; }],
    state.view === 'locked' ? ['Move out of locked folder', () => { bulk('unlock', [a.id]); closeViewer(); }]
      : ['Move to locked folder', () => withUnlock(() => { bulk('lock', [a.id]); closeViewer(); })],
  ]);
};
async function shareFile(a) {
  try {
    const blob = await (await fetch(original(a))).blob();
    await navigator.share({ files: [new File([blob], a.name, { type: a.mime })] });
  } catch { /* cancelled */ }
}
addEventListener('keydown', e => {
  if (!$('editor').classList.contains('hidden') || !$('modal').classList.contains('hidden') || document.querySelector('.story')) return;
  if (e.target.matches('input, textarea')) return;
  if (V.i >= 0) {
    if (e.key === 'Escape') closeViewer();
    else if (e.key === 'ArrowLeft') show(V.i - 1);
    else if (e.key === 'ArrowRight') show(V.i + 1);
    else if (e.key === 'f') $('vFav').click();
    else if (e.key === 'i') $('vInfo').click();
    else if (e.key === 'e' && current().type === 'image') $('vEdit').click();
    else if (e.key === 'Delete') $('vDelete').click();
  } else if (e.key === 'Escape') clearSelection();
  else if ((e.ctrlKey || e.metaKey) && e.key === 'a' && state.list.length) { e.preventDefault(); state.list.forEach(a => state.sel.add(a.id)); syncSelection(); }
});

// ---------------------------------------------------------------- editor
const FILTERS = [
  ['Original', ''], ['Vivid', 'saturate(1.35) contrast(1.1)'], ['Warm', 'sepia(.22) saturate(1.25) brightness(1.02)'],
  ['Cool', 'hue-rotate(-10deg) saturate(1.1) brightness(1.04)'], ['Fade', 'contrast(.85) brightness(1.08) saturate(.8)'],
  ['Vintage', 'sepia(.45) contrast(.95) brightness(1.05) saturate(.9)'], ['Dramatic', 'contrast(1.35) saturate(1.1) brightness(.92)'],
  ['B&W', 'grayscale(1) contrast(1.1)'], ['Noir', 'grayscale(1) contrast(1.5) brightness(.88)'], ['Pop', 'saturate(1.6) contrast(1.15) brightness(1.03)'],
];
const ADJUST = [['brightness', 'Brightness'], ['contrast', 'Contrast'], ['saturation', 'Saturation'], ['warmth', 'Warmth'], ['highlights', 'Exposure'], ['vignette', 'Vignette']];
const E = { img: null, asset: null, rot: 0, flip: false, crop: null, cropping: false, adj: {}, filter: '' };

$('vEdit').onclick = async () => {
  const a = current();
  E.asset = a;
  const canDecode = /^image\/(jpeg|png|webp|gif|avif)$/.test(a.mime);
  E.img = new Image();
  E.img.crossOrigin = 'use-credentials';
  E.img.src = canDecode ? original(a) : preview(a);
  await E.img.decode().catch(() => { E.img.src = preview(a); return E.img.decode(); });
  resetEdit();
  buildEditorTools();
  $('editor').classList.remove('hidden');
  drawEdit();
};
function resetEdit() { Object.assign(E, { rot: 0, flip: false, crop: null, cropping: false, adj: Object.fromEntries(ADJUST.map(([k]) => [k, 0])), filter: '' }); }
function cssFilter() {
  const a = E.adj;
  return [`brightness(${1 + a.brightness / 200 + a.highlights / 300})`, `contrast(${1 + a.contrast / 150})`, `saturate(${1 + a.saturation / 100})`, E.filter].join(' ');
}
// Draws the edited image. scale < 1 renders a smaller copy for the on-screen preview.
function renderEdit(canvas, maxSide, showFullForCrop) {
  const img = E.img;
  const rotW = E.rot % 180 ? img.naturalHeight : img.naturalWidth;
  const rotH = E.rot % 180 ? img.naturalWidth : img.naturalHeight;
  const crop = !showFullForCrop && E.crop ? E.crop : { x: 0, y: 0, w: 1, h: 1 };
  const outW = rotW * crop.w, outH = rotH * crop.h;
  const scale = Math.min(1, maxSide / Math.max(outW, outH));
  canvas.width = Math.round(outW * scale); canvas.height = Math.round(outH * scale);
  const ctx = canvas.getContext('2d');
  ctx.save();
  ctx.filter = cssFilter();
  ctx.scale(scale, scale);
  ctx.translate(-crop.x * rotW, -crop.y * rotH);
  ctx.translate(rotW / 2, rotH / 2);
  ctx.rotate(E.rot * Math.PI / 180);
  if (E.flip) ctx.scale(-1, 1);
  ctx.drawImage(img, -img.naturalWidth / 2, -img.naturalHeight / 2);
  ctx.restore();
  const w = canvas.width, hgt = canvas.height;
  if (E.adj.warmth) {
    ctx.save(); ctx.globalCompositeOperation = 'soft-light';
    ctx.fillStyle = E.adj.warmth > 0 ? `rgba(255,150,40,${E.adj.warmth / 160})` : `rgba(40,120,255,${-E.adj.warmth / 160})`;
    ctx.fillRect(0, 0, w, hgt); ctx.restore();
  }
  if (E.adj.vignette) {
    const g = ctx.createRadialGradient(w / 2, hgt / 2, Math.min(w, hgt) * 0.3, w / 2, hgt / 2, Math.hypot(w, hgt) / 2);
    g.addColorStop(0, 'rgba(0,0,0,0)');
    g.addColorStop(1, E.adj.vignette > 0 ? `rgba(0,0,0,${E.adj.vignette / 110})` : `rgba(255,255,255,${-E.adj.vignette / 110})`);
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, hgt);
  }
}
function drawEdit() {
  renderEdit($('eCanvas'), 1600, E.cropping);
  $('cropBox').classList.toggle('hidden', !E.cropping);
  if (E.cropping) placeCropBox();
}
function buildEditorTools() {
  const adj = $('tab-adjust');
  adj.innerHTML = '';
  for (const [k, label] of ADJUST) {
    const r = h('input', { type: 'range', min: -100, max: 100, value: E.adj[k] });
    r.oninput = () => { E.adj[k] = +r.value; drawEdit(); };
    r.ondblclick = () => { r.value = 0; E.adj[k] = 0; drawEdit(); };
    adj.append(h('label', {}, label, r));
  }
  const fl = $('tab-filters');
  fl.innerHTML = '';
  for (const [name, f] of FILTERS) {
    const c = h('canvas', { width: 76, height: 76 });
    const ctx = c.getContext('2d');
    const s = Math.min(E.img.naturalWidth, E.img.naturalHeight);
    ctx.filter = f || 'none';
    ctx.drawImage(E.img, (E.img.naturalWidth - s) / 2, (E.img.naturalHeight - s) / 2, s, s, 0, 0, 76, 76);
    const el = h('div', { class: 'filter' + (E.filter === f ? ' on' : '') }, c, h('div', {}, name));
    el.onclick = () => { E.filter = f; fl.querySelectorAll('.filter').forEach(x => x.classList.remove('on')); el.classList.add('on'); drawEdit(); };
    fl.append(el);
  }
}
document.querySelectorAll('#editor .tabs button').forEach(b => (b.onclick = () => {
  document.querySelectorAll('#editor .tabs button').forEach(x => x.classList.toggle('active', x === b));
  document.querySelectorAll('#editor .tab').forEach(t => t.classList.toggle('hidden', t.id !== 'tab-' + b.dataset.tab));
}));
$('eRotL').onclick = () => { E.rot = (E.rot + 270) % 360; E.crop = null; drawEdit(); };
$('eRotR').onclick = () => { E.rot = (E.rot + 90) % 360; E.crop = null; drawEdit(); };
$('eFlip').onclick = () => { E.flip = !E.flip; E.crop = null; drawEdit(); };
$('eReset').onclick = () => { resetEdit(); buildEditorTools(); drawEdit(); };
$('eCancel').onclick = () => $('editor').classList.add('hidden');
$('eCropToggle').onclick = () => {
  E.cropping = !E.cropping;
  if (E.cropping && !E.crop) E.crop = { x: 0.05, y: 0.05, w: 0.9, h: 0.9 };
  $('eCropToggle').textContent = E.cropping ? 'Done cropping' : 'Crop';
  if (E.cropping) applyAspect();
  drawEdit();
};
$('eAspect').onchange = () => { if (!E.cropping) $('eCropToggle').click(); else { applyAspect(); drawEdit(); } };
function applyAspect() {
  const v = $('eAspect').value;
  if (v === 'free' || !E.crop) return;
  const cv = $('eCanvas');
  const imgAspect = cv.width / cv.height, target = +v;
  // Crop rect is stored as fractions of the image, so convert the pixel aspect ratio.
  let w = 0.9, hh = (w * imgAspect) / target;
  if (hh > 0.9) { hh = 0.9; w = (hh * target) / imgAspect; }
  E.crop = { x: (1 - w) / 2, y: (1 - hh) / 2, w, h: hh };
}
function placeCropBox() {
  const box = $('cropBox'), cv = $('eCanvas');
  const W = cv.clientWidth, H = cv.clientHeight;
  Object.assign(box.style, { left: E.crop.x * W + 'px', top: E.crop.y * H + 'px', width: E.crop.w * W + 'px', height: E.crop.h * H + 'px' });
  if (!box.children.length) ['nw', 'ne', 'sw', 'se'].forEach(c => box.append(h('i', { class: c, 'data-h': c })));
}
$('cropBox').addEventListener('pointerdown', e => {
  e.preventDefault();
  const cv = $('eCanvas');
  const W = cv.clientWidth, H = cv.clientHeight;
  const start = { ...E.crop }, sx = e.clientX, sy = e.clientY, handle = e.target.dataset.h;
  const aspect = $('eAspect').value === 'free' ? null : (+$('eAspect').value * H) / W;
  const move = ev => {
    const dx = (ev.clientX - sx) / W, dy = (ev.clientY - sy) / H;
    let { x, y, w, h: hh } = start;
    if (!handle) { x = Math.max(0, Math.min(1 - w, x + dx)); y = Math.max(0, Math.min(1 - hh, y + dy)); }
    else {
      if (handle.includes('w')) { x = Math.min(start.x + start.w - 0.05, Math.max(0, x + dx)); w = start.x + start.w - x; }
      if (handle.includes('e')) w = Math.max(0.05, Math.min(1 - x, w + dx));
      if (handle.includes('n')) { y = Math.min(start.y + start.h - 0.05, Math.max(0, y + dy)); hh = start.y + start.h - y; }
      if (handle.includes('s')) hh = Math.max(0.05, Math.min(1 - y, hh + dy));
      if (aspect) { hh = w / aspect; if (y + hh > 1) { hh = 1 - y; w = hh * aspect; } if (handle.includes('n')) y = start.y + start.h - hh; }
    }
    E.crop = { x, y, w, h: hh };
    placeCropBox();
  };
  const up = () => { removeEventListener('pointermove', move); removeEventListener('pointerup', up); };
  addEventListener('pointermove', move); addEventListener('pointerup', up);
});
$('eSave').onclick = async () => {
  $('eSave').disabled = true;
  try {
    E.cropping = false;
    const out = document.createElement('canvas');
    renderEdit(out, 12000, false);
    const blob = await new Promise(r => out.toBlob(r, 'image/jpeg', 0.93));
    const name = E.asset.name.replace(/\.[^.]+$/, '') + '_edited.jpg';
    await uploadFiles([new File([blob], name, { type: 'image/jpeg' })], state.view === 'album' ? state.albumId : null,
      { editedFrom: E.asset.id, lastModified: E.asset.takenAt + 1000 });
    $('editor').classList.add('hidden');
    closeViewer();
    toast('Saved as a copy next to the original');
  } catch (e) { toast('Could not save: ' + e.message); }
  $('eSave').disabled = false;
};

// ---------------------------------------------------------------- boot
async function start() {
  $('auth').classList.add('hidden');
  $('app').classList.remove('hidden');
  await sync(true);
  route();
  refreshStats();
}
// Pick up photos uploaded from the phone while the page is open.
setInterval(async () => { if (appVisible() && !document.hidden && V.i < 0 && !state.sel.size) { const n = state.assets.size; await sync().catch(() => {}); if (state.assets.size !== n && ['photos', 'videos', 'favorites'].includes(state.view)) route(); } }, 30000);
api('/api/status').then(s => (s.authed ? start() : showAuth(!s.setup))).catch(() => showAuth(false));
