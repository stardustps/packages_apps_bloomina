package com.Zerodactyl.bloomina

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.Zerodactyl.bloomina.data.UpdateManifest
import com.Zerodactyl.bloomina.data.UpdateRepository
import com.Zerodactyl.bloomina.ota.VersionCheck
import com.Zerodactyl.bloomina.ui.CheckUpdateFragment
import com.Zerodactyl.bloomina.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Schedules a periodic background update check and posts a notification when a newer
 * build is available. Uses only framework APIs (AlarmManager) — no extra library deps.
 */
object UpdateScheduler {
    private const val INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000,
            INTERVAL_MS,
            pi
        )
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("bloomina", 0)
                val url = prefs.getString("json_url", null)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: CheckUpdateFragment.DEFAULT_JSON_URL
                UpdateRepository().fetchManifest(url, connectTimeout = 5000, readTimeout = 5000)
                    .onSuccess { m ->
                        if (VersionCheck.evaluate(m.release).updateAvailable) notifyUpdate(context, m)
                    }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyUpdate(context: Context, m: UpdateManifest) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "ota_updates",
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, "ota_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_update_title))
            .setContentText(context.getString(R.string.notif_update_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            UpdateScheduler.schedule(context)
        }
    }
}
