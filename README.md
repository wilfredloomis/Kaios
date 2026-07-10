# Kai Runtime for Android

Kai Runtime is an Android shell for packaged KaiOS web applications. It embeds GeckoView, renders an app in a 240 x 320 profile, and provides a virtual D-pad, numeric keypad, softkeys, a small native API bridge, and an in-app runtime console.

This is an application runtime, not a KaiOS firmware or hardware emulator.

## Current MVP

- Imports ZIP packages with a root-level `manifest.webapp`
- Rejects traversal paths, oversized packages, excessive entries, and native binaries
- Stores installed packages in private app storage and metadata in SQLite
- Gives every app a stable localhost port so browser storage is isolated by origin
- Serves assets with correct MIME types, HTTP byte-range (media seeking), directory `index.html` fallback, and `OPTIONS`
- Runs content in GeckoView at a 240 x 320 screen profile
- Sends D-pad, number, star, hash, softkey, Enter, and Backspace events
- Repeats `keydown` for long virtual-key presses
- Exposes battery, network, vibration, location, and notification bridge calls
- Proxies cross-origin fetch/XHR for privileged apps that request `systemXHR`
- Provides legacy `mozApps`, device storage, mobile connection, and audio-channel shims
- Exposes the Promise-based Battery Status API (`navigator.getBattery`) backed by the real device
- Mirrors the shims under a KaiOS 3.x `navigator.b2g` namespace
- Supports process-local Firefox OS system-message registration and wake-lock mocks
- Checks KaiOS manifest permissions before privileged native calls
- Captures page console messages, errors, focused elements, key events, and API calls

Hosted manifests, orientation profiles, contacts, calendar, device storage, camera, activities, and mock telephony are not implemented yet.

## Build

Open this directory in Android Studio, or run:

```sh
./gradlew assembleDebug
```

The project uses Android Gradle Plugin 8.2.2, Kotlin 1.9.22, JDK 17 or newer, compile SDK 35, and GeckoView 128.

## Try the sample

Create an archive whose root contains the sample files:

```sh
cd sample-apps/hello-kaios
zip -r ../hello-kaios.zip .
```

Install the Android APK, tap **Import ZIP**, and select `sample-apps/hello-kaios.zip`. Use the virtual D-pad to move between actions and OK to activate one. Long-press an app in the launcher to remove it.

## JavaScript bridge

The document-start WebExtension exposes both `window.KaiRuntime` and `navigator.kaiRuntime`:

```js
const battery = await navigator.kaiRuntime.getBattery();
await navigator.kaiRuntime.vibrate(200);
const position = await navigator.kaiRuntime.getLocation();
await navigator.kaiRuntime.showNotification("Title", "Body");
```

See [`documentation/architecture.md`](documentation/architecture.md) for trust boundaries and the next implementation stages.
