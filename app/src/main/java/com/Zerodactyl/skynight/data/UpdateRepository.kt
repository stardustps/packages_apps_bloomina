package com.Zerodactyl.skynight.data

import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
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
            require(urlString.startsWith("http://") || urlString.startsWith("https://")) {
                "Manifest URL must start with http:// or https://"
            }
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "skynight/8.6.4")
            
            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    error(
                        when (responseCode) {
                            404 -> "No manifest for this device (HTTP 404)"
                            403 -> "Manifest access denied (HTTP 403)"
                            in 500..599 -> "Update server error (HTTP $responseCode)"
                            else -> "HTTP $responseCode"
                        }
                    )
                }
                
                val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                if (body.isBlank()) error("Update server returned an empty response")
                
                UpdateManifest.fromJson(JSONObject(body))
            } finally {
                connection.disconnect()
            }
        }
    }

    fun download(download: Download, dest: File): Flow<DownloadState> = flow {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(download.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                emit(DownloadState.Failed("HTTP $responseCode"))
                return@flow
            }
            
            val total = if (download.sizeBytes > 0) download.sizeBytes else connection.contentLength.toLong()
            val digest = MessageDigest.getInstance("SHA-256")
            
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastEmit = 0L
                    
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
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
            
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(download.sha256, ignoreCase = true)) {
                dest.delete()
                emit(DownloadState.Failed("Checksum mismatch - download rejected"))
            } else {
                emit(DownloadState.Done(dest))
            }
        } catch (e: Exception) {
            emit(DownloadState.Failed(e.message ?: "Download failed"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        fun describe(t: Throwable): String = when (t) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Update server timed out"
            is JSONException -> "Manifest is not valid JSON"
            is IllegalArgumentException, is IllegalStateException ->
                t.message ?: "Could not read the update manifest"
            is IOException -> t.message ?: "Network error"
            else -> t.message ?: t::class.java.simpleName
        }
    }
}
