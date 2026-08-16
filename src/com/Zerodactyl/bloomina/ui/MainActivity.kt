package com.Zerodactyl.bloomina.ui

import android.os.Bundle
import com.google.android.material.color.DynamicColors
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.Zerodactyl.bloomina.R

/**
 * Standard Material Design shell:
 *   - MaterialToolbar gives the collapsing title.
 *   - BottomNavigationView is the standard bottom navigation bar.
 */
class MainActivity : AppCompatActivity() {


    private val updateFragment by lazy { CheckUpdateFragment() }
    private val maintainerFragment by lazy { MaintainerFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_main)

        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomTab).setOnItemSelectedListener { item ->
            when (item.getItemId()) {
                R.id.tab_update -> show(updateFragment, R.string.tab_check_update)
                R.id.tab_maintainer -> show(maintainerFragment, R.string.tab_maintainer)
                R.id.tab_settings -> show(settingsFragment, R.string.tab_settings)
            }
            true
        }

        if (savedInstanceState == null) show(updateFragment, R.string.tab_check_update)
    }

    /** Swap the main_content fragment and update the collapsing header subtitle. */
    private fun show(fragment: Fragment, subtitleRes: Int) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).subtitle = getString(subtitleRes)
    }
}
