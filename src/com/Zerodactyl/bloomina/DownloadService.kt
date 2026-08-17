package com.Zerodactyl.bloomina

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.Zerodactyl.bloomina.data.Download
import com.Zerodactyl.bloomina.data.DownloadBus
import com.Zerodactyl.bloomina.data.DownloadState
import com.Zerodactyl.bloomina.data.OtaConfig
import com.Zerodactyl.bloomina.data.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the actual OTA download coroutine in a long-lived service so the transfer keeps
 * running even if the user leaves the app or the system reclaims the UI. Progress is mirrored
 * to [DownloadBus] (observed by the ViewModel) and to a foreground notification.
 *
 * Pause = cancel the coroutine; resume = restart the service, which range-resumes from the
 * partial file (see [UpdateRepository.download]).
 */
class DownloadService : Service() {

    private val repo = UpdateRepository()
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                val dl = intent.getParcelableExtraCompat<Download>(EXTRA_DOWNLOAD)
                val dest = intent.getStringExtra(EXTRA_DEST)?.let { File(it) }
                if (dl == null || dest == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startDownload(dl, dest)
            }
            ACTION_PAUSE -> pause()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startDownload(dl: Download, dest: File) {
        job?.cancel()
        OtaConfig.setActiveDownload(this, dest, dl.sha256, dl.installType)
        createChannel()
        startForeground(NOTIF_ID, buildNotification(0, getString(R.string.notif_preparing), indeterminate = true))
        job = scope.launch {
            var attempt = 0
            while (true) {
                try {
                    repo.download(dl, dest).collect { st ->
                        when (st) {
                            is DownloadState.Progress -> {
                                val pct = if (st.total > 0) ((st.bytes * 100) / st.total).toInt() else 0
                                DownloadBus.post(
                                    DownloadBus.Snapshot(
                                        status = DownloadBus.Status.DOWNLOADING,
                                        bytes = st.bytes,
                                        total = st.total,
                                    ),
                                )
                                updateNotification(pct, formatSize(st.bytes) + " / " + formatSize(st.total))
                            }
                            is DownloadState.Done -> {
                                DownloadBus.post(
                                    DownloadBus.Snapshot(
                                        status = DownloadBus.Status.DONE,
                                        total = dest.length(),
                                        file = dest.absolutePath,
                                    ),
                                )
                                DownloadBus.pendingInstallPath = dest.absolutePath
                                OtaConfig.markActiveDownloadDone(this@DownloadService)
                                showResultNotification(
                                    title = getString(R.string.notif_done_title),
                                    text = getString(R.string.notif_done_text),
                                )
                                stopForeground(STOP_FOREGROUND_DETACH)
                                stopSelf()
                            }
                            is DownloadState.Failed -> {
                                DownloadBus.post(
                                    DownloadBus.Snapshot(
                                        status = DownloadBus.Status.FAILED,
                                        error = st.reason,
                                    ),
                                )
                                showResultNotification(
                                    title = getString(R.string.notif_failed_title),
                                    text = st.reason,
                                )
                                stopSelf()
                            }
                        }
                    }
                    break
                } catch (_: CancellationException) {
                    throw _ // Paused — handled by pause().
                } catch (e: Exception) {
                    if (++attempt > MAX_ATTEMPTS) {
                        DownloadBus.post(DownloadBus.Snapshot(DownloadBus.Status.FAILED, error = e.message))
                        showResultNotification(getString(R.string.notif_failed_title), e.message ?: "")
                        stopSelf()
                        break
                    }
                    // Transient (e.g. network drop): brief backoff, then range-resume from the
                    // partial file. The foreground notification stays up the whole time.
                    DownloadBus.post(
                        DownloadBus.Snapshot(
                            status = DownloadBus.Status.DOWNLOADING,
                            bytes = dest.length(),
                            retrying = true,
                        ),
                    )
                    updateNotification(0, getString(R.string.notif_retrying))
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun pause() {
        job?.cancel()
        DownloadBus.post(DownloadBus.Snapshot(DownloadBus.Status.PAUSED))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = NotificationManagerCompat.from(this)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(progress: Int, text: String, indeterminate: Boolean): android.app.Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
    }

    private fun updateNotification(progress: Int, text: String) {
        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(progress, text, indeterminate = false))
    }

    private fun showResultNotification(title: String, text: String) {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
    }

    @Suppress("DEPRECATION")
    private fun <T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        this.getParcelableExtra(key)

    companion object {
        const val ACTION_START = "com.Zerodactyl.bloomina.action.DOWNLOAD_START"
        const val ACTION_PAUSE = "com.Zerodactyl.bloomina.action.DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.Zerodactyl.bloomina.action.DOWNLOAD_RESUME"
        const val EXTRA_DOWNLOAD = "extra_download"
        const val EXTRA_DEST = "extra_dest"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "ota_download"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 15_000L
    }
}
