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
        var updateEngine: UpdateEngine? = null
        try {
            // Copy zip to /cache/ which is readable by update_engine service
            val cacheDir = File("/cache/")
            val cachePkg = File(cacheDir, pkg.name)
            if (pkg.absolutePath != cachePkg.absolutePath) {
                pkg.copyTo(cachePkg, overwrite = true)
                cachePkg.setReadable(true, false)
            }

            updateEngine = UpdateEngine()
            
            // Extract payload properties and offset from the OTA Zip
            val zipFile = ZipFile(cachePkg)
            try {
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
                val payloadOffset = getZipEntryOffset(cachePkg, payloadEntry.name)
                val payloadSize = payloadEntry.size
                val fileUrl = "file://${cachePkg.absolutePath}"

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

                cont.invokeOnCancellation {
                    runCatching { updateEngine.unbind() }
                }

                updateEngine.bind(callback)
                updateEngine.applyPayload(fileUrl, payloadOffset, payloadSize, headerKeyValuePairs)
            } finally {
                zipFile.close()
            }

        } catch (e: Exception) {
            cont.resume(InstallResult.Failed("A/B Update initialization failed: ${e.message}"))
        }
    }

    /**
     * Calculates the absolute byte offset of a file inside an uncompressed Zip file.
     * UpdateEngine requires this offset so it doesn't have to extract the 3GB payload.bin.
     */
    private fun getZipEntryOffset(file: File, entryName: String): Long {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            // End Of Central Directory record is at most 22 + 65535 bytes and lives at the file tail.
            val eocdSize = if (len > 22L + 0xFFFF) (22 + 0xFFFF) else len.toInt()
            val eocdBuf = ByteArray(eocdSize)
            raf.seek(len - eocdBuf.size)
            raf.readFully(eocdBuf)
            // Locate the EOCD signature (0x06054b50), taking the last match.
            var eocd = -1
            for (i in 0 until eocdBuf.size - 21) {
                if (eocdBuf[i] == 0x50.toByte() && eocdBuf[i + 1] == 0x4b.toByte() &&
                    eocdBuf[i + 2] == 0x05.toByte() && eocdBuf[i + 3] == 0x06.toByte()
                ) eocd = i
            }
            if (eocd < 0) throw IllegalStateException("Not a valid zip (missing EOCD)")
            val cdOffset = readLe32(eocdBuf, eocd + 16)
            val cdCount = readLe16(eocdBuf, eocd + 10)
            raf.seek(cdOffset)
            val cdh = ByteArray(46)
            for (n in 0 until cdCount) {
                raf.readFully(cdh)
                if (readLe32(cdh, 0) != 0x02014b50L) throw IllegalStateException("Corrupt central directory")
                val nameLen = readLe16(cdh, 28)
                val extraLen = readLe16(cdh, 30)
                val commentLen = readLe16(cdh, 32)
                val localOffset = readLe32(cdh, 42)
                val nameBuf = ByteArray(nameLen)
                raf.readFully(nameBuf)
                raf.skipBytes(extraLen + commentLen)
                if (String(nameBuf, Charsets.UTF_8) == entryName) {
                    val lh = ByteArray(30)
                    raf.seek(localOffset)
                    raf.readFully(lh)
                    if (readLe32(lh, 0) != 0x04034b50L) throw IllegalStateException("Bad local header for $entryName")
                    val lNameLen = readLe16(lh, 26)
                    val lExtraLen = readLe16(lh, 28)
                    return localOffset + 30 + lNameLen + lExtraLen
                }
            }
        }
        throw IllegalStateException("Could not find $entryName in zip")
    }

    private fun readLe16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
        ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or
        ((b[o + 3].toLong() and 0xFF) shl 24)
}
