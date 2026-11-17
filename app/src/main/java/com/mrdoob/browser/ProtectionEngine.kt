package com.mrdoob.browser

import android.content.Context
import android.webkit.JavascriptInterface
import com.mrdoob.browser.DevToolsPrefs.Flag
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ProtectionEngine {

    enum class Confidence { NONE, LOW, MEDIUM, HIGH }

    sealed interface Ui {
        data class Notice(val text: String, val host: String, val detailsAction: Boolean) : Ui
        data class LoopWarning(val state: NavigationTracker.LoopState, val host: String, val level: NavigationTracker.Level) : Ui
        data class ReinitBridge(val navId: Long) : Ui
    }

    private val _ui = MutableSharedFlow<Ui>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val ui = _ui.asSharedFlow()

    @Volatile private var appContext: Context? = null

    @Volatile var panelOpen = false
        private set
    @Volatile private var panelOpenedAt = 0L

    fun init(context: Context) { appContext = context.applicationContext }

    fun setPanelOpen(open: Boolean) {
        if (open == panelOpen) return
        panelOpen = open
        if (open) panelOpenedAt = System.currentTimeMillis()
    }

    private fun autoAllowed(host: String, rule: DevToolsPrefs.Flag): Boolean =
        DevToolsPrefs.on(DevToolsPrefs.Flag.AUTO_MASTER, host) && DevToolsPrefs.on(rule, host)

    private fun act(host: String, action: DevToolsPrefs.Flag): Boolean = DevToolsPrefs.on(action, host)

    private fun notify(host: String, text: String, details: Boolean) {
        if (DevToolsPrefs.on(DevToolsPrefs.Flag.NOTIFY, host)) _ui.tryEmit(Ui.Notice(text, host, details))
    }

    fun onLoop(state: NavigationTracker.LoopState) {
        val host = DevToolsPrefs.siteKey(state.url) ?: return
        val redirect = state.kind == NavigationTracker.Kind.REDIRECT_CYCLE
        val type = if (redirect || state.kind == NavigationTracker.Kind.CYCLE) DevToolsEvents.Type.REDIRECT_LOOP
        else DevToolsEvents.Type.INFINITE_RELOAD
        val rule = if (redirect) DevToolsPrefs.Flag.AUTO_RULE_REDIRECT else DevToolsPrefs.Flag.AUTO_RULE_RELOAD
        val actions = ArrayList<String>()
        actions += "Network log and navigation history kept"

        if (state.level.ordinal >= NavigationTracker.Level.PROTECTION.ordinal && autoAllowed(host, rule)) {
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_PRESERVE)) {
                actions += DevToolsPrefs.activate(
                    host,
                    listOf(DevToolsPrefs.Flag.PRESERVE_NAV, DevToolsPrefs.Flag.PRESERVE_RELOAD, DevToolsPrefs.Flag.PRESERVE_REDIRECT),
                    "Navigation loop"
                ).map { "Enabled: $it" }
            }
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_TRACKING)) {
                actions += DevToolsPrefs.activate(
                    host, listOf(DevToolsPrefs.Flag.NAV_TRACKING, DevToolsPrefs.Flag.LOOP_DETECT, DevToolsPrefs.Flag.REDIRECT_MONITOR),
                    "Navigation loop"
                ).map { "Enabled: $it" }
            }
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_RECORDING) && DevToolsPrefs.on(DevToolsPrefs.Flag.REC_PROTECT, host)) {
                if (HarRecorder.protectSession("loop")) actions += "Recording saved to a protected copy"
            }
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_HISTORY) && DevToolsPrefs.on(DevToolsPrefs.Flag.NAV_HISTORY, host)) {
                appContext?.let { NavigationTracker.save(it); actions += "Navigation history saved" }
            }
        }

        if (state.level.ordinal >= NavigationTracker.Level.PROTECTION.ordinal &&
            DevToolsPrefs.on(DevToolsPrefs.Flag.REC_AUTO_EXPORT, host)
        ) {
            if (HarRecorder.exportToDownloads(appContext)) actions += "Recording exported to Downloads"
        }

        val sev = when (state.level) {
            NavigationTracker.Level.WARNING -> DevToolsEvents.Severity.WARNING
            else -> DevToolsEvents.Severity.CRITICAL
        }
        val what = when (state.kind) {
            NavigationTracker.Kind.SAME_URL -> "${state.count} automatic loads of the same URL in ${state.windowMs / 1000}s"
            NavigationTracker.Kind.SAME_HOST -> "${state.count} automatic navigations on one site in ${state.windowMs / 1000}s"
            NavigationTracker.Kind.CYCLE -> "Navigation is cycling between ${state.count / 3} URLs"
            NavigationTracker.Kind.REDIRECT_CYCLE -> "Redirect chain returned to a URL it already visited (${state.count} hops)"
        }
        DevToolsEvents.record(type, state.url, sev, "${state.level.name.lowercase().replaceFirstChar { it.uppercase() }}: $what", actions)

        if (state.level == NavigationTracker.Level.WARNING) {
            notify(host, "Page keeps reloading: $what", true)
        } else if (DevToolsPrefs.on(DevToolsPrefs.Flag.LOOP_PROMPT, host)) {
            _ui.tryEmit(Ui.LoopWarning(state, host, state.level))
        } else {
            notify(host, "Navigation loop detected: $what", true)
        }
    }

    fun hold(url: String?) {
        NavigationTracker.holding = true
        DevToolsEvents.record(
            DevToolsEvents.Type.INFINITE_RELOAD, url, DevToolsEvents.Severity.INFO,
            "Automatic navigation stopped by the user", listOf("Navigation on hold")
        )
    }

    fun release() {
        NavigationTracker.holding = false
        NavigationTracker.dismissLoop()
    }

    private val contextLosses = ArrayList<Long>()

    private fun countContextLoss(): Int = synchronized(contextLosses) {
        val now = System.currentTimeMillis()
        contextLosses.add(now)
        contextLosses.removeAll { now - it > 60_000 }
        contextLosses.size
    }

    fun onRenderGone(didCrash: Boolean, url: String?) {
        val host = DevToolsPrefs.siteKey(url) ?: DevToolsPrefs.currentHost ?: ""
        val n = countContextLoss()
        val actions = arrayListOf("Native navigation tracking continues", "Network log kept")
        if (host.isNotEmpty() && autoAllowed(host, DevToolsPrefs.Flag.AUTO_RULE_CONTEXT)) {
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_PRESERVE)) {
                actions += DevToolsPrefs.activate(host, listOf(DevToolsPrefs.Flag.PRESERVE_CONTEXT, DevToolsPrefs.Flag.PRESERVE_NAV), "Renderer lost")
                    .map { "Enabled: $it" }
            }
        }
        if (HarRecorder.isRecording && DevToolsPrefs.on(DevToolsPrefs.Flag.REC_PROTECT, host.ifEmpty { null })) {
            if (HarRecorder.protectSession("renderer")) actions += "Recording saved to a protected copy"
        }
        DevToolsEvents.record(
            DevToolsEvents.Type.CONTEXT_DESTROYED, url, DevToolsEvents.Severity.CRITICAL,
            (if (didCrash) "WebView renderer crashed" else "WebView renderer was killed") + if (n > 1) " ($n times in a minute)" else "",
            actions
        )
        if (HarRecorder.isRecording || HarRecorder.status.value.entries > 0) {
            DevToolsEvents.record(
                DevToolsEvents.Type.RECORDING_INTERRUPTED, url, DevToolsEvents.Severity.WARNING,
                "Page was lost while a recording was running",
                listOf(if (DevToolsPrefs.on(DevToolsPrefs.Flag.REC_RECOVER, host.ifEmpty { null })) "Recording continues in the new page" else "Recording paused")
            )
        }
        if (host.isNotEmpty()) notify(host, "The page process was lost. Logs and recording were kept.", true)
    }

    private val helloNav = ConcurrentHashMap<Long, Boolean>()
    @Volatile private var bridgeDown = false
    private val reinitTried = ConcurrentHashMap.newKeySet<Long>()

    fun onBridgeHello(isTop: Boolean) {
        if (!isTop) return
        helloNav[NavigationTracker.currentId()] = true
        if (helloNav.size > 50) helloNav.clear()
        if (bridgeDown) {
            bridgeDown = false
            DevToolsEvents.record(
                DevToolsEvents.Type.BRIDGE_RESTORED, null, DevToolsEvents.Severity.INFO, "Page bridge is delivering messages again"
            )
        }
    }

    fun checkBridge(navId: Long, url: String?) {
        if (navId == 0L || helloNav[navId] == true) return
        val host = DevToolsPrefs.siteKey(url) ?: return
        if (!DevToolsPrefs.on(DevToolsPrefs.Flag.NAV_TRACKING, host)) return
        val actions = arrayListOf("Native tracking and network monitoring continue")
        val n = countContextLoss()
        if (!bridgeDown) {
            bridgeDown = true
            if (autoAllowed(host, DevToolsPrefs.Flag.AUTO_RULE_CONTEXT)) {
                if (act(host, DevToolsPrefs.Flag.AUTO_ACT_PRESERVE)) {
                    actions += DevToolsPrefs.activate(host, listOf(DevToolsPrefs.Flag.PRESERVE_CONTEXT), "Bridge lost").map { "Enabled: $it" }
                }
                if (act(host, DevToolsPrefs.Flag.AUTO_ACT_BRIDGE) && reinitTried.add(navId)) {
                    actions += "Page bridge re-initialised once"
                    _ui.tryEmit(Ui.ReinitBridge(navId))
                }
            }
            if (reinitTried.size > 50) reinitTried.clear()
            DevToolsEvents.record(
                DevToolsEvents.Type.BRIDGE_DISCONNECTED, url, DevToolsEvents.Severity.WARNING,
                "Page finished loading without contacting the network bridge (script context replaced or blocked)", actions
            )
        }
        if (n >= 3) {
            DevToolsEvents.record(
                DevToolsEvents.Type.CONTEXT_DESTROYED, url, DevToolsEvents.Severity.WARNING,
                "JavaScript context repeatedly unavailable ($n times in a minute)", actions
            )
        }
    }

    fun onCaptureFailure(reason: String, url: String?) {
        DevToolsEvents.record(DevToolsEvents.Type.CAPTURE_FAILURE, url, DevToolsEvents.Severity.WARNING, reason)
    }

    fun onRecreatedAfterCrash() {
        val host = DevToolsPrefs.currentHost
        if (HarRecorder.status.value.state != HarRecorder.State.IDLE) {
            if (DevToolsPrefs.on(DevToolsPrefs.Flag.REC_RECOVER, host)) HarRecorder.recoverSession()
            else HarRecorder.pauseIfRecording()
        }
    }

    private class Evidence(var count: Int = 0, var weight: Int = 1, var behavioral: Boolean = false, var detail: String = "")

    private class SiteDetection {
        val indicators = LinkedHashMap<String, Evidence>()
        var confidence = Confidence.NONE
        var lastUrl = ""
        var firstAt = System.currentTimeMillis()
        var homeRedirects = 0
        var reloadsAfterOpen = 0
    }

    private val detections = object : LinkedHashMap<String, SiteDetection>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SiteDetection>?) = size > 50
    }

    fun onPageSignal(obj: JSONObject) {
        val url = obj.optString("url")
        val host = DevToolsPrefs.siteKey(url) ?: return
        if (!DevToolsPrefs.on(DevToolsPrefs.Flag.ANTI_DETECT, host)) return
        val id = obj.optString("id").take(60).ifEmpty { return }
        val behavioral = obj.optBoolean("behavioral", false)
        if (behavioral && !DevToolsPrefs.on(DevToolsPrefs.Flag.ANTI_BEHAVIOR, host)) return
        if (!behavioral && !DevToolsPrefs.on(DevToolsPrefs.Flag.ANTI_SCAN, host) && !id.startsWith("debugger")) return
        record(host, url, id, obj.optInt("count", 1).coerceIn(1, 100000), obj.optInt("weight", 1).coerceIn(1, 3), behavioral, obj.optString("detail").take(200))
    }

    fun onHistoryChurn(host: String, url: String) {
        if (!DevToolsPrefs.on(DevToolsPrefs.Flag.ANTI_DETECT, host)) return
        record(host, url, "history-churn", 1, 1, false, "Over 30 URL changes in 10 seconds (history.pushState / replaceState)")
    }

    fun onNavigationBegun(navId: Long, url: String, from: String?, host: String?, userInitiated: Boolean, type: NavigationTracker.Type) {
        if (host == null || userInitiated) return
        if (!DevToolsPrefs.on(DevToolsPrefs.Flag.ANTI_DETECT, host) || !DevToolsPrefs.on(DevToolsPrefs.Flag.ANTI_BEHAVIOR, host)) return
        val now = System.currentTimeMillis()

        if (from != null && DevToolsPrefs.siteKey(from) == host && NavigationTracker.isRootPage(url) && !NavigationTracker.isRootPage(from)) {
            val n = synchronized(detections) { detections.getOrPut(host) { SiteDetection() }.let { it.homeRedirects++; it.homeRedirects } }
            if (n >= 3) record(host, url, "repeated-home-redirect", 1, 2, true, "Automatically sent to the homepage $n times")
        }

        val opened = panelOpenedAt
        if (panelOpen && opened > 0 && now - opened < 20_000) {
            val before = NavigationTracker.recentAutomatic(host, 60_000)
            val n = synchronized(detections) {
                val d = detections.getOrPut(host) { SiteDetection() }
                d.reloadsAfterOpen++
                d.reloadsAfterOpen
            }
            if (n >= 2 && before <= n + 1) {
                record(host, url, "reload-after-devtools", 1, 2, true, "Automatic navigation started right after the panel was opened ($n times)")
            }
        }
    }

    private fun record(host: String, url: String, id: String, count: Int, weight: Int, behavioral: Boolean, detail: String) {
        val snapshot: Pair<Confidence, Confidence>
        val summary: String
        synchronized(detections) {
            val d = detections.getOrPut(host) { SiteDetection() }
            d.lastUrl = url
            val e = d.indicators.getOrPut(id) { Evidence(0, weight, behavioral, detail) }
            e.count += count
            if (detail.isNotEmpty()) e.detail = detail
            val old = d.confidence
            d.confidence = score(d)
            snapshot = old to d.confidence
            summary = d.indicators.entries.joinToString("; ") { "${label(it.key)} x${it.value.count}" }
        }
        val (before, after) = snapshot
        if (after.ordinal <= before.ordinal) return

        val actions = ArrayList<String>()
        val type = if (id.startsWith("debugger")) DevToolsEvents.Type.DEBUGGER_SUSPICIOUS else DevToolsEvents.Type.ANTI_DEVTOOLS
        if (after.ordinal >= Confidence.MEDIUM.ordinal && autoAllowed(host, DevToolsPrefs.Flag.AUTO_RULE_ANTI)) {
            actions += "Detection details recorded"
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_PRESERVE)) {
                actions += DevToolsPrefs.activate(host, listOf(DevToolsPrefs.Flag.PRESERVE_NAV, DevToolsPrefs.Flag.PRESERVE_RELOAD, DevToolsPrefs.Flag.PRESERVE_REDIRECT), "Anti-DevTools indicators")
                    .map { "Enabled: $it" }
            }
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_TRACKING)) {
                actions += DevToolsPrefs.activate(host, listOf(DevToolsPrefs.Flag.NAV_TRACKING, DevToolsPrefs.Flag.LOOP_DETECT, DevToolsPrefs.Flag.REDIRECT_MONITOR), "Anti-DevTools indicators")
                    .map { "Enabled: $it" }
            }
            if (act(host, DevToolsPrefs.Flag.AUTO_ACT_COMPAT) && DevToolsPrefs.on(DevToolsPrefs.Flag.COMPAT_AUTO, host) &&
                DevToolsPrefs.global(DevToolsPrefs.Flag.COMPAT_ENABLED)
            ) {
                val done = DevToolsPrefs.activateCompat(host, "Anti-DevTools indicators")
                if (done.isNotEmpty()) actions += "Compatibility mode on for this session: ${done.joinToString()}"
            }
        }
        val sev = when (after) {
            Confidence.HIGH -> DevToolsEvents.Severity.CRITICAL
            Confidence.MEDIUM -> DevToolsEvents.Severity.WARNING
            else -> DevToolsEvents.Severity.INFO
        }
        DevToolsEvents.record(type, url, sev, "Confidence ${after.name.lowercase()}: $summary", actions)
        if (after.ordinal >= Confidence.MEDIUM.ordinal) {
            notify(host, "$host reacts to developer tools (${after.name.lowercase()} confidence)", true)
        }
    }

    private fun score(d: SiteDetection): Confidence {
        val kinds = d.indicators.size
        if (kinds == 0) return Confidence.NONE
        val behavioral = d.indicators.values.any { it.behavioral }
        val repeatedDebugger = d.indicators.entries.any {
            it.key.startsWith("debugger") && it.value.count >= DevToolsPrefs.num(DevToolsPrefs.Num.DEBUGGER_REPEAT)
        }
        return when {
            kinds >= 3 && behavioral -> Confidence.HIGH
            kinds >= 2 && repeatedDebugger -> Confidence.HIGH
            kinds >= 2 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
    }

    private fun label(id: String): String = when (id) {
        "debugger-dynamic" -> "debugger in dynamic code"
        "debugger-exec" -> "debugger statement executed"
        "debugger-inline" -> "debugger in inline script"
        "viewport-check" -> "viewport/window size comparison"
        "devtools-library" -> "known detection library"
        "console-trap" -> "console getter trap"
        "console-clear" -> "repeated console.clear()"
        "content-cleared" -> "page content removed"
        "repeated-home-redirect" -> "repeated homepage redirect"
        "reload-after-devtools" -> "reload after panel opened"
        "history-churn" -> "history manipulation"
        "timing-check" -> "debugger timing check"
        else -> id
    }

    fun detectionJson(host: String?): String {
        val o = JSONObject()
        val h = host ?: return o.put("found", false).toString()
        synchronized(detections) {
            val d = detections[h]
            if (d == null || d.confidence == Confidence.NONE) return o.put("found", false).put("host", h).toString()
            val arr = JSONArray()
            for ((id, e) in d.indicators) {
                arr.put(JSONObject().put("id", id).put("label", label(id)).put("count", e.count)
                    .put("behavioral", e.behavioral).put("detail", e.detail))
            }
            val behavior = d.indicators.entries.firstOrNull { it.value.behavioral }?.let { label(it.key) }
                ?: d.indicators.keys.firstOrNull()?.let { label(it) } ?: ""
            o.put("found", true).put("host", h).put("url", d.lastUrl).put("type", "Anti-DevTools Detection")
                .put("confidence", d.confidence.name.lowercase()).put("behavior", behavior)
                .put("occurrences", d.indicators.values.sumOf { it.count }).put("indicators", arr)
        }
        o.put("activations", JSONArray().also { a ->
            DevToolsPrefs.activations(h).forEach { a.put(JSONObject().put("label", it.label).put("reason", it.reason).put("at", it.at)) }
        })
        return o.toString()
    }

    fun clearDetection(host: String?) {
        synchronized(detections) { if (host == null) detections.clear() else detections.remove(host) }
    }
}

class DevToolsFlag {
    companion object { const val JS_OBJECT_NAME = "AndroidDevTools" }

    @JavascriptInterface
    fun cfg(host: String): String {
        val h = host.lowercase().removePrefix("www.").take(253)
        val on = DevToolsPrefs.on(Flag.ANTI_DETECT, h)
        val instrument = DevToolsPrefs.on(Flag.JS_INSTRUMENT, h)
        return JSONObject()
            .put("on", on)
            .put("scan", on && DevToolsPrefs.on(Flag.ANTI_SCAN, h))
            .put("behavior", on && DevToolsPrefs.on(Flag.ANTI_BEHAVIOR, h))
            .put("instrument", instrument)
            .put("suppress", instrument && DevToolsPrefs.on(Flag.SUPPRESS_DEBUGGER, h))
            .put("experimental", instrument && DevToolsPrefs.on(Flag.EXPERIMENTAL, h))
            .put("threshold", DevToolsPrefs.num(DevToolsPrefs.Num.DEBUGGER_REPEAT))
            .toString()
    }

    @JavascriptInterface
    fun panelOpen(): Boolean = ProtectionEngine.panelOpen
}
