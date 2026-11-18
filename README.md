# Netex Browser

A minimal Android browser with a built-in devtools panel. Drag up the
bottom bar to open it — four tabs:

- **Console** — live page logs plus a JS prompt that evaluates in
  the page's global scope.
- **Source** — formatted page HTML.
- **Network** — fetch/XHR log with inline image / glTF previews.
- **Three.js** — the vendored
  [Three.js DevTools](https://github.com/mrdoob/three.js/tree/dev/devtools)
  Chrome extension running in-process via a minimum `chrome.*` shim.
  Its background script drives the revision badge on the logo.

## Navigation

The bottom bar is now a full navigation dock: **back**, **forward**, a
**dev tools** toggle, the **menu** and the Three.js status button. The URL pill
shows the site (lock icon for HTTPS, warning for HTTP) and has a **reload / stop**
button built in. Tap the handle above the pill, tap the `</>` button, or drag the
bar up to open the panel; it animates open and snaps shut when barely open.
The panel has tabs for Console, Source, Network and Three.js, plus clear and close buttons.

## Menu

- **Share / Copy link / Open in… / Hard reload** quick actions.
- **Desktop site** - desktop user agent, layout viewport pinned to 1024px.
- **Cookies** - view, add, copy and delete cookies for the current site.
- **Page text size** - 85 / 100 / 125 / 150 %.
- **Clear browsing data** - cache, cookies, site storage, history.
- **Network capture (HAR)** - see below.

## Network capture (HAR)

Records traffic into a [HAR](https://en.wikipedia.org/wiki/HAR_(file_format)) session
that survives page navigations. Record, pause/resume, stop, then share, save or browse
the exported `.har` files. Three tabs:

- **Session** - live setup summary, presets (Lite / Balanced / Full), max requests,
  max stored size (MB, set manually) and *pause when full*.
- **Detail** - everything is switchable on its own:
  - request types: Fetch/XHR, Documents, Images, Scripts, Stylesheets, Fonts, Audio/video, Beacons & other
  - bodies with a manual per-item MB limit: text, images, fonts & binaries, request payloads
  - request headers, cookies, response headers, timing breakdown
  - redact sensitive values, third-party requests, skip URLs containing...
- **Export** - share, save to Downloads, file manager, clear.

Pages that request landscape on fullscreen (e.g. YouTube) rotate the app
through a `screen.orientation.lock()` shim.

## Install

Download the APK from the
[Releases page](https://github.com/lwprfs/Netex-rework/releases) and
sideload it. You may need to allow "install unknown apps" for the
browser or file manager you opened the file from.

CI also uploads a release APK as a build artifact on every push (see the
[Actions tab](https://github.com/lwprfs/Netex-rework/actions)).

## Build from source

```sh
git clone https://github.com/lwprfs/Netex-rework.git
cd Netex-rework
./gradlew :app:installDebug
```

Requirements:

- JDK 17 or newer (CI uses JDK 21)
- Android SDK with platform 35 and a `local.properties` pointing to it
  (`sdk.dir=/path/to/Android/sdk`)
- Min Android version: 8.0 (API 26)

To build a release APK locally: `./gradlew :app:assembleRelease`. Without
signing secrets it is signed with the debug key. CI signs it when
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`
are set as repository secrets.

## Dev Log

https://x.com/mrdoob/status/2051185470344953969

## Developer Tools compatibility

Settings → Developer Tools has eight sub-pages (General, Network Protection, Navigation Protection,
Anti-Debugging Detection, JavaScript Compatibility, Recording Protection, Automatic Protection,
Advanced Settings). Every switch is wired to real behaviour and shows whether it is automatic or manual.

- **Native navigation tracking** (`NavigationTracker`) is fed by WebViewClient callbacks, not page JS, so it
  survives destroyed JavaScript contexts. Redirect hops, reloads, errors and repeat counts are recorded.
  WebView does not expose redirect statuses or response bodies and cannot tell iframe documents from other
  requests; those rows are labelled *partially captured* and iframe documents are only counted.
- **Reload / loop detection** counts only automatic navigations (no tap, no Netex button) against
  configurable thresholds (default 3/10 s warn, 5/15 s protect, 8/30 s stronger), plus A→B→A cycle and
  redirect-revisit detection. A loop offers *Stop automatic navigation*; this refuses non-gesture
  navigations and pauses page timers. A page calling `location.reload()` from running script cannot be
  blocked by WebView otherwise.
- **Network log** is bounded, de-duplicated (stable ids for performance entries), keeps navigation ids and can
  optionally be saved across restarts (metadata only, no bodies or headers).
- **Anti-DevTools detection** combines several indicators (inline script scan, content removal, reload or
  redirect after opening the panel, optional debugger instrumentation). One indicator is never more than
  LOW. Compatibility mode is a set of protections, not a guaranteed bypass.
- **Per-site profiles** override global settings; protections the engine activates are session-only and
  never rewrite saved settings. Profiles can be exported and imported as JSON.
- Network tab: Preserve, Failed / Nav filters, Nav history (timeline and redirect chains), Events dashboard
  and a protection status strip.

Not verified on a device in this change set: the code was written without a Kotlin compiler available.
Build and run the ten scenarios before shipping.

## Navigation confirmation

Settings → Privacy & Security → Navigation Protection. `NavPolicy` (pure Kotlin, unit-tested in
`app/src/test`) decides allow / ask / block from the rules, allowed and blocked domain lists and per-website
modes. `MainActivity` applies it to `shouldOverrideUrlLoading` (taps, script and meta-refresh navigation, HTTP
redirects, external URL schemes) and `WebChromeClient.onCreateWindow` (target=_blank, window.open). Forms are held
by `nav-guard.js` because WebView never reports POST navigations. One dialog per redirect chain, one dialog at a
time, and a cancelled destination is dropped silently for 20 s so cancelling cannot loop. Blocked domains are also
refused for redirect hops in `shouldInterceptRequest`, and refused or cancelled navigations appear in the Network
tab and the Developer Tools event dashboard. Not covered: `form.submit()` called directly, navigation WebView does
not report, and the Referer header is limited to the origin when a confirmed navigation is re-issued.
