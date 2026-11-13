package com.mrdoob.browser

/** Masks credentials before they are written into a HAR (optional "Redact sensitive values"). */
object Redact {

    const val MASK = "[redacted]"

    private val HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "x-api-key", "x-auth-token", "x-access-token", "x-csrf-token", "x-xsrf-token"
    )

    private val PARAMS = Regex(
        """([?&](?:access_token|refresh_token|id_token|token|api[_-]?key|apikey|key|secret|password|passwd|pwd|auth|sig|signature|session|sid)=)[^&#]*""",
        RegexOption.IGNORE_CASE
    )

    fun headers(list: List<Pair<String, String>>): List<Pair<String, String>> =
        list.map { (k, v) -> if (k.lowercase() in HEADERS) k to MASK else k to v }

    fun url(url: String): String = PARAMS.replace(url, "\$1$MASK")
}
