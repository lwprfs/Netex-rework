package com.mrdoob.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Browsing history and bookmarks, kept as small JSON files in the app's private storage.
 * Reads happen from memory; every change is written on a background thread.
 */
object BrowserLibrary {

    data class Visit(val url: String, val title: String, val time: Long)
    data class Bookmark(val id: String, val title: String, val url: String, val added: Long)

    private const val MAX_HISTORY = 3000
    private const val SAME_PAGE_WINDOW_MS = 30 * 60 * 1000L

    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var dir: File? = null
    private var loaded = false
    private val history = ArrayList<Visit>() // newest first
    private val bookmarks = ArrayList<Bookmark>() // oldest first

    fun init(context: Context) {
        synchronized(lock) { if (dir == null) dir = context.applicationContext.filesDir }
    }

    /** Warm the cache off the main thread. */
    fun preload() {
        io.execute { synchronized(lock) { ensureLoaded() } }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val d = dir ?: return
        try {
            val h = File(d, "history.json")
            if (h.exists()) {
                val arr = JSONArray(h.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    history += Visit(o.optString("u"), o.optString("t"), o.optLong("w"))
                }
            }
        } catch (_: Exception) { /* corrupt file: start empty */ }
        try {
            val b = File(d, "bookmarks.json")
            if (b.exists()) {
                val arr = JSONArray(b.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    bookmarks += Bookmark(o.optString("i"), o.optString("t"), o.optString("u"), o.optLong("a"))
                }
            }
        } catch (_: Exception) { /* corrupt file: start empty */ }
    }

    private fun saveHistory() {
        val d = dir ?: return
        val json = JSONArray().also { arr ->
            history.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("w", it.time)) }
        }.toString()
        io.execute { writeAtomic(File(d, "history.json"), json) }
    }

    private fun saveBookmarks() {
        val d = dir ?: return
        val json = JSONArray().also { arr ->
            bookmarks.forEach {
                arr.put(JSONObject().put("i", it.id).put("t", it.title).put("u", it.url).put("a", it.added))
            }
        }.toString()
        io.execute { writeAtomic(File(d, "bookmarks.json"), json) }
    }

    private fun writeAtomic(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) { /* best effort */ }
    }

    // --- history ---------------------------------------------------------------------------

    fun recordVisit(url: String?, title: String?) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return
        synchronized(lock) {
            ensureLoaded()
            val now = System.currentTimeMillis()
            val cleanTitle = title?.trim().orEmpty()
            val top = history.firstOrNull()
            if (top != null && top.url == url && now - top.time < SAME_PAGE_WINDOW_MS) {
                // Reload / redirect settle on the same page: refresh instead of duplicating.
                history[0] = top.copy(title = cleanTitle.ifEmpty { top.title }, time = now)
            } else {
                history.add(0, Visit(url, cleanTitle, now))
                while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
            }
            saveHistory()
        }
    }

    fun history(): List<Visit> = synchronized(lock) { ensureLoaded(); ArrayList(history) }

    fun removeVisit(v: Visit) {
        synchronized(lock) { ensureLoaded(); if (history.remove(v)) saveHistory() }
    }

    fun restoreVisit(v: Visit) {
        synchronized(lock) {
            ensureLoaded()
            val idx = history.indexOfFirst { it.time < v.time }.let { if (it < 0) history.size else it }
            history.add(idx, v)
            saveHistory()
        }
    }

    /** Removes visits newer than [sinceMs] (0 = everything). Returns how many were removed. */
    fun clearHistory(sinceMs: Long): Int = synchronized(lock) {
        ensureLoaded()
        val before = history.size
        history.removeAll { it.time >= sinceMs }
        if (history.size != before) saveHistory()
        before - history.size
    }

    // --- bookmarks -------------------------------------------------------------------------

    private fun key(url: String) = url.substringBefore('#').trimEnd('/')

    fun bookmarks(): List<Bookmark> = synchronized(lock) { ensureLoaded(); ArrayList(bookmarks) }

    fun isBookmarked(url: String?): Boolean = url != null && synchronized(lock) {
        ensureLoaded(); bookmarks.any { key(it.url) == key(url) }
    }

    fun addBookmark(title: String, url: String): Bookmark? = synchronized(lock) {
        ensureLoaded()
        if (bookmarks.any { key(it.url) == key(url) }) return null
        val b = Bookmark(UUID.randomUUID().toString(), title.trim().ifEmpty { url }, url.trim(), System.currentTimeMillis())
        bookmarks += b
        saveBookmarks()
        b
    }

    fun removeBookmark(b: Bookmark) {
        synchronized(lock) { ensureLoaded(); if (bookmarks.remove(b)) saveBookmarks() }
    }

    fun removeBookmarkByUrl(url: String) {
        synchronized(lock) {
            ensureLoaded()
            if (bookmarks.removeAll { key(it.url) == key(url) }) saveBookmarks()
        }
    }

    fun restoreBookmark(b: Bookmark) {
        synchronized(lock) {
            ensureLoaded()
            if (bookmarks.none { it.id == b.id }) {
                bookmarks.add(b)
                bookmarks.sortBy { it.added }
                saveBookmarks()
            }
        }
    }

    fun updateBookmark(id: String, title: String, url: String) {
        synchronized(lock) {
            ensureLoaded()
            val i = bookmarks.indexOfFirst { it.id == id }
            if (i >= 0) {
                bookmarks[i] = bookmarks[i].copy(title = title.trim().ifEmpty { url }, url = url.trim())
                saveBookmarks()
            }
        }
    }

    /** Netscape bookmark HTML: understood by Chrome, Firefox and by [importBookmarks]. */
    fun exportBookmarksHtml(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        for (b in bookmarks()) {
            sb.append("    <DT><A HREF=\"").append(escape(b.url)).append("\" ADD_DATE=\"")
                .append(b.added / 1000).append("\">").append(escape(b.title)).append("</A>\n")
        }
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    /** Accepts Netscape HTML or a JSON list of {title,url}. Returns how many new bookmarks were added. */
    fun importBookmarks(text: String): Int {
        val t = text.trim()
        val found = ArrayList<Pair<String, String>>() // title to url
        if (t.startsWith("[") || t.startsWith("{")) {
            try {
                val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("bookmarks") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").ifEmpty { o.optString("u") }
                    if (url.isNotEmpty()) found += o.optString("title").ifEmpty { o.optString("t") } to url
                }
            } catch (_: Exception) { return -1 }
        } else {
            val re = Regex("""<a\s[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (m in re.findAll(t)) {
                found += unescape(m.groupValues[2].replace(Regex("<[^>]+>"), "")) to unescape(m.groupValues[1])
            }
            if (found.isEmpty()) return -1
        }
        var added = 0
        for ((title, url) in found) {
            if (!(url.startsWith("http://") || url.startsWith("https://"))) continue
            if (addBookmark(title, url) != null) added++
        }
        return added
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").trim()
}
