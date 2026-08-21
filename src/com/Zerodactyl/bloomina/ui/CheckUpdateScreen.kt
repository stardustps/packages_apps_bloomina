package com.Zerodactyl.bloomina.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemProperties
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.LinkedHashMap

@Composable
fun CheckUpdateScreen() {
    val vm: CheckUpdateViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var confirmEvent by remember { mutableStateOf<CheckUpdateViewModel.CheckEvent?>(null) }
    var showRebootSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val localUpdateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let { vm.handleLocalUpdate(it) }
    }

    LaunchedEffect(Unit) { vm.initialize() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is CheckUpdateViewModel.CheckEvent.Toast ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is CheckUpdateViewModel.CheckEvent.ConfirmMeteredDownload -> confirmEvent = event
                is CheckUpdateViewModel.CheckEvent.ConfirmBatteryInstall -> confirmEvent = event
                is CheckUpdateViewModel.CheckEvent.ConfirmDowngradeInstall -> confirmEvent = event
                is CheckUpdateViewModel.CheckEvent.ShowRebootSheet -> showRebootSheet = true
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HeroCard(state) }

            item {
                ActionRow(
                    state = state,
                    onCheck = vm::check,
                    onPrimary = vm::onPrimaryButtonClicked,
                    onPause = vm::pauseDownload,
                    onSnooze = vm::snooze,
                    onExport = vm::exportCurrentRelease,
                )
            }

            if (state.downloadVisible) {
                item {
                    if (state.downloadIndeterminate) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { state.downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (state.showReleaseSections) {
                item { SectionHeader(stringResource(R.string.sep_available)) }
                item { ReleaseCard(state.remote) }

                item { SectionHeader(stringResource(R.string.sep_changelog)) }
                item {
                    ChangelogCard(
                        state = state,
                        query = query,
                        onQueryChange = { query = it },
                        onViewChange = vm::setChangelogView,
                        onUseIncrementalChange = vm::setUseIncremental,
                        onCopy = { text -> copyText(context, "Changelog", text) },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.diag_title)) }
            item {
                DiagnosticsCard(
                    state = state,
                    onCopy = { text -> copyText(context, "Device Info", text) },
                )
            }

            if (state.lastChecked > 0L) {
                item {
                    val rel = DateUtils.getRelativeTimeSpanString(
                        state.lastChecked, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    )
                    Text(
                        stringResource(R.string.last_checked, rel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { localUpdateLauncher.launch("*/*") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 88.dp),
            icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
            text = { Text(stringResource(R.string.local_update)) },
        )
    }

    confirmEvent?.let { event ->
        when (event) {
            is CheckUpdateViewModel.CheckEvent.ConfirmMeteredDownload -> {
                AlertDialog(
                    onDismissRequest = { confirmEvent = null },
                    title = { Text(stringResource(R.string.warn_metered_title)) },
                    text = { Text(stringResource(R.string.warn_metered_msg)) },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.confirmMeteredDownload()
                            confirmEvent = null
                        }) { Text(stringResource(R.string.btn_download)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmEvent = null }) { Text(android.R.string.cancel) }
                    },
                )
            }
            is CheckUpdateViewModel.CheckEvent.ConfirmBatteryInstall -> {
                AlertDialog(
                    onDismissRequest = { confirmEvent = null },
                    title = { Text(stringResource(R.string.warn_battery_title)) },
                    text = { Text(stringResource(R.string.warn_battery_msg)) },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.install(event.file, forceBattery = true)
                            confirmEvent = null
                        }) { Text(android.R.string.ok) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmEvent = null }) { Text(android.R.string.cancel) }
                    },
                )
            }
            is CheckUpdateViewModel.CheckEvent.ConfirmDowngradeInstall -> {
                AlertDialog(
                    onDismissRequest = { confirmEvent = null },
                    title = { Text(stringResource(R.string.warn_downgrade_title)) },
                    text = { Text(stringResource(R.string.warn_downgrade_msg)) },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.install(event.file, forceDowngrade = true)
                            confirmEvent = null
                        }) { Text(android.R.string.ok) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmEvent = null }) { Text(android.R.string.cancel) }
                    },
                )
            }
            else -> { /* Toast / ShowRebootSheet handled in the collect block */ }
        }
    }

    if (showRebootSheet) {
        ModalBottomSheet(onDismissRequest = { showRebootSheet = false }, sheetState = sheetState) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.sheet_complete_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.sheet_complete_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(24.dp))
                Button(
                    onClick = {
                        showRebootSheet = false
                        rebootDevice(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sheet_reboot_btn)) }
            }
        }
    }
}

@Composable
private fun HeroCard(state: CheckUpdateViewModel.CheckUiState) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(state.heroIcon),
                contentDescription = stringResource(R.string.desc_hero_icon),
                modifier = Modifier.size(48.dp),
                tint = scheme.onPrimaryContainer,
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.heroTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onPrimaryContainer,
                )
                if (state.heroSubtitle.isNotBlank()) {
                    Text(
                        state.heroSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                if (state.updateAvailable || state.securityUpdate) {
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.updateAvailable) {
                            SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.badge_new)) })
                        }
                        if (state.securityUpdate) {
                            SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.badge_security)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    state: CheckUpdateViewModel.CheckUiState,
    onCheck: () -> Unit,
    onPrimary: () -> Unit,
    onPause: () -> Unit,
    onSnooze: () -> Unit,
    onExport: () -> Unit,
) {
    val buttonVisible = state.button != CheckUpdateViewModel.DownloadButtonState.HIDDEN
    val pauseVisible = state.downloadVisible &&
        state.button == CheckUpdateViewModel.DownloadButtonState.HIDDEN
    val snoozeVisible = state.updateAvailable && !state.snoozed && !state.downloadVisible
    val exportVisible = state.updateAvailable

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.FilledTonalButton(onClick = onCheck, enabled = !state.checking) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.btn_check))
        }

        if (buttonVisible) {
            val (label, icon) = when (state.button) {
                CheckUpdateViewModel.DownloadButtonState.DOWNLOAD ->
                    stringResource(R.string.btn_download) to Icons.Outlined.FileDownload
                CheckUpdateViewModel.DownloadButtonState.RETRY ->
                    stringResource(R.string.btn_retry) to Icons.Outlined.Refresh
                CheckUpdateViewModel.DownloadButtonState.INSTALL ->
                    stringResource(R.string.btn_install) to Icons.Outlined.SystemUpdate
                CheckUpdateViewModel.DownloadButtonState.REBOOT ->
                    stringResource(R.string.btn_reboot) to Icons.Outlined.RestartAlt
                CheckUpdateViewModel.DownloadButtonState.PAUSED ->
                    stringResource(R.string.btn_resume) to Icons.Outlined.PlayArrow
                else -> "" to Icons.Outlined.FileDownload
            }
            if (label.isNotEmpty()) {
                Button(onClick = onPrimary) {
                    Icon(icon, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(label)
                }
            }
        }

        if (pauseVisible) {
            TextButton(onClick = onPause) {
                Icon(Icons.Outlined.Pause, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.btn_pause))
            }
        }

        if (snoozeVisible) {
            TextButton(onClick = onSnooze) {
                Icon(Icons.Outlined.Snooze, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.btn_snooze))
            }
        }

        if (exportVisible) {
            androidx.compose.material3.FilledTonalButton(onClick = onExport) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.export_button))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ReleaseCard(remote: CheckUpdateViewModel.RemoteReleaseView?) {
    if (remote == null) return
    Card(Modifier.fillMaxWidth()) {
        Column {
            ChangelogField(stringResource(R.string.f_version), remote.version)
            ChangelogField(stringResource(R.string.f_build_date), remote.buildDate)
            ChangelogField(stringResource(R.string.f_size), remote.size)
            ChangelogField(stringResource(R.string.f_android), remote.androidVersion)
            ChangelogField(stringResource(R.string.f_patch), remote.securityPatch)
            ChangelogField(stringResource(R.string.f_fingerprint), remote.fingerprint, last = true)
        }
    }
}

@Composable
private fun ChangelogField(label: String, value: String, last: Boolean = false) {
    ListItem(headlineContent = { Text(value) }, supportingContent = { Text(label) })
    if (!last) HorizontalDivider()
}

@Composable
private fun ChangelogCard(
    state: CheckUpdateViewModel.CheckUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onViewChange: (CheckUpdateViewModel.ChangelogMode) -> Unit,
    onUseIncrementalChange: (Boolean) -> Unit,
    onCopy: (CharSequence) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasContent = state.changelogLines.isNotEmpty() || state.changelogDiff.isNotEmpty()
    val canDiff = state.changelogDiff.any { it.kind != CheckUpdateViewModel.ChangelogKind.SAME }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (state.hasIncremental) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !state.useIncremental,
                        onClick = { onUseIncrementalChange(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.label_full)) }
                    SegmentedButton(
                        selected = state.useIncremental,
                        onClick = { onUseIncrementalChange(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.label_incremental)) }
                }
                Spacer(Modifier.size(12.dp))
            }

            if (hasContent) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.changelog_search_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { onCopy(changelogPlain(state, query)) }) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.btn_copy_changelog),
                            )
                        }
                    },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
            }

            if (canDiff) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.changelogView == CheckUpdateViewModel.ChangelogMode.DIFF,
                        onClick = { onViewChange(CheckUpdateViewModel.ChangelogMode.DIFF) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.label_diff)) }
                    SegmentedButton(
                        selected = state.changelogView == CheckUpdateViewModel.ChangelogMode.FULL,
                        onClick = { onViewChange(CheckUpdateViewModel.ChangelogMode.FULL) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.label_full)) }
                }
                Spacer(Modifier.size(8.dp))
            }

            val summary = if (state.changelogView == CheckUpdateViewModel.ChangelogMode.DIFF && canDiff) {
                if (state.changelogNew > 0 || state.changelogRemoved > 0) {
                    stringResource(R.string.changelog_summary, state.changelogNew, state.changelogRemoved)
                } else {
                    stringResource(R.string.changelog_summary_none)
                }
            } else if (query.isBlank()) {
                if (state.changelogNew > 0 || state.changelogRemoved > 0) {
                    stringResource(R.string.changelog_summary, state.changelogNew, state.changelogRemoved)
                } else {
                    stringResource(R.string.changelog_summary_none)
                }
            } else {
                val lines = state.changelogLines.filter { it.contains(query, ignoreCase = true) }
                stringResource(R.string.changelog_count, lines.size)
            }

            if (hasContent) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                val annotated = if (state.changelogView == CheckUpdateViewModel.ChangelogMode.DIFF && canDiff) {
                    buildDiffAnnotated(state.changelogDiff, query, scheme.primary, scheme.error, scheme.onSurfaceVariant)
                } else {
                    buildGroupedAnnotated(state.changelogLines, query, scheme.primary, scheme.onSurface)
                }
                SelectionContainer {
                    Text(
                        annotated,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.changelog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    state: CheckUpdateViewModel.CheckUiState,
    onCopy: (CharSequence) -> Unit,
) {
    val isAb = remember { isAbDevice() }
    val slot = remember { activeSlotSuffix() }
    val local = state.installed

    Card(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(if (isAb) "A/B (Seamless)" else "A-Only (Recovery)") },
                supportingContent = { Text(stringResource(R.string.diag_layout)) },
            )
            HorizontalDivider()
            if (isAb && slot.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text(slot.replace("_", "").uppercase()) },
                    supportingContent = { Text(stringResource(R.string.diag_slot)) },
                    modifier = Modifier.clickable { onCopy(slot.replace("_", "").uppercase()) },
                )
                HorizontalDivider()
            }
            CopyableRow(stringResource(R.string.f_installed), local.installed, onCopy)
            CopyableRow(stringResource(R.string.f_model), local.model, onCopy)
            CopyableRow(stringResource(R.string.f_android), local.android, onCopy)
            CopyableRow(stringResource(R.string.f_patch), local.patch, onCopy)
            CopyableRow(stringResource(R.string.f_fingerprint), local.fingerprint, onCopy)
            CopyableRow(stringResource(R.string.f_kernel), local.kernel, onCopy)
            if (state.integrity != null) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(state.integrity!!) },
                    supportingContent = { Text("Integrity") },
                )
            }
        }
    }
}

@Composable
private fun CopyableRow(label: String, value: String, onCopy: (CharSequence) -> Unit) {
    ListItem(
        headlineContent = { Text(value) },
        supportingContent = { Text(label) },
        modifier = Modifier.clickable { onCopy(value) },
    )
    HorizontalDivider()
}

private fun copyText(context: Context, label: String, text: CharSequence) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

private fun rebootDevice(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.reboot(null)
}

private fun isAbDevice(): Boolean =
    runCatching { SystemProperties.getBoolean("ro.build.ab_update", false) }.getOrDefault(false)

private fun activeSlotSuffix(): String =
    runCatching { SystemProperties.get("ro.boot.slot_suffix", "") }.getOrDefault("")

private fun changelogPlain(state: CheckUpdateViewModel.CheckUiState, query: String): String {
    val canDiff = state.changelogDiff.any { it.kind != CheckUpdateViewModel.ChangelogKind.SAME }
    return if (state.changelogView == CheckUpdateViewModel.ChangelogMode.DIFF && canDiff) {
        val entries = if (query.isBlank()) {
            state.changelogDiff
        } else {
            state.changelogDiff.filter { it.text.contains(query, ignoreCase = true) }
        }
        entries.joinToString("\n") { "• " + it.text }
    } else {
        val lines = if (query.isBlank()) {
            state.changelogLines
        } else {
            state.changelogLines.filter { it.contains(query, ignoreCase = true) }
        }
        lines.joinToString("\n")
    }
}

private fun buildDiffAnnotated(
    entries: List<CheckUpdateViewModel.ChangelogEntry>,
    query: String,
    newColor: Color,
    removedColor: Color,
    sameColor: Color,
): AnnotatedString = buildAnnotatedString {
    val filtered = if (query.isBlank()) {
        entries
    } else {
        entries.filter { it.text.contains(query, ignoreCase = true) }
    }
    filtered.forEachIndexed { index, entry ->
        val color = when (entry.kind) {
            CheckUpdateViewModel.ChangelogKind.NEW -> newColor
            CheckUpdateViewModel.ChangelogKind.REMOVED -> removedColor
            else -> sameColor
        }
        append("• ")
        val start = this.length
        append(entry.text)
        addStyle(SpanStyle(color = color), start, this.length)
        if (entry.kind == CheckUpdateViewModel.ChangelogKind.REMOVED) {
            addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, this.length)
        }
        if (index != filtered.lastIndex) append("\n")
    }
}

private val CHANGELOG_GROUPS = listOf(
    "Features" to Regex("^(feat|feature|add|new|\\+)\\b[\\s:\\-]*", RegexOption.IGNORE_CASE),
    "Fixes" to Regex("^(fix|bugfix|bug|\\-)\\b[\\s:\\-]*", RegexOption.IGNORE_CASE),
    "Security" to Regex("^(sec|security|cve)\\b[\\s:\\-]*", RegexOption.IGNORE_CASE),
)

private fun buildGroupedAnnotated(
    lines: List<String>,
    query: String,
    headerColor: Color,
    bodyColor: Color,
): AnnotatedString = buildAnnotatedString {
    val filtered = if (query.isBlank()) lines else lines.filter { it.contains(query, ignoreCase = true) }
    val groups = LinkedHashMap<String, MutableList<String>>()
    val other = mutableListOf<String>()
    for (line in filtered) {
        val match = CHANGELOG_GROUPS.firstOrNull { (_, re) -> re.containsMatchIn(line) }
        if (match != null) {
            val (title, re) = match
            val cleaned = line.replaceFirst(re, "").trim().ifBlank { line }
            groups.getOrPut(title) { mutableListOf() }.add(cleaned)
        } else {
            other.add(line)
        }
    }
    if (other.isNotEmpty()) groups.getOrPut("Other") { mutableListOf() }.addAll(other)

    var first = true
    for ((title, items) in groups) {
        if (!first) append("\n")
        first = false
        val hs = this.length
        append(title.uppercase())
        addStyle(SpanStyle(color = headerColor, fontWeight = FontWeight.Bold), hs, this.length)
        append("\n")
        items.forEachIndexed { i, it ->
            val bs = this.length
            append("• ")
            append(it)
            addStyle(SpanStyle(color = bodyColor), bs, this.length)
            if (i != items.lastIndex) append("\n")
        }
        append("\n")
    }
}
