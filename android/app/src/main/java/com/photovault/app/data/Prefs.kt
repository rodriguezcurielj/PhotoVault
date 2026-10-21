package com.photovault.app.data

import android.content.Context
import android.content.SharedPreferences
import com.photovault.app.BuildConfig
import java.util.UUID

/** Small settings store (server, login token, backup options). */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("photovault", Context.MODE_PRIVATE)

    var server: String
        // The old Tailscale-only address moved to the public one.
        get() = p.getString("server", null)?.takeIf { it != "https://budget-pc.tail05789d.ts.net:9443" } ?: BuildConfig.DEFAULT_SERVER
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    val deviceId: String
        get() = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }

    var backupEnabled: Boolean
        get() = p.getBoolean("backup", true)
        set(v) = p.edit().putBoolean("backup", v).apply()
    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", false)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    var chargingOnly: Boolean
        get() = p.getBoolean("chargingOnly", false)
        set(v) = p.edit().putBoolean("chargingOnly", v).apply()
    var backupVideos: Boolean
        get() = p.getBoolean("videos", true)
        set(v) = p.edit().putBoolean("videos", v).apply()
    /** Device folders (bucket ids) the user turned off for backup. */
    var excludedBuckets: Set<String>
        get() = p.getStringSet("excluded", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("excluded", v).apply()
    var columns: Int
        get() = p.getInt("columns", 3)
        set(v) = p.edit().putInt("columns", v).apply()
    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) = p.edit().putLong("lastSync", v).apply()
    /** "system" | "light" | "dark" */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit().putString("theme", v).apply()
    var accent: Long
        get() = p.getLong("accent", 0xFF007AFF)
        set(v) = p.edit().putLong("accent", v).apply()
    /** Last app versionCode we sent an "update available" notification for. */
    var notifiedVersion: Int
        get() = p.getInt("notifiedVersion", 0)
        set(v) = p.edit().putInt("notifiedVersion", v).apply()
    var askedDefault: Boolean
        get() = p.getBoolean("askedDefault", false)
        set(v) = p.edit().putBoolean("askedDefault", v).apply()
}
