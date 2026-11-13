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

## Tools menu

Tap the menu button to open the **Tools** sheet:

- **Desktop site** — request the desktop version of pages (desktop user
  agent, layout viewport pinned to 1024px).
- **Cookies** — view, add and delete cookies for the current site.
- **Network recording (HAR)** — record traffic into a
  [HAR](https://en.wikipedia.org/wiki/HAR_(file_format)) session that
  survives page navigations. Start, pause/resume, clear, then share,
  save or browse the exported `.har` files.

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
