package com.Zerodactyl.bloomina.data

import android.content.Context
import android.content.SharedPreferences
import com.Zerodactyl.bloomina.ota.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shared OTA configuration constants and helpers, previously scattered as a `companion object`
 * inside [com.Zerodactyl.bloomina.ui.CheckUpdateFragment] and re-referenced from
 * [com.Zerodactyl.bloomina.ui.MaintainerFragment] and
 * [com.Zerodactyl.bloomina.BackgroundUpdateCheck]. Centralizing them removes the cross-class
 * coupling and the magic default-URL string living in a UI class.
 */
object OtaConfig {

    const val PREFS_NAME = "bloomina"

    private const val OTA_BASE = "https://over-the-air.tuong.qzz.io/bloomina"

    // Active (in-flight or completed-pending-install) download bookkeeping.
    private const val ACTIVE_OTA_FILE = "active_ota_file"
    private const val ACTIVE_OTA_SHA = "active_ota_sha"
    private const val ACTIVE_OTA_TYPE = "active_ota_install"
    private const val ACTIVE_OTA_DONE = "active_ota_done"

    /** Default manifest location. The device codename is auto-detected from
     *  `ro.product.vendor.device` (falling back to Build.DEVICE), e.g. .../16.2/a32.json */
    val defaultJsonUrl: String
        get() = "$OTA_BASE/${DeviceInfo.romName}/${DeviceInfo.deviceCodename}.json"

    /** User opt-in to install automatically once a completed download is on a charging device. */
    fun isAutoInstallEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, 0).getBoolean("auto_install", false)

    /** Returns the user-configured URL, or the auto-detected default when unset/blank. */
    fun resolveJsonUrl(prefs: SharedPreferences): String =
        prefs.getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultJsonUrl

    data class ActiveDownload(
        val file: File,
        val sha256: String,
        val installType: String,
        val done: Boolean,
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setActiveDownload(ctx: Context, file: File, sha256: String, installType: String) {
        prefs(ctx).edit().apply {
            putString(ACTIVE_OTA_FILE, file.absolutePath)
            putString(ACTIVE_OTA_SHA, sha256)
            putString(ACTIVE_OTA_TYPE, installType)
            putBoolean(ACTIVE_OTA_DONE, false)
            apply()
        }
    }

    fun markActiveDownloadDone(ctx: Context) {
        prefs(ctx).edit().putBoolean(ACTIVE_OTA_DONE, true).apply()
    }

    fun clearActiveDownload(ctx: Context) {
        prefs(ctx).edit().apply {
            remove(ACTIVE_OTA_FILE)
            remove(ACTIVE_OTA_SHA)
            remove(ACTIVE_OTA_TYPE)
            remove(ACTIVE_OTA_DONE)
            apply()
        }
    }

    /** Returns the recorded active download, or null if none is tracked. */
    fun getActiveDownload(ctx: Context): ActiveDownload? {
        val p = prefs(ctx)
        val path = p.getString(ACTIVE_OTA_FILE, null) ?: return null
        return ActiveDownload(
            file = File(path),
            sha256 = p.getString(ACTIVE_OTA_SHA, "") ?: "",
            installType = p.getString(ACTIVE_OTA_TYPE, "") ?: "",
            done = p.getBoolean(ACTIVE_OTA_DONE, false),
        )
    }

    // Update history (builds this device has applied).
    private const val UPDATE_HISTORY = "update_history"
    private const val MAX_HISTORY = 50

    data class AppliedUpdate(val version: String, val timestamp: Long)

    fun recordAppliedUpdate(ctx: Context, version: String) {
        if (version.isBlank()) return
        val p = prefs(ctx)
        val arr = runCatching { JSONArray(p.getString(UPDATE_HISTORY, "[]")) }
            .getOrDefault(JSONArray())
        val obj = JSONObject().apply {
            put("version", version)
            put("ts", System.currentTimeMillis())
        }
        arr.put(obj)
        while (arr.length() > MAX_HISTORY) arr.remove(0)
        p.edit().putString(UPDATE_HISTORY, arr.toString()).apply()
    }

    fun getUpdateHistory(ctx: Context): List<AppliedUpdate> {
        val p = prefs(ctx)
        val arr = runCatching { JSONArray(p.getString(UPDATE_HISTORY, "[]")) }
            .getOrDefault(JSONArray())
        val list = ArrayList<AppliedUpdate>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += AppliedUpdate(o.optString("version", "?"), o.optLong("ts", 0L))
        }
        return list.asReversed()
    }

    // Last install result (for the in-app log viewer).
    private const val INSTALL_LOG = "install_log"

    data class InstallRecord(val timestamp: Long, val success: Boolean, val detail: String)

    fun recordInstallLog(ctx: Context, success: Boolean, detail: String) {
        val obj = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("success", success)
            put("detail", detail)
        }
        prefs(ctx).edit().putString(INSTALL_LOG, obj.toString()).apply()
    }

    fun getInstallLog(ctx: Context): InstallRecord? {
        val s = prefs(ctx).getString(INSTALL_LOG, null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            InstallRecord(o.optLong("ts", 0L), o.optBoolean("success"), o.optString("detail", ""))
        }.getOrNull()
    }
}
