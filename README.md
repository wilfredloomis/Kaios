# Kai Runtime for Android

Kai Runtime is an Android compatibility shell for packaged KaiOS/Firefox OS web applications and small PWAs. It embeds GeckoView, maps Android and on-screen keys to KaiOS-style keyboard events, offers a fullscreen touch-friendly runtime shell, and supplies a compatibility layer for selected legacy APIs.

It is **not** a full KaiOS firmware emulator. Apps that depend on proprietary system services, carrier APIs, DRM, certified-only APIs, or exact device firmware behavior can still require app-specific work.

## Version 0.3.5 improvements

- Automatically hides the runtime toolbar after 3.2 seconds to expose more of the running app
- Restores the toolbar by tapping near the top edge of the app
- Replaces the two permanent bottom buttons with a compact collapsible side handle
- Expands the side handle only when `LOG` or `KEYS` is needed, then collapses it automatically
- Reserves a narrow side safe area so the collapsed control does not cover app content
- Expands the app vertically when the toolbar is hidden while preserving safe margins
- Adds a trusted in-page **Enable sound** control for videos that remain muted after tapping the site UI
- Tracks and resumes suspended Web Audio contexts after a real touch/key gesture
- Re-applies unmute briefly when site scripts immediately set `muted` or volume `0` again
- Injects media compatibility into child frames and `about:blank`/`srcdoc` player frames
- Adds old hls.js `hls.loadLevel(...)` call compatibility for IPTV-style applications
- Requests Android media audio focus and routes hardware volume keys to the music stream
- Reworks the runtime UI into a true fullscreen app surface instead of a fixed 240 × 320 box
- Hides Android system bars in immersive mode for more usable screen space
- Replaces the always-visible keypad and log with compact floating toggles (`KEYS` and `LOG`)
- Keeps touch apps usable in fullscreen while preserving virtual KaiOS keys for non-touch apps
- Lets tapping the app area dismiss the keypad quickly and return to the app view
- Enlarges virtual key buttons for easier touch use on Android phones
- Fixes `XMLHttpRequest` event-handler dispatch so `event.currentTarget` remains valid in HLS players and other libraries
- Implements event-capable `DOMRequest`, `DOMCursor`, and `DeviceStorage` objects
- Adds DeviceStorage `change` events, `done` cursor state, editable aliases, and storage-status compatibility
- Detaches GeckoView and clears delegates before closing sessions, avoiding the session-store teardown race
- Imports `manifest.webapp`, `manifest.webmanifest`, and `manifest.json` packages
- Accepts archives wrapped in one top-level directory
- Raises package limits to 250 MB total, 100 MB per file, and 10,000 entries
- Streams large files and supports HTTP `Range`/`HEAD` requests for audio, video, games, fonts, and WebAssembly
- Adds MIME coverage for WebAssembly, WebM, MP4, fonts, PDFs, manifests, and common audio formats
- Preserves native same-origin `fetch`/`XMLHttpRequest`; only privileged cross-origin requests use the `systemXHR` proxy
- Supports larger proxied requests and responses with explicit limits and timeouts
- Keeps the native bridge connected while an app navigates to HTTPS pages
- Restricts privileged native KaiOS bridge calls to the installed localhost app origin
- Supports browser history/back, popups, external protocols, full-screen mode, and crash recovery
- Supports HTML file inputs through Android's document picker
- Routes standard web geolocation, notifications, camera, and microphone permissions through GeckoView and Android
- Emits legacy keyboard fields (`keyCode`, `which`, `charCode`) and exposes a virtual standard Gamepad
- Adds compatibility shims for contacts, alarms, settings, localization, orientation, `MozActivity`, device storage, mobile connection, `mozApps`, wake locks, and system messages
- Uses a modern KaiOS-flavored Firefox 150 user agent for better mobile-site compatibility

## What this improves

These changes remove several common causes of partial behavior in browser apps, web messengers, media apps, and games: blocked navigation, incomplete XHR semantics, missing file upload, missing byte-range streaming, incorrect keypad events, unavailable camera/microphone routing, and absent legacy API objects.

Telegram Web, Discord Web, and other complex sites still depend on their own current browser requirements and may change independently. The runtime now gives them a substantially more complete Gecko/Web API surface, but it cannot guarantee every feature of every service.

## Build

Open the directory in Android Studio, or run:

```sh
./gradlew assembleDebug
```

Requirements:

- JDK 17+
- Android SDK 35
- Android Gradle Plugin 8.2.2
- Kotlin 2.3.20
- GeckoView 150 (`150.0.20260511200624`)

The uploaded project did not include `gradle/wrapper/gradle-wrapper.jar`. Regenerate it with a local Gradle 8.5 installation (`gradle wrapper --gradle-version 8.5`) before using `./gradlew`.

Run the JavaScript compatibility smoke test with:

```sh
node tools/runtime-smoke-test.mjs
```

## Package layout

A ZIP may contain files directly at its root or inside one enclosing folder. Supported manifests:

- `manifest.webapp`
- `manifest.webmanifest`
- `manifest.json`

For a PWA manifest, `start_url` is used as the launch path and a canonical `manifest.webapp` is generated for the compatibility layer.

## Native bridge

The injected runtime exposes `window.KaiRuntime` and `navigator.kaiRuntime`:

```js
const battery = await navigator.kaiRuntime.getBattery();
await navigator.kaiRuntime.vibrate(200);
const position = await navigator.kaiRuntime.getLocation();
await navigator.kaiRuntime.showNotification("Title", "Body");
```

Native calls still require the matching permission in the installed app manifest.

See [`documentation/architecture.md`](documentation/architecture.md) and [`REVIEW.md`](REVIEW.md).
