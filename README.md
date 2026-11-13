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
