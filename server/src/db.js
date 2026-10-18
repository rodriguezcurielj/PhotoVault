// SQLite storage for photo metadata, albums and login sessions (node:sqlite, no native addon needed).
const { DatabaseSync } = require('node:sqlite');
const path = require('path');
const { DB_DIR } = require('./config');
require('fs').mkdirSync(DB_DIR, { recursive: true });

const db = new DatabaseSync(path.join(DB_DIR, 'photovault.db'));
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;

  CREATE TABLE IF NOT EXISTS assets (
    id            INTEGER PRIMARY KEY,
    file          TEXT NOT NULL,          -- path under library/
    original_name TEXT NOT NULL,
    mime          TEXT,
    type          TEXT NOT NULL,          -- 'image' | 'video'
    size          INTEGER,
    checksum      TEXT UNIQUE,            -- sha1 hex, used to skip duplicates
    taken_at      INTEGER NOT NULL,       -- ms since epoch
    uploaded_at   INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    width         INTEGER,
    height        INTEGER,
    duration      REAL,
    lat           REAL,
    lon           REAL,
    city          TEXT,
    region        TEXT,
    country       TEXT,
    make          TEXT,
    model         TEXT,
    favorite      INTEGER NOT NULL DEFAULT 0,
    archived      INTEGER NOT NULL DEFAULT 0,
    locked        INTEGER NOT NULL DEFAULT 0,
    trashed_at    INTEGER,
    description   TEXT,
    device_id     TEXT,
    device_asset  TEXT,
    edited_from   INTEGER
  );
  CREATE INDEX IF NOT EXISTS assets_taken   ON assets(taken_at);
  CREATE INDEX IF NOT EXISTS assets_updated ON assets(updated_at);
  CREATE INDEX IF NOT EXISTS assets_device  ON assets(device_id, device_asset);

  CREATE TABLE IF NOT EXISTS deleted (id INTEGER PRIMARY KEY, deleted_at INTEGER NOT NULL);

  CREATE TABLE IF NOT EXISTS albums (
    id          INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    cover_id    INTEGER,
    share_token TEXT UNIQUE
  );
  CREATE TABLE IF NOT EXISTS album_assets (
    album_id INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
    asset_id INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (album_id, asset_id)
  );

  CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    device     TEXT,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT);
`);

const getSetting = key => db.prepare('SELECT value FROM settings WHERE key = ?').get(key)?.value ?? null;
const setSetting = (key, value) =>
  db.prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run(key, value);

module.exports = { db, getSetting, setSetting };
