package com.Zerodactyl.bloomina.ui

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.Zerodactyl.bloomina.BackgroundUpdateCheck.UpdateScheduler
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.OtaConfig
import org.json.JSONObject

/**
 * bloomina internal settings, using standard AndroidX preferences.
 *   • "Custom JSON URL"      → standard EditTextPreference dialog
 *   • "Reset Configurations" → clears every SharedPreference flag
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = OtaConfig.PREFS_NAME
        setPreferencesFromResource(R.xml.prefs, rootKey)

        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importSettings(it) }
        }

        (findPreference<EditTextPreference>("json_url"))?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            if (text.isNullOrBlank()) text = OtaConfig.defaultJsonUrl
        }

        findPreference<Preference>("reset")?.setOnPreferenceClickListener {
            requireContext().getSharedPreferences(OtaConfig.PREFS_NAME, 0).edit().clear().apply()
            // Re-seed the default URL so the app stays usable after a reset.
            requireContext().getSharedPreferences(OtaConfig.PREFS_NAME, 0).edit()
                .putString("json_url", OtaConfig.defaultJsonUrl).apply()
            findPreference<EditTextPreference>("json_url")?.text = OtaConfig.defaultJsonUrl
            true
        }

        findPreference<Preference>("update_history")?.setOnPreferenceClickListener {
            showUpdateHistory()
            true
        }

        findPreference<Preference>("install_log")?.setOnPreferenceClickListener {
            showInstallLog()
            true
        }

        findPreference<Preference>("export_settings")?.setOnPreferenceClickListener {
            exportSettings()
            true
        }

        findPreference<Preference>("import_settings")?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/json"))
            true
        }

        findPreference<androidx.preference.SwitchPreferenceCompat>("auto_check")
            ?.setOnPreferenceChangeListener { _, _ ->
                if (UpdateScheduler.isEnabled(requireContext())) {
                    UpdateScheduler.schedule(requireContext())
                } else {
                    UpdateScheduler.cancel(requireContext())
                }
                true
            }

        findPreference<androidx.preference.ListPreference>("check_interval")
            ?.setOnPreferenceChangeListener { _, _ ->
                // Re-arm the alarm with the new cadence (only matters while enabled).
                UpdateScheduler.cancel(requireContext())
                UpdateScheduler.schedule(requireContext())
                true
            }
    }

    private fun showUpdateHistory() {
        val history = OtaConfig.getUpdateHistory(requireContext())
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_update_history)
        if (history.isEmpty()) {
            dialog.setMessage(R.string.update_history_empty)
        } else {
            val items = history.map { entry ->
                val date = if (entry.timestamp > 0) {
                    java.text.DateFormat.getDateInstance().format(java.util.Date(entry.timestamp))
                } else {
                    ""
                }
                "${entry.version}  ·  $date"
            }.toTypedArray()
            dialog.setItems(items) { _, _ -> }
        }
        dialog.setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showInstallLog() {
        val rec = OtaConfig.getInstallLog(requireContext())
        val msg = if (rec == null) {
            getString(R.string.install_log_empty)
        } else {
            val whenStr = if (rec.timestamp > 0) {
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(rec.timestamp))
            } else {
                ""
            }
            val outcome = if (rec.success) getString(R.string.install_log_success) else getString(R.string.install_log_failed)
            getString(R.string.install_log_line, whenStr, outcome, rec.detail)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.pref_install_log)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun exportSettings() {
        try {
            val prefs = requireContext().getSharedPreferences(OtaConfig.PREFS_NAME, 0)
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
            val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create file")
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(obj.toString(2).toByteArray())
            } ?: throw java.io.IOException("Could not open file")
            android.widget.Toast.makeText(requireContext(), R.string.settings_exported, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_export_failed, e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSettings(uri: Uri) {
        try {
            val json = requireContext().contentResolver.openInputStream(uri)
                ?.use { it.bufferedReader().readText() }
                ?: throw java.io.IOException("Could not read file")
            val obj = JSONObject(json)
            val edit = requireContext().getSharedPreferences(OtaConfig.PREFS_NAME, 0).edit()
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
            UpdateScheduler.cancel(requireContext())
            if (UpdateScheduler.isEnabled(requireContext())) UpdateScheduler.schedule(requireContext())
            findPreference<EditTextPreference>("json_url")?.text =
                requireContext().getSharedPreferences(OtaConfig.PREFS_NAME, 0)
                    .getString("json_url", OtaConfig.defaultJsonUrl)
            android.widget.Toast.makeText(requireContext(), R.string.settings_imported, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.settings_import_failed, e.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
