package com.mrdoob.browser

import android.net.Uri
import java.net.IDN
import java.util.Locale

class NavigationGuard {

    class Pending(val id: Long, val url: String, val destinationLabel: String, val sourceHost: String, val kind: Kind, val headers: Map<String, String>)

    enum class Kind { LOAD, BACK, FORWARD }

    private var nextId = 0L
    var pending: Pending? = null
        private set

    fun begin(url: String, label: String, sourceHost: String, kind: Kind, headers: Map<String, String> = emptyMap()): Pending =
        Pending(++nextId, url, label, sourceHost, kind, headers).also { pending = it }

    fun take(id: Long): Pending? {
        val p = pending?.takeIf { it.id == id } ?: return null
        pending = null
        return p
    }

    fun clearPending() { pending = null }

    private var approvedChain = false

    fun approveChain() { approvedChain = true }

    fun inApprovedChain(): Boolean = approvedChain

    fun endApprovedChain() { approvedChain = false }

    fun reset() { pending = null; approvedChain = false }

    companion object {

        private fun clean(url: String): String {
            val t = url.filter { it != '\t' && it != '\n' && it != '\r' }.trim()
            val cut = t.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) t.length else it }
            return t.substring(0, cut).replace('\\', '/') + t.substring(cut)
        }

        fun isWebUrl(url: String?): Boolean {
            val u = url?.trimStart()?.lowercase(Locale.ROOT) ?: return false
            return u.startsWith("http://") || u.startsWith("https://")
        }

        fun hostOf(url: String?): String? {
            if (url == null || !isWebUrl(url)) return null
            val raw = try { Uri.parse(clean(url)).host } catch (_: Exception) { null }
            val host = raw?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
            val ascii = try { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { host }
            return ascii.lowercase(Locale.ROOT)
        }

        fun labelOf(url: String): String {
            val host = hostOf(url) ?: return "an unrecognised address"
            return try { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { host }
        }

        fun crossesSite(fromUrl: String?, toUrl: String?): Boolean {
            if (!isWebUrl(toUrl)) return false
            val from = hostOf(fromUrl) ?: return false
            val to = hostOf(toUrl) ?: return true
            return from != to
        }
    }
}
