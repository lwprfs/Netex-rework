package com.mrdoob.browser

import java.util.Locale

/**
 * Pure decision logic of the navigation confirmation system (no Android classes, so it is unit-tested on the JVM).
 *
 * Relations between the current page and the destination:
 *  - SAME_ORIGIN: same scheme, host and port ("www." is ignored: www.example.com == example.com)
 *  - SAME_SITE:   same registrable domain, different origin (a subdomain, another port or scheme)
 *  - CROSS_SITE:  different registrable domain
 * Subdomains are never trusted unless the user turns on [Rule.TRUST_SUBDOMAINS] or lists "*.domain".
 *
 * The registrable domain is a heuristic (last two labels, three for common multi-part suffixes such as
 * co.uk); it is not the full public-suffix list.
 */
object NavPolicy {

    enum class Type(val label: String, val initiator: String) {
        LINK_CLICK("Link tap", "You tapped a link"),
        SCRIPT("JavaScript navigation", "Page script, without a tap"),
        AUTOMATIC("Automatic navigation", "Page script or meta refresh, without a tap (WebView does not say which)"),
        META_REFRESH("Meta refresh redirect", "Page markup (meta refresh)"),
        HTTP_REDIRECT("HTTP redirect", "Server redirect (HTTP 3xx)"),
        FORM("Form submission", "A form on the page"),
        NEW_WINDOW("New window", "target=_blank or window.open()"),
        EXTERNAL_SCHEME("External app link", "A link that opens another app")
    }

    enum class Relation(val label: String) {
        SAME_ORIGIN("Same origin"), SAME_SITE("Same site, different origin"), CROSS_SITE("Different site")
    }

    enum class Action { ALLOW, ASK, BLOCK }
    enum class SiteMode { ALLOW, ASK, BLOCK }

    enum class Rule(val key: String, val title: String, val description: String, val default: Boolean) {
        CONFIRM_EXTERNAL("confirm_external", "Confirm external domain navigation",
            "Ask before leaving for a different site (or an untrusted subdomain)", true),
        CONFIRM_SCRIPT("confirm_script", "Confirm JavaScript-triggered navigation",
            "Page script changes the address without a tap", true),
        CONFIRM_AUTO("confirm_auto", "Confirm automatic redirects",
            "HTTP redirects, meta refresh and other navigation nobody asked for", true),
        CONFIRM_NEW_WINDOW("confirm_new_window", "Confirm new-window navigation",
            "target=_blank links and window.open()", true),
        CONFIRM_UNKNOWN("confirm_unknown", "Confirm unknown domains",
            "Domains you never visited and have not allowed", true),
        ALLOW_SAME_ORIGIN("allow_same_origin", "Allow same-origin navigation automatically",
            "Moving inside the same origin never asks", true),
        CONFIRM_EVERY("confirm_every", "Confirm every main-frame navigation",
            "Also asks for same-origin navigation. Typed addresses, bookmarks, back and forward never ask.", false),
        CONFIRM_HTTPS_UPGRADE("confirm_https_upgrade", "Confirm HTTP-to-HTTPS redirects",
            "Off: upgrading the same host to HTTPS is allowed silently", false),
        CONFIRM_SCHEME("confirm_scheme", "Confirm external app links",
            "intent:, market:, tel:, mailto: and other URL schemes that open another app", true),
        TRUST_SUBDOMAINS("trust_subdomains", "Trust subdomains of the same site",
            "Off by default: blog.example.com is not automatically trusted from www.example.com", false),
        SMART_WARNINGS("smart_warnings", "Warn about suspicious navigation",
            "Adds a warning, and asks, when several signs of an unwanted redirect appear", true)
    }

    class Config(
        val rules: Map<Rule, Boolean> = emptyMap(),
        val allowed: Set<String> = emptySet(),
        val blocked: Set<String> = emptySet(),
        val siteModes: Map<String, SiteMode> = emptyMap(),
        val visited: (String) -> Boolean = { false }
    ) {
        fun on(r: Rule): Boolean = rules[r] ?: r.default
    }

    class Request(
        val from: String?,
        val to: String,
        val type: Type,
        /** A tap (or other user gesture) started this navigation, or the redirect chain it belongs to. */
        val userInitiated: Boolean,
        val msSincePageLoad: Long = Long.MAX_VALUE,
        val redirectHops: Int = 0
    )

    class Decision(
        val action: Action,
        val reason: String,
        val relation: Relation?,
        val warnings: List<String> = emptyList()
    )

    // --- URL helpers ----------------------------------------------------------------------

    private fun clean(url: String?): String? =
        url?.filter { it != '\t' && it != '\n' && it != '\r' }?.trim()?.takeIf { it.isNotEmpty() }

    fun schemeOf(url: String?): String? {
        val t = clean(url) ?: return null
        val s = t.substringBefore(':', "").lowercase(Locale.ROOT)
        return s.takeIf { it.isNotEmpty() && it[0].isLetter() && it.all { c -> c.isLetterOrDigit() || c == '+' || c == '-' || c == '.' } }
    }

    fun isWeb(url: String?): Boolean = schemeOf(url).let { it == "http" || it == "https" }

    /** "user@host:port" part of a web URL, parsed by hand so malformed query strings cannot hide the host. */
    private fun authority(url: String?): String? {
        val t = clean(url)?.replace('\\', '/') ?: return null
        val i = t.indexOf("://")
        if (i < 0) return null
        val rest = t.substring(i + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return (if (end < 0) rest else rest.substring(0, end)).substringAfterLast('@')
    }

    private fun hostPart(auth: String): String =
        if (auth.startsWith("[")) auth.substringBefore(']') + "]" else auth.substringBeforeLast(':', auth)

    /** Lower-case host without "www." and without a trailing dot, or null. */
    fun hostOf(url: String?): String? {
        if (!isWeb(url)) return null
        val auth = authority(url) ?: return null
        return hostPart(auth).trim().trimEnd('.').lowercase(Locale.ROOT).removePrefix("www.").takeIf { it.isNotEmpty() }
    }

    private fun portOf(url: String?, scheme: String): Int {
        val auth = authority(url) ?: return -1
        val p = if (auth.startsWith("[")) auth.substringAfter("]:", "") else if (auth.contains(':')) auth.substringAfterLast(':') else ""
        return p.toIntOrNull() ?: if (scheme == "https") 443 else 80
    }

    fun originOf(url: String?): String? {
        val scheme = schemeOf(url) ?: return null
        val host = hostOf(url) ?: return null
        return "$scheme://$host:${portOf(url, scheme)}"
    }

    private val MULTI_SUFFIX = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp", "or.jp",
        "com.br", "com.cn", "com.hk", "com.tw", "com.sg", "com.mx", "com.tr", "com.ar", "co.in", "co.kr",
        "co.za", "com.ua", "com.pl", "com.ru"
    )

    fun registrableDomain(host: String): String {
        if (host.all { it.isDigit() || it == '.' || it == ':' }) return host // IP address
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in MULTI_SUFFIX && labels.size >= 3) labels.takeLast(3).joinToString(".") else last2
    }

    fun relation(from: String?, to: String?): Relation? {
        val toHost = hostOf(to) ?: return null
        val fromHost = hostOf(from) ?: return Relation.CROSS_SITE
        if (originOf(from) == originOf(to)) return Relation.SAME_ORIGIN
        return if (registrableDomain(fromHost) == registrableDomain(toHost)) Relation.SAME_SITE else Relation.CROSS_SITE
    }

    /** List entries: "host" (exact), "*.host" (host and every subdomain). */
    fun listMatches(list: Set<String>, host: String, trustSubdomains: Boolean): Boolean {
        if (host in list) return true
        for (e in list) {
            if (e.startsWith("*.")) {
                val base = e.substring(2)
                if (host == base || host.endsWith(".$base")) return true
            } else if (trustSubdomains && host.endsWith(".$e")) return true
        }
        return false
    }

    private val AD_NAME = Regex(
        "(^|[.-])(ads?|adserver|adservice|adsystem|adsterra|popads|popcash|exoclick|clickadu|propellerads|" +
            "trafficjunky|doubleclick|adnxs|popunder|clickserve)([.-]|$)"
    )

    // --- decision -------------------------------------------------------------------------------

    fun decide(req: Request, cfg: Config): Decision {
        if (req.type == Type.EXTERNAL_SCHEME) {
            val scheme = (schemeOf(req.to) ?: "") + ":"
            return when {
                scheme in cfg.blocked -> Decision(Action.BLOCK, "Scheme $scheme is on your blocked list", null)
                scheme in cfg.allowed -> Decision(Action.ALLOW, "Scheme $scheme is on your allowed list", null)
                cfg.on(Rule.CONFIRM_SCHEME) -> Decision(Action.ASK, "This link opens another app", null)
                else -> Decision(Action.ALLOW, "External app confirmation is off", null)
            }
        }
        val toHost = hostOf(req.to) ?: return Decision(Action.ALLOW, "Not a web address", null)
        val fromHost = hostOf(req.from)
        val rel = relation(req.from, req.to) ?: Relation.CROSS_SITE
        val trust = cfg.on(Rule.TRUST_SUBDOMAINS)
        val warnings = if (cfg.on(Rule.SMART_WARNINGS)) warnings(req, rel, toHost) else emptyList()

        if (listMatches(cfg.blocked, toHost, false)) {
            return Decision(Action.BLOCK, "$toHost is on your blocked list", rel, warnings)
        }
        val mode = fromHost?.let { cfg.siteModes[it] }
        if (mode == SiteMode.BLOCK && rel != Relation.SAME_ORIGIN) {
            return Decision(Action.BLOCK, "${fromHost} is set to always block navigation away from it", rel, warnings)
        }
        if (listMatches(cfg.allowed, toHost, trust)) {
            return Decision(Action.ALLOW, "$toHost is on your allowed list", rel)
        }
        if (mode == SiteMode.ALLOW) {
            return Decision(Action.ALLOW, "${fromHost} is set to always allow navigation", rel)
        }

        val every = cfg.on(Rule.CONFIRM_EVERY)
        val upgrade = isHttpsUpgrade(req)
        val triggers = ArrayList<String>()
        if (mode == SiteMode.ASK && rel != Relation.SAME_ORIGIN) triggers += "${fromHost} is set to always ask"
        if (every) triggers += "Confirm every main-frame navigation is on"

        if (upgrade) {
            if (cfg.on(Rule.CONFIRM_HTTPS_UPGRADE)) triggers += "HTTP to HTTPS redirect"
            else if (!every && mode != SiteMode.ASK) return Decision(Action.ALLOW, "HTTPS upgrade of the same host", rel)
        } else if (rel == Relation.SAME_ORIGIN) {
            if (cfg.on(Rule.ALLOW_SAME_ORIGIN) && !every && mode != SiteMode.ASK) {
                return Decision(Action.ALLOW, "Same-origin navigation", rel)
            }
        } else {
            val trusted = rel == Relation.SAME_SITE && trust
            if (!trusted) {
                if (cfg.on(Rule.CONFIRM_EXTERNAL)) {
                    triggers += if (rel == Relation.CROSS_SITE) "External domain: $toHost" else "Subdomain or other origin is not trusted: $toHost"
                }
                if (cfg.on(Rule.CONFIRM_UNKNOWN) && !cfg.visited(toHost)) triggers += "You have not visited $toHost before"
                if (!req.userInitiated) {
                    when (req.type) {
                        Type.SCRIPT -> if (cfg.on(Rule.CONFIRM_SCRIPT)) triggers += "Triggered by JavaScript without a tap"
                        Type.AUTOMATIC -> {
                            if (cfg.on(Rule.CONFIRM_SCRIPT) || cfg.on(Rule.CONFIRM_AUTO)) triggers += "Automatic navigation without a tap"
                        }
                        Type.META_REFRESH, Type.HTTP_REDIRECT -> if (cfg.on(Rule.CONFIRM_AUTO)) triggers += "Automatic redirect"
                        else -> Unit
                    }
                }
            }
        }
        if (req.type == Type.NEW_WINDOW && cfg.on(Rule.CONFIRM_NEW_WINDOW) && rel != Relation.SAME_ORIGIN) {
            triggers += "Opens in a new window"
        }

        if (triggers.isNotEmpty()) return Decision(Action.ASK, triggers.joinToString("; "), rel, warnings)
        // Rules would let it through, but the page shows several signs of an unwanted redirect.
        if (warnings.isNotEmpty() && rel != Relation.SAME_ORIGIN && !(rel == Relation.SAME_SITE && trust)) {
            return Decision(Action.ASK, "Suspicious navigation pattern", rel, warnings)
        }
        return Decision(Action.ALLOW, "No rule asks for confirmation", rel)
    }

    fun isHttpsUpgrade(req: Request): Boolean =
        req.type == Type.HTTP_REDIRECT && schemeOf(req.from) == "http" && schemeOf(req.to) == "https" &&
            hostOf(req.from) != null && hostOf(req.from) == hostOf(req.to)

    /**
     * Signs of an unwanted redirect. Each needs the navigation to leave the origin without a user
     * action; none of them alone proves anything, so the dialog words them as observations.
     */
    fun warnings(req: Request, rel: Relation, toHost: String): List<String> {
        if (rel == Relation.SAME_ORIGIN || req.userInitiated && req.type != Type.NEW_WINDOW) return emptyList()
        val w = ArrayList<String>()
        val auto = req.type == Type.SCRIPT || req.type == Type.AUTOMATIC || req.type == Type.META_REFRESH
        if (req.type == Type.NEW_WINDOW && !req.userInitiated) w += "A window was opened without a tap (possible pop-up or pop-under)"
        if (auto && req.msSincePageLoad < 2500) w += "Navigation started right after the page loaded"
        if (req.redirectHops >= 3) w += "Repeated redirects (${req.redirectHops} hops so far)"
        if (!req.userInitiated && rel == Relation.CROSS_SITE) w += "Leaves for an unrelated site without a tap"
        if (!req.userInitiated && AD_NAME.containsMatchIn(toHost)) w += "The destination's name looks like an advertising domain (name only, not verified)"
        if (req.type == Type.SCRIPT && !req.userInitiated) w += "Script-driven navigation without user action"
        return w
    }
}
