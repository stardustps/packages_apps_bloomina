package com.Zerodactyl.bloomina.ui

import android.app.Application
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Zerodactyl.bloomina.DownloadService
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.UpdateWidgetProvider
import com.Zerodactyl.bloomina.data.Download
import com.Zerodactyl.bloomina.data.DownloadBus
import com.Zerodactyl.bloomina.data.OtaConfig
import com.Zerodactyl.bloomina.data.UpdateManifest
import com.Zerodactyl.bloomina.data.UpdateRepository
import com.Zerodactyl.bloomina.ota.DeviceInfo
import com.Zerodactyl.bloomina.ota.InstallResult
import com.Zerodactyl.bloomina.ota.OtaInstaller
import com.Zerodactyl.bloomina.ota.VersionCheck
import kotlinx.coroutines.Dispatchers
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
 * [CheckUpdateScreen] is a thin view that renders [CheckUiState] and reacts to [CheckEvent]s.
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
        val fingerprint: String
    )

    enum class DownloadButtonState { HIDDEN, DOWNLOAD, RETRY, INSTALL, REBOOT, PAUSED }

    enum class ChangelogMode { DIFF, FULL }

    enum class ChangelogKind { NEW, REMOVED, SAME }

    data class ChangelogEntry(val kind: ChangelogKind, val text: String)

    private data class ChangelogState(
        val full: CharSequence,
        val diff: List<ChangelogEntry>,
        val newCount: Int,
        val removedCount: Int,
        val mode: ChangelogMode
    )

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
        val securityUpdate: Boolean = false,
        val snoozed: Boolean = false,
        val button: DownloadButtonState = DownloadButtonState.HIDDEN,
        val integrity: String? = null,
        val lastChecked: Long = 0L,
        val error: Boolean = false,
        val hasIncremental: Boolean = false,
        val useIncremental: Boolean = false,
        val changelogFull: CharSequence = "",
        val changelogLines: List<String> = emptyList(),
        val changelogDiff: List<ChangelogEntry> = emptyList(),
        val changelogNew: Int = 0,
        val changelogRemoved: Int = 0,
        val changelogView: ChangelogMode = ChangelogMode.FULL
    )

    sealed interface CheckEvent {
        data class Toast(val message: String) : CheckEvent
        data object ConfirmMeteredDownload : CheckEvent
        data class ConfirmBatteryInstall(val file: File) : CheckEvent
        data class ConfirmDowngradeInstall(val file: File) : CheckEvent
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
    private var downLastMs = 0L
    private var downLastBytes = 0L

    init {
        viewModelScope.launch {
            DownloadBus.snapshot.collect { snap -> onServiceSnapshot(snap) }
        }
    }

    private val app: Application get() = getApplication()
    private fun S(resId: Int, vararg fmt: Any): String = app.getString(resId, *fmt)

    // ---- Public API --------------------------------------------------------

    /** Prep work done on view attach: cache cleanup, local device rows, cached manifest. */
    fun initialize() {
        cleanupOldOtas()
        loadLocalDeviceInfo()
        loadCached()
        refreshLastChecked()
        applyPendingInstallState()
        UpdateWidgetProvider.notifyUpdate(app)
    }

    fun check() {
        OtaConfig.clearSnooze(app)
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
                    val baseline = app.getSharedPreferences(OtaConfig.PREFS_NAME, 0)
                        .getString("cached_changelog", "")
                        ?.lineSequence()?.map { it.trim() }?.filter { it.isNotBlank() }
                        ?.toList() ?: emptyList()
                    val changelogState = buildChangelog(r.changelog, baseline)
                    val verdict = withContext(Dispatchers.IO) { VersionCheck.evaluate(r) }
                    val securityUpdate = runCatching {
                        (r.securityPatch.ifBlank { "0" }) > (DeviceInfo.securityPatch.ifBlank { "0" })
                    }.getOrDefault(false)
                    _state.update {
                        it.copy(
                            remote = RemoteReleaseView(
                                version = r.version,
                                buildDate = r.buildDate,
                                size = formatBytes(chosen.sizeBytes),
                                androidVersion = r.androidVersion,
                                securityPatch = r.securityPatch,
                                fingerprint = r.fingerprint
                            ),
                            installed = it.installed.copy(installed = verdict.installed),
                            hasIncremental = useInc,
                            useIncremental = useInc,
                            securityUpdate = securityUpdate,
                            changelogLines = r.changelog,
                            changelogFull = changelogState.full,
                            changelogDiff = changelogState.diff,
                            changelogNew = changelogState.newCount,
                            changelogRemoved = changelogState.removedCount,
                            changelogView = changelogState.mode
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
                                securityUpdate = securityUpdate,
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
                    applyPendingInstallState()
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
        UpdateWidgetProvider.notifyUpdate(app)
    }

    /** The download the user will actually fetch: incremental delta when selected, else full. */
    private fun activeDownload(): Download? {
        val release = manifest?.release ?: return null
        return if (_state.value.useIncremental) release.incrementalDownload ?: release.download
        else release.download
    }

    /** Primary action button (download / retry / install / reboot / resume) dispatcher. */
    fun onPrimaryButtonClicked() {
        when (_state.value.button) {
            DownloadButtonState.DOWNLOAD, DownloadButtonState.RETRY ->
                activeDownload()?.let { beginDownload(it) }
            DownloadButtonState.INSTALL -> pendingInstallFile?.let { install(it) }
            DownloadButtonState.REBOOT -> _events.trySend(CheckEvent.ShowRebootSheet)
            DownloadButtonState.PAUSED -> activeDownload()?.let { beginDownload(it) }
            DownloadButtonState.HIDDEN -> Unit
        }
    }

    fun confirmMeteredDownload() {
        activeDownload()?.let { startDownloadService(it) }
    }

    /** Hide the "update available" prompt until the next check cadence passes. */
    fun snooze() {
        OtaConfig.setSnoozed(app)
        _state.update {
            it.copy(
                snoozed = true,
                showReleaseSections = false,
                downloadVisible = false,
                button = DownloadButtonState.HIDDEN,
                heroIcon = R.drawable.ic_status_available,
                heroTitle = S(R.string.status_snoozed),
                heroSubtitle = S(R.string.status_snoozed_sub)
            )
        }
    }

    fun pauseDownload() {
        if (_state.value.downloadVisible.not()) return
        app.startService(Intent(app, DownloadService::class.java).apply { action = DownloadService.ACTION_PAUSE })
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

    fun install(file: File, forceBattery: Boolean = false, forceDowngrade: Boolean = false) {
        if (!forceBattery && !isBatteryOk()) {
            _events.trySend(CheckEvent.ConfirmBatteryInstall(file))
            return
        }
        if (!forceDowngrade && isDowngrade()) {
            _events.trySend(CheckEvent.ConfirmDowngradeInstall(file))
            return
        }
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
                    OtaConfig.clearActiveDownload(app)
                    persistPendingVersion()
                    OtaConfig.recordAppliedUpdate(app, manifest?.release?.version ?: "")
                    OtaConfig.recordInstallLog(app, true, manifest?.release?.version ?: "update")
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
                    OtaConfig.clearActiveDownload(app)
                    persistPendingVersion()
                    OtaConfig.recordAppliedUpdate(app, manifest?.release?.version ?: "")
                    OtaConfig.recordInstallLog(app, true, manifest?.release?.version ?: "update")
                    deleteLocalZipIfNeeded(file)
                    _events.trySend(CheckEvent.ShowRebootSheet)
                }
                is InstallResult.Failed -> {
                    OtaConfig.recordInstallLog(app, false, result.why)
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

    /** True when the battery is healthy enough to install (or the device is charging). */
    private fun isBatteryOk(): Boolean {
        val batteryStatus: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (scale > 0) level * 100 / scale.toFloat() else -1f
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return !(batteryPct in 0f..20f && !isCharging)
    }

    /** True when the incoming build is an older version than what's installed. */
    private fun isDowngrade(): Boolean {
        val incoming = manifest?.release?.versionCode
        val current = DeviceInfo.romVersionCode
        return incoming != null && current != null && current > 0 && incoming < current
    }

    /** True when the device is currently on a charger. */
    private fun isCharging(): Boolean {
        val status: Int = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private enum class DownloadPolicy { ASK, WIFI_ONLY, WIFI_CHARGING }

    private fun currentDownloadPolicy(): DownloadPolicy {
        val p = app.getSharedPreferences(OtaConfig.PREFS_NAME, 0).getString("download_policy", "ask") ?: "ask"
        return when (p) {
            "wifi" -> DownloadPolicy.WIFI_ONLY
            "wifi_charge" -> DownloadPolicy.WIFI_CHARGING
            else -> DownloadPolicy.ASK
        }
    }

    fun exportCurrentRelease() {
        val dl = activeDownload() ?: return
        exportUpdate(dl)
    }

    // ---- Download (delegated to DownloadService) ---------------------------

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
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        when (currentDownloadPolicy()) {
            DownloadPolicy.ASK -> if (!unmetered) {
                _events.trySend(CheckEvent.ConfirmMeteredDownload)
                return
            }
            DownloadPolicy.WIFI_ONLY -> if (!unmetered) {
                _events.trySend(CheckEvent.Toast(S(R.string.policy_blocked_wifi)))
                return
            }
            DownloadPolicy.WIFI_CHARGING -> if (!unmetered || !isCharging()) {
                _events.trySend(CheckEvent.Toast(S(R.string.policy_blocked_wifi_charging)))
                return
            }
        }
        startDownloadService(dl)
    }

    private fun startDownloadService(dl: Download) {
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
        val dest = File(extDir, dl.filename)
        OtaConfig.setActiveDownload(app, dest, dl.sha256, dl.installType)
        downLastMs = System.currentTimeMillis()
        downLastBytes = 0L
        _state.update {
            it.copy(
                downloadVisible = true,
                downloadIndeterminate = true,
                button = DownloadButtonState.HIDDEN,
                heroIcon = R.drawable.ic_status_available,
                heroTitle = S(R.string.status_downloading),
                heroSubtitle = S(R.string.status_preparing)
            )
        }
        val intent = Intent(app, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_DOWNLOAD, dl)
            putExtra(DownloadService.EXTRA_DEST, dest.absolutePath)
        }
        ContextCompat.startForegroundService(app, intent)
    }

    /** Reacts to progress/completion posted by [DownloadService] via [DownloadBus]. */
    private fun onServiceSnapshot(snap: DownloadBus.Snapshot?) {
        if (snap == null) return
        when (snap.status) {
            DownloadBus.Status.DOWNLOADING -> {
                val pct = if (snap.total > 0) ((snap.bytes * 100) / snap.total).toInt() else 0
                val now = System.currentTimeMillis()
                val dt = (now - downLastMs) / 1000.0
                val speed = if (dt > 0) (snap.bytes - downLastBytes) / dt else 0.0
                downLastMs = now
                downLastBytes = snap.bytes
                val speedStr = if (speed > 0) formatBytes(speed.toLong()) + "/s" else "—"
                val etaStr = if (speed > 0 && snap.total > 0) formatEta(((snap.total - snap.bytes) / speed).toLong()) else "—"
                val sub = if (snap.retrying) S(R.string.status_retrying) else S(R.string.download_speed, pct, speedStr, etaStr)
                _state.update {
                    it.copy(
                        downloadVisible = true,
                        downloadIndeterminate = false,
                        downloadProgress = pct,
                        button = DownloadButtonState.HIDDEN,
                        heroIcon = R.drawable.ic_status_available,
                        heroTitle = S(R.string.status_downloading),
                        heroSubtitle = sub
                    )
                }
            }
            DownloadBus.Status.DONE -> {
                val file = snap.file?.let { f -> File(f) } ?: return
                pendingInstallFile = file
                val sha = OtaConfig.getActiveDownload(app)?.sha256 ?: ""
                _state.update {
                    it.copy(
                        downloadVisible = false,
                        integrity = S(R.string.integrity_verified, sha),
                        button = DownloadButtonState.INSTALL,
                        heroIcon = R.drawable.ic_status_available,
                        heroTitle = S(R.string.status_download_done),
                        heroSubtitle = S(R.string.status_download_done_sub)
                    )
                }
                // Set-and-forget: if the user opted in and we're charging, install now.
                if (OtaConfig.isAutoInstallEnabled(app) && isCharging()) {
                    install(file, forceBattery = true)
                }
            }
            DownloadBus.Status.FAILED -> {
                _state.update {
                    it.copy(
                        downloadVisible = false,
                        heroIcon = R.drawable.ic_status_error,
                        heroTitle = S(R.string.status_failed),
                        heroSubtitle = snap.error ?: S(R.string.unknown_error),
                        button = DownloadButtonState.RETRY
                    )
                }
            }
            DownloadBus.Status.PAUSED -> {
                _state.update {
                    it.copy(
                        downloadVisible = false,
                        heroIcon = R.drawable.ic_status_available,
                        heroTitle = S(R.string.status_paused),
                        heroSubtitle = S(R.string.status_paused_sub),
                        button = DownloadButtonState.PAUSED
                    )
                }
            }
        }
    }

    /** If a completed download is already on disk (e.g. finished while the app was closed),
     *  surface it as ready-to-install instead of re-offering a download. Returns true if applied. */
    private fun applyPendingInstallState(): Boolean {
        val active = OtaConfig.getActiveDownload(app) ?: return false
        if (!active.done || !active.file.exists()) return false
        pendingInstallFile = active.file
        _state.update {
            it.copy(
                downloadVisible = false,
                integrity = S(R.string.integrity_verified, active.sha256),
                button = DownloadButtonState.INSTALL,
                heroIcon = R.drawable.ic_status_available,
                heroTitle = S(R.string.status_download_done),
                heroSubtitle = S(R.string.status_download_done_sub),
                showReleaseSections = true,
                updateAvailable = true
            )
        }
        if (OtaConfig.isAutoInstallEnabled(app) && isCharging()) {
            install(active.file, forceBattery = true)
        }
        return true
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
        val cachedLines = prefs.getString("cached_changelog", "")?.lineSequence()
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.toList() ?: emptyList()
        val changelogState = buildChangelog(cachedLines, cachedLines)
        val inc = restoreDownload(prefs, "cached_inc_")
        incrementalDownload = inc
        val useInc = inc != null
        val chosenSize = if (useInc) inc!!.sizeBytes else size
        val available = prefs.getBoolean("cached_available", false)
        val cachedSec = prefs.getString("cached_security", "") ?: ""
        val securityUpdate = available && runCatching {
            cachedSec.ifBlank { "0" } > DeviceInfo.securityPatch.ifBlank { "0" }
        }.getOrDefault(false)
        val snoozed = available && OtaConfig.isSnoozed(app)
        _state.update {
            it.copy(
                remote = RemoteReleaseView(
                    version = version,
                    buildDate = prefs.getString("cached_build_date", "-") ?: "-",
                    size = formatBytes(chosenSize),
                    androidVersion = prefs.getString("cached_android", "-") ?: "-",
                    securityPatch = prefs.getString("cached_security", "-") ?: "-",
                    fingerprint = prefs.getString("cached_fingerprint", "-") ?: "-"
                ),
                showReleaseSections = available && !snoozed,
                heroIcon = if (available) R.drawable.ic_status_available else R.drawable.ic_status_uptodate,
                heroTitle = if (snoozed) S(R.string.status_snoozed)
                else if (available) S(R.string.status_update_available) else S(R.string.status_up_to_date),
                heroSubtitle = if (snoozed) S(R.string.status_snoozed_sub)
                else if (available) S(R.string.status_update_available_sub, version) else S(R.string.cached_sub),
                button = if (snoozed || !available) DownloadButtonState.HIDDEN else DownloadButtonState.DOWNLOAD,
                securityUpdate = securityUpdate,
                snoozed = snoozed,
                hasIncremental = useInc,
                useIncremental = useInc,
                changelogLines = cachedLines,
                changelogFull = changelogState.full,
                changelogDiff = changelogState.diff,
                changelogNew = changelogState.newCount,
                changelogRemoved = changelogState.removedCount,
                changelogView = changelogState.mode
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
        val activeOta = OtaConfig.getActiveDownload(app)?.file?.name
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

    /**
     * Builds the changelog presentation. [incoming] is the new build's notes; [baseline] is the
     * last-known (installed/previous) build's notes. When they differ we default to a color-coded
     * Diff view (NEW / REMOVED / SAME); otherwise a plain Full view.
     */
    private fun buildChangelog(incoming: List<String>, baseline: List<String>): ChangelogState {
        val full = if (incoming.isEmpty()) {
            S(R.string.changelog_empty)
        } else {
            Html.fromHtml(incoming.joinToString("<br>") { "&#8226; $it" }, Html.FROM_HTML_MODE_COMPACT)
        }
        if (incoming.isEmpty()) {
            return ChangelogState(full, emptyList(), 0, 0, ChangelogMode.FULL)
        }
        val baseSet = baseline.toSet()
        val incSet = incoming.toSet()
        val diff = ArrayList<ChangelogEntry>()
        incoming.forEach { line ->
            diff += ChangelogEntry(
                if (line in baseSet) ChangelogKind.SAME else ChangelogKind.NEW,
                line
            )
        }
        baseline.forEach { line ->
            if (line !in incSet) diff += ChangelogEntry(ChangelogKind.REMOVED, line)
        }
        val newCount = diff.count { it.kind == ChangelogKind.NEW }
        val removedCount = diff.count { it.kind == ChangelogKind.REMOVED }
        val mode = if (newCount + removedCount > 0) ChangelogMode.DIFF else ChangelogMode.FULL
        return ChangelogState(full, diff, newCount, removedCount, mode)
    }

    fun setChangelogView(mode: ChangelogMode) {
        if (_state.value.changelogView == mode) return
        _state.update { it.copy(changelogView = mode) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "-"
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return "—"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
