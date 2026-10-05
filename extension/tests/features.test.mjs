import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

let now = new Date(2026, 9, 3, 12).getTime();
const storage = {}, sessionStorage = {}, uploads = new Map();
let accountId = 'A', networkFailure = false, saves = 0;
const sources = {};
for (const file of ['src/matcher.js', 'src/features.js', 'src/store.js', 'src/policy.js', 'background/service-worker.js']) sources[file] = await readFile(new URL('../' + file, import.meta.url), 'utf8');
async function worker() {
  const handlers = {};
  const event = key => ({ addListener: listener => { handlers[key] = listener; } });
  class Clock extends Date { constructor(...args) { super(...(args.length ? args : [now])); } static now() { return now; } }
  const chrome = {
    runtime: { id: 'fixture', getURL: path => `chrome-extension://fixture/${path}`, onMessage: event('message'), onInstalled: event('installed'), onStartup: event('startup') },
    tabs: { query: async () => [], get: async () => ({}), update: async () => {}, onActivated: event('activated'), onUpdated: event('updated'), onRemoved: event('removed') },
    windows: { WINDOW_ID_NONE: -1, getLastFocused: async () => ({ focused: true }), onFocusChanged: event('focus') },
    webNavigation: { onBeforeNavigate: event('navigation'), onHistoryStateUpdated: event('history'), onReferenceFragmentUpdated: event('fragment') },
    alarms: { create: async () => {}, onAlarm: event('alarm') }, idle: { queryState: (_timeout, callback) => callback('active') },
    storage: { local: { get: async key => ({ [key]: structuredClone(storage[key]) }), set: async values => Object.assign(storage, structuredClone(values)) },
      session: { get: async key => ({ [key]: structuredClone(sessionStorage[key]) }), set: async values => Object.assign(sessionStorage, structuredClone(values)) } },
    action: { setBadgeText: async () => {}, setBadgeBackgroundColor: async () => {} }, notifications: { create() {} },
  };
  const context = { chrome, self: {}, URL, console, Date: Clock, structuredClone, importScripts() {} };
  vm.createContext(context);
  for (const file of ['src/matcher.js', 'src/features.js', 'src/store.js', 'src/policy.js']) vm.runInContext(sources[file], context);
  context.self.FocusLockCloud = {
    syncUsage: async () => ({ ok: false }), status: async () => ({ signedIn: Boolean(accountId), accountId }),
    savePrefs: async (_prefs, expected) => { if (networkFailure) throw new Error('offline'); assert.equal(expected, accountId); saves++; return { ok: true }; },
    logFocusSession: async (record, expected) => { if (networkFailure) throw new Error('offline'); assert.equal(expected, accountId); uploads.set(record.sessionId, structuredClone(record)); return { ok: true }; },
    isAuthUrl: url => url.startsWith('https://account.test'),
  };
  vm.runInContext(sources['background/service-worker.js'], context);
  return { context, engine: context.self.FocusLockEngine, helper: context.self.FocusLockFeatures,
    send: message => new Promise(resolve => handlers.message(message, { id: 'fixture', url: 'chrome-extension://fixture/options/options.html' }, resolve)),
    alarm: name => handlers.alarm({ name }), store: context.self.FocusLockStore };
}
let w = await worker();
const F = w.helper;
const today = w.store.todayKey();
const frog = F.frogState({ enabled: true, cycleDate: today, frog: { id: 'x', title: 'Study' }, trackedSeconds: 60, requiredSeconds: 60, tickedOff: false });
assert.equal(frog.locked, true, 'Time alone cannot release Frog');
assert.equal(F.frogState({ ...frog, tickedOff: true }).locked, false, 'Time plus completion releases Frog');
assert.equal(F.frogState({ ...frog, tickedOff: true, trackedSeconds: 59 }).locked, true);
assert.equal(F.frogState({ ...frog, cycleDate: '2026-10-04', tickedOff: true }).phase, 'complete', 'Clock rollback preserves completed newer cycle');
const morning = new Date(2026, 9, 4, 4).getTime();
assert.equal(F.frogState({ ...frog, tickedOff: true }, morning).cycleDate, today, 'Before wake stays in previous cycle');
assert.equal(F.frogState({ ...frog, tickedOff: true }, new Date(2026, 9, 4, 6).getTime()).frog, null, 'Next wake clears task');
const friday = { enabled: true, days: [5], startMinute: 23 * 60, endMinute: 60 };
const overnight = F.weeklyWindow(friday, new Date(2026, 9, 3, 0, 30).getTime());
assert.equal(overnight.endsAt, new Date(2026, 9, 3, 1).getTime());
assert.equal(F.weeklyWindow(friday, new Date(2026, 9, 3, 23, 30).getTime()), null, 'Overnight belongs to Friday, not Saturday');

networkFailure = true;
let result = await w.send({ type: 'strictCommit', endsAt: now + 3600000, preset: 'hours' });
assert.equal(result.ok, true); assert.equal(result.synced, false);
assert.equal((await w.store.load()).strictPending.accountId, 'A');
assert.equal((await w.send({ type: 'strictCommit', endsAt: now + 1800000 })).ok, false, 'Cannot shorten commitment');
assert.equal((await w.send({ type: 'strictCommit', endsAt: now + 31 * 86400000 })).ok, false, '30-day ceiling');
result = await w.send({ type: 'strictCommit', endsAt: now + 7200000, preset: 'hours' });
assert.equal(result.ok, true, 'Can extend offline');
const state = await w.store.load();
state.lists[0].alwaysOn = false; state.schedules = [];
assert.equal(w.engine.listIsActive(state.lists[0], state, now).active, false,
  'Strict Mode does not activate a local list outside its configured schedule');
accountId = 'B'; networkFailure = false;
result = await w.send({ type: 'strictCommit', endsAt: now + 10800000, preset: 'hours' });
assert.equal(result.ok, true);
assert.equal((await w.store.load()).strictPending.accountId, 'A', 'Extending from another account retains the original owner');
await w.send({ type: 'featureRetry' });
assert.equal(saves, 0, 'Cannot upload A commitment into B');
accountId = 'A'; await w.send({ type: 'featureRetry' });
assert.equal(saves, 1); assert.equal((await w.store.load()).strictPending, null);
accountId = ''; w = await worker();
assert.equal((await w.send({ type: 'featureStatus' })).strictMode, true, 'Commitment survives signout and worker restart');
now += 10800001;
assert.equal((await w.send({ type: 'featureStatus' })).strictMode, false, 'Commitment expires');

await w.send({ type: 'frogConfigure', enabled: true, requiredMinutes: 1, wakeHour: 0 });
await w.send({ type: 'frogSelect', title: 'Study chemistry' });
await w.store.update(state => { state.strictMode = true; state.strictEndsAt = now + 60_000; return state; });
assert.equal(w.engine.verdictFor('https://reddit.com/', await w.store.load(), now).mode, 'frog');
assert.equal((await w.send({ type: 'snooze', url: 'https://reddit.com/' })).ok, false,
  'Frog remains enforced and cannot be snoozed during Strict Mode');
await w.send({ type: 'frogTick', tickedOff: true });
assert.equal((await w.send({ type: 'frogStatus' })).locked, true);
await w.send({ type: 'focusTimerStart', minutes: 1 });
assert.equal((await w.send({ type: 'focusTimerStart', minutes: 1 })).ok, false, 'Only one running session');
w = await worker(); now += 61000;
await w.alarm('focuslock-focus');
result = await w.send({ type: 'frogStatus' });
assert.equal(result.locked, false); assert.equal(result.trackedSeconds, 60, 'Alarm credits task after worker restart');
assert.equal(uploads.size, 0, 'Anonymous focus does not create shared credit');
await w.alarm('focuslock-focus');
assert.equal((await w.send({ type: 'frogStatus' })).trackedSeconds, 60, 'Repeated alarm cannot double count');

accountId = 'A'; networkFailure = true;
await w.send({ type: 'focusTimerStart', minutes: 5, ratio: 4 });
now += 301000; await w.send({ type: 'focusTimerFinish' });
assert.equal((await w.store.load()).pendingFocusSessions.length, 1, 'Failed upload remains durable');
w = await worker(); accountId = 'B'; networkFailure = false;
await w.send({ type: 'featureRetry' }); assert.equal(uploads.size, 0, 'Pending focus stays in originating account');
accountId = 'A'; await w.send({ type: 'featureRetry' });
assert.equal(uploads.size, 1); assert.equal((await w.store.load()).pendingFocusSessions.length, 0);
await w.send({ type: 'featureRetry' }); assert.equal(uploads.size, 1, 'Stable session ID uploads once');
const local = new Date(now), currentMinute = local.getHours() * 60 + local.getMinutes();
await w.send({ type: 'strictWeeklySave', rule: { enabled: true, days: [local.getDay()], startMinute: currentMinute - 1, endMinute: currentMinute + 30 } });
assert.equal((await w.send({ type: 'featureStatus' })).strictMode, true, 'Saving inside a weekly window starts a commitment');
assert.equal((await w.send({ type: 'strictWeeklySave', rule: { enabled: false } })).ok, true,
  'Future weekly activation can be edited without shortening the active commitment');
assert.equal((await w.send({ type: 'featureStatus' })).strictMode, true,
  'Changing future weekly activation does not release the active commitment');
await w.store.update(state => { state.strictMode = false; state.strictEndsAt = now - 1; return state; });
await w.send({ type: 'refresh' });
assert.equal((await w.send({ type: 'featureStatus' })).strictMode, false, 'Approval release must not re-arm the same weekly occurrence');
console.log('Strict commitments, Frog cycles, durable timers and account isolation passed');
