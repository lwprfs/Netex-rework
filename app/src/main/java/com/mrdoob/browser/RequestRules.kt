package com.mrdoob.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Network test tools applied to every request the page WebView makes:
 *  - BLOCK: the request fails with a 403 (handy to test how a page copes without a tracker/API).
 *  - MOCK:  the request is answered locally with a status, content type and body you choose.
 *  - Delay: every request waits [delayMs] before it is sent (a simple "slow network").
 *
 * A rule's pattern is matched against the full URL, case-insensitively. `*` matches anything,
 * otherwise the pattern just has to appear in the URL (e.g. `analytics` or `*.png`).
 */
object RequestRules {

    enum class Action { BLOCK, MOCK }

    data class Rule(
        val pattern: String,
        val action: Action,
        val status: Int = 200,
        val mime: String = "application/json",
        val body: String = "",
        val enabled: Boolean = true
    )

    private const val PREFS = "netex_tools"
    private const val KEY_RULES = "req_rules"
    private const val KEY_DELAY = "req_delay_ms"

    @Volatile private var appContext: Context? = null
    @Volatile private var cache: List<Rule>? = null
    @Volatile private var compiled: List<Pair<Regex, Rule>> = emptyList()

    val DELAY_STEPS = intArrayOf(0, 300, 1000, 3000)

    fun init(context: Context) {
        appContext = context.applicationContext
        reload()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var delayMs: Int
        get() = prefs()?.getInt(KEY_DELAY, 0) ?: 0
        set(v) { prefs()?.edit()?.putInt(KEY_DELAY, v.coerceAtLeast(0))?.apply() }

    fun rules(): List<Rule> = cache ?: reload()

    fun save(rules: List<Rule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("p", r.pattern).put("a", r.action.name).put("s", r.status)
                    .put("m", r.mime).put("b", r.body).put("e", r.enabled)
            )
        }
        prefs()?.edit()?.putString(KEY_RULES, arr.toString())?.apply()
        reload()
    }

    private fun reload(): List<Rule> {
        val raw = prefs()?.getString(KEY_RULES, null)
        val list = ArrayList<Rule>()
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val action = try { Action.valueOf(o.optString("a")) } catch (_: Exception) { continue }
                    list += Rule(
                        pattern = o.optString("p"),
                        action = action,
                        status = o.optInt("s", 200),
                        mime = o.optString("m", "application/json"),
                        body = o.optString("b"),
                        enabled = o.optBoolean("e", true)
                    )
                }
            } catch (_: Exception) {
                // corrupt data: start empty
            }
        }
        cache = list
        compiled = list.filter { it.enabled && it.pattern.isNotBlank() }.map { toRegex(it.pattern) to it }
        return list
    }

    private fun toRegex(pattern: String): Regex {
        val p = pattern.trim()
        val body = p.split('*').joinToString(".*") { Regex.escape(it) }
        // The pattern may appear anywhere in the URL; `*` stands for any text.
        return Regex(".*$body.*", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    /** Called from WebViewClient.shouldInterceptRequest (a background thread). null = let it through. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        val delay = delayMs
        if (delay > 0) {
            try { Thread.sleep(delay.toLong()) } catch (_: InterruptedException) { /* ignore */ }
        }

        val rule = compiled.firstOrNull { it.first.matches(url) }?.second ?: return null
        val cors = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "*",
            "Access-Control-Allow-Methods" to "*"
        )
        return when (rule.action) {
            Action.BLOCK -> WebResourceResponse(
                "text/plain", "UTF-8", 403, "Blocked", cors,
                ByteArrayInputStream("Blocked by Netex".toByteArray())
            )
            Action.MOCK -> {
                if (request.method.equals("OPTIONS", ignoreCase = true)) {
                    WebResourceResponse("text/plain", "UTF-8", 204, "No Content", cors, ByteArrayInputStream(ByteArray(0)))
                } else {
                    val status = rule.status.coerceIn(200, 599)
                    WebResourceResponse(
                        rule.mime.ifBlank { "text/plain" }, "UTF-8", status, reason(status), cors,
                        ByteArrayInputStream(rule.body.toByteArray())
                    )
                }
            }
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 301 -> "Moved Permanently"
        302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
        502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        else -> "Mocked"
    }
}
