package com.Zerodactyl.bloomina.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between [com.Zerodactyl.bloomina.DownloadService] (which owns the
 * actual download coroutine so it survives the UI being closed) and the UI layer
 * ([com.Zerodactyl.bloomina.ui.CheckUpdateViewModel]).
 *
 * It intentionally lives outside the ViewModel so a download that finishes while the
 * app is in the background can still be surfaced when the user returns.
 */
object DownloadBus {

    enum class Status { DOWNLOADING, PAUSED, DONE, FAILED }

    data class Snapshot(
        val status: Status,
        val bytes: Long = 0L,
        val total: Long = 0L,
        val file: String? = null,
        val error: String? = null,
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot = _snapshot.asStateFlow()

    /** Absolute path of a completed download awaiting install, if any. */
    var pendingInstallPath: String? = null

    fun post(snapshot: Snapshot) {
        _snapshot.value = snapshot
    }

    fun reset() {
        _snapshot.value = null
        pendingInstallPath = null
    }
}
