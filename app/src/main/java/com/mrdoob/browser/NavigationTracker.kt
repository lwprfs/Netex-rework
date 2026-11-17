package com.mrdoob.browser

import android.content.Context
import android.webkit.WebResourceRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

object NavigationTracker {

    enum class Type { LOAD, RELOAD, REDIRECT, HISTORY }
    enum class Status { STARTED, COMPLETED, FAILED, CANCELLED }
    enum class Level { NONE, WARNING, PROTECTION, STRONG }
    enum class Kind { SAME_URL, SAME_HOST, CYCLE, REDIRECT_CYCLE }

    class Hop(val url: String, val time: Long, val redirect: Boolean, val method: String, val headers: List<Pair<String, String>>)

    class Seq(val navId: Long, val url: String, val time: Long, val type: Type, val userInitiated: Boolean)

    class LoopState(
        val level: Level, val kind: Kind, val url: String, val count: Int,
        val windowMs: Long, val sequence: List<Seq>
    )

    class Navigation(
        val id: Long, var url: String, val startedAt: Long,
        var type: Type, var inferred: Boolean, val userInitiated: Boolean
    ) {
        var status = Status.STARTED
        var finishedAt = 0L
        var error = ""
        var httpStatus = 0
        val hops = ArrayList<Hop>()
        var redirects = 0
        var nativeRequests = 0
        var subframeDocs = 0
        var urlChanges = 0
        var pageStarts = 0
        var chainReported = false
        var restored = false
    }

    class Started(val navId: Long, val type: Type, val first: Boolean)

    private class Meta(val url: String, val isRedirect: Boolean, val hasGesture: Boolean, val time: Long)
    private class Auto(val time: Long, val key: String, val host: String, val navId: Long, val url: String)

    private const val USER_NOTE_MS = 2500L
    private const val ADOPT_MS = 20_000L
    private const val QUIET_RESET_MS = 60_000L

    private val lock = Any()
    private val history = ArrayDeque<Navigation>()
    private var active: Navigation? = null
    private var nextId = 1L
    private var lastCommittedUrl: String? = null
    private var pendingMeta: Meta? = null
    private var userNoteKind: String? = null
    private var userNoteAt = 0L
    private val auto = ArrayDeque<Auto>()
    private val loopLevels = HashMap<String, Level>()
    private var lastLoop: LoopState? = null
    private var lastLoopAt = 0L
    private val urlChangeTimes = ArrayDeque<Long>()
    private var lastChurnSignalAt = 0L

    @Volatile var holding = false

    private fun tracking(): Boolean = DevToolsPrefs.on(DevToolsPrefs.Flag.NAV_TRACKING)

    fun key(url: String): String {
        val u = url.substringBefore('#')
        return if (u.length > 1 && u.endsWith("/") && !u.endsWith("://")) u.dropLast(1) else u
    }

    private fun pathOf(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return ""
        val rest = url.substring(i + 3)
        val s = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (s < 0) return "/"
        val tail = rest.substring(s)
        return if (tail[0] == '/') tail.substringBefore('?').substringBefore('#') else "/"
    }

    fun isRootPage(url: String): Boolean = pathOf(url).let { it.isEmpty() || it == "/" }

    fun noteUserAction(kind: String) {
        synchronized(lock) { userNoteKind = kind; userNoteAt = System.currentTimeMillis() }
        if (kind == "stop") cancelActive()
    }

    fun cancelActive() {
        synchronized(lock) {
            val a = active ?: return
            if (a.status == Status.STARTED) { a.status = Status.CANCELLED; a.finishedAt = System.currentTimeMillis() }
        }
    }

    fun onShouldOverride(url: String, isMain: Boolean, isRedirect: Boolean, hasGesture: Boolean): Boolean {
        if (!isMain) return false
        val now = System.currentTimeMillis()
        val refuse: Boolean
        synchronized(lock) {
            pendingMeta = Meta(url, isRedirect, hasGesture, now)
            refuse = holding && !hasGesture && !(isRedirect && active?.userInitiated == true)
        }
        if (refuse) {
            DevToolsEvents.record(
                DevToolsEvents.Type.INFINITE_RELOAD, url, DevToolsEvents.Severity.INFO,
                "Automatic navigation refused while navigation is on hold",
                listOf("Refused navigation")
            )
        }
        return refuse
    }

    fun onIntercept(request: WebResourceRequest) {
        try {
            if (!tracking()) return
            interceptInternal(request)
        } catch (_: Exception) {  }
    }

    private fun interceptInternal(request: WebResourceRequest) {
        val now = System.currentTimeMillis()
        if (!request.isForMainFrame) {
            synchronized(lock) {
                val a = active ?: return
                a.nativeRequests++
                val accept = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"]
                if (accept != null && accept.contains("text/html")) a.subframeDocs++
            }
            return
        }
        val url = request.url.toString()
        if (!NavigationGuard.isWebUrl(url)) return
        val method = request.method ?: "GET"
        val headers = request.requestHeaders.map { it.key to it.value }

        var begun: Navigation? = null
        var chainLoop = false
        var hopCount = 0
        lateinit var nav: Navigation
        lateinit var hop: Hop
        var prevHop: Hop? = null
        synchronized(lock) {
            val a = active
            val sameAlready = a != null && a.hops.isNotEmpty() && a.hops.any { key(it.url) == key(url) } && !request.isRedirect
            val adopt = a != null && a.status == Status.STARTED && now - a.startedAt < ADOPT_MS && !sameAlready &&
                (request.isRedirect || key(a.url) == key(url))
            nav = if (adopt) a!! else {
                cancelActiveLocked(now)
                beginLocked(url, now, request.hasGesture()).also { begun = it }
            }
            val redirect = adopt && request.isRedirect
            prevHop = nav.hops.lastOrNull()
            hop = Hop(url, now, redirect, method, headers)
            nav.hops.add(hop)
            nav.nativeRequests++
            if (redirect) {
                nav.url = url
                nav.redirects++
                val revisits = nav.hops.dropLast(1).any { key(it.url) == key(url) }
                if (revisits && !nav.chainReported) { nav.chainReported = true; chainLoop = true }
            }
            hopCount = nav.hops.size
        }
        begun?.let { afterBegin(it) }
        prevHop?.let { emitDoc(nav, it, pending = false, end = now) }
        emitDoc(nav, hop, pending = true, end = 0)

        if (hop.redirect) {
            val host = DevToolsPrefs.siteKey(url)
            if (DevToolsPrefs.on(DevToolsPrefs.Flag.REDIRECT_MONITOR, host)) {
                if (chainLoop) {
                    val seq = synchronized(lock) { nav.hops.map { Seq(nav.id, it.url, it.time, Type.REDIRECT, false) } }
                    ProtectionEngine.onLoop(LoopState(Level.PROTECTION, Kind.REDIRECT_CYCLE, url, hopCount, now - nav.startedAt, seq))
                } else if (hopCount >= 6 && hopCount % 6 == 0) {
                    DevToolsEvents.record(
                        DevToolsEvents.Type.REDIRECT_LOOP, url, DevToolsEvents.Severity.WARNING,
                        "Redirect chain with $hopCount hops", listOf("Recorded redirect chain")
                    )
                }
            }
        }
    }

    fun onPageStarted(url: String?): Started? {
        if (url == null || !NavigationGuard.isWebUrl(url) || !tracking()) return null
        val now = System.currentTimeMillis()
        var begun: Navigation? = null
        val nav: Navigation
        val first: Boolean
        synchronized(lock) {
            val a = active
            val adopt = a != null && a.status == Status.STARTED && now - a.startedAt < ADOPT_MS &&
                (key(a.url) == key(url) || a.hops.any { key(it.url) == key(url) })
            nav = if (adopt) a!! else {
                cancelActiveLocked(now)
                beginLocked(url, now, false).also { begun = it }
            }
            nav.pageStarts++
            first = nav.pageStarts == 1
            if (nav.url != url && nav.hops.none { key(it.url) == key(url) }) nav.url = url
        }
        begun?.let { afterBegin(it) }
        return Started(nav.id, nav.type, first)
    }

    fun onPageFinished(url: String?) {
        if (url == null || !tracking()) return
        val now = System.currentTimeMillis()
        var done: Navigation? = null
        var lastHop: Hop? = null
        synchronized(lock) {
            val n = findStartedLocked(url) ?: return
            n.status = Status.COMPLETED
            n.finishedAt = now
            lastCommittedUrl = url
            done = n
            lastHop = n.hops.lastOrNull()
        }
        val n = done ?: return
        lastHop?.let { emitDoc(n, it, pending = false, end = now) }
        DevToolsEvents.record(
            DevToolsEvents.Type.NAV_COMPLETED, n.url, DevToolsEvents.Severity.INFO, "Main-frame navigation completed"
        )
    }

    fun onRequestFailed(request: WebResourceRequest, status: Int, error: String) {
        try {
            if (!tracking()) return
            val url = request.url.toString()
            val isMain = request.isForMainFrame
            var navId = 0L
            if (isMain) {
                synchronized(lock) {
                    val n = findStartedLocked(url) ?: history.lastOrNull { key(it.url) == key(url) }
                    if (n != null) {
                        navId = n.id
                        if (status > 0) n.httpStatus = status
                        if (error.isNotEmpty()) { n.error = error; n.status = Status.FAILED; n.finishedAt = System.currentTimeMillis() }
                    }
                }
                if (error.isNotEmpty()) {
                    DevToolsEvents.record(
                        DevToolsEvents.Type.NAV_FAILED, url, DevToolsEvents.Severity.WARNING, "Navigation failed: $error"
                    )
                }
            }
            NetworkLog.addNativeStatus(
                navId, request.method ?: "GET", url, status, failed = error.isNotEmpty(), error = error,
                requestHeaders = request.requestHeaders.map { it.key to it.value }, responseHeaders = emptyList(),
                isDocument = isMain
            )
        } catch (_: Exception) {  }
    }

    fun onVisited(url: String?, isReload: Boolean) {
        if (url == null || !NavigationGuard.isWebUrl(url) || !tracking()) return
        var churnHost: String? = null
        synchronized(lock) {
            val a = active ?: return
            if (isReload && a.type != Type.RELOAD) { a.type = Type.RELOAD; a.inferred = false }
            if (a.status == Status.COMPLETED && key(url) != key(a.url)) {
                a.urlChanges++
                lastCommittedUrl = url
                val now = System.currentTimeMillis()
                urlChangeTimes.addLast(now)
                while (urlChangeTimes.isNotEmpty() && now - urlChangeTimes.first > 10_000) urlChangeTimes.removeFirst()
                if (urlChangeTimes.size >= 30 && now - lastChurnSignalAt > 60_000) {
                    lastChurnSignalAt = now
                    churnHost = DevToolsPrefs.siteKey(url)
                }
            }
        }
        churnHost?.let { ProtectionEngine.onHistoryChurn(it, url) }
    }

    fun onRenderGone(didCrash: Boolean, url: String?) {
        synchronized(lock) {
            val a = active
            if (a != null && a.status == Status.STARTED) {
                a.status = Status.FAILED
                a.error = if (didCrash) "Renderer crashed" else "Renderer was killed"
                a.finishedAt = System.currentTimeMillis()
            }
        }
        ProtectionEngine.onRenderGone(didCrash, url)
    }

    private fun cancelActiveLocked(now: Long) {
        val a = active ?: return
        if (a.status == Status.STARTED) { a.status = Status.CANCELLED; a.finishedAt = now }
    }

    private fun findStartedLocked(url: String): Navigation? {
        val it = history.descendingIterator()
        while (it.hasNext()) {
            val n = it.next()
            if (n.status == Status.STARTED && (key(n.url) == key(url) || n.hops.any { h -> key(h.url) == key(url) })) return n
        }
        return null
    }

    private fun beginLocked(url: String, now: Long, gesture: Boolean): Navigation {
        val meta = pendingMeta?.takeIf { now - it.time < 3000 && key(it.url) == key(url) }
        val note = userNoteKind?.takeIf { now - userNoteAt < USER_NOTE_MS }
        val userInit = note != null || gesture || meta?.hasGesture == true
        var inferred = false
        val type = when {
            note == "reload" -> Type.RELOAD
            note == "back" || note == "forward" -> Type.HISTORY
            meta?.isRedirect == true -> Type.REDIRECT
            note == null && lastCommittedUrl?.let { key(it) == key(url) } == true -> { inferred = true; Type.RELOAD }
            else -> Type.LOAD
        }
        if (note != null) userNoteKind = null
        pendingMeta = null
        val nav = Navigation(nextId++, url, now, type, inferred, userInit)
        history.addLast(nav)
        val max = DevToolsPrefs.num(DevToolsPrefs.Num.MAX_NAV_HISTORY)
        while (history.size > max) history.removeFirst()
        active = nav
        return nav
    }

    private fun afterBegin(nav: Navigation) {
        val host = DevToolsPrefs.siteKey(nav.url)
        DevToolsPrefs.currentHost = host
        HarRecorder.onNavigation(nav.id, nav.url)
        DevToolsEvents.record(
            DevToolsEvents.Type.NAV_STARTED, nav.url, DevToolsEvents.Severity.INFO,
            "Main-frame navigation (${nav.type.name.lowercase()}${if (nav.inferred) ", inferred" else ""})"
        )
        val prev = synchronized(lock) { lastCommittedUrl }
        evaluateLoop(nav, host)?.let { ProtectionEngine.onLoop(it) }
        ProtectionEngine.onNavigationBegun(nav.id, nav.url, prev, host, nav.userInitiated, nav.type)
    }

    private fun emitDoc(nav: Navigation, hop: Hop, pending: Boolean, end: Long) {
        val timing = DevToolsPrefs.on(DevToolsPrefs.Flag.REC_TIMING)
        NetworkLog.add(
            NetworkLog.Record(
                method = hop.method, url = hop.url, status = 0, body = "", contentType = null,
                durationMs = if (timing && !pending && end > hop.time) end - hop.time else 0,
                requestId = NetworkLog.docId(nav.id, hop.url), pending = pending, startedAt = hop.time,
                requestHeaders = hop.headers, kind = "document", navId = nav.id, capture = "native",
                redirectHop = hop.redirect
            )
        )
    }

    private fun evaluateLoop(nav: Navigation, host: String?): LoopState? {
        if (!DevToolsPrefs.on(DevToolsPrefs.Flag.LOOP_DETECT, host)) return null
        val now = nav.startedAt
        val thresholds = listOf(
            Triple(Level.WARNING, DevToolsPrefs.num(DevToolsPrefs.Num.WARN_COUNT), DevToolsPrefs.num(DevToolsPrefs.Num.WARN_SECS) * 1000L),
            Triple(Level.PROTECTION, DevToolsPrefs.num(DevToolsPrefs.Num.PROTECT_COUNT), DevToolsPrefs.num(DevToolsPrefs.Num.PROTECT_SECS) * 1000L),
            Triple(Level.STRONG, DevToolsPrefs.num(DevToolsPrefs.Num.STRONG_COUNT), DevToolsPrefs.num(DevToolsPrefs.Num.STRONG_SECS) * 1000L)
        )
        synchronized(lock) {
            val key = key(nav.url)
            if (nav.userInitiated) {

                auto.removeAll { it.key == key }
                loopLevels.remove(key)
                loopLevels.remove("host:" + (host ?: ""))
                return null
            }
            if (auto.isNotEmpty() && now - auto.last.time > QUIET_RESET_MS) loopLevels.clear()
            auto.addLast(Auto(now, key, host ?: "", nav.id, nav.url))
            while (auto.size > 64 || (auto.isNotEmpty() && now - auto.first.time > 120_000)) auto.removeFirst()

            var level = Level.NONE
            var kind = Kind.SAME_URL
            var count = 0
            var window = 0L
            var loopKey = key

            val same = auto.filter { it.key == key }
            for ((lvl, c, ms) in thresholds) {
                val n = same.count { now - it.time <= ms }
                if (n >= c) { level = lvl; count = n; window = ms }
            }

            if (host != null) {
                val sameHost = auto.filter { it.host == host }
                for ((lvl, c, ms) in thresholds) {
                    val inWin = sameHost.filter { now - it.time <= ms }
                    if (inWin.size >= c * 2 && inWin.map { it.key }.toSet().size >= 2 && lvl.ordinal > level.ordinal) {
                        level = lvl; kind = Kind.SAME_HOST; count = inWin.size; window = ms; loopKey = "host:$host"
                    }
                }
            }

            val recent = auto.filter { now - it.time <= 60_000 }.map { it.key }
            for (len in 2..4) {
                if (recent.size < len * 3) continue
                val tail = recent.takeLast(len)
                if (tail.toSet().size != len) continue
                var ok = true
                for (i in 0 until len * 2) if (recent[recent.size - 1 - i] != recent[recent.size - 1 - i - len]) { ok = false; break }
                if (ok && Level.PROTECTION.ordinal > level.ordinal) {
                    level = Level.PROTECTION; kind = Kind.CYCLE; count = len * 3; window = 60_000; loopKey = "cycle:" + tail.joinToString(">")
                }
            }

            if (level == Level.NONE) return null
            val old = loopLevels[loopKey] ?: Level.NONE
            if (level.ordinal <= old.ordinal) return null
            loopLevels[loopKey] = level
            val seq = history.toList().takeLast(12).map { Seq(it.id, it.url, it.startedAt, it.type, it.userInitiated) }
            return LoopState(level, kind, nav.url, count, window, seq).also { lastLoop = it; lastLoopAt = now }
        }
    }

    fun loopIndicator(): String = synchronized(lock) {
        val l = lastLoop
        if (l != null && System.currentTimeMillis() - lastLoopAt < QUIET_RESET_MS) l.level.name.lowercase() else "none"
    }

    fun dismissLoop() = synchronized(lock) { lastLoop = null }

    fun currentId(): Long = synchronized(lock) { active?.id ?: 0L }

    fun navIdAt(time: Long): Long = synchronized(lock) {
        val it = history.descendingIterator()
        while (it.hasNext()) {
            val n = it.next()
            if (n.startedAt <= time + 50) return n.id
        }
        active?.id ?: 0L
    }

    fun recentAutomatic(host: String, withinMs: Long): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        auto.count { it.host == host && now - it.time <= withinMs }
    }

    fun clearHistory() = synchronized(lock) {
        history.clear(); auto.clear(); loopLevels.clear(); lastLoop = null
        active?.let { history.addLast(it) }
    }

    fun snapshotJson(): String = synchronized(lock) {
        val counts = HashMap<String, Int>()
        history.forEach { counts.merge(key(it.url), 1) { a, b -> a + b } }
        val arr = JSONArray()
        for (n in history) {
            val hops = JSONArray()
            n.hops.forEach { hops.put(JSONObject().put("url", it.url).put("redirect", it.redirect)) }
            arr.put(
                JSONObject()
                    .put("id", n.id).put("url", n.url).put("type", n.type.name.lowercase())
                    .put("inferred", n.inferred).put("user", n.userInitiated)
                    .put("status", n.status.name.lowercase()).put("started", n.startedAt)
                    .put("finished", n.finishedAt).put("error", n.error).put("http", n.httpStatus)
                    .put("native", n.nativeRequests).put("frames", n.subframeDocs).put("changes", n.urlChanges)
                    .put("repeat", counts[key(n.url)] ?: 1).put("hops", hops).put("restored", n.restored)
            )
        }
        val seq = JSONArray()
        lastLoop?.sequence?.forEach { seq.put(JSONObject().put("id", it.navId).put("url", it.url).put("type", it.type.name.lowercase())) }
        JSONObject()
            .put("tracking", tracking()).put("navs", arr).put("holding", holding)
            .put("loop", JSONObject().put("level", loopIndicator()).put("kind", lastLoop?.kind?.name ?: "").put("sequence", seq))
            .toString()
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nav-io").apply { isDaemon = true } }
    private fun file(context: Context) = File(context.filesDir, "netex_navhistory.json")

    fun save(context: Context) {
        val app = context.applicationContext
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (n in history) {
                arr.put(
                    JSONObject().put("id", n.id).put("url", n.url).put("t", n.startedAt).put("f", n.finishedAt)
                        .put("type", n.type.name).put("inf", n.inferred).put("user", n.userInitiated)
                        .put("st", n.status.name).put("err", n.error).put("nr", n.nativeRequests)
                        .put("hops", JSONArray(n.hops.map { it.url }))
                )
            }
            arr.toString()
        }
        io.execute {
            try {
                val f = file(app)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (_: Exception) {  }
        }
    }

    fun deletePersisted(context: Context) {
        val app = context.applicationContext
        io.execute { try { file(app).delete() } catch (_: Exception) {} }
    }

    fun restore(context: Context) {
        val f = file(context.applicationContext)
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            synchronized(lock) {
                if (history.isNotEmpty()) return
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = try { Type.valueOf(o.optString("type")) } catch (_: Exception) { Type.LOAD }
                    val n = Navigation(o.optLong("id"), o.optString("url"), o.optLong("t"), type, o.optBoolean("inf"), o.optBoolean("user"))
                    n.status = try { Status.valueOf(o.optString("st")) } catch (_: Exception) { Status.COMPLETED }
                    if (n.status == Status.STARTED) n.status = Status.CANCELLED
                    n.finishedAt = o.optLong("f")
                    n.error = o.optString("err")
                    n.nativeRequests = o.optInt("nr")
                    n.restored = true
                    val hops = o.optJSONArray("hops")
                    if (hops != null) for (j in 0 until hops.length()) n.hops.add(Hop(hops.optString(j), n.startedAt, j > 0, "GET", emptyList()))
                    history.addLast(n)
                    if (n.id >= nextId) nextId = n.id + 1
                }
            }
        } catch (_: Exception) {  }
    }
}
