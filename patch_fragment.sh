#!/bin/bash
# Insert export logic into CheckUpdateFragment.kt

# Add imports for Environment and Toast
sed -i '/import java.util.Locale/a import android.os.Environment\nimport android.widget.Toast\nimport java.io.FileInputStream\nimport java.io.FileOutputStream' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

# Add export function
cat << 'INNER_EOF' >> app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

    private fun exportUpdate(dl: Download) {
        val src = File(requireContext().getExternalFilesDir(null), dl.filename)
        if (!src.exists()) {
            Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = File(downloadsDir, dl.filename)
                
                FileInputStream(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Exported to Downloads folder", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
INNER_EOF

# Add click listener and visibility
sed -i '/b.btnDownload.setOnClickListener/a\
        b.btnExport.setOnClickListener { manifest?.let { exportUpdate(it.release.download) } }' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

sed -i '/v.btnDownload.isEnabled = true/a\
                        v.btnExport.visibility = View.VISIBLE' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt

sed -i '/v.btnDownload.isEnabled = false/a\
                        v.btnExport.visibility = View.GONE' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt
