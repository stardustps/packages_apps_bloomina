package com.Zerodactyl.bloomina.ui

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.Zerodactyl.bloomina.R

/**
 * bloomina internal settings, using standard AndroidX preferences.
 *   • "Custom JSON URL"      → standard EditTextPreference dialog
 *   • "Reset Configurations" → clears every SharedPreference flag
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "bloomina"
        setPreferencesFromResource(R.xml.prefs, rootKey)

        (findPreference<EditTextPreference>("json_url"))?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            if (text.isNullOrBlank()) text = CheckUpdateFragment.DEFAULT_JSON_URL
        }

        findPreference<Preference>("reset")?.setOnPreferenceClickListener {
            requireContext().getSharedPreferences("bloomina", 0).edit().clear().apply()
            // Re-seed the default URL so the app stays usable after a reset.
            requireContext().getSharedPreferences("bloomina", 0).edit()
                .putString("json_url", CheckUpdateFragment.DEFAULT_JSON_URL).apply()
            findPreference<EditTextPreference>("json_url")?.text = CheckUpdateFragment.DEFAULT_JSON_URL
            true
        }
    }
}
