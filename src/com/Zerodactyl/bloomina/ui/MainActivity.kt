package com.Zerodactyl.bloomina.ui

import android.os.Bundle
import com.google.android.material.color.DynamicColors
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.Zerodactyl.bloomina.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.Zerodactyl.bloomina.ota.DeviceInfo

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
        checkUpdateCompleted()
        
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

    private fun checkUpdateCompleted() {
        val prefs = getSharedPreferences("bloomina", 0)
        val pending = prefs.getString("pending_update_version", null)
        if (!pending.isNullOrBlank() && pending == DeviceInfo.romVersion) {
            prefs.edit().remove("pending_update_version").apply()
            val text = getString(R.string.update_done_text)
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel("ota_updates", getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            nm.notify(
                3,
                NotificationCompat.Builder(this, "ota_updates")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(getString(R.string.update_done_title))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build()
            )
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
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
