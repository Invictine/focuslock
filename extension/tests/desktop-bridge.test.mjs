import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../src/desktop-bridge.js', import.meta.url), 'utf8');

function event() {
  const listeners = new Set();
  return {
    addListener: listener => listeners.add(listener),
    removeListener: listener => listeners.delete(listener),
    fire: (...args) => [...listeners].forEach(listener => listener(...args)),
    get size() { return listeners.size; }
  };
}

function harness(overrides = {}) {
  const ports = [];
  let connectAttempts = 0;
  const windows = [
    { id: 10, type: 'normal', state: 'normal', left: 11, top: 12, width: 900, height: 700,
      tabs: [{ active: true, title: 'Example page', url: 'https://private.example/path' }] },
    { id: 11, type: 'popup', state: 'normal', tabs: [{ active: true, title: 'Popup' }] },
    { id: 12, type: 'normal', state: 'minimized', tabs: [{ active: true, title: 'Hidden' }] }
  ];
  const chrome = {
    runtime: {
      lastError: null,
      connectNative(host) {
        connectAttempts += 1;
        if (overrides.connectThrows) throw new Error('Specified native messaging host not found.');
        const port = {
          host,
          messages: [],
          onDisconnect: event(),
          onMessage: event(),
          postMessage(message) { this.messages.push(message); },
          disconnect() { this.disconnected = true; }
        };
        ports.push(port);
        return port;
      }
    },
    windows: {
      getAll: async () => overrides.windows ? overrides.windows() : windows,
      onFocusChanged: event(), onBoundsChanged: event(), onCreated: event(), onRemoved: event()
    },
    tabs: { onActivated: event(), onUpdated: event() },
    permissions: {
      contains: async query => { chrome.lastPermissionQuery = query; return true; },
      onAdded: event(), onRemoved: event()
    },
    extension: { isAllowedIncognitoAccess: async () => true },
    alarms: {
      create: (...args) => { chrome.alarm = args; },
      clear: async name => { chrome.clearedAlarm = name; return true; },
      onAlarm: event()
    }
  };
  const timerApi = overrides.timerApi || { setInterval, clearInterval, setTimeout, clearTimeout };
  const context = { self: {}, globalThis: {}, ...timerApi, Promise };
  vm.runInNewContext(source, context);
  return { bridge: context.self.FocusLockDesktopBridge, chrome, ports, windows,
    get connectAttempts() { return connectAttempts; } };
}

function fakeTimers() {
  let now = 0;
  let id = 0;
  const tasks = new Map();
  return {
    setTimeout(callback, delay) {
      const task = { id: ++id, callback, at: now + delay, cancelled: false };
      tasks.set(task.id, task);
      return task.id;
    },
    clearTimeout(taskId) {
      const task = tasks.get(taskId);
      if (task) task.cancelled = true;
    },
    setInterval() { return ++id; },
    clearInterval() {},
    advance(milliseconds) {
      now += milliseconds;
      let task;
      while ((task = [...tasks.values()].filter(item => !item.cancelled && item.at <= now)
        .sort((left, right) => left.at - right.at)[0])) {
        tasks.delete(task.id);
        task.callback();
      }
    }
  };
}

const tick = () => new Promise(resolve => setImmediate(resolve));

test('sends native heartbeat with visible window titles and permission state, never URLs', async () => {
  const { bridge, chrome, ports } = harness();
  bridge.start(chrome);
  await tick();
  assert.equal(ports.length, 1);
  assert.equal(ports[0].host, 'com.focuslock.browser');
  assert.deepEqual(JSON.parse(JSON.stringify(ports[0].messages[0])), {
    type: 'heartbeat', version: 1, allUrls: true, incognitoAllowed: true,
    windows: [{ id: 10, title: 'Example page', left: 11, top: 12, width: 900, height: 700 }]
  });
  assert.equal(JSON.stringify(chrome.lastPermissionQuery), JSON.stringify({
    origins: ['http://*/*', 'https://*/*']
  }));
  assert.equal(JSON.stringify(ports[0].messages[0]).includes('private.example'), false);
  bridge.stop();
});

test('caps reports and titles', async () => {
  const longTitle = 'x'.repeat(900);
  const { bridge, chrome, ports } = harness({
    windows: async () => Array.from({ length: 140 }, (_, id) => ({
      id, type: 'normal', state: 'normal', title: longTitle, tabs: [{ active: true, title: longTitle }]
    }))
  });
  bridge.start(chrome);
  await tick();
  assert.equal(ports[0].messages[0].windows.length, 128);
  assert.equal(ports[0].messages[0].windows[0].title.length, 512);
  bridge.stop();
});

test('missing native host or incomplete Chrome APIs are safe and use the retry alarm', () => {
  const { bridge, chrome, ports } = harness({ connectThrows: true });
  assert.doesNotThrow(() => bridge.start(chrome));
  assert.ok(chrome.alarm);
  assert.equal(ports.length, 0);
  bridge.stop();

  const context = { self: {}, globalThis: {}, setInterval, clearInterval, setTimeout, clearTimeout, Promise };
  vm.runInNewContext(source, context);
  assert.doesNotThrow(() => context.self.FocusLockDesktopBridge.start({ runtime: {} }));
});

test('an unregistered host gets six quick attempts then waits for the minute alarm', async () => {
  const timerApi = fakeTimers();
  const state = harness({ connectThrows: true, timerApi });
  state.bridge.start(state.chrome);
  assert.equal(state.connectAttempts, 1);
  for (let attempt = 0; attempt < 6; attempt += 1) {
    timerApi.advance(5000);
    await tick();
  }
  assert.equal(state.connectAttempts, 7);
  timerApi.advance(5000);
  await tick();
  assert.equal(state.connectAttempts, 7, 'it should stop rapid retries after six retries');
  state.chrome.alarms.onAlarm.fire({ name: 'focuslock-desktop-bridge-reconnect' });
  assert.equal(state.connectAttempts, 8, 'the minute alarm provides a later recovery attempt');
  state.bridge.stop();
});

test('disconnect retries after five seconds and ignores stale port events', async () => {
  const { bridge, chrome, ports } = harness();
  bridge.start(chrome);
  await tick();
  const first = ports[0];
  chrome.runtime.lastError = { message: 'Native host has exited.' };
  first.onDisconnect.fire();
  chrome.runtime.lastError = null;
  assert.equal(chrome.alarm[0], 'focuslock-desktop-bridge-reconnect');
  await new Promise(resolve => setTimeout(resolve, 5100));
  await tick();
  assert.equal(ports.length, 2);
  first.onDisconnect.fire();
  assert.equal(ports[1].disconnected, undefined);
  bridge.stop();
});

test('an in-flight snapshot from a disconnected port is discarded', async () => {
  let resolveWindows;
  const { bridge, chrome, ports } = harness({
    windows: () => new Promise(resolve => { resolveWindows = resolve; })
  });
  bridge.start(chrome);
  assert.equal(ports.length, 1);
  ports[0].onDisconnect.fire();
  resolveWindows([]);
  await tick();
  assert.equal(ports[0].messages.length, 0);
  bridge.stop();
});

test('start is idempotent, change events request a fresh heartbeat, stop detaches listeners', async () => {
  const { bridge, chrome, ports } = harness();
  bridge.start(chrome);
  bridge.start(chrome);
  await tick();
  assert.equal(ports.length, 1);
  const count = ports[0].messages.length;
  chrome.tabs.onUpdated.fire(1, { title: 'Changed' });
  await tick();
  assert.ok(ports[0].messages.length > count);
  assert.equal(chrome.tabs.onUpdated.size, 1);
  bridge.stop();
  assert.equal(chrome.tabs.onUpdated.size, 0);
  assert.equal(ports[0].disconnected, true);
});
