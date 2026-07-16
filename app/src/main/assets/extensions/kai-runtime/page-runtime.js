(function () {
  "use strict";
  if (window.KaiRuntime) return;

  let sequence = 0;
  const pending = new Map();
  const networkPending = new Map();
  const nativeFetch = window.fetch.bind(window);
  const NativeXMLHttpRequest = window.XMLHttpRequest;

  function defineEventHandlerProperties(prototype, types) {
    types.forEach(type => {
      const callbackKey = Symbol(`on${type}`);
      const listenerKey = Symbol(`on${type}Listener`);
      Object.defineProperty(prototype, `on${type}`, {
        configurable: true,
        enumerable: true,
        get() { return this[callbackKey] || null; },
        set(callback) {
          const previous = this[listenerKey];
          if (previous) this.removeEventListener(type, previous);
          this[callbackKey] = typeof callback === "function" ? callback : null;
          this[listenerKey] = null;
          if (this[callbackKey]) {
            const listener = event => this[callbackKey] && this[callbackKey].call(this, event);
            this[listenerKey] = listener;
            this.addEventListener(type, listener);
          }
        }
      });
    });
  }

  const XHR_EVENT_TYPES = ["readystatechange", "loadstart", "progress", "abort", "error", "load", "timeout", "loadend"];
  const PROGRESS_EVENT_TYPES = ["loadstart", "progress", "abort", "error", "load", "timeout", "loadend"];

  class CompatibleXMLHttpRequestUpload extends EventTarget {}
  defineEventHandlerProperties(CompatibleXMLHttpRequestUpload.prototype, PROGRESS_EVENT_TYPES);

  const manifestPromise = nativeFetch("/manifest.webapp", { cache: "no-store" })
    .then(response => response.ok ? response.json() : {})
    .catch(() => ({}));
  const systemXhrAllowed = manifestPromise
    .then(manifest => Object.keys(manifest.permissions || {}).some(name => name.toLowerCase() === "systemxhr"));

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
      networkPending.set(detail.id, { resolve, reject });
      document.dispatchEvent(new CustomEvent("kai-network-request", { detail }));
      setTimeout(() => {
        const callback = networkPending.get(detail.id);
        if (!callback) return;
        networkPending.delete(detail.id);
        callback.reject(new TypeError("Proxied network request timed out"));
      }, 60000);
    });
  }

  async function compatibleFetch(input, init) {
    const request = new Request(input, init);
    const target = new URL(request.url, location.href);
    if (target.origin === location.origin || !/^https?:$/.test(target.protocol) || !(await systemXhrAllowed)) {
      return nativeFetch(input, init);
    }

    let bodyBase64 = null;
    if (request.method !== "GET" && request.method !== "HEAD") {
      const body = await request.clone().arrayBuffer();
      if (body.byteLength > 16 * 1024 * 1024) throw new TypeError("Network request exceeds 16 MB");
      bodyBase64 = arrayBufferToBase64(body);
    }
    const headers = {};
    request.headers.forEach((value, name) => { headers[name] = value; });
    const result = await proxyNetworkRequest({
      id: `network-${Date.now()}-${++sequence}`,
      url: target.href,
      method: request.method,
      headers,
      bodyBase64,
      credentials: request.credentials,
      cache: request.cache,
      redirect: request.redirect
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
      this.upload = new CompatibleXMLHttpRequestUpload();
      this._headers = {};
      this._responseHeaders = new Headers();
      this._controller = null;
      this._native = null;
      this._mimeType = null;
      this._sent = false;
      this._async = true;
    }

    open(method, url, async = true, user, password) {
      this._method = String(method).toUpperCase();
      this._url = new URL(url, location.href).href;
      this._async = async !== false;
      this._user = user;
      this._password = password;
      this.readyState = 1;
      this._emit("readystatechange");
      if (!this._async) {
        this._createNative();
        this._native.open(this._method, this._url, false, user, password);
      }
    }

    setRequestHeader(name, value) {
      if (this.readyState !== 1 || this._sent) throw new DOMException("XHR is not open", "InvalidStateError");
      const key = String(name).toLowerCase();
      this._headers[key] = this._headers[key] ? `${this._headers[key]}, ${value}` : String(value);
      if (this._native) this._native.setRequestHeader(name, value);
    }

    async send(body = null) {
      if (this.readyState !== 1 || this._sent) throw new DOMException("XHR is not open", "InvalidStateError");
      this._sent = true;
      if (!this._async) {
        this._prepareNative();
        this._native.send(body);
        this._syncNative();
        return;
      }
      const target = new URL(this._url, location.href);
      const useProxy = target.origin !== location.origin && /^https?:$/.test(target.protocol) && await systemXhrAllowed;
      if (!useProxy) {
        this._sendNative(body);
        return;
      }
      this._sendProxy(body);
    }

    _createNative() {
      if (this._native) return;
      const native = new NativeXMLHttpRequest();
      this._native = native;
      XHR_EVENT_TYPES.forEach(type => native.addEventListener(type, event => {
        this._syncNative();
        this._emit(type, event);
      }));
      PROGRESS_EVENT_TYPES.forEach(type => {
        native.upload.addEventListener(type, event => this.upload.dispatchEvent(new ProgressEvent(type, {
          lengthComputable: event.lengthComputable,
          loaded: event.loaded,
          total: event.total
        })));
      });
    }

    _prepareNative() {
      this._native.responseType = this.responseType;
      this._native.timeout = this.timeout;
      this._native.withCredentials = this.withCredentials;
      if (this._mimeType) this._native.overrideMimeType(this._mimeType);
    }

    _sendNative(body) {
      this._createNative();
      this._native.open(this._method, this._url, true, this._user, this._password);
      Object.entries(this._headers).forEach(([name, value]) => this._native.setRequestHeader(name, value));
      this._prepareNative();
      this._native.send(body);
    }

    async _sendProxy(body) {
      this._controller = new AbortController();
      let timeoutId = null;
      let timedOut = false;
      if (this.timeout > 0) timeoutId = setTimeout(() => {
        timedOut = true;
        this._controller.abort();
      }, this.timeout);
      this._emit("loadstart");
      try {
        const response = await compatibleFetch(this._url, {
          method: this._method,
          headers: this._headers,
          body: this._method === "GET" || this._method === "HEAD" ? undefined : body,
          credentials: this.withCredentials ? "include" : "omit",
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
        this._emit("progress", new ProgressEvent("progress", { lengthComputable: true, loaded: buffer.byteLength, total: buffer.byteLength }));
        const contentType = this._mimeType || response.headers.get("content-type") || "";
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
        this._emit(timedOut ? "timeout" : (error && error.name === "AbortError" ? "abort" : "error"));
        this._emit("loadend");
      } finally {
        if (timeoutId !== null) clearTimeout(timeoutId);
      }
    }

    _syncNative() {
      if (!this._native) return;
      this.readyState = this._native.readyState;
      this.status = this._native.status;
      this.statusText = this._native.statusText;
      this.responseURL = this._native.responseURL;
      this.response = this._native.response;
      this.responseXML = this._native.responseXML;
      try { this.responseText = this._native.responseText; } catch (_) { this.responseText = ""; }
    }

    abort() {
      if (this._native) this._native.abort();
      if (this._controller) this._controller.abort();
      if (!this._native) {
        this.readyState = 0;
        this._emit("abort");
        this._emit("loadend");
      }
    }

    getResponseHeader(name) {
      if (this._native) return this._native.getResponseHeader(name);
      return this._responseHeaders.get(name);
    }

    getAllResponseHeaders() {
      if (this._native) return this._native.getAllResponseHeaders();
      let result = "";
      this._responseHeaders.forEach((value, name) => { result += `${name}: ${value}\r\n`; });
      return result;
    }

    overrideMimeType(value) {
      this._mimeType = String(value);
      if (this._native) this._native.overrideMimeType(this._mimeType);
    }

    _emit(type, source) {
      const event = source && typeof source.loaded === "number"
        ? new ProgressEvent(type, {
            lengthComputable: Boolean(source.lengthComputable),
            loaded: Number(source.loaded) || 0,
            total: Number(source.total) || 0
          })
        : new Event(type);
      this.dispatchEvent(event);
    }
  }

  defineEventHandlerProperties(CompatibleXMLHttpRequest.prototype, XHR_EVENT_TYPES);
  Object.assign(CompatibleXMLHttpRequest, { UNSENT: 0, OPENED: 1, HEADERS_RECEIVED: 2, LOADING: 3, DONE: 4 });
  Object.assign(CompatibleXMLHttpRequest.prototype, { UNSENT: 0, OPENED: 1, HEADERS_RECEIVED: 2, LOADING: 3, DONE: 4 });
  Object.defineProperty(window, "XMLHttpRequest", {
    configurable: true,
    writable: true,
    value: CompatibleXMLHttpRequest
  });

  const trackedAudioContexts = new Set();
  const NativeAudioContext = window.AudioContext || window.webkitAudioContext;
  if (NativeAudioContext) {
    function CompatibleAudioContext(options) {
      const context = options && typeof options === "object"
        ? new NativeAudioContext(options)
        : new NativeAudioContext();
      trackedAudioContexts.add(context);
      return context;
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

  // Media compatibility -----------------------------------------------------
  // A number of KaiOS applications rely on old hls.js behavior and muted
  // autoplay. Android Gecko correctly blocks audible autoplay until a trusted
  // page gesture, so provide a real in-page sound button rather than trying to
  // unlock audio from a synthetic native-message event.
  const hlsConstructorCache = new WeakMap();
  const hlsInstanceCache = new WeakMap();
  let soundRecoveryUntil = 0;
  let soundButton = null;

  function wrapHlsInstance(instance) {
    if (!instance || (typeof instance !== "object" && typeof instance !== "function")) return instance;
    try { if (instance.__kaiHlsCompatProxy) return instance; } catch (_) {}
    if (hlsInstanceCache.has(instance)) return hlsInstanceCache.get(instance);
    let proxy;
    const callableLoadLevel = function (levelOrSource) {
      if (arguments.length === 0) return Reflect.get(instance, "loadLevel", instance);
      if (typeof levelOrSource === "string" && typeof instance.loadSource === "function") {
        instance.loadSource(levelOrSource);
      } else {
        const level = Number(levelOrSource);
        if (Number.isFinite(level)) Reflect.set(instance, "loadLevel", level, instance);
      }
      if (typeof instance.startLoad === "function") instance.startLoad();
      return proxy;
    };
    proxy = new Proxy(instance, {
      get(target, property) {
        if (property === "__kaiHlsCompatProxy") return true;
        const value = Reflect.get(target, property, target);
        if (property === "loadLevel" && typeof value !== "function") return callableLoadLevel;
        return typeof value === "function" ? value.bind(target) : value;
      },
      set(target, property, value) {
        return Reflect.set(target, property, value, target);
      }
    });
    hlsInstanceCache.set(instance, proxy);
    return proxy;
  }

  function wrapHlsConstructor(Constructor) {
    if (typeof Constructor !== "function") return Constructor;
    if (hlsConstructorCache.has(Constructor)) return hlsConstructorCache.get(Constructor);
    const wrapped = new Proxy(Constructor, {
      construct(target, argumentsList) {
        return wrapHlsInstance(Reflect.construct(target, argumentsList, target));
      },
      apply(target, thisArgument, argumentsList) {
        return wrapHlsInstance(Reflect.apply(target, thisArgument, argumentsList));
      }
    });
    hlsConstructorCache.set(Constructor, wrapped);
    return wrapped;
  }

  function installHlsGlobalCompatibility(name, constructorMode) {
    const descriptor = Object.getOwnPropertyDescriptor(window, name);
    if (descriptor && !descriptor.configurable) return;
    let currentValue = descriptor && "value" in descriptor ? descriptor.value : window[name];
    Object.defineProperty(window, name, {
      configurable: true,
      enumerable: descriptor ? descriptor.enumerable : true,
      get() {
        return constructorMode ? wrapHlsConstructor(currentValue) : wrapHlsInstance(currentValue);
      },
      set(value) { currentValue = value; }
    });
  }

  try {
    installHlsGlobalCompatibility("Hls", true);
    installHlsGlobalCompatibility("hls", false);
  } catch (_) {
    // Some sites define non-configurable globals. Their native behavior remains.
  }

  function allMediaElements(root = document) {
    const found = [];
    const visit = node => {
      if (!node || typeof node.querySelectorAll !== "function") return;
      node.querySelectorAll("audio,video").forEach(media => found.push(media));
      node.querySelectorAll("*").forEach(element => {
        if (element.shadowRoot) visit(element.shadowRoot);
      });
    };
    visit(root);
    return found;
  }

  function mediaNeedsSound(media) {
    return Boolean(media && !media.ended && (media.muted || media.defaultMuted || Number(media.volume) <= 0.001));
  }

  function resumeTrackedAudioContexts() {
    trackedAudioContexts.forEach(context => {
      if (context && context.state === "suspended" && typeof context.resume === "function") {
        Promise.resolve(context.resume()).catch(() => {});
      }
    });
  }

  function forceMediaSound() {
    resumeTrackedAudioContexts();
    for (const media of allMediaElements()) {
      try { media.removeAttribute("muted"); } catch (_) {}
      try { media.defaultMuted = false; } catch (_) {}
      try { media.muted = false; } catch (_) {}
      try { if (!Number.isFinite(media.volume) || media.volume <= 0.001) media.volume = 1; } catch (_) {}
      if (media.paused && media.autoplay && media.readyState > 0 && typeof media.play === "function") {
        Promise.resolve(media.play()).catch(() => {});
      }
    }
  }

  function hideSoundButton() {
    if (soundButton && soundButton.isConnected) soundButton.remove();
    soundButton = null;
  }

  function requestSoundRecovery() {
    soundRecoveryUntil = performance.now() + 4000;
    logToAndroid({ type: "console", level: "log", message: "Media sound unlock requested" });
    forceMediaSound();
    [80, 250, 700, 1500, 3000].forEach(delay => setTimeout(() => {
      if (performance.now() <= soundRecoveryUntil) forceMediaSound();
    }, delay));
    setTimeout(() => {
      const stillMuted = allMediaElements().some(mediaNeedsSound);
      if (!stillMuted) hideSoundButton();
    }, 500);
  }

  function ensureSoundButton() {
    if (soundButton && soundButton.isConnected) return;
    const parent = document.body || document.documentElement;
    if (!parent) return;
    soundButton = document.createElement("button");
    soundButton.type = "button";
    soundButton.textContent = "🔊 Enable sound";
    soundButton.setAttribute("aria-label", "Enable sound for this app");
    Object.assign(soundButton.style, {
      all: "initial",
      position: "fixed",
      right: "10px",
      bottom: "64px",
      zIndex: "2147483647",
      display: "block",
      boxSizing: "border-box",
      padding: "10px 14px",
      border: "1px solid rgba(255,255,255,.35)",
      borderRadius: "18px",
      background: "rgba(18,25,22,.94)",
      color: "#fff",
      font: "600 14px sans-serif",
      lineHeight: "20px",
      boxShadow: "0 2px 10px rgba(0,0,0,.45)",
      cursor: "pointer",
      pointerEvents: "auto",
      userSelect: "none"
    });
    soundButton.addEventListener("click", event => {
      event.preventDefault();
      event.stopPropagation();
      requestSoundRecovery();
    }, true);
    parent.appendChild(soundButton);
  }

  function inspectMediaSound() {
    const mutedPlayback = allMediaElements().some(media => mediaNeedsSound(media) && (!media.paused || media.autoplay));
    if (mutedPlayback) ensureSoundButton();
    else if (performance.now() > soundRecoveryUntil) hideSoundButton();
  }

  let mediaInspectionScheduled = false;
  function scheduleMediaInspection() {
    if (mediaInspectionScheduled) return;
    mediaInspectionScheduled = true;
    requestAnimationFrame(() => {
      mediaInspectionScheduled = false;
      inspectMediaSound();
    });
  }

  function looksLikeSoundControl(target) {
    if (!(target instanceof Element)) return false;
    const control = target.closest("button,[role='button'],a,label,[aria-label],[title]") || target;
    const text = [control.textContent, control.getAttribute("aria-label"), control.getAttribute("title")]
      .filter(Boolean).join(" ").toLowerCase();
    return /unmute|enable sound|sound on|turn on sound|tap for sound|listen|volume/.test(text);
  }

  ["play", "playing", "loadedmetadata", "volumechange", "emptied"].forEach(type => {
    document.addEventListener(type, scheduleMediaInspection, true);
  });

  document.addEventListener("pointerup", event => {
    if (!event.isTrusted) return;
    resumeTrackedAudioContexts();
    const mediaTarget = event.target instanceof Element ? event.target.closest("audio,video") : null;
    if (mediaTarget || looksLikeSoundControl(event.target)) requestSoundRecovery();
  }, true);
  document.addEventListener("touchend", event => {
    if (!event.isTrusted) return;
    resumeTrackedAudioContexts();
    if (looksLikeSoundControl(event.target)) requestSoundRecovery();
  }, true);
  document.addEventListener("keydown", event => {
    if (!event.isTrusted) return;
    resumeTrackedAudioContexts();
    if (["Enter", " ", "SoftLeft", "SoftRight"].includes(event.key) && looksLikeSoundControl(event.target)) {
      requestSoundRecovery();
    }
  }, true);

  const mutedDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "muted");
  if (mutedDescriptor && mutedDescriptor.configurable && mutedDescriptor.get && mutedDescriptor.set) {
    Object.defineProperty(HTMLMediaElement.prototype, "muted", {
      configurable: mutedDescriptor.configurable,
      enumerable: mutedDescriptor.enumerable,
      get() { return mutedDescriptor.get.call(this); },
      set(value) {
        const forceAudible = performance.now() <= soundRecoveryUntil;
        mutedDescriptor.set.call(this, forceAudible && value ? false : value);
      }
    });
  }
  const volumeDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "volume");
  if (volumeDescriptor && volumeDescriptor.configurable && volumeDescriptor.get && volumeDescriptor.set) {
    Object.defineProperty(HTMLMediaElement.prototype, "volume", {
      configurable: volumeDescriptor.configurable,
      enumerable: volumeDescriptor.enumerable,
      get() { return volumeDescriptor.get.call(this); },
      set(value) {
        const forceAudible = performance.now() <= soundRecoveryUntil;
        const numeric = Number(value);
        volumeDescriptor.set.call(this, forceAudible && numeric <= 0.001 ? 1 : value);
      }
    });
  }

  const mediaObserver = new MutationObserver(() => {
    if (performance.now() <= soundRecoveryUntil) forceMediaSound();
    scheduleMediaInspection();
  });
  const observeMediaRoot = () => {
    const root = document.documentElement;
    if (!root) return requestAnimationFrame(observeMediaRoot);
    mediaObserver.observe(root, { subtree: true, childList: true, attributes: true, attributeFilter: ["muted", "autoplay", "src"] });
    inspectMediaSound();
  };
  observeMediaRoot();

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

  document.addEventListener("kai-key", event => {
    const detail = event.detail || {};
    if (detail.dispatchedByContent) return;
    const target = document.activeElement || document.body || document.documentElement;
    if (!target) return;
    target.dispatchEvent(new KeyboardEvent(detail.phase === "up" ? "keyup" : "keydown", {
      key: detail.key,
      bubbles: true,
      cancelable: true,
      repeat: Boolean(detail.repeat),
      composed: true
    }));
  });

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
      audioChannel: true,
      systemMessages: true,
      contacts: true,
      alarms: true,
      settings: true,
      activities: true,
      gamepad: true,
      telephony: false,
      sms: false
    }),
    getBattery: () => request("battery"),
    getNetwork: () => request("network"),
    vibrate: duration => request("vibrate", { duration }),
    getLocation: () => request("location"),
    showNotification: (title, body) => request("notification", { title, body })
  };

  Object.freeze(runtime);
  Object.defineProperty(window, "KaiRuntime", { value: runtime, enumerable: true });
  Object.defineProperty(navigator, "kaiRuntime", { value: runtime, enumerable: true });

  const storageDb = new Promise((resolve, reject) => {
    const open = indexedDB.open("kai-device-storage", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("files", { keyPath: "name" });
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error);
  });

  class CompatibleDOMRequest extends EventTarget {
    constructor() {
      super();
      this.result = null;
      this.error = null;
      this.readyState = "pending";
    }
  }
  defineEventHandlerProperties(CompatibleDOMRequest.prototype, ["success", "error"]);

  function domRequest(operation) {
    const request = new CompatibleDOMRequest();
    Promise.resolve().then(operation).then(value => {
      request.result = value;
      request.readyState = "done";
      request.dispatchEvent(new Event("success"));
    }, error => {
      request.error = error instanceof Error ? error : new Error(String(error));
      request.readyState = "done";
      request.dispatchEvent(new Event("error"));
    });
    return request;
  }

  async function withStore(mode, operation) {
    const db = await storageDb;
    return new Promise((resolve, reject) => {
      const transaction = db.transaction("files", mode);
      const request = operation(transaction.objectStore("files"));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
      transaction.onabort = () => reject(transaction.error || new Error("Device storage transaction aborted"));
    });
  }

  function asFile(record) {
    return new File([record.data], record.name, {
      type: record.type || "application/octet-stream",
      lastModified: record.lastModified || Date.now()
    });
  }

  function enumerate(prefix) {
    const cursor = new CompatibleDOMRequest();
    let records = [];
    let index = 0;
    let dispatchQueued = false;
    let recordsReady = false;
    cursor.done = false;

    function emit() {
      if (!recordsReady || dispatchQueued || cursor.done) return;
      dispatchQueued = true;
      cursor.readyState = "pending";
      queueMicrotask(() => {
        dispatchQueued = false;
        if (index < records.length) {
          cursor.result = asFile(records[index++]);
          cursor.done = false;
        } else {
          cursor.result = null;
          cursor.done = true;
        }
        cursor.readyState = "done";
        cursor.dispatchEvent(new Event("success"));
      });
    }

    cursor.continue = () => {
      if (!cursor.done) emit();
    };

    withStore("readonly", store => store.getAll()).then(all => {
      records = all.filter(record => !prefix || record.name.startsWith(prefix));
      recordsReady = true;
      emit();
    }, error => {
      cursor.error = error instanceof Error ? error : new Error(String(error));
      cursor.done = true;
      cursor.readyState = "done";
      cursor.dispatchEvent(new Event("error"));
    });
    return cursor;
  }

  class CompatibleDeviceStorage extends EventTarget {
    constructor(storageName) {
      super();
      this.storageName = storageName;
      this.default = true;
      this.isRemovable = false;
      this.canBeMounted = false;
    }

    _change(reason, path) {
      const event = new Event("change");
      Object.defineProperties(event, {
        reason: { enumerable: true, value: reason },
        path: { enumerable: true, value: path }
      });
      this.dispatchEvent(event);
    }

    available() { return domRequest(() => "available"); }
    storageStatus() { return this.available(); }

    freeSpace() {
      return domRequest(async () => {
        const records = await withStore("readonly", store => store.getAll());
        const used = records.reduce((total, record) => total + (record.data.size || 0), 0);
        return Math.max(0, 50 * 1024 * 1024 - used);
      });
    }

    usedSpace() {
      return domRequest(async () => {
        const records = await withStore("readonly", store => store.getAll());
        return records.reduce((total, record) => total + (record.data.size || 0), 0);
      });
    }

    get(name) {
      return domRequest(async () => {
        const normalized = String(name).replace(/^\/+/, "");
        const record = await withStore("readonly", store => store.get(normalized));
        if (!record) throw new DOMException(`File not found: ${normalized}`, "NotFoundError");
        return asFile(record);
      });
    }

    getEditable(name) { return this.get(name); }

    add(blob) {
      const name = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
      return this.addNamed(blob, name);
    }

    addNamed(blob, name) {
      return domRequest(async () => {
        const safeName = String(name).replace(/^\/+/, "");
        if (!safeName || safeName.split("/").includes("..")) throw new DOMException("Invalid storage path", "InvalidModificationError");
        await withStore("readwrite", store => store.put({
          name: safeName, data: blob, type: blob && blob.type, lastModified: Date.now()
        }));
        this._change("created", safeName);
        return safeName;
      });
    }

    delete(name) {
      return domRequest(async () => {
        const safeName = String(name).replace(/^\/+/, "");
        await withStore("readwrite", store => store.delete(safeName));
        this._change("deleted", safeName);
        return safeName;
      });
    }

    enumerate(prefix) {
      return enumerate(prefix ? String(prefix).replace(/^\/+/, "") : "");
    }

    enumerateEditable(prefix) { return this.enumerate(prefix); }
  }
  defineEventHandlerProperties(CompatibleDeviceStorage.prototype, ["change"]);

  function createDeviceStorage(storageName) {
    return new CompatibleDeviceStorage(storageName);
  }


  // A virtual standard gamepad lets HTML5 games use the KaiOS D-pad and keypad
  // without requiring a physical Android controller.
  const gamepadButtons = Array.from({ length: 17 }, () => ({ pressed: false, touched: false, value: 0 }));
  const virtualGamepad = {
    id: "Kai Runtime Virtual Keypad",
    index: 0,
    connected: true,
    mapping: "standard",
    timestamp: performance.now(),
    axes: [0, 0, 0, 0],
    buttons: gamepadButtons,
    vibrationActuator: null
  };
  function updateGamepad(key, pressed) {
    const buttonMap = { Enter: 0, SoftRight: 1, Backspace: 1, SoftLeft: 8, EndCall: 9, "*": 4, "#": 5 };
    if (Object.prototype.hasOwnProperty.call(buttonMap, key)) {
      const button = gamepadButtons[buttonMap[key]];
      button.pressed = pressed;
      button.touched = pressed;
      button.value = pressed ? 1 : 0;
    }
    if (key === "ArrowLeft") virtualGamepad.axes[0] = pressed ? -1 : (virtualGamepad.axes[0] < 0 ? 0 : virtualGamepad.axes[0]);
    if (key === "ArrowRight") virtualGamepad.axes[0] = pressed ? 1 : (virtualGamepad.axes[0] > 0 ? 0 : virtualGamepad.axes[0]);
    if (key === "ArrowUp") virtualGamepad.axes[1] = pressed ? -1 : (virtualGamepad.axes[1] < 0 ? 0 : virtualGamepad.axes[1]);
    if (key === "ArrowDown") virtualGamepad.axes[1] = pressed ? 1 : (virtualGamepad.axes[1] > 0 ? 0 : virtualGamepad.axes[1]);
    virtualGamepad.timestamp = performance.now();
  }
  document.addEventListener("kai-key", event => {
    const detail = event.detail || {};
    updateGamepad(detail.key, detail.phase !== "up");
  });
  if (!navigator.getGamepads) {
    Object.defineProperty(navigator, "getGamepads", { configurable: true, value: () => [virtualGamepad, null, null, null] });
  }
  queueMicrotask(() => {
    try { window.dispatchEvent(new GamepadEvent("gamepadconnected", { gamepad: virtualGamepad })); } catch (_) {}
  });

  const contactsDb = new Promise((resolve, reject) => {
    const open = indexedDB.open("kai-contacts", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("contacts", { keyPath: "id" });
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error);
  });
  function withContacts(mode, operation) {
    return contactsDb.then(db => new Promise((resolve, reject) => {
      const request = operation(db.transaction("contacts", mode).objectStore("contacts"));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    }));
  }
  function MozContact(properties) {
    Object.assign(this, properties || {});
    if (!this.id) this.id = `contact-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }
  Object.defineProperty(window, "mozContact", { configurable: true, value: MozContact });
  Object.defineProperty(window, "MozContact", { configurable: true, value: MozContact });
  const mozContacts = {
    find(options) {
      return domRequest(async () => {
        const all = await withContacts("readonly", store => store.getAll());
        const query = String(options && options.filterValue || "").toLowerCase();
        if (!query) return all;
        const fields = options && options.filterBy || ["name", "givenName", "familyName", "tel", "email"];
        return all.filter(contact => fields.some(field => {
          const value = contact[field];
          return JSON.stringify(value == null ? "" : value).toLowerCase().includes(query);
        }));
      });
    },
    save(contact) {
      return domRequest(async () => {
        const normalized = JSON.parse(JSON.stringify(contact || {}));
        if (!normalized.id) normalized.id = `contact-${Date.now()}-${Math.random().toString(16).slice(2)}`;
        await withContacts("readwrite", store => store.put(normalized));
        Object.assign(contact, normalized);
        return contact;
      });
    },
    remove(contact) {
      return domRequest(async () => {
        const id = typeof contact === "string" ? contact : contact && contact.id;
        if (!id) throw new Error("Contact id is required");
        await withContacts("readwrite", store => store.delete(id));
        return null;
      });
    },
    clear() {
      return domRequest(async () => { await withContacts("readwrite", store => store.clear()); return null; });
    },
    getAll() { return this.find({}); }
  };
  if (!navigator.mozContacts) Object.defineProperty(navigator, "mozContacts", { configurable: true, value: mozContacts });

  let alarmSequence = 0;
  const alarms = new Map();
  const mozAlarms = {
    add(date, respectTimezone, data) {
      return domRequest(() => {
        const id = ++alarmSequence;
        const when = new Date(date).getTime();
        const alarm = { id, date: new Date(when), respectTimezone: String(respectTimezone || "ignoreTimezone"), data };
        const delay = Math.max(0, when - Date.now());
        alarm.timer = setTimeout(() => {
          alarms.delete(id);
          const handler = systemMessageHandlers.get("alarm");
          if (handler) handler({ id, date: alarm.date, data: alarm.data });
          window.dispatchEvent(new CustomEvent("kai-system-message", { detail: { type: "alarm", data: alarm } }));
        }, Math.min(delay, 0x7fffffff));
        alarms.set(id, alarm);
        return id;
      });
    },
    remove(id) {
      const alarm = alarms.get(Number(id));
      if (alarm) clearTimeout(alarm.timer);
      alarms.delete(Number(id));
    },
    getAll() {
      return domRequest(() => Array.from(alarms.values()).map(({ timer, ...alarm }) => alarm));
    }
  };
  if (!navigator.mozAlarms) Object.defineProperty(navigator, "mozAlarms", { configurable: true, value: mozAlarms });

  const settingsValues = new Map();
  const settingsObservers = new Map();
  const mozSettings = {
    createLock() {
      return {
        get(name) { return domRequest(() => ({ [name]: settingsValues.get(name) })); },
        set(values) {
          return domRequest(() => {
            Object.entries(values || {}).forEach(([name, value]) => {
              settingsValues.set(name, value);
              (settingsObservers.get(name) || []).forEach(callback => callback({ settingName: name, settingValue: value }));
            });
            return null;
          });
        }
      };
    },
    addObserver(name, callback) {
      if (!settingsObservers.has(name)) settingsObservers.set(name, []);
      settingsObservers.get(name).push(callback);
    },
    removeObserver(name, callback) {
      const list = settingsObservers.get(name) || [];
      const index = list.indexOf(callback);
      if (index >= 0) list.splice(index, 1);
    }
  };
  if (!navigator.mozSettings) Object.defineProperty(navigator, "mozSettings", { configurable: true, value: mozSettings });

  if (!navigator.mozL10n) {
    Object.defineProperty(navigator, "mozL10n", {
      configurable: true,
      value: {
        get: (id, args) => String(id).replace(/\{\{\s*([^}]+)\s*\}\}/g, (_, key) => args && key in args ? args[key] : ""),
        once: callback => queueMicrotask(callback),
        ready: callback => queueMicrotask(callback),
        language: { code: navigator.language || "en-US", direction: "ltr" },
        translate: () => undefined
      }
    });
  }

  if (!screen.mozLockOrientation) {
    screen.mozLockOrientation = orientation => {
      try {
        const value = Array.isArray(orientation) ? orientation[0] : orientation;
        if (screen.orientation && screen.orientation.lock) screen.orientation.lock(value).catch(() => {});
        return true;
      } catch (_) { return false; }
    };
  }
  if (!screen.mozUnlockOrientation) {
    screen.mozUnlockOrientation = () => {
      try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (_) {}
    };
  }

  function MozActivity(options) {
    this.source = options || {};
    this.result = null;
    this.error = null;
    this.onsuccess = null;
    this.onerror = null;
    queueMicrotask(async () => {
      try {
        const name = this.source.name;
        const data = this.source.data || {};
        if ((name === "view" || name === "open") && (data.url || data.uri)) {
          location.href = new URL(data.url || data.uri, location.href).href;
          this.result = true;
        } else if (name === "share" && navigator.share) {
          await navigator.share({ title: data.title, text: data.text, url: data.url });
          this.result = true;
        } else {
          this.result = data;
        }
        if (typeof this.onsuccess === "function") this.onsuccess({ target: this });
      } catch (error) {
        this.error = error;
        if (typeof this.onerror === "function") this.onerror({ target: this });
      }
    });
  }
  if (!window.MozActivity) Object.defineProperty(window, "MozActivity", { configurable: true, value: MozActivity });

  const nativeWindowOpen = window.open.bind(window);
  window.open = function (url, target, features) {
    const opened = nativeWindowOpen(url, target, features);
    if (opened || !url) return opened;
    try { location.href = new URL(url, location.href).href; return window; } catch (_) { return null; }
  };

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

  const connectionInfo = new EventTarget();
  Object.defineProperties(connectionInfo, {
    type: { enumerable: true, get: () => navigator.onLine ? "wifi" : "none" },
    effectiveType: { enumerable: true, get: () => navigator.onLine ? "4g" : "slow-2g" },
    downlink: { enumerable: true, get: () => navigator.onLine ? 10 : 0 },
    rtt: { enumerable: true, get: () => navigator.onLine ? 50 : 0 },
    saveData: { enumerable: true, value: false },
    metered: { enumerable: true, value: false },
    bandwidth: { enumerable: true, get: () => navigator.onLine ? 10 : 0 }
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
        connected: navigator.onLine,
        roaming: false,
        network: null,
        type: navigator.onLine ? "wifi" : null
      })
    }
  });
  mobileConnection.getNetworks = () => domRequest(() => []);
  mobileConnection.selectNetworkAutomatically = () => domRequest(() => null);
  if (!navigator.mozMobileConnections) {
    Object.defineProperty(navigator, "mozMobileConnections", { configurable: true, value: [mobileConnection] });
  }

  async function currentApplication() {
    let manifest = { name: document.title || "KaiOS App", type: "web", permissions: {} };
    try {
      const response = await fetch("/manifest.webapp");
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
})();
