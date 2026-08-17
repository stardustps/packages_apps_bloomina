package com.Zerodactyl.bloomina.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.OtaConfig
import com.Zerodactyl.bloomina.ota.DeviceInfo

/**
 * Standard Material Design shell:
 *   - MaterialToolbar gives the collapsing title.
 *   - Floating navigation bar with pill shape and glass effect.
 *
 * Tab tinting reads from the active theme via [MaterialColors] instead of hard-coded
 * color resources, so it tracks Dynamic Color / dark mode automatically. The selected tab is
 * persisted across configuration changes so the active pill stays highlighted after rotation.
 */
class MainActivity : AppCompatActivity() {

    private val updateFragment by lazy { CheckUpdateFragment() }
    private val maintainerFragment by lazy { MaintainerFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    private var activeTab: Int = 0

    private data class Tab(
        val id: Int,
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val fragment: Fragment,
        val subtitleRes: Int
    )

    private lateinit var tabs: List<Tab>

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        checkUpdateCompleted()

        setContentView(R.layout.activity_main)

        tabs = listOf(
            Tab(
                id = R.id.navUpdate,
                container = findViewById(R.id.navUpdate),
                icon = findViewById(R.id.iconUpdate),
                label = findViewById(R.id.labelUpdate),
                fragment = updateFragment,
                subtitleRes = R.string.tab_check_update
            ),
            Tab(
                id = R.id.navMaintainer,
                container = findViewById(R.id.navMaintainer),
                icon = findViewById(R.id.iconMaintainer),
                label = findViewById(R.id.labelMaintainer),
                fragment = maintainerFragment,
                subtitleRes = R.string.tab_maintainer
            ),
            Tab(
                id = R.id.navSettings,
                container = findViewById(R.id.navSettings),
                icon = findViewById(R.id.iconSettings),
                label = findViewById(R.id.labelSettings),
                fragment = settingsFragment,
                subtitleRes = R.string.tab_settings
            )
        )

        tabs.forEach { tab -> tab.container.setOnClickListener { selectTab(tab) } }

        val restored = savedInstanceState?.getInt(KEY_ACTIVE_TAB, R.id.navUpdate) ?: R.id.navUpdate
        if (savedInstanceState == null) {
            selectTab(tabs[0])
        } else {
            activeTab = restored
            applyTabVisuals(restored)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTab)
    }

    private fun selectTab(tab: Tab) {
        if (activeTab == tab.id) return
        activeTab = tab.id
        applyTabVisuals(tab.id)
        show(tab)
    }

    private fun applyTabVisuals(activeId: Int) {
        val primary = MaterialColors.getColor(this, R.attr.colorPrimary, ContextCompat.getColor(this, R.color.ic_launcher_background))
        val inactive = MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant, ContextCompat.getColor(this, R.color.glass_card_border))
        tabs.forEach { tab ->
            val active = tab.id == activeId
            val color = if (active) primary else inactive
            tab.icon.setColorFilter(color)
            tab.label.setTextColor(color)
            tab.label.textFontWeight = if (active) 600 else 400
        }
    }

    private fun checkUpdateCompleted() {
        val prefs: SharedPreferences = getSharedPreferences(OtaConfig.PREFS_NAME, 0)
        val pending = prefs.getString("pending_update_version", null)
        if (!pending.isNullOrBlank() && pending == DeviceInfo.romVersion) {
            prefs.edit().remove("pending_update_version").apply()
            val text = getString(R.string.update_done_text)
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = android.app.NotificationChannel(
                "ota_updates",
                getString(R.string.notif_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
            nm.notify(
                3,
                androidx.core.app.NotificationCompat.Builder(this, "ota_updates")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(getString(R.string.update_done_title))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build()
            )
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Swap the main_content fragment and update the collapsing header subtitle. */
    private fun show(tab: Tab) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragmentContainer, tab.fragment)
            .commit()
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).subtitle =
            getString(tab.subtitleRes)
    }

    private companion object {
        const val KEY_ACTIVE_TAB = "active_tab"
    }
}
