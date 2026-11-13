package com.mrdoob.browser

import android.net.Uri

object UrlDisplay {

    fun shorten(url: String, pageUrl: String): String {
        val pageDir = pageDir(pageUrl)
        if (pageDir.isNotEmpty() && url.startsWith(pageDir)) {
            return url.substring(pageDir.length).ifEmpty { "./" }
        }
        val pageOrigin = pageOrigin(pageUrl)
        if (pageOrigin.isNotEmpty() && url.startsWith(pageOrigin)) {
            return url.substring(pageOrigin.length)
        }
        return url
    }

    private fun pageDir(pageUrl: String): String {
        val cleanUrl = pageUrl.substringBefore('?').substringBefore('#')
        val lastSlash = cleanUrl.lastIndexOf('/')
        return if (lastSlash < 0) "" else cleanUrl.substring(0, lastSlash + 1)
    }

    private fun pageOrigin(pageUrl: String): String {
        val uri = try { Uri.parse(pageUrl) } catch (_: Exception) { return "" }
        val scheme = uri.scheme ?: return ""
        val authority = uri.authority ?: return ""
        return "$scheme://$authority"
    }
}
