package com.Zerodactyl.bloomina.data

import android.content.Context
import android.content.SharedPreferences
import com.Zerodactyl.bloomina.ota.DeviceInfo
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
}
