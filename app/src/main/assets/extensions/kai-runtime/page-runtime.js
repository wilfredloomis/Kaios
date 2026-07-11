(function () {
  "use strict";
  if (window.KaiRuntime) return;

  let sequence = 0;
  const pending = new Map();
  const networkPending = new Map();
  const tcpSockets = new Map();
  const nativeFetch = window.fetch.bind(window);
  let networkEnabled = true;
  let privilegedHint = false;

  // ---------------------------------------------------------------------------
  // Manifest inspection – systemXHR / privileged / tcp-socket unlock network
  // ---------------------------------------------------------------------------
  const manifestPromise = nativeFetch("/manifest.webapp", { cache: "no-store" })
    .then(response => (response.ok ? response.json() : {}))
    .catch(() => ({}));

  const systemXhrAllowed = manifestPromise.then(manifest => {
    const permissions = manifest.permissions || {};
    const keys = Object.keys(permissions).map(k => k.toLowerCase());
    const type = String(manifest.type || "").toLowerCase();
    privilegedHint = type === "privileged" || type === "certified";
    return (
      privilegedHint ||
      keys.includes("systemxhr") ||
      keys.includes("tcp-socket") ||
      keys.includes("network-tcp") ||
      keys.includes("mobileconnection") ||
      keys.includes("wifi-manage")
    );
  }).catch(() => false);

  function arrayBufferToBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
    }
    return btoa(binary);
  }

  function base64ToBytes(value) {
    const binary = atob(value || "");
    return Uint8Array.from(binary, character => character.charCodeAt(0));
  }

  // ---------------------------------------------------------------------------
  // Cross-origin network proxy (systemXHR)
  // ---------------------------------------------------------------------------
  document.addEventListener("kai-network-response", event => {
    const detail = event.detail || {};
    const callback = networkPending.get(detail.id);
    if (!callback) return;
    networkPending.delete(detail.id);
    if (detail.success) callback.resolve(detail.response);
    else callback.reject(new TypeError(detail.error || "Proxied network request failed"));
  });

  function proxyNetworkRequest(detail) {
    return new Promise((resolve, reject) => {
      if (!networkEnabled) {
        reject(new TypeError("Network is disabled (offline simulation)"));
        return;
      }
      networkPending.set(detail.id, { resolve, reject });
      document.dispatchEvent(new CustomEvent("kai-network-request", { detail }));
      setTimeout(() => {
        const callback = networkPending.get(detail.id);
        if (!callback) return;
        networkPending.delete(detail.id);
        callback.reject(new TypeError("Proxied network request timed out"));
      }, 45000);
    });
  }

  async function compatibleFetch(input, init) {
    const request = new Request(input, init);
    const target = new URL(request.url, location.href);

    // Same-origin, non-http(s), or offline simulation → native path.
    if (target.origin === location.origin || !/^https?:$/.test(target.protocol)) {
      return nativeFetch(input, init);
    }
    if (!networkEnabled) throw new TypeError("Failed to fetch (network disabled)");

    // Always try native first – Gecko may allow CORS if the remote sends headers.
    // Fall back to the privileged proxy for apps that declared systemXHR / privileged.
    try {
      const nativeResponse = await nativeFetch(input, init);
      // Opaque / opaque-redirect responses from no-cors mode are useless for XHR;
      // if the caller used cors mode and we got a real response, return it.
      if (nativeResponse.type !== "opaque" && nativeResponse.type !== "opaqueredirect") {
        return nativeResponse;
      }
    } catch (_) {
      // CORS or network failure – try proxy if allowed.
    }

    if (!(await systemXhrAllowed)) {
      return nativeFetch(input, init);
    }

    let bodyBase64 = null;
    if (request.method !== "GET" && request.method !== "HEAD") {
      const body = await request.clone().arrayBuffer();
      if (body.byteLength > 8 * 1024 * 1024) throw new TypeError("Network request exceeds 8 MB");
      bodyBase64 = arrayBufferToBase64(body);
    }
    const headers = {};
    request.headers.forEach((value, name) => { headers[name] = value; });
    // Ensure a KaiOS-like Accept default so CDNs don't 406.
    if (!headers.accept && !headers.Accept) headers["Accept"] = "*/*";

    const result = await proxyNetworkRequest({
      id: `network-${Date.now()}-${++sequence}`,
      url: target.href,
      method: request.method,
      headers,
      bodyBase64,
      credentials: request.credentials,
      cache: request.cache
    });
    const response = new Response(base64ToBytes(result.bodyBase64), {
      status: result.status,
      statusText: result.statusText,
      headers: result.headers
    });
    try { Object.defineProperty(response, "url", { value: result.url }); } catch (_) {}
    return response;
  }

  Object.defineProperty(window, "fetch", { configurable: true, writable: true, value: compatibleFetch });

  // ---------------------------------------------------------------------------
  // XMLHttpRequest shim (routes through compatibleFetch)
  // ---------------------------------------------------------------------------
  class CompatibleXMLHttpRequest extends EventTarget {
    constructor() {
      super();
      this.readyState = 0;
      this.status = 0;
      this.statusText = "";
      this.response = null;
      this.responseText = "";
      this.responseXML = null;
      this.responseType = "";
      this.responseURL = "";
      this.timeout = 0;
      this.withCredentials = false;
      this.upload = new EventTarget();
      this._headers = {};
      this._responseHeaders = new Headers();
      this._controller = null;
    }

    open(method, url, async = true) {
      if (async === false) throw new DOMException("Synchronous XHR is not supported", "NotSupportedError");
      this._method = String(method).toUpperCase();
      this._url = new URL(url, location.href).href;
      this.readyState = 1;
      this._emit("readystatechange");
    }

    setRequestHeader(name, value) {
      if (this.readyState !== 1) throw new DOMException("XHR is not open", "InvalidStateError");
      const key = String(name).toLowerCase();
      this._headers[key] = this._headers[key] ? `${this._headers[key]}, ${value}` : String(value);
    }

    async send(body = null) {
      if (this.readyState !== 1) throw new DOMException("XHR is not open", "InvalidStateError");
      this._controller = new AbortController();
      let timeoutId = null;
      if (this.timeout > 0) timeoutId = setTimeout(() => this._controller.abort("timeout"), this.timeout);
      this._emit("loadstart");
      try {
        const response = await compatibleFetch(this._url, {
          method: this._method,
          headers: this._headers,
          body: this._method === "GET" || this._method === "HEAD" ? undefined : body,
          credentials: this.withCredentials ? "include" : "same-origin",
          signal: this._controller.signal
        });
        this.status = response.status;
        this.statusText = response.statusText;
        this.responseURL = response.url;
        this._responseHeaders = response.headers;
        this.readyState = 2;
        this._emit("readystatechange");
        const buffer = await response.arrayBuffer();
        this.readyState = 3;
        this._emit("readystatechange");
        this._emit("progress");
        const contentType = response.headers.get("content-type") || "";
        const text = new TextDecoder().decode(buffer);
        if (this.responseType === "arraybuffer") this.response = buffer;
        else if (this.responseType === "blob") this.response = new Blob([buffer], { type: contentType });
        else if (this.responseType === "json") this.response = text ? JSON.parse(text) : null;
        else if (this.responseType === "document") {
          this.responseXML = new DOMParser().parseFromString(text, contentType.includes("html") ? "text/html" : "application/xml");
          this.response = this.responseXML;
        } else {
          this.responseText = text;
          this.response = text;
          if (/\b(xml|html)\b/i.test(contentType)) {
            this.responseXML = new DOMParser().parseFromString(text, contentType.includes("html") ? "text/html" : "application/xml");
          }
        }
        this.readyState = 4;
        this._emit("readystatechange");
        this._emit("load");
        this._emit("loadend");
      } catch (error) {
        this.readyState = 4;
        this.status = 0;
        this._emit("readystatechange");
        this._emit(error && error.name === "AbortError" && this.timeout > 0 ? "timeout" : "error");
        this._emit("loadend");
      } finally {
        if (timeoutId !== null) clearTimeout(timeoutId);
      }
    }

    abort() {
      if (this._controller) this._controller.abort();
      this.readyState = 0;
      this._emit("abort");
      this._emit("loadend");
    }

    getResponseHeader(name) { return this._responseHeaders.get(name); }
    getAllResponseHeaders() {
      let result = "";
      this._responseHeaders.forEach((value, name) => { result += `${name}: ${value}\r\n`; });
      return result;
    }
    overrideMimeType() {}
    _emit(type) {
      const event = new Event(type);
      this.dispatchEvent(event);
      const handler = this[`on${type}`];
      if (typeof handler === "function") handler.call(this, event);
    }
  }

  Object.assign(CompatibleXMLHttpRequest, { UNSENT: 0, OPENED: 1, HEADERS_RECEIVED: 2, LOADING: 3, DONE: 4 });
  Object.assign(CompatibleXMLHttpRequest.prototype, { UNSENT: 0, OPENED: 1, HEADERS_RECEIVED: 2, LOADING: 3, DONE: 4 });
  Object.defineProperty(window, "XMLHttpRequest", {
    configurable: true,
    writable: true,
    value: CompatibleXMLHttpRequest
  });

  // ---------------------------------------------------------------------------
  // mozTCPSocket – Firefox OS / KaiOS raw TCP (used by Telegram ports)
  // ---------------------------------------------------------------------------
  document.addEventListener("kai-tcp-event", event => {
    const detail = event.detail || {};
    const socket = tcpSockets.get(detail.socketId);
    if (!socket) return;
    const data = detail.data || {};
    switch (detail.event) {
      case "open":
      case "opened":
        socket.readyState = "open";
        socket._emit("open");
        break;
      case "data": {
        let payload;
        if (data.dataBase64) {
          const bytes = base64ToBytes(data.dataBase64);
          payload = socket.binaryType === "arraybuffer" ? bytes.buffer : new TextDecoder().decode(bytes);
        } else {
          payload = data.text || "";
        }
        socket._emit("data", { data: payload });
        break;
      }
      case "error":
        socket._emit("error", { data: new Error(data.message || "TCP error"), message: data.message });
        break;
      case "close":
        socket.readyState = "closed";
        socket._emit("close");
        tcpSockets.delete(detail.socketId);
        break;
      default:
        break;
    }
  });

  function createTCPSocket(host, port, options) {
    options = options || {};
    const binaryType = options.binaryType === "arraybuffer" ? "arraybuffer" : "string";
    const useSecureTransport = Boolean(options.useSecureTransport);
    const requestId = `tcp-${Date.now()}-${++sequence}`;
    const socket = {
      host: String(host),
      port: Number(port),
      ssl: useSecureTransport,
      binaryType,
      bufferedAmount: 0,
      readyState: "connecting",
      onopen: null,
      ondata: null,
      onerror: null,
      onclose: null,
      _listeners: { open: [], data: [], error: [], close: [] },
      _socketId: null,
      addEventListener(type, listener) {
        if (this._listeners[type]) this._listeners[type].push(listener);
      },
      removeEventListener(type, listener) {
        if (!this._listeners[type]) return;
        this._listeners[type] = this._listeners[type].filter(l => l !== listener);
      },
      _emit(type, extra) {
        const event = Object.assign({ type, target: this }, extra || {});
        const handler = this[`on${type}`];
        if (typeof handler === "function") {
          try { handler.call(this, event); } catch (e) { console.error(e); }
        }
        (this._listeners[type] || []).forEach(listener => {
          try { listener.call(this, event); } catch (e) { console.error(e); }
        });
      },
      send(data) {
        if (this.readyState !== "open" || this._socketId == null) {
          throw new DOMException("TCPSocket is not open", "InvalidStateError");
        }
        const message = {
          type: "tcp",
          action: "send",
          socketId: this._socketId
        };
        if (typeof data === "string") {
          message.text = data;
        } else if (data instanceof ArrayBuffer) {
          message.dataBase64 = arrayBufferToBase64(data);
        } else if (ArrayBuffer.isView(data)) {
          message.dataBase64 = arrayBufferToBase64(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength));
        } else {
          message.text = String(data);
        }
        document.dispatchEvent(new CustomEvent("kai-tcp-request", { detail: message }));
        return true;
      },
      suspend() {},
      resume() {},
      close() {
        if (this._socketId != null) {
          document.dispatchEvent(new CustomEvent("kai-tcp-request", {
            detail: { type: "tcp", action: "close", socketId: this._socketId }
          }));
        }
        this.readyState = "closing";
      }
    };

    // Temporary id until Android assigns a real one via "opened" event.
    // We track by requestId first, then re-key on socketId.
    const pendingKey = requestId;
    tcpSockets.set(pendingKey, socket);

    document.addEventListener("kai-tcp-event", function onOpened(event) {
      const detail = event.detail || {};
      if (detail.requestId !== requestId && detail.event !== "opened" && detail.event !== "open") return;
      if (detail.requestId && detail.requestId !== requestId) return;
      if (detail.event === "opened" || detail.event === "open") {
        if (detail.requestId && detail.requestId !== requestId) return;
        // Accept first opened after our request if requestId matches, or if we
        // haven't been assigned yet and this is the only connecting socket.
        if (socket._socketId != null) return;
        if (detail.requestId === requestId || (!detail.requestId && socket.readyState === "connecting")) {
          socket._socketId = detail.socketId;
          tcpSockets.delete(pendingKey);
          tcpSockets.set(detail.socketId, socket);
          document.removeEventListener("kai-tcp-event", onOpened);
        }
      }
      if (detail.event === "error" && detail.requestId === requestId) {
        socket.readyState = "closed";
        socket._emit("error", { data: new Error((detail.data && detail.data.message) || "TCP open failed") });
        socket._emit("close");
        tcpSockets.delete(pendingKey);
        document.removeEventListener("kai-tcp-event", onOpened);
      }
    });

    document.dispatchEvent(new CustomEvent("kai-tcp-request", {
      detail: {
        type: "tcp",
        action: "open",
        requestId,
        host: String(host),
        port: Number(port),
        useSecureTransport,
        binaryType
      }
    }));

    return socket;
  }

  // navigator.mozTCPSocket.open(host, port, options)
  const mozTCPSocket = {
    open: createTCPSocket,
    listen() {
      throw new DOMException("TCPSocket.listen is not supported in Kai Runtime", "NotSupportedError");
    }
  };
  Object.defineProperty(navigator, "mozTCPSocket", {
    configurable: true,
    enumerable: true,
    value: mozTCPSocket
  });
  // Some ports check window.TCPSocket or navigator.TCPSocket.
  if (!window.TCPSocket) {
    Object.defineProperty(window, "TCPSocket", { configurable: true, value: mozTCPSocket });
  }

  // ---------------------------------------------------------------------------
  // Audio / media compatibility
  // ---------------------------------------------------------------------------
  const NativeAudioContext = window.AudioContext || window.webkitAudioContext;
  if (NativeAudioContext) {
    function CompatibleAudioContext(options) {
      return options && typeof options === "object"
        ? new NativeAudioContext(options)
        : new NativeAudioContext();
    }
    CompatibleAudioContext.prototype = NativeAudioContext.prototype;
    Object.setPrototypeOf(CompatibleAudioContext, NativeAudioContext);
    Object.defineProperty(window, "AudioContext", {
      configurable: true,
      writable: true,
      value: CompatibleAudioContext
    });
    if (!window.webkitAudioContext) {
      Object.defineProperty(window, "webkitAudioContext", { configurable: true, value: CompatibleAudioContext });
    }
  }

  if (!("mozAudioChannelType" in HTMLMediaElement.prototype)) {
    Object.defineProperty(HTMLMediaElement.prototype, "mozAudioChannelType", {
      configurable: true,
      get() { return this.__kaiAudioChannel || "normal"; },
      set(value) { this.__kaiAudioChannel = String(value); }
    });
  }

  const audioChannelManager = new EventTarget();
  let volumeControlChannel = "normal";
  Object.defineProperties(audioChannelManager, {
    volumeControlChannel: {
      enumerable: true,
      get: () => volumeControlChannel,
      set: value => { volumeControlChannel = String(value || "normal"); }
    },
    headphones: { enumerable: true, get: () => false },
    telephonyChannelActive: { enumerable: true, get: () => false },
    onheadphoneschange: { configurable: true, writable: true, value: null }
  });
  Object.defineProperty(navigator, "mozAudioChannelManager", {
    configurable: true,
    value: audioChannelManager
  });

  // ---------------------------------------------------------------------------
  // System messages + wake locks
  // ---------------------------------------------------------------------------
  const systemMessageHandlers = new Map();
  Object.defineProperty(navigator, "mozSetMessageHandler", {
    configurable: true,
    value(type, handler) {
      const messageType = String(type);
      if (handler === null || handler === undefined) systemMessageHandlers.delete(messageType);
      else if (typeof handler === "function") systemMessageHandlers.set(messageType, handler);
      else throw new TypeError("System message handler must be a function or null");
    }
  });
  Object.defineProperty(navigator, "mozHasPendingMessage", {
    configurable: true,
    value() { return false; }
  });
  window.addEventListener("kai-system-message", event => {
    const detail = event.detail || {};
    const handler = systemMessageHandlers.get(String(detail.type));
    if (handler) queueMicrotask(() => handler(detail.data));
  });

  function requestWakeLock(topic) {
    let released = false;
    return {
      topic: String(topic),
      get released() { return released; },
      unlock() { released = true; }
    };
  }
  if (!navigator.requestWakeLock) {
    Object.defineProperty(navigator, "requestWakeLock", { configurable: true, value: requestWakeLock });
  }
  if (!navigator.mozRequestWakeLock) {
    Object.defineProperty(navigator, "mozRequestWakeLock", { configurable: true, value: requestWakeLock });
  }

  // ---------------------------------------------------------------------------
  // Native bridge (API calls)
  // ---------------------------------------------------------------------------
  function request(api, args) {
    const callbackId = `kai-${Date.now()}-${++sequence}`;
    return new Promise((resolve, reject) => {
      pending.set(callbackId, { resolve, reject });
      document.dispatchEvent(new CustomEvent("kai-api-request", {
        detail: { callbackId, api, args: args || {} }
      }));
      setTimeout(() => {
        const callback = pending.get(callbackId);
        if (!callback) return;
        pending.delete(callbackId);
        callback.reject(new Error("Kai Runtime bridge timed out"));
      }, 15000);
    });
  }

  document.addEventListener("kai-bridge-disconnected", () => {
    for (const callback of pending.values()) callback.reject(new Error("Kai Runtime bridge disconnected"));
    pending.clear();
  });

  document.addEventListener("kai-api-response", event => {
    const response = event.detail || {};
    const callback = pending.get(response.callbackId);
    if (!callback) return;
    pending.delete(response.callbackId);
    window.dispatchEvent(new CustomEvent("kai-api-response", { detail: response }));
    if (response.success) callback.resolve(response.data);
    else callback.reject(new Error(response.error || "Kai Runtime API request failed"));
  });

  // Runtime info / network control from Android
  document.addEventListener("kai-runtime-info", event => {
    const detail = event.detail || {};
    if (typeof detail.networkEnabled === "boolean") networkEnabled = detail.networkEnabled;
    if (detail.privileged) privilegedHint = true;
  });
  document.addEventListener("kai-network-control", event => {
    const detail = event.detail || {};
    if (typeof detail.enabled === "boolean") {
      networkEnabled = detail.enabled;
      window.dispatchEvent(new Event(networkEnabled ? "online" : "offline"));
    }
  });

  // ---------------------------------------------------------------------------
  // Key events with KaiOS keyCode values (softkeys, call, etc.)
  // ---------------------------------------------------------------------------
  // KaiOS softkey codes: primary SoftLeft/SoftRight are 37/39 (same as arrows);
  // apps distinguish via event.key. Some devices also emit 403/404.
  const KEY_CODES = {
    SoftLeft: 37,
    SoftRight: 39,
    Enter: 13,
    ArrowUp: 38,
    ArrowDown: 40,
    ArrowLeft: 37,
    ArrowRight: 39,
    Backspace: 8,
    Call: 419,
    EndCall: 420,
    "*": 170,
    "#": 163
  };

  document.addEventListener("kai-key", event => {
    const detail = event.detail || {};
    const key = detail.key || "";
    const keyCode = typeof detail.keyCode === "number" ? detail.keyCode : (KEY_CODES[key] || (key.length === 1 ? key.charCodeAt(0) : 0));
    const target = document.activeElement || document.body || document.documentElement;
    const type = detail.phase === "up" ? "keyup" : "keydown";
    const init = {
      key,
      code: key.length === 1 ? `Key${key.toUpperCase()}` : key,
      keyCode,
      which: keyCode,
      bubbles: true,
      cancelable: true,
      repeat: Boolean(detail.repeat),
      composed: true
    };
    // KeyboardEvent constructor ignores keyCode in modern Gecko; define after.
    const keyboardEvent = new KeyboardEvent(type, init);
    try {
      Object.defineProperty(keyboardEvent, "keyCode", { get: () => keyCode });
      Object.defineProperty(keyboardEvent, "which", { get: () => keyCode });
      Object.defineProperty(keyboardEvent, "charCode", { get: () => 0 });
    } catch (_) { /* read-only on some builds */ }

    const cancelled = !target.dispatchEvent(keyboardEvent);
    // Also fire on window/document for apps that listen there.
    if (!cancelled) {
      window.dispatchEvent(keyboardEvent);
    }

    // SoftLeft / SoftRight often double as lsk/rsk custom events in KaiUI apps.
    if (type === "keydown" && (key === "SoftLeft" || key === "SoftRight")) {
      window.dispatchEvent(new CustomEvent(key === "SoftLeft" ? "lsk" : "rsk", { bubbles: true }));
    }
  });

  // ---------------------------------------------------------------------------
  // Console / error forwarding
  // ---------------------------------------------------------------------------
  function logToAndroid(payload) {
    document.dispatchEvent(new CustomEvent("kai-runtime-log", { detail: payload }));
  }

  let lastConsoleMessage = null;
  let lastConsoleLevel = null;
  let lastConsoleTime = 0;
  let suppressedConsoleMessages = 0;

  function forwardConsole(level, message) {
    const now = Date.now();
    if (level === lastConsoleLevel && message === lastConsoleMessage) {
      suppressedConsoleMessages++;
      if (now - lastConsoleTime < 1000) return;
      logToAndroid({
        type: "console",
        level,
        message: `${message} (repeated ${suppressedConsoleMessages} times)`
      });
      suppressedConsoleMessages = 0;
      lastConsoleTime = now;
      return;
    }
    if (suppressedConsoleMessages > 0) {
      logToAndroid({
        type: "console",
        level: lastConsoleLevel,
        message: `${lastConsoleMessage} (repeated ${suppressedConsoleMessages} times)`
      });
    }
    lastConsoleLevel = level;
    lastConsoleMessage = message;
    lastConsoleTime = now;
    suppressedConsoleMessages = 0;
    logToAndroid({ type: "console", level, message });
  }

  ["log", "info", "warn", "error"].forEach(level => {
    const original = console[level].bind(console);
    console[level] = (...values) => {
      original(...values);
      const message = values.map(value => {
        if (typeof value === "string") return value;
        try { return JSON.stringify(value); } catch (_) { return String(value); }
      }).join(" ");
      forwardConsole(level, message);
    };
  });

  window.addEventListener("error", event => {
    logToAndroid({ type: "error", message: `${event.message} (${event.filename}:${event.lineno})` });
  });
  window.addEventListener("unhandledrejection", event => {
    logToAndroid({ type: "error", message: `Unhandled promise rejection: ${String(event.reason)}` });
  });
  document.addEventListener("focusin", event => {
    const target = event.target;
    const element = target && target.tagName
      ? `${target.tagName.toLowerCase()}${target.id ? `#${target.id}` : ""}`
      : "unknown";
    logToAndroid({ type: "focus", element });
  });

  // ---------------------------------------------------------------------------
  // KaiRuntime surface
  // ---------------------------------------------------------------------------
  const runtime = {
    version: "0.3.0",
    profile: "kaios-2.5",
    platform: "android",
    capabilities: Object.freeze({
      battery: true,
      network: true,
      vibration: true,
      geolocation: true,
      notifications: true,
      deviceStorage: true,
      systemXHR: true,
      tcpSocket: true,
      audioChannel: true,
      systemMessages: true,
      contacts: false,
      telephony: false,
      sms: false
    }),
    getBattery: () => request("battery"),
    getNetwork: () => request("network"),
    vibrate: duration => request("vibrate", { duration }),
    getLocation: () => request("location"),
    showNotification: (title, body) => request("notification", { title, body }),
    getCapabilities: () => request("capabilities")
  };

  Object.freeze(runtime);
  Object.defineProperty(window, "KaiRuntime", { value: runtime, enumerable: true });
  Object.defineProperty(navigator, "kaiRuntime", { value: runtime, enumerable: true });

  // ---------------------------------------------------------------------------
  // Battery Status API
  // ---------------------------------------------------------------------------
  if (typeof navigator.getBattery !== "function") {
    const batteryManager = new EventTarget();
    let batteryLevel = 1;
    let batteryCharging = true;
    Object.defineProperties(batteryManager, {
      charging: { enumerable: true, get: () => batteryCharging },
      chargingTime: { enumerable: true, get: () => (batteryCharging ? 0 : Infinity) },
      dischargingTime: { enumerable: true, get: () => (batteryCharging ? Infinity : 7200) },
      level: { enumerable: true, get: () => batteryLevel },
      onchargingchange: { configurable: true, writable: true, value: null },
      onlevelchange: { configurable: true, writable: true, value: null }
    });
    Object.defineProperty(navigator, "getBattery", {
      configurable: true,
      value() {
        return request("battery").then(data => {
          if (data && typeof data.level === "number") {
            batteryLevel = Math.max(0, Math.min(1, data.level));
          }
          if (data && typeof data.charging === "boolean") batteryCharging = data.charging;
          return batteryManager;
        }).catch(() => batteryManager);
      }
    });
    if (!("battery" in navigator)) {
      Object.defineProperty(navigator, "battery", { configurable: true, get: () => batteryManager });
    }
    if (!("mozBattery" in navigator)) {
      Object.defineProperty(navigator, "mozBattery", { configurable: true, get: () => batteryManager });
    }
  }

  if (typeof navigator.mozVibrate !== "function" && typeof navigator.vibrate === "function") {
    Object.defineProperty(navigator, "mozVibrate", {
      configurable: true,
      value: (...args) => navigator.vibrate(...args)
    });
  }

  // ---------------------------------------------------------------------------
  // Device Storage (IndexedDB-backed)
  // ---------------------------------------------------------------------------
  const storageDb = new Promise((resolve, reject) => {
    const open = indexedDB.open("kai-device-storage", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("files", { keyPath: "name" });
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error);
  });

  function domRequest(operation) {
    const listeners = { success: [], error: [] };
    const result = {
      result: null,
      error: null,
      readyState: "pending",
      onsuccess: null,
      onerror: null,
      addEventListener(type, listener) {
        if (listeners[type]) listeners[type].push(listener);
      }
    };
    Promise.resolve().then(operation).then(value => {
      result.result = value;
      result.readyState = "done";
      const event = { target: result };
      if (typeof result.onsuccess === "function") result.onsuccess(event);
      listeners.success.forEach(listener => listener(event));
    }, error => {
      result.error = error instanceof Error ? error : new Error(String(error));
      result.readyState = "done";
      const event = { target: result };
      if (typeof result.onerror === "function") result.onerror(event);
      listeners.error.forEach(listener => listener(event));
    });
    return result;
  }

  async function withStore(mode, operation) {
    const db = await storageDb;
    return new Promise((resolve, reject) => {
      const transaction = db.transaction("files", mode);
      const request = operation(transaction.objectStore("files"));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  function asFile(record) {
    return new File([record.data], record.name, {
      type: record.type || "application/octet-stream",
      lastModified: record.lastModified || Date.now()
    });
  }

  function enumerate(prefix) {
    const listeners = { success: [], error: [] };
    let records = [];
    let index = 0;
    const cursor = {
      result: null,
      error: null,
      readyState: "pending",
      onsuccess: null,
      onerror: null,
      addEventListener(type, listener) {
        if (listeners[type]) listeners[type].push(listener);
      },
      continue() {
        emit();
      }
    };
    function notify(type) {
      const event = { target: cursor };
      const handler = cursor[`on${type}`];
      if (typeof handler === "function") handler(event);
      listeners[type].forEach(listener => listener(event));
    }
    function emit() {
      queueMicrotask(() => {
        cursor.result = index < records.length ? asFile(records[index++]) : null;
        cursor.readyState = "done";
        notify("success");
      });
    }
    withStore("readonly", store => store.getAll()).then(all => {
      records = all.filter(record => !prefix || record.name.startsWith(prefix));
      emit();
    }, error => {
      cursor.error = error;
      cursor.readyState = "done";
      notify("error");
    });
    return cursor;
  }

  function createDeviceStorage(storageName) {
    return {
      storageName,
      default: true,
      available: () => domRequest(() => "available"),
      freeSpace: () => domRequest(async () => {
        const records = await withStore("readonly", store => store.getAll());
        const used = records.reduce((total, record) => total + (record.data.size || 0), 0);
        return Math.max(0, 50 * 1024 * 1024 - used);
      }),
      usedSpace: () => domRequest(async () => {
        const records = await withStore("readonly", store => store.getAll());
        return records.reduce((total, record) => total + (record.data.size || 0), 0);
      }),
      get: name => domRequest(async () => {
        const record = await withStore("readonly", store => store.get(String(name)));
        if (!record) throw new Error(`File not found: ${name}`);
        return asFile(record);
      }),
      add: blob => {
        const name = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        return domRequest(async () => {
          await withStore("readwrite", store => store.put({
            name, data: blob, type: blob.type, lastModified: Date.now()
          }));
          return name;
        });
      },
      addNamed: (blob, name) => domRequest(async () => {
        const safeName = String(name).replace(/^\/+/, "");
        if (!safeName || safeName.split("/").includes("..")) throw new Error("Invalid storage path");
        await withStore("readwrite", store => store.put({
          name: safeName, data: blob, type: blob.type, lastModified: Date.now()
        }));
        return safeName;
      }),
      delete: name => domRequest(async () => {
        await withStore("readwrite", store => store.delete(String(name).replace(/^\/+/, "")));
        return String(name);
      }),
      enumerate: prefix => enumerate(prefix ? String(prefix).replace(/^\/+/, "") : "")
    };
  }

  const deviceStores = new Map();
  Object.defineProperty(navigator, "getDeviceStorage", {
    configurable: true,
    value(type) {
      const name = String(type || "sdcard");
      if (!deviceStores.has(name)) deviceStores.set(name, createDeviceStorage(name));
      return deviceStores.get(name);
    }
  });
  Object.defineProperty(navigator, "getDeviceStorages", {
    configurable: true,
    value(type) { return [navigator.getDeviceStorage(type)]; }
  });

  // ---------------------------------------------------------------------------
  // Connection / mobile connection
  // ---------------------------------------------------------------------------
  const connectionInfo = new EventTarget();
  Object.defineProperties(connectionInfo, {
    type: { enumerable: true, get: () => (networkEnabled && navigator.onLine ? "wifi" : "none") },
    effectiveType: { enumerable: true, get: () => (networkEnabled && navigator.onLine ? "4g" : "slow-2g") },
    downlink: { enumerable: true, get: () => (networkEnabled && navigator.onLine ? 10 : 0) },
    rtt: { enumerable: true, get: () => (networkEnabled && navigator.onLine ? 50 : 0) },
    saveData: { enumerable: true, value: false },
    metered: { enumerable: true, value: false },
    bandwidth: { enumerable: true, get: () => (networkEnabled && navigator.onLine ? 10 : 0) }
  });
  if (!navigator.connection) {
    Object.defineProperty(navigator, "connection", { configurable: true, value: connectionInfo });
  }
  if (!navigator.mozConnection) {
    Object.defineProperty(navigator, "mozConnection", { configurable: true, value: navigator.connection });
  }

  const mobileConnection = new EventTarget();
  Object.defineProperties(mobileConnection, {
    radioState: { enumerable: true, value: "enabled" },
    iccId: { enumerable: true, value: null },
    voice: {
      enumerable: true,
      get: () => ({ connected: false, emergencyCallsOnly: false, roaming: false, network: null, type: null })
    },
    data: {
      enumerable: true,
      get: () => ({
        connected: networkEnabled && navigator.onLine,
        roaming: false,
        network: null,
        type: networkEnabled && navigator.onLine ? "wifi" : null
      })
    }
  });
  mobileConnection.getNetworks = () => domRequest(() => []);
  mobileConnection.selectNetworkAutomatically = () => domRequest(() => null);
  if (!navigator.mozMobileConnections) {
    Object.defineProperty(navigator, "mozMobileConnections", { configurable: true, value: [mobileConnection] });
  }
  if (!navigator.mozMobileConnection) {
    Object.defineProperty(navigator, "mozMobileConnection", { configurable: true, value: mobileConnection });
  }

  // ---------------------------------------------------------------------------
  // mozApps
  // ---------------------------------------------------------------------------
  async function currentApplication() {
    let manifest = { name: document.title || "KaiOS App", type: "web", permissions: {} };
    try {
      const response = await nativeFetch("/manifest.webapp", { cache: "no-store" });
      if (response.ok) manifest = await response.json();
    } catch (_) {
      // The generic manifest keeps legacy app-detection code operational.
    }
    return {
      manifest,
      manifestURL: `${location.origin}/manifest.webapp`,
      origin: location.origin,
      installOrigin: location.origin,
      receipts: []
    };
  }

  const mozApps = {
    getSelf: () => domRequest(currentApplication),
    getInstalled: () => domRequest(async () => [await currentApplication()]),
    checkInstalled: () => domRequest(async () => (await currentApplication()).manifestURL),
    mgmt: {
      getAll: () => domRequest(async () => [await currentApplication()])
    }
  };
  Object.defineProperty(navigator, "mozApps", {
    configurable: true,
    value: mozApps
  });

  // ---------------------------------------------------------------------------
  // navigator.b2g (KaiOS 3.x)
  // ---------------------------------------------------------------------------
  if (!navigator.b2g) {
    const b2g = {};
    const mirror = (target, key, value) => {
      if (value === undefined) return;
      try {
        Object.defineProperty(target, key, { configurable: true, enumerable: true, value });
      } catch (_) { /* read-only host property */ }
    };
    mirror(b2g, "getDeviceStorage", navigator.getDeviceStorage && navigator.getDeviceStorage.bind(navigator));
    mirror(b2g, "getDeviceStorages", navigator.getDeviceStorages && navigator.getDeviceStorages.bind(navigator));
    mirror(b2g, "mozApps", navigator.mozApps);
    mirror(b2g, "b2gApps", navigator.mozApps);
    mirror(b2g, "mozMobileConnections", navigator.mozMobileConnections);
    mirror(b2g, "connection", navigator.connection);
    mirror(b2g, "getBattery", navigator.getBattery && navigator.getBattery.bind(navigator));
    mirror(b2g, "vibrate", navigator.vibrate && navigator.vibrate.bind(navigator));
    mirror(b2g, "setMessageHandler", navigator.mozSetMessageHandler && navigator.mozSetMessageHandler.bind(navigator));
    mirror(b2g, "requestWakeLock", navigator.requestWakeLock && navigator.requestWakeLock.bind(navigator));
    mirror(b2g, "TCPSocket", mozTCPSocket);
    Object.defineProperty(navigator, "b2g", { configurable: true, value: b2g });
  }

  // Advertise online status for apps that check navigator.onLine at boot.
  try {
    Object.defineProperty(navigator, "onLine", {
      configurable: true,
      get: () => networkEnabled && true
    });
  } catch (_) { /* some engines lock onLine */ }
})();
