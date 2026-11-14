package com.mrdoob.browser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Records [NetworkLog] traffic into a HAR session that survives page navigations
 * (the Network tab itself is cleared on every navigation, the recording is not).
 *
 * What ends up in the session is controlled by [HarOptions]: request types, body and
 * header detail, MB limits, privacy filters and session caps.
 *
 * The session is mirrored to `cacheDir/har/<name>.har`. All file work runs on one
 * serial thread, so writes and deletes can never interleave.
 */
object HarRecorder {

    enum class State { IDLE, RECORDING, PAUSED }

    data class Status(
        val state: State = State.IDLE,
        val entries: Int = 0,
        val pages: Int = 0,
        val fileName: String? = null,
        /** Approximate size of stored bodies, and the configured cap for it. */
        val bytes: Long = 0,
        val maxBytes: Long = 0,
        val maxEntries: Int = 0,
        /** True when "stop when full" paused the session. */
        val full: Boolean = false,
        /** True once the session is at 80% of its request or size limit. */
        val nearLimit: Boolean = false
    )

    data class Page(val id: String, val key: String, val url: String, val startedAt: Long, val title: String)
    data class Entry(val record: NetworkLog.Record, val pageId: String?, val startedAt: Long)
    class Snapshot(val pages: List<Page>, val entries: List<Entry>)

    private const val FLUSH_DELAY_MS = 3000L
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    val isRecording: Boolean get() = state == State.RECORDING

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "har-io").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // Size in bytes of the .har file as last written to the cache (0 until the first write).
    private val _fileBytes = MutableStateFlow(0L)
    val fileBytes: StateFlow<Long> = _fileBytes.asStateFlow()

    // Guarded by `lock`.
    @Volatile private var state = State.IDLE
    private val pages = LinkedHashMap<String, Page>()
    private val entries = LinkedHashMap<String, Entry>()
    private var totalChars = 0L
    private var seq = 0
    private var full = false
    private var currentUrl: String? = null // latest page, tracked even while not recording
    private var currentKey: String? = null
    private var flushJob: Job? = null
    private var pinnedSite: String? = null // "this site only": site the recording started on

    // io thread only.
    private var cacheDir: File? = null
    private var ownedFile: File? = null

    fun init(context: Context) {
        HarOptions.init(context)
        val dir = File(context.cacheDir, "har")
        scope.launch { cacheDir = dir }
    }

    /** Starts a brand-new recording (and a new file). */
    fun start() {
        synchronized(lock) {
            resetSession()
            state = State.RECORDING
            pinnedSite = currentUrl?.let { siteOf(it) }?.takeIf { it.isNotEmpty() }
            currentUrl?.let { registerPage(it) }
            publish()
        }
        scope.launch { ownedFile = null } // the previous file stays in the cache untouched
    }

    fun togglePause() {
        synchronized(lock) {
            when (state) {
                State.RECORDING -> state = State.PAUSED
                State.PAUSED -> {
                    state = State.RECORDING
                    full = false
                    currentUrl?.let { registerPage(it) }
                }
                State.IDLE -> return
            }
            publish()
        }
        scheduleFlush()
    }

    /** Ends the recording but keeps what was captured so it can still be exported. */
    fun stop() {
        synchronized(lock) {
            if (state == State.IDLE) return
            state = State.IDLE
            publish()
        }
        scope.launch { writeCache() }
    }

    /** Drops the session and deletes every cached HAR file. */
    fun clear() {
        synchronized(lock) {
            resetSession()
            state = State.IDLE
            publish()
        }
        scope.launch {
            ownedFile = null
            cacheDir?.listFiles()?.forEach { it.delete() }
        }
    }

    /** Called for every committed main-frame URL (including SPA route changes). */
    fun onPageVisited(url: String?) {
        if (url.isNullOrEmpty() || url == "about:blank") return
        synchronized(lock) {
            currentUrl = url
            if (state == State.RECORDING && allowedBySiteOnly(url)) {
                registerPage(url)
                publish()
            }
        }
    }

    fun onPageTitle(title: String?) {
        if (title.isNullOrBlank()) return
        synchronized(lock) {
            val key = currentKey ?: return
            pages[key]?.let { pages[key] = it.copy(title = title) }
        }
    }

    fun onRecord(raw: NetworkLog.Record) {
        if (state == State.IDLE) return
        synchronized(lock) {
            val record = prepare(raw) ?: return
            val key = record.requestId ?: "n${seq++}"
            val old = entries[key]
            if (old == null) {
                // Paused: only requests that were already in flight may still complete.
                if (state != State.RECORDING) return
                if (HarOptions.stopWhenFull && isFull(weight(record))) {
                    state = State.PAUSED
                    full = true
                    publish()
                    return
                }
                val started = if (record.startedAt > 0) record.startedAt
                else System.currentTimeMillis() - record.durationMs
                entries[key] = Entry(record, currentPageId(), started)
                totalChars += weight(record)
                evictIfNeeded()
                publish()
            } else {
                totalChars += weight(record) - weight(old.record)
                entries[key] = old.copy(record = record)
            }
        }
        scheduleFlush()
    }

    /** Writes the session to the cache dir right now and returns the file (null if nothing recorded). */
    suspend fun flush(): File? = withContext(io) { writeCache() }

    // --- capture options -------------------------------------------------------------

    /** Applies the capture-detail options to one record; null drops it from the recording. */
    private fun prepare(r: NetworkLog.Record): NetworkLog.Record? {
        val kind = HarOptions.Kind.from(r.kind, r.contentType)
        if (!HarOptions.recordKind(kind)) return null
        if (!HarOptions.thirdParty && !sameSite(r.url, currentUrl)) return null
        if (!allowedBySiteOnly(r.url)) return null
        if (HarOptions.isExcluded(r.url)) return null

        // Response bodies.
        var body = r.body
        var harBody = r.harBody
        if (r.harBase64) {
            val isImage = kind == HarOptions.Kind.IMAGE
            val allowed = if (isImage) HarOptions.images else HarOptions.fonts
            val limitMb = if (isImage) HarOptions.imageLimitMb else HarOptions.fontLimitMb
            // base64 is ~4/3 the size of the raw bytes
            val cap = HarOptions.capChars(limitMb).toLong() * 4L / 3L
            if (!allowed || harBody.length.toLong() > cap) harBody = ""
            body = ""
        } else if (!HarOptions.textBodies) {
            body = ""
            harBody = ""
        } else {
            val cap = HarOptions.capChars(HarOptions.textLimitMb)
            if (body.length > cap) body = body.substring(0, cap)
            if (harBody.length > cap) harBody = harBody.substring(0, cap)
        }

        // Request payload.
        val reqBody = if (HarOptions.requestBodies) {
            val cap = HarOptions.capChars(HarOptions.requestBodyLimitMb)
            if (r.requestBody.length > cap) r.requestBody.substring(0, cap) else r.requestBody
        } else ""

        // Headers & cookies.
        var reqHeaders = if (HarOptions.requestHeaders) r.requestHeaders else emptyList()
        var resHeaders = if (HarOptions.responseHeaders) r.responseHeaders else emptyList()
        if (!HarOptions.cookies) {
            reqHeaders = reqHeaders.filterNot { it.first.equals("cookie", ignoreCase = true) }
            resHeaders = resHeaders.filterNot { it.first.equals("set-cookie", ignoreCase = true) }
        }
        var url = r.url
        if (HarOptions.redact) {
            reqHeaders = Redact.headers(reqHeaders)
            resHeaders = Redact.headers(resHeaders)
            url = Redact.url(url)
        }

        return r.copy(
            url = url,
            body = body,
            harBody = harBody,
            harBase64 = r.harBase64 && harBody.isNotEmpty(),
            requestBody = reqBody,
            requestHeaders = reqHeaders,
            responseHeaders = resHeaders,
            timings = if (HarOptions.timings) r.timings else null
        )
    }

    /** "This site only": everything must belong to the site the recording was started on. */
    private fun allowedBySiteOnly(url: String): Boolean {
        if (!HarOptions.siteOnly) return true
        val pin = pinnedSite ?: return true
        val site = siteOf(url)
        return site.isEmpty() || site == pin
    }

    private fun sameSite(url: String, page: String?): Boolean {
        if (page.isNullOrEmpty()) return true
        val a = siteOf(url)
        val b = siteOf(page)
        return a.isEmpty() || b.isEmpty() || a == b
    }

    /** Registrable-domain-ish key: `cdn.example.com` and `www.example.com` both give `example.com`. */
    private fun siteOf(url: String): String {
        val host = (try { Uri.parse(url).host } catch (_: Exception) { null } ?: return "").lowercase()
        if (host.all { it.isDigit() || it == '.' }) return host // IPv4
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val last = parts[parts.size - 1]
        val second = parts[parts.size - 2]
        val take = if (last.length == 2 && second in SECOND_LEVEL) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }

    private fun isFull(extra: Long): Boolean =
        entries.size >= HarOptions.maxEntries || totalChars + extra > HarOptions.maxTotalBytes()

    // --- internals -----------------------------------------------------------------

    private fun resetSession() {
        _fileBytes.value = 0L
        pages.clear()
        entries.clear()
        totalChars = 0
        full = false
        currentKey = null
        flushJob?.cancel()
        flushJob = null
    }

    private fun registerPage(url: String) {
        val key = HarBuilder.pageKey(url)
        if (key !in pages) {
            pages[key] = Page("page_${pages.size + 1}", key, url, System.currentTimeMillis(), "")
        }
        currentKey = key
    }

    private fun currentPageId(): String? = currentKey?.let { pages[it]?.id }

    private fun weight(r: NetworkLog.Record): Long = (r.body.length + r.requestBody.length + r.harBody.length).toLong()

    private fun evictIfNeeded() {
        if (HarOptions.stopWhenFull) return // paused before the limit instead
        val maxEntries = HarOptions.maxEntries
        val maxChars = HarOptions.maxTotalBytes()
        val iter = entries.entries.iterator()
        while ((entries.size > maxEntries || totalChars > maxChars) && iter.hasNext()) {
            totalChars -= weight(iter.next().value.record)
            iter.remove()
        }
    }

    private fun publish() {
        _status.value = Status(
            state = state,
            entries = entries.size,
            pages = pages.size,
            fileName = if (entries.isEmpty()) null
            else HarBuilder.fileBase(pages.values.map { it.url }) + ".har",
            bytes = totalChars,
            maxBytes = HarOptions.maxTotalBytes(),
            maxEntries = HarOptions.maxEntries,
            full = full,
            nearLimit = entries.isNotEmpty() && (
                entries.size >= HarOptions.maxEntries * 8 / 10 ||
                    totalChars >= HarOptions.maxTotalBytes() * 8 / 10
                )
        )
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (flushJob?.isActive == true) return
            flushJob = scope.launch {
                delay(FLUSH_DELAY_MS)
                writeCache()
            }
        }
    }

    private fun writeCache(): File? {
        val dir = cacheDir ?: return null
        val snap = synchronized(lock) {
            if (entries.isEmpty()) return null
            Snapshot(pages.values.toList(), entries.values.toList())
        }
        dir.mkdirs()
        val base = HarBuilder.fileBase(snap.pages.map { it.url })
        val owned = ownedFile
        var target = File(dir, "$base.har")
        var n = 2
        while (target.exists() && target != owned) target = File(dir, "${base}_${n++}.har")

        val tmp = File(dir, "$base.tmp")
        try {
            tmp.bufferedWriter().use { HarBuilder.write(snap, it) }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
        } catch (e: Exception) {
            tmp.delete()
            return null
        }
        // The name follows the visited pages, so it can change between writes.
        if (owned != null && owned != target) owned.delete()
        ownedFile = target
        _fileBytes.value = target.length()
        return target
    }
}
