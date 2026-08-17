package com.Zerodactyl.bloomina

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.Zerodactyl.bloomina.data.OtaConfig
import com.Zerodactyl.bloomina.data.UpdateRepository
import com.Zerodactyl.bloomina.ota.VersionCheck
import com.Zerodactyl.bloomina.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick Settings tile to check for (and jump to) a system update without opening the app.
 * Shows the current availability state and, when tapped, performs a lightweight check:
 * if an update is found it posts the usual notification and opens the app, otherwise it
 * confirms "up to date" with a toast.
 */
class UpdateTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        reflectCachedState()
    }

    override fun onClick() {
        scope.launch {
            val prefs = getSharedPreferences(OtaConfig.PREFS_NAME, 0)
            val url = OtaConfig.resolveJsonUrl(prefs)
            val available = UpdateRepository().fetchManifest(url, connectTimeout = 8000, readTimeout = 8000)
                .getOrNull()
                ?.let { VersionCheck.evaluate(it.release).updateAvailable }
                ?: false
            if (available) {
                notifyUpdateAvailable()
                startActivityAndCollapse(
                    Intent(this@UpdateTileService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                reflectState(available)
                runOnUiThread {
                    Toast.makeText(this@UpdateTileService, getString(R.string.tile_up_to_date), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun reflectCachedState() {
        val available = getSharedPreferences(OtaConfig.PREFS_NAME, 0).getBoolean("cached_available", false)
        reflectState(available)
    }

    private fun reflectState(available: Boolean) {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(if (available) R.string.tile_update_available else R.string.tile_check)
            icon = Icon.createWithResource(
                this@UpdateTileService,
                if (available) R.drawable.ic_status_available else R.drawable.ic_status_uptodate,
            )
            updateTile()
        }
    }

    private fun notifyUpdateAvailable() {
        val tap = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, "ota_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_update_title))
            .setContentText(getString(R.string.notif_update_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(2, n)
    }
}
