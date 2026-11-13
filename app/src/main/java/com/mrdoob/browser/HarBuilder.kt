package com.mrdoob.browser

import android.net.Uri
import android.util.JsonWriter
import java.io.Writer
import java.time.Instant

/** HAR 1.2 serialisation and the file-naming rules for exported recordings. */
object HarBuilder {

    private const val MAX_NAME = 120
    private val UNSAFE = Regex("""[\\/:*?"<>|,\u0000-\u001F]""")
    private val SPACES = Regex("""\s+""")

    // --- naming ----------------------------------------------------------------------

    private fun clean(s: String) = s.trim().replace(SPACES, "_").replace(UNSAFE, "_").trim('_', '.')

    private fun host(u: Uri): String {
        val h = (u.host ?: u.scheme ?: "page").lowercase().removePrefix("www.")
        return if (u.port != -1) "$h:${u.port}" else h
    }

    private fun segments(u: Uri): List<String> = u.pathSegments.filter { it.isNotBlank() }

    /** Identity of a page: host + path, ignoring scheme, `www.`, query, fragment and trailing `/`. */
    fun pageKey(url: String): String {
        val u = Uri.parse(url)
        return host(u) + "/" + segments(u).joinToString("/")
    }

    /**
     * One page  → `example.com_blog_post-1`
     * Many pages → `example.com,about,contact` (main site, then each page's name; the main
     * site's own root page is represented by the first part). Spaces become `_`.
     */
    fun fileBase(urls: List<String>): String {
        if (urls.isEmpty()) return "netex"
        val uris = urls.map { Uri.parse(it) }
        val main = host(uris[0])
        val name = if (uris.size == 1) {
            (listOf(main) + segments(uris[0])).map(::clean).filter { it.isNotEmpty() }.joinToString("_")
        } else {
            val pageNames = uris.mapNotNull { u ->
                val h = host(u)
                val last = segments(u).lastOrNull()
                when {
                    h == main -> last
                    last == null -> h
                    else -> "${h}_$last"
                }
            }.map(::clean).filter { it.isNotEmpty() }
            var out = clean(main)
            for (p in pageNames) {
                if (out.length + 1 + p.length > MAX_NAME) break
                out += ",$p"
            }
            out
        }
        return name.take(MAX_NAME).ifEmpty { "netex" }
    }

    // --- HAR -------------------------------------------------------------------------

    fun write(snap: HarRecorder.Snapshot, out: Writer) {
        val w = JsonWriter(out)
        w.setIndent("  ")
        w.beginObject()
        w.name("log").beginObject()
        w.name("version").value("1.2")
        w.name("creator").beginObject()
            .name("name").value("Netex Browser")
            .name("version").value(BuildConfig.VERSION_NAME)
            .endObject()
        w.name("pages").beginArray()
        snap.pages.forEach { writePage(w, it) }
        w.endArray()
        w.name("entries").beginArray()
        snap.entries.sortedBy { it.startedAt }.forEach { writeEntry(w, it) }
        w.endArray()
        w.endObject()
        w.endObject()
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun writePage(w: JsonWriter, p: HarRecorder.Page) {
        w.beginObject()
        w.name("startedDateTime").value(iso(p.startedAt))
        w.name("id").value(p.id)
        w.name("title").value(p.title.ifEmpty { p.url })
        w.name("pageTimings").beginObject()
            .name("onContentLoad").value(-1L)
            .name("onLoad").value(-1L)
            .endObject()
        w.endObject()
    }

    private fun writePairs(w: JsonWriter, pairs: List<Pair<String, String>>) {
        w.beginArray()
        for ((k, v) in pairs) w.beginObject().name("name").value(k).name("value").value(v).endObject()
        w.endArray()
    }

    private fun writeQuery(w: JsonWriter, url: String) {
        val uri = Uri.parse(url)
        val pairs = ArrayList<Pair<String, String>>()
        try {
            for (n in uri.queryParameterNames) for (v in uri.getQueryParameters(n)) pairs += n to v
        } catch (_: Exception) { /* opaque URI */ }
        writePairs(w, pairs)
    }

    private fun httpVersion(p: String): String = when (p.lowercase()) {
        "h2" -> "HTTP/2.0"
        "h3", "h3-29" -> "HTTP/3.0"
        "http/1.1" -> "HTTP/1.1"
        "http/1.0" -> "HTTP/1.0"
        else -> if (p.isEmpty()) "unknown" else p
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"; 304 -> "Not Modified"
        307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }

    /** `Cookie` request header → HAR cookie objects. */
    private fun writeCookies(w: JsonWriter, headers: List<Pair<String, String>>) {
        w.beginArray()
        val raw = headers.firstOrNull { it.first.equals("cookie", ignoreCase = true) }?.second.orEmpty()
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            w.beginObject().name("name").value(part.substring(0, i).trim())
                .name("value").value(part.substring(i + 1).trim()).endObject()
        }
        w.endArray()
    }

    private fun utf8Len(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()

    private fun header(h: List<Pair<String, String>>, name: String): String =
        h.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second.orEmpty()

    private fun writeEntry(w: JsonWriter, e: HarRecorder.Entry) {
        val r = e.record
        w.beginObject()
        e.pageId?.let { w.name("pageref").value(it) }
        w.name("startedDateTime").value(iso(e.startedAt))
        w.name("time").value(r.durationMs)

        w.name("request").beginObject()
        w.name("method").value(r.method)
        w.name("url").value(r.url)
        w.name("httpVersion").value(httpVersion(r.protocol))
        w.name("cookies"); writeCookies(w, r.requestHeaders)
        w.name("headers"); writePairs(w, r.requestHeaders)
        w.name("queryString"); writeQuery(w, r.url)
        if (r.requestBody.isNotEmpty()) {
            w.name("postData").beginObject()
                .name("mimeType").value(header(r.requestHeaders, "content-type").ifEmpty { "text/plain;charset=UTF-8" })
                .name("text").value(r.requestBody)
                .endObject()
        }
        w.name("headersSize").value(-1L)
        w.name("bodySize").value(if (r.requestBody.isEmpty()) 0L else utf8Len(r.requestBody))
        w.endObject()

        // Full body for HAR when the shim sent one, else the (possibly clipped) panel body.
        val text = if (r.harBody.isNotEmpty()) r.harBody else r.body
        val decoded = when {
            r.decodedSize >= 0 && (r.harBase64 || text.isEmpty()) -> r.decodedSize
            text.isNotEmpty() && !r.harBase64 -> utf8Len(text)
            r.decodedSize >= 0 -> r.decodedSize
            else -> r.sizeBytes
        }
        val encoded = when {
            r.encodedSize > 0 -> r.encodedSize
            r.transferSize > 0 -> r.transferSize
            else -> r.sizeBytes
        }

        w.name("response").beginObject()
        w.name("status").value(r.status.toLong())
        w.name("statusText").value(statusText(r.status))
        w.name("httpVersion").value(httpVersion(r.protocol))
        w.name("cookies").beginArray().endArray()
        w.name("headers"); writePairs(w, r.responseHeaders)
        w.name("content").beginObject()
        w.name("size").value(decoded)
        if (encoded in 1 until decoded) w.name("compression").value(decoded - encoded)
        w.name("mimeType").value(r.contentType.orEmpty())
        if (text.isNotEmpty()) {
            w.name("text").value(text)
            if (r.harBase64) w.name("encoding").value("base64")
        }
        w.endObject()
        w.name("redirectURL").value(header(r.responseHeaders, "location"))
        w.name("headersSize").value(-1L)
        w.name("bodySize").value(encoded)
        if (r.transferSize >= 0) w.name("_transferSize").value(r.transferSize)
        w.endObject()

        w.name("cache").beginObject().endObject()
        val t = r.timings
        w.name("timings").beginObject()
        if (t != null && t.size >= 7) {
            w.name("blocked").value(t[0]).name("dns").value(t[1]).name("connect").value(t[2])
                .name("ssl").value(t[3]).name("send").value(maxOf(0L, t[4]))
                .name("wait").value(maxOf(0L, t[5])).name("receive").value(maxOf(0L, t[6]))
        } else {
            w.name("blocked").value(-1L).name("dns").value(-1L).name("connect").value(-1L)
                .name("ssl").value(-1L).name("send").value(0L)
                .name("wait").value(r.durationMs).name("receive").value(0L)
        }
        w.endObject()
        w.endObject()
    }
}
