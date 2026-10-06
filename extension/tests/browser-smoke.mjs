// Real unpacked MV3 extension in a disposable Chromium profile. This verifies
// package loading, local boundary persistence/enforcement, and the compact UI.
import { chromium } from 'playwright-core';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const out = path.join(root, 'build', 'extension-verification');
await mkdir(out, { recursive: true });
const server = createServer((_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<!doctype html><title>FocusLock test</title><h1>Local test website</h1>'); });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const site = `http://127.0.0.1:${server.address().port}/`;
const extension = path.join(root, 'build', 'extension-unpacked');
let context;
const checks = [];
try {
  context = await chromium.launchPersistentContext('', { channel: 'chromium', headless: true,
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`] });
  const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
  const id = new URL(worker.url()).hostname;
  const errors = [];
  const account = await context.newPage();
  account.on('pageerror', error => errors.push(error.message));
  await account.goto(`chrome-extension://${id}/options/options.html?account=signin`);
  await account.locator('#accountSignIn').waitFor({ state: 'visible' });
  assert.equal(await account.locator('#tab-stats, #tab-blocks, #tab-strict, #tab-settings').count(), 0);
  assert.equal(await account.locator('#accountDevicesCard').isHidden(), true);
  assert.deepEqual(await account.locator('script').evaluateAll(nodes => nodes.map(node => new URL(node.src).pathname)),
    ['/dist/options-auth.js'], 'Account page loads only the authentication and sync controller');
  await account.screenshot({ path: path.join(out, 'extension-account.png'), fullPage: true });
  checks.push('Packaged extension opens the compact persistent account page without dashboard assets');

  await worker.evaluate(async () => {
    const state = await ensureState();
    state.settings.idleTimeoutSec = 3600;
    state.lists = [{ id: 'smoke', name: 'Browser test', enabled: true, alwaysOn: true,
      mode: 'blacklist', sites: ['127.0.0.1'], exceptions: [], dailyLimitMin: 0, lockedUntil: 0 }];
    await FocusLockStore.save(state);
  });
  await account.evaluate(() => chrome.runtime.sendMessage({ type: 'refresh' }));
  const popup = await context.newPage();
  popup.on('pageerror', error => errors.push(error.message));
  await popup.goto(`chrome-extension://${id}/popup/popup.html`);
  await popup.locator('#block-site').waitFor({ state: 'visible' });
  assert.equal(await popup.locator('[data-open-tab], #dashboard').count(), 0, 'Popup does not offer dashboard navigation');
  assert.equal(await popup.locator('#block-site').isVisible(), true, 'Quick block control remains in the popup');
  await popup.screenshot({ path: path.join(out, 'extension-popup.png'), fullPage: true });
  checks.push('Popup keeps current-site tracking and quick block controls without dashboard links');

  const website = await context.newPage();
  let blocked = false;
  for (let attempt = 0; attempt < 6; attempt++) {
    await website.goto(site).catch(error => { if (!/ERR_ABORTED|interrupted/.test(error.message)) throw error; });
    await website.bringToFront();
    try { await website.waitForURL(`chrome-extension://${id}/blocked/blocked.html**`, { timeout: 3000, waitUntil: 'commit' }); blocked = true; break; }
    catch (error) { if (attempt === 5) throw error; }
  }
  assert.equal(blocked, true, 'Packaged worker eventually redirects the HTTP navigation');
  await website.locator('#blockedUrl').waitFor();
  assert.match(await website.locator('#dashboard').textContent(), /Account & sync/);
  checks.push('Real HTTP navigation is blocked and the block page keeps its controls');
  await website.screenshot({ path: path.join(out, 'blocked.png'), fullPage: true });

  assert.equal(await worker.evaluate(async () => (await FocusLockStore.load()).lists[0].id), 'smoke', 'Boundary settings remain persisted');

  // Exercise policy transitions through the packaged background worker, with
  // no dashboard UI involved.
  await worker.evaluate(async () => {
    const state = await ensureState();
    state.lists = [];
    state.stats = {};
    state.leisureStats = {};
    state.cloudSites = [{ domain: '127.0.0.1', isBlocked: true }];
    state.cloudSitesLoaded = true;
    state.cloudSitesSyncedAt = Date.now();
    state.cloudPolicy = { state: { creditBalanceSeconds: 8, lastResetDate: FocusLockStore.todayKey(), totalScrollSecondsToday: 0 }, groups: [], limits: [], schedules: [] };
    state.cloudLeisureBaseline = {};
    await FocusLockStore.save(state);
  });
  await account.evaluate(() => chrome.runtime.sendMessage({ type: 'refresh' }));
  const earned = await context.newPage();
  await earned.goto(site);
  await earned.bringToFront();
  await earned.locator('h1').waitFor();
  await earned.waitForURL(`chrome-extension://${id}/blocked/blocked.html**`, { timeout: 15000, waitUntil: 'commit' });
  assert.equal(new URL(earned.url()).searchParams.get('mode'), 'earned-time');
  const spent = await worker.evaluate(async () => (await FocusLockStore.load()).leisureStats[FocusLockStore.todayKey()]?.['127.0.0.1'] || 0);
  assert.ok(spent > 0 && spent < 10, `Expected earned time to be persisted, received ${spent}`);
  checks.push('Cached earned time permits a shared site briefly, records the spend, then blocks it');
  await earned.close();

  await worker.evaluate(async () => {
    const state = await ensureState();
    state.leisureStats = {};
    state.cloudPolicy.state.creditBalanceSeconds = 60;
    state.cloudPolicy.groups = [{ groupId: 'smoke-cap', name: 'Smoke cap', limitEnabled: true, dailyLimitMinutes: 1,
      members: [{ targetKind: 'app', targetKey: 'phone.test', targetLabel: 'Phone test' }, { targetKind: 'website', targetKey: '127.0.0.1', targetLabel: 'Browser test' }] }];
    state.cloudUsage = { date: FocusLockStore.todayKey(), groups: [{ groupId: 'smoke-cap', trackedSeconds: 59 }], targets: [] };
    state.cloudUsageBaseline = {};
    await FocusLockStore.save(state);
  });
  await account.evaluate(() => chrome.runtime.sendMessage({ type: 'refresh' }));
  const cappedVerdict = await account.evaluate(url => chrome.runtime.sendMessage({ type: 'verdict', url }), site);
  assert.equal(cappedVerdict.mode, 'daily-limit');
  const capPause = await account.evaluate(url => chrome.runtime.sendMessage({ type: 'snooze', url }), site);
  assert.equal(capPause.ok, false, 'Merged daily limits cannot be snoozed');
  checks.push('Merged app and website caps block at the cached usage limit and reject snoozes');

  await worker.evaluate(async () => {
    const state = await ensureState();
    state.cloudPolicy.groups = [];
    state.cloudUsage = { date: FocusLockStore.todayKey(), groups: [], targets: [] };
    state.cloudPolicy.state.creditBalanceSeconds = 60;
    state.leisureStats = {};
    await FocusLockStore.save(state);
    await syncCloud('live', { ok: true, signedIn: true, userId: 'smoke-account', isCurrent: async () => true,
      prefs: { strictMode: true, strictEndsAt: Date.now() + 60000 } });
  });
  const funded = await worker.evaluate(async () => verdictFor('http://127.0.0.1/', await ensureState()));
  assert.equal(funded.blocked, false, 'Strict Mode does not itself block an earned-time site');
  await worker.evaluate(async () => {
    const state = await ensureState(); state.cloudPolicy.state.creditBalanceSeconds = 0; state.leisureStats = {}; state.snoozes = {};
    await FocusLockStore.save(state); mem.state = state;
  });
  const strictPause = await account.evaluate(url => chrome.runtime.sendMessage({ type: 'snooze', url }), site);
  assert.equal(strictPause.ok, true, 'Strict Mode still permits a zero-credit site pause');
  await worker.evaluate(async () => { await FocusLockStore.update(state => { state.snoozes = {}; return state; }); mem.state = await FocusLockStore.load(); });
  await context.setOffline(true);
  const offlineVerdict = await account.evaluate(url => chrome.runtime.sendMessage({ type: 'verdict', url }), site);
  assert.equal(offlineVerdict.blocked, true, 'Cached boundary policy still blocks offline');
  await context.setOffline(false);
  checks.push('Synced Strict Mode leaves site access policy intact, preserves zero-credit snoozes, and cached rules enforce offline');

  await worker.evaluate(async () => {
    const state = await ensureState();
    state.strictMode = false; state.strictEndsAt = 0; state.cloudPrefs = { strictMode: false, strictEndsAt: 0 };
    state.cloudPolicy = null; state.cloudSites = []; state.permanentSites = [];
    state.browserFrog = self.FocusLockFeatures.frogState({ enabled: false });
    state.focusTimer = null; state.snoozes = {};
    await FocusLockStore.save(state);
  });
  const frogConfig = await account.evaluate(() => chrome.runtime.sendMessage({ type: 'frogConfigure', enabled: true, requiredMinutes: 1, wakeHour: 0 }));
  assert.equal(frogConfig.ok, true);
  const frogSelect = await account.evaluate(() => chrome.runtime.sendMessage({ type: 'frogSelect', title: 'Read a chapter' }));
  assert.equal(frogSelect.ok, true);
  const frogTimer = await account.evaluate(() => chrome.runtime.sendMessage({ type: 'focusTimerStart', minutes: 1 }));
  assert.equal(frogTimer.ok, true);
  await worker.evaluate(async () => {
    await FocusLockStore.update(state => { state.focusTimer.startedAt = Date.now() - 61000; return state; });
    mem.state = await FocusLockStore.load();
    await maintainFeatures(false);
  });
  const focusedFrog = await account.evaluate(() => chrome.runtime.sendMessage({ type: 'featureStatus' }));
  assert.equal(focusedFrog.timer, null, 'Elapsed background timer is recovered and completed after worker wake');
  assert.ok(focusedFrog.frog.trackedSeconds >= 60);
  const tick = await account.evaluate(() => chrome.runtime.sendMessage({ type: 'frogTick', tickedOff: true }));
  assert.equal(tick.frog.locked, false, 'Frog releases only after focus requirement and task completion');
  checks.push('Browser Frog and focus timer state survive background updates and release only after both requirements pass');

  assert.deepEqual(errors, [], 'Popup and account page have no uncaught runtime errors');
  const report = { realExtension: true, signedIn: false, checks, pageErrors: errors,
    limitations: ['A signed-in cross-device round trip requires a real account and Android device.'] };
  await writeFile(path.join(out, 'browser-results.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await context?.close();
  await new Promise(resolve => server.close(resolve));
}
