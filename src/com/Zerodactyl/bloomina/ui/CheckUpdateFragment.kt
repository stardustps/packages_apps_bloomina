package com.Zerodactyl.bloomina.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.Download
import com.Zerodactyl.bloomina.data.DownloadState
import com.Zerodactyl.bloomina.data.UpdateManifest
import com.Zerodactyl.bloomina.data.UpdateRepository
import com.Zerodactyl.bloomina.ota.DeviceInfo
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.Zerodactyl.bloomina.ota.InstallResult
import com.Zerodactyl.bloomina.ota.OtaInstaller
import com.Zerodactyl.bloomina.ota.VersionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import android.os.Environment
import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemProperties
import android.text.Html
import android.text.method.LinkMovementMethod
import android.text.format.DateUtils
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.content.ClipboardManager
import android.content.ClipData
import java.io.FileInputStream
import java.io.FileOutputStream

class CheckUpdateFragment : Fragment() {

    private var _b: V? = null
    private val b get() = _b!!
    private val repo = UpdateRepository()
    private var manifest: UpdateManifest? = null
    private var pendingInstallFile: File? = null
    private val localUpdateLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) handleLocalUpdate(uri)
    }

    /** Falls back to the default when the stored value is missing OR blank - a user who
     *  cleared the Settings field used to leave an empty string here, which OkHttp rejects. */
    private val jsonUrl: String
        get() = requireContext()
            .getSharedPreferences("bloomina", 0)
            .getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_JSON_URL

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val root = i.inflate(R.layout.fragment_check_update, c, false)
        _b = V(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cleanupOldOtas()
        showLastChecked()
        renderCached()
        renderLocalDeviceRows()
        setupTapToCopy()
        renderDiagnostics()
        b.fabLocalUpdate.setOnClickListener { localUpdateLauncher.launch("*/*") }
        b.btnCheck.setOnClickListener { check() }
        b.btnExport.setOnClickListener { manifest?.let { exportUpdate(it.release.download) } }
        b.btnCopyChangelog.setOnClickListener { copyChangelog() }
        pendingInstallFile?.let { file ->
            if (file.exists()) {
                setInstallButton(b, file)
            } else {
                pendingInstallFile = null
                b.btnDownload.setOnClickListener { manifest?.let { downloadAndInstall(it.release.download) } }
            }
        } ?: run {
            b.btnDownload.setOnClickListener { manifest?.let { downloadAndInstall(it.release.download) } }
        }
        check()
    }

    /**
     * Device rows come from `getprop` and /proc/version - process forks and a file read.
     * They ran on the main thread before, which stuttered the first frame of the tab.
     */
    private fun renderLocalDeviceRows() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                LocalInfo(
                    installed = DeviceInfo.romVersion.ifBlank { "${DeviceInfo.PROP_ROM_VER} unset" },
                    model = DeviceInfo.model,
                    android = DeviceInfo.androidVersion,
                    patch = DeviceInfo.securityPatch,
                    fingerprint = DeviceInfo.fingerprint,
                    kernel = DeviceInfo.kernelVersion
                )
            }
            val v = _b ?: return@launch
            v.rowInstalledVersion.text = info.installed
            v.rowDeviceModel.text = info.model
            v.rowAndroid.text = info.android
            v.rowSecurity.text = info.patch
            v.rowFingerprint.text = info.fingerprint
            v.rowKernel.text = info.kernel
        }
    }

    private fun check() {
        setHero(R.drawable.ic_cloud_large, getString(R.string.status_checking), getString(R.string.status_checking_sub))
        b.downloadBar.isIndeterminate = true
        b.downloadBar.visibility = View.VISIBLE
        b.btnCheck.isEnabled = false

        // viewLifecycleOwner, not lifecycleScope: a plain lifecycleScope job outlives
        // onDestroyView, so switching tabs mid-check resumed into `b` after it was nulled
        // and crashed with an NPE. The view scope cancels at onDestroyView instead.
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repo.fetchManifest(jsonUrl)
            val v = _b ?: return@launch

            result
                .onSuccess { m ->
                    manifest = m
                    val r = m.release
                    v.rowRemoteVersion.text = r.version
                    v.rowBuildDate.text = r.buildDate
                    v.rowDownloadSize.text = formatBytes(r.download.sizeBytes)
                    v.rowRemoteAndroid.text = r.androidVersion
                    v.rowRemoteSecurity.text = r.securityPatch
                    v.rowRemoteFingerprint.text = r.fingerprint
                    val changelogText = r.changelog.joinToString("<br>") { "&#8226; $it" }
                    v.changelog.text = if (r.changelog.isEmpty()) {
                        getString(R.string.changelog_empty)
                    } else {
                        Html.fromHtml(changelogText, Html.FROM_HTML_MODE_COMPACT)
                    }
                    v.changelog.movementMethod = LinkMovementMethod.getInstance()

                    val verdict = withContext(Dispatchers.IO) { VersionCheck.evaluate(r) }
                    v.rowInstalledVersion.text = verdict.installed

                    if (verdict.updateAvailable) {
                        setHero(
                            R.drawable.ic_status_available,
                            getString(R.string.status_update_available),
                            getString(R.string.status_update_available_sub, r.version)
                        )
                        showReleaseSections(true)
                        v.btnDownload.visibility = View.VISIBLE
                        v.btnDownload.isEnabled = true
                        v.btnExport.visibility = View.VISIBLE
                    } else {
                        setHero(
                            R.drawable.ic_status_uptodate,
                            getString(R.string.status_up_to_date),
                            getString(R.string.status_up_to_date_sub, verdict.installed)
                        )
                        // 8.5 keeps the up-to-date screen short: no changelog for a build
                        // you already have.
                        showReleaseSections(false)
                        v.btnDownload.visibility = View.GONE
                        v.btnDownload.isEnabled = false
                        v.btnExport.visibility = View.GONE
                    }
                    persistManifest(m, verdict)
                }
                .onFailure { t ->
                    manifest = null
                    val hasCache = requireContext().getSharedPreferences("bloomina", 0)
                        .getString("cached_version", "")?.isNotBlank() == true
                    if (hasCache) {
                        renderCached()
                    } else {
                        setHero(
                            R.drawable.ic_status_error,
                            getString(R.string.status_failed),
                            UpdateRepository.describe(t)
                        )
                        showReleaseSections(false)
                        v.btnDownload.visibility = View.GONE
                        v.btnDownload.isEnabled = false
                        v.btnExport.visibility = View.GONE
                    }
                }
        persistLastChecked()

            v.downloadBar.visibility = View.GONE
            v.btnCheck.isEnabled = true
        }
    }

    private fun setHero(iconRes: Int, title: String, subtitle: String) {
        val v = _b ?: return
        v.heroIcon.setImageResource(iconRes)
        v.heroTitle.text = title
        v.heroSubtitle.text = subtitle
    }

    private fun showReleaseSections(visible: Boolean) {
        val v = _b ?: return
        val vis = if (visible) View.VISIBLE else View.GONE
        v.sepAvailable.visibility = vis
        v.cardAvailable.visibility = vis
        v.sepChangelog.visibility = vis
        v.cardChangelog.visibility = vis
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "-"
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun downloadAndInstall(dl: Download) {
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        if (caps == null) {
            setHero(R.drawable.ic_status_error, getString(R.string.status_failed), getString(R.string.error_no_network))
            return
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.warn_metered_title))
                .setMessage(getString(R.string.warn_metered_msg))
                .setPositiveButton(getString(R.string.btn_download)) { _, _ -> startDownload(dl) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        startDownload(dl)
    }

    private fun startDownload(dl: Download) {
        val extDir = requireContext().getExternalFilesDir(null) ?: run {
            setHero(R.drawable.ic_status_error, getString(R.string.status_failed), "External storage unavailable")
            return
        }
        requireContext().getSharedPreferences("bloomina", 0)
            .edit().putString("active_ota_file", dl.filename).apply()
        val dest = File(extDir, dl.filename)
        b.btnDownload.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val nm = requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel("ota_updates", getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            val builder = NotificationCompat.Builder(requireContext(), "ota_updates")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.notif_downloading_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            var lastMs = System.currentTimeMillis()
            var lastBytes = 0L
            repo.download(dl, dest).collect { st ->
                val v = _b ?: return@collect
                when (st) {
                    is DownloadState.Progress -> {
                        val pct = (st.fraction * 100).toInt()
                        v.downloadBar.isIndeterminate = false
                        v.downloadBar.visibility = View.VISIBLE
                        v.downloadBar.progress = pct
                        val now = System.currentTimeMillis()
                        val dt = (now - lastMs) / 1000.0
                        val bytesNow = st.bytes
                        val speed = if (dt > 0) (bytesNow - lastBytes) / dt else 0.0
                        lastMs = now
                        lastBytes = bytesNow
                        val speedStr = if (speed > 0) formatBytes(speed.toLong()) + "/s" else "—"
                        setHero(R.drawable.ic_status_available, getString(R.string.status_downloading), getString(R.string.download_speed, pct, speedStr))

                        builder.setProgress(100, pct, false)
                        builder.setContentText("$pct%")
                        nm.notify(1, builder.build())
                    }
                    is DownloadState.Failed -> {
                        v.downloadBar.visibility = View.GONE
                        setHero(R.drawable.ic_status_error, getString(R.string.status_failed), st.reason)
                        v.btnDownload.isEnabled = true
                        v.btnDownload.text = getString(R.string.btn_retry)

                        builder.setContentTitle(getString(R.string.notif_download_failed)).setContentText(st.reason).setProgress(0, 0, false).setOngoing(false)
                        nm.notify(1, builder.build())
                    }
                    is DownloadState.Done -> {
                        v.downloadBar.visibility = View.GONE
                        builder.setContentTitle(getString(R.string.notif_download_complete)).setContentText(getString(R.string.notif_ready_to_install)).setProgress(0, 0, false).setOngoing(false)
                        nm.notify(1, builder.build())

                        v.txtIntegrity.text = getString(R.string.integrity_verified, dl.sha256)
                        v.txtIntegrity.visibility = View.VISIBLE

                        val batteryStatus: Intent? = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                        val batteryPct = if (scale > 0) level * 100 / scale.toFloat() else -1f
                        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                        if (batteryPct in 0f..20f && !isCharging) {
                            AlertDialog.Builder(requireContext())
                                .setTitle(getString(R.string.warn_battery_title))
                                .setMessage(getString(R.string.warn_battery_msg))
                                .setPositiveButton("OK", null)
                                .show()
                            setInstallButton(v, st.file)
                        } else {
                            install(st.file)
                        }
                    }
                }
            }
        }
    }

    private fun setInstallButton(v: V, file: File) {
        pendingInstallFile = file
        v.btnDownload.text = getString(R.string.btn_install)
        v.btnDownload.isEnabled = true
        v.btnDownload.setOnClickListener { v.btnDownload.isEnabled = false; install(file) }
    }

    private fun setRebootButton(v: V) {
        v.btnDownload.text = getString(R.string.btn_reboot)
        v.btnDownload.isEnabled = true
        v.btnDownload.setOnClickListener { showRebootBottomSheet() }
    }

    private fun install(pkg: File) {
        val dl = manifest?.release?.download ?: return
        // A system/privileged updater applies packages via framework APIs (UpdateEngine for
        // A/B, RecoverySystem for A-Only) — no root/dd required.
        setHero(R.drawable.ic_status_available, getString(R.string.status_installing), "")

        viewLifecycleOwner.lifecycleScope.launch {
            // RecoverySystem.verifyPackage re-hashes the whole zip; run off the Main thread
            // so a multi-GB ROM doesn't ANR the UI.
            val result = withContext(Dispatchers.IO) {
                val installer = OtaInstaller(requireContext().applicationContext)
                installer.installPackage(pkg)
            }
            val v = _b ?: return@launch
            when (result) {
                is InstallResult.StagedRebootingToRecovery -> {
                    setHero(R.drawable.ic_status_available, getString(R.string.install_staged_title), getString(R.string.install_staged_sub))
                    requireContext().getSharedPreferences("bloomina", 0).edit().putString("pending_update_version", manifest?.release?.version ?: "").apply()
                    // Clean up local update zip after successful staging
                    if (pkg.name == "local_update.zip") {
                        runCatching { pkg.delete() }
                    }
                }
                is InstallResult.AppliedBackgroundRebootRequired -> {
                    setHero(R.drawable.ic_status_available, getString(R.string.install_applied_title), getString(R.string.install_applied_sub))
                    setRebootButton(v)
                    v.btnExport.visibility = View.VISIBLE
                    showRebootBottomSheet()
                    requireContext().getSharedPreferences("bloomina", 0).edit().putString("pending_update_version", manifest?.release?.version ?: "").apply()
                    // Clean up local update zip after successful apply
                    if (pkg.name == "local_update.zip") {
                        runCatching { pkg.delete() }
                    }
                }
                is InstallResult.Failed -> {
                    setHero(R.drawable.ic_status_error, getString(R.string.install_failed_title), result.why)
                    v.btnDownload.text = getString(R.string.btn_download)
                    v.btnDownload.isEnabled = true
                    v.btnDownload.setOnClickListener { manifest?.release?.download?.let { d -> downloadAndInstall(d) } }
                    v.btnExport.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private data class LocalInfo(
        val installed: String,
        val model: String,
        val android: String,
        val patch: String,
        val fingerprint: String,
        val kernel: String
    )

    /** Manual view holder - avoids the ViewBinding generator, which is not enabled in this AOSP build. */
    private class V(root: View) {
        val heroIcon: ImageView = root.findViewById(R.id.heroIcon)
        val heroTitle: TextView = root.findViewById(R.id.heroTitle)
        val heroSubtitle: TextView = root.findViewById(R.id.heroSubtitle)
        val downloadBar: ProgressBar = root.findViewById(R.id.downloadBar)
        val btnExport: Button = root.findViewById(R.id.btnExport)
        val btnDownload: Button = root.findViewById(R.id.btnDownload)
        val btnCheck: Button = root.findViewById(R.id.btnCheck)
        val sepAvailable: TextView = root.findViewById(R.id.sepAvailable)
        val cardAvailable: MaterialCardView = root.findViewById(R.id.cardAvailable)
        val rowRemoteVersion: TextView = root.findViewById(R.id.rowRemoteVersion)
        val rowBuildDate: TextView = root.findViewById(R.id.rowBuildDate)
        val rowDownloadSize: TextView = root.findViewById(R.id.rowDownloadSize)
        val rowRemoteAndroid: TextView = root.findViewById(R.id.rowRemoteAndroid)
        val rowRemoteSecurity: TextView = root.findViewById(R.id.rowRemoteSecurity)
        val rowRemoteFingerprint: TextView = root.findViewById(R.id.rowRemoteFingerprint)
        val changelog: TextView = root.findViewById(R.id.changelog)
        val sepChangelog: TextView = root.findViewById(R.id.sepChangelog)
        val cardChangelog: MaterialCardView = root.findViewById(R.id.cardChangelog)
        val rowLayoutType: TextView = root.findViewById(R.id.rowLayoutType)
        val lblActiveSlot: TextView = root.findViewById(R.id.lblActiveSlot)
        val rowActiveSlot: TextView = root.findViewById(R.id.rowActiveSlot)
        val rowInstalledVersion: TextView = root.findViewById(R.id.rowInstalledVersion)
        val rowDeviceModel: TextView = root.findViewById(R.id.rowDeviceModel)
        val rowAndroid: TextView = root.findViewById(R.id.rowAndroid)
        val rowSecurity: TextView = root.findViewById(R.id.rowSecurity)
        val rowFingerprint: TextView = root.findViewById(R.id.rowFingerprint)
        val rowKernel: TextView = root.findViewById(R.id.rowKernel)
        val fabLocalUpdate: FloatingActionButton = root.findViewById(R.id.fabLocalUpdate)
        val txtIntegrity: TextView = root.findViewById(R.id.txtIntegrity)
        val txtLastChecked: TextView = root.findViewById(R.id.txtLastChecked)
        val btnCopyChangelog: Button = root.findViewById(R.id.btnCopyChangelog)
    }

    companion object {
        /**
         * Default manifest location. The device codename is auto-detected from
         * `ro.product.vendor.device` (falling back to Build.DEVICE), e.g. .../16.2/a32.json
         */
        private const val OTA_BASE = "https://over-the-air.tuong.qzz.io/bloomina"
        val DEFAULT_JSON_URL: String get() = "$OTA_BASE/${DeviceInfo.romName}/${DeviceInfo.deviceCodename}.json"
    }

    private fun exportUpdate(dl: Download) {
        val src = File(requireContext().getExternalFilesDir(null) ?: return, dl.filename)
        if (!src.exists()) {
            Toast.makeText(context, getString(R.string.export_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resolver = requireContext().contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, dl.filename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Failed to create MediaStore entry")
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(src).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Failed to open output stream")
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, getString(R.string.export_success), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, getString(R.string.export_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderDiagnostics() {
        val isAB = SystemProperties.getBoolean("ro.build.ab_update", false)
        val slot = SystemProperties.get("ro.boot.slot_suffix", "")
        b.rowLayoutType.text = if (isAB) "A/B (Seamless)" else "A-Only (Recovery)"
        if (isAB && slot.isNotEmpty()) {
            b.rowActiveSlot.text = slot.replace("_", "").uppercase()
            b.lblActiveSlot.visibility = View.VISIBLE
            b.rowActiveSlot.visibility = View.VISIBLE
        } else {
            b.lblActiveSlot.visibility = View.GONE
            b.rowActiveSlot.visibility = View.GONE
        }
    }

    private fun handleLocalUpdate(uri: Uri) {
        setHero(R.drawable.ic_cloud_large, getString(R.string.local_staging), getString(R.string.local_copying))
        b.downloadBar.isIndeterminate = true
        b.downloadBar.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dest = File(requireContext().cacheDir, "local_update.zip")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    b.downloadBar.visibility = View.GONE
                    install(dest)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setHero(R.drawable.ic_status_error, getString(R.string.local_failed_title), e.message ?: getString(R.string.local_failed_msg))
                    b.downloadBar.visibility = View.GONE
                }
            }
        }
    }

    private fun cleanupOldOtas() {
        val isDownloading = _b?.downloadBar?.visibility == View.VISIBLE
        val activeOta = requireContext().getSharedPreferences("bloomina", 0).getString("active_ota_file", null)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Clean cache dir (Local Updates staged zips) but keep the in-flight sideload.
                requireContext().cacheDir.listFiles { _, name -> name.endsWith(".zip") && name != "local_update.zip" }?.forEach { it.delete() }
                // Clean external files dir (Downloaded OTAs) but never the active download.
                val extDir = requireContext().getExternalFilesDir(null)
                extDir?.listFiles { _, name -> name.endsWith(".zip") && name != activeOta }?.forEach { file ->
                    if (file.exists() && !isDownloading) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    private fun showRebootBottomSheet() {
        val sheet = BottomSheetDialog(requireContext())
        
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val title = TextView(requireContext()).apply {
            text = getString(R.string.sheet_complete_title)
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        
        val desc = TextView(requireContext()).apply {
            text = getString(R.string.sheet_complete_desc)
            textSize = 16f
            setPadding(0, 0, 0, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val btnReboot = Button(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = getString(R.string.sheet_reboot_btn)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                sheet.dismiss()
                val pm = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                pm.reboot(null)
            }
        }
        
        layout.addView(title)
        layout.addView(desc)
        layout.addView(btnReboot)
        
        sheet.setContentView(layout)
        sheet.setCancelable(false)
        sheet.show()
    }

    private fun setupTapToCopy() {
        val copyAction = { text: CharSequence ->
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Device Info", text))
            Toast.makeText(context, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
        
        b.rowInstalledVersion.setOnClickListener { copyAction(b.rowInstalledVersion.text) }
        b.rowDeviceModel.setOnClickListener { copyAction(b.rowDeviceModel.text) }
        b.rowAndroid.setOnClickListener { copyAction(b.rowAndroid.text) }
        b.rowSecurity.setOnClickListener { copyAction(b.rowSecurity.text) }
        b.rowFingerprint.setOnClickListener { copyAction(b.rowFingerprint.text) }
        b.rowKernel.setOnClickListener { copyAction(b.rowKernel.text) }
    }
    private fun persistLastChecked() {
        requireContext().getSharedPreferences("bloomina", 0)
            .edit().putLong("last_check_ms", System.currentTimeMillis()).apply()
        showLastChecked()
    }

    private fun showLastChecked() {
        val v = _b ?: return
        val ms = requireContext().getSharedPreferences("bloomina", 0).getLong("last_check_ms", 0L)
        if (ms <= 0L) {
            v.txtLastChecked.visibility = View.GONE
            return
        }
        v.txtLastChecked.visibility = View.VISIBLE
        val rel = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        v.txtLastChecked.text = getString(R.string.last_checked, rel)
    }

    private fun copyChangelog() {
        val text = _b?.changelog?.text ?: return
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Changelog", text))
        Toast.makeText(context, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun persistManifest(m: UpdateManifest, verdict: VersionCheck.Result) {
        val prefs = requireContext().getSharedPreferences("bloomina", 0).edit()
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
        prefs.apply()
    }

    private fun renderCached() {
        val v = _b ?: return
        val prefs = requireContext().getSharedPreferences("bloomina", 0)
        val version = prefs.getString("cached_version", "") ?: ""
        if (version.isBlank()) {
            showReleaseSections(false)
            return
        }
        val size = prefs.getString("cached_size", "0")?.toLongOrNull() ?: 0L
        v.rowRemoteVersion.text = version
        v.rowBuildDate.text = prefs.getString("cached_build_date", "-") ?: "-"
        v.rowDownloadSize.text = formatBytes(size)
        v.rowRemoteAndroid.text = prefs.getString("cached_android", "-") ?: "-"
        v.rowRemoteSecurity.text = prefs.getString("cached_security", "-") ?: "-"
        v.rowRemoteFingerprint.text = prefs.getString("cached_fingerprint", "-") ?: "-"
        val changelog = prefs.getString("cached_changelog", "") ?: ""
        v.changelog.text = if (changelog.isBlank()) {
            getString(R.string.changelog_empty)
        } else {
            Html.fromHtml(changelog.split("\n").joinToString("<br>") { "&#8226; $it" }, Html.FROM_HTML_MODE_COMPACT)
        }
        v.changelog.movementMethod = LinkMovementMethod.getInstance()
        val available = prefs.getBoolean("cached_available", false)
        showReleaseSections(available)
        if (available) {
            setHero(R.drawable.ic_status_available, getString(R.string.status_update_available), getString(R.string.status_update_available_sub, version))
        } else {
            setHero(R.drawable.ic_status_uptodate, getString(R.string.status_up_to_date), getString(R.string.cached_sub))
        }
    }
}
