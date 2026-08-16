#!/bin/bash
cat << 'INNER_EOF' > app/src/main/java/com/Zerodactyl/bloomina/data/UpdateRepository.kt
package com.Zerodactyl.bloomina.data

import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

sealed interface DownloadState {
    data class Progress(val bytes: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }
    data class Done(val file: File) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

class UpdateRepository {

    suspend fun fetchManifest(urlString: String): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        runCatching {
            require(urlString.isNotBlank()) { "No manifest URL configured" }
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    error("HTTP ${connection.responseCode}")
                }
                val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                UpdateManifest.fromJson(JSONObject(body))
            } finally {
                connection.disconnect()
            }
        }
    }

    fun download(download: Download, dest: File): Flow<DownloadState> = flow {
        var connection: HttpURLConnection? = null
        try {
            var downloadedBytes = 0L
            var append = false
            
            if (dest.exists()) {
                downloadedBytes = dest.length()
                if (downloadedBytes == download.sizeBytes) {
                    // Already fully downloaded, verify hash
                    if (verifyHash(dest, download.sha256)) {
                        emit(DownloadState.Done(dest))
                        return@flow
                    } else {
                        // Hash mismatch on complete file, restart
                        dest.delete()
                        downloadedBytes = 0L
                    }
                } else if (downloadedBytes > 0) {
                    append = true
                }
            }

            val url = URL(download.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            if (append) {
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            
            val responseCode = connection.responseCode
            // HTTP 206 Partial Content is returned when resuming
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                emit(DownloadState.Failed("HTTP $responseCode"))
                return@flow
            }
            
            val total = download.sizeBytes
            
            connection.inputStream.use { input ->
                FileOutputStream(dest, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = downloadedBytes
                    var lastEmit = 0L
                    
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        written += read
                        
                        val pct = if (total > 0) written * 100 / total else 0
                        if (pct != lastEmit) {
                            lastEmit = pct
                            emit(DownloadState.Progress(written, total))
                        }
                    }
                    emit(DownloadState.Progress(written, total))
                }
            }
            
            if (verifyHash(dest, download.sha256)) {
                emit(DownloadState.Done(dest))
            } else {
                dest.delete()
                emit(DownloadState.Failed("Checksum mismatch - download rejected"))
            }
        } catch (e: Exception) {
            emit(DownloadState.Failed(e.message ?: "Download failed"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun verifyHash(file: File, expectedSha256: String): Boolean {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return hex.equals(expectedSha256, ignoreCase = true)
        } catch (e: Exception) {
            return false
        }
    }

    companion object {
        fun describe(t: Throwable): String = t.message ?: "Network error"
    }
}
INNER_EOF
