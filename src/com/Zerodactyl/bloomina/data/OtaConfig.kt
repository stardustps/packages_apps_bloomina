package com.Zerodactyl.bloomina.data

import android.content.SharedPreferences
import com.Zerodactyl.bloomina.ota.DeviceInfo

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
}
