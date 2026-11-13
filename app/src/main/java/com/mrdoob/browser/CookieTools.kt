package com.mrdoob.browser

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager

object CookieTools {

    data class Cookie(val name: String, val value: String)

    private const val PREFS = "netex_tools"
    private const val KEY_SITES = "cookie_sites"
    private const val MAX_SITES = 30

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

    fun delete(url: String, name: String, done: () -> Unit) {
        val u = Uri.parse(url)
        val host = u.host
        if (host == null) {
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
        var h = host
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
}
