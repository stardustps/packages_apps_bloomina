package com.Zerodactyl.bloomina.ui

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.OtaConfig

/**
 * bloomina internal settings, using standard AndroidX preferences.
 *   • "Custom JSON URL"      → standard EditTextPreference dialog
 *   • "Reset Configurations" → clears every SharedPreference flag
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = OtaConfig.PREFS_NAME
        setPreferencesFromResource(R.xml.prefs, rootKey)

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
}
