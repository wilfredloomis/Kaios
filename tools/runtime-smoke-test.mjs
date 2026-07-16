import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const runtimePath = path.join(root, 'app/src/main/assets/extensions/kai-runtime/page-runtime.js');
const source = fs.readFileSync(runtimePath, 'utf8');

function extractDeclaration(token) {
  const start = source.indexOf(token);
  assert.notEqual(start, -1, `Missing ${token}`);
  const brace = source.indexOf('{', start);
  let depth = 0;
  let quote = null;
  let escaped = false;
  for (let i = brace; i < source.length; i++) {
    const ch = source[i];
    if (quote) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue; }
    if (ch === '{') depth++;
    else if (ch === '}') {
      depth--;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  throw new Error(`Unclosed declaration ${token}`);
}

class BrowserEvent {
  constructor(type) { this.type = type; this.target = null; this.currentTarget = null; }
}
class BrowserProgressEvent extends BrowserEvent {
  constructor(type, init = {}) { super(type); Object.assign(this, init); }
}
class BrowserEventTarget {
  constructor() { this.__listeners = new Map(); }
  addEventListener(type, callback) {
    if (!callback) return;
    const list = this.__listeners.get(type) || [];
    if (!list.includes(callback)) list.push(callback);
    this.__listeners.set(type, list);
  }
  removeEventListener(type, callback) {
    const list = this.__listeners.get(type) || [];
    this.__listeners.set(type, list.filter(item => item !== callback));
  }
  dispatchEvent(event) {
    event.target = this;
    event.currentTarget = this;
    for (const callback of [...(this.__listeners.get(event.type) || [])]) callback.call(this, event);
    event.currentTarget = null;
    return true;
  }
}
globalThis.__BrowserEvent = BrowserEvent;
globalThis.__BrowserProgressEvent = BrowserProgressEvent;
globalThis.__BrowserEventTarget = BrowserEventTarget;

const helper = extractDeclaration('function defineEventHandlerProperties');
const xhrClass = extractDeclaration('class CompatibleXMLHttpRequest extends EventTarget');
const requestClass = extractDeclaration('class CompatibleDOMRequest extends EventTarget');
const enumerateFn = extractDeclaration('function enumerate(prefix)');
const storageClass = extractDeclaration('class CompatibleDeviceStorage extends EventTarget');
const hlsWrapFn = extractDeclaration('function wrapHlsInstance');
const browserAliases = 'const EventTarget = globalThis.__BrowserEventTarget; const Event = globalThis.__BrowserEvent; const ProgressEvent = globalThis.__BrowserProgressEvent;';

// Exact helper + exact XHR class from the shipped runtime.
eval(`${browserAliases}\n${helper}\nconst XHR_EVENT_TYPES = ["readystatechange", "loadstart", "progress", "abort", "error", "load", "timeout", "loadend"];\nconst PROGRESS_EVENT_TYPES = ["loadstart", "progress", "abort", "error", "load", "timeout", "loadend"];\nclass CompatibleXMLHttpRequestUpload extends EventTarget {}\ndefineEventHandlerProperties(CompatibleXMLHttpRequestUpload.prototype, PROGRESS_EVENT_TYPES);\n${xhrClass}\ndefineEventHandlerProperties(CompatibleXMLHttpRequest.prototype, XHR_EVENT_TYPES);\nglobalThis.__XHR = CompatibleXMLHttpRequest;`);

const xhr = new globalThis.__XHR();
let propertyCalls = 0;
let listenerCalls = 0;
let currentTargetCorrect = false;
xhr.addEventListener('load', event => {
  listenerCalls++;
  assert.equal(event.currentTarget, xhr);
});
xhr.onload = event => {
  propertyCalls++;
  currentTargetCorrect = event.currentTarget === xhr && event.target === xhr;
};
xhr._emit('load');
assert.equal(propertyCalls, 1);
assert.equal(listenerCalls, 1);
assert.equal(currentTargetCorrect, true);

// Replacing an on* property must not retain the previous callback.
let oldCalls = 0;
xhr.onload = () => oldCalls++;
xhr.onload = () => propertyCalls++;
xhr._emit('load');
assert.equal(oldCalls, 0);
assert.equal(propertyCalls, 2);

// Exact request/cursor implementation with a deterministic IndexedDB mock.
eval(`${browserAliases}\n${helper}\n${requestClass}\ndefineEventHandlerProperties(CompatibleDOMRequest.prototype, ["success", "error"]);\nconst __records = [{name:"one"}, {name:"two"}];\nasync function withStore() { return __records; }\nfunction asFile(record) { return record; }\n${enumerateFn}\nglobalThis.__enumerate = enumerate;`);

const cursorValues = [];
await new Promise((resolve, reject) => {
  const cursor = globalThis.__enumerate('');
  cursor.onerror = () => reject(cursor.error);
  cursor.onsuccess = event => {
    assert.equal(event.currentTarget, cursor);
    if (cursor.done) {
      assert.equal(cursor.result, null);
      cursorValues.push('done');
      resolve();
      return;
    }
    cursorValues.push(cursor.result.name);
    cursor.continue();
  };
});
assert.deepEqual(cursorValues, ['one', 'two', 'done']);

// Exact DeviceStorage object must be a real EventTarget.
eval(`${browserAliases}\n${helper}\n${requestClass}\ndefineEventHandlerProperties(CompatibleDOMRequest.prototype, ["success", "error"]);\nfunction domRequest() { return new CompatibleDOMRequest(); }\nasync function withStore() { return []; }\nfunction enumerate() { return new CompatibleDOMRequest(); }\n${storageClass}\ndefineEventHandlerProperties(CompatibleDeviceStorage.prototype, ["change"]);\nglobalThis.__Storage = CompatibleDeviceStorage;`);
const storage = new globalThis.__Storage('sdcard');
let changeSeen = false;
storage.addEventListener('change', event => {
  changeSeen = event.currentTarget === storage && event.reason === 'created' && event.path === 'music/test.mp3';
});
storage._change('created', 'music/test.mp3');
assert.equal(changeSeen, true);
assert.equal(typeof storage.addEventListener, 'function');

// The old IPTV app calls hls.loadLevel(...) as a function. The compatibility
// proxy must translate numeric calls to the modern property and string calls
// to loadSource(), without changing internal method receivers.
eval(`const hlsInstanceCache = new WeakMap();
${hlsWrapFn}
globalThis.__wrapHlsInstance = wrapHlsInstance;`);
class FakeHls {
  constructor() { this.loadLevel = -1; this.source = null; this.started = 0; }
  loadSource(source) { this.source = source; }
  startLoad() { this.started++; }
  internalLevel() { return this.loadLevel; }
}
const nativeHls = new FakeHls();
const compatibleHls = globalThis.__wrapHlsInstance(nativeHls);
assert.equal(typeof compatibleHls.loadLevel, 'function');
compatibleHls.loadLevel(3);
assert.equal(nativeHls.loadLevel, 3);
assert.equal(nativeHls.started, 1);
assert.equal(compatibleHls.internalLevel(), 3);
compatibleHls.loadLevel('https://example.invalid/live.m3u8');
assert.equal(nativeHls.source, 'https://example.invalid/live.m3u8');
assert.equal(nativeHls.started, 2);

console.log('Kai Runtime 0.3.3 compatibility smoke tests passed');
