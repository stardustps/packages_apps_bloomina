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
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * Standard Material Design shell:
 *   - MaterialToolbar gives the collapsing title.
 *   - Floating navigation bar with pill shape and glass effect.
 */
class MainActivity : AppCompatActivity() {

    private val updateFragment by lazy { CheckUpdateFragment() }
    private val maintainerFragment by lazy { MaintainerFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    private var activeTab: Int = R.id.navUpdate

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        checkUpdateCompleted()
        
        setContentView(R.layout.activity_main)

        val navUpdate = findViewById<LinearLayout>(R.id.navUpdate)
        val navMaintainer = findViewById<LinearLayout>(R.id.navMaintainer)
        val navSettings = findViewById<LinearLayout>(R.id.navSettings)

        navUpdate.setOnClickListener { selectTab(R.id.navUpdate) }
        navMaintainer.setOnClickListener { selectTab(R.id.navMaintainer) }
        navSettings.setOnClickListener { selectTab(R.id.navSettings) }

        if (savedInstanceState == null) selectTab(R.id.navUpdate)
    }

    private fun selectTab(tabId: Int) {
        if (activeTab == tabId) return
        activeTab = tabId

        val iconUpdate = findViewById<ImageView>(R.id.iconUpdate)
        val labelUpdate = findViewById<TextView>(R.id.labelUpdate)
        val iconMaintainer = findViewById<ImageView>(R.id.iconMaintainer)
        val labelMaintainer = findViewById<TextView>(R.id.labelMaintainer)
        val iconSettings = findViewById<ImageView>(R.id.iconSettings)
        val labelSettings = findViewById<TextView>(R.id.labelSettings)

        // Reset all to inactive
        iconUpdate.setColorFilter(ContextCompat.getColor(this, R.color.material_on_surface_stroke))
        labelUpdate.setTextColor(ContextCompat.getColor(this, R.color.material_on_surface_stroke))
        labelUpdate.textFontWeight = 400

        iconMaintainer.setColorFilter(ContextCompat.getColor(this, R.color.material_on_surface_stroke))
        labelMaintainer.setTextColor(ContextCompat.getColor(this, R.color.material_on_surface_stroke))
        labelMaintainer.textFontWeight = 400

        iconSettings.setColorFilter(ContextCompat.getColor(this, R.color.material_on_surface_stroke))
        labelSettings.setTextColor(ContextCompat.getColor(this, R.color.material_on_surface_stroke))
        labelSettings.textFontWeight = 400

        // Set active tab
        val primaryColor = ContextCompat.getColor(this, R.color.ic_launcher_background)
        when (tabId) {
            R.id.navUpdate -> {
                iconUpdate.setColorFilter(primaryColor)
                labelUpdate.setTextColor(primaryColor)
                labelUpdate.textFontWeight = 600
                show(updateFragment, R.string.tab_check_update)
            }
            R.id.navMaintainer -> {
                iconMaintainer.setColorFilter(primaryColor)
                labelMaintainer.setTextColor(primaryColor)
                labelMaintainer.textFontWeight = 600
                show(maintainerFragment, R.string.tab_maintainer)
            }
            R.id.navSettings -> {
                iconSettings.setColorFilter(primaryColor)
                labelSettings.setTextColor(primaryColor)
                labelSettings.textFontWeight = 600
                show(settingsFragment, R.string.tab_settings)
            }
        }
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
