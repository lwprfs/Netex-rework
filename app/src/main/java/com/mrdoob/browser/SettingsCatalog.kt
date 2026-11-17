package com.mrdoob.browser

import androidx.annotation.DrawableRes
import java.util.Locale

enum class SettingsCategory(
    val id: String,
    val title: String,
    val description: String,
    @DrawableRes val icon: Int,
    val keywords: List<String> = emptyList()
) {
    APPEARANCE("appearance", "Appearance", "Customise theme, colours and visual behaviour", R.drawable.ic_tune,
        listOf("look", "style", "dark mode", "light mode", "colour", "color")),
    GENERAL("general", "General", "Search engine, homepage and what opens at launch", R.drawable.ic_settings,
        listOf("start", "launch", "home page")),
    BROWSER("browser", "Browser", "Control how websites load and behave", R.drawable.ic_open,
        listOf("web", "page", "site")),
    PRIVACY("privacy", "Privacy & Security", "Browsing data, cookies and protection", R.drawable.ic_shield,
        listOf("clear", "delete", "data", "safe")),
    DEVELOPER("developer", "Developer Tools", "Console, Network and debugging preferences", R.drawable.ic_code,
        listOf("debug", "devtools", "inspect")),
    FLOATING("floating", "Floating Controls", "The floating record, reload and cookie bubble", R.drawable.ic_play,
        listOf("bubble", "overlay", "record")),
    CAPTURE("capture", "Network Capture", "Preferences for recording requests (HAR)", R.drawable.ic_network,
        listOf("har", "record", "requests", "traffic")),
    RULES("rules", "Request Rules", "Block, mock or delay network requests", R.drawable.ic_filter,
        listOf("block requests", "intercept", "mock response", "throttle", "slow network")),
    COOKIES("cookies", "Cookies", "Per-site cookie protection", R.drawable.ic_cookie,
        listOf("whitelist", "keep", "site data"));

    companion object {
        fun fromId(id: String?): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}

class SettingMeta(
    val id: String,
    val title: String,
    val description: String,
    val category: SettingsCategory,
    val keywords: List<String> = emptyList()
) {
    private val haystack = (listOf(title, description, category.title) + keywords)
        .joinToString(" ").lowercase(Locale.ROOT)
    private val titleLower = title.lowercase(Locale.ROOT)

    fun score(words: List<String>, whole: String): Int = when {
        titleLower.contains(whole) -> 0
        words.all { haystack.contains(it) } -> 1
        else -> -1
    }
}

object SettingsCatalog {

    val all: List<SettingMeta> = baseSettings() + devToolsSettings()

    private fun devToolsSettings(): List<SettingMeta> = DevToolsPrefs.Flag.entries.map { f ->
        SettingMeta(f.rowId, f.title, f.description.ifEmpty { f.section.title },
            SettingsCategory.DEVELOPER, listOf("devtools", "protection", f.section.title.lowercase(), f.key.replace('_', ' ')))
    }

    private fun baseSettings(): List<SettingMeta> = listOf(

        SettingMeta("appearance_theme", "Theme", "Light, dark, or follow the system", SettingsCategory.APPEARANCE,
            listOf("dark", "dark mode", "light", "night", "system")),
        SettingMeta("appearance_dynamic", "Dynamic colours", "Use your wallpaper's Material You colours", SettingsCategory.APPEARANCE,
            listOf("material you", "wallpaper", "dynamic colors")),
        SettingMeta("appearance_accent", "Accent colour", "Colour of buttons, tabs and highlights", SettingsCategory.APPEARANCE,
            listOf("accent color", "highlight", "tint")),
        SettingMeta("appearance_compact", "Compact address bar", "A slimmer address bar leaves more room for the page", SettingsCategory.APPEARANCE,
            listOf("url bar", "toolbar", "slim", "small")),
        SettingMeta("appearance_animations", "Animations", "Speed of panel, progress and bubble animations", SettingsCategory.APPEARANCE,
            listOf("motion", "reduce motion", "transitions")),
        SettingMeta("appearance_remember_tab", "Remember developer tools tab", "Reopen the Console / Source / Network tab you used last", SettingsCategory.APPEARANCE,
            listOf("last tab", "panel")),

        SettingMeta("general_engine", "Search engine", "Used when you type something that isn't a web address", SettingsCategory.GENERAL,
            listOf("google", "duckduckgo", "bing", "custom search")),
        SettingMeta("general_homepage", "Homepage", "Opened at launch when Startup is set to Homepage", SettingsCategory.GENERAL,
            listOf("home page", "start page")),
        SettingMeta("general_startup", "Startup", "What the browser opens when you start it", SettingsCategory.GENERAL,
            listOf("launch", "restore", "last page", "blank page", "continue")),

        SettingMeta("browser_js", "JavaScript", "Turn off to load pages without scripts (the page reloads)", SettingsCategory.BROWSER,
            listOf("scripts", "js", "disable javascript")),
        SettingMeta("browser_3p_cookies", "Third-party cookies", "Let embedded content from other sites set cookies", SettingsCategory.BROWSER,
            listOf("tracking", "embedded", "cross-site")),
        SettingMeta("browser_ua", "User agent", "Replaces the mobile user agent (Desktop site still overrides it)", SettingsCategory.BROWSER,
            listOf("desktop", "desktop site", "mobile", "ua", "browser identity")),
        SettingMeta("browser_nav_confirm", "Navigation Confirmation", "Ask before leaving the current website", SettingsCategory.BROWSER,
            listOf("leave website", "leave site", "navigation guard", "confirm navigation", "stay", "external link", "protection")),

        SettingMeta("privacy_library", "Bookmarks & history", "Search, edit and open them", SettingsCategory.PRIVACY,
            listOf("history", "browsing history", "bookmarks", "visited", "favourites")),
        SettingMeta("privacy_clear_history", "Clear browsing history", "Removes every visited page from the list", SettingsCategory.PRIVACY,
            listOf("history", "delete history", "erase", "visited pages")),
        SettingMeta("privacy_clear_cache", "Clear cache and site storage", "Cached files, local storage and IndexedDB for every site", SettingsCategory.PRIVACY,
            listOf("cache", "storage", "indexeddb", "local storage", "site data", "free space")),
        SettingMeta("privacy_clear_cookies", "Clear all cookies", "Signs you out of every site", SettingsCategory.PRIVACY,
            listOf("cookies", "sign out", "log out", "delete cookies")),

        SettingMeta("dev_keep_console", "Preserve console log", "Keep console messages when the page navigates or reloads", SettingsCategory.DEVELOPER,
            listOf("keep console", "persist logs")),
        SettingMeta("dev_timestamps", "Console timestamps", "Show the time each console message arrived", SettingsCategory.DEVELOPER,
            listOf("time", "clock")),
        SettingMeta("dev_clear_commands", "Clear command history", "Forget the JavaScript commands you ran in the console", SettingsCategory.DEVELOPER,
            listOf("console history", "commands")),

        SettingMeta("float_main", "Floating bubble", "Record, reload and cookie buttons on top of the browser (this app only)", SettingsCategory.FLOATING,
            listOf("overlay", "record button", "pop-up")),
        SettingMeta("float_haptics", "Haptic feedback", "A small tap on Play, Pause, Resume and Delete cookies", SettingsCategory.FLOATING,
            listOf("vibration", "vibrate")),
        SettingMeta("float_item_reload", "Hard reload button", "Show it in the bubble's ring", SettingsCategory.FLOATING,
            listOf("refresh", "ring")),
        SettingMeta("float_item_har", "Network capture settings button", "Show it in the bubble's ring", SettingsCategory.FLOATING,
            listOf("har", "ring")),
        SettingMeta("float_item_cookies", "Cookie settings button", "Show it in the bubble's ring", SettingsCategory.FLOATING,
            listOf("ring")),
        SettingMeta("float_item_delete", "Delete cookies and site data button", "Show it in the bubble's ring", SettingsCategory.FLOATING,
            listOf("ring", "clear")),

        SettingMeta("capture_site_only", "This site only", "Record only the site you started on; other sites are ignored", SettingsCategory.CAPTURE,
            listOf("har", "filter", "single site")),
        SettingMeta("capture_warn", "Warn before the limit", "Toast and amber bubble at 80% of the request / size limit", SettingsCategory.CAPTURE,
            listOf("limit", "warning", "har size")),

        SettingMeta("rules_delay", "Network delay", "Wait before every request is sent", SettingsCategory.RULES,
            listOf("throttle", "slow", "latency", "simulate")),
        SettingMeta("rules_list", "Rules", "Your block and mock response rules", SettingsCategory.RULES,
            listOf("edit rule", "delete rule", "block requests", "mock")),
        SettingMeta("rules_add", "Add rule", "Block a URL or answer it with your own response", SettingsCategory.RULES,
            listOf("block requests", "block url", "mock response", "new rule", "intercept")),

        SettingMeta("cookies_keep_site", "Keep cookies for this site", "The delete-cookies button and \"Clear this site\" will skip it", SettingsCategory.COOKIES,
            listOf("whitelist", "protect", "current site")),
        SettingMeta("cookies_whitelist", "Whitelisted sites", "Sites whose cookies are kept", SettingsCategory.COOKIES,
            listOf("kept websites", "exceptions", "allow list"))
    )

    fun byId(id: String): SettingMeta? = all.firstOrNull { it.id == id }

    class Results(val categories: List<SettingsCategory>, val settings: List<SettingMeta>) {
        val isEmpty get() = categories.isEmpty() && settings.isEmpty()
    }

    fun search(query: String): Results {
        val whole = query.trim().lowercase(Locale.ROOT)
        if (whole.isEmpty()) return Results(emptyList(), emptyList())
        val words = whole.split(Regex("\\s+"))
        val categories = SettingsCategory.entries.filter { c ->
            val hay = (listOf(c.title, c.description) + c.keywords).joinToString(" ").lowercase(Locale.ROOT)
            words.all { hay.contains(it) }
        }
        val settings = all.mapNotNull { s -> s.score(words, whole).takeIf { it >= 0 }?.let { it to s } }
            .sortedBy { it.first }
            .map { it.second }
        return Results(categories, settings)
    }
}
