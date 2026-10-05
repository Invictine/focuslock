import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

const handlers = {};
const event = (name) => ({ addListener(fn) { handlers[name] = fn; } });
let activeTabs = [];
const tabsById = new Map();
const sessionData = {};
let fakeNow = Date.now();
const state = {
  lists: [{ id: 'social', name: 'Social', mode: 'blacklist', enabled: true, alwaysOn: true,
    sites: ['reddit.com'], exceptions: [], lockedUntil: 0, dailyLimitMin: 0 }],
  schedules: [], nuclear: { active: false, until: 0, allow: [] }, snoozes: {}, stats: {},
  blockedLog: [], blockedTotal: 0, strictMode: false, strictEndsAt: 0, settings: { idleTimeoutSec: 60 },
};
const chrome = {
  runtime: { id: 'focuslock-test', getURL: (p) => `chrome-extension://focuslock-test/${p}`,
    onMessage: event('message'), onInstalled: event('installed'), onStartup: event('startup') },
  tabs: { onActivated: event('activated'), onUpdated: event('updated'), onRemoved: event('removed'),
    async query() { return activeTabs; }, async update() {}, async get(id) { return tabsById.get(id) || {}; } },
  windows: { WINDOW_ID_NONE: -1, onFocusChanged: event('focus'), async getLastFocused() { return { focused: true }; } },
  webNavigation: { onBeforeNavigate: event('beforeNavigate'), onHistoryStateUpdated: event('history'), onReferenceFragmentUpdated: event('fragment') },
  alarms: { onAlarm: event('alarm'), async create() {} },
  idle: { async queryState(_timeout, cb) { cb('active'); } },
  storage: { local: { async get() { return {}; }, async set() {} }, session: {
    async get(key) { return { [key]: structuredClone(sessionData[key]) }; },
    async set(values) { Object.assign(sessionData, structuredClone(values)); },
  } },
  action: { async setBadgeText() {}, async setBadgeBackgroundColor() {} }, notifications: { create() {} },
};
class FakeDate extends Date { static now() { return fakeNow; } }
const context = { chrome, self: {}, console, URL, Date: FakeDate, Map, Promise, Math, JSON, Object, String, Number, Array, Boolean, structuredClone };
vm.createContext(context);
const matcher = await fs.readFile(new URL('../src/matcher.js', import.meta.url), 'utf8');
vm.runInContext(matcher, context);
vm.runInContext(await fs.readFile(new URL('../src/policy.js', import.meta.url), 'utf8'), context);
context.self.FocusLockCloud = {
  async syncUsage() { return { ok: false, signedIn: false }; }, async activateNuke() {},
  async refreshAuth() {}, async getSnapshot() { return {}; }, async signOut() {},
  async setWebsiteBlocked() { return { ok: true }; }, async getDashboard() { return {}; },
  async savePrefs() { return {}; }, async addWorkRecord() { return {}; }, async logFocusSession() { return {}; },
  async status() { return {}; },
};
context.self.FocusLockStore = {
  async load() { return structuredClone(state); }, async save() {}, todayKey() { return '2026-09-27'; },
  defaultState() { return structuredClone(state); },
};
context.importScripts = () => {};
const sw = await fs.readFile(new URL('../background/service-worker.js', import.meta.url), 'utf8');
vm.runInContext(sw, context);
const engine = context.self.FocusLockEngine;

assert.equal(engine.verdictFor('https://www.reddit.com/r/focus/', state, 100).blocked, true);
assert.equal(engine.verdictFor('chrome://settings/', state, 100).blocked, false);

// Daily allowance permits the site before the recorded budget is exhausted and
// produces a distinct verdict afterward.
const limited = structuredClone(state);
limited.lists[0].dailyLimitMin = 10;
limited.stats['2026-09-27'] = { 'reddit.com': 599 };
assert.equal(engine.verdictFor('https://reddit.com/', limited, 100).blocked, false);
limited.stats['2026-09-27']['reddit.com'] = 600;
assert.equal(engine.verdictFor('https://reddit.com/', limited, 100).mode, 'daily-limit');

// Message mutations require an extension page; a content script can only ask
// for a verdict about its own current document.
const send = (msg, sender) => new Promise((resolve) => {
  handlers.message(msg, sender, resolve);
});
assert.equal(JSON.stringify(await send({ type: 'snooze', url: 'https://reddit.com/' },
  { id: 'focuslock-test', tab: { id: 1 }, url: 'https://attacker.test/' })),
JSON.stringify({ ok: false, error: 'Untrusted message sender.' }));
assert.equal(JSON.stringify(await send({ type: 'verdict', url: 'https://attacker.test/' },
  { id: 'focuslock-test', tab: { id: 1 }, url: 'https://reddit.com/' })),
JSON.stringify({ ok: false, error: 'Invalid verdict request.' }));

const extensionSender = { id: 'focuslock-test', url: 'chrome-extension://focuslock-test/blocked/blocked.html' };
const strictState = structuredClone(state);
strictState.strictMode = true;
context.self.FocusLockStore.load = async () => structuredClone(strictState);
assert.equal((await send({ type: 'snooze', url: 'https://reddit.com/', minutes: 999 }, extensionSender)).ok, false);
const frozenState = structuredClone(state);
frozenState.lists[0].lockedUntil = Date.now() + 60_000;
context.self.FocusLockStore.load = async () => structuredClone(frozenState);
await send({ type: 'refresh' }, extensionSender);
assert.equal((await send({ type: 'snooze', url: 'https://reddit.com/', minutes: 1 }, extensionSender)).ok, false);

// Overnight schedules after midnight remain attached to the day they started.
class Monday0130 extends Date {
  getDay() { return 1; }
  getHours() { return 1; }
  getMinutes() { return 30; }
}
context.Date = Monday0130;
assert.equal(engine.inRecurring({ days: [0], start: '23:00', end: '02:00' }), true);
assert.equal(engine.inRecurring({ days: [1], start: '23:00', end: '02:00' }), false);
context.Date = FakeDate;

// Background tabs cannot steal tracking, and blur flushes the last active slice.
const trackingState = structuredClone(state);
context.self.FocusLockStore.load = async () => trackingState;
context.self.FocusLockStore.save = async (saved) => Object.assign(trackingState, saved);
await send({ type: 'refresh' }, extensionSender);
fakeNow = 50_000;
const active = { id: 1, url: 'https://reddit.com/r/focus', active: true };
tabsById.set(1, active);
activeTabs = [active];
await handlers.activated({ tabId: 1 });
fakeNow += 5_000;
await handlers.updated(2, { status: 'loading', url: 'https://youtube.com/' },
  { id: 2, url: 'https://youtube.com/', active: false });
// Simulate Chrome suspending and recreating the worker while the same tab stays focused.
vm.runInContext("mem.state = null; mem.cur = { tabId: -1, url: '', since: 0 }; mem.focused = false", context);
await send({ type: 'refresh' }, extensionSender);
fakeNow += 5_000;
await handlers.focus(-1);
assert.equal(trackingState.stats['2026-09-27']['reddit.com'], 10);
assert.equal(trackingState.stats['2026-09-27']['youtube.com'], undefined);

// Previously granted snoozes must not bypass a subsequently frozen list.
const frozenWithPass = structuredClone(state);
frozenWithPass.lists[0].lockedUntil = fakeNow + 60000;
frozenWithPass.snoozes['reddit.com'] = fakeNow + 60000;
assert.equal(engine.verdictFor('https://reddit.com/', frozenWithPass, fakeNow).blocked, true);
frozenWithPass.lists[0].mode = 'whitelist';
frozenWithPass.lists[0].sites = ['work.example'];
assert.equal(engine.verdictFor('https://reddit.com/', frozenWithPass, fakeNow).blocked, true);

let savedState;
async function resetState(next) {
  savedState = structuredClone(next);
  context.self.FocusLockStore.load = async () => structuredClone(savedState);
  context.self.FocusLockStore.save = async value => { savedState = structuredClone(value); };
  vm.runInContext('syncInFlight = null; lastCloudRefreshAttemptAt = Date.now(); mem.lastAttempt.clear()', context);
  await send({ type: 'refresh' }, extensionSender);
}
await resetState(frozenWithPass);
assert.equal((await send({ type: 'snooze', url: 'https://reddit.com/' }, extensionSender)).ok, false,
  'Frozen allow-only rules also reject snooze');
assert.equal((await send({ type: 'verdict', url: 'https://reddit.com/' }, { ...extensionSender, tab: { id: 9 } })).blocked, true,
  'An extension page in a tab can inspect any website verdict');

// Permalock: the highest-priority verdict. No snooze, exception, schedule,
// daily limit, Strict commitment, Nuclear, or shared Nuke can lift it.
const permanentState = structuredClone(state);
permanentState.permanentSites = ['example.com'];
assert.equal(engine.verdictFor('https://example.com/', permanentState, 100).mode, 'permanent');
assert.equal(engine.verdictFor('https://deep.sub.example.com/x', permanentState, 100).mode, 'permanent',
  'Subdomains of a permanent domain are permanent too');
assert.equal(engine.verdictFor('https://notexample.com/', permanentState, 100).blocked, false,
  'A domain that merely ends with the permanent text is not blocked');
const permanentOverrides = structuredClone(permanentState);
permanentOverrides.strictMode = true;
permanentOverrides.strictEndsAt = 0;
permanentOverrides.strictHeldSites = ['example.com'];
permanentOverrides.cloudNuke = { isActive: true, startedAt: 0 };
permanentOverrides.cloudSites = [{ domain: 'example.com', isBlocked: false }];
permanentOverrides.nuclear = { active: true, until: 9_999_999_999_999, allow: [] };
permanentOverrides.snoozes = { 'example.com': 9_999_999_999_999 };
permanentOverrides.lists.push({ id: 'perm-exception', name: 'Exception list', mode: 'blacklist', enabled: true,
  alwaysOn: true, sites: ['example.com'], exceptions: ['example.com'], lockedUntil: 0, dailyLimitMin: 1 });
assert.equal(engine.verdictFor('https://example.com/', permanentOverrides, 100).reason, 'permanent',
  'Permanent wins over strict/held/nuke/nuclear/lists/snoozes/exceptions');

await resetState({ ...structuredClone(state), permanentSites: ['example.com'] });
assert.equal((await send({ type: 'snooze', url: 'https://sub.example.com/' }, extensionSender)).ok, false,
  'A permanent domain cannot be snoozed');
assert.equal((await send({ type: 'snooze', url: 'https://unrelated.example/' }, extensionSender)).ok, true,
  'Non-permanent domains still snooze normally');

// Active commitment rules remain on the device, but B's account data stays B's.
fakeNow = Date.now();
await resetState({ ...structuredClone(state), strictMode: true, strictEndsAt: fakeNow + 60000,
  cloudAccountId: 'A', cloudSites: [{ domain: 'held.example', isBlocked: true }] });
context.self.FocusLockCloud.syncUsage = async current => {
  current.cloudAccountId = 'B';
  current.cloudSites = [];
  current.stats = { '2026-09-27': { 'b.example': 20 } };
  return { ok: true, signedIn: true, accountChanged: true, sites: [], prefs: { strictMode: false }, nuke: null };
};
await vm.runInContext('syncCloud("test")', context);
assert.equal(savedState.strictMode, true);
assert.equal(savedState.cloudSites.length, 0, 'Held A rules do not enter B boundary list');
assert.equal(engine.verdictFor('https://held.example/', savedState, fakeNow).blocked, true);
assert.equal(engine.verdictFor('https://held.example/', savedState, fakeNow + 60001).blocked, false);

// Another account's inactive Nuke cannot release a held shared Nuke.
await resetState({ ...structuredClone(state), cloudAccountId: 'A', cloudNuke: { isActive: true, startedAt: fakeNow } });
await vm.runInContext('syncCloud("test")', context);
assert.equal(savedState.cloudNuke.isActive, true);
assert.equal(engine.verdictFor('https://docs.google.com/', savedState, fakeNow).mode, 'shared-nuke');
assert.equal((await send({ type: 'snooze', url: 'https://reddit.com/' }, extensionSender)).ok, false);
context.self.FocusLockCloud.syncUsage = async current => {
  current.cloudAccountId = 'A';
  return { ok: true, signedIn: true, nuke: { isActive: false }, sites: [] };
};
await vm.runInContext('syncCloud("test")', context);
assert.equal(savedState.cloudNuke.isActive, false, 'Only the originating account releases its shared Nuke');

// Duplicate navigation callbacks cannot trigger the five-attempt Nuke early.
await resetState({ ...structuredClone(state), strictMode: true, strictEndsAt: fakeNow + 60000 });
const attemptsBeforeNavigation = savedState.strictAttempts || 0;
const blockedBeforeNavigation = savedState.blockedTotal || 0;
await handlers.beforeNavigate({ tabId: 3, frameId: 0, url: 'https://reddit.com/' });
await handlers.beforeNavigate({ tabId: 3, frameId: 0, url: 'https://reddit.com/' });
assert.equal(savedState.strictAttempts, attemptsBeforeNavigation + 1);
assert.equal(savedState.blockedTotal, blockedBeforeNavigation + 1);

// A hung network check cannot delay a known cached block response.
context.self.FocusLockCloud.syncUsage = () => new Promise(() => {});
vm.runInContext('lastCloudRefreshAttemptAt = 0; mem.state.cloudSitesSyncedAt = 0', context);
const quickVerdict = await Promise.race([
  send({ type: 'verdict', url: 'https://reddit.com/' }, extensionSender),
  new Promise((_, reject) => setTimeout(() => reject(new Error('Cached verdict waited for the network')), 100)),
]);
assert.equal(quickVerdict.blocked, true);

// The real worker accounting feeds the shared policy, survives worker restart,
// and never spends twice for the checkpointed slice.
fakeNow = new Date(2026, 8, 27, 12).getTime();
context.self.FocusLockCloud.syncUsage = async () => ({ ok: false, signedIn: false });
const fundedState = { ...structuredClone(state), lists: [], cloudAccountId: 'A', cloudSitesLoaded: true,
  cloudSites: [{ domain: 'reddit.com', isBlocked: true }], cloudSitesSyncedAt: fakeNow,
  cloudPolicy: { state: { creditBalanceSeconds: 6, lastResetDate: '2026-09-27' }, groups: [], limits: [], schedules: [] },
  leisureStats: {}, cloudLeisureBaseline: {} };
await resetState(fundedState);
vm.runInContext('mem.cur = { tabId: -1, url: "", since: 0 }; mem.focused = true', context);
await handlers.activated({ tabId: 1 });
fakeNow += 4000;
const contentSender = { id: 'focuslock-test', tab: { id: 1 }, url: active.url };
assert.equal((await send({ type: 'verdict', url: active.url }, contentSender)).blocked, false);
assert.equal(savedState.leisureStats['2026-09-27']['reddit.com'], 4);
vm.runInContext('mem.state = null; mem.cur = { tabId: -1, url: "", since: 0 }; mem.focused = false', context);
fakeNow += 2000;
const exhausted = await send({ type: 'verdict', url: active.url }, contentSender);
assert.equal(exhausted.mode, 'earned-time');
assert.equal(savedState.leisureStats['2026-09-27']['reddit.com'], 6);
assert.equal((await send({ type: 'verdict', url: active.url }, contentSender)).mode, 'earned-time');
assert.equal(savedState.leisureStats['2026-09-27']['reddit.com'], 6, 'saved spending cannot replay on repeated verdicts');

// A server approval releases only the matching held commitment.
const commitmentEnd = fakeNow + 60_000;
await resetState({ ...fundedState, strictMode: true, strictEndsAt: commitmentEnd,
  cloudPrefs: { strictMode: true, strictEndsAt: commitmentEnd, strictSessionId: 'commit-A' } });
context.self.FocusLockCloud.syncUsage = async () => ({ ok: true, signedIn: true,
  prefs: { strictMode: false, strictEndsAt: 0, strictSessionId: 'commit-A', strictApprovedSessionId: 'commit-A',
    strictApprovedEndsAt: commitmentEnd, strictApprovedAt: fakeNow } });
await vm.runInContext('syncCloud("approval")', context);
assert.equal(savedState.strictMode, false);
assert.equal(engine.verdictFor(active.url, savedState, fakeNow).blocked, false);

// A live mobile commitment must apply while no HTTP tab is focused, without
// waiting for maintenance or making another REST policy request.
await resetState({ ...fundedState, cloudSitesSyncedAt: fakeNow - 61_000 });
vm.runInContext('mem.focused = false; mem.cur = { tabId: -1, url: "", since: 0 }; lastCloudRefreshAttemptAt = 0', context);
let policyReads = 0;
context.self.FocusLockCloud.syncUsage = async () => {
  policyReads++;
  return { ok: true, signedIn: true, prefs: { strictMode: true, strictEndsAt: fakeNow + 60_000 } };
};
context.self.FocusLockCloud.livePolicyStatus = () => ({ active: true, connected: true, received: true });
await handlers.alarm({ name: 'focuslock-maint' });
assert.equal(policyReads, 0, 'Maintenance does not poll for policy');
context.__live = { ok: true, signedIn: true, userId: 'A', isCurrent: async () => true,
  prefs: { strictMode: true, strictEndsAt: fakeNow + 60_000 } };
await vm.runInContext('syncCloud("live", __live)', context);
assert.equal(policyReads, 0, 'Live updates apply directly without REST');
assert.equal(savedState.strictMode, true);
assert.equal(engine.verdictFor(active.url, savedState, fakeNow).blocked, true,
  'Incoming mobile strict overrides available earned time');
assert.equal((await send({ type: 'snooze', url: active.url }, extensionSender)).ok, false);
let boundaryWrites = 0;
context.self.FocusLockCloud.setWebsiteBlocked = async () => { boundaryWrites++; return { ok: true }; };
context.self.FocusLockCloud.saveGroups = async () => { boundaryWrites++; return { ok: true }; };
assert.equal((await send({ type: 'setSharedSite', domain: 'reddit.com', isBlocked: false }, extensionSender)).ok, false);
assert.equal((await send({ type: 'focusGroupsSave', groups: [], updatedAt: fakeNow }, extensionSender)).ok, false);
assert.equal(boundaryWrites, 0, 'Strict Mode rejects shared edits before queuing cloud writes');
assert.equal(engine.verdictFor(active.url, savedState, fakeNow + 60_001).blocked, false,
  'The incoming commitment releases at its synced expiry');

// Switching accounts during an existing sync must queue a new preparation
// cycle, rather than accidentally reusing the old account's in-flight result.
await resetState(fundedState);
let releaseOldCycle;
const oldCycleGate = new Promise(resolve => { releaseOldCycle = resolve; });
const cycleReasons = [];
context.self.FocusLockCloud.syncUsage = async (current, reason) => {
  cycleReasons.push(reason);
  if (reason === 'old-account') { await oldCycleGate; return { ok: false }; }
  current.cloudAccountId = 'B';
  return { ok: true, signedIn: true, accountChanged: true,
    sites: [{ domain: 'b.example', isBlocked: true }], prefs: { strictMode: false } };
};
const oldCycle = vm.runInContext('syncCloud("old-account")', context);
await new Promise(resolve => setImmediate(resolve));
const accountCycle = vm.runInContext('syncCloud("account-change")', context);
releaseOldCycle();
await Promise.all([oldCycle, accountCycle]);
assert.deepEqual(cycleReasons, ['old-account', 'account-change']);
assert.equal(savedState.cloudAccountId, 'B');
context.__live = { ok: true, signedIn: true, userId: 'A', isCurrent: async () => false,
  sites: [{ domain: 'stale.example', isBlocked: true }], prefs: { strictMode: true } };
await vm.runInContext('syncCloud("live", __live)', context);
assert.equal(savedState.cloudSites[0].domain, 'b.example', 'Old account push cannot replace new account rules');

console.log('extension enforcement regression tests passed');
