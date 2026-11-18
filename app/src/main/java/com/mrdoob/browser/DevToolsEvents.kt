package com.mrdoob.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object DevToolsEvents {

    enum class Type(val label: String) {
        INFINITE_RELOAD("Infinite reload detected"),
        REDIRECT_LOOP("Redirect loop detected"),
        CONTEXT_DESTROYED("JavaScript context destroyed"),
        RECORDING_INTERRUPTED("Recording interrupted"),
        BRIDGE_DISCONNECTED("Network bridge disconnected"),
        BRIDGE_RESTORED("Network bridge restored"),
        DEBUGGER_SUSPICIOUS("Suspicious debugger behaviour detected"),
        ANTI_DEVTOOLS("Anti-DevTools behaviour detected"),
        NAV_STARTED("Main-frame navigation started"),
        NAV_COMPLETED("Main-frame navigation completed"),
        NAV_FAILED("Main-frame navigation failed"),
        CAPTURE_FAILURE("Request capture failure"),
        PROTECTION_ACTIVATED("Protection activated"),
        NAV_CONFIRMATION("Navigation confirmation")
    }

    enum class Severity { INFO, WARNING, CRITICAL }

    class Event(
        val id: Long,
        val type: Type,
        val time: Long,
        val url: String,
        val severity: Severity,
        val reason: String,
        val actions: List<String>,
        val count: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("type", type.name)
            .put("label", type.label)
            .put("time", time)
            .put("url", url)
            .put("severity", severity.name.lowercase())
            .put("reason", reason)
            .put("actions", JSONArray(actions))
            .put("count", count)
    }

    private const val COALESCE_MS = 120_000L
    private val lock = Any()
    private val list = ArrayList<Event>()
    private var nextId = 1L

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun record(
        type: Type,
        url: String?,
        severity: Severity,
        reason: String,
        actions: List<String> = emptyList()
    ) {
        if (!DevToolsPrefs.on(DevToolsPrefs.Flag.EVENT_LOG, null)) return
        val u = (url ?: "").take(500)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val last = list.lastOrNull()
            if (last != null && last.type == type && last.url == u && last.reason == reason &&
                now - last.time < COALESCE_MS
            ) {
                list[list.size - 1] = Event(
                    last.id, type, now, u,
                    if (severity.ordinal > last.severity.ordinal) severity else last.severity,
                    reason, (last.actions + actions).distinct(), last.count + 1
                )
            } else {
                list.add(Event(nextId++, type, now, u, severity, reason, actions.distinct(), 1))
                val max = DevToolsPrefs.num(DevToolsPrefs.Num.MAX_EVENTS)
                while (list.size > max) list.removeAt(0)
            }
        }
        _revision.value = _revision.value + 1
    }

    fun clear() {
        synchronized(lock) { list.clear() }
        _revision.value = _revision.value + 1
    }

    fun snapshot(): List<Event> = synchronized(lock) { list.toList() }

    fun toJson(): String {
        val arr = JSONArray()

        snapshot().asReversed().forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun exportText(): String {
        val sb = StringBuilder("Netex developer tools events\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        for (e in snapshot()) {
            sb.append('\n').append(fmt.format(java.util.Date(e.time))).append("  [")
                .append(e.severity.name).append("] ").append(e.type.label)
            if (e.count > 1) sb.append(" x").append(e.count)
            sb.append('\n').append("  URL: ").append(e.url)
            sb.append('\n').append("  Reason: ").append(e.reason)
            if (e.actions.isNotEmpty()) sb.append('\n').append("  Actions: ").append(e.actions.joinToString("; "))
        }
        return sb.toString()
    }
}
