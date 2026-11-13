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
        w.name("httpVersion").value("unknown")
        w.name("cookies").beginArray().endArray()
        w.name("headers"); writePairs(w, r.requestHeaders)
        w.name("queryString"); writeQuery(w, r.url)
        if (r.requestBody.isNotEmpty()) {
            w.name("postData").beginObject()
                .name("mimeType").value(header(r.requestHeaders, "content-type").ifEmpty { "text/plain;charset=UTF-8" })
                .name("text").value(r.requestBody)
                .endObject()
        }
        w.name("headersSize").value(-1L)
        w.name("bodySize").value(r.requestBody.length.toLong())
        w.endObject()

        w.name("response").beginObject()
        w.name("status").value(r.status.toLong())
        w.name("statusText").value("")
        w.name("httpVersion").value("unknown")
        w.name("cookies").beginArray().endArray()
        w.name("headers"); writePairs(w, r.responseHeaders)
        w.name("content").beginObject()
        w.name("size").value(r.sizeBytes)
        w.name("mimeType").value(r.contentType.orEmpty())
        if (r.body.isNotEmpty()) w.name("text").value(r.body)
        w.endObject()
        w.name("redirectURL").value(header(r.responseHeaders, "location"))
        w.name("headersSize").value(-1L)
        w.name("bodySize").value(r.sizeBytes)
        w.endObject()

        w.name("cache").beginObject().endObject()
        w.name("timings").beginObject()
            .name("blocked").value(-1L)
            .name("dns").value(-1L)
            .name("connect").value(-1L)
            .name("ssl").value(-1L)
            .name("send").value(0L)
            .name("wait").value(r.durationMs)
            .name("receive").value(0L)
            .endObject()
        w.endObject()
    }
}
