import { chromium } from 'playwright-core';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '../..');
const html = await readFile(path.join(root, 'extension/options/options.html'), 'utf8');
const popup = await readFile(path.join(root, 'extension/popup/popup.js'), 'utf8');
assert.doesNotMatch(html, /data-tab="(?:stats|blocks|strict|settings)"|id="tab-(?:stats|blocks|strict|settings)"/, 'Account page does not expose dashboard sections');
assert.match(html, /id="accountSignIn"/);
assert.match(html, /id="accountSync"/);
assert.match(popup, /id="block-site"/);
assert.doesNotMatch(popup, /data-open-tab|function openDashboard/, 'Popup does not navigate to the old dashboard');

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext();
await context.addInitScript(() => {
  window.__tabs = [];
  window.__frogResponse = { supported: true, enabled: true, frog: { title: 'Read a chapter' }, trackedSeconds: 300, requiredSeconds: 900, locked: true };
  window.chrome = {
    runtime: { lastError: null, getURL: path => new URL('/extension/' + path, location.origin).href,
      sendMessage: (message, callback) => { callback?.(message.type === 'frogStatus' ? window.__frogResponse : { ok: true }); return Promise.resolve({ ok: true }); } },
    tabs: { update: async options => window.__tabs.push(options.url), getCurrent: callback => callback({ id: 1 }), remove: () => {} },
  };
});
await context.route('**/*', async route => {
  const url = new URL(route.request().url());
  if (url.hostname !== 'focuslock.test') return route.abort();
  const file = path.join(root, ...url.pathname.split('/').filter(Boolean));
  try { return route.fulfill({ contentType: path.extname(file) === '.html' ? 'text/html' : 'text/javascript', body: await readFile(file) }); }
  catch { return route.fulfill({ status: 404, body: 'Not found' }); }
});
const page = await context.newPage();
await page.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com&list=Strict&mode=strict');
await page.locator('#frogCta').waitFor({ state: 'visible' });
assert.match(await page.locator('#frogTitle').textContent(), /Read a chapter/);
assert.match(await page.locator('#frogCta').getAttribute('href'), /popup\/popup\.html/);
await page.locator('#dashboard').click();
assert.match(await page.evaluate(() => window.__tabs.at(-1)), /options\/options\.html\?account=sync/);
await page.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com&list=Permanent&mode=permanent');
assert.equal(await page.locator('#frogCard').isHidden(), true, 'Permanent block continues to hide alternate navigation');
assert.equal(await page.locator('#snoozeBtn').isHidden(), true, 'Permanent block remains non-snoozable');
await browser.close();
console.log('Compact UI regression tests passed.');
