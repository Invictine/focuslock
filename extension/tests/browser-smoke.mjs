// Real unpacked MV3 extension in a disposable Chromium profile. Cached account
// policy is seeded for enforcement checks; this does not sign into an account.
import { chromium } from 'playwright-core';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const out = path.join(root, 'build', 'extension-verification');
await mkdir(out, { recursive: true });
const server = createServer((_req, res) => {
  res.setHeader('Content-Type', 'text/html');
  res.end('<!doctype html><title>FocusLock browser test</title><h1>Local test website</h1>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const site = `http://127.0.0.1:${server.address().port}/`;
const extension = path.join(root, 'build', 'extension-unpacked');
let context;
const checks = [];
try {
  context = await chromium.launchPersistentContext('', {
    channel: 'chromium', headless: true,
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
  });
  const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
  const id = new URL(worker.url()).hostname;
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(`chrome-extension://${id}/options/options.html`);
  await page.locator('#lists .card').first().waitFor({ state: 'attached' });
  checks.push('Packaged extension loads its real service worker and dashboard');
  await page.evaluate(async () => {
    const state = await FocusLockStore.load();
    // Headless automation does not move the OS mouse. Use the supported one-hour
    // idle setting so the actual chrome.idle API doesn't pause the timed fixture
    // simply because the human hasn't touched the keyboard during this test.
    state.settings.idleTimeoutSec = 3600;
    state.lists = [{ id: 'smoke', name: 'Browser test', enabled: true, alwaysOn: true,
      mode: 'blacklist', sites: ['127.0.0.1'], exceptions: [], dailyLimitMin: 0, lockedUntil: 0 }];
    await FocusLockStore.save(state);
    const refreshed = await chrome.runtime.sendMessage({ type: 'refresh' });
    if (!refreshed?.ok) throw new Error(`Refresh failed: ${JSON.stringify(refreshed)}`);
  });
  const website = await context.newPage();
  await website.goto(site).catch(error => {
    if (!/ERR_ABORTED|interrupted/.test(error.message)) throw error;
  });
  for (let attempt = 0; attempt < 8; attempt++) {
    try {
      await website.waitForURL(`chrome-extension://${id}/blocked/blocked.html**`, { timeout: 5000, waitUntil: 'commit' });
      break;
    } catch (error) { if (attempt === 7) {
      console.log('Failed navigation:', website.url());
      console.log('Worker verdict:', await page.evaluate(() => chrome.runtime.sendMessage({ type: 'verdict', url: 'http://127.0.0.1/' })));
      throw error;
    } }
  }
  await website.locator('#blockedUrl').waitFor();
  checks.push('Actual HTTP navigation is redirected to the packaged block page');
  await website.screenshot({ path: path.join(out, 'blocked.png'), fullPage: true });
  await page.reload();
  assert.equal(await page.evaluate(async () => (await FocusLockStore.load()).lists[0].id), 'smoke');
  checks.push('Saved boundaries survive dashboard reload');
  await page.evaluate(async () => {
    const state = await FocusLockStore.load();
    state.lists = [];
    state.stats = {};
    state.leisureStats = {};
    state.cloudSites = [{ domain: '127.0.0.1', isBlocked: true }];
    state.cloudSitesLoaded = true;
    state.cloudSitesSyncedAt = Date.now();
    state.cloudPolicy = { state: { creditBalanceSeconds: 4, lastResetDate: FocusLockStore.todayKey() },
      groups: [], limits: [], schedules: [] };
    state.cloudLeisureBaseline = {};
    await FocusLockStore.save(state);
    await chrome.runtime.sendMessage({ type: 'refresh' });
  });
  const leisure = await context.newPage();
  await leisure.goto(site);
  await leisure.bringToFront();
  await leisure.locator('h1').waitFor();
  checks.push('A selected shared website opens while its cached earned balance is positive');
  try {
    await leisure.waitForURL(`chrome-extension://${id}/blocked/blocked.html**`, { timeout: 15000, waitUntil: 'commit' });
  } catch (error) {
    console.log('Earned-time enforcement diagnostics:', await worker.evaluate(async () => {
      const state = await FocusLockStore.load();
      return { cursor: mem.cur, focused: mem.focused, checkpoint: state.trackingCheckpoint,
        stats: state.stats, spend: state.leisureStats, balance: state.cloudPolicy?.state?.creditBalanceSeconds,
        tabs: (await chrome.tabs.query({ active: true })).map(tab => ({ id: tab.id, url: tab.url })),
        idle: await chrome.idle.queryState(state.settings.idleTimeoutSec),
        windows: (await chrome.windows.getAll()).map(window => ({ id: window.id, focused: window.focused })) };
    }));
    throw error;
  }
  assert.equal(new URL(leisure.url()).searchParams.get('mode'), 'earned-time');
  const spent = await page.evaluate(async () => {
    const state = await FocusLockStore.load();
    return state.leisureStats[FocusLockStore.todayKey()]?.['127.0.0.1'];
  });
  assert.ok(spent >= 4 && spent < 10, `Expected a short persisted leisure slice, received ${spent}`);
  checks.push('The content guard spends earned time and redirects an already-open page when it is exhausted');
  await leisure.close();
  await page.evaluate(async () => {
    const state = await FocusLockStore.load();
    state.stats = {};
    state.leisureStats = {};
    state.cloudSites = [];
    state.cloudPolicy.groups = [{ groupId: 'merged-test', name: 'Phone and browser', limitEnabled: true,
      dailyLimitMinutes: 1, members: [{ targetKind: 'app', targetKey: 'phone.test', targetLabel: 'Phone test' },
        { targetKind: 'website', targetKey: '127.0.0.1', targetLabel: 'Browser test' }] }];
    state.cloudUsage = { date: FocusLockStore.todayKey(), groups: [{ groupId: 'merged-test', trackedSeconds: 59 }], targets: [] };
    state.cloudUsageBaseline = {};
    state.cloudSitesSyncedAt = Date.now();
    await FocusLockStore.save(state);
    await chrome.runtime.sendMessage({ type: 'refresh' });
  });
  const merged = await context.newPage();
  await merged.goto(site);
  await merged.bringToFront();
  await merged.waitForURL(`chrome-extension://${id}/blocked/blocked.html**`, { timeout: 10000, waitUntil: 'commit' });
  assert.equal(new URL(merged.url()).searchParams.get('mode'), 'daily-limit');
  const sharedCapSnooze = await page.evaluate(() => chrome.runtime.sendMessage({ type: 'snooze', url: 'http://127.0.0.1/' }));
  assert.equal(sharedCapSnooze.ok, false);
  checks.push('A merged app/website cap adds browser time to cached phone usage and rejects snoozing the limit');
  await merged.close();
  await page.evaluate(async () => {
    const state = await FocusLockStore.load();
    state.cloudPolicy = null;
    state.cloudSites = [{ domain: '127.0.0.1', isBlocked: true }];
    state.lists = [{ id: 'strict-ui', name: 'Local boundary', enabled: true, alwaysOn: true,
      mode: 'blacklist', sites: ['ui.example'], exceptions: [], dailyLimitMin: 0, lockedUntil: 0 }];
    await FocusLockStore.save(state);
    await chrome.runtime.sendMessage({ type: 'refresh' });
  });
  await page.bringToFront();
  await page.locator('nav button[data-tab="blocks"]').click();
  await page.locator('#siteRows [data-del]').first().waitFor();
  // Simulate the incoming mobile prefs at the cloud boundary, using the real
  // packaged worker, storage events, dashboard, and HTTP enforcement.
  await worker.evaluate(async () => {
    const state = await ensureState();
    await syncCloud('live', { ok: true, signedIn: true, userId: state.cloudAccountId,
      isCurrent: async () => true, prefs: { strictMode: true, strictEndsAt: Date.now() + 3000 } });
  });
  await page.waitForFunction(() => document.getElementById('lockdownBadge').textContent === 'Strict Mode');
  assert.equal(await page.locator('#presetUnblock').isDisabled(), true);
  assert.equal(await page.locator('#newList').isDisabled(), true);
  assert.equal(await page.locator('#siteRows [data-del]').first().isDisabled(), true);
  checks.push('Incoming mobile Strict Mode locks the already-open boundary dashboard without a reload');
  const denied = await page.evaluate(() => chrome.runtime.sendMessage({ type: 'snooze', url: 'http://127.0.0.1/', minutes: 999 }));
  assert.equal(denied.ok, false, 'Strict mode must reject snooze at the worker boundary');
  checks.push('Worker rejects a strict-mode snooze request');
  await page.waitForFunction(() => !document.getElementById('presetUnblock').disabled, { timeout: 10000 });
  assert.equal(await page.locator('#siteRows [data-del]').first().isDisabled(), false);
  checks.push('Boundary controls unlock when the synced commitment expires');
  await worker.evaluate(async () => {
    const state = await ensureState();
    await syncCloud('live', { ok: true, signedIn: true, userId: state.cloudAccountId,
      isCurrent: async () => true, prefs: { strictMode: true, strictEndsAt: Date.now() + 600000 } });
  });
  // Simulate offline browser operation; cached boundaries must remain enforceable.
  await context.setOffline(true);
  const verdict = await page.evaluate(() => chrome.runtime.sendMessage({ type: 'verdict', url: 'http://127.0.0.1/' }));
  assert.equal(verdict.blocked, true);
  checks.push('Cached boundaries still produce a blocking verdict offline');
  const popup = await context.newPage();
  await popup.goto(`chrome-extension://${id}/popup/popup.html`);
  await popup.locator('#block-site').waitFor({ timeout: 20000 });
  assert.equal(await popup.locator('.fatal').count(), 0);
  assert.equal(await popup.locator('#allow-site').isDisabled(), true);
  assert.match(await popup.locator('.current-site').textContent(), /Strict Mode is active/);
  await popup.screenshot({ path: path.join(out, 'popup-offline.png'), fullPage: true });
  checks.push('Real popup remains usable with network offline');
  await popup.close();
  await context.setOffline(false);
  for (const width of [1280, 840, 390]) {
    await page.setViewportSize({ width, height: 900 });
    for (const tab of ['stats', 'blocks', 'permalock', 'settings', 'account']) {
      await page.locator(`nav button[data-tab="${tab}"]`).click();
      if (tab === 'stats') await page.locator('#focusConnectNotice').waitFor({ state: 'visible' });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, `${tab} overflow at ${width}`);
      await page.screenshot({ path: path.join(out, `${tab}-${width}.png`), fullPage: true });
    }
  }
  checks.push('All five dashboard destinations fit 1280, 840 and 390px viewports');
  assert.deepEqual(errors, [], 'Dashboard has no uncaught page errors');
  const report = { realExtension: true, signedIn: false, seededPolicy: true, extensionId: id, checks, pageErrors: errors,
    limitations: ['Real signed-in cross-device sync requires the user account and Android device.'] };
  await writeFile(path.join(out, 'browser-results.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await context?.close();
  await new Promise(resolve => server.close(resolve));
}
