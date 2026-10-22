// Adding files to the library: checksum, metadata (EXIF / ffprobe), place lookup, thumbnails and previews.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFile } = require('child_process');
const sharp = require('sharp');
const exifr = require('exifr');
const { db } = require('./db');
const geo = require('./geo');
const { LIBRARY_DIR, THUMB_DIR, PREVIEW_DIR, UPLOAD_TMP } = require('./config');

const IMAGE_EXT = /\.(jpe?g|png|gif|webp|heic|heif|avif|bmp|tiff?|dng)$/i;
const VIDEO_EXT = /\.(mp4|mov|m4v|3gp|webm|mkv|avi)$/i;
const isMedia = name => IMAGE_EXT.test(name) || VIDEO_EXT.test(name);

const MIME = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', heic: 'image/heic',
  heif: 'image/heif', avif: 'image/avif', bmp: 'image/bmp', tif: 'image/tiff', tiff: 'image/tiff', dng: 'image/x-adobe-dng',
  mp4: 'video/mp4', mov: 'video/quicktime', m4v: 'video/x-m4v', '3gp': 'video/3gpp', webm: 'video/webm', mkv: 'video/x-matroska', avi: 'video/x-msvideo',
};
const mimeOf = name => MIME[path.extname(name).slice(1).toLowerCase()] || 'application/octet-stream';

const run = (cmd, args, opts = {}) => new Promise((resolve, reject) =>
  execFile(cmd, args, { maxBuffer: 64 * 1024 * 1024, encoding: opts.encoding || 'utf8', timeout: 120000 }, (err, out) => (err ? reject(err) : resolve(out))));

function sha1File(file) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha1');
    fs.createReadStream(file).on('data', d => h.update(d)).on('end', () => resolve(h.digest('hex'))).on('error', reject);
  });
}

async function imageMeta(file) {
  const meta = {};
  try {
    const x = await exifr.parse(file, { tiff: true, exif: true, gps: true, translateValues: false });
    if (x) {
      const d = x.DateTimeOriginal || x.CreateDate || x.ModifyDate;
      if (d instanceof Date && !isNaN(d)) meta.taken_at = d.getTime();
      if (Number.isFinite(x.latitude) && Number.isFinite(x.longitude) && (x.latitude || x.longitude)) { meta.lat = x.latitude; meta.lon = x.longitude; }
      meta.make = x.Make || null;
      meta.model = x.Model || null;
    }
  } catch { /* no EXIF */ }
  try {
    const m = await sharp(file).metadata();
    const swap = m.orientation >= 5;
    meta.width = swap ? m.height : m.width;
    meta.height = swap ? m.width : m.height;
  } catch { /* format sharp can't read (e.g. HEIC); dimensions filled in from the decoded preview */ }
  return meta;
}

async function videoMeta(file) {
  const meta = {};
  try {
    const out = JSON.parse(await run('ffprobe', ['-v', 'quiet', '-print_format', 'json', '-show_format', '-show_streams', file]));
    const v = (out.streams || []).find(s => s.codec_type === 'video');
    const tags = { ...(out.format?.tags || {}), ...(v?.tags || {}) };
    if (v) {
      const rot = Math.abs(Number(v.tags?.rotate || v.side_data_list?.find(s => s.rotation != null)?.rotation || 0));
      meta.width = rot === 90 || rot === 270 ? v.height : v.width;
      meta.height = rot === 90 || rot === 270 ? v.width : v.height;
    }
    meta.duration = Number(out.format?.duration) || null;
    const created = Date.parse(tags.creation_time || tags['com.apple.quicktime.creationdate'] || '');
    if (created > 0) meta.taken_at = created;
    const loc = (tags.location || tags['com.apple.quicktime.location.ISO6709'] || '').match(/([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)/);
    if (loc && (+loc[1] || +loc[2])) { meta.lat = +loc[1]; meta.lon = +loc[2]; }
    meta.make = tags['com.android.manufacturer'] || tags['com.apple.quicktime.make'] || null;
    meta.model = tags['com.android.model'] || tags['com.apple.quicktime.model'] || null;
  } catch { /* ffprobe failed; keep defaults */ }
  return meta;
}

// Thumbnail work is heavy; this keeps it from slowing down everything else the server is doing.
sharp.concurrency(2);
// Windows has no nice; Gallery Server already runs the whole server below normal priority there.
const WIN = process.platform === 'win32';
const runLow = (cmd, args, opts) => (WIN ? run(cmd, args, opts) : run('nice', ['-n', '15', cmd, ...args], opts));
const ffmpegFrame = (file, at) => {
  const seek = at ? ['-ss', at] : [];
  return runLow('ffmpeg', ['-v', 'quiet', ...seek, '-i', file, '-frames:v', '1', '-f', 'image2pipe', '-vcodec', 'png', '-'], { encoding: 'buffer' });
};

// Returns something sharp can decode for the thumbnail: the file itself, a decoded HEIC, or a video frame.
// upright: the pixels are already turned the right way (don't apply the EXIF rotation again).
async function decodable(file, type) {
  if (type === 'video') {
    for (const at of ['1', '0']) {
      try {
        const png = await ffmpegFrame(file, at);
        if (png.length) return { input: png, upright: true };
      } catch { /* try the first frame */ }
    }
    return null;
  }
  if (/\.(heic|heif)$/i.test(file) && WIN) {
    // No heif-convert on Windows; Gallery Server's ffmpeg (7.1+) decodes HEIC grids and turns them upright.
    const png = await ffmpegFrame(file);
    return png.length ? { input: png, upright: true } : null;
  }
  if (/\.(heic|heif)$/i.test(file)) {
    // sharp's libvips can't decode iPhone HEVC photos. heif-convert (native libheif) runs in its own process,
    // so the server keeps answering while it works; the JS decoder used before froze it ~1.5 s per photo.
    const out = path.join(UPLOAD_TMP, `heic-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}.jpg`);
    try {
      await runLow('heif-convert', ['-q', '90', file, out]);
      // Its pixels are already upright even though the EXIF it copies still says "rotate".
      return { input: await fs.promises.readFile(out), upright: true };
    } finally { fs.rmSync(out, { force: true }); }
  }
  return { input: file, upright: false };
}

async function makeRenditions(asset) {
  const src = await decodable(path.join(LIBRARY_DIR, asset.file), asset.type);
  if (!src) return;
  // Decode once at preview size, then make both files from those pixels.
  const img = sharp(src.input, { failOn: 'none' });
  if (!src.upright) img.rotate();
  const { data, info } = await img.resize({ width: 2048, height: 2048, fit: 'inside', withoutEnlargement: true })
    .removeAlpha().raw().toBuffer({ resolveWithObject: true });
  const pixels = () => sharp(data, { raw: { width: info.width, height: info.height, channels: info.channels } });
  await pixels().webp({ quality: 82, effort: 2 }).toFile(path.join(PREVIEW_DIR, `${asset.id}.webp`));
  await pixels().resize({ width: 480, height: 480, fit: 'inside' }).webp({ quality: 74 }).toFile(path.join(THUMB_DIR, `${asset.id}.webp`));
  if (!asset.width && info.width) {
    db.prepare('UPDATE assets SET width = ?, height = ? WHERE id = ?').run(info.width, info.height, asset.id);
  }
}

function libraryPath(takenAt, originalName) {
  const d = new Date(takenAt);
  const dir = path.join(String(d.getFullYear()), String(d.getMonth() + 1).padStart(2, '0'));
  fs.mkdirSync(path.join(LIBRARY_DIR, dir), { recursive: true });
  const ext = path.extname(originalName).toLowerCase();
  const stem = path.basename(originalName, path.extname(originalName)).replace(/[^\w.-]+/g, '_').slice(0, 80) || 'photo';
  let rel = path.join(dir, stem + ext), n = 1;
  while (fs.existsSync(path.join(LIBRARY_DIR, rel))) rel = path.join(dir, `${stem}_${n++}${ext}`);
  return rel;
}

/**
 * Adds a file to the library (moving it). Returns { asset, duplicate }.
 * opts: { originalName, lastModified, deviceId, deviceAsset, editedFrom, checksum, lat, lon }
 */
async function ingest(tmpFile, opts) {
  const checksum = await sha1File(tmpFile);
  const existing = db.prepare('SELECT * FROM assets WHERE checksum = ?').get(checksum);
  if (existing) {
    fs.rmSync(tmpFile, { force: true });
    // Re-uploading something that was in the trash brings it back.
    if (existing.trashed_at) db.prepare('UPDATE assets SET trashed_at = NULL, updated_at = ? WHERE id = ?').run(Date.now(), existing.id);
    if (opts.deviceAsset && !existing.device_asset) {
      db.prepare('UPDATE assets SET device_id = ?, device_asset = ?, updated_at = ? WHERE id = ?').run(opts.deviceId || null, opts.deviceAsset, Date.now(), existing.id);
    }
    return { asset: db.prepare('SELECT * FROM assets WHERE id = ?').get(existing.id), duplicate: true };
  }

  const name = opts.originalName;
  const type = VIDEO_EXT.test(name) ? 'video' : 'image';
  const meta = type === 'video' ? await videoMeta(tmpFile) : await imageMeta(tmpFile);
  // Edited copies made in the browser/app lose EXIF; keep the original's location and camera.
  const source = opts.editedFrom && db.prepare('SELECT * FROM assets WHERE id = ?').get(opts.editedFrom);
  if (source && meta.lat == null) Object.assign(meta, { lat: source.lat, lon: source.lon, make: meta.make || source.make, model: meta.model || source.model });
  // Location from elsewhere (e.g. a Google Takeout sidecar) when the file has none.
  if (meta.lat == null && opts.lat != null && opts.lon != null && (opts.lat || opts.lon)) Object.assign(meta, { lat: opts.lat, lon: opts.lon });
  const hint = Number(opts.lastModified) > 315532800000 ? Number(opts.lastModified) : null; // ignore bogus dates before 1980
  const takenAt = meta.taken_at || hint || Date.now();
  const place = geo.lookup(meta.lat, meta.lon) || {};
  const rel = libraryPath(takenAt, name);
  fs.renameSync(tmpFile, path.join(LIBRARY_DIR, rel));
  const now = Date.now();

  const { lastInsertRowid } = db.prepare(`
    INSERT INTO assets (file, original_name, mime, type, size, checksum, taken_at, uploaded_at, updated_at, width, height, duration,
                        lat, lon, city, region, country, make, model, device_id, device_asset, edited_from)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
    rel, name, mimeOf(name), type, fs.statSync(path.join(LIBRARY_DIR, rel)).size, checksum, Math.round(takenAt), now, now,
    meta.width || null, meta.height || null, meta.duration || null, meta.lat ?? null, meta.lon ?? null,
    place.city || null, place.region || null, place.country || null, meta.make || null, meta.model || null,
    opts.deviceId || null, opts.deviceAsset || null, opts.editedFrom || null);

  const asset = db.prepare('SELECT * FROM assets WHERE id = ?').get(Number(lastInsertRowid));
  try { await makeRenditions(asset); } catch (e) { console.warn(`thumbnail failed for ${name}: ${e.message}`); }
  return { asset: db.prepare('SELECT * FROM assets WHERE id = ?').get(asset.id), duplicate: false };
}

function removeFiles(asset) {
  fs.rmSync(path.join(LIBRARY_DIR, asset.file), { force: true });
  fs.rmSync(path.join(THUMB_DIR, `${asset.id}.webp`), { force: true });
  fs.rmSync(path.join(PREVIEW_DIR, `${asset.id}.webp`), { force: true });
}

module.exports = { ingest, isMedia, removeFiles, makeRenditions, mimeOf, sha1File };
