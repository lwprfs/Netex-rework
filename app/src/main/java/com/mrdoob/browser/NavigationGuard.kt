package com.mrdoob.browser

import android.net.Uri
import java.net.IDN
import java.util.Locale

/**
 * Decides whether a top-level navigation leaves the current website, and tracks the one
 * navigation that is waiting for the user's Stay / Leave answer.
 *
 * Only the main thread touches this object (WebViewClient callbacks and dialog callbacks).
 */
class NavigationGuard {

    /** A navigation held back until the user answers. [id] guards against stale answers. */
    class Pending(val id: Long, val url: String, val destinationLabel: String, val sourceHost: String, val kind: Kind, val headers: Map<String, String>)

    enum class Kind { LOAD, BACK, FORWARD }

    private var nextId = 0L
    var pending: Pending? = null
        private set

    /**
     * Set once the user chose Leave. Redirects that follow that approved load are not asked about
     * again. Cleared when the load finishes, fails, or another navigation starts.
     */
    private var approvedChain = false

    fun approveChain() { approvedChain = true }

    fun inApprovedChain(): Boolean = approvedChain

    fun endApprovedChain() { approvedChain = false }

    fun reset() { pending = null; approvedChain = false }

    companion object {

        /**
         * Chromium treats backslashes as slashes and drops tabs / newlines in http(s) URLs, while
         * android.net.Uri does not. Normalising first stops "https://evil.com\@example.com" from
         * being read as example.com here but loaded as evil.com.
         */
        private fun clean(url: String): String {
            val t = url.filter { it != '\t' && it != '\n' && it != '\r' }.trim()
            val cut = t.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) t.length else it }
            return t.substring(0, cut).replace('\\', '/') + t.substring(cut)
        }

        fun isWebUrl(url: String?): Boolean {
            val u = url?.trimStart()?.lowercase(Locale.ROOT) ?: return false
            return u.startsWith("http://") || u.startsWith("https://")
        }

        /** Lower-case ASCII (punycode) host without a trailing dot, or null if there is none. */
        fun hostOf(url: String?): String? {
            if (url == null || !isWebUrl(url)) return null
            val raw = try { Uri.parse(clean(url)).host } catch (_: Exception) { null }
            val host = raw?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
            val ascii = try { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { host }
            return ascii.lowercase(Locale.ROOT)
        }

        /** Host (and nothing else: no path, query or credentials) to show in the dialog. */
        fun labelOf(url: String): String {
            val host = hostOf(url) ?: return "an unrecognised address"
            return try { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { host }
        }

        /**
         * Whether going from [fromUrl] to [toUrl] crosses the website boundary. Only http(s) pages
         * count: about:blank, file:, custom schemes and a blank source never ask. A destination
         * whose host can't be parsed counts as a different site (fail closed).
         */
        fun crossesSite(fromUrl: String?, toUrl: String?): Boolean {
            if (!isWebUrl(toUrl)) return false
            val from = hostOf(fromUrl) ?: return false
            val to = hostOf(toUrl) ?: return true
            return from != to
        }
    }
}
