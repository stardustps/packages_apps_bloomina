package com.Zerodactyl.bloomina.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.Zerodactyl.bloomina.BackgroundUpdateCheck
import com.Zerodactyl.bloomina.data.OtaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(OtaConfig.PREFS_NAME, Context.MODE_PRIVATE) }
    val res = context.resources

    var jsonUrl by remember { mutableStateOf(prefs.getString("json_url", "") ?: "") }
    var autoCheck by remember { mutableStateOf(prefs.getBoolean("auto_check", true)) }
    var checkInterval by remember { mutableStateOf(prefs.getString("check_interval", "6") ?: "6") }
    var downloadPolicy by remember { mutableStateOf(prefs.getString("download_policy", "ask") ?: "ask") }
    var autoInstall by remember { mutableStateOf(prefs.getBoolean("auto_install", false)) }

    var showJsonDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    val intervalEntries = res.getStringArray(R.array.check_interval_entries).toList()
    val intervalValues = res.getStringArray(R.array.check_interval_values).toList()
    val policyEntries = res.getStringArray(R.array.download_policy_entries).toList()
    val policyValues = res.getStringArray(R.array.download_policy_values).toList()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                val err = importSettings(context, it)
                if (err == null) {
                    jsonUrl = prefs.getString("json_url", "") ?: ""
                    rearmScheduler(context)
                    Toast.makeText(context, context.getString(R.string.settings_imported), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.settings_import_failed, err), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val intervalLabel = intervalEntries.getOrElse(intervalValues.indexOf(checkInterval)) { checkInterval }
    val policyLabel = policyEntries.getOrElse(policyValues.indexOf(downloadPolicy)) { downloadPolicy }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsGroup("Update source") {
                ListItem(
                    headlineContent = { Text(jsonUrl.ifBlank { OtaConfig.defaultJsonUrl }) },
                    supportingContent = { Text("Remote manifest bloomina checks for new bloomina builds") },
                    leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                    modifier = Modifier.clickable { showJsonDialog = true },
                )
            }
        }

        item {
            SettingsGroup("Automatic checks") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_auto_check)) },
                    supportingContent = { Text(stringResource(R.string.pref_auto_check_summary)) },
                    trailingContent = {
                        Switch(checked = autoCheck, onCheckedChange = {
                            autoCheck = it
                            prefs.edit().putBoolean("auto_check", it).apply()
                            rearmScheduler(context)
                        })
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_check_interval)) },
                    supportingContent = { Text(intervalLabel) },
                    leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                    modifier = Modifier.clickable { showIntervalDialog = true },
                )
            }
        }

        item {
            SettingsGroup("Downloads") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_download_policy)) },
                    supportingContent = { Text(policyLabel) },
                    leadingContent = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                    modifier = Modifier.clickable { showPolicyDialog = true },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_auto_install)) },
                    supportingContent = { Text(stringResource(R.string.pref_auto_install_summary)) },
                    trailingContent = {
                        Switch(checked = autoInstall, onCheckedChange = {
                            autoInstall = it
                            prefs.edit().putBoolean("auto_install", it).apply()
                        })
                    },
                )
            }
        }

        item {
            SettingsGroup("Maintenance") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_update_history)) },
                    supportingContent = { Text(stringResource(R.string.pref_update_history_summary)) },
                    leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                    modifier = Modifier.clickable { showHistory = true },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_install_log)) },
                    supportingContent = { Text(stringResource(R.string.pref_install_log_summary)) },
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    modifier = Modifier.clickable { showLog = true },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_export_settings)) },
                    supportingContent = { Text(stringResource(R.string.pref_export_settings_summary)) },
                    leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            val err = exportSettings(context)
                            val msg = if (err == null) {
                                context.getString(R.string.settings_exported)
                            } else {
                                context.getString(R.string.settings_export_failed, err)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pref_import_settings)) },
                    supportingContent = { Text(stringResource(R.string.pref_import_settings_summary)) },
                    leadingContent = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                    modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json")) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Reset configurations") },
                    supportingContent = { Text("Clear all saved bloomina flags and restore defaults") },
                    leadingContent = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                    modifier = Modifier.clickable { showReset = true },
                )
            }
        }
    }

    if (showJsonDialog) {
        AlertDialog(
            onDismissRequest = { showJsonDialog = false },
            title = { Text("Update manifest URL") },
            text = {
                OutlinedTextField(
                    value = jsonUrl,
                    onValueChange = { jsonUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(OtaConfig.defaultJsonUrl) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("json_url", jsonUrl.trim()).apply()
                    showJsonDialog = false
                }) { Text(android.R.string.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showJsonDialog = false }) { Text(android.R.string.cancel) }
            },
        )
    }

    if (showIntervalDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.pref_check_interval),
            entries = intervalEntries,
            values = intervalValues,
            selected = checkInterval,
            onDismiss = { showIntervalDialog = false },
            onChoice = {
                checkInterval = it
                prefs.edit().putString("check_interval", it).apply()
                rearmScheduler(context)
                showIntervalDialog = false
            },
        )
    }

    if (showPolicyDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.pref_download_policy),
            entries = policyEntries,
            values = policyValues,
            selected = downloadPolicy,
            onDismiss = { showPolicyDialog = false },
            onChoice = {
                downloadPolicy = it
                prefs.edit().putString("download_policy", it).apply()
                showPolicyDialog = false
            },
        )
    }

    if (showHistory) {
        val history = OtaConfig.getUpdateHistory(context)
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text(stringResource(R.string.pref_update_history)) },
            text = {
                val items = if (history.isEmpty()) {
                    listOf(context.getString(R.string.update_history_empty))
                } else {
                    history.map { entry ->
                        val date = if (entry.timestamp > 0) {
                            DateFormat.getDateInstance().format(Date(entry.timestamp))
                        } else {
                            ""
                        }
                        "${entry.version}  ·  $date"
                    }
                }
                LazyColumn(Modifier.verticalScroll(rememberScrollState())) {
                    items(items) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text(android.R.string.ok) } },
        )
    }

    if (showLog) {
        val rec = OtaConfig.getInstallLog(context)
        val msg = if (rec == null) {
            context.getString(R.string.install_log_empty)
        } else {
            val whenStr = if (rec.timestamp > 0) {
                DateFormat.getDateTimeInstance().format(Date(rec.timestamp))
            } else {
                ""
            }
            val outcome = if (rec.success) {
                context.getString(R.string.install_log_success)
            } else {
                context.getString(R.string.install_log_failed)
            }
            context.getString(R.string.install_log_line, whenStr, outcome, rec.detail)
        }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.pref_install_log)) },
            text = {
                LazyColumn(Modifier.verticalScroll(rememberScrollState())) {
                    item { Text(msg, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text(android.R.string.ok) } },
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset configurations") },
            text = { Text("Clear all saved bloomina flags and restore defaults?") },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().clear().apply()
                    prefs.edit().putString("json_url", OtaConfig.defaultJsonUrl).apply()
                    jsonUrl = OtaConfig.defaultJsonUrl
                    autoCheck = prefs.getBoolean("auto_check", true)
                    checkInterval = prefs.getString("check_interval", "6") ?: "6"
                    downloadPolicy = prefs.getString("download_policy", "ask") ?: "ask"
                    autoInstall = prefs.getBoolean("auto_install", false)
                    rearmScheduler(context)
                    showReset = false
                }) { Text(android.R.string.ok) }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text(android.R.string.cancel) } },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    entries: List<String>,
    values: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onChoice: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(entries.size) { i ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onChoice(values[i]) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = values[i] == selected, onClick = null)
                        Spacer(Modifier.size(8.dp))
                        Text(entries[i])
                    }
                }
            }
        },
        confirmButton = {},
    )
}

private fun rearmScheduler(context: Context) {
    if (BackgroundUpdateCheck.UpdateScheduler.isEnabled(context)) {
        BackgroundUpdateCheck.UpdateScheduler.schedule(context)
    } else {
        BackgroundUpdateCheck.UpdateScheduler.cancel(context)
    }
}

private suspend fun exportSettings(context: Context): String? = withContext(Dispatchers.IO) {
    try {
        val prefs = context.getSharedPreferences(OtaConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject()
        prefs.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> obj.put(k, v)
                is Int -> obj.put(k, v)
                is Long -> obj.put(k, v)
                is Float -> obj.put(k, v)
                is String -> obj.put(k, v)
                else -> obj.put(k, v.toString())
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "bloomina_settings.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Could not create file")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(obj.toString(2).toByteArray())
        } ?: throw java.io.IOException("Could not open file")
        null
    } catch (e: Exception) {
        e.message ?: "error"
    }
}

private suspend fun importSettings(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val json = context.contentResolver.openInputStream(uri)
            ?.use { it.bufferedReader().readText() }
            ?: throw java.io.IOException("Could not read file")
        val obj = JSONObject(json)
        val edit = context.getSharedPreferences(OtaConfig.PREFS_NAME, Context.MODE_PRIVATE).edit()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = obj.get(k)) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is String -> edit.putString(k, v)
                else -> edit.putString(k, v.toString())
            }
        }
        edit.apply()
        null
    } catch (e: Exception) {
        e.message ?: "error"
    }
}
