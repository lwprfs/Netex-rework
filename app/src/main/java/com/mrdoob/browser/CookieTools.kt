package com.mrdoob.browser

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper over [CookieManager]. The WebView API can't enumerate every stored cookie, only
 * the ones that would be sent to a given URL (name + value, no attributes) — so cookies are
 * always looked up per site, and visited sites are remembered to make switching easy.
 */
object CookieTools {

    data class Cookie(val name: String, val value: String)

    private const val PREFS = "netex_tools"
    private const val KEY_SITES = "cookie_sites"
    private const val KEY_KEEP = "cookie_keep_sites"
    private const val MAX_SITES = 30

    /** `example.com` / `http://x.y:8080/a?b` → `https://example.com/` style URL; null if unusable. */
    fun normalizeSite(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val u = Uri.parse(if (t.contains("://")) t else "https://$t")
        val scheme = u.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = u.host?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (u.port != -1) ":${u.port}" else ""
        val path = u.encodedPath?.takeIf { it.isNotEmpty() } ?: "/"
        return "$scheme://$host$port$path"
    }

    fun list(url: String): List<Cookie> = CookieManager.getInstance().getCookie(url).orEmpty()
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Cookie(it.substringBefore('='), it.substringAfter('=', "")) }

    /** Host-only cookie for [url]'s host. [days] null = session cookie. [done] runs once it's stored. */
    fun add(
        url: String, name: String, value: String, path: String,
        days: Int?, secure: Boolean, httpOnly: Boolean, done: () -> Unit
    ) {
        val sb = StringBuilder("$name=$value; Path=${path.ifEmpty { "/" }}")
        if (days != null) sb.append("; Max-Age=${days * 86400L}")
        if (secure) sb.append("; Secure")
        if (httpOnly) sb.append("; HttpOnly")
        val cm = CookieManager.getInstance()
        cm.setCookie(url, sb.toString()) {
            cm.flush()
            done()
        }
    }

    /**
     * The API can't tell us a cookie's Path/Domain, so expire every plausible variant
     * (each path prefix of the URL × host-only / host / .host / parent domains).
     */
    fun delete(url: String, name: String, done: () -> Unit) {
        val u = Uri.parse(url)
        val host: String = u.host ?: run {
            done()
            return
        }
        val paths = linkedSetOf("/")
        var acc = ""
        for (seg in u.pathSegments) {
            acc += "/$seg"
            paths += acc
        }
        val domains = mutableListOf<String?>(null)
        var h: String = host
        while (h.contains('.')) {
            domains += h
            domains += ".$h"
            h = h.substringAfter('.')
        }
        val specs = ArrayList<String>()
        for (p in paths) for (d in domains) {
            val domain = if (d != null) "; Domain=$d" else ""
            specs += "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$p$domain"
        }
        val cm = CookieManager.getInstance()
        specs.forEachIndexed { i, spec ->
            if (i == specs.lastIndex) cm.setCookie(url, spec) { cm.flush(); done() }
            else cm.setCookie(url, spec)
        }
    }

    fun clearSite(url: String, done: () -> Unit) {
        val names = list(url).map { it.name }.distinct()
        if (names.isEmpty()) {
            done()
            return
        }
        var left = names.size
        names.forEach { delete(url, it) { if (--left == 0) done() } }
    }

    fun clearAll(done: () -> Unit) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            cm.flush()
            done()
        }
    }

    // --- visited sites (most recent first) -------------------------------------------------

    fun sites(context: Context): List<String> = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SITES, "").orEmpty()
        .split(',')
        .filter { it.isNotEmpty() }

    fun rememberSite(context: Context, url: String?) {
        val host = url?.let { Uri.parse(it).host }?.takeIf { it.isNotEmpty() } ?: return
        val list = sites(context)
        if (list.firstOrNull() == host) return
        val updated = (listOf(host) + list.filter { it != host }).take(MAX_SITES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SITES, updated.joinToString(",")).apply()
    }

    // --- per-site whitelist ("keep cookies") -------------------------------------------------

    fun whitelist(context: Context): List<String> = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_KEEP, "").orEmpty()
        .split(',')
        .filter { it.isNotEmpty() }

    fun hostOf(url: String?): String? =
        url?.let { try { Uri.parse(it).host } catch (_: Exception) { null } }
            ?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    /** True for the whitelisted host itself and any of its subdomains. */
    fun isWhitelisted(context: Context, url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return whitelist(context).any { host == it || host.endsWith(".$it") }
    }

    fun setWhitelisted(context: Context, host: String, on: Boolean) {
        val h = host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return
        val list = whitelist(context).filter { it != h }
        val updated = if (on) list + h else list
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_KEEP, updated.joinToString(",")).apply()
    }

    // --- JSON import / export --------------------------------------------------------------
    // The WebView API only exposes name + value, so that is what an export contains.

    fun exportJson(url: String): String {
        val arr = JSONArray()
        for (c in list(url)) arr.put(JSONObject().put("name", c.name).put("value", c.value))
        return JSONObject()
            .put("site", url)
            .put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
            .put("cookies", arr)
            .toString(2)
    }

    /**
     * Accepts `{"site":..., "cookies":[{name,value,path?,days?,secure?}]}` or a bare array.
     * Returns how many cookies were added, or -1 if the text isn't valid JSON of that shape.
     * [url] is where they are set (the JSON's own "site" is only used when [url] is null).
     */
    fun importJson(text: String, url: String?, done: (Int) -> Unit) {
        val t = text.trim()
        var target = url
        val arr: JSONArray = try {
            if (t.startsWith("[")) {
                JSONArray(t)
            } else {
                val o = JSONObject(t)
                if (target == null) target = normalizeSite(o.optString("site"))
                o.getJSONArray("cookies")
            }
        } catch (_: Exception) {
            done(-1)
            return
        }
        val site = target ?: run { done(-1); return }
        var added = 0
        val jobs = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty() || name.any { it == ';' || it == '=' || it.isWhitespace() }) continue
            jobs += o
        }
        if (jobs.isEmpty()) { done(0); return }
        var left = jobs.size
        for (o in jobs) {
            val days = if (o.has("days")) o.optInt("days").takeIf { it > 0 } else null
            add(
                site, o.optString("name"), o.optString("value").replace(";", ""),
                o.optString("path", "/").ifEmpty { "/" }, days,
                o.optBoolean("secure", site.startsWith("https://")), o.optBoolean("httpOnly", false)
            ) {
                added++
                if (--left == 0) done(added)
            }
        }
    }
}
