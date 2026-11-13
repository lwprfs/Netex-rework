package com.mrdoob.browser

import android.content.Context

/** Desktop-site preference plus the user agent / viewport tweaks that implement it. */
object DesktopMode {

    private const val PREFS = "netex_tools"
    private const val KEY = "desktop_mode"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }

    /** Desktop Chrome UA (no "Android" / "Mobile" tokens), reusing the WebView's Chrome version. */
    fun userAgent(mobileUa: String): String {
        val chrome = Regex("""Chrome/[\d.]+""").find(mobileUa)?.value ?: "Chrome/124.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36 Netex/0.1"
    }

    /**
     * WebView honours `<meta name=viewport content="width=device-width">` and would keep the mobile
     * layout even with a desktop UA, so pin the layout viewport to 1024px while desktop mode is on.
     */
    const val VIEWPORT_SCRIPT = """
(function () {
  function fix() {
    var m = document.querySelector('meta[name=viewport]');
    if (!m) {
      if (!document.head) return;
      m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
    }
    if (m.content !== 'width=1024') m.content = 'width=1024';
  }
  fix();
  var mo = new MutationObserver(fix);
  mo.observe(document, { childList: true, subtree: true });
  document.addEventListener('DOMContentLoaded', function () { fix(); mo.disconnect(); });
})();
"""
}
