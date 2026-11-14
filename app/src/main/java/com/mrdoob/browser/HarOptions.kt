package com.mrdoob.browser

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * User-selectable detail captured into HAR recordings.
 * Every switch is independent, and every size limit is set manually (in MB).
 */
object HarOptions {

    /** Request categories that can be recorded (or left out) individually. */
    enum class Kind(val key: String, val label: String) {
        FETCH("fetch", "Fetch / XHR"),
        DOCUMENT("document", "Documents"),
        IMAGE("image", "Images"),
        SCRIPT("script", "Scripts"),
        STYLE("style", "Stylesheets"),
        FONT("font", "Fonts"),
        MEDIA("media", "Audio / video"),
        OTHER("other", "Beacons & other");

        companion object {
            /** [kind] comes from the page shim; older records fall back to the content type. */
            fun from(kind: String?, contentType: String?): Kind {
                when (kind?.lowercase()) {
                    "fetch", "xhr" -> return FETCH
                    "document", "frame", "iframe" -> return DOCUMENT
                    "image", "img" -> return IMAGE
                    "script" -> return SCRIPT
                    "style", "css" -> return STYLE
                    "font" -> return FONT
                    "media" -> return MEDIA
                    "beacon", "other" -> return OTHER
                }
                val ct = contentType?.lowercase().orEmpty()
                return when {
                    ct.startsWith("image/") -> IMAGE
                    ct.contains("html") -> DOCUMENT
                    ct.contains("javascript") || ct.contains("ecmascript") -> SCRIPT
                    ct.contains("css") -> STYLE
                    ct.startsWith("font/") || ct.contains("woff") -> FONT
                    ct.startsWith("audio/") || ct.startsWith("video/") -> MEDIA
                    else -> FETCH
                }
            }
        }
    }

    enum class Preset { LITE, BALANCED, FULL }

    const val MIN_LIMIT_MB = 0.01f
    const val MAX_LIMIT_MB = 256f
    const val MIN_ENTRIES = 50
    const val MAX_ENTRIES = 20000
    const val MIN_TOTAL_MB = 1f
    const val MAX_TOTAL_MB = 200f

    private const val PREFS = "netex_tools"
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun bool(key: String, def: Boolean): Boolean = prefs?.getBoolean(key, def) ?: def
    private fun setBool(key: String, v: Boolean) { prefs?.edit()?.putBoolean(key, v)?.apply() }
    private fun float(key: String, def: Float): Float = prefs?.getFloat(key, def) ?: def
    private fun setFloat(key: String, v: Float) { prefs?.edit()?.putFloat(key, v)?.apply() }
    private fun int(key: String, def: Int): Int = prefs?.getInt(key, def) ?: def
    private fun setInt(key: String, v: Int) { prefs?.edit()?.putInt(key, v)?.apply() }

    // --- what to record ---------------------------------------------------------------

    fun recordKind(kind: Kind): Boolean {
        // Before per-type switches existed, one "extra requests" switch covered everything non-core.
        val legacy = if (kind == Kind.FETCH || kind == Kind.DOCUMENT || kind == Kind.IMAGE) true
        else bool("har_resources", true)
        return bool("har_kind_" + kind.key, legacy)
    }

    fun setRecordKind(kind: Kind, on: Boolean) = setBool("har_kind_" + kind.key, on)

    // --- bodies -------------------------------------------------------------------------

    /** Text response bodies (JS, CSS, HTML, JSON, XML...). */
    var textBodies: Boolean
        get() = bool("har_bodies", true); set(v) = setBool("har_bodies", v)

    /** Images embedded as base64. */
    var images: Boolean
        get() = bool("har_images", true); set(v) = setBool("har_images", v)

    /** Fonts and other binary files embedded as base64. */
    var fonts: Boolean
        get() = bool("har_fonts", true); set(v) = setBool("har_fonts", v)

    /** Bodies of POST / PUT / PATCH requests. */
    var requestBodies: Boolean
        get() = bool("har_reqbodies", true); set(v) = setBool("har_reqbodies", v)

    // --- size limits (MB, per item) --------------------------------------------------------

    var textLimitMb: Float
        get() = float("har_lim_text", 8f); set(v) = setFloat("har_lim_text", v.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB))

    var imageLimitMb: Float
        get() = float("har_lim_image", 3f); set(v) = setFloat("har_lim_image", v.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB))

    var fontLimitMb: Float
        get() = float("har_lim_font", 3f); set(v) = setFloat("har_lim_font", v.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB))

    var requestBodyLimitMb: Float
        get() = float("har_lim_req", 0.25f); set(v) = setFloat("har_lim_req", v.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB))

    // --- headers & cookies ------------------------------------------------------------------

    /** Real request headers sent by the browser. */
    var requestHeaders: Boolean
        get() = bool("har_headers", true); set(v) = setBool("har_headers", v)

    /** The Cookie request header (and Set-Cookie when visible). */
    var cookies: Boolean
        get() = bool("har_cookies", true); set(v) = setBool("har_cookies", v)

    var responseHeaders: Boolean
        get() = bool("har_reshead", true); set(v) = setBool("har_reshead", v)

    /** DNS / connect / TLS / wait / receive breakdown. */
    var timings: Boolean
        get() = bool("har_timings", true); set(v) = setBool("har_timings", v)

    // --- privacy & filters ----------------------------------------------------------------

    /** Mask Authorization, cookies, API keys and token-like URL parameters. */
    var redact: Boolean
        get() = bool("har_redact", false); set(v) = setBool("har_redact", v)

    /** Requests to other sites than the page being visited. */
    var thirdParty: Boolean
        get() = bool("har_3p", true); set(v) = setBool("har_3p", v)

    /**
     * Record only the site the recording was started on. Unlike [thirdParty] (which follows
     * whatever page is open), this pins the site at Record time; other sites are ignored.
     * Stored under a non-"har_" key so presets don't reset it.
     */
    var siteOnly: Boolean
        get() = bool("site_only", false); set(v) = setBool("site_only", v)

    /** Warn (toast + amber pill) when a session is about to hit its limits. */
    var warnNearLimit: Boolean
        get() = bool("warn_near_limit", true); set(v) = setBool("warn_near_limit", v)

    /** Comma separated URL fragments; matching requests are skipped. */
    var excludeUrls: String
        get() = prefs?.getString("har_exclude", "") ?: ""
        set(v) { prefs?.edit()?.putString("har_exclude", v)?.apply() }

    @Volatile private var excludeRaw = ""
    @Volatile private var excludeParsed: List<String> = emptyList()

    fun isExcluded(url: String): Boolean {
        val raw = excludeUrls
        if (raw != excludeRaw) {
            excludeParsed = raw.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            excludeRaw = raw
        }
        if (excludeParsed.isEmpty()) return false
        val u = url.lowercase()
        return excludeParsed.any { u.contains(it) }
    }

    // --- session limits -----------------------------------------------------------------------

    var maxEntries: Int
        get() = int("har_max_entries", 3000); set(v) = setInt("har_max_entries", v.coerceIn(MIN_ENTRIES, MAX_ENTRIES))

    var maxTotalMb: Float
        get() = float("har_max_total", 24f); set(v) = setFloat("har_max_total", v.coerceIn(MIN_TOTAL_MB, MAX_TOTAL_MB))

    /** Pause when a session limit is hit instead of dropping the oldest requests. */
    var stopWhenFull: Boolean
        get() = bool("har_stopfull", false); set(v) = setBool("har_stopfull", v)

    fun maxTotalBytes(): Long = (maxTotalMb.toDouble() * 1024.0 * 1024.0).toLong()

    /** [mb] as a character cap that fits an Int. */
    fun capChars(mb: Float): Int =
        (mb.toDouble() * 1024.0 * 1024.0).toLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    // --- page-shim probes ---------------------------------------------------------------------

    /** Used by the page-side shim: option [name] while a recording is running. */
    fun enabled(name: String): Boolean {
        if (!HarRecorder.isRecording) return false
        return when {
            name == "bodies" -> textBodies
            name == "images" -> images
            name == "fonts" -> fonts
            name == "headers" -> requestHeaders
            name == "reqbodies" -> requestBodies
            name.startsWith("kind:") -> recordKind(Kind.from(name.substring(5), null))
            else -> false
        }
    }

    /** Size limit for the page shim, in bytes. */
    fun limitBytes(name: String): Double {
        val mb = when (name) {
            "bodies" -> textLimitMb
            "images" -> imageLimitMb
            "fonts" -> fontLimitMb
            "reqbodies" -> requestBodyLimitMb
            else -> 0f
        }
        return mb.toDouble() * 1024.0 * 1024.0
    }

    // --- presets --------------------------------------------------------------------------------

    fun resetAll() {
        val p = prefs ?: return
        val e = p.edit()
        for (k in p.all.keys) if (k.startsWith("har_")) e.remove(k)
        e.apply()
    }

    fun applyPreset(preset: Preset) {
        resetAll()
        when (preset) {
            Preset.BALANCED -> Unit
            Preset.LITE -> {
                for (k in Kind.entries) setRecordKind(k, k == Kind.FETCH || k == Kind.DOCUMENT)
                textLimitMb = 1f
                images = false
                fonts = false
                requestBodyLimitMb = 0.1f
                cookies = false
                redact = true
                maxEntries = 1000
                maxTotalMb = 8f
            }
            Preset.FULL -> {
                textLimitMb = 32f
                imageLimitMb = 10f
                fontLimitMb = 10f
                requestBodyLimitMb = 4f
                maxEntries = 8000
                maxTotalMb = 96f
            }
        }
    }

    // --- display --------------------------------------------------------------------------------

    fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun summary(): String {
        val kinds = Kind.entries.filter { recordKind(it) }
        val types = when (kinds.size) {
            Kind.entries.size -> "all request types"
            0 -> "nothing (every type is off)"
            else -> kinds.joinToString(", ") { it.label }
        }
        val bodies = buildList {
            add(if (textBodies) "text ≤ ${fmt(textLimitMb)} MB" else "text off")
            add(if (images) "images ≤ ${fmt(imageLimitMb)} MB" else "images off")
            add(if (fonts) "fonts ≤ ${fmt(fontLimitMb)} MB" else "fonts off")
        }.joinToString(" · ")
        val headers = buildList {
            if (requestHeaders) add("request headers")
            if (cookies) add("cookies")
            if (responseHeaders) add("response headers")
            if (timings) add("timings")
        }.ifEmpty { listOf("no headers") }.joinToString(", ")
        val extra = buildList {
            if (redact) add("secrets redacted")
            if (!thirdParty) add("first-party only")
            if (siteOnly) add("this site only")
            if (excludeUrls.isNotBlank()) add("custom exclusions")
        }
        return buildString {
            append("Recording: ").append(types).append('\n')
            append("Bodies: ").append(bodies).append('\n')
            append("Headers: ").append(headers)
            if (extra.isNotEmpty()) append('\n').append("Privacy: ").append(extra.joinToString(", "))
            append('\n').append("Limit: up to ").append(maxEntries).append(" requests / ")
                .append(fmt(maxTotalMb)).append(" MB")
            if (stopWhenFull) append(" (then pause)")
        }
    }
}
