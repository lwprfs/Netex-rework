package com.mrdoob.browser

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Edit and replay" for the Network tab. Sends the (possibly edited) request with a plain
 * HttpURLConnection, so it is not limited by the page's CORS rules and cookies are only sent when
 * the user explicitly asks for them. PATCH is sent as POST with X-HTTP-Method-Override, because
 * Android's HttpURLConnection refuses the PATCH verb.
 */
object Replayer {

    private const val MAX_BODY = 1_000_000
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    /** Blocking; call off the main thread. Returns the JSON delivered to the panel. */
    fun run(request: JSONObject): String {
        val started = System.currentTimeMillis()
        fun fail(message: String) = JSONObject().put("ok", false).put("error", message)
            .put("ms", System.currentTimeMillis() - started).toString()

        val method = request.optString("method", "GET").uppercase()
        val url = request.optString("url")
        if (method !in METHODS) return fail("Unsupported method: $method")
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return fail("Only http(s) URLs can be replayed")

        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            val sendBody = method == "POST" || method == "PUT" || method == "PATCH"
            conn.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")

            val headers = request.optJSONArray("headers") ?: JSONArray()
            for (i in 0 until headers.length()) {
                val h = headers.optJSONArray(i) ?: continue
                val name = h.optString(0).trim()
                if (name.isEmpty() || name.any { it == ':' || it.isWhitespace() }) continue
                if (name.equals("host", true) || name.equals("content-length", true)) continue
                conn.setRequestProperty(name, h.optString(1))
            }
            if (request.optBoolean("cookies", false)) {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotEmpty() }
                    ?.let { conn.setRequestProperty("Cookie", it) }
            }

            val body = request.optString("body")
            if (sendBody && body.isNotEmpty()) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < MAX_BODY) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, minOf(n, MAX_BODY - out.size()))
                }
                out.toByteArray()
            } ?: ByteArray(0)

            val resHeaders = JSONArray()
            for ((k, v) in conn.headerFields) {
                if (k != null) resHeaders.put(JSONArray().put(k).put(v.joinToString(", ")))
            }
            val type = conn.contentType.orEmpty()
            val textual = type.isEmpty() || type.contains("text", true) || type.contains("json", true) ||
                type.contains("xml", true) || type.contains("javascript", true) || type.contains("html", true) ||
                type.contains("x-www-form", true)
            return JSONObject()
                .put("ok", true)
                .put("status", status)
                .put("statusText", conn.responseMessage.orEmpty())
                .put("headers", resHeaders)
                .put("contentType", type)
                .put("size", bytes.size)
                .put("truncated", bytes.size >= MAX_BODY)
                .put("body", if (textual) String(bytes, Charsets.UTF_8) else "")
                .put("binary", !textual)
                .put("ms", System.currentTimeMillis() - started)
                .toString()
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
