# Architecture

## Runtime flow

1. `MainActivity` selects a package through Android's Storage Access Framework.
2. `KaiPackageInstaller` extracts it into a private staging directory with strict limits.
3. The root manifest and launch path are validated before staging is renamed into place.
4. `AppDatabase` records metadata and a stable, app-specific localhost port.
5. `RuntimeActivity` starts `AppHttpServer`, opens GeckoView, and installs the built-in bridge WebExtension.
6. The WebExtension injects `page-runtime.js` at document start and forwards messages through GeckoView native messaging.
7. Native API requests are checked against manifest and Android permissions before execution.

## Static file serving

`AppHttpServer` serves package assets over loopback. Correctness details that
matter for compatibility:

- Files are typed from an explicit MIME table (`HttpAssets.MIME_TYPES`). This is
  essential because GeckoView sends `X-Content-Type-Options: nosniff`, so a
  script served as `text/plain` is refused (the failure mode that broke real
  KaiOS apps such as `common/js/cache.js`).
- Directory requests fall back to `index.html`/`index.htm`.
- `GET`/`HEAD` support single `Range` requests (`206 Partial Content` with
  `Content-Range`) so audio and video can seek; `Accept-Ranges: bytes` is always
  advertised.
- `OPTIONS` returns `204` with an `Allow` header; other methods return `405`.
- HTML responses are rewritten in memory to inject the runtime bridge script and
  therefore never participate in range requests.

The pure logic (MIME typing, range parsing, path resolution, script injection)
lives in `HttpAssets` so it can be unit-tested on the JVM without a live socket.

## Trust boundaries

- Imported files are untrusted. Canonical paths, extracted byte count, entry count, launch path, and binary extensions are checked.
- App files are served only from the selected installation root by a loopback-only HTTP server.
- A unique stable port creates a distinct web origin for each app, isolating localStorage and IndexedDB.
- Messages from WebExtension content are treated as untrusted JSON and accepted only for known operations.
- Native capabilities are denied unless the installed manifest contains the relevant permission.
- The runtime does not expose `file://` URLs to installed content.

## Next stages

1. Add hosted HTTPS manifest installation with redirect, content-type, size, and private-address validation.
2. Add profile persistence for KaiOS 2.5, KaiOS 3.x, PWA, and custom screen settings.
3. Add Gecko permission delegates for standard web geolocation, media, and notifications.
4. Add per-app data clearing, orientation switching, screenshots, offline simulation, and configurable API mocks.
5. Add contacts, alarms, device storage, camera capture, sharing, and activity handlers.
