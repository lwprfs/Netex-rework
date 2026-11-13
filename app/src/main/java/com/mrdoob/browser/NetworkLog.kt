package com.mrdoob.browser

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object NetworkLog {

    data class Record(
        val method: String,
        val url: String,
        val status: Int,
        val body: String,
        val contentType: String?,
        val durationMs: Long,
        val sizeBytes: Long = 0,
        val requestId: String? = null,
        val pending: Boolean = false,
        // Only consumed by HarRecorder.
        val startedAt: Long = 0,
        val requestHeaders: List<Pair<String, String>> = emptyList(),
        val responseHeaders: List<Pair<String, String>> = emptyList(),
        val requestBody: String = "",
        // HAR-only detail (untruncated body, base64 flag, transfer sizes, protocol, timings).
        val harBody: String = "",
        val harBase64: Boolean = false,
        val encodedSize: Long = -1,
        val decodedSize: Long = -1,
        val transferSize: Long = -1,
        val protocol: String = "",
        val timings: LongArray? = null
    )

    sealed interface Event {
        data class Added(val record: Record) : Event
        data object Cleared : Event
    }

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun add(record: Record) {
        _events.tryEmit(Event.Added(record))
        HarRecorder.onRecord(record)
    }

    fun reset() {
        _events.tryEmit(Event.Cleared)
    }
}
