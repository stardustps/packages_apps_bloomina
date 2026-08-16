#!/bin/bash

# ==========================================
# 1. Add A/B Slot Diagnostic Status UI
# ==========================================
sed -i '/<!-- This device -->/i\
        <!-- A/B Slot Status -->\
        <TextView\
            android:id="@+id/sepSlotStatus"\
            android:layout_width="match_parent"\
            android:layout_height="wrap_content"\
            android:paddingHorizontal="24dp"\
            android:paddingTop="24dp"\
            android:paddingBottom="8dp"\
            android:text="System Diagnostics"\
            android:textAppearance="?attr/textAppearanceLabelLarge"\
            android:textColor="?attr/colorPrimary" />\
\
        <com.google.android.material.card.MaterialCardView\
            android:id="@+id/cardSlotStatus"\
            android:layout_width="match_parent"\
            android:layout_height="wrap_content"\
            android:layout_marginHorizontal="16dp"\
            app:cardElevation="0dp"\
            app:cardBackgroundColor="?attr/colorSurfaceContainer">\
            <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:padding="16dp">\
                <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Partition Layout" android:textStyle="bold" />\
                <TextView android:id="@+id/rowLayoutType" android:layout_width="match_parent" android:layout_height="wrap_content" android:paddingBottom="8dp" />\
                <TextView android:id="@+id/lblActiveSlot" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Active Slot" android:textStyle="bold" />\
                <TextView android:id="@+id/rowActiveSlot" android:layout_width="match_parent" android:layout_height="wrap_content" />\
            </LinearLayout>\
        </com.google.android.material.card.MaterialCardView>\
' app/src/main/res/layout/fragment_check_update.xml


# ==========================================
# 2. Add Local Update FAB UI
# ==========================================
# Change NestedScrollView to CoordinatorLayout
sed -i 's/<androidx.core.widget.NestedScrollView/<androidx.coordinatorlayout.widget.CoordinatorLayout/g' app/src/main/res/layout/fragment_check_update.xml
sed -i 's/<\/androidx.core.widget.NestedScrollView>/<\/androidx.coordinatorlayout.widget.CoordinatorLayout>/g' app/src/main/res/layout/fragment_check_update.xml

sed -i 's/<LinearLayout/<androidx.core.widget.NestedScrollView android:layout_width="match_parent" android:layout_height="match_parent">\n    <LinearLayout/g' app/src/main/res/layout/fragment_check_update.xml
sed -i '/<\/androidx.coordinatorlayout.widget.CoordinatorLayout>/i\
    </androidx.core.widget.NestedScrollView>\
\
    <com.google.android.material.floatingactionbutton.FloatingActionButton\
        android:id="@+id/fabLocalUpdate"\
        android:layout_width="wrap_content"\
        android:layout_height="wrap_content"\
        android:layout_gravity="bottom|end"\
        android:layout_margin="16dp"\
        android:layout_marginBottom="112dp"\
        app:srcCompat="@drawable/ic_launcher_foreground"\
        app:tint="?attr/colorOnPrimaryContainer"\
        android:backgroundTint="?attr/colorPrimaryContainer"\
        android:contentDescription="Local Update" />\
' app/src/main/res/layout/fragment_check_update.xml


# ==========================================
# 3. Patch Kotlin Logic for 1, 2, 3, 4
# ==========================================
# Add missing imports
sed -i '/import android.widget.Toast/a import android.net.ConnectivityManager\nimport android.net.NetworkCapabilities\nimport android.os.SystemProperties\nimport android.text.Html\nimport android.text.method.LinkMovementMethod\nimport androidx.activity.result.contract.ActivityResultContracts\nimport android.net.Uri' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# Inject Local Update launcher
sed -i '/private var manifest: UpdateManifest?/a\
    private val localUpdateLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->\
        if (uri != null) handleLocalUpdate(uri)\
    }' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# Inject FAB listener and A/B status
sed -i '/renderLocalDeviceRows()/a\
        renderDiagnostics()\
        b.fabLocalUpdate.setOnClickListener { localUpdateLauncher.launch("application/zip") }' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# HTML formatting for Changelog (Idea 4)
sed -i 's/v.changelog.text = r.changelog.joinToString("\\n") { "•  $it" }/v.changelog.text = Html.fromHtml(r.changelog.joinToString("<br>") { "&#8226; $it" }, Html.FROM_HTML_MODE_COMPACT)\n                    v.changelog.movementMethod = LinkMovementMethod.getInstance()/g' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# Inject Metered Network warning (Idea 2)
sed -i '/private fun downloadAndInstall(dl: Download) {/a\
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager\
        val activeNetwork = cm.activeNetwork\
        val caps = cm.getNetworkCapabilities(activeNetwork)\
        if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {\
            AlertDialog.Builder(requireContext())\
                .setTitle("Cellular Data Warning")\
                .setMessage("You are on a metered network. This update may consume a large amount of data. Continue?")\
                .setPositiveButton("Download") { _, _ -> startDownload(dl) }\
                .setNegativeButton("Cancel", null)\
                .show()\
            return\
        }\
        startDownload(dl)\
    }\
\
    private fun startDownload(dl: Download) {' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# Append Diagnostics and Local Update Handlers
cat << 'INNER_EOF' >> app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

    private fun renderDiagnostics() {
        val isAB = SystemProperties.getBoolean("ro.build.ab_update", false)
        val slot = SystemProperties.get("ro.boot.slot_suffix", "")
        b.rowLayoutType.text = if (isAB) "A/B (Seamless)" else "A-Only (Recovery)"
        if (isAB && slot.isNotEmpty()) {
            b.rowActiveSlot.text = slot.replace("_", "").uppercase()
            b.lblActiveSlot.visibility = View.VISIBLE
            b.rowActiveSlot.visibility = View.VISIBLE
        } else {
            b.lblActiveSlot.visibility = View.GONE
            b.rowActiveSlot.visibility = View.GONE
        }
    }

    private fun handleLocalUpdate(uri: Uri) {
        setHero(R.drawable.ic_cloud_large, "Staging Local Update", "Copying zip file...")
        b.downloadBar.isIndeterminate = true
        b.downloadBar.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dest = File(requireContext().cacheDir, "local_update.zip")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    b.downloadBar.visibility = View.GONE
                    install(dest)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setHero(R.drawable.ic_status_error, "Local Update Failed", e.message ?: "Could not read file")
                    b.downloadBar.visibility = View.GONE
                }
            }
        }
    }
INNER_EOF

