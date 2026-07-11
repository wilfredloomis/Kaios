(function () {
  "use strict";

  const port = browser.runtime.connectNative("kaiRuntime");
  let connected = true;

  function safePost(message) {
    if (!connected) return;
    try {
      port.postMessage(message);
    } catch (_) {
      connected = false;
    }
  }

  port.onDisconnect.addListener(() => {
    connected = false;
    dispatchToPage("kai-bridge-disconnected", {});
  });

  function dispatchToPage(type, detail) {
    const safeDetail = typeof cloneInto === "function"
      ? cloneInto(detail, document.defaultView)
      : detail;
    document.dispatchEvent(new CustomEvent(type, { detail: safeDetail }));
  }

  port.onMessage.addListener(message => {
    if (!message || typeof message !== "object") return;
    switch (message.type) {
      case "key":
        dispatchToPage("kai-key", message);
        break;
      case "response":
        dispatchToPage("kai-api-response", message);
        break;
      case "tcp":
        dispatchToPage("kai-tcp-event", message);
        break;
      case "runtime-info":
        dispatchToPage("kai-runtime-info", message);
        break;
      case "network-control":
        dispatchToPage("kai-network-control", message);
        break;
      default:
        break;
    }
  });

  document.addEventListener("kai-api-request", event => {
    const detail = event.detail;
    if (!detail || typeof detail.callbackId !== "string" || typeof detail.api !== "string") return;
    safePost({
      type: "api",
      callbackId: detail.callbackId,
      api: detail.api,
      args: detail.args || {}
    });
  });

  document.addEventListener("kai-runtime-log", event => {
    if (event.detail) safePost(event.detail);
  });

  document.addEventListener("kai-tcp-request", event => {
    if (location.hostname !== "127.0.0.1") return;
    const detail = event.detail;
    if (!detail || detail.type !== "tcp") return;
    safePost(detail);
  });

  document.addEventListener("kai-network-request", async event => {
    if (location.hostname !== "127.0.0.1") return;
    const detail = event.detail;
    if (!detail || typeof detail.id !== "string" || typeof detail.url !== "string") return;
    try {
      const response = await browser.runtime.sendMessage({ type: "kai-network-request", ...detail });
      dispatchToPage("kai-network-response", { id: detail.id, success: true, response });
    } catch (error) {
      dispatchToPage("kai-network-response", {
        id: detail.id,
        success: false,
        error: error && error.message ? error.message : String(error)
      });
    }
  });

  function injectRuntime() {
    if (!document.documentElement) {
      requestAnimationFrame(injectRuntime);
      return;
    }
    // Avoid double-injection if the HTML rewrite already loaded page-runtime.js.
    if (document.documentElement.dataset.kaiRuntimeInjected === "1") return;
    document.documentElement.dataset.kaiRuntimeInjected = "1";
    const script = document.createElement("script");
    script.src = browser.runtime.getURL("page-runtime.js");
    script.onload = () => script.remove();
    script.onerror = () => {
      // Fallback: the HTML-injected copy from AppHttpServer may still work.
    };
    document.documentElement.appendChild(script);
  }

  injectRuntime();
})();
