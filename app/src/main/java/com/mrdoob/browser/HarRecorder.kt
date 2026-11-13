package com.mrdoob.browser

import android.content.Context
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
 * The session is mirrored to `cacheDir/har/<name>.har`. All file work runs on one
 * serial thread, so writes and deletes can never interleave.
 */
object HarRecorder {

    enum class State { IDLE, RECORDING, PAUSED }

    data class Status(
        val state: State = State.IDLE,
        val entries: Int = 0,
        val pages: Int = 0,
        val fileName: String? = null
    )

    data class Page(val id: String, val key: String, val url: String, val startedAt: Long, val title: String)
    data class Entry(val record: NetworkLog.Record, val pageId: String?, val startedAt: Long)
    class Snapshot(val pages: List<Page>, val entries: List<Entry>)

    private const val MAX_ENTRIES = 3000
    private const val MAX_CHARS = 24_000_000L // cap on stored body text, keeps long sessions from OOMing
    private const val FLUSH_DELAY_MS = 3000L

    val isRecording: Boolean get() = state == State.RECORDING

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "har-io").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // Guarded by `lock`.
    @Volatile private var state = State.IDLE
    private val pages = LinkedHashMap<String, Page>()
    private val entries = LinkedHashMap<String, Entry>()
    private var totalChars = 0L
    private var seq = 0
    private var currentUrl: String? = null // latest page, tracked even while not recording
    private var currentKey: String? = null
    private var flushJob: Job? = null

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
                    currentUrl?.let { registerPage(it) }
                }
                State.IDLE -> return
            }
            publish()
        }
        scheduleFlush()
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
            if (state == State.RECORDING) {
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

    fun onRecord(record: NetworkLog.Record) {
        if (state == State.IDLE) return
        synchronized(lock) {
            val key = record.requestId ?: "n${seq++}"
            val old = entries[key]
            if (old == null) {
                // Paused: only requests that were already in flight may still complete.
                if (state != State.RECORDING) return
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

    // --- internals -----------------------------------------------------------------

    private fun resetSession() {
        pages.clear()
        entries.clear()
        totalChars = 0
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
        val iter = entries.entries.iterator()
        while ((entries.size > MAX_ENTRIES || totalChars > MAX_CHARS) && iter.hasNext()) {
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
            else HarBuilder.fileBase(pages.values.map { it.url }) + ".har"
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
        return target
    }
}
