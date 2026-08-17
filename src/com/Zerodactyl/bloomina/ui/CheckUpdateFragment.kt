package com.Zerodactyl.bloomina.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemProperties
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikeThruSpan
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.OtaConfig
import kotlinx.coroutines.launch
import java.io.File
import android.net.Uri
import android.text.format.DateUtils

class CheckUpdateFragment : Fragment() {

    private var _b: V? = null
    private val b get() = _b!!

    private val vm: CheckUpdateViewModel by viewModels()

    private val localUpdateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) vm.handleLocalUpdate(uri) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val root = i.inflate(R.layout.fragment_check_update, c, false)
        _b = V(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.fabLocalUpdate.setOnClickListener { localUpdateLauncher.launch("*/*") }
        b.btnCheck.setOnClickListener { vm.check() }
        b.btnExport.setOnClickListener { vm.exportCurrentRelease() }
        b.btnCopyChangelog.setOnClickListener { copyChangelog() }
        b.btnDownload.setOnClickListener { vm.onPrimaryButtonClicked() }
        b.downloadTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) vm.setUseIncremental(checkedId == R.id.chipIncremental)
        }
        b.changelogToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) vm.setChangelogView(
                if (checkedId == R.id.chipChangelogDiff) CheckUpdateViewModel.ChangelogMode.DIFF
                else CheckUpdateViewModel.ChangelogMode.FULL
            )
        }

        setupTapToCopy()
        renderDiagnostics()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.uiState.collect { render(it) } }
                launch { vm.events.collect { handleEvent(it) } }
            }
        }

        vm.initialize()
        vm.check()
    }

    private fun render(state: CheckUpdateViewModel.CheckUiState) {
        val v = _b ?: return

        v.heroIcon.setImageResource(state.heroIcon)
        v.heroTitle.text = state.heroTitle
        v.heroSubtitle.text = state.heroSubtitle

        v.downloadBar.visibility = if (state.downloadVisible) View.VISIBLE else View.GONE
        v.downloadBar.isIndeterminate = state.downloadIndeterminate
        if (!state.downloadIndeterminate && state.downloadVisible) {
            v.downloadBar.progress = state.downloadProgress
        }

        v.btnCheck.isEnabled = !state.checking

        val btnVisible = state.button != CheckUpdateViewModel.DownloadButtonState.HIDDEN
        v.btnDownload.visibility = if (btnVisible) View.VISIBLE else View.GONE
        v.btnDownload.isEnabled = btnVisible
        v.btnDownload.text = when (state.button) {
            CheckUpdateViewModel.DownloadButtonState.DOWNLOAD -> getString(R.string.btn_download)
            CheckUpdateViewModel.DownloadButtonState.RETRY -> getString(R.string.btn_retry)
            CheckUpdateViewModel.DownloadButtonState.INSTALL -> getString(R.string.btn_install)
            CheckUpdateViewModel.DownloadButtonState.REBOOT -> getString(R.string.btn_reboot)
            CheckUpdateViewModel.DownloadButtonState.HIDDEN -> ""
        }
        v.btnExport.visibility = if (state.updateAvailable) View.VISIBLE else View.GONE

        v.downloadTypeToggle.visibility = if (state.hasIncremental) View.VISIBLE else View.GONE
        if (state.hasIncremental) {
            v.downloadTypeToggle.check(if (state.useIncremental) R.id.chipIncremental else R.id.chipFull)
        }

        val remote = state.remote
        if (remote != null) {
            v.rowRemoteVersion.text = remote.version
            v.rowBuildDate.text = remote.buildDate
            v.rowDownloadSize.text = remote.size
            v.rowRemoteAndroid.text = remote.androidVersion
            v.rowRemoteSecurity.text = remote.securityPatch
            v.rowRemoteFingerprint.text = remote.fingerprint
        }

        renderChangelog(state)

        val vis = if (state.showReleaseSections) View.VISIBLE else View.GONE
        v.sepAvailable.visibility = vis
        v.cardAvailable.visibility = vis
        v.sepChangelog.visibility = vis
        v.cardChangelog.visibility = vis

        if (state.integrity != null) {
            v.txtIntegrity.text = state.integrity
            v.txtIntegrity.visibility = View.VISIBLE
        } else {
            v.txtIntegrity.visibility = View.GONE
        }

        val local = state.installed
        v.rowInstalledVersion.text = local.installed
        v.rowDeviceModel.text = local.model
        v.rowAndroid.text = local.android
        v.rowSecurity.text = local.patch
        v.rowFingerprint.text = local.fingerprint
        v.rowKernel.text = local.kernel

        if (state.lastChecked > 0L) {
            v.txtLastChecked.visibility = View.VISIBLE
            val rel = DateUtils.getRelativeTimeSpanString(
                state.lastChecked, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            v.txtLastChecked.text = getString(R.string.last_checked, rel)
        } else {
            v.txtLastChecked.visibility = View.GONE
        }
    }

    private fun renderChangelog(state: CheckUpdateViewModel.CheckUiState) {
        val v = _b ?: return

        val hasContent = state.changelogFull.isNotEmpty() || state.changelogDiff.isNotEmpty()
        if (!hasContent) {
            v.changelog.text = getString(R.string.changelog_empty)
            v.changelogSummary.visibility = View.GONE
            v.changelogToggle.visibility = View.GONE
            return
        }

        // Summary line
        if (state.changelogNew > 0 || state.changelogRemoved > 0) {
            v.changelogSummary.visibility = View.VISIBLE
            v.changelogSummary.text = getString(R.string.changelog_summary, state.changelogNew, state.changelogRemoved)
        } else {
            v.changelogSummary.visibility = View.VISIBLE
            v.changelogSummary.text = getString(R.string.changelog_summary_none)
        }

        // Diff / Full toggle (hidden when there's nothing to diff against)
        val canDiff = state.changelogDiff.any { it.kind != CheckUpdateViewModel.ChangelogKind.SAME }
        v.changelogToggle.visibility = if (canDiff) View.VISIBLE else View.GONE
        if (canDiff) {
            v.changelogToggle.check(
                if (state.changelogView == CheckUpdateViewModel.ChangelogMode.DIFF) R.id.chipChangelogDiff
                else R.id.chipChangelogFull
            )
        }

        v.changelog.text = if (state.changelogView == CheckUpdateViewModel.ChangelogMode.DIFF && canDiff) {
            buildDiffText(state)
        } else {
            state.changelogFull
        }
        v.changelog.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun buildDiffText(state: CheckUpdateViewModel.CheckUiState): CharSequence {
        val ctx = requireContext()
        val newColor = MaterialColors.getColor(ctx, R.attr.colorPrimary, 0xFF00BFA5.toInt())
        val removedColor = MaterialColors.getColor(ctx, R.attr.colorError, 0xFFC62828.toInt())
        val sameColor = MaterialColors.getColor(ctx, R.attr.colorOnSurfaceVariant, 0xFF666666.toInt())

        val ssb = SpannableStringBuilder()
        state.changelogDiff.forEachIndexed { index, entry ->
            val bullet = "• "
            val color = when (entry.kind) {
                CheckUpdateViewModel.ChangelogKind.NEW -> newColor
                CheckUpdateViewModel.ChangelogKind.REMOVED -> removedColor
                CheckUpdateViewModel.ChangelogKind.SAME -> sameColor
            }
            val start = ssb.length
            ssb.append(bullet).append(entry.text)
            if (index != state.changelogDiff.lastIndex) ssb.append("\n")
            val end = ssb.length
            ssb.setSpan(
                ForegroundColorSpan(color),
                start + bullet.length, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (entry.kind == CheckUpdateViewModel.ChangelogKind.REMOVED) {
                ssb.setSpan(
                    StrikeThruSpan(),
                    start + bullet.length, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return ssb
    }

    private fun handleEvent(event: CheckUpdateViewModel.CheckEvent) {
        when (event) {
            is CheckUpdateViewModel.CheckEvent.Toast ->
                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
            is CheckUpdateViewModel.CheckEvent.ConfirmMeteredDownload ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.warn_metered_title))
                    .setMessage(getString(R.string.warn_metered_msg))
                    .setPositiveButton(getString(R.string.btn_download)) { _, _ -> vm.confirmMeteredDownload() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            is CheckUpdateViewModel.CheckEvent.ConfirmBatteryInstall ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.warn_battery_title))
                    .setMessage(getString(R.string.warn_battery_msg))
                    .setPositiveButton(android.R.string.ok) { _, _ -> vm.install(event.file) }
                    .show()
            is CheckUpdateViewModel.CheckEvent.ShowRebootSheet -> showRebootBottomSheet()
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

    private fun copyChangelog() {
        val text = _b?.changelog?.text ?: return
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Changelog", text))
        Toast.makeText(context, getString(R.string.copied), Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

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
        val downloadTypeToggle: MaterialButtonToggleGroup = root.findViewById(R.id.downloadTypeToggle)
        val chipFull: MaterialButton = root.findViewById(R.id.chipFull)
        val chipIncremental: MaterialButton = root.findViewById(R.id.chipIncremental)
        val changelogSummary: TextView = root.findViewById(R.id.changelogSummary)
        val changelogToggle: MaterialButtonToggleGroup = root.findViewById(R.id.changelogToggle)
        val chipChangelogDiff: MaterialButton = root.findViewById(R.id.chipChangelogDiff)
        val chipChangelogFull: MaterialButton = root.findViewById(R.id.chipChangelogFull)
    }
}
