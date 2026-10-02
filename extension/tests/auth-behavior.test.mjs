import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';
import { test } from 'node:test';

const sourcePath = new URL('../options/options-auth.js', import.meta.url);

class FakeElement {
  constructor(id) {
    this.id = id;
    this.hidden = ['accountAuth', 'accountSync', 'accountSignOut', 'accountPitch', 'accountDevicesCard'].includes(id);
    this.disabled = false;
    this.textContent = '';
    this.innerHTML = '';
    this.listeners = new Map();
    this.classList = {
      values: new Set(),
      add: (...names) => names.forEach((name) => this.classList.values.add(name)),
      remove: (...names) => names.forEach((name) => this.classList.values.delete(name)),
      contains: (name) => this.classList.values.has(name),
    };
  }

  addEventListener(type, listener) { this.listeners.set(type, listener); }
  async click() { return this.listeners.get('click')?.({ currentTarget: this }); }
}

async function loadAuth({ browserSignInUrl = 'https://focuslock.example/sign-in', storage = new Map() } = {}) {
  const ids = ['accountTitle', 'accountDetail', 'accountState', 'accountSignIn', 'accountEmailSignIn', 'accountSync', 'accountSignOut', 'accountError', 'accountAuth', 'accountBadge', 'accountCloud', 'accountPitch', 'accountSignInPitch', 'accountDevicesCard', 'accountDeviceSummary', 'accountDevices'];
  const elements = Object.fromEntries(ids.map((id) => [id, new FakeElement(id)]));
  const tabsCalls = [];
  const messages = [];
  const listeners = [];
  const intervals = new Map();
  let nextIntervalId = 1;
  let now = 1_000_000;
  let user = null;
  let session = null;
  let backgroundSignedIn = false;
  let reloadCount = 0;
  const clerk = {
    get user() { return user; },
    get session() { return session; },
    client: { reload: async () => { reloadCount += 1; } },
    addListener: (listener) => listeners.push(listener),
    signOut: async () => { user = null; session = null; },
  };
  let createTab = async (details) => {
    tabsCalls.push({ type: 'create', details, pendingAtCall: storage.get('focuslock.browserAuthPending') });
    return { id: 7 };
  };
  const context = {
    console,
    URL,
    Date: class extends Date { static now() { return now; } },
    setInterval: (callback) => { const id = nextIntervalId++; intervals.set(id, callback); return id; },
    clearInterval: (id) => intervals.delete(id),
    process: { env: { CLERK_PUBLISHABLE_KEY: 'pk_test_focuslock', CLERK_SYNC_HOST: 'https://accounts.example', CLERK_SIGN_IN_URL: browserSignInUrl } },
    document: { documentElement: {}, getElementById: (id) => elements[id] ?? null },
    getComputedStyle: () => ({ getPropertyValue: () => '#123456' }),
    history: { replaceState: () => {} },
    location: { reload: () => {} },
    sessionStorage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)), removeItem: (key) => storage.delete(key) },
    window: { addEventListener: (type, listener) => listeners.push({ type, listener }) },
    chrome: {
      runtime: {
        getURL: (path) => `chrome-extension://focuslock/${path}`,
        sendMessage: async (message) => {
          messages.push(message);
          if (message.type === 'cloudAuthRefresh') return { signedIn: backgroundSignedIn };
          if (message.type === 'cloudSnapshot') return { devices: [], lastSyncAt: null };
          return {};
        },
      },
      tabs: {
        getCurrent: async () => { tabsCalls.push({ type: 'getCurrent' }); return { id: 3 }; },
        create: (...args) => createTab(...args),
        update: async (...args) => { tabsCalls.push({ type: 'update', args }); },
        remove: async (...args) => { tabsCalls.push({ type: 'remove', args }); },
      },
    },
  };
  context.createClerkClient = (options) => { clerk.clientOptions = options; return clerk; };
  let source = await fs.readFile(sourcePath, 'utf8');
  source = source.replace("import { createClerkClient } from '@clerk/chrome-extension/client';", '');
  source = `const createClerkClient = globalThis.createClerkClient;\n${source}`;
  vm.runInNewContext(source, context, { filename: sourcePath.pathname });
  await new Promise((resolve) => setImmediate(resolve));
  const flush = async () => { await new Promise((resolve) => setImmediate(resolve)); };
  return {
    elements, clerk, storage, tabsCalls, messages,
    setCreateTab: (fn) => { createTab = fn; },
    setSignedIn: (value) => { user = value ? { primaryEmailAddress: { emailAddress: 'person@example.com' } } : null; session = value ? { id: 'session_1' } : null; },
    setBackgroundSignedIn: (value) => { backgroundSignedIn = value; },
    advance: (milliseconds) => { now += milliseconds; },
    runTimers: async () => { await flush(); for (const callback of [...intervals.values()]) callback(); await flush(); },
    flush,
    get reloadCount() { return reloadCount; },
  };
}

test('portal-only signed-out view cannot report a successful connection', async () => {
  const app = await loadAuth();
  assert.equal(app.clerk.clientOptions.background, true);
  assert.equal(app.elements.accountEmailSignIn.hidden, true);
  await app.elements.accountSignIn.click();
  await app.flush();
  assert.equal(app.reloadCount, 1);
  assert.equal(app.storage.get('focuslock.browserAuthPending'), '1');
  assert.equal(app.tabsCalls.some((call) => call.type === 'update'), false);
  assert.equal(app.tabsCalls.some((call) => call.type === 'remove'), false);
  assert.equal(app.elements.accountSignIn.disabled, true);
  assert.match(app.elements.accountTitle.textContent, /Finish signing in/);
});

test('insecure browser sign-in URL is rejected before opening a tab', async () => {
  const app = await loadAuth({ browserSignInUrl: 'http://focuslock.example/sign-in' });
  await app.elements.accountSignIn.click();
  await app.flush();
  assert.equal(app.tabsCalls.some((call) => call.type === 'create'), false);
  assert.equal(app.storage.has('focuslock.browserAuthPending'), false);
  assert.match(app.elements.accountError.textContent, /not configured/i);
  assert.equal(app.elements.accountSignIn.disabled, false);
});

test('signed-in browser return refreshes the background tracker and restores the options tab', async () => {
  const app = await loadAuth();
  await app.elements.accountSignIn.click();
  await app.flush();
  app.setSignedIn(true);
  app.setBackgroundSignedIn(true);
  await app.runTimers();
  assert.equal(app.storage.has('focuslock.browserAuthPending'), false);
  assert.deepEqual(app.messages.map((message) => message.type), ['cloudAuthRefresh', 'cloudSnapshot']);
  assert.deepEqual(JSON.parse(JSON.stringify(app.tabsCalls.filter((call) => call.type === 'update')[0].args)), [3, { active: true }]);
  assert.deepEqual(app.tabsCalls.filter((call) => call.type === 'remove')[0].args, [7]);
  assert.match(app.elements.accountTitle.textContent, /Connected as person@example.com/);
});

test('browser sign-in handoff resumes after the options page reloads', async () => {
  const storage = new Map();
  const firstPage = await loadAuth({ storage });
  await firstPage.elements.accountSignIn.click();
  await firstPage.flush();
  assert.equal(storage.get('focuslock.browserAuthTabId'), '7');
  assert.equal(storage.get('focuslock.browserAuthReturnTabId'), '3');

  const reloadedPage = await loadAuth({ storage });
  assert.equal(reloadedPage.elements.accountSignIn.disabled, true);
  assert.match(reloadedPage.elements.accountTitle.textContent, /Finish signing in/);
  assert.equal(reloadedPage.reloadCount, 1);
  reloadedPage.setSignedIn(true);
  reloadedPage.setBackgroundSignedIn(true);
  await reloadedPage.runTimers();

  assert.equal(storage.has('focuslock.browserAuthPending'), false);
  assert.deepEqual(JSON.parse(JSON.stringify(reloadedPage.tabsCalls.find((call) => call.type === 'update').args)), [3, { active: true }]);
  assert.deepEqual(reloadedPage.tabsCalls.find((call) => call.type === 'remove').args, [7]);
});

test('a session without a signed-in background tracker keeps polling', async () => {
  const app = await loadAuth();
  await app.elements.accountSignIn.click();
  await app.flush();
  app.setSignedIn(true);
  await app.runTimers();
  assert.equal(app.storage.get('focuslock.browserAuthPending'), '1');
  assert.equal(app.messages.filter((message) => message.type === 'cloudAuthRefresh').length, 1);
  assert.equal(app.tabsCalls.some((call) => call.type === 'remove'), false);
  assert.match(app.elements.accountError.textContent, /Waiting for account connection/);
  app.setBackgroundSignedIn(true);
  await app.runTimers();
  assert.equal(app.storage.has('focuslock.browserAuthPending'), false);
  assert.equal(app.tabsCalls.filter((call) => call.type === 'remove').length, 1);
});

test('rejected browser tab creation clears pending state and renders an error', async () => {
  const app = await loadAuth();
  app.setCreateTab(async () => { throw new Error('tabs API unavailable'); });
  await app.elements.accountSignIn.click();
  await app.flush();
  assert.equal(app.storage.has('focuslock.browserAuthPending'), false);
  assert.equal(app.elements.accountError.hidden, false);
  assert.match(app.elements.accountError.textContent, /Could not open browser sign-in/);
  assert.equal(app.tabsCalls.some((call) => call.type === 'remove'), false);
});

test('expired browser handoff stops polling and explains that sign-in did not return', async () => {
  const app = await loadAuth();
  await app.elements.accountSignIn.click();
  app.advance(5 * 60 * 1000 + 1);
  await app.runTimers();
  assert.equal(app.storage.has('focuslock.browserAuthPending'), false);
  assert.equal(app.tabsCalls.some((call) => call.type === 'update'), false);
  assert.equal(app.tabsCalls.some((call) => call.type === 'remove'), false);
  assert.equal(app.elements.accountSignIn.disabled, false);
  assert.match(app.elements.accountError.textContent, /has not reached FocusLock/);
});
