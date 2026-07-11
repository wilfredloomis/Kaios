(function () {
  "use strict";

  const MAX_RESPONSE_BYTES = 15 * 1024 * 1024;
  const MAX_REDIRECTS = 8;

  function isInstalledApp(url) {
    try {
      const parsed = new URL(url);
      return parsed.protocol === "http:" && parsed.hostname === "127.0.0.1";
    } catch (_) {
      return false;
    }
  }

  function isPublicHttpUrl(url) {
    try {
      const parsed = new URL(url);
      if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return false;
      const host = parsed.hostname.toLowerCase();
      if (host === "localhost" || host === "::1" || host.endsWith(".local")) return false;
      if (/^127\./.test(host) || /^10\./.test(host) || /^169\.254\./.test(host) || /^192\.168\./.test(host)) return false;
      const private172 = /^172\.(\d{1,2})\./.exec(host);
      if (private172 && Number(private172[1]) >= 16 && Number(private172[1]) <= 31) return false;
      // IPv6 unique-local / link-local
      if (host.startsWith("[") && (host.includes("fc") || host.includes("fd") || host.includes("fe80"))) {
        // crude; reject bracketed private-looking v6
        if (/^\[(fc|fd|fe80)/i.test(host)) return false;
      }
      return true;
    } catch (_) {
      return false;
    }
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
    }
    return btoa(binary);
  }

  function sanitizeOutboundHeaders(raw) {
    const headers = new Headers();
    const blocked = new Set([
      "host", "connection", "content-length", "transfer-encoding",
      "keep-alive", "proxy-connection", "upgrade", "te", "trailer"
    ]);
    const source = raw || {};
    Object.keys(source).forEach(name => {
      const lower = name.toLowerCase();
      if (blocked.has(lower)) return;
      try {
        headers.set(name, source[name]);
      } catch (_) {
        // Invalid header name/value – skip.
      }
    });
    if (!headers.has("User-Agent")) {
      headers.set(
        "User-Agent",
        "Mozilla/5.0 (Mobile; rv:48.0) Gecko/48.0 Firefox/48.0 KAIOS/2.5"
      );
    }
    if (!headers.has("Accept")) headers.set("Accept", "*/*");
    return headers;
  }

  browser.runtime.onMessage.addListener(async (message, sender) => {
    if (!message || message.type !== "kai-network-request") return undefined;
    if (!isInstalledApp(sender.url) || !isPublicHttpUrl(message.url)) {
      throw new Error("Network proxy rejected this origin or target");
    }

    const method = (message.method || "GET").toUpperCase();
    const headers = sanitizeOutboundHeaders(message.headers);
    const body = message.bodyBase64
      ? Uint8Array.from(atob(message.bodyBase64), character => character.charCodeAt(0))
      : undefined;

    let response;
    try {
      response = await fetch(message.url, {
        method,
        headers,
        body: method === "GET" || method === "HEAD" ? undefined : body,
        credentials: message.credentials === "include" ? "include" : "omit",
        redirect: "follow",
        cache: message.cache || "default",
        // mode "cors" is fine for extension background which is privileged.
        mode: "cors"
      });
    } catch (error) {
      throw new Error(`Network request failed: ${error && error.message ? error.message : error}`);
    }

    const declaredLength = Number(response.headers.get("content-length") || 0);
    if (declaredLength > MAX_RESPONSE_BYTES) throw new Error("Network response exceeds 15 MB");

    const responseBytes = new Uint8Array(await response.arrayBuffer());
    if (responseBytes.length > MAX_RESPONSE_BYTES) throw new Error("Network response exceeds 15 MB");

    const responseHeaders = {};
    response.headers.forEach((value, name) => { responseHeaders[name] = value; });
    // Strip hop-by-hop and framing headers that confuse XHR consumers.
    delete responseHeaders["content-encoding"];
    delete responseHeaders["transfer-encoding"];
    delete responseHeaders["content-length"];
    // Advertise CORS-friendly headers so the page Response object is usable.
    responseHeaders["access-control-allow-origin"] = "*";

    return {
      status: response.status,
      statusText: response.statusText || "",
      url: response.url,
      headers: responseHeaders,
      bodyBase64: bytesToBase64(responseBytes)
    };
  });
})();
