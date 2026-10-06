// Compact extension UI smoke: the popup remains local-first and account sync
// lives on a persistent page so authentication survives popup closure.
import { chromium } from 'playwright-core';
import { build } from 'esbuild';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '../..');
const clerkStub = `export function createClerkClient(){const listeners=[];const client={loaded:true,user:{firstName:'UI',primaryEmailAddress:{emailAddress:'review@example.test'}},session:{id:'fixture'},addListener:fn=>(listeners.push(fn),()=>{}),client:{reload:async()=>{}},signOut:async function(){this.user=null;this.session=null;listeners.forEach(fn=>fn());}};return client}`;
const bundled = {};
for (const entry of ['popup/popup.js', 'options/options-auth.js']) {
  const result = await build({ entryPoints: [path.join(root, 'extension', entry)], bundle: true, write: false, format: 'iife',
    define: { 'process.env.CLERK_PUBLISHABLE_KEY': '"fixture"', 'process.env.CLERK_SYNC_HOST': '"https://accounts.example.test"', 'process.env.CLERK_SIGN_IN_URL': '"https://accounts.example.test/sign-in"' },
    plugins: [{ name: 'auth-fixture', setup(api) { api.onResolve({ filter: /^@clerk/ }, () => ({ path: 'clerk', namespace: 'fixture' })); api.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: clerkStub, loader: 'js' })); } }],
  });
  bundled[`/extension/dist/${path.basename(entry)}`] = result.outputFiles[0].text;
}
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 390, height: 760 }, colorScheme: 'light' });
await context.addInitScript(() => {
  window.__tabs = [];
  window.__messages = [];
  window.__frog = location.search.includes('frog-fixture') ? { enabled: true, armed: true, locked: true,
    requiredSeconds: 60, trackedSeconds: 0, tickedOff: false, frog: { id: 'frog-1', title: 'Finish report' } } : null;
  window.__timer = null;
  window.chrome = {
    runtime: { getURL: path => new URL('/extension/' + path, location.origin).href, lastError: null,
      sendMessage: async message => { window.__messages.push(message);
        if (message.type === 'featureStatus') return { strictMode: false, frog: window.__frog || { enabled: false }, timer: window.__timer };
        if (message.type === 'focusTimerStart') window.__timer = { id: 'timer-1', startedAt: Date.now(), targetMinutes: message.minutes };
        if (message.type === 'focusTimerFinish') { window.__frog.trackedSeconds = 60; window.__timer = null; }
        if (message.type === 'frogTick') { window.__frog.tickedOff = message.tickedOff; window.__frog.locked = !(window.__frog.tickedOff && window.__frog.trackedSeconds >= window.__frog.requiredSeconds); }
        return { ok: true,
        summary: { totalTrackedSeconds: 900 }, devices: [{ platform: 'browser', name: 'Chrome', trackingStatus: 'active', lastSeen: Date.now() }, { platform: 'android', name: 'Android', trackingStatus: 'active', lastSeen: Date.now() }], lastSyncAt: Date.now() }; } },
    tabs: { query: async () => [{ url: 'https://example.com/path' }], create: async options => { window.__tabs.push(options.url); return { id: 2 }; }, update: async options => { window.__tabs.push(options.url); }, getCurrent: async () => ({ id: 1 }), remove: async () => {} },
    storage: { local: { get: async key => ({ [key]: JSON.parse(localStorage.getItem(key) || 'null') }), set: async values => Object.entries(values).forEach(([key, value]) => localStorage.setItem(key, JSON.stringify(value))) } },
  };
});
await context.route('**/*', async route => {
  const url = new URL(route.request().url());
  if (url.hostname !== 'focuslock.test') return route.abort();
  if (bundled[url.pathname]) return route.fulfill({ contentType: 'text/javascript', body: bundled[url.pathname] });
  const file = path.join(root, ...url.pathname.split('/').filter(Boolean));
  try { return route.fulfill({ contentType: path.extname(file) === '.html' ? 'text/html' : path.extname(file) === '.css' ? 'text/css' : 'text/javascript', body: await readFile(file) }); }
  catch { return route.fulfill({ status: 404, body: 'Not found' }); }
});
const errors = [];
const popup = await context.newPage();
popup.on('pageerror', error => errors.push(error.message));
await popup.goto('http://focuslock.test/extension/popup/popup.html');
await popup.locator('#block-site').waitFor();
assert.equal(await popup.locator('#block-site').isEnabled(), true, 'Quick block remains available in the popup');
assert.equal(await popup.locator('[data-open-tab]').count(), 0, 'Popup has no links to the removed dashboard');
await popup.locator('#settings').click();
assert.match(await popup.evaluate(() => window.__tabs.at(-1)), /options\.html\?account=profile$/, 'Account and sync opens a persistent page');
const account = await context.newPage();
account.on('pageerror', error => errors.push(error.message));
await account.goto('http://focuslock.test/extension/options/options.html?account=profile');
await account.locator('#accountSync').waitFor({ state: 'visible' });
assert.match(await account.locator('#accountTitle').textContent(), /review@example\.test/, 'Signed-in account remains visible after popup closure');
assert.equal(await account.locator('#accountDevicesCard').isVisible(), true, 'Account page shows synced devices');
assert.equal(await account.locator('#accountSync').isVisible(), true, 'Account page offers manual sync');
assert.equal(await account.locator('#tab-stats, #tab-blocks, #tab-strict, #tab-settings').count(), 0, 'The full dashboard sections are gone');
await account.locator('#accountSync').click();
await account.waitForFunction(() => window.__messages.filter(message => message.type === 'cloudSnapshot').length > 1);
await account.locator('#accountSignOut').click();
await account.waitForFunction(() => document.getElementById('accountSignIn').hidden === false && document.getElementById('accountSync').hidden === true);
assert.ok((await account.evaluate(() => window.__messages)).some(message => message.type === 'cloudSignOut'), 'Sign-out clears the background sync session');
const frogPopup = await context.newPage();
frogPopup.on('pageerror', error => errors.push(error.message));
await frogPopup.goto('http://focuslock.test/extension/popup/popup.html?frog-fixture');
await frogPopup.locator('#frogTimerStart').waitFor({ state: 'visible' });
assert.match(await frogPopup.locator('#frog-heading').textContent(), /Finish report/, 'Existing locked browser Frog is available in the compact popup');
await frogPopup.locator('#frogTimerStart').click();
await frogPopup.locator('#frogTimerFinish').waitFor({ state: 'visible' });
await frogPopup.locator('#frogTimerFinish').click();
await frogPopup.locator('#frogDone').click();
await frogPopup.waitForFunction(() => document.querySelector('.frog-head .state')?.textContent === 'Complete');
assert.equal(await frogPopup.locator('#frog-heading').textContent(), 'Finish report', 'Completing a Frog preserves its task history');
assert.deepEqual(errors, [], 'Compact popup and account surface have no runtime errors');
console.log('Compact extension UI tests passed.');
await browser.close();
