package com.mrdoob.browser

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

        val startedAt: Long = 0,
        val requestHeaders: List<Pair<String, String>> = emptyList(),
        val responseHeaders: List<Pair<String, String>> = emptyList(),
        val requestBody: String = "",

        val harBody: String = "",
        val harBase64: Boolean = false,
        val encodedSize: Long = -1,
        val decodedSize: Long = -1,
        val transferSize: Long = -1,
        val protocol: String = "",
        val timings: LongArray? = null,

        val kind: String = "",

        val navId: Long = 0,

        val capture: String = "",
        val failed: Boolean = false,
        val error: String = "",

        val redirectHop: Boolean = false
    ) {
        val isDocument: Boolean get() = kind == "document"
        val isFailure: Boolean get() = failed || status >= 400 || (capture.isEmpty() && !pending && status == 0)
    }

    sealed interface Event {
        data class Added(val record: Record) : Event
        data object Cleared : Event

        data class Trimmed(val keepFailed: Boolean, val keepDocs: Boolean) : Event

        data class Patched(
            val requestId: String, val status: Int, val failed: Boolean, val error: String,
            val responseHeaders: List<Pair<String, String>>
        ) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private const val MAX_WEIGHT = 24_000_000L
    private const val STORE_BODY = 32_768
    private const val STORE_REQ_BODY = 8_192

    private val lock = Any()
    private val store = LinkedHashMap<String, Record>()
    private var weight = 0L
    private val idByNavUrl = HashMap<String, String>()

    @Volatile var restoredFromDisk = false
        private set

    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "netlog-timer").apply { isDaemon = true }
    }

    val size: Int get() = synchronized(lock) { store.size }

    fun snapshot(): List<Record> = synchronized(lock) { store.values.toList() }

    fun add(record: Record) {
        val r = stamp(record)
        var forHar: Record? = r
        val toEmit = synchronized(lock) {
            val old = store[r.requestId]
            if (old != null && old.capture.isEmpty() && r.capture == "native") {

                forHar = null
                null
            } else put(r)
        }
        if (toEmit != null) _events.tryEmit(Event.Added(toEmit))

        forHar?.let { HarRecorder.onRecord(toEmit ?: it) }
    }

    fun addHarOnly(record: Record) {
        HarRecorder.onRecord(stamp(record))
    }

    private fun stamp(r: Record): Record {
        val nav = if (r.navId != 0L) r.navId
        else NavigationTracker.navIdAt(if (r.startedAt > 0) r.startedAt else System.currentTimeMillis() - r.durationMs)
        var id = r.requestId
        if (id.isNullOrEmpty()) id = syntheticId(nav, r)
        return if (nav == r.navId && id == r.requestId) r else r.copy(navId = nav, requestId = id)
    }

    private fun syntheticId(nav: Long, r: Record): String {
        val u = r.url.substringBefore('#')
        val key = if (r.kind == "document" && r.method.equals("GET", true)) "d|$nav|$u"
        else "r|$nav|${r.method.uppercase()}|$u|${r.startedAt}"
        return hashId(key)
    }

    fun docId(navId: Long, url: String): String = hashId("d|$navId|${url.substringBefore('#')}")

    private fun hashId(key: String): String {
        var h = -0x340d631b7bdddcdbL
        for (c in key) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return "n" + java.lang.Long.toHexString(h)
    }

    private fun navUrlKey(nav: Long, url: String) = "$nav|${url.substringBefore('#')}"

    private fun put(incoming: Record): Record? {
        val id = incoming.requestId ?: return null
        val old = store[id]
        var r = incoming
        if (old != null && r.status == 0 && !r.pending) {

            if (old.status > 0) r = r.copy(status = old.status)
            if (old.failed && !r.failed) r = r.copy(failed = true, error = r.error.ifEmpty { old.error })
        }
        if (old != null && !old.pending && !r.pending && old.status == r.status && old.sizeBytes == r.sizeBytes &&
            old.durationMs == r.durationMs && old.failed == r.failed && old.capture == r.capture
        ) return null
        val t = r.copy(
            body = r.body.take(STORE_BODY), harBody = "", requestBody = r.requestBody.take(STORE_REQ_BODY)
        )
        store[id] = t
        weight += weightOf(t) - (old?.let { weightOf(it) } ?: 0L)
        idByNavUrl[navUrlKey(r.navId, r.url)] = id
        evict()
        return r
    }

    private fun weightOf(r: Record): Long =
        300L + r.url.length + r.body.length + r.requestBody.length + r.error.length

    private fun evict() {
        val max = DevToolsPrefs.num(DevToolsPrefs.Num.MAX_LOG_ENTRIES)
        if (store.size <= max && weight <= MAX_WEIGHT) return
        val it = store.entries.iterator()
        while ((store.size > max || weight > MAX_WEIGHT) && it.hasNext()) {
            weight -= weightOf(it.next().value)
            it.remove()
        }
        if (idByNavUrl.size > max * 2) idByNavUrl.clear()
    }

    fun addNativeStatus(
        navId: Long, method: String, url: String, status: Int, failed: Boolean, error: String,
        requestHeaders: List<Pair<String, String>>, responseHeaders: List<Pair<String, String>>,
        isDocument: Boolean
    ) {
        val nav = if (navId != 0L) navId else NavigationTracker.navIdAt(System.currentTimeMillis())
        val startedAt = System.currentTimeMillis()
        fun applyPatch(id: String) {
            val patched = synchronized(lock) {
                val old = store[id] ?: return@synchronized false
                store[id] = old.copy(
                    status = if (status > 0) status else old.status,
                    failed = failed || old.failed,
                    error = error.ifEmpty { old.error },
                    pending = false,
                    responseHeaders = if (old.responseHeaders.isEmpty()) responseHeaders else old.responseHeaders
                )
                true
            }
            if (patched) {
                _events.tryEmit(Event.Patched(id, status, failed, error, responseHeaders))
                HarRecorder.onNativePatch(id, status, failed, error, responseHeaders)
            }
        }
        fun lookup(): String? = synchronized(lock) { idByNavUrl[navUrlKey(nav, url)] }
        lookup()?.let { applyPatch(it); return }
        timer.schedule({
            try {
                val id = lookup()
                if (id != null) applyPatch(id) else add(
                    Record(
                        method = method, url = url, status = status, body = "", contentType = null,
                        durationMs = 0, startedAt = startedAt, requestHeaders = requestHeaders,
                        responseHeaders = responseHeaders, kind = if (isDocument) "document" else "",
                        navId = nav, capture = "native", failed = failed, error = error
                    )
                )
            } catch (_: Exception) {  }
        }, 1500, TimeUnit.MILLISECONDS)
    }

    fun reset() {
        synchronized(lock) { store.clear(); idByNavUrl.clear(); weight = 0 }
        _events.tryEmit(Event.Cleared)
    }

    fun clearForNavigation(keepFailed: Boolean, keepDocs: Boolean) {
        if (!keepFailed && !keepDocs) { reset(); return }
        synchronized(lock) {
            val it = store.entries.iterator()
            while (it.hasNext()) {
                val r = it.next().value
                if ((keepFailed && r.isFailure) || (keepDocs && r.isDocument)) continue
                weight -= weightOf(r)
                it.remove()
            }
            idByNavUrl.clear()
            store.values.forEach { idByNavUrl[navUrlKey(it.navId, it.url)] = it.requestId ?: "" }
        }
        _events.tryEmit(Event.Trimmed(keepFailed, keepDocs))
    }

    private val ioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "netlog-io").apply { isDaemon = true } }

    private fun file(context: Context) = File(context.filesDir, "netex_netlog.jsonl")

    fun persist(context: Context) {
        val app = context.applicationContext
        val snap = snapshot()
        ioExec.execute {
            try {
                val f = file(app)
                if (snap.isEmpty()) { f.delete(); return@execute }
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.bufferedWriter().use { w ->
                    for (r in snap) {
                        w.write(
                            JSONObject()
                                .put("m", r.method).put("u", r.url).put("s", r.status)
                                .put("ct", r.contentType ?: "").put("d", r.durationMs).put("z", r.sizeBytes)
                                .put("id", r.requestId ?: "").put("t", r.startedAt).put("k", r.kind)
                                .put("n", r.navId).put("c", r.capture).put("f", r.failed).put("e", r.error)
                                .put("h", r.redirectHop).toString()
                        )
                        w.newLine()
                    }
                }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (_: Exception) {  }
        }
    }

    fun deletePersisted(context: Context) {
        val app = context.applicationContext
        ioExec.execute { try { file(app).delete() } catch (_: Exception) {} }
    }

    fun restore(context: Context) {
        val f = file(context.applicationContext)
        if (!f.exists()) return
        try {
            val max = DevToolsPrefs.num(DevToolsPrefs.Num.MAX_LOG_ENTRIES)
            val lines = f.readLines().takeLast(max)
            synchronized(lock) {
                for (line in lines) {
                    val o = try { JSONObject(line) } catch (_: Exception) { continue }
                    val id = o.optString("id").ifEmpty { continue }
                    val r = Record(
                        method = o.optString("m"), url = o.optString("u"), status = o.optInt("s"), body = "",
                        contentType = o.optString("ct").ifEmpty { null }, durationMs = o.optLong("d"),
                        sizeBytes = o.optLong("z"), requestId = id, startedAt = o.optLong("t"),
                        kind = o.optString("k"), navId = o.optLong("n"), capture = o.optString("c"),
                        failed = o.optBoolean("f"), error = o.optString("e"), redirectHop = o.optBoolean("h")
                    )
                    store[id] = r
                    weight += weightOf(r)
                    idByNavUrl[navUrlKey(r.navId, r.url)] = id
                }
                evict()
            }
            restoredFromDisk = store.isNotEmpty()
        } catch (_: Exception) {  }
    }

    fun markRestoredShown() { restoredFromDisk = false }
}
