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
import com.Zerodactyl.bloomina.ota.IFlashCallback
import com.Zerodactyl.bloomina.ota.InstallResult
import com.Zerodactyl.bloomina.ota.OtaInstaller
import com.Zerodactyl.bloomina.ota.RootIpc
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
    private val rootIpc by lazy { RootIpc(requireContext().applicationContext) }
    private var manifest: UpdateManifest? = null
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
        renderLocalDeviceRows()
        setupTapToCopy()
        renderDiagnostics()
        b.fabLocalUpdate.setOnClickListener { localUpdateLauncher.launch("application/zip") }
        b.btnCheck.setOnClickListener { check() }
        b.btnDownload.setOnClickListener { manifest?.let { downloadAndInstall(it.release.download) } }
        b.btnExport.setOnClickListener { manifest?.let { exportUpdate(it.release.download) } }
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
                    v.changelog.text = Html.fromHtml(r.changelog.joinToString("<br>") { "&#8226; $it" }, Html.FROM_HTML_MODE_COMPACT)
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
                }
                .onFailure { t ->
                    manifest = null
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
        if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            AlertDialog.Builder(requireContext())
                .setTitle("Cellular Data Warning")
                .setMessage("You are on a metered network. This update may consume a large amount of data. Continue?")
                .setPositiveButton("Download") { _, _ -> startDownload(dl) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        startDownload(dl)
    }

    private fun startDownload(dl: Download) {
        val dest = File(requireContext().getExternalFilesDir(null), dl.filename)
        b.btnDownload.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val nm = requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel("ota_updates", "System Updates", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            val builder = NotificationCompat.Builder(requireContext(), "ota_updates")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Downloading System Update")
                .setOngoing(true)
                .setOnlyAlertOnce(true)

            repo.download(dl, dest).collect { st ->
                val v = _b ?: return@collect
                when (st) {
                    is DownloadState.Progress -> {
                        val pct = (st.fraction * 100).toInt()
                        v.downloadBar.isIndeterminate = false
                        v.downloadBar.visibility = View.VISIBLE
                        v.downloadBar.progress = pct
                        setHero(R.drawable.ic_status_available, getString(R.string.status_downloading), getString(R.string.status_downloading_sub, pct))
                        
                        builder.setProgress(100, pct, false)
                        builder.setContentText("$pct%")
                        nm.notify(1, builder.build())
                    }
                    is DownloadState.Failed -> {
                        v.downloadBar.visibility = View.GONE
                        setHero(R.drawable.ic_status_error, getString(R.string.status_failed), st.reason)
                        v.btnDownload.isEnabled = true
                        
                        builder.setContentTitle("Download Failed").setContentText(st.reason).setProgress(0, 0, false).setOngoing(false)
                        nm.notify(1, builder.build())
                    }
                    is DownloadState.Done -> {
                        v.downloadBar.visibility = View.GONE
                        builder.setContentTitle("Download Complete").setContentText("Ready to install").setProgress(0, 0, false).setOngoing(false)
                        nm.notify(1, builder.build())
                        
                        // Battery Safety Check
                        val batteryStatus: Intent? = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                        val batteryPct = level * 100 / scale.toFloat()
                        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                        
                        if (batteryPct < 20 && !isCharging) {
                            AlertDialog.Builder(requireContext())
                                .setTitle("Battery Too Low")
                                .setMessage("Your battery is below 20%. Please plug in your device to safely install this system update.")
                                .setPositiveButton("OK", null)
                                .show()
                            v.btnDownload.text = "Install Now"
                            v.btnDownload.isEnabled = true
                            v.btnDownload.setOnClickListener { install(st.file) }
                        } else {
                            install(st.file)
                        }
                    }
                }
            }
        }
    }

    private fun install(pkg: File) {
        val dl = manifest?.release?.download ?: return
        if (dl.installType.equals("raw_image", ignoreCase = true)) {
            confirmRawFlash(pkg, dl)   // dangerous path -> explicit confirmation first
            return
        }
        setHero(R.drawable.ic_status_available, getString(R.string.status_installing), "")

        viewLifecycleOwner.lifecycleScope.launch {
            // RecoverySystem.verifyPackage re-hashes the whole zip and RootManager.exec blocks
            // on a shell round-trip. On a multi-GB ROM that is an ANR if it runs on Main.
            val result = withContext(Dispatchers.IO) {
                val installer = OtaInstaller(requireContext().applicationContext)
                installer.installPackage(pkg)
            }
            val v = _b ?: return@launch
            when (result) {
                is InstallResult.StagedRebootingToRecovery ->
                    setHero(R.drawable.ic_status_available, "Staged", "Rebooting to recovery to apply…")
                is InstallResult.AppliedBackgroundRebootRequired -> {
                    setHero(R.drawable.ic_status_available, "Installed", "Update applied successfully. Please reboot.")
                    showRebootBottomSheet()
                    v.btnDownload.isEnabled = true
                    v.btnExport.visibility = View.VISIBLE
                }
                is InstallResult.Failed -> {
                    setHero(R.drawable.ic_status_error, "Install failed", result.why)
                    v.btnDownload.isEnabled = true
                    v.btnExport.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Scary, unambiguous confirmation before any direct-to-partition write on an A-only device. */
    private fun confirmRawFlash(pkg: File, dl: Download) {
        val target = "system"   // for bloomina full images; a boot/recovery image would pass its own name
        AlertDialog.Builder(requireContext())
            .setTitle("Flash directly to /$target?")
            .setMessage(
                "This writes the image straight to the $target partition.\n\n" +
                "The Galaxy A32 is A-only — there is no backup slot. If the write is " +
                "interrupted or the image is wrong, the device may not boot and will need " +
                "recovery/Odin to restore.\n\nOnly continue if you understand the risk."
            )
            .setPositiveButton("I understand, flash") { _, _ -> startRawFlash(pkg, target, dl.sizeBytes) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRawFlash(pkg: File, partition: String, totalBytes: Long) {
        val progressView = layoutInflater.inflate(R.layout.dialog_flash_progress, null)
        // AlertDialog inflates against its own themed context, so the activity's font factory
        // does not reach this view tree - apply the family by hand.
        val bar = progressView.findViewById<android.widget.ProgressBar>(R.id.flashBar)
        val label = progressView.findViewById<android.widget.TextView>(R.id.flashLabel)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Flashing $partition")
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            if (!rootIpc.connect()) {
                dialog.dismiss()
                setHero(
                    R.drawable.ic_status_error,
                    getString(R.string.status_failed),
                    "Root worker unavailable — is the bloomina module installed?"
                )
                return@launch
            }
            val cb = object : IFlashCallback.Stub() {
                override fun onProgress(percent: Int, line: String?) {
                    this@CheckUpdateFragment.view?.post {
                        if (percent >= 0) { bar.isIndeterminate = false; bar.progress = percent }
                        label.text = if (percent >= 0) "$percent%  ·  ${line.orEmpty()}" else line.orEmpty()
                    }
                }
                override fun onDone(success: Boolean, message: String?) {
                    // post-to-view instead of requireActivity().runOnUiThread: the callback can
                    // land after the fragment is detached, and requireActivity() throws then.
                    this@CheckUpdateFragment.view?.post {
                        dialog.dismiss()
                        if (success) {
                            setHero(R.drawable.ic_status_available, "Flashed", "Rebooting to recovery to finalize…")
                            rootIpc.worker?.rebootRecovery()
                        } else {
                            setHero(R.drawable.ic_status_error, "Flash failed", message.orEmpty())
                        }
                    }
                }
            }
            // Runs entirely in the root worker process; progress streams back via cb.
            withContext(Dispatchers.IO) {
                rootIpc.worker?.rawFlash(pkg.absolutePath, partition, totalBytes, cb)
            }
        }
    }

    override fun onDestroyView() {
        rootIpc.disconnect()
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
        val src = File(requireContext().getExternalFilesDir(null), dl.filename)
        if (!src.exists()) {
            Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = File(downloadsDir, dl.filename)
                
                FileInputStream(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Exported to Downloads folder", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
        setHero(R.drawable.ic_cloud_large, "Staging Local Update", "Copying zip file...")
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
                    setHero(R.drawable.ic_status_error, "Local Update Failed", e.message ?: "Could not read file")
                    b.downloadBar.visibility = View.GONE
                }
            }
        }
    }

    private fun cleanupOldOtas() {
        val isDownloading = _b?.downloadBar?.visibility == View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Clean cache dir (Local Updates)
                requireContext().cacheDir.listFiles { _, name -> name.endsWith(".zip") }?.forEach { it.delete() }
                // Clean external files dir (Downloaded OTAs)
                val extDir = requireContext().getExternalFilesDir(null)
                extDir?.listFiles { _, name -> name.endsWith(".zip") }?.forEach { file ->
                    // Only delete if it's not currently downloading
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
            text = "System Update Complete"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        
        val desc = TextView(requireContext()).apply {
            text = "The update has been successfully installed in the background. A restart is required to finish applying the changes."
            textSize = 16f
            setPadding(0, 0, 0, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val btnReboot = Button(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "Reboot Now"
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
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        
        b.rowInstalledVersion.setOnClickListener { copyAction(b.rowInstalledVersion.text) }
        b.rowDeviceModel.setOnClickListener { copyAction(b.rowDeviceModel.text) }
        b.rowAndroid.setOnClickListener { copyAction(b.rowAndroid.text) }
        b.rowSecurity.setOnClickListener { copyAction(b.rowSecurity.text) }
        b.rowFingerprint.setOnClickListener { copyAction(b.rowFingerprint.text) }
        b.rowKernel.setOnClickListener { copyAction(b.rowKernel.text) }
    }
}
