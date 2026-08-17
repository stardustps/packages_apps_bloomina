package com.Zerodactyl.bloomina.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.Download
import com.Zerodactyl.bloomina.data.OtaConfig
import com.Zerodactyl.bloomina.data.UpdateManifest
import com.Zerodactyl.bloomina.data.UpdateRepository
import com.Zerodactyl.bloomina.ota.DeviceInfo
import com.Zerodactyl.bloomina.ota.InstallResult
import com.Zerodactyl.bloomina.ota.OtaInstaller
import com.Zerodactyl.bloomina.ota.VersionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Owns the entire update lifecycle (check → download → install) for Tab 1, so
 * [CheckUpdateFragment] is a thin view that renders [CheckUiState] and reacts to [CheckEvent]s.
 *
 * Everything that touches the network, disk, or system properties runs on [Dispatchers.IO]
 * through [viewModelScope], which survives configuration changes — fixing the old NPE where a
 * mid-check tab switch resumed into a nulled view holder.
 */
class CheckUpdateViewModel : AndroidViewModel() {

    // ---- State -------------------------------------------------------------

    data class LocalDeviceInfo(
        val installed: String,
        val model: String,
        val android: String,
        val patch: String,
        val fingerprint: String,
        val kernel: String
    )

    data class RemoteReleaseView(
        val version: String,
        val buildDate: String,
        val size: String,
        val androidVersion: String,
        val securityPatch: String,
        val fingerprint: String,
        val changelog: CharSequence
    )

    enum class DownloadButtonState { HIDDEN, DOWNLOAD, RETRY, INSTALL, REBOOT }

    data class CheckUiState(
        val heroIcon: Int = R.drawable.ic_cloud_large,
        val heroTitle: String = "",
        val heroSubtitle: String = "",
        val checking: Boolean = false,
        val downloadVisible: Boolean = false,
        val downloadIndeterminate: Boolean = false,
        val downloadProgress: Int = 0,
        val remote: RemoteReleaseView? = null,
        val installed: LocalDeviceInfo = LocalDeviceInfo("", "", "", "", "", ""),
        val showReleaseSections: Boolean = false,
        val updateAvailable: Boolean = false,
        val button: DownloadButtonState = DownloadButtonState.HIDDEN,
        val integrity: String? = null,
        val lastChecked: Long = 0L,
        val error: Boolean = false,
        val hasIncremental: Boolean = false,
        val useIncremental: Boolean = false
    )

    sealed interface CheckEvent {
        data class Toast(val message: String) : CheckEvent
        data object ConfirmMeteredDownload : CheckEvent
        data class ConfirmBatteryInstall(val file: File) : CheckEvent
        data object ShowRebootSheet : CheckEvent
    }

    private val _state = MutableStateFlow(CheckUiState())
    val uiState: StateFlow<CheckUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CheckEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CheckEvent> = _events.asSharedFlow()

    // ---- Private state held by the ViewModel -------------------------------

    private val repo = UpdateRepository()
    private var manifest: UpdateManifest? = null
    private var incrementalDownload: Download? = null
    private var pendingInstallFile: File? = null

    private val app: Application get() = getApplication()
    private fun S(resId: Int, vararg fmt: Any): String = app.getString(resId, *fmt)

    // ---- Public API --------------------------------------------------------

    /** Prep work done on view attach: cache cleanup, local device rows, cached manifest. */
    fun initialize() {
        cleanupOldOtas()
        loadLocalDeviceInfo()
        loadCached()
        refreshLastChecked()
    }

    fun check() {
        _state.update {
            it.copy(
                heroIcon = R.drawable.ic_cloud_large,
                heroTitle = S(R.string.status_checking),
                heroSubtitle = S(R.string.status_checking_sub),
                checking = true,
                downloadVisible = true,
                downloadIndeterminate = true,
                button = DownloadButtonState.HIDDEN,
                error = false
            )
        }

        viewModelScope.launch {
            val url = OtaConfig.resolveJsonUrl(app.getSharedPreferences(OtaConfig.PREFS_NAME, 0))
            val result = repo.fetchManifest(url)

            result
                .onSuccess { m ->
                    manifest = m
                    val r = m.release
                    incrementalDownload = r.incrementalDownload
                    val chosen = r.incrementalDownload ?: r.download
                    val useInc = r.incrementalDownload != null
                    val changelog = buildChangelog(r.changelog)
                    val verdict = withContext(Dispatchers.IO) { VersionCheck.evaluate(r) }
                    _state.update {
                        it.copy(
                            remote = RemoteReleaseView(
                                version = r.version,
                                buildDate = r.buildDate,
                                size = formatBytes(chosen.sizeBytes),
                                androidVersion = r.androidVersion,
                                securityPatch = r.securityPatch,
                                fingerprint = r.fingerprint,
                                changelog = changelog
                            ),
                            installed = it.installed.copy(installed = verdict.installed),
                            hasIncremental = useInc,
                            useIncremental = useInc
                        )
                    }

                    if (verdict.updateAvailable) {
                        _state.update {
                            it.copy(
                                heroIcon = R.drawable.ic_status_available,
                                heroTitle = S(R.string.status_update_available),
                                heroSubtitle = S(R.string.status_update_available_sub, r.version),
                                showReleaseSections = true,
                                updateAvailable = true,
                                button = DownloadButtonState.DOWNLOAD,
                                checking = false,
                                downloadVisible = false
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                heroIcon = R.drawable.ic_status_uptodate,
                                heroTitle = S(R.string.status_up_to_date),
                                heroSubtitle = S(R.string.status_up_to_date_sub, verdict.installed),
                                showReleaseSections = false,
                                updateAvailable = false,
                                button = DownloadButtonState.HIDDEN,
                                checking = false,
                                downloadVisible = false
                            )
                        }
                    }
                    persistManifest(m, verdict)
                }
                .onFailure { t ->
                    manifest = null
                    val hasCache = app.getSharedPreferences(OtaConfig.PREFS_NAME, 0)
                        .getString("cached_version", "")?.isNotBlank() == true
                    if (hasCache) {
                        loadCached()
                    } else {
                        _state.update {
                            it.copy(
                                heroIcon = R.drawable.ic_status_error,
                                heroTitle = S(R.string.status_failed),
                                heroSubtitle = UpdateRepository.describe(t),
                                showReleaseSections = false,
                                button = DownloadButtonState.HIDDEN,
                                checking = false,
                                downloadVisible = false,
                                error = true
                            )
                        }
                    }
                }

            refreshLastChecked()
            _state.update { it.copy(checking = false, downloadVisible = false) }
        }
    }

    /** The download the user will actually fetch: incremental delta when selected, else full. */
    private fun activeDownload(): Download? {
        val release = manifest?.release ?: return null
        return if (_state.value.useIncremental) release.incrementalDownload ?: release.download
        else release.download
    }

    /** Primary action button (download / retry / install / reboot) dispatcher. */
    fun onPrimaryButtonClicked() {
        when (_state.value.button) {
            DownloadButtonState.DOWNLOAD, DownloadButtonState.RETRY ->
                activeDownload()?.let { beginDownload(it) }
            DownloadButtonState.INSTALL -> pendingInstallFile?.let { install(it) }
            DownloadButtonState.REBOOT -> _events.trySend(CheckEvent.ShowRebootSheet)
            DownloadButtonState.HIDDEN -> Unit
        }
    }

    fun confirmMeteredDownload() {
        activeDownload()?.let { performDownload(it) }
    }

    /** Toggle between the full and incremental package; the displayed size follows. */
    fun setUseIncremental(use: Boolean) {
        if (_state.value.useIncremental == use) return
        val release = manifest?.release ?: return
        val dl = if (use) release.incrementalDownload ?: release.download else release.download
        _state.update {
            it.copy(
                useIncremental = use,
                remote = it.remote?.copy(size = formatBytes(dl.sizeBytes))
            )
        }
    }

    fun install(file: File) {
        val dl = activeDownload() ?: return
        _state.update {
            it.copy(
                heroIcon = R.drawable.ic_status_available,
                heroTitle = S(R.string.status_installing),
                heroSubtitle = "",
                button = DownloadButtonState.HIDDEN,
                downloadVisible = false
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                OtaInstaller(app).installPackage(file)
            }
            when (result) {
                is InstallResult.StagedRebootingToRecovery -> {
                    _state.update {
                        it.copy(
                            heroIcon = R.drawable.ic_status_available,
                            heroTitle = S(R.string.install_staged_title),
                            heroSubtitle = S(R.string.install_staged_sub)
                        )
                    }
                    persistPendingVersion()
                    deleteLocalZipIfNeeded(file)
                }
                is InstallResult.AppliedBackgroundRebootRequired -> {
                    _state.update {
                        it.copy(
                            heroIcon = R.drawable.ic_status_available,
                            heroTitle = S(R.string.install_applied_title),
                            heroSubtitle = S(R.string.install_applied_sub),
                            button = DownloadButtonState.REBOOT
                        )
                    }
                    persistPendingVersion()
                    deleteLocalZipIfNeeded(file)
                    _events.trySend(CheckEvent.ShowRebootSheet)
                }
                is InstallResult.Failed -> {
                    _state.update {
                        it.copy(
                            heroIcon = R.drawable.ic_status_error,
                            heroTitle = S(R.string.install_failed_title),
                            heroSubtitle = result.why,
                            button = DownloadButtonState.DOWNLOAD
                        )
                    }
                }
            }
        }
    }

    fun exportCurrentRelease() {
        val dl = activeDownload() ?: return
        exportUpdate(dl)
    }

    // ---- Download -----------------------------------------------------------

    private fun beginDownload(dl: Download) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) {
            _state.update {
                it.copy(
                    heroIcon = R.drawable.ic_status_error,
                    heroTitle = S(R.string.status_failed),
                    heroSubtitle = S(R.string.error_no_network),
                    button = DownloadButtonState.HIDDEN
                )
            }
            return
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            _events.trySend(CheckEvent.ConfirmMeteredDownload)
            return
        }
        performDownload(dl)
    }

    private fun performDownload(dl: Download) {
        val extDir = app.getExternalFilesDir(null) ?: run {
            _state.update {
                it.copy(
                    heroIcon = R.drawable.ic_status_error,
                    heroTitle = S(R.string.status_failed),
                    heroSubtitle = "External storage unavailable",
                    button = DownloadButtonState.HIDDEN
                )
            }
            return
        }
        app.getSharedPreferences(OtaConfig.PREFS_NAME, 0).edit().putString("active_ota_file", dl.filename).apply()
        val dest = File(extDir, dl.filename)

        _state.update {
            it.copy(
                downloadVisible = true,
                downloadIndeterminate = true,
                button = DownloadButtonState.HIDDEN
            )
        }

        viewModelScope.launch {
            var lastMs = System.currentTimeMillis()
            var lastBytes = 0L
            repo.download(dl, dest).collect { st ->
                when (st) {
                    is com.Zerodactyl.bloomina.data.DownloadState.Progress -> {
                        val pct = (st.fraction * 100).toInt()
                        val now = System.currentTimeMillis()
                        val dt = (now - lastMs) / 1000.0
                        val speed = if (dt > 0) (st.bytes - lastBytes) / dt else 0.0
                        lastMs = now
                        lastBytes = st.bytes
                        val speedStr = if (speed > 0) formatBytes(speed.toLong()) + "/s" else "—"
                        _state.update {
                            it.copy(
                                downloadIndeterminate = false,
                                downloadProgress = pct,
                                heroIcon = R.drawable.ic_status_available,
                                heroTitle = S(R.string.status_downloading),
                                heroSubtitle = S(R.string.download_speed, pct, speedStr)
                            )
                        }
                        postNotification(1, S(R.string.notif_downloading_title), "$pct%", true, pct)
                    }
                    is com.Zerodactyl.bloomina.data.DownloadState.Failed -> {
                        _state.update {
                            it.copy(
                                downloadVisible = false,
                                heroIcon = R.drawable.ic_status_error,
                                heroTitle = S(R.string.status_failed),
                                heroSubtitle = st.reason,
                                button = DownloadButtonState.RETRY
                            )
                        }
                        postNotification(1, S(R.string.notif_download_failed), st.reason, false, -1)
                    }
                    is com.Zerodactyl.bloomina.data.DownloadState.Done -> {
                        _state.update {
                            it.copy(
                                downloadVisible = false,
                                integrity = S(R.string.integrity_verified, dl.sha256),
                                button = DownloadButtonState.INSTALL
                            )
                        }
                        postNotification(1, S(R.string.notif_download_complete), S(R.string.notif_ready_to_install), false, -1)
                        pendingInstallFile = st.file

                        val batteryStatus: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                        val batteryPct = if (scale > 0) level * 100 / scale.toFloat() else -1f
                        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                        if (batteryPct in 0f..20f && !isCharging) {
                            _events.trySend(CheckEvent.ConfirmBatteryInstall(st.file))
                        } else {
                            install(st.file)
                        }
                    }
                }
            }
        }
    }

    // ---- Local update / export ---------------------------------------------

    fun handleLocalUpdate(uri: Uri) {
        _state.update {
            it.copy(
                heroIcon = R.drawable.ic_cloud_large,
                heroTitle = S(R.string.local_staging),
                heroSubtitle = S(R.string.local_copying),
                downloadVisible = true,
                downloadIndeterminate = true
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = File(app.cacheDir, "local_update.zip")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(downloadVisible = false) }
                    install(dest)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            downloadVisible = false,
                            heroIcon = R.drawable.ic_status_error,
                            heroTitle = S(R.string.local_failed_title),
                            heroSubtitle = e.message ?: S(R.string.local_failed_msg)
                        )
                    }
                }
            }
        }
    }

    private fun exportUpdate(dl: Download) {
        val src = File(app.getExternalFilesDir(null) ?: return, dl.filename)
        if (!src.exists()) {
            _events.trySend(CheckEvent.Toast(S(R.string.export_not_found)))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, dl.filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("Failed to create MediaStore entry")
                app.contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(src).use { input -> input.copyTo(output) }
                } ?: throw java.io.IOException("Failed to open output stream")
                _events.trySend(CheckEvent.Toast(S(R.string.export_success)))
            } catch (e: Exception) {
                _events.trySend(CheckEvent.Toast(S(R.string.export_failed, e.message ?: "")))
            }
        }
    }

    // ---- Local device info / cache -----------------------------------------

    private fun loadLocalDeviceInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val info = LocalDeviceInfo(
                installed = DeviceInfo.romVersion.ifBlank { "${DeviceInfo.PROP_ROM_VER} unset" },
                model = DeviceInfo.model,
                android = DeviceInfo.androidVersion,
                patch = DeviceInfo.securityPatch,
                fingerprint = DeviceInfo.fingerprint,
                kernel = DeviceInfo.kernelVersion
            )
            _state.update { it.copy(installed = info) }
        }
    }

    private fun loadCached() {
        val prefs = app.getSharedPreferences(OtaConfig.PREFS_NAME, 0)
        val version = prefs.getString("cached_version", "") ?: ""
        if (version.isBlank()) {
            _state.update { it.copy(showReleaseSections = false) }
            return
        }
        val size = prefs.getString("cached_size", "0")?.toLongOrNull() ?: 0L
        val changelog = prefs.getString("cached_changelog", "") ?: ""
        val changelogText = if (changelog.isBlank()) {
            S(R.string.changelog_empty)
        } else {
            Html.fromHtml(changelog.split("\n").joinToString("<br>") { "&#8226; $it" }, Html.FROM_HTML_MODE_COMPACT)
        }
        val inc = restoreDownload(prefs, "cached_inc_")
        incrementalDownload = inc
        val useInc = inc != null
        val chosenSize = if (useInc) inc!!.sizeBytes else size
        val available = prefs.getBoolean("cached_available", false)
        _state.update {
            it.copy(
                remote = RemoteReleaseView(
                    version = version,
                    buildDate = prefs.getString("cached_build_date", "-") ?: "-",
                    size = formatBytes(chosenSize),
                    androidVersion = prefs.getString("cached_android", "-") ?: "-",
                    securityPatch = prefs.getString("cached_security", "-") ?: "-",
                    fingerprint = prefs.getString("cached_fingerprint", "-") ?: "-",
                    changelog = changelogText
                ),
                showReleaseSections = available,
                heroIcon = if (available) R.drawable.ic_status_available else R.drawable.ic_status_uptodate,
                heroTitle = if (available) S(R.string.status_update_available) else S(R.string.status_up_to_date),
                heroSubtitle = if (available) S(R.string.status_update_available_sub, version) else S(R.string.cached_sub),
                button = if (available) DownloadButtonState.DOWNLOAD else DownloadButtonState.HIDDEN,
                hasIncremental = useInc,
                useIncremental = useInc
            )
        }
    }

    /** Rebuilds a [Download] from prefixed SharedPreferences keys, or null if no URL was stored. */
    private fun restoreDownload(prefs: android.content.SharedPreferences, p: String): Download? {
        val url = prefs.getString(p + "url", "") ?: ""
        if (url.isBlank()) return null
        return Download(
            url = url,
            filename = prefs.getString(p + "filename", "") ?: "",
            sizeBytes = prefs.getLong(p + "size", -1L),
            sha256 = prefs.getString(p + "sha", "") ?: "",
            installType = prefs.getString(p + "install_type", "") ?: ""
        )
    }

    private fun persistManifest(m: UpdateManifest, verdict: VersionCheck.Result) {
        val prefs = app.getSharedPreferences(OtaConfig.PREFS_NAME, 0).edit()
        val r = m.release
        prefs.putString("cached_version", r.version)
        prefs.putLong("cached_version_code", r.versionCode ?: -1L)
        prefs.putString("cached_build_date", r.buildDate)
        prefs.putString("cached_size", r.download.sizeBytes.toString())
        prefs.putString("cached_android", r.androidVersion)
        prefs.putString("cached_security", r.securityPatch)
        prefs.putString("cached_fingerprint", r.fingerprint)
        prefs.putString("cached_changelog", r.changelog.joinToString("\n"))
        prefs.putString("cached_rom", m.romName)
        prefs.putBoolean("cached_available", verdict.updateAvailable)
        val inc = r.incrementalDownload
        prefs.putString("cached_inc_url", inc?.url ?: "")
        prefs.putString("cached_inc_filename", inc?.filename ?: "")
        prefs.putLong("cached_inc_size", inc?.sizeBytes ?: -1L)
        prefs.putString("cached_inc_sha", inc?.sha256 ?: "")
        prefs.putString("cached_inc_install_type", inc?.installType ?: "")
        prefs.apply()
    }

    private fun persistPendingVersion() {
        app.getSharedPreferences(OtaConfig.PREFS_NAME, 0).edit()
            .putString("pending_update_version", manifest?.release?.version ?: "").apply()
    }

    private fun deleteLocalZipIfNeeded(file: File) {
        if (file.name == "local_update.zip") runCatching { file.delete() }
    }

    private fun refreshLastChecked() {
        val ms = app.getSharedPreferences(OtaConfig.PREFS_NAME, 0).getLong("last_check_ms", 0L)
        if (ms > 0L) _state.update { it.copy(lastChecked = ms) }
    }

    private fun cleanupOldOtas() {
        val activeOta = app.getSharedPreferences(OtaConfig.PREFS_NAME, 0).getString("active_ota_file", null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                app.cacheDir.listFiles { _, name -> name.endsWith(".zip") && name != "local_update.zip" }
                    ?.forEach { it.delete() }
                app.getExternalFilesDir(null)
                    ?.listFiles { _, name -> name.endsWith(".zip") && name != activeOta }
                    ?.forEach { it.delete() }
            }
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private fun buildChangelog(lines: List<String>): CharSequence =
        if (lines.isEmpty()) {
            S(R.string.changelog_empty)
        } else {
            Html.fromHtml(lines.joinToString("<br>") { "&#8226; $it" }, Html.FROM_HTML_MODE_COMPACT)
        }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "-"
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun postNotification(id: Int, title: String, text: String, ongoing: Boolean, progress: Int) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel("ota_updates", S(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
        val builder = NotificationCompat.Builder(app, "ota_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (progress >= 0) builder.setProgress(100, progress, false)
        builder.setContentText(text)
        nm.notify(id, builder.build())
    }
}
