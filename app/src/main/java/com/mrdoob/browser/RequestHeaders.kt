package com.mrdoob.browser

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the headers WebView really sent (User-Agent, Accept, Referer, ...) for every
 * request, so the HAR can show them. Page JS cannot see these. Filled from
 * WebViewClient.shouldInterceptRequest (which never intercepts), read by NetworkBridge.
 */
object RequestHeaders {

    private const val MAX = 4000
    private val byKey = ConcurrentHashMap<String, List<Pair<String, String>>>()

    private fun key(method: String, url: String) = method.uppercase() + " " + url.substringBefore('#')

    fun remember(r: WebResourceRequest) {
        if (!HarRecorder.isRecording || !HarOptions.requestHeaders) return
        if (byKey.size > MAX) byKey.clear()
        byKey[key(r.method ?: "GET", r.url.toString())] = r.requestHeaders.map { it.key to it.value }
    }

    /** Native headers for this request merged over the ones page JS could see, plus Cookie. */
    fun merge(method: String, url: String, js: List<Pair<String, String>>): List<Pair<String, String>> {
        if (!HarOptions.requestHeaders) return js
        val native = byKey[key(method, url)]
        val out = LinkedHashMap<String, Pair<String, String>>()
        for (h in js) out[h.first.lowercase()] = h
        native?.forEach { out[it.first.lowercase()] = it }
        if (!out.containsKey("cookie")) {
            val c = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
            if (!c.isNullOrEmpty()) out["cookie"] = "Cookie" to c
        }
        return out.values.toList()
    }
}
