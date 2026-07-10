(function () {
  "use strict";

  const MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

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

  browser.runtime.onMessage.addListener(async (message, sender) => {
    if (!message || message.type !== "kai-network-request") return undefined;
    if (!isInstalledApp(sender.url) || !isPublicHttpUrl(message.url)) {
      throw new Error("Network proxy rejected this origin or target");
    }

    const headers = new Headers(message.headers || {});
    const body = message.bodyBase64
      ? Uint8Array.from(atob(message.bodyBase64), character => character.charCodeAt(0))
      : undefined;
    const response = await fetch(message.url, {
      method: message.method || "GET",
      headers,
      body: message.method === "GET" || message.method === "HEAD" ? undefined : body,
      credentials: message.credentials === "include" ? "include" : "omit",
      redirect: "follow",
      cache: message.cache || "default"
    });
    const declaredLength = Number(response.headers.get("content-length") || 0);
    if (declaredLength > MAX_RESPONSE_BYTES) throw new Error("Network response exceeds 10 MB");
    const responseBytes = new Uint8Array(await response.arrayBuffer());
    if (responseBytes.length > MAX_RESPONSE_BYTES) throw new Error("Network response exceeds 10 MB");

    const responseHeaders = {};
    response.headers.forEach((value, name) => { responseHeaders[name] = value; });
    return {
      status: response.status,
      statusText: response.statusText,
      url: response.url,
      headers: responseHeaders,
      bodyBase64: bytesToBase64(responseBytes)
    };
  });
})();
