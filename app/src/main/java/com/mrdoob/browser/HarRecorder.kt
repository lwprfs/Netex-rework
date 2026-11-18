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

object HarRecorder {

    enum class State { IDLE, RECORDING, PAUSED }

    data class Status(
        val state: State = State.IDLE,
        val entries: Int = 0,
        val pages: Int = 0,
        val fileName: String? = null,

        val bytes: Long = 0,
        val maxBytes: Long = 0,
        val maxEntries: Int = 0,

        val full: Boolean = false,

        val nearLimit: Boolean = false
    )

    data class Page(val id: String, val key: String, val url: String, val startedAt: Long, val title: String)
    data class Entry(val record: NetworkLog.Record, val pageId: String?, val startedAt: Long)
    class Snapshot(val pages: List<Page>, val entries: List<Entry>)

    private const val FLUSH_DELAY_MS = 3000L
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    val isRecording: Boolean get() = state == State.RECORDING

    @Volatile var onStarted: (() -> Unit)? = null

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "har-io").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _fileBytes = MutableStateFlow(0L)
    val fileBytes: StateFlow<Long> = _fileBytes.asStateFlow()

    @Volatile private var state = State.IDLE
    private val pages = LinkedHashMap<String, Page>()
    private val entries = LinkedHashMap<String, Entry>()
    private var totalChars = 0L
    private var seq = 0
    private var full = false
    private var currentUrl: String? = null
    private var currentKey: String? = null
    private var flushJob: Job? = null
    private var pinnedSite: String? = null

    private val navPages = object : LinkedHashMap<Long, String>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 500
    }

    private var cacheDir: File? = null
    private var ownedFile: File? = null

    fun init(context: Context) {
        HarOptions.init(context)
        val dir = File(context.cacheDir, "har")
        scope.launch { cacheDir = dir }
    }

    fun start() {
        synchronized(lock) {
            resetSession()
            state = State.RECORDING
            pinnedSite = currentUrl?.let { siteOf(it) }?.takeIf { it.isNotEmpty() }
            currentUrl?.let { registerPage(it) }
            publish()
        }
        scope.launch { ownedFile = null }
        onStarted?.invoke()
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

    fun stop() {
        synchronized(lock) {
            if (state == State.IDLE) return
            state = State.IDLE
            publish()
        }
        scope.launch { writeCache() }
    }

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

    fun onNavigation(navId: Long, url: String) {
        if (url.isEmpty() || url == "about:blank") return
        synchronized(lock) {
            currentUrl = url
            if (state == State.RECORDING) {

                if (!DevToolsPrefs.on(DevToolsPrefs.Flag.REC_CONTINUOUS, DevToolsPrefs.siteKey(url))) {
                    state = State.PAUSED
                    publish()
                    return
                }
                if (allowedBySiteOnly(url)) {
                    registerPage(url)
                    currentPageId()?.let { navPages[navId] = it }
                    publish()
                }
            }
        }
    }

    fun onNativePatch(id: String, status: Int, failed: Boolean, error: String, headers: List<Pair<String, String>>) {
        if (state == State.IDLE) return
        synchronized(lock) {
            val e = entries[id] ?: return
            val r = e.record
            entries[id] = e.copy(
                record = r.copy(
                    status = if (status > 0) status else r.status,
                    failed = failed || r.failed,
                    error = error.ifEmpty { r.error },
                    responseHeaders = if (r.responseHeaders.isEmpty() && HarOptions.responseHeaders) headers else r.responseHeaders
                )
            )
        }
        scheduleFlush()
    }

    fun protectSession(reason: String): Boolean {
        val has = synchronized(lock) { entries.isNotEmpty() }
        if (!has) return false
        scope.launch {
            val f = writeCache() ?: return@launch
            val dir = f.parentFile ?: return@launch
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            try { f.copyTo(File(dir, "protected_${reason}_$stamp.har"), overwrite = true) } catch (_: Exception) { }
            dir.listFiles { x -> x.name.startsWith("protected_") }
                ?.sortedBy { it.lastModified() }?.dropLast(5)?.forEach { it.delete() }
        }
        return true
    }

    fun exportToDownloads(context: Context?): Boolean {
        val ctx = context ?: return false
        val has = synchronized(lock) { entries.isNotEmpty() }
        if (!has) return false
        CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch { HarExporter.saveToDownloads(ctx) }
        return true
    }

    fun recoverSession() {
        if (state != State.IDLE) onStarted?.invoke()
    }

    fun pauseIfRecording() {
        synchronized(lock) {
            if (state != State.RECORDING) return
            state = State.PAUSED
            publish()
        }
    }


    // --- HAR viewer ---------------------------------------------------------------------

    class ViewerHar(val text: String, val count: Int, val fromRecording: Boolean)

    /**
     * The HAR the Network tab's HAR button opens. Uses the recording when there is one, otherwise the
     * Network tab's own log (bodies trimmed). [type] is the active type chip ("all", "doc", "js", ...), so
     * "doc" yields a HAR with only the document entries.
     */
    fun buildViewerHar(type: String): ViewerHar {
        val rec = synchronized(lock) { Snapshot(pages.values.toList(), entries.values.toList()) }
        val fromRecording = rec.entries.isNotEmpty()
        val snap = if (fromRecording) rec else logSnapshot()
        val picked = snap.entries.filter { type.isEmpty() || type == "all" || matchesType(it.record, type) }
        val used = picked.mapNotNull { it.pageId }.toSet()
        val out = Snapshot(snap.pages.filter { it.id in used }, picked)
        val sw = java.io.StringWriter()
        HarBuilder.write(out, sw)
        return ViewerHar(sw.toString(), picked.size, fromRecording)
    }

    private fun logSnapshot(): Snapshot {
        val recs = NetworkLog.snapshot()
        val pageUrl = recs.groupBy { it.navId }.mapValues { (_, l) -> (l.firstOrNull { it.isDocument } ?: l.first()).url }
        val pages = LinkedHashMap<Long, Page>()
        val list = recs.map { r ->
            val pg = pages.getOrPut(r.navId) {
                Page("page_${pages.size + 1}", "nav${r.navId}", pageUrl[r.navId] ?: r.url, r.startedAt, "")
            }
            Entry(r, pg.id, r.startedAt)
        }
        return Snapshot(pages.values.toList(), list)
    }

    private val IMG_EXT = Regex("\\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val MEDIA_EXT = Regex("\\.(mp4|webm|m4a|mp3|ogg|wav|m3u8|mpd)(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val FONT_EXT = Regex("\\.(woff2?|ttf|otf|eot)(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val JS_EXT = Regex("\\.m?js(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val CSS_EXT = Regex("\\.css(\\?|#|$)", RegexOption.IGNORE_CASE)

    /** Same classification the Network tab uses for its type chips. */
    private fun panelType(r: NetworkLog.Record): String {
        val ct = r.contentType?.lowercase().orEmpty()
        val u = r.url
        return when {
            ct.startsWith("image/") || IMG_EXT.containsMatchIn(u) -> "img"
            ct.startsWith("video/") || ct.startsWith("audio/") || MEDIA_EXT.containsMatchIn(u) -> "media"
            ct.startsWith("font/") || ct.contains("woff") || FONT_EXT.containsMatchIn(u) -> "font"
            ct.contains("wasm") || Regex("\\.wasm(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(u) -> "wasm"
            ct.contains("html") -> "doc"
            ct.contains("javascript") || ct.contains("ecmascript") || JS_EXT.containsMatchIn(u) -> "js"
            ct.contains("css") || CSS_EXT.containsMatchIn(u) -> "css"
            ct.contains("json") || ct.contains("xml") || ct.startsWith("text/") -> "data"
            else -> "other"
        }
    }

    private fun matchesType(r: NetworkLog.Record, type: String): Boolean = when (type) {
        "doc" -> r.isDocument || panelType(r) == "doc"
        "fetch", "xhr" -> r.kind == type
        "failed" -> r.isFailure
        "err" -> r.isFailure
        "nav" -> r.isDocument && r.navId != 0L
        "slow" -> !r.pending && r.durationMs > 1000
        else -> panelType(r) == type
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

                if (state != State.RECORDING) return
                if (HarOptions.stopWhenFull && isFull(weight(record))) {
                    state = State.PAUSED
                    full = true
                    publish()
                    return
                }
                val started = if (record.startedAt > 0) record.startedAt
                else System.currentTimeMillis() - record.durationMs
                entries[key] = Entry(record, navPages[record.navId] ?: currentPageId(), started)
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

    suspend fun flush(): File? = withContext(io) { writeCache() }

    private fun prepare(r: NetworkLog.Record): NetworkLog.Record? {
        val kind = HarOptions.Kind.from(r.kind, r.contentType)
        if (!HarOptions.recordKind(kind)) return null
        val host = DevToolsPrefs.siteKey(r.url)

        if (r.capture == "native") {
            if (r.isDocument && !DevToolsPrefs.on(DevToolsPrefs.Flag.REC_DOCS, host)) return null
            if (r.redirectHop && !DevToolsPrefs.on(DevToolsPrefs.Flag.REC_REDIRECTS, host)) return null
        }
        if (r.isFailure && r.capture == "native" && !DevToolsPrefs.on(DevToolsPrefs.Flag.REC_FAILED, host)) return null
        if (!HarOptions.thirdParty && !sameSite(r.url, currentUrl)) return null
        if (!allowedBySiteOnly(r.url)) return null
        if (HarOptions.isExcluded(r.url)) return null

        var body = r.body
        var harBody = r.harBody
        if (r.harBase64) {
            val isImage = kind == HarOptions.Kind.IMAGE
            val allowed = if (isImage) HarOptions.images else HarOptions.fonts
            val limitMb = if (isImage) HarOptions.imageLimitMb else HarOptions.fontLimitMb

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

        val reqBody = if (HarOptions.requestBodies) {
            val cap = HarOptions.capChars(HarOptions.requestBodyLimitMb)
            if (r.requestBody.length > cap) r.requestBody.substring(0, cap) else r.requestBody
        } else ""

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

    private fun siteOf(url: String): String {
        val host = (try { Uri.parse(url).host } catch (_: Exception) { null } ?: return "").lowercase()
        if (host.all { it.isDigit() || it == '.' }) return host
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val last = parts[parts.size - 1]
        val second = parts[parts.size - 2]
        val take = if (last.length == 2 && second in SECOND_LEVEL) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }

    private fun isFull(extra: Long): Boolean =
        entries.size >= HarOptions.maxEntries || totalChars + extra > HarOptions.maxTotalBytes()

    private fun resetSession() {
        _fileBytes.value = 0L
        pages.clear()
        entries.clear()
        navPages.clear()
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
        if (HarOptions.stopWhenFull) return
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

        if (owned != null && owned != target) owned.delete()
        ownedFile = target
        _fileBytes.value = target.length()
        return target
    }
}
