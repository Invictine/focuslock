import { chromium } from 'playwright-core';
import { build } from 'esbuild';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '../..');
const clerkStub = `export function createClerkClient(){return {loaded:true,user:null,session:null,addListener:()=>()=>{},client:{reload:async()=>{}},signOut:async()=>{}}}`;
const popupClerkFailure = `export function createClerkClient(){throw new Error('fixture auth failure')}`;
const bundles = {};
for (const [entry, stub] of [['options/options-auth.js', clerkStub], ['popup/popup.js', popupClerkFailure]]) {
  const result = await build({
    entryPoints: [path.join(root, 'extension', entry)], bundle: true, write: false, format: 'iife',
    define: { 'process.env.CLERK_PUBLISHABLE_KEY': '"fixture"', 'process.env.CLERK_SYNC_HOST': '"https://accounts.example.test"', 'process.env.CLERK_SIGN_IN_URL': '"https://accounts.example.test/sign-in"' },
    plugins: [{ name: 'auth-fixture', setup(buildApi) {
      buildApi.onResolve({ filter: /^@clerk/ }, () => ({ path: 'clerk', namespace: 'fixture' }));
      buildApi.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: stub, loader: 'js' }));
    } }],
  });
  bundles[`/extension/dist/${path.basename(entry)}`] = result.outputFiles[0].text;
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
await context.addInitScript(() => {
  const key = 'focuslock.v1';
  const original = JSON.parse(localStorage.getItem(key) || 'null');
  if (!original) localStorage.setItem(key, JSON.stringify({
    version: 1, lists: [{ id: 'test-list', name: 'Test boundary', mode: 'blacklist', enabled: true, alwaysOn: true, sites: ['example.com'], exceptions: [], lockedUntil: Date.now() + 60000, dailyLimitMin: 0 }],
    schedules: [], nuclear: { active: false, until: 0, allow: [] }, snoozes: {}, stats: {}, blockedLog: [], blockedTotal: 0,
    strictMode: false, strictEndsAt: 0, security: { salt: '', hash: '', strict: true }, settings: { idleTimeoutSec: 60 },
  }));
  window.__messages = [];
  window.__snoozeError = 'This block cannot be snoozed.';
  window.__frogResponse = null;
  window.chrome = {
    runtime: {
      lastError: null,
      getURL: (p) => new URL('/extension/' + p, location.origin).href,
      sendMessage: (msg, callback) => {
        window.__messages.push(msg);
        const current = JSON.parse(localStorage.getItem('focuslock.v1') || '{}');
        const result = msg.type === 'featureStatus' ? { ok: true,
          strictMode: current.strictMode === true && (!Number(current.strictEndsAt) || Number(current.strictEndsAt) > Date.now()),
          strictEndsAt: current.strictEndsAt || 0,
          frog: { enabled: false, phase: 'not-armed', frog: null, trackedSeconds: 0, requiredSeconds: 1800, locked: false }, timer: null }
          : msg.type === 'getDashboard' ? { signedIn: false }
          : msg.type === 'protectionStatus' ? { engineRunning: true, focused: true, listsActive: 1, listsTotal: 1, signedIn: false }
          : msg.type === 'frogStatus' ? (window.__frogResponse || { supported: false })
          : msg.type === 'snooze' ? { ok: false, error: window.__snoozeError }
          : { ok: true };
        if (typeof callback === 'function') queueMicrotask(() => callback(result));
        return Promise.resolve(result);
      },
    },
    tabs: { query: async () => [{ url: 'https://example.com/article' }], create: async () => ({ id: 2 }), update: async () => {}, getCurrent: async () => ({ id: 1 }), remove: async () => {} },
    storage: { local: {
      get: async (requested) => ({ [requested]: JSON.parse(localStorage.getItem(requested) || 'null') }),
      set: async (values) => Object.entries(values).forEach(([k, v]) => localStorage.setItem(k, JSON.stringify(v))),
    } },
  };
});
await context.route('**/*', async (route) => {
  const url = new URL(route.request().url());
  if (url.hostname !== 'focuslock.test') return route.abort();
  const bundle = bundles[url.pathname];
  if (bundle) return route.fulfill({ contentType: 'text/javascript', body: bundle });
  const file = path.join(root, ...url.pathname.split('/').filter(Boolean));
  const type = path.extname(file) === '.html' ? 'text/html' : path.extname(file) === '.css' ? 'text/css' : 'text/javascript';
  try { return route.fulfill({ contentType: type, body: await readFile(file) }); }
  catch { return route.fulfill({ status: 404, body: 'Not found' }); }
});

const options = await context.newPage();
const pageErrors = [];
options.on('pageerror', (error) => pageErrors.push(error.message));
await options.goto('http://focuslock.test/extension/options/options.html?tab=blocks');
await options.locator('#boundaryHub [data-boundary-route="sites"]').click();
await options.locator('#surface-sites > details.advanced').evaluate(el => { el.open = true; });
await options.locator('#lists .card').first().waitFor();
const frozen = options.locator('#lists .card').first();
await frozen.locator('details.list-editor').evaluate((el) => { el.open = true; });
await frozen.locator('[data-f="sites"]').evaluate((el) => { el.disabled = false; el.value = 'changed.example'; });
await frozen.locator('[data-a="save"]').evaluate(el => { el.disabled = false; });
await frozen.locator('[data-a="save"]').click();
await options.waitForFunction(() => /frozen/i.test(document.getElementById('toast').textContent));
await assert.match(await options.locator('#toast').textContent(), /frozen/i, 'Locked-list save reports a clear failure');
assert.deepEqual(JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1'))).lists[0].sites, ['example.com'], 'Frozen-list save does not write');

const current = JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1')));
current.nuclear = { active: true, until: Date.now() + 60000, allow: [] };
await options.evaluate((state) => localStorage.setItem('focuslock.v1', JSON.stringify(state)), current);
await options.locator('#importBtn').click();
await options.locator('#importFile').setInputFiles({ name: 'reset.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ ...current, nuclear: { active: false, until: 0, allow: [] } })) });
await options.waitForFunction(() => /Nuclear block/i.test(document.getElementById('toast').textContent));
await assert.match(await options.locator('#toast').textContent(), /Nuclear block/i, 'Import reports that active Nuclear must be stopped first');
assert.equal(JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1'))).nuclear.active, true, 'Import preserves active Nuclear state');

current.strictMode = true; current.strictEndsAt = Date.now() + 60000;
await options.evaluate((state) => localStorage.setItem('focuslock.v1', JSON.stringify(state)), current);
await options.reload();
await options.locator('#boundaryHub [data-boundary-route="sites"]').click();
await options.locator('#surface-sites > details.advanced').evaluate(el => { el.open = true; });
await options.locator('#lists .card').first().waitFor();
assert.equal(await options.locator('#newList').isDisabled(), true, 'Strict Mode visibly disables list creation');
// Even a stale/enabled control must hit the latest-state write guard.
await options.locator('#newList').evaluate(el => { el.disabled = false; });
await options.locator('#newList').click();
await options.locator('#toast').waitFor({ state: 'visible' });
assert.match(await options.locator('#toast').textContent(), /Strict Mode/i, 'Strict Mode blocks list creation');
assert.equal(await options.locator('#lists .card').count(), 1, 'Strict Mode does not add a list');

// Permalock: append-only under Strict Mode, duplicate/invalid input rejected,
// no removal control, and a full reset keeps every permanent block.
await options.locator('[data-boundary-back]').click();
await options.locator('#permalockHubTile').click();
await options.locator('#permaInput').fill('https://www.Perma.example/path');
await options.locator('#permaAdd').click();
await options.waitForFunction(() => (JSON.parse(localStorage.getItem('focuslock.v1')).permanentSites || []).length === 1);
assert.deepEqual(JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1'))).permanentSites, ['perma.example'],
  'Strict Mode still allows adding a permanent block');
assert.equal(await options.locator('#permaRows [data-del]').count(), 0, 'Permanent rows offer no remove control');
assert.equal(await options.locator('#permaRows .badge.lock').count(), 1, 'Permanent rows carry the Permanent badge');
const withPermaOrdinaryEntry = JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1')));
withPermaOrdinaryEntry.lists[0].sites.push('perma.example');
await options.evaluate(state => localStorage.setItem('focuslock.v1', JSON.stringify(state)), withPermaOrdinaryEntry);
await options.reload();
await options.locator('#boundaryHub [data-boundary-route="sites"]').click();
assert.equal(await options.locator('#siteRows').getByText('perma.example').count(), 0, 'Permanent domains are omitted from ordinary website rows');
await options.locator('[data-boundary-back]').click();
await options.locator('#permalockHubTile').click();

await options.locator('#permaInput').fill('perma.example');
await options.locator('#permaAdd').click();
await options.waitForFunction(() => /already permanently blocked/i.test(document.getElementById('toast').textContent));
assert.deepEqual(JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1'))).permanentSites, ['perma.example'],
  'Duplicate permanent domains are rejected');

await options.locator('#permaInput').fill('not a domain');
await options.locator('#permaAdd').click();
await options.waitForFunction(() => /Use a domain like/i.test(document.getElementById('toast').textContent));
assert.deepEqual(JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1'))).permanentSites, ['perma.example'],
  'Invalid permanent domains are rejected');

await options.evaluate(() => {
  const snapshot = JSON.parse(localStorage.getItem('focuslock.v1'));
  snapshot.strictMode = false; snapshot.strictEndsAt = 0;
  snapshot.nuclear = { active: false, until: 0, allow: [] };
  snapshot.lists.forEach((list) => { list.lockedUntil = 0; });
  localStorage.setItem('focuslock.v1', JSON.stringify(snapshot));
});
await options.reload();
await options.locator('.header-tools button[data-tab="settings"]').click();
options.once('dialog', (dialog) => dialog.accept());
await options.locator('#resetAll').click();
await options.waitForFunction(() => {
  const snapshot = JSON.parse(localStorage.getItem('focuslock.v1'));
  return (snapshot.lists || []).length === 2 && (snapshot.permanentSites || []).length === 1;
});
assert.deepEqual(JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1'))).permanentSites, ['perma.example'],
  'Reset keeps permanent blocks');

// Import cannot remove a permanent block.
const afterResetState = JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1')));
await options.locator('nav button[data-tab="blocks"]').click();
await options.locator('#boundaryHub [data-boundary-route="sites"]').click();
await options.locator('#surface-sites > details.advanced').evaluate((el) => { el.open = true; });
await options.locator('#importBtn').click();
await options.locator('#importFile').setInputFiles({ name: 'no-permalock.json', mimeType: 'application/json',
  buffer: Buffer.from(JSON.stringify({ ...afterResetState, permanentSites: [] })) });
await options.waitForFunction(() => (JSON.parse(localStorage.getItem('focuslock.v1')).permanentSites || []).length === 1);
assert.deepEqual(JSON.parse(await options.evaluate(() => localStorage.getItem('focuslock.v1'))).permanentSites, ['perma.example'],
  'Import cannot remove permanent blocks');

const popup = await context.newPage();
await popup.goto('http://focuslock.test/extension/popup/popup.html');
await popup.locator('#block-site').waitFor();
assert.match(await popup.locator('.error').textContent(), /Local tracking and boundaries are still ready/i, 'Auth initialization failure retains local popup');
assert.equal(await popup.locator('#block-site').isEnabled(), true, 'Local popup actions remain available without Clerk');
await popup.evaluate(() => { window.__snoozeError = 'This shared daily limit cannot be paused.'; });
await popup.locator('#allow-site').click();
await popup.waitForFunction(() => /shared daily limit cannot be paused/i.test(document.querySelector('.feedback')?.textContent || ''));
assert.match(await popup.locator('.feedback').textContent(), /shared daily limit cannot be paused/i,
  'Popup surfaces the shared daily limit pause denial');

const blocked = await context.newPage();
await blocked.addInitScript(() => {
  window.__timerCallback = null;
  window.setInterval = (callback) => { window.__timerCallback = callback; return 1; };
  window.clearInterval = () => { window.__timerCallback = null; };
});
await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com%2Farticle&list=Test&mode=blacklist');
await blocked.locator('#frogTitle').waitFor({ state: 'attached' });
assert.equal(await blocked.locator('#frogCard').isHidden(), true, 'No unsupported Frog panel is shown when browser Frog is unavailable');
assert.equal(await blocked.locator('#dashboard').textContent(), 'Open Focus', 'Blocked page has a direct Focus action');
await blocked.addInitScript(() => { window.__frogResponse = { supported: true, enabled: true, frog: { title: 'Read a chapter' }, trackedSeconds: 300, requiredSeconds: 900, locked: true }; });
await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com%2Farticle&list=Strict&mode=strict');
await blocked.locator('#frogCta').waitFor({ state: 'visible' });
assert.match(await blocked.locator('#frogTitle').textContent(), /Read a chapter/);
assert.match(await blocked.locator('#frogCta').getAttribute('href'), /options\.html\?tab=stats&frog=1/);
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Strict Mode hides snooze before interaction');
await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com%2Farticle&list=Schedule&mode=schedule');
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Scheduled block hides snooze before interaction');
await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com%2Farticle&list=Eat%20the%20Frog&mode=frog');
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Frog lock hides snooze before interaction');
assert.match(await blocked.locator('.sub').textContent(), /Frog task and the required focused time/i, 'Frog lock explains its completion condition');
await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com%2Farticle&list=Frozen&mode=blacklist&reason=frozen-lock');
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Frozen-list lock hides snooze before interaction');
await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com%2Farticle&list=Test&mode=blacklist');
await blocked.locator('#snoozeBtn').click();
const phrase = (await blocked.locator('#phrase').textContent()).replace(/[“”]/g, '').trim();
await blocked.locator('#phraseInput').fill(phrase);
await blocked.evaluate(() => { for (let i = 0; i < 8; i++) window.__timerCallback?.(); });
await blocked.locator('#phraseInput').fill('incorrect phrase');
assert.equal(await blocked.locator('#count').textContent(), '60', 'Changing the phrase resets the delay');
assert.equal(await blocked.locator('#snoozeGo').isDisabled(), true, 'Changing the phrase disables snooze');
await blocked.locator('#phraseInput').fill(phrase);
await blocked.evaluate(() => { for (let i = 0; i < 61; i++) window.__timerCallback?.(); });
await blocked.locator('#snoozeGo').click();
await blocked.locator('#snoozeStatus').waitFor();
assert.equal(await blocked.locator('#snoozeStatus').textContent(), 'This block cannot be snoozed.', 'Denied snooze is shown inline');
assert.match(blocked.url(), /blocked\.html/, 'Denied snooze does not leave the blocked page');

await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com&list=Shared%20Nuclear%20Block&mode=shared-nuke');
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Shared Nuclear never offers a snooze control');
assert.match(await blocked.locator('.sub').textContent(), /complete reset.*Android or Windows/i, 'Shared Nuclear explains where to reset');

await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fexample.com&list=Daily%20limit&mode=group-limit');
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Merged daily limits do not show an emergency pause action');
assert.match(await blocked.locator('.sub').textContent(), /daily limit.*cannot override it.*earned Focus time permits access/i,
  'Merged daily limit explains why a pause is unavailable and how account-shared sites reopen');

await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fperma.example%2F&list=Permanent%20block&mode=permanent');
assert.equal(await blocked.locator('#listPill').textContent(), 'Permanent block');
assert.match(await blocked.locator('.sub').textContent(), /no removal, credits, or emergency pass/i, 'Permanent blocks explain there is no way out');
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Permanent blocks never offer a snooze control');
assert.equal(await blocked.locator('#snoozeBox').isHidden(), true, 'Permanent snooze box stays hidden');
assert.equal(await blocked.locator('#frogCard').isHidden(), true, 'Permanent blocks hide the frog task card');
assert.equal(await blocked.locator('#dashboard').isHidden(), true, 'Permanent blocks hide the dashboard link');
assert.equal(await blocked.locator('#goBack').isVisible(), true, 'Back navigation remains on a permanent block');

await blocked.goto('http://focuslock.test/extension/blocked/blocked.html?url=https%3A%2F%2Fperma.example%2F&list=Permanent%20block');
assert.equal(await blocked.locator('#dashboard').isHidden(), true, 'Empty mode with a Permanent list is still treated as permanent');
assert.equal(await blocked.locator('#snoozeBtn').isHidden(), true, 'Empty mode with a Permanent list offers no snooze');
assert.deepEqual(pageErrors, [], 'No options-page runtime errors');
console.log('UI guard regressions passed.');
await browser.close();
