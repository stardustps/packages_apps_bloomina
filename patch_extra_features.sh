#!/bin/bash

# ==========================================
# 1. Dynamic Colors (Monet)
# ==========================================
sed -i '/import android.os.Bundle/a import com.google.android.material.color.DynamicColors' app/src/main/java/com/Zerodactyl/bloomina/ui/MainActivity.kt
sed -i '/super.onCreate(savedInstanceState)/i\
        DynamicColors.applyToActivityIfAvailable(this)' app/src/main/java/com/Zerodactyl/bloomina/ui/MainActivity.kt

# ==========================================
# 3. Tap to Copy
# ==========================================
sed -i '/import android.net.Uri/a import android.content.ClipboardManager\nimport android.content.ClipData' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

cat << 'INNER_EOF' >> app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

    private fun setupTapToCopy() {
        val copyAction = { text: CharSequence ->
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Device Info", text))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        
        b.rowInstalledVersion.setOnClickListener { copyAction(b.rowInstalledVersion.text) }
        b.rowDeviceModel.setOnClickListener { copyAction(b.rowDeviceModel.text) }
        b.rowAndroid.setOnClickListener { copyAction(b.rowAndroid.text) }
        b.rowSecurity.setOnClickListener { copyAction(b.rowSecurity.text) }
        b.rowFingerprint.setOnClickListener { copyAction(b.rowFingerprint.text) }
        b.rowKernel.setOnClickListener { copyAction(b.rowKernel.text) }
    }
INNER_EOF

# Call setupTapToCopy from onViewCreated
sed -i '/renderLocalDeviceRows()/a\
        setupTapToCopy()' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt


# ==========================================
# 2. Download Progress Notifications & 4. Battery Check
# ==========================================
sed -i '/import android.widget.Toast/a import android.app.NotificationChannel\nimport android.app.NotificationManager\nimport androidx.core.app.NotificationCompat\nimport android.os.BatteryManager\nimport android.content.Intent\nimport android.content.IntentFilter' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# Replace downloadAndInstall collect loop with Notification and Battery logic
sed -i '/repo.download(dl, dest).collect { st ->/,/}/c\
            val nm = requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager\
            val channel = NotificationChannel("ota_updates", "System Updates", NotificationManager.IMPORTANCE_LOW)\
            nm.createNotificationChannel(channel)\
            val builder = NotificationCompat.Builder(requireContext(), "ota_updates")\
                .setSmallIcon(R.drawable.ic_launcher_foreground)\
                .setContentTitle("Downloading System Update")\
                .setOngoing(true)\
                .setOnlyAlertOnce(true)\
\
            repo.download(dl, dest).collect { st ->\
                val v = _b ?: return@collect\
                when (st) {\
                    is DownloadState.Progress -> {\
                        val pct = (st.fraction * 100).toInt()\
                        v.downloadBar.isIndeterminate = false\
                        v.downloadBar.visibility = View.VISIBLE\
                        v.downloadBar.progress = pct\
                        setHero(R.drawable.ic_status_available, getString(R.string.status_downloading), getString(R.string.status_downloading_sub, pct))\
                        \
                        builder.setProgress(100, pct, false)\
                        builder.setContentText("$pct%")\
                        nm.notify(1, builder.build())\
                    }\
                    is DownloadState.Failed -> {\
                        v.downloadBar.visibility = View.GONE\
                        setHero(R.drawable.ic_status_error, getString(R.string.status_failed), st.reason)\
                        v.btnDownload.isEnabled = true\
                        \
                        builder.setContentTitle("Download Failed").setContentText(st.reason).setProgress(0, 0, false).setOngoing(false)\
                        nm.notify(1, builder.build())\
                    }\
                    is DownloadState.Done -> {\
                        v.downloadBar.visibility = View.GONE\
                        builder.setContentTitle("Download Complete").setContentText("Ready to install").setProgress(0, 0, false).setOngoing(false)\
                        nm.notify(1, builder.build())\
                        \
                        // Battery Safety Check\
                        val batteryStatus: Intent? = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))\
                        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1\
                        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1\
                        val batteryPct = level * 100 / scale.toFloat()\
                        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1\
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL\
                        \
                        if (batteryPct < 20 && !isCharging) {\
                            AlertDialog.Builder(requireContext())\
                                .setTitle("Battery Too Low")\
                                .setMessage("Your battery is below 20%. Please plug in your device to safely install this system update.")\
                                .setPositiveButton("OK", null)\
                                .show()\
                            v.btnDownload.text = "Install Now"\
                            v.btnDownload.isEnabled = true\
                            v.btnDownload.setOnClickListener { install(st.file) }\
                        } else {\
                            install(st.file)\
                        }\
                    }\
                }\
            }' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

