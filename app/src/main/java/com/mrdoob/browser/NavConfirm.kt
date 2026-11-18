package com.mrdoob.browser

import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * State and glue of the navigation confirmation system: stored rules, allowed / blocked lists, per-website
 * modes, page-side hints, and the bookkeeping that keeps one redirect chain from raising several dialogs and a
 * cancelled navigation from turning into a loop. The decisions themselves are made by [NavPolicy].
 *
 * Coverage is honest, not complete:
 *  - link taps, location changes, HTTP redirects and meta refresh reach shouldOverrideUrlLoading (WebView does not
 *    say which of the last three a script-less navigation was; the page-side hints in nav-guard.js refine the label);
 *  - target=_blank and window.open arrive in onCreateWindow;
 *  - form submissions are caught in the page (WebView never reports POST navigations to the app), so they are
 *    covered only while page scripts can run;
 *  - typed addresses, bookmarks, back, forward and reload are the user's own and are never intercepted.
 */
object NavConfirm {

    private const val PREFS = "netex_navconfirm"
    private const val MAX_ENTRIES = 1000
    private const val ONCE_MS = 15_000L
    private const val CANCEL_MS = 20_000L
    private const val CHAIN_MS = 30_000L
    private const val HINT_MS = 5_000L

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // --- rules ----------------------------------------------------------------------------

    fun rule(r: NavPolicy.Rule): Boolean = prefs?.getBoolean("r_" + r.key, r.default) ?: r.default

    fun setRule(r: NavPolicy.Rule, on: Boolean) { prefs?.edit()?.putBoolean("r_" + r.key, on)?.apply() }

    fun resetRules() {
        val e = prefs?.edit() ?: return
        NavPolicy.Rule.entries.forEach { e.remove("r_" + it.key) }
        e.apply()
    }

    // --- allowed / blocked lists ------------------------------------------------------------------

    enum class ListKind(val key: String) { ALLOWED("allowed"), BLOCKED("blocked") }

    fun list(kind: ListKind): Set<String> = readSet(kind.key)

    private fun readSet(key: String): Set<String> {
        val raw = prefs?.getString("l_$key", null) ?: return emptySet()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }.toCollection(LinkedHashSet())
        } catch (_: Exception) { emptySet() }
    }

    private fun writeSet(key: String, s: Set<String>) {
        prefs?.edit()?.putString("l_$key", JSONArray(s.toList()).toString())?.apply()
    }

    /** "Example.com", "https://x.org/a", "*.x.org" or "tel:" to a list entry, or null if it is not valid. */
    fun normalizeEntry(input: String): String? {
        val t = input.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        if (t.contains("://")) return NavPolicy.hostOf(t)
        if (t.endsWith(":") && t.length > 1 && t.dropLast(1).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return t
        val wildcard = t.startsWith("*.")
        val host = (if (wildcard) t.substring(2) else t).removePrefix("www.").trimEnd('.')
        if (host.isEmpty() || !Regex("^[a-z0-9.\\-\\[\\]:]+$").matches(host) || host.length > 253) return null
        return if (wildcard) "*.$host" else host
    }

    /** Adds to one list and removes the entry from the other, so a domain is never on both. */
    fun addToList(kind: ListKind, entry: String) {
        val other = if (kind == ListKind.ALLOWED) ListKind.BLOCKED else ListKind.ALLOWED
        synchronized(this) {
            val mine = LinkedHashSet(list(kind))
            if (mine.size >= MAX_ENTRIES && entry !in mine) return
            mine.add(entry)
            writeSet(kind.key, mine)
            val theirs = LinkedHashSet(list(other))
            if (theirs.remove(entry)) writeSet(other.key, theirs)
        }
    }

    fun removeFromList(kind: ListKind, entry: String) {
        synchronized(this) { writeSet(kind.key, LinkedHashSet(list(kind)).also { it.remove(entry) }) }
    }

    // --- per-website rules --------------------------------------------------------------------------

    fun siteModes(): Map<String, NavPolicy.SiteMode> {
        val raw = prefs?.getString("sites", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val m = LinkedHashMap<String, NavPolicy.SiteMode>()
            o.keys().forEach { k ->
                runCatching { NavPolicy.SiteMode.valueOf(o.getString(k)) }.getOrNull()?.let { m[k] = it }
            }
            m
        } catch (_: Exception) { emptyMap() }
    }

    fun setSiteMode(host: String, mode: NavPolicy.SiteMode?) {
        synchronized(this) {
            val m = LinkedHashMap(siteModes())
            if (mode == null) m.remove(host) else if (m.size < MAX_ENTRIES || host in m) m[host] = mode
            val o = JSONObject()
            m.forEach { (h, v) -> o.put(h, v.name) }
            prefs?.edit()?.putString("sites", o.toString())?.apply()
        }
    }

    fun config(): NavPolicy.Config {
        val rules = NavPolicy.Rule.entries.associateWith { rule(it) }
        return NavPolicy.Config(rules, list(ListKind.ALLOWED), list(ListKind.BLOCKED), siteModes(), ::visited)
    }

    // --- visited domains ------------------------------------------------------------------------------

    private val visitedHosts = HashSet<String>()
    private var visitedLoaded = false

    private fun visited(host: String): Boolean = synchronized(visitedHosts) {
        if (!visitedLoaded) {
            visitedLoaded = true
            try { BrowserLibrary.history().forEach { v -> NavPolicy.hostOf(v.url)?.let { visitedHosts.add(it) } } } catch (_: Exception) { }
        }
        host in visitedHosts
    }

    fun noteVisited(url: String?) {
        val h = NavPolicy.hostOf(url) ?: return
        synchronized(visitedHosts) { visitedHosts.add(h) }
    }

    // --- page-side hints (nav-guard.js) --------------------------------------------------------------------

    private class Hint(val kind: String, val url: String, val time: Long)
    private val hints = ArrayDeque<Hint>()

    fun onHint(obj: JSONObject) {
        val kind = obj.optString("kind")
        val url = obj.optString("url").take(2000)
        if (kind !in setOf("script", "meta", "form") || url.isEmpty()) return
        synchronized(hints) {
            hints.addLast(Hint(kind, url.substringBefore('#'), System.currentTimeMillis()))
            while (hints.size > 20) hints.removeFirst()
        }
    }

    private fun takeHint(url: String): String? = synchronized(hints) {
        val now = System.currentTimeMillis()
        val key = url.substringBefore('#')
        val h = hints.lastOrNull { now - it.time < HINT_MS && it.url == key }
        h?.kind
    }

    /** Names the navigation as precisely as WebView and the page hints allow. */
    fun classify(url: String, isRedirect: Boolean, hasGesture: Boolean): NavPolicy.Type {
        if (!NavPolicy.isWeb(url)) return NavPolicy.Type.EXTERNAL_SCHEME
        if (isRedirect) return NavPolicy.Type.HTTP_REDIRECT
        val hint = takeHint(url)
        if (hint == "form") return NavPolicy.Type.FORM
        if (hasGesture) return NavPolicy.Type.LINK_CLICK
        return when (hint) {
            "meta" -> NavPolicy.Type.META_REFRESH
            "script" -> NavPolicy.Type.SCRIPT
            else -> NavPolicy.Type.AUTOMATIC
        }
    }

    // --- duplicate and loop protection ------------------------------------------------------------------------

    private val allowedOnce = HashMap<String, Long>()
    private val cancelled = HashMap<String, Long>()
    private val silent = HashMap<String, Int>()
    private var promptKey: String? = null
    private var chainAt = 0L
    private val chain = ArrayList<String>()

    fun keyOf(url: String): String = NavPolicy.hostOf(url) ?: (NavPolicy.schemeOf(url) ?: url) + ":"

    /** The user chose Continue: the re-issued load (and an immediate repeat) must not ask again. */
    fun allowOnce(url: String) = synchronized(this) { allowedOnce[url.substringBefore('#')] = System.currentTimeMillis() }

    /** A form was approved: the GET it produces has a URL nobody knows yet, so the host is approved briefly. */
    fun allowHostOnce(host: String) = synchronized(this) { allowedOnce["h:$host"] = System.currentTimeMillis() }

    fun passOnce(url: String): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        val t = allowedOnce[url.substringBefore('#')]
        if (t != null && now - t < ONCE_MS) return true
        val h = NavPolicy.hostOf(url)?.let { allowedOnce["h:$it"] }
        h != null && now - h < ONCE_MS
    }

    /** Only one dialog at a time; attempts that arrive while it is open are dropped, not queued. */
    fun beginPrompt(key: String): Boolean = synchronized(this) {
        if (promptKey != null) return false
        promptKey = key
        true
    }

    fun endPrompt() = synchronized(this) { promptKey = null }

    fun promptOpen(): Boolean = synchronized(this) { promptKey != null }

    fun noteCancelled(key: String) = synchronized(this) { cancelled[key] = System.currentTimeMillis(); silent[key] = 0 }

    fun recentlyCancelled(key: String): Boolean = synchronized(this) {
        val t = cancelled[key] ?: return false
        if (System.currentTimeMillis() - t < CANCEL_MS) true else { cancelled.remove(key); false }
    }

    /** Counts a silently dropped repeat attempt; returns the running count. */
    fun noteSilentBlock(key: String): Int = synchronized(this) {
        val n = (silent[key] ?: 0) + 1
        silent[key] = n
        n
    }

    // Redirect chains -----------------------------------------------------------------------

    fun approveChain(startUrls: List<String>) = synchronized(this) {
        chainAt = System.currentTimeMillis()
        chain.clear()
        chain.addAll(startUrls)
    }

    fun inApprovedChain(): Boolean = synchronized(this) { chainAt != 0L && System.currentTimeMillis() - chainAt < CHAIN_MS }

    fun noteChainHop(url: String): Int = synchronized(this) { chain.add(url); chain.size }

    fun chainSoFar(): List<String> = synchronized(this) { chain.toList() }

    fun endChain() = synchronized(this) { chainAt = 0L; chain.clear() }

    // --- blocked destinations at the network layer (integrates with request interception) ---------------------

    /**
     * shouldInterceptRequest for main-frame requests: a redirect hop (or a navigation the page started) to a blocked
     * domain gets the same 403 a RequestRules BLOCK rule gives. The user's own typed addresses are left alone.
     */
    fun blockedMainFrame(request: WebResourceRequest): WebResourceResponse? {
        if (!request.isForMainFrame || prefs == null || !BrowserPrefs.isNavigationConfirmationOn()) return null
        val url = request.url.toString()
        val host = NavPolicy.hostOf(url) ?: return null
        if (!NavPolicy.listMatches(list(ListKind.BLOCKED), host, false)) return null
        if (!request.isRedirect && NavigationTracker.activeStartedByUser()) return null
        report(DevToolsEvents.Severity.WARNING, url, "Request to blocked domain refused", listOf("Blocked by navigation rules"))
        logRefused(url, "Blocked domain: $host")
        return WebResourceResponse(
            "text/plain", "UTF-8", 403, "Blocked", mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream("Blocked by Netex navigation rules".toByteArray())
        )
    }

    // --- reporting ---------------------------------------------------------------------------------------------

    fun report(sev: DevToolsEvents.Severity, url: String?, reason: String, actions: List<String> = emptyList()) {
        DevToolsEvents.record(DevToolsEvents.Type.NAV_CONFIRMATION, url, sev, reason, actions)
    }

    /** A refused or cancelled navigation shows up in the Network tab like a failed document request. */
    fun logRefused(url: String, why: String) {
        try {
            NetworkLog.add(
                NetworkLog.Record(
                    method = "GET", url = url, status = 0, body = "", contentType = null, durationMs = 0,
                    startedAt = System.currentTimeMillis(), kind = "document", navId = NavigationTracker.currentId(),
                    capture = "native", failed = true, error = why
                )
            )
        } catch (_: Exception) { }
    }

    // --- form submissions asked by the page ------------------------------------------------------------------------

    class FormAsk(val id: String, val from: String, val to: String, val method: String)

    private val _forms = MutableSharedFlow<FormAsk>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val forms = _forms.asSharedFlow()

    fun onFormAsk(obj: JSONObject) {
        val id = obj.optString("id").take(40).ifEmpty { return }
        _forms.tryEmit(FormAsk(id, obj.optString("from").take(2000), obj.optString("to").take(2000), obj.optString("method", "GET").take(8)))
    }
}

/** Page-side read-only check used by nav-guard.js to decide whether a form submission needs a native dialog. */
class NavGuardFlag {
    companion object { const val JS_OBJECT_NAME = "AndroidNavGuard" }

    /** "ask" when the submission should wait for the user (including when it is blocked), else "allow". */
    @JavascriptInterface
    fun check(from: String, to: String, userActive: Boolean): String {
        if (!BrowserPrefs.isNavigationConfirmationOn() || !NavPolicy.isWeb(to)) return "allow"
        val req = NavPolicy.Request(from, to, NavPolicy.Type.FORM, userActive, Long.MAX_VALUE, 0)
        return if (NavPolicy.decide(req, NavConfirm.config()).action == NavPolicy.Action.ALLOW) "allow" else "ask"
    }
}
