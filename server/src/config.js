const path = require('path');
const fs = require('fs');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', '..', 'data');
const dirs = {
  DATA_DIR,
  LIBRARY_DIR: path.join(DATA_DIR, 'library'),   // originals, organised as YYYY/MM/
  THUMB_DIR: path.join(DATA_DIR, 'thumbs'),      // small square-ish grid images
  PREVIEW_DIR: path.join(DATA_DIR, 'previews'),  // screen-size images for the viewer
  UPLOAD_TMP: path.join(DATA_DIR, 'tmp'),
  IMPORT_DIR: path.join(DATA_DIR, 'import'),     // drop files here to add them to the library
};
for (const d of Object.values(dirs)) fs.mkdirSync(d, { recursive: true });

module.exports = {
  ...dirs,
  DB_DIR: process.env.DB_DIR || DATA_DIR,
  PORT: Number(process.env.PORT) || 8420,
  GEO_DIR: process.env.GEO_DIR || path.join(__dirname, '..', 'geo'),
  TRASH_DAYS: 30,
  SESSION_DAYS: 365,
};
