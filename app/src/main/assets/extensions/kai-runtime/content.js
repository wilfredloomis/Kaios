(function () {
  "use strict";

  const port = browser.runtime.connectNative("kaiRuntime");
  let connected = true;
  let trustKnown = false;
  let trustedAppOrigin = false;
  const queuedNetworkRequests = [];

  function safePost(message) {
    if (!connected) return;
    try {
      port.postMessage(message);
    } catch (_) {
      connected = false;
    }
  }

  function dispatchToPage(type, detail) {
    const safeDetail = typeof cloneInto === "function"
      ? cloneInto(detail, document.defaultView)
      : detail;
    document.dispatchEvent(new CustomEvent(type, { detail: safeDetail }));
  }

  function keyMetadata(key) {
    const codes = {
      ArrowUp: [38, "ArrowUp"], ArrowDown: [40, "ArrowDown"],
      ArrowLeft: [37, "ArrowLeft"], ArrowRight: [39, "ArrowRight"],
      Enter: [13, "Enter"], Backspace: [8, "Backspace"],
      SoftLeft: [0, "SoftLeft"], SoftRight: [0, "SoftRight"], EndCall: [0, "EndCall"],
      "*": [106, "NumpadMultiply"], "#": [0, "NumpadDivide"]
    };
    if (/^[0-9]$/.test(key)) return [48 + Number(key), `Digit${key}`];
    return codes[key] || [0, String(key || "")];
  }

  function dispatchKeyboard(message) {
    const phase = message.phase === "up" ? "keyup" : "keydown";
    const target = document.activeElement || document.body || document.documentElement;
    if (!target) return;
    const [keyCode, code] = keyMetadata(message.key);
    const event = new KeyboardEvent(phase, {
      key: message.key,
      code,
      bubbles: true,
      cancelable: true,
      repeat: Boolean(message.repeat),
      composed: true
    });
    // Firefox keeps these legacy fields read-only. KaiOS applications and older games
    // frequently inspect them, so expose compatible getters on our synthetic event.
    for (const [name, value] of Object.entries({ keyCode, which: keyCode, charCode: 0 })) {
      try { Object.defineProperty(event, name, { configurable: true, get: () => value }); } catch (_) {}
    }
    target.dispatchEvent(event);
    if (phase === "keydown" && !message.repeat && /^[0-9*#]$/.test(message.key)) {
      const press = new KeyboardEvent("keypress", {
        key: message.key, code, bubbles: true, cancelable: true, composed: true
      });
      for (const [name, value] of Object.entries({ keyCode, which: keyCode, charCode: message.key.charCodeAt(0) })) {
        try { Object.defineProperty(press, name, { configurable: true, get: () => value }); } catch (_) {}
      }
      target.dispatchEvent(press);
    }
    dispatchToPage("kai-key", { ...message, dispatchedByContent: true });
  }

  port.onDisconnect.addListener(() => {
    connected = false;
    trustKnown = true;
    trustedAppOrigin = false;
    queuedNetworkRequests.splice(0).forEach(forwardNetworkRequest);
    dispatchToPage("kai-bridge-disconnected", {});
  });

  port.onMessage.addListener(message => {
    if (message.type === "bridge-status") {
      trustKnown = true;
      trustedAppOrigin = Boolean(message.trusted);
      queuedNetworkRequests.splice(0).forEach(forwardNetworkRequest);
    } else if (message.type === "key") {
      dispatchKeyboard(message);
    } else if (message.type === "response") {
      dispatchToPage("kai-api-response", message);
    } else if (message.type === "system-message") {
      dispatchToPage("kai-system-message", message);
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

  async function forwardNetworkRequest(detail) {
    if (!trustedAppOrigin) {
      dispatchToPage("kai-network-response", {
        id: detail.id,
        success: false,
        error: "Privileged network access is restricted to the installed app origin"
      });
      return;
    }
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
  }

  document.addEventListener("kai-network-request", event => {
    const detail = event.detail;
    if (!detail || typeof detail.id !== "string" || typeof detail.url !== "string") return;
    if (trustKnown) forwardNetworkRequest(detail);
    else queuedNetworkRequests.push(detail);
  });

  function injectRuntime() {
    if (!document.documentElement) {
      requestAnimationFrame(injectRuntime);
      return;
    }
    const script = document.createElement("script");
    script.src = browser.runtime.getURL("page-runtime.js");
    script.onload = () => script.remove();
    script.onerror = () => script.remove();
    document.documentElement.appendChild(script);
  }

  injectRuntime();
})();
