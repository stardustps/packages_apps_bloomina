#!/bin/bash

# ==========================================
# 2. Hide from App Drawer & Settings Integration
# ==========================================
# Remove LAUNCHER category to hide from app drawer
sed -i '/<category android:name="android.intent.category.LAUNCHER" \/>/d' app/src/main/AndroidManifest.xml

# Ensure standard AOSP Settings injection alongside Samsung's
sed -i '/<meta-data android:name="com.samsung.settings.category"/i\
            <!-- Standard AOSP Settings Injection -->\
            <intent-filter>\
                <action android:name="android.settings.SYSTEM_UPDATE_SETTINGS" />\
                <category android:name="android.intent.category.DEFAULT" />\
            </intent-filter>' app/src/main/AndroidManifest.xml

# ==========================================
# 3. Maintainer Social Links (GitHub/XDA)
# ==========================================
# Add fields to JSON parser (UpdateManifest.kt)
sed -i 's/val donateUrl: String?/val donateUrl: String?,\n    val githubUrl: String?,\n    val xdaUrl: String?/g' app/src/main/java/com/Zerodactyl/bloomina/data/UpdateManifest.kt
sed -i 's/donateUrl = json.optString("donate_url", null)/donateUrl = json.optString("donate_url", null),\n                githubUrl = json.optString("github_url", null),\n                xdaUrl = json.optString("xda_url", null)/g' app/src/main/java/com/Zerodactyl/bloomina/data/UpdateManifest.kt

# Add buttons to fragment_maintainer.xml
sed -i '/<View android:layout_width="match_parent" android:layout_height="1dp" android:background="?android:attr\/listDivider" \/>/i\
                <TextView\
                    android:id="@+id/btnGithub"\
                    android:layout_width="match_parent"\
                    android:layout_height="wrap_content"\
                    android:padding="16dp"\
                    android:text="GitHub"\
                    android:visibility="gone"\
                    android:textAppearance="?attr/textAppearanceBodyLarge"\
                    android:clickable="true"\
                    android:focusable="true"\
                    android:background="?attr/selectableItemBackground" />\
                <View android:id="@+id/divGithub" android:layout_width="match_parent" android:layout_height="1dp" android:background="?android:attr/listDivider" android:visibility="gone" />\
                <TextView\
                    android:id="@+id/btnXda"\
                    android:layout_width="match_parent"\
                    android:layout_height="wrap_content"\
                    android:padding="16dp"\
                    android:text="XDA Thread"\
                    android:visibility="gone"\
                    android:textAppearance="?attr/textAppearanceBodyLarge"\
                    android:clickable="true"\
                    android:focusable="true"\
                    android:background="?attr/selectableItemBackground" />\
                <View android:id="@+id/divXda" android:layout_width="match_parent" android:layout_height="1dp" android:background="?android:attr/listDivider" android:visibility="gone" />' app/src/main/res/layout/fragment_maintainer.xml

# Update MaintainerFragment.kt to handle the new links
sed -i '/import android.os.Bundle/a import android.content.Intent\nimport android.net.Uri' app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt

cat << 'INNER_EOF' >> app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt

    private fun bindLinks(m: com.Zerodactyl.bloomina.data.Maintainer) {
        val v = _b ?: return
        
        fun setupLink(btn: View, div: View?, url: String?) {
            if (!url.isNullOrBlank()) {
                btn.visibility = View.VISIBLE
                div?.visibility = View.VISIBLE
                btn.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } else {
                btn.visibility = View.GONE
                div?.visibility = View.GONE
            }
        }
        
        setupLink(v.btnTelegram, null, m.telegram)
        setupLink(v.btnDonate, null, m.donateUrl)
        setupLink(v.btnGithub, v.divGithub, m.githubUrl)
        setupLink(v.btnXda, v.divXda, m.xdaUrl)
    }
INNER_EOF

# Replace the simple link setup with our new bindLinks function
sed -i 's/v.btnTelegram.setOnClickListener {/bindLinks(m) \/\//g' app/src/main/java/com/Zerodactyl/bloomina/ui/MaintainerFragment.kt

