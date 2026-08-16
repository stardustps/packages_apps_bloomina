package com.Zerodactyl.bloomina.ota

import android.content.Context
import android.os.RecoverySystem
import android.os.SystemProperties
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface InstallResult {
    data object StagedRebootingToRecovery : InstallResult
    data object AppliedBackgroundRebootRequired : InstallResult
    data class Failed(val why: String) : InstallResult
}

/**
 * Handles applying a downloaded package natively using AOSP system privileges.
 * 
 * Supports both:
 * 1. A/B Devices: Uses android.os.UpdateEngine to silently apply payload.bin to the inactive slot.
 * 2. A-Only Devices: Uses android.os.RecoverySystem to stage the zip and reboot to recovery.
 * 
 * Requires building as a privileged system app with REBOOT and RECOVERY permissions.
 */
class OtaInstaller(private val context: Context) {

    private val isABDevice: Boolean
        get() = SystemProperties.getBoolean("ro.build.ab_update", false)

    suspend fun installPackage(pkg: File): InstallResult {
        return if (isABDevice) {
            installAB(pkg)
        } else {
            installAOnly(pkg)
        }
    }

    private fun installAOnly(pkg: File): InstallResult = try {
        // RecoverySystem verifies the cryptographic signature of the zip against the system certs
        RecoverySystem.verifyPackage(pkg, null, null)
        // Stages /cache/recovery/command and reboots the device into recovery mode to flash
        RecoverySystem.installPackage(context, pkg)
        InstallResult.StagedRebootingToRecovery
    } catch (e: Exception) {
        InstallResult.Failed("A-only Install failed: ${e.message}")
    }

    private suspend fun installAB(pkg: File): InstallResult = suspendCancellableCoroutine { cont ->
        try {
            val updateEngine = UpdateEngine()
            
            // Extract payload properties and offset from the OTA Zip
            val zipFile = ZipFile(pkg)
            val propertiesEntry = zipFile.getEntry("payload_properties.txt")
                ?: throw IllegalStateException("Not a valid A/B OTA zip (missing payload_properties.txt)")
                
            val payloadEntry = zipFile.getEntry("payload.bin")
                ?: throw IllegalStateException("Not a valid A/B OTA zip (missing payload.bin)")

            // Read the properties into a String array for the UpdateEngine
            val propertiesList = mutableListOf<String>()
            zipFile.getInputStream(propertiesEntry).bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) propertiesList.add(line) }
            }
            val headerKeyValuePairs = propertiesList.toTypedArray()

            // Calculate the exact byte offset of payload.bin within the zip file.
            // (Android UpdateEngine can read directly from the zip if we give it the offset and length)
            val payloadOffset = getZipEntryOffset(pkg, payloadEntry.name)
            val payloadSize = payloadEntry.size
            val fileUrl = "file://${pkg.absolutePath}"

            val callback = object : UpdateEngineCallback() {
                override fun onStatusUpdate(status: Int, percent: Float) {
                    // Could emit progress here if we passed a flow/callback, but for now just wait for completion
                }

                override fun onPayloadApplicationComplete(errorCode: Int) {
                    if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
                        cont.resume(InstallResult.AppliedBackgroundRebootRequired)
                    } else {
                        cont.resume(InstallResult.Failed("UpdateEngine error code: $errorCode"))
                    }
                }
            }

            updateEngine.bind(callback)
            updateEngine.applyPayload(fileUrl, payloadOffset, payloadSize, headerKeyValuePairs)

        } catch (e: Exception) {
            cont.resume(InstallResult.Failed("A/B Update initialization failed: ${e.message}"))
        }
    }

    /**
     * Calculates the absolute byte offset of a file inside an uncompressed Zip file.
     * UpdateEngine requires this offset so it doesn't have to extract the 3GB payload.bin.
     */
    private fun getZipEntryOffset(file: File, entryName: String): Long {
        // A simple, reliable way without writing a full zip parser is iterating headers.
        // We will just read raw bytes to find the local file header signature 0x04034b50
        // and matching file name, then calculate offset + header length.
        // For production LineageOS, they use a dedicated C++ zip parser or ZipFile API, 
        // but this works securely in Kotlin.
        java.io.RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            val buffer = ByteArray(30)
            while (offset < raf.length() - 30) {
                raf.seek(offset)
                raf.readFully(buffer)
                
                // Check for Local File Header signature (0x04034b50)
                if (buffer[0] == 0x50.toByte() && buffer[1] == 0x4b.toByte() && 
                    buffer[2] == 0x03.toByte() && buffer[3] == 0x04.toByte()) {
                    
                    val nameLength = (buffer[26].toInt() and 0xFF) or ((buffer[27].toInt() and 0xFF) shl 8)
                    val extraFieldLength = (buffer[28].toInt() and 0xFF) or ((buffer[29].toInt() and 0xFF) shl 8)
                    
                    val nameBuffer = ByteArray(nameLength)
                    raf.readFully(nameBuffer)
                    val currentName = String(nameBuffer)
                    
                    if (currentName == entryName) {
                        return offset + 30 + nameLength + extraFieldLength
                    }
                    
                    // Skip to next header by seeking past compressed data (if we could easily read compressed size).
                    // As a fallback, we just advance the offset safely. In reality ZipFile API handles this better 
                    // via Central Directory, but Java's ZipFile doesn't expose the local header offset.
                    // Instead, we just advance by 1 to search for the next signature since it's fast enough.
                }
                offset++
            }
        }
        throw IllegalStateException("Could not find payload offset for $entryName")
    }
}
