package com.photovault.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Remembers which phone items are already on the server (phone id -> server id). */
class BackupDb(context: Context) : SQLiteOpenHelper(context, "backup.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE backed (local_id INTEGER PRIMARY KEY, remote_id INTEGER NOT NULL, checksum TEXT, size INTEGER, modified INTEGER)")
        db.execSQL("CREATE TABLE failed (local_id INTEGER PRIMARY KEY, error TEXT, at INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    data class Entry(val remoteId: Long, val size: Long, val modified: Long)

    fun all(): Map<Long, Entry> {
        val out = HashMap<Long, Entry>()
        readableDatabase.rawQuery("SELECT local_id, remote_id, size, modified FROM backed", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = Entry(c.getLong(1), c.getLong(2), c.getLong(3))
        }
        return out
    }

    fun mark(local: LocalItem, remoteId: Long, checksum: String?) {
        writableDatabase.insertWithOnConflict("backed", null, ContentValues().apply {
            put("local_id", local.id); put("remote_id", remoteId); put("checksum", checksum)
            put("size", local.size); put("modified", local.modified)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.delete("failed", "local_id = ?", arrayOf(local.id.toString()))
    }

    /** After signing out or switching servers, nothing counts as backed up any more. */
    fun clear() { writableDatabase.execSQL("DELETE FROM backed"); writableDatabase.execSQL("DELETE FROM failed") }

    fun forgetRemote(remoteIds: Collection<Long>) {
        if (remoteIds.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM backed WHERE remote_id IN (${remoteIds.joinToString(",")})")
    }

    fun failed(localId: Long, error: String) {
        writableDatabase.insertWithOnConflict("failed", null, ContentValues().apply {
            put("local_id", localId); put("error", error); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun failedIds(): Set<Long> {
        val out = HashSet<Long>()
        // Retry failures after 6 hours.
        readableDatabase.rawQuery("SELECT local_id FROM failed WHERE at > ?", arrayOf((System.currentTimeMillis() - 6 * 3600_000).toString())).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }
}
