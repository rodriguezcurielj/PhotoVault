const express = require('express');
const compression = require('compression');
const multer = require('multer');
const archiver = require('archiver');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const cfg = require('./config');
const { db, getSetting, setSetting } = require('./db');
const media = require('./media');
const importer = require('./importer');
const mail = require('./mail');

// ---------- password + sessions ----------
function hashPassword(pw, salt = crypto.randomBytes(16).toString('hex')) {
  return `${salt}:${crypto.scryptSync(pw, salt, 64).toString('hex')}`;
}
function checkPassword(pw) {
  const stored = getSetting('password');
  if (!stored) return false;
  const [salt, hash] = stored.split(':');
  return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(hashPassword(pw, salt).split(':')[1], 'hex'));
}
// The login is a username plus that password (username is not case sensitive).
const checkLogin = (user, pw) => {
  const want = getSetting('username');
  return checkPassword(pw) && (!want || String(user || '').trim().toLowerCase() === want.toLowerCase());
};
// Carry over the password from the first version of PhotoVault (data/config.json).
const legacy = path.join(cfg.DATA_DIR, 'config.json');
if (!getSetting('password') && fs.existsSync(legacy)) {
  const { salt, hash } = JSON.parse(fs.readFileSync(legacy, 'utf8'));
  setSetting('password', `${salt}:${hash}`);
}

function tokenOf(req) {
  const auth = req.headers.authorization || '';
  if (auth.startsWith('Bearer ')) return auth.slice(7);
  const m = (req.headers.cookie || '').match(/(?:^|; )pv_session=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : req.query.token || null;
}
function sessionOf(req) {
  const t = tokenOf(req);
  return t ? db.prepare('SELECT * FROM sessions WHERE token = ? AND expires_at > ?').get(t, Date.now()) : null;
}
function newSession(res, device) {
  const token = crypto.randomBytes(32).toString('hex');
  db.prepare('INSERT INTO sessions (token, device, created_at, expires_at) VALUES (?, ?, ?, ?)')
    .run(token, device || 'browser', Date.now(), Date.now() + cfg.SESSION_DAYS * 864e5);
  res.setHeader('Set-Cookie', `pv_session=${token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${cfg.SESSION_DAYS * 86400}`);
  return token;
}
const requireAuth = (req, res, next) => (sessionOf(req) ? next() : res.status(401).json({ error: 'Not logged in' }));

// The site is public (Tailscale Funnel), so wrong logins are limited per address (10 per 15 minutes)
// and overall (100 per 15 minutes).
const failures = [];
const recentFailures = ip => {
  while (failures.length && failures[0].at < Date.now() - 15 * 60e3) failures.shift();
  return failures.length >= 100 || failures.filter(f => f.ip === ip).length >= 10;
};

// ---------- helpers ----------
const now = () => Date.now();
function toJson(a) {
  return {
    id: a.id, type: a.type, name: a.original_name, mime: a.mime, size: a.size, checksum: a.checksum,
    takenAt: a.taken_at, uploadedAt: a.uploaded_at, updatedAt: a.updated_at,
    width: a.width, height: a.height, duration: a.duration,
    lat: a.lat, lon: a.lon, city: a.city, region: a.region, country: a.country,
    make: a.make, model: a.model, favorite: !!a.favorite, archived: !!a.archived, locked: !!a.locked,
    trashedAt: a.trashed_at, description: a.description, deviceId: a.device_id, deviceAsset: a.device_asset,
  };
}
const ids = v => [].concat(v || []).map(Number).filter(Number.isInteger);
const getAsset = id => db.prepare('SELECT * FROM assets WHERE id = ?').get(Number(id));
function placeName(a) { return [a.city, a.region, a.country].filter(Boolean).join(', '); }

function purgeAsset(a) {
  media.removeFiles(a);
  db.prepare('DELETE FROM assets WHERE id = ?').run(a.id);
  db.prepare('INSERT OR REPLACE INTO deleted (id, deleted_at) VALUES (?, ?)').run(a.id, now());
}
function emptyOldTrash() {
  const cutoff = now() - cfg.TRASH_DAYS * 864e5;
  for (const a of db.prepare('SELECT * FROM assets WHERE trashed_at IS NOT NULL AND trashed_at < ?').all(cutoff)) purgeAsset(a);
}

// ---------- app ----------
const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 'loopback');
app.use(compression());
app.use(express.json({ limit: '2mb' }));
app.use((req, res, next) => { res.setHeader('X-Content-Type-Options', 'nosniff'); res.setHeader('Referrer-Policy', 'same-origin'); next(); });
// The website on GitHub Pages talks to this server with a Bearer token (no cookies).
const ORIGINS = ['https://rodriguezcurielj.github.io'];
app.use((req, res, next) => {
  if (!ORIGINS.includes(req.headers.origin)) return next();
  res.setHeader('Access-Control-Allow-Origin', req.headers.origin);
  res.setHeader('Vary', 'Origin');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, X-Unlock');
  res.setHeader('Access-Control-Max-Age', '86400');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});
app.use(express.static(path.join(__dirname, '..', 'public'), { index: 'index.html' }));

app.get('/api/health', (req, res) => res.json({ ok: true, name: 'PhotoVault', version: 2 }));
app.get('/api/status', (req, res) => {
  const authed = !!sessionOf(req);
  res.json({ setup: !!getSetting('password'), authed, username: !!getSetting('username'), needsEmail: authed && !getSetting('email') });
});

app.post('/api/setup', (req, res) => {
  if (getSetting('password')) return res.status(400).json({ error: 'Already set up' });
  const pw = String(req.body.password || '');
  if (pw.length < 4) return res.status(400).json({ error: 'Password must be at least 4 characters' });
  if (req.body.username) setSetting('username', String(req.body.username).trim());
  setSetting('password', hashPassword(pw));
  res.json({ ok: true, token: newSession(res, req.body.device) });
});

app.post('/api/login', async (req, res) => {
  if (recentFailures(req.ip)) return res.status(429).json({ error: 'Too many wrong tries. Wait 15 minutes.' });
  if (checkLogin(req.body.username, String(req.body.password || ''))) return res.json({ ok: true, token: newSession(res, req.body.device) });
  failures.push({ at: now(), ip: req.ip });
  await new Promise(r => setTimeout(r, 800));
  res.status(401).json({ error: 'Wrong username or password' });
});

app.post('/api/logout', (req, res) => {
  const t = tokenOf(req);
  if (t) db.prepare('DELETE FROM sessions WHERE token = ?').run(t);
  res.setHeader('Set-Cookie', 'pv_session=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0');
  res.json({ ok: true });
});

app.post('/api/password', requireAuth, (req, res) => {
  if (!checkPassword(String(req.body.current || ''))) return res.status(401).json({ error: 'Current password is wrong' });
  const pw = String(req.body.password || '');
  if (pw.length < 4) return res.status(400).json({ error: 'Password must be at least 4 characters' });
  setSetting('password', hashPassword(pw));
  res.json({ ok: true });
});

// Checks the current password before the apps ask for a new one.
app.post('/api/password/verify', requireAuth, async (req, res) => {
  if (recentFailures(req.ip)) return res.status(429).json({ error: 'Too many wrong tries. Wait 15 minutes.' });
  if (checkPassword(String(req.body.password || ''))) return res.json({ ok: true });
  failures.push({ at: now(), ip: req.ip });
  await new Promise(r => setTimeout(r, 800));
  res.status(401).json({ error: 'That password is wrong' });
});

// ----- profile -----
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const profileJson = () => ({
  username: getSetting('username') || '', name: getSetting('name') || '', email: getSetting('email') || '',
  avatar: Number(getSetting('avatar')) || null, mailReady: mail.ready(),
});
app.get('/api/profile', requireAuth, (req, res) => res.json(profileJson()));
app.patch('/api/profile', requireAuth, async (req, res) => {
  const b = req.body;
  if (b.name != null) setSetting('name', String(b.name).trim().slice(0, 80));
  if (b.avatar !== undefined) setSetting('avatar', b.avatar ? String(Number(b.avatar)) : '');
  if (b.email != null) {
    const email = String(b.email).trim().toLowerCase();
    if (!EMAIL.test(email)) return res.status(400).json({ error: 'Enter a valid email address' });
    // Replacing the recovery email needs the password; adding the first one doesn't.
    if (getSetting('email') && !checkPassword(String(b.password || ''))) return res.status(401).json({ error: 'Enter your current password to change your email' });
    setSetting('email', email);
    if (mail.ready()) mail.send(email, 'Your Gallery recovery email', 'This address will get a code if you ever forget your Gallery password.')
      .catch(e => console.warn(`mail failed: ${e.message}`));
  }
  res.json(profileJson());
});

// ----- forgot password: a 6-digit code goes to the recovery email -----
let resetCode = null; // { hash, expires, tries }
const forgotLog = [];
const sha = s => crypto.createHash('sha256').update(s).digest('hex');
const maskEmail = e => e.replace(/^(.)(.*)(@.*)$/, (m, a, b, c) => a + '•'.repeat(Math.min(b.length, 6)) + c);
app.post('/api/forgot', async (req, res) => {
  while (forgotLog.length && forgotLog[0] < now() - 15 * 60e3) forgotLog.shift();
  if (forgotLog.length >= 5) return res.status(429).json({ error: 'Too many codes sent. Wait 15 minutes.' });
  const want = getSetting('username');
  if (want && String(req.body.username || '').trim().toLowerCase() !== want.toLowerCase()) return res.status(400).json({ error: 'That username doesn’t exist' });
  const email = getSetting('email');
  if (!email) return res.status(400).json({ error: 'This account has no recovery email.' });
  if (!mail.ready()) return res.status(503).json({ error: 'Email isn’t set up on the server yet.' });
  forgotLog.push(now());
  const code = String(crypto.randomInt(100000, 1000000));
  resetCode = { hash: sha(code), expires: now() + 15 * 60e3, tries: 0 };
  try {
    await mail.send(email, `Gallery reset code: ${code}`,
      `Your Gallery password reset code is ${code}. It works for 15 minutes. If you didn't ask for this, ignore this email.`,
      `<div style="font:16px -apple-system,Segoe UI,sans-serif"><p>Your Gallery password reset code:</p><p style="font-size:32px;font-weight:700;letter-spacing:6px">${code}</p><p style="color:#888">It works for 15 minutes. If you didn't ask for this, ignore this email.</p></div>`);
  } catch (e) {
    console.warn(`mail failed: ${e.message}`);
    return res.status(502).json({ error: 'Couldn’t send the email. Try again later.' });
  }
  res.json({ ok: true, sentTo: maskEmail(email) });
});
app.post('/api/reset', (req, res) => {
  const r = resetCode;
  if (!r || r.expires < now() || r.tries >= 5) return res.status(400).json({ error: 'That code expired. Ask for a new one.' });
  if (sha(String(req.body.code || '').trim()) !== r.hash) { r.tries++; return res.status(400).json({ error: 'Wrong code' }); }
  const pw = String(req.body.password || '');
  if (pw.length < 4) return res.status(400).json({ error: 'Password must be at least 4 characters' });
  resetCode = null;
  setSetting('password', hashPassword(pw));
  db.prepare('DELETE FROM sessions').run(); // sign out everywhere else
  res.json({ ok: true, token: newSession(res, req.body.device) });
});

// Locked folder: needs the password again on every visit, sent as X-Unlock.
const unlocked = req => checkPassword(String(req.headers['x-unlock'] || ''));

// ----- assets -----
// Full list (or changes since ?since=ms) so clients can keep a local copy of the timeline.
app.get('/api/assets', requireAuth, (req, res) => {
  const since = Number(req.query.since) || 0;
  const wantLocked = req.query.locked === '1';
  if (wantLocked && !unlocked(req)) return res.status(403).json({ error: 'Enter your password to open the locked folder' });
  const rows = db.prepare(`SELECT * FROM assets WHERE updated_at > ? AND locked = ? ORDER BY taken_at DESC`).all(since, wantLocked ? 1 : 0);
  const deleted = since ? db.prepare('SELECT id FROM deleted WHERE deleted_at > ?').all(since).map(r => r.id) : [];
  // Items moved into the locked folder count as deleted for normal views.
  if (since && !wantLocked) deleted.push(...db.prepare('SELECT id FROM assets WHERE locked = 1 AND updated_at > ?').all(since).map(r => r.id));
  res.json({ now: now(), assets: rows.map(toJson), deleted });
});

app.get('/api/assets/:id', requireAuth, (req, res) => {
  const a = getAsset(req.params.id);
  if (!a || (a.locked && !unlocked(req))) return res.status(404).json({ error: 'Not found' });
  const albums = db.prepare('SELECT al.id, al.name FROM album_assets aa JOIN albums al ON al.id = aa.album_id WHERE aa.asset_id = ?').all(a.id);
  res.json({ ...toJson(a), place: placeName(a), albums });
});

app.patch('/api/assets/:id', requireAuth, (req, res) => {
  const a = getAsset(req.params.id);
  if (!a) return res.status(404).json({ error: 'Not found' });
  const b = req.body;
  const set = {
    favorite: b.favorite != null ? (b.favorite ? 1 : 0) : a.favorite,
    archived: b.archived != null ? (b.archived ? 1 : 0) : a.archived,
    description: b.description !== undefined ? String(b.description || '').slice(0, 2000) || null : a.description,
    taken_at: Number(b.takenAt) > 0 ? Number(b.takenAt) : a.taken_at,
  };
  db.prepare('UPDATE assets SET favorite = ?, archived = ?, description = ?, taken_at = ?, updated_at = ? WHERE id = ?')
    .run(set.favorite, set.archived, set.description, set.taken_at, now(), a.id);
  res.json(toJson(getAsset(a.id)));
});

// Bulk actions on many items at once.
app.post('/api/assets/bulk', requireAuth, (req, res) => {
  const list = ids(req.body.ids);
  const action = String(req.body.action || '');
  if (['lock', 'unlock'].includes(action) && !unlocked(req)) return res.status(403).json({ error: 'Enter your password' });
  const t = now();
  const stmt = {
    favorite: 'UPDATE assets SET favorite = 1, updated_at = ? WHERE id = ?',
    unfavorite: 'UPDATE assets SET favorite = 0, updated_at = ? WHERE id = ?',
    archive: 'UPDATE assets SET archived = 1, updated_at = ? WHERE id = ?',
    unarchive: 'UPDATE assets SET archived = 0, updated_at = ? WHERE id = ?',
    trash: `UPDATE assets SET trashed_at = ${t}, updated_at = ? WHERE id = ?`,
    restore: 'UPDATE assets SET trashed_at = NULL, updated_at = ? WHERE id = ?',
    lock: 'UPDATE assets SET locked = 1, updated_at = ? WHERE id = ?',
    unlock: 'UPDATE assets SET locked = 0, updated_at = ? WHERE id = ?',
  }[action];
  if (stmt) {
    const q = db.prepare(stmt);
    for (const id of list) q.run(t, id);
  } else if (action === 'delete') {
    for (const id of list) { const a = getAsset(id); if (a && a.trashed_at) purgeAsset(a); }
  } else if (action === 'setDate') {
    const shift = Number(req.body.shiftMs) || 0;
    const q = db.prepare('UPDATE assets SET taken_at = taken_at + ?, updated_at = ? WHERE id = ?');
    for (const id of list) q.run(shift, t, id);
  } else {
    return res.status(400).json({ error: 'Unknown action' });
  }
  res.json({ ok: true, count: list.length });
});

app.post('/api/trash/empty', requireAuth, (req, res) => {
  const rows = db.prepare('SELECT * FROM assets WHERE trashed_at IS NOT NULL').all();
  rows.forEach(purgeAsset);
  res.json({ ok: true, count: rows.length });
});

// Phones send checksums first and only upload what the server doesn't have.
app.post('/api/assets/check', requireAuth, (req, res) => {
  const q = db.prepare('SELECT id FROM assets WHERE checksum = ?');
  const result = {};
  for (const c of [].concat(req.body.checksums || []).slice(0, 5000)) { const r = q.get(String(c)); if (r) result[c] = r.id; }
  res.json({ existing: result });
});

const upload = multer({ dest: cfg.UPLOAD_TMP, limits: { fileSize: 8 * 1024 ** 3 } });
app.post('/api/upload', requireAuth, upload.array('files', 200), async (req, res) => {
  const b = req.body;
  const pick = (field, i) => [].concat(b[field] ?? [])[i];
  const results = [];
  for (const [i, f] of (req.files || []).entries()) {
    if (!media.isMedia(f.originalname)) { fs.rmSync(f.path, { force: true }); results.push({ name: f.originalname, error: 'Not a photo or video' }); continue; }
    try {
      const { asset, duplicate } = await media.ingest(f.path, {
        originalName: f.originalname, lastModified: pick('lastModified', i), deviceId: b.deviceId,
        deviceAsset: pick('deviceAsset', i), editedFrom: Number(b.editedFrom) || null,
      });
      if (b.albumId) db.prepare('INSERT OR IGNORE INTO album_assets (album_id, asset_id, added_at) SELECT ?, id, ? FROM assets WHERE id = ?').run(Number(b.albumId), now(), asset.id);
      results.push({ name: f.originalname, id: asset.id, duplicate, asset: toJson(asset) });
    } catch (e) {
      fs.rmSync(f.path, { force: true });
      results.push({ name: f.originalname, error: e.message });
    }
  }
  res.json({ results });
});

// Google Photos (Takeout) / Amazon Photos exports: upload the .zip, it's unpacked in the background.
const zipUpload = multer({ dest: cfg.UPLOAD_TMP, limits: { fileSize: 100 * 1024 ** 3 } });
app.post('/api/import/zip', requireAuth, zipUpload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No file' });
  if (!/\.zip$/i.test(req.file.originalname)) { fs.rmSync(req.file.path, { force: true }); return res.status(400).json({ error: 'Pick a .zip file' }); }
  importer.addZip(req.file.path, req.file.originalname);
  res.json({ ok: true });
});
app.get('/api/import/status', requireAuth, (req, res) => res.json(importer.status));

// ----- files -----
function sendRendition(dir, req, res) {
  const a = getAsset(req.params.id);
  if (!a) return res.status(404).end();
  const file = path.join(dir, `${a.id}.webp`);
  if (fs.existsSync(file)) return res.sendFile(file, { maxAge: '30d', immutable: true });
  // No rendition (e.g. undecodable file): fall back to the original for images.
  if (a.type === 'image') return res.sendFile(path.join(cfg.LIBRARY_DIR, a.file), { maxAge: '30d' });
  res.status(404).end();
}
app.get('/api/assets/:id/thumb', requireAuth, (req, res) => sendRendition(cfg.THUMB_DIR, req, res));
app.get('/api/assets/:id/preview', requireAuth, (req, res) => sendRendition(cfg.PREVIEW_DIR, req, res));
app.get('/api/assets/:id/original', requireAuth, (req, res) => {
  const a = getAsset(req.params.id);
  if (!a) return res.status(404).end();
  if (req.query.download) res.attachment(a.original_name);
  res.sendFile(path.join(cfg.LIBRARY_DIR, a.file), { maxAge: '30d', headers: { 'Content-Type': a.mime } });
});

function zipAssets(res, list, name) {
  res.attachment(name);
  const zip = archiver('zip', { store: true });
  zip.pipe(res);
  const used = new Set();
  for (const a of list) {
    let n = a.original_name, i = 1;
    while (used.has(n)) n = `${path.parse(a.original_name).name}_${i++}${path.extname(a.original_name)}`;
    used.add(n);
    zip.file(path.join(cfg.LIBRARY_DIR, a.file), { name: n });
  }
  zip.finalize();
}
app.get('/api/download', requireAuth, (req, res) => {
  const list = ids(String(req.query.ids || '').split(',')).map(getAsset).filter(Boolean);
  zipAssets(res, list, `Gallery-${new Date().toISOString().slice(0, 10)}.zip`);
});

// ----- explore -----
const visible = `trashed_at IS NULL AND locked = 0`;

app.get('/api/memories', requireAuth, (req, res) => {
  const today = new Date();
  const groups = [];
  for (let y = 1; y <= 30; y++) {
    const center = new Date(today.getFullYear() - y, today.getMonth(), today.getDate()).getTime();
    const rows = db.prepare(`SELECT * FROM assets WHERE ${visible} AND archived = 0 AND taken_at BETWEEN ? AND ? ORDER BY taken_at`)
      .all(center - 3 * 864e5, center + 4 * 864e5);
    if (rows.length) groups.push({ title: y === 1 ? '1 year ago' : `${y} years ago`, year: today.getFullYear() - y, assets: rows.map(toJson) });
  }
  // "Recent highlights": favourites from the last 30 days.
  const recentFav = db.prepare(`SELECT * FROM assets WHERE ${visible} AND favorite = 1 AND taken_at > ? ORDER BY taken_at DESC LIMIT 50`).all(now() - 30 * 864e5);
  if (recentFav.length) groups.unshift({ title: 'Recent favorites', assets: recentFav.map(toJson) });
  res.json(groups);
});

app.get('/api/places', requireAuth, (req, res) => {
  const rows = db.prepare(`SELECT city, region, country, COUNT(*) AS count, MAX(id) AS cover FROM assets
    WHERE ${visible} AND city IS NOT NULL GROUP BY city, region, country ORDER BY count DESC`).all();
  res.json(rows.map(r => ({ ...r, name: r.city, full: placeName(r) })));
});

app.get('/api/map', requireAuth, (req, res) => {
  res.json(db.prepare(`SELECT id, lat, lon, taken_at AS takenAt FROM assets WHERE ${visible} AND lat IS NOT NULL ORDER BY taken_at DESC`).all());
});

const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
app.get('/api/search', requireAuth, (req, res) => {
  const words = String(req.query.q || '').toLowerCase().split(/\s+/).filter(Boolean);
  const rows = db.prepare(`SELECT * FROM assets WHERE ${visible} ORDER BY taken_at DESC`).all();
  const matches = rows.filter(a => {
    const d = new Date(a.taken_at);
    const text = [a.original_name, a.description, a.city, a.region, a.country, a.make, a.model,
      a.type, a.favorite ? 'favorite favorites' : '', a.archived ? 'archive archived' : '',
      /screenshot/i.test(a.original_name) ? 'screenshot screenshots' : '', a.type === 'video' ? 'videos' : 'photos photo',
      /selfie|front/i.test(a.original_name) ? 'selfie selfies' : ''].join(' ').toLowerCase();
    return words.every(w => {
      if (/^\d{4}$/.test(w)) return d.getFullYear() === +w;
      const m = MONTHS.findIndex(n => n.startsWith(w) && w.length >= 3);
      if (m >= 0 && !text.includes(w)) return d.getMonth() === m;
      return text.includes(w);
    });
  });
  res.json(matches.slice(0, 2000).map(toJson));
});

app.get('/api/stats', requireAuth, (req, res) => {
  const s = db.prepare(`SELECT
      SUM(type = 'image') AS photos, SUM(type = 'video') AS videos, SUM(size) AS bytes,
      SUM(trashed_at IS NOT NULL) AS trashed, SUM(favorite) AS favorites FROM assets`).get();
  let free = null;
  try { const st = fs.statfsSync(cfg.LIBRARY_DIR); free = st.bavail * st.bsize; } catch { /* not supported */ }
  res.json({ ...s, freeBytes: free });
});

// ----- albums -----
function albumJson(al) {
  const c = db.prepare(`SELECT COUNT(*) AS n, MAX(a.id) AS last FROM album_assets aa JOIN assets a ON a.id = aa.asset_id WHERE aa.album_id = ? AND a.trashed_at IS NULL AND a.locked = 0`).get(al.id);
  return { id: al.id, name: al.name, createdAt: al.created_at, updatedAt: al.updated_at, count: c.n, cover: al.cover_id || c.last, shareToken: al.share_token };
}
app.get('/api/albums', requireAuth, (req, res) => {
  res.json(db.prepare('SELECT * FROM albums ORDER BY updated_at DESC').all().map(albumJson));
});
app.post('/api/albums', requireAuth, (req, res) => {
  const name = String(req.body.name || '').trim().slice(0, 200) || 'Untitled album';
  const { lastInsertRowid } = db.prepare('INSERT INTO albums (name, created_at, updated_at) VALUES (?, ?, ?)').run(name, now(), now());
  const id = Number(lastInsertRowid);
  const q = db.prepare('INSERT OR IGNORE INTO album_assets (album_id, asset_id, added_at) SELECT ?, id, ? FROM assets WHERE id = ?');
  for (const a of ids(req.body.ids)) q.run(id, now(), a);
  res.json(albumJson(db.prepare('SELECT * FROM albums WHERE id = ?').get(id)));
});
const getAlbum = id => db.prepare('SELECT * FROM albums WHERE id = ?').get(Number(id));
app.get('/api/albums/:id', requireAuth, (req, res) => {
  const al = getAlbum(req.params.id);
  if (!al) return res.status(404).json({ error: 'Not found' });
  const assets = db.prepare(`SELECT a.* FROM album_assets aa JOIN assets a ON a.id = aa.asset_id
    WHERE aa.album_id = ? AND a.trashed_at IS NULL AND a.locked = 0 ORDER BY a.taken_at DESC`).all(al.id);
  res.json({ ...albumJson(al), assets: assets.map(toJson) });
});
app.patch('/api/albums/:id', requireAuth, (req, res) => {
  const al = getAlbum(req.params.id);
  if (!al) return res.status(404).json({ error: 'Not found' });
  const name = req.body.name != null ? String(req.body.name).trim().slice(0, 200) || al.name : al.name;
  const cover = req.body.cover !== undefined ? Number(req.body.cover) || null : al.cover_id;
  db.prepare('UPDATE albums SET name = ?, cover_id = ?, updated_at = ? WHERE id = ?').run(name, cover, now(), al.id);
  res.json(albumJson(getAlbum(al.id)));
});
app.delete('/api/albums/:id', requireAuth, (req, res) => {
  db.prepare('DELETE FROM albums WHERE id = ?').run(Number(req.params.id));
  res.json({ ok: true });
});
app.post('/api/albums/:id/assets', requireAuth, (req, res) => {
  const al = getAlbum(req.params.id);
  if (!al) return res.status(404).json({ error: 'Not found' });
  const q = db.prepare('INSERT OR IGNORE INTO album_assets (album_id, asset_id, added_at) SELECT ?, id, ? FROM assets WHERE id = ?');
  for (const a of ids(req.body.ids)) q.run(al.id, now(), a);
  db.prepare('UPDATE albums SET updated_at = ? WHERE id = ?').run(now(), al.id);
  res.json(albumJson(getAlbum(al.id)));
});
app.post('/api/albums/:id/remove', requireAuth, (req, res) => {
  const q = db.prepare('DELETE FROM album_assets WHERE album_id = ? AND asset_id = ?');
  for (const a of ids(req.body.ids)) q.run(Number(req.params.id), a);
  db.prepare('UPDATE albums SET updated_at = ? WHERE id = ?').run(now(), Number(req.params.id));
  res.json({ ok: true });
});
app.post('/api/albums/:id/share', requireAuth, (req, res) => {
  const al = getAlbum(req.params.id);
  if (!al) return res.status(404).json({ error: 'Not found' });
  const token = req.body.enabled === false ? null : al.share_token || crypto.randomBytes(18).toString('base64url');
  db.prepare('UPDATE albums SET share_token = ?, updated_at = ? WHERE id = ?').run(token, now(), al.id);
  res.json({ shareToken: token });
});

// ----- map tiles -----
// The server fetches OpenStreetMap tiles itself (OSM blocks requests without a proper app ID or
// referrer, which browsers and WebViews often don't send) and keeps them on disk, so maps load
// fast and keep working for places you've already looked at.
const TILE_DIR = path.join(cfg.DATA_DIR, 'tilecache');
app.get('/tiles/:z/:x/:y.png', async (req, res) => {
  const [z, x, y] = [req.params.z, req.params.x, req.params.y].map(Number);
  if (![z, x, y].every(Number.isInteger) || z < 0 || z > 19 || x < 0 || y < 0 || x >= 2 ** z || y >= 2 ** z) return res.status(400).end();
  const file = path.join(TILE_DIR, String(z), String(x), `${y}.png`);
  if (fs.existsSync(file)) return res.sendFile(file, { maxAge: '30d' });
  try {
    const r = await fetch(`https://tile.openstreetmap.org/${z}/${x}/${y}.png`, {
      headers: { 'User-Agent': 'PhotoVault/2 (self-hosted personal photo library)' },
      signal: AbortSignal.timeout(15000),
    });
    if (!r.ok) return res.status(r.status).end();
    const buf = Buffer.from(await r.arrayBuffer());
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, buf);
    res.type('png').set('Cache-Control', 'public, max-age=2592000').send(buf);
  } catch {
    res.status(502).end();
  }
});

// ----- links for single photos/videos or a selection -----
app.post('/api/links', requireAuth, (req, res) => {
  const want = ids(req.body.ids).filter(id => getAsset(id));
  if (!want.length) return res.status(400).json({ error: 'Nothing to share' });
  const token = crypto.randomBytes(18).toString('base64url');
  db.prepare('INSERT INTO links (token, created_at) VALUES (?, ?)').run(token, now());
  const q = db.prepare('INSERT OR IGNORE INTO link_assets (token, asset_id) VALUES (?, ?)');
  for (const id of want) q.run(token, id);
  res.json({ token });
});
app.get('/api/links', requireAuth, (req, res) => {
  res.json(db.prepare(`SELECT l.token, l.created_at, COUNT(la.asset_id) AS n, MAX(la.asset_id) AS cover
    FROM links l LEFT JOIN link_assets la ON la.token = l.token GROUP BY l.token ORDER BY l.created_at DESC`).all()
    .map(l => ({ token: l.token, createdAt: l.created_at, count: l.n, cover: l.cover })));
});
app.delete('/api/links/:token', requireAuth, (req, res) => {
  db.prepare('DELETE FROM links WHERE token = ?').run(String(req.params.token));
  res.json({ ok: true });
});

// ----- public share links (no login; only the items behind that link) -----
const VISIBLE = 'a.trashed_at IS NULL AND a.locked = 0';
function shared(req, res) {
  const t = String(req.params.token);
  const al = db.prepare('SELECT * FROM albums WHERE share_token = ?').get(t);
  if (al) return { name: al.name, album: true, from: 'album_assets x JOIN assets a ON a.id = x.asset_id WHERE x.album_id = ?', key: al.id };
  if (db.prepare('SELECT 1 FROM links WHERE token = ?').get(t)) return { name: null, album: false, from: 'link_assets x JOIN assets a ON a.id = x.asset_id WHERE x.token = ?', key: t };
  res.status(404).json({ error: 'This link no longer works' });
  return null;
}
function sharedAsset(req, res) {
  const s = shared(req, res);
  if (!s) return null;
  const a = db.prepare(`SELECT a.* FROM ${s.from} AND a.id = ? AND ${VISIBLE}`).get(s.key, Number(req.params.id));
  if (!a) { res.status(404).end(); return null; }
  return a;
}
app.get('/s/:token', (req, res) => res.sendFile(path.join(__dirname, '..', 'public', 'share.html')));
app.get('/api/share/:token', (req, res) => {
  const s = shared(req, res);
  if (!s) return;
  const assets = db.prepare(`SELECT a.* FROM ${s.from} AND ${VISIBLE} ORDER BY a.taken_at`).all(s.key);
  res.json({ name: s.name, album: s.album, assets: assets.map(a => ({ id: a.id, type: a.type, takenAt: a.taken_at, width: a.width, height: a.height, name: a.original_name })) });
});
for (const [kind, dir] of [['thumb', cfg.THUMB_DIR], ['preview', cfg.PREVIEW_DIR]]) {
  app.get(`/api/share/:token/${kind}/:id`, (req, res) => {
    const a = sharedAsset(req, res);
    if (!a) return;
    const f = path.join(dir, `${a.id}.webp`);
    res.sendFile(fs.existsSync(f) ? f : path.join(cfg.LIBRARY_DIR, a.file), { maxAge: '1d' });
  });
}
app.get('/api/share/:token/original/:id', (req, res) => {
  const a = sharedAsset(req, res);
  if (!a) return;
  if (req.query.download) res.attachment(a.original_name);
  res.sendFile(path.join(cfg.LIBRARY_DIR, a.file), { headers: { 'Content-Type': a.mime } });
});

// ---------- background jobs ----------
// Make any thumbnails that are missing (e.g. after restoring a backup of library/).
async function fillRenditions() {
  for (const a of db.prepare('SELECT * FROM assets').all()) {
    if (!fs.existsSync(path.join(cfg.THUMB_DIR, `${a.id}.webp`))) {
      try { await media.makeRenditions(a); } catch { /* undecodable, leave it */ }
    }
  }
}
// Clear out abandoned partial uploads.
for (const f of fs.readdirSync(cfg.UPLOAD_TMP)) fs.rmSync(path.join(cfg.UPLOAD_TMP, f), { force: true, recursive: true });

// Daily copy of the database next to the photos, keeping the last 7.
function backupDb() {
  const dir = path.join(cfg.DATA_DIR, 'db-backups');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `photovault-${new Date().toISOString().slice(0, 10)}.db`);
  if (fs.existsSync(file)) return;
  try { db.exec(`VACUUM INTO '${file.replace(/'/g, "''")}'`); } catch (e) { console.warn(`db backup failed: ${e.message}`); }
  fs.readdirSync(dir).sort().slice(0, -7).forEach(f => fs.rmSync(path.join(dir, f), { force: true }));
}

setInterval(() => { emptyOldTrash(); importer.scanFolder(); backupDb(); }, 10 * 60e3);
backupDb();
db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(now());
emptyOldTrash();
importer.scanFolder();
fillRenditions();

app.listen(cfg.PORT, () => console.log(`PhotoVault on http://localhost:${cfg.PORT} (data in ${cfg.DATA_DIR})`));
