// Bulk imports: files dropped into data/import, and .zip exports from Google Photos (Takeout) or
// Amazon Photos. Takeout keeps the real date and location in a .json file next to each photo, so
// those are read first and used when the photo itself has no EXIF.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const yauzl = require('yauzl');
const cfg = require('./config');
const media = require('./media');

const status = { running: false, file: null, total: 0, done: 0, added: 0, duplicates: 0, failed: 0, finishedAt: null };
const queue = [];

// Takeout names sidecars "IMG_1.jpg.json", "IMG_1.jpg.supplemental-metadata.json" (often cut short)
// or "IMG_1.json"; each holds the photo's name in "title", so they are matched on folder + title.
function readSidecar(text) {
  try {
    const j = JSON.parse(text);
    if (!j.title) return null;
    const ts = Number(j.photoTakenTime?.timestamp || j.creationTime?.timestamp) * 1000 || null;
    const g = [j.geoDataExif, j.geoData].find(g => g && (g.latitude || g.longitude));
    return { title: j.title, takenAt: ts, lat: g ? g.latitude : null, lon: g ? g.longitude : null };
  } catch { return null; }
}
const sideKey = (dir, name) => `${dir}/${name}`.toLowerCase();
function sidecarFor(map, dir, name) {
  // Edited copies ("IMG_1-edited.jpg") and duplicates ("IMG_1(1).jpg") share the original's sidecar.
  const plain = name.replace(/-(edited|bearbeitet|modifié)(?=\.[^.]+$)/i, '').replace(/\((\d+)\)(?=\.[^.]+$)/, '');
  return map.get(sideKey(dir, name)) || map.get(sideKey(dir, plain)) || null;
}

async function ingestOne(file, name, side, fallbackTime) {
  try {
    const { duplicate } = await media.ingest(file, {
      originalName: name, lastModified: side?.takenAt || fallbackTime, lat: side?.lat, lon: side?.lon,
    });
    duplicate ? status.duplicates++ : status.added++;
  } catch (e) {
    fs.rmSync(file, { force: true });
    status.failed++;
    console.warn(`import failed for ${name}: ${e.message}`);
  }
  status.done++;
}

const openZip = file => new Promise((ok, fail) => yauzl.open(file, { lazyEntries: true, autoClose: false }, (e, z) => (e ? fail(e) : ok(z))));
// Calls fn(entry) for every file in the zip, one at a time.
function eachEntry(zip, fn) {
  return new Promise((ok, fail) => {
    zip.on('entry', async entry => {
      try { if (!entry.fileName.endsWith('/')) await fn(entry); zip.readEntry(); } catch (e) { fail(e); }
    });
    zip.once('end', ok);
    zip.once('error', fail);
    zip.readEntry();
  });
}
const entryStream = (zip, entry) => new Promise((ok, fail) => zip.openReadStream(entry, (e, s) => (e ? fail(e) : ok(s))));

async function importZip(file) {
  // Pass 1: read the sidecars and count the photos.
  const sidecars = new Map();
  let zip = await openZip(file);
  status.total = 0;
  await eachEntry(zip, async entry => {
    const name = path.posix.basename(entry.fileName);
    if (media.isMedia(name)) status.total++;
    else if (/\.json$/i.test(name) && entry.uncompressedSize < 1e6) {
      const chunks = [];
      for await (const c of await entryStream(zip, entry)) chunks.push(c);
      const side = readSidecar(Buffer.concat(chunks).toString('utf8'));
      if (side) sidecars.set(sideKey(path.posix.dirname(entry.fileName), side.title), side);
    }
  });
  zip.close();
  // Pass 2: add the photos and videos.
  zip = await openZip(file);
  await eachEntry(zip, async entry => {
    const name = path.posix.basename(entry.fileName);
    if (!media.isMedia(name)) return;
    const tmp = path.join(cfg.UPLOAD_TMP, crypto.randomBytes(8).toString('hex'));
    await new Promise(async (ok, fail) => {
      const s = await entryStream(zip, entry);
      s.pipe(fs.createWriteStream(tmp)).on('finish', ok).on('error', fail);
      s.on('error', fail);
    });
    await ingestOne(tmp, name, sidecarFor(sidecars, path.posix.dirname(entry.fileName), name), entry.getLastModDate().getTime());
  });
  zip.close();
}

// Loose files in data/import (including an unzipped Takeout folder with its .json files).
async function importLoose(files) {
  const sidecars = new Map();
  for (const f of files.filter(f => /\.json$/i.test(f))) {
    const side = readSidecar(fs.readFileSync(f, 'utf8'));
    if (side) sidecars.set(sideKey(path.dirname(f), side.title), side);
  }
  const photos = files.filter(f => media.isMedia(f));
  status.total = photos.length;
  for (const f of photos) {
    const tmp = path.join(cfg.UPLOAD_TMP, crypto.randomBytes(8).toString('hex'));
    fs.copyFileSync(f, tmp);
    await ingestOne(tmp, path.basename(f), sidecarFor(sidecars, path.dirname(f), path.basename(f)), fs.statSync(f).mtimeMs);
    fs.rmSync(f, { force: true });
  }
  for (const f of files.filter(f => /\.json$/i.test(f))) fs.rmSync(f, { force: true });
}

async function run() {
  if (status.running) return;
  status.running = true;
  Object.assign(status, { added: 0, duplicates: 0, failed: 0, done: 0, total: 0, finishedAt: null });
  try {
    while (queue.length) {
      const job = queue.shift();
      status.file = job.label;
      try { await job.fn(); } catch (e) { console.warn(`import of ${job.label} failed: ${e.message}`); status.failed++; }
    }
  } finally {
    status.running = false;
    status.file = null;
    status.finishedAt = Date.now();
  }
}

/** Queues an uploaded .zip (deleted afterwards). */
function addZip(file, label) {
  queue.push({ label, fn: () => importZip(file).finally(() => fs.rmSync(file, { force: true })) });
  run();
}

/** Looks in data/import for anything new (files still being copied in are left for next time). */
function scanFolder() {
  if (status.running) return;
  const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });
  const settled = f => { const s = fs.statSync(f); return Date.now() - Math.max(s.mtimeMs, s.ctimeMs) > 60e3; };
  const files = walk(cfg.IMPORT_DIR).filter(settled);
  for (const z of files.filter(f => /\.zip$/i.test(f))) addZip(z, path.basename(z));
  const loose = files.filter(f => media.isMedia(f) || /\.json$/i.test(f));
  if (loose.some(f => media.isMedia(f))) { queue.push({ label: 'import folder', fn: () => importLoose(loose) }); run(); }
}

module.exports = { status, addZip, scanFolder };
