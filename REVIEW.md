# Project Review — Kai Runtime 0.3.0

## Main causes of partial app behavior found

1. External navigation disconnected or rejected the bridge, preventing browser-style apps from keeping keypad/runtime support.
2. A custom XMLHttpRequest replaced the browser implementation globally but did not implement enough of the XHR state machine, events, response types, headers, upload events, timeout, or synchronous behavior.
3. The local HTTP server read most files as whole byte arrays and did not support byte ranges, which breaks seeking and can destabilize large games/media.
4. File inputs, camera, microphone, popup, full-screen, and history delegates were missing.
5. Synthetic keypad events omitted legacy numeric fields used by many KaiOS games and older apps.
6. The installer accepted only one exact archive shape and rejected common PWA or wrapped-folder packages.
7. Several expected Firefox OS objects were absent, causing feature-detection failures before an app reached its fallback path.

## Engine update

- Updated GeckoView from `128.0.20240725162350` to the newest published stable artifact found during this review, `150.0.20260511200624`.
- Updated the mobile KaiOS-flavored user agent to Firefox/Gecko 150.
- This is the highest-impact compatibility change for modern sites such as Telegram Web and Discord, although those services can still depend on APIs or policies that a compatibility shell cannot fully emulate.

## Enhancements applied

- Reworked package import and validation
- Rebuilt the loopback file server for streaming and range requests
- Replaced the XHR shim with a hybrid native/proxy implementation
- Expanded key, Gamepad, legacy API, and PWA compatibility
- Added Gecko navigation, content, prompt, and permission delegates
- Added file upload and standard media permission flow
- Added origin-aware bridge security
- Added JVM tests for package safety and the HTTP server

## Validation performed

- JavaScript syntax checks passed for all extension scripts.
- Extension JSON and Android XML parsed successfully.
- `AppHttpServer` compiled with Kotlin 1.9 and passed live socket smoke tests for runtime injection, range responses, and traversal rejection.
- `PackageSafety` compiled with Kotlin 1.9 and passed traversal/native-binary smoke tests.
- ZIP integrity is checked when the enhanced project archive is produced.

## Build limitation in this environment

A full Android build was not possible here because the uploaded archive omitted `gradle/wrapper/gradle-wrapper.jar`, and this execution environment has no Android SDK or cached GeckoView artifact. The Kotlin/JavaScript changes were therefore validated through targeted compilation, syntax checks, and runtime smoke tests rather than an APK build.


## 0.3.1 log-driven fixes

- Corrected duplicate/manual XHR handler invocation that cleared `currentTarget` before `onload` callbacks.
- Replaced plain-object storage requests and stores with EventTarget-compatible implementations.
- Added DOMCursor `done` state so applications do not treat the final null result as a file.
- Added DeviceStorage change notifications and editable method aliases.
- Reordered GeckoView/GeckoSession destruction to detach the view and clear delegates before session close.


## 0.3.4 note
Added fitted viewport safe-area layout to reduce overlay collisions with app UI.


## 0.3.5 UI note
The runtime toolbar now auto-hides and the LOG/KEYS controls are collapsed into a narrow side dock to reduce app-content obstruction.
