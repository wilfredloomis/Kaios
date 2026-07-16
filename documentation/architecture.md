# Architecture

## Runtime flow

1. `MainActivity` selects a ZIP through Android's Storage Access Framework.
2. `KaiPackageInstaller` extracts it into private staging storage with traversal, binary, entry-count, single-file, and total-size checks.
3. A KaiOS manifest or PWA manifest is normalized; wrapped single-folder archives are flattened.
4. `AppDatabase` records metadata and a stable per-app localhost port.
5. `RuntimeActivity` starts `AppHttpServer`, opens a configured GeckoSession, and installs the built-in bridge WebExtension.
6. The WebExtension injects `page-runtime.js` at document start on both app and navigated web pages.
7. Native API requests are accepted only from the installed localhost origin and checked against manifest and Android permissions.

## Network model

- Normal same-origin and ordinary web requests use Gecko's native networking stack.
- Cross-origin requests are proxied only when the installed manifest declares `systemXHR`.
- The loopback server supports `GET`, `HEAD`, and single byte ranges.
- Files are streamed rather than loaded entirely into memory.
- Each installed app receives a stable unique origin, preserving separate cookies, localStorage, IndexedDB, service workers, and cache state.

## Browser and navigation model

- Top-level HTTP and HTTPS navigation remains inside the same GeckoSession.
- New-window requests are redirected into the current session.
- Non-web protocols are handed to Android through `ACTION_VIEW`.
- Android Back uses Gecko history before closing the runtime.
- The bridge follows top-level navigation so keypad and diagnostics still work on external web pages.
- Privileged native KaiOS APIs remain unavailable to those external pages.

## Compatibility layer

The injected layer supplies:

- KaiOS softkeys, D-pad, numeric keys, legacy key codes, repeat events, and a virtual Gamepad
- Hybrid native/proxied `fetch` and `XMLHttpRequest`
- `mozApps`, device storage, mobile connection, wake locks, audio channels, system messages
- local IndexedDB-backed contacts
- process-local alarms and settings
- `MozActivity`, localization, and orientation shims
- native battery, network, vibration, location, and notification bridge calls

These are compatibility implementations, not exact replicas of privileged KaiOS system services.

## Trust boundaries

- Imported package content is untrusted.
- Canonical path validation prevents ZIP traversal and loopback-server traversal.
- Native executable extensions are rejected.
- Native bridge messages are untrusted JSON.
- Privileged bridge calls require both the installed origin and a matching manifest permission.
- Standard HTTPS pages may use normal web permissions only when the containing installed app declares the matching capability and Android grants it.

## Remaining high-value work

- Persistent alarm scheduling through Android instead of in-process timers
- Real contacts/calendar providers with per-app user consent
- Download manager integration and save-as UI
- Site-specific permission prompts and remembered decisions
- Multiple screen/device profiles and orientation-aware layout
- Web push integration, background execution, and notification click routing
- Automated Android instrumentation tests with representative KaiOS apps
