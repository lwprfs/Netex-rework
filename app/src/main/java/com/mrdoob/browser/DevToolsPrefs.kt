package com.mrdoob.browser

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object DevToolsPrefs {

    enum class Section(val title: String, val blurb: String) {
        GENERAL("General", "Master switch, notifications and the event log"),
        NETWORK("Network Protection", "What the Network tab keeps when a page navigates"),
        NAVIGATION("Navigation Protection", "Native tracking of navigations, reloads and redirects"),
        ANTI("Anti-Debugging Detection", "Spots sites that react to developer tools"),
        JS("JavaScript Compatibility", "Compatibility mode and optional page instrumentation"),
        RECORDING("Recording Protection", "How HAR recordings behave during navigation and failures"),
        AUTO("Automatic Protection", "Which events may switch protections on by themselves"),
        ADVANCED("Advanced Settings", "Thresholds, limits and website profiles")
    }

    enum class Flag(
        val key: String,
        val section: Section,
        val title: String,
        val description: String,
        val default: Boolean,

        val automatic: Boolean = false,

        val perSite: Boolean = false
    ) {

        ENABLED("enabled", Section.GENERAL, "DevTools compatibility system",
            "Master switch for tracking, detection and protection. Off: only the plain Preserve log toggle keeps working.",
            true, perSite = true),
        NOTIFY("notify", Section.GENERAL, "Show notifications",
            "A small message when a protection is switched on or interference is detected", true),
        EVENT_LOG("event_log", Section.GENERAL, "Record events",
            "Keep the event dashboard (Network tab, Events button)", true),

        PRESERVE_NAV("preserve_nav", Section.NETWORK, "Preserve network logs across navigation",
            "Keep requests when the page goes to another URL. Same switch as Preserve in the Network tab.",
            true, automatic = true, perSite = true),
        PRESERVE_RELOAD("preserve_reload", Section.NETWORK, "Preserve logs across page reloads",
            "Needs the switch above. Keeps requests when the same page reloads.", true, automatic = true),
        PRESERVE_REDIRECT("preserve_redirect", Section.NETWORK, "Preserve logs across redirects",
            "Needs the first switch. Keeps requests when a redirect starts a new page load.", true, automatic = true),
        PRESERVE_CONTEXT("preserve_context", Section.NETWORK, "Preserve logs when JavaScript context is destroyed",
            "After a renderer crash the Network tab is refilled from the native log", true, automatic = true),
        PRESERVE_FAILED("preserve_failed", Section.NETWORK, "Preserve failed requests",
            "Failed requests survive navigation even when Preserve is off", true),
        PRESERVE_DOCS("preserve_docs", Section.NETWORK, "Preserve document navigation requests",
            "Page loads survive navigation even when Preserve is off", true),
        PRESERVE_RESTART("preserve_restart", Section.NETWORK, "Preserve logs after browser restart",
            "Saves request metadata (no bodies, no headers) and navigation history when the app pauses", false),

        NAV_TRACKING("nav_tracking", Section.NAVIGATION, "Native navigation tracking",
            "Records every main-frame navigation, redirect, reload and error from WebView callbacks",
            true, automatic = true, perSite = true),
        LOOP_DETECT("loop_detect", Section.NAVIGATION, "Reload and loop detection",
            "Warns when a page keeps reloading or bouncing between URLs without you doing anything",
            true, automatic = true, perSite = true),
        REDIRECT_MONITOR("redirect_monitor", Section.NAVIGATION, "Monitor suspicious redirects",
            "Watches redirect chains for cycles and repeated trips to the homepage",
            true, automatic = true, perSite = true),
        NAV_HISTORY("nav_history", Section.NAVIGATION, "Preserve navigation history",
            "Keep the navigation timeline (Network tab, Nav button)", true, automatic = true, perSite = true),
        LOOP_PROMPT("loop_prompt", Section.NAVIGATION, "Offer to stop automatic navigation",
            "Show the navigation sequence with a Stop option when a loop is detected", true, perSite = true),

        ANTI_DETECT("anti_detect", Section.ANTI, "Anti-DevTools interference detection",
            "Needs several indicators before a site is flagged; one debugger statement is never enough",
            true, automatic = true, perSite = true),
        ANTI_SCAN("anti_scan", Section.ANTI, "Scan inline scripts",
            "Read-only look at inline script text for known detection techniques. Nothing is modified.", true),
        ANTI_BEHAVIOR("anti_behavior", Section.ANTI, "Watch behaviour after the panel opens",
            "Reloads, redirects or removed page content that follow opening developer tools", true),

        COMPAT_ENABLED("compat_enabled", Section.JS, "Compatibility mode",
            "Globally: allow it to be used. In a website profile: turn it on for that site.",
            true, perSite = true),
        COMPAT_AUTO("compat_auto", Section.JS, "Enable compatibility mode automatically",
            "Let detection switch compatibility mode on for the affected site (this session only)", true),
        JS_INSTRUMENT("js_instrument", Section.JS, "Advanced JavaScript instrumentation",
            "Watches eval, Function() and timers for debugger statements. Wraps page APIs, so it is detectable.",
            false, perSite = true),
        SUPPRESS_DEBUGGER("suppress_debugger", Section.JS, "Suppress unnecessary debugger statements",
            "Needs instrumentation. Removes debugger from code passed as a string; code the page already has cannot be changed.",
            false, perSite = true),
        EXPERIMENTAL("experimental", Section.JS, "Experimental compatibility patches",
            "Needs instrumentation. Makes outerWidth/outerHeight match the viewport and ignores console.clear().",
            false, perSite = true),

        REC_PROTECT("rec_protect", Section.RECORDING, "Protect recording sessions",
            "When a loop or crash is detected the recording is saved to a protected copy",
            true, automatic = true),
        REC_CONTINUOUS("rec_continuous", Section.RECORDING, "Continuous recording",
            "Keep recording through navigation. Off: recording pauses at every page load.", true, perSite = true),
        REC_RECOVER("rec_recover", Section.RECORDING, "Automatic session recovery",
            "After a renderer crash the recording carries on and replays what the new page has already loaded", true),
        REC_FAILED("rec_failed", Section.RECORDING, "Capture failed requests",
            "Network errors and failed page loads reported by WebView go into the recording", true),
        REC_REDIRECTS("rec_redirects", Section.RECORDING, "Capture redirect chains",
            "Every hop of a main-frame redirect gets its own entry", true),
        REC_DOCS("rec_docs", Section.RECORDING, "Capture document requests",
            "Page loads seen by WebView are recorded even if page scripts never report them", true),
        REC_TIMING("rec_timing", Section.RECORDING, "Capture request timing",
            "Native entries carry start and finish times", true),
        REC_AUTO_EXPORT("rec_auto_export", Section.RECORDING, "Export recording when a loop is detected",
            "Saves the current HAR to Downloads at the moment a loop is confirmed", false),

        AUTO_MASTER("auto_master", Section.AUTO, "Automatic protection",
            "Allow the engine to switch protections on for a site. Off: nothing is changed by itself.",
            true, perSite = true),
        AUTO_RULE_RELOAD("auto_rule_reload", Section.AUTO, "React to repeated reloads", "", true),
        AUTO_RULE_CONTEXT("auto_rule_context", Section.AUTO, "React to a destroyed JavaScript context", "", true),
        AUTO_RULE_ANTI("auto_rule_anti", Section.AUTO, "React to anti-DevTools behaviour", "", true),
        AUTO_RULE_REDIRECT("auto_rule_redirect", Section.AUTO, "React to repeated redirects", "", true),
        AUTO_ACT_PRESERVE("auto_act_preserve", Section.AUTO, "Action: enable Preserve log", "", true),
        AUTO_ACT_TRACKING("auto_act_tracking", Section.AUTO, "Action: enable tracking and loop detection", "", true),
        AUTO_ACT_COMPAT("auto_act_compat", Section.AUTO, "Action: enable compatibility protections", "", true),
        AUTO_ACT_RECORDING("auto_act_recording", Section.AUTO, "Action: protect the current recording", "", true),
        AUTO_ACT_HISTORY("auto_act_history", Section.AUTO, "Action: save navigation history", "", true),
        AUTO_ACT_BRIDGE("auto_act_bridge", Section.AUTO, "Action: reinitialise the page bridge once", "", true);

        val rowId: String get() = "dt_$key"
        val indicator: String get() = if (automatic) "Auto" else "Manual"

        companion object {
            fun fromKey(k: String): Flag? = entries.firstOrNull { it.key == k }
            fun fromRowId(id: String): Flag? = entries.firstOrNull { it.rowId == id }
        }
    }

    enum class Num(
        val key: String, val title: String, val description: String,
        val default: Int, val min: Int, val max: Int, val unit: String
    ) {
        WARN_COUNT("warn_count", "Warning: reloads", "Show a warning after this many automatic loads of one URL", 3, 2, 50, ""),
        WARN_SECS("warn_secs", "Warning: within", "…inside this window", 10, 2, 600, "s"),
        PROTECT_COUNT("protect_count", "Loop protection: reloads", "Activate loop protection after this many", 5, 2, 100, ""),
        PROTECT_SECS("protect_secs", "Loop protection: within", "…inside this window", 15, 2, 600, "s"),
        STRONG_COUNT("strong_count", "Stronger protection: reloads", "Activate stronger protection after this many", 8, 2, 200, ""),
        STRONG_SECS("strong_secs", "Stronger protection: within", "…inside this window", 30, 2, 600, "s"),
        MAX_LOG_ENTRIES("max_log_entries", "Maximum network log size", "Requests kept by the native log and the Network tab", 2000, 200, 20000, " requests"),
        MAX_EVENTS("max_events", "Maximum events", "Entries kept by the event dashboard", 500, 50, 5000, " events"),
        MAX_NAV_HISTORY("max_nav_history", "Maximum navigation history", "Navigations kept for the timeline", 300, 20, 2000, " navigations"),
        DEBUGGER_REPEAT("debugger_repeat", "Repeated debugger statements", "Executions of dynamic debugger code that count as repeated", 5, 2, 100, "")
    }

    class Activation(val host: String, val label: String, val reason: String, val at: Long)

    val COMPAT_BUNDLE: Set<Flag> = setOf(
        Flag.PRESERVE_NAV, Flag.PRESERVE_RELOAD, Flag.PRESERVE_REDIRECT, Flag.PRESERVE_CONTEXT,
        Flag.NAV_TRACKING, Flag.LOOP_DETECT, Flag.REDIRECT_MONITOR, Flag.NAV_HISTORY,
        Flag.REC_PROTECT, Flag.ANTI_DETECT
    )

    fun siteScoped(flag: Flag): Boolean = flag.perSite || flag in COMPAT_BUNDLE

    private const val PREFS = "netex_devtools"
    private const val KEY_SITES = "sites_v1"
    private const val LEGACY_KEEP_NETWORK = "keep_network"
    private const val MAX_SITES = 200
    private const val MAX_SESSION_HOSTS = 200
    private const val MAX_ACTIVATIONS = 100

    @Volatile private var appContext: Context? = null
    @Volatile private var prefs: SharedPreferences? = null

    @Volatile var currentHost: String? = null

    private val sites = ConcurrentHashMap<String, Map<Flag, Boolean>>()
    private val session = ConcurrentHashMap<String, MutableSet<Flag>>()
    private val compatSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val activations = CopyOnWriteArrayList<Activation>()

    private val _revision = MutableStateFlow(0)

    val revision: StateFlow<Int> = _revision.asStateFlow()
    private fun bump() { _revision.value = _revision.value + 1 }

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadSites()
    }

    fun siteKey(url: String?): String? = NavigationGuard.hostOf(url)?.removePrefix("www.")

    fun on(flag: Flag, host: String? = currentHost): Boolean {
        val h = host?.removePrefix("www.")
        if (!masterOn(h)) {
            return when (flag) {
                Flag.PRESERVE_NAV, Flag.PRESERVE_RELOAD, Flag.PRESERVE_REDIRECT, Flag.PRESERVE_CONTEXT,
                Flag.PRESERVE_FAILED, Flag.PRESERVE_DOCS -> global(flag)
                else -> false
            }
        }
        if (h != null) {
            siteValue(h, flag)?.let { return it }
            if (flag in COMPAT_BUNDLE && compatActive(h)) return true
            if (session[h]?.contains(flag) == true) return true
        }
        return global(flag)
    }

    fun global(flag: Flag): Boolean {
        val p = prefs ?: return flag.default
        if (flag == Flag.PRESERVE_NAV) {

            val legacy = p.getString("dev_$LEGACY_KEEP_NETWORK", null)
            if (legacy != null) return legacy == "true"
            return flag.default
        }
        return p.getBoolean("f_" + flag.key, flag.default)
    }

    fun setGlobal(flag: Flag, value: Boolean) {
        val p = prefs ?: return
        if (flag == Flag.PRESERVE_NAV) p.edit().putString("dev_$LEGACY_KEEP_NETWORK", value.toString()).apply()
        else p.edit().putBoolean("f_" + flag.key, value).apply()
        bump()
    }

    fun num(n: Num): Int = (prefs?.getInt("n_" + n.key, n.default) ?: n.default).coerceIn(n.min, n.max)

    fun setNum(n: Num, v: Int) {
        prefs?.edit()?.putInt("n_" + n.key, v.coerceIn(n.min, n.max))?.apply()
        bump()
    }

    private fun masterOn(host: String?): Boolean {
        if (host != null) siteValue(host, Flag.ENABLED)?.let { return it }
        return global(Flag.ENABLED)
    }

    fun source(flag: Flag, host: String?): String {
        val h = host?.removePrefix("www.") ?: return "Global"
        return when {
            siteValue(h, flag) != null -> "Website profile"
            flag in COMPAT_BUNDLE && compatActive(h) -> "Compatibility mode"
            session[h]?.contains(flag) == true -> "Automatic protection"
            else -> "Global"
        }
    }

    fun preserveFor(type: NavigationTracker.Type, host: String?): Boolean {
        if (!on(Flag.PRESERVE_NAV, host)) return false
        return when (type) {
            NavigationTracker.Type.RELOAD -> on(Flag.PRESERVE_RELOAD, host)
            NavigationTracker.Type.REDIRECT -> on(Flag.PRESERVE_REDIRECT, host)
            else -> true
        }
    }

    fun siteValue(host: String, flag: Flag): Boolean? = sites[host]?.get(flag)

    fun siteProfile(host: String): Map<Flag, Boolean> = sites[host.removePrefix("www.")] ?: emptyMap()

    fun siteHosts(): List<String> = sites.keys.sorted()

    fun setSite(host: String, flag: Flag, value: Boolean?) {
        val h = host.removePrefix("www.")
        if (h.isEmpty() || !siteScoped(flag)) return
        val m = LinkedHashMap(sites[h] ?: emptyMap())
        if (value == null) m.remove(flag) else {
            if (sites[h] == null && sites.size >= MAX_SITES) return
            m[flag] = value
        }
        if (m.isEmpty()) sites.remove(h) else sites[h] = m
        saveSites()
        bump()
    }

    fun resetSite(host: String) {
        sites.remove(host.removePrefix("www."))
        saveSites()
        bump()
    }

    fun userSet(flag: Flag, value: Boolean, host: String?) {
        val h = host?.removePrefix("www.")
        val hasSite = h != null && siteValue(h, flag) != null
        val forced = h != null && (session[h]?.contains(flag) == true || (flag in COMPAT_BUNDLE && compatActive(h)))
        if (h != null && siteScoped(flag) && (hasSite || forced)) setSite(h, flag, value)
        if (!hasSite && (!forced || value)) setGlobal(flag, value)
    }

    fun exportProfiles(): String {
        val root = JSONObject().put("format", "netex-site-profiles").put("version", 1)
        val obj = JSONObject()
        for (h in siteHosts()) {
            val p = JSONObject()
            sites[h]?.forEach { (f, v) -> p.put(f.key, v) }
            obj.put(h, p)
        }
        return root.put("sites", obj).toString(2)
    }

    fun importProfiles(json: String): Int {
        val root = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("Not valid JSON") }
        if (root.optString("format") != "netex-site-profiles") throw IllegalArgumentException("Not a Netex website profile export")
        val obj = root.optJSONObject("sites") ?: throw IllegalArgumentException("No websites in the file")
        var imported = 0
        val keys = obj.keys()
        while (keys.hasNext()) {
            val raw = keys.next()
            val host = raw.trim().lowercase().removePrefix("www.")
            if (host.isEmpty() || host.length > 253 || !HOST_OK.matches(host)) continue
            val p = obj.optJSONObject(raw) ?: continue
            val m = LinkedHashMap(sites[host] ?: emptyMap())
            val fk = p.keys()
            while (fk.hasNext()) {
                val k = fk.next()
                val f = Flag.fromKey(k) ?: continue
                if (!siteScoped(f) || p.opt(k) !is Boolean) continue
                m[f] = p.getBoolean(k)
            }
            if (m.isEmpty()) continue
            if (sites[host] == null && sites.size >= MAX_SITES) break
            sites[host] = m
            imported++
        }
        saveSites()
        bump()
        return imported
    }

    private val HOST_OK = Regex("^[a-z0-9._:\\[\\]-]+$")

    private fun loadSites() {
        val raw = prefs?.getString(KEY_SITES, null) ?: return
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val host = keys.next()
                val p = obj.optJSONObject(host) ?: continue
                val m = LinkedHashMap<Flag, Boolean>()
                val fk = p.keys()
                while (fk.hasNext()) {
                    val k = fk.next()
                    val f = Flag.fromKey(k) ?: continue
                    if (p.opt(k) is Boolean) m[f] = p.getBoolean(k)
                }
                if (m.isNotEmpty()) sites[host] = m
            }
        } catch (_: Exception) {  }
    }

    private fun saveSites() {
        val obj = JSONObject()
        for ((h, m) in sites) {
            val p = JSONObject()
            m.forEach { (f, v) -> p.put(f.key, v) }
            obj.put(h, p)
        }
        prefs?.edit()?.putString(KEY_SITES, obj.toString())?.apply()
    }

    fun resetSection(section: Section) {
        val e = prefs?.edit() ?: return
        for (f in Flag.entries) if (f.section == section) {
            if (f == Flag.PRESERVE_NAV) e.remove("dev_$LEGACY_KEEP_NETWORK") else e.remove("f_" + f.key)
        }
        if (section == Section.ADVANCED) for (n in Num.entries) e.remove("n_" + n.key)
        e.apply()
        bump()
    }

    fun resetAll() {
        for (s in Section.entries) resetSection(s)
    }

    fun compatActive(host: String): Boolean =
        siteValue(host, Flag.COMPAT_ENABLED) == true || compatSession.contains(host)

    fun activate(host: String, flags: Collection<Flag>, reason: String): List<String> {
        val h = host.removePrefix("www.")
        if (h.isEmpty()) return emptyList()
        val done = ArrayList<String>()
        for (f in flags) {
            if (siteValue(h, f) != null) continue
            if (on(f, h)) continue
            if (session.size >= MAX_SESSION_HOSTS && session[h] == null) session.clear()
            session.computeIfAbsent(h) { ConcurrentHashMap.newKeySet() }.add(f)
            record(Activation(h, f.title, reason, System.currentTimeMillis()))
            done += f.title
        }
        if (done.isNotEmpty()) bump()
        return done
    }

    fun activateCompat(host: String, reason: String): List<String> {
        val h = host.removePrefix("www.")
        if (h.isEmpty() || siteValue(h, Flag.COMPAT_ENABLED) == false) return emptyList()
        if (compatSession.size >= MAX_SESSION_HOSTS) compatSession.clear()
        val before = COMPAT_BUNDLE.filter { !on(it, h) && siteValue(h, it) == null }
        if (!compatSession.add(h)) return emptyList()
        before.forEach { record(Activation(h, it.title, reason, System.currentTimeMillis())) }
        bump()
        return before.map { it.title }
    }

    fun activations(host: String? = null): List<Activation> =
        if (host == null) activations.toList() else activations.filter { it.host == host.removePrefix("www.") }

    private fun record(a: Activation) {
        activations.add(a)
        while (activations.size > MAX_ACTIVATIONS) activations.removeAt(0)
    }

    fun disableActivated(host: String, flag: Flag) {
        val h = host.removePrefix("www.")
        session[h]?.remove(flag)
        setSite(h, flag, false)
        bump()
    }

    fun clearSessionState() {
        session.clear()
        compatSession.clear()
        activations.clear()
        bump()
    }
}
