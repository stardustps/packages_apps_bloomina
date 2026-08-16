#!/bin/bash

# ==========================================
# 1. Auto-Delete Old OTAs
# ==========================================
# Inject cleanup logic into CheckUpdateFragment
sed -i '/renderLocalDeviceRows()/i\
        cleanupOldOtas()' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

cat << 'INNER_EOF' >> app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

    private fun cleanupOldOtas() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Clean cache dir (Local Updates)
                requireContext().cacheDir.listFiles { _, name -> name.endsWith(".zip") }?.forEach { it.delete() }
                // Clean external files dir (Downloaded OTAs)
                val extDir = requireContext().getExternalFilesDir(null)
                extDir?.listFiles { _, name -> name.endsWith(".zip") }?.forEach { file ->
                    // Only delete if it's not currently downloading
                    if (file.exists() && b.downloadBar.visibility != View.VISIBLE) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
INNER_EOF

# ==========================================
# 2. AMOLED Black Theme for Night Mode
# ==========================================
mkdir -p app/src/main/res/values-night
cat << 'INNER_EOF' > app/src/main/res/values-night/themes.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.skynight" parent="Theme.Material3.DynamicColors.DayNight">
        <item name="android:fontFamily">@font/google_sans_flex</item>
        <item name="fontFamily">@font/google_sans_flex</item>
        <!-- Pure AMOLED Black for OLED screens -->
        <item name="android:windowBackground">#000000</item>
        <item name="colorSurface">#000000</item>
        <item name="colorSurfaceContainer">#111111</item>
        <item name="colorPrimary">@color/ic_launcher_background</item>
    </style>
</resources>
INNER_EOF

# Ensure day mode is updated too just in case
cat << 'INNER_EOF' > app/src/main/res/values/themes.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.skynight" parent="Theme.Material3.DynamicColors.DayNight">
        <item name="android:fontFamily">@font/google_sans_flex</item>
        <item name="fontFamily">@font/google_sans_flex</item>
        <item name="android:windowBackground">?attr/colorSurface</item>
        <item name="colorPrimary">@color/ic_launcher_background</item>
    </style>
</resources>
INNER_EOF

# ==========================================
# 4. Custom "Reboot Now" Bottom Sheet
# ==========================================
# Add BottomSheetDialog import
sed -i '/import androidx.appcompat.app.AlertDialog/a import com.google.android.material.bottomsheet.BottomSheetDialog\nimport android.widget.Button\nimport android.widget.TextView\nimport android.widget.LinearLayout\nimport android.view.Gravity' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# Replace the boring button text change with a Bottom Sheet
sed -i 's/v.btnDownload.text = "Reboot"/showRebootBottomSheet()/g' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt
sed -i 's/v.btnDownload.setOnClickListener { RootManager.exec("reboot") } \/\/ Optional reboot shortcut//g' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

cat << 'INNER_EOF' >> app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

    private fun showRebootBottomSheet() {
        val sheet = BottomSheetDialog(requireContext())
        
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val title = TextView(requireContext()).apply {
            text = "System Update Complete"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        
        val desc = TextView(requireContext()).apply {
            text = "The update has been successfully installed in the background. A restart is required to finish applying the changes."
            textSize = 16f
            setPadding(0, 0, 0, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        val btnReboot = Button(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "Reboot Now"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                sheet.dismiss()
                val pm = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                pm.reboot(null)
            }
        }
        
        layout.addView(title)
        layout.addView(desc)
        layout.addView(btnReboot)
        
        sheet.setContentView(layout)
        sheet.setCancelable(false)
        sheet.show()
    }
INNER_EOF

