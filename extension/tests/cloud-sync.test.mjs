import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';
import { createLivePolicyController } from '../src/live-policy.js';

const source = (await fs.readFile(new URL('../src/cloud-sync.js', import.meta.url), 'utf8'))
  .replace(/^import .*?;\r?\n/gm, '')
  .replaceAll('process.env.CLERK_PUBLISHABLE_KEY', '"pk_test"')
  .replaceAll('process.env.CLERK_SYNC_HOST', '"https://clerk.test"')
  .replaceAll('process.env.CLERK_SIGN_IN_URL', '"https://accounts.focuslock.test/sign-in"')
  .replaceAll('process.env.CONVEX_URL', '"https://convex.test"');

const store = new Map();
const clerk = {
  user: { id: 'A', primaryEmailAddress: { emailAddress: 'a@test' } },
  session: { async getToken() { return 'token-A'; } },
  async signOut() { this.user = null; this.session = null; },
  addListener(listener) { clerkListeners.push(listener); return () => {}; },
};
const calls = [];
const clerkListeners = [];
const liveClients = [];
class FakeConvexClient {
  constructor(url, options) { this.url = url; this.options = options; this.closed = false; this.authFetcher = null; this.update = null; liveClients.push(this); }
  setAuth(fetcher) { this.authFetcher = fetcher; }
  onUpdate(_query, args, callback, onError) { this.args = args; this.update = callback; this.onError = onError; return () => { this.unsubscribed = true; }; }
  subscribeToConnectionState(callback) { this.connectionCallback = callback; return () => { this.connectionUnsubscribed = true; }; }
  close() { this.closed = true; }
}
let failUsageOnce = false;
let expireUsageOnce = false;
let changeIdentityDuringToken = false;
let changeIdentityDuringDashboard = false;
let rejectMutationPath = '';
let failConfigOnce = false;
let hangConvexRequest = false;
let nukeState = null;
let rulesConfig = null;
let loseWorkResponseOnce = false;
const serverWorkRecords = new Map();
const serverPermanentSites = new Map();
let pulseConfig = {
  sitesUpdatedAt: 0, prefsUpdatedAt: 0, nukeUpdatedAt: 0,
  policyVersion: JSON.stringify([0, 0, 0, 0]),
  sites: [], prefs: null, nuke: null, permanentBlocks: [],
  policy: { state: null, groups: [], limits: [], schedules: [] },
  usageSummary: { totalTrackedSeconds: 0, totalBlockedSeconds: 0 },
};
const context = {
  URL,
  self: { FocusLockStore: {
    todayKey: () => '2026-09-19',
    async save(state) { store.set('state', structuredClone(state)); },
    normalizePermanentSites(value) {
      const out = [];
      for (const item of Array.isArray(value) ? value : []) {
        if (typeof item !== 'string') continue;
        const domain = item.trim().toLowerCase().replace(/^www\./, '').replace(/\.+$/, '');
        if (domain.includes('.') && !/[\s/\\:@?#]/.test(domain) && !out.includes(domain)) out.push(domain);
      }
      return out;
    },
    async load() { return structuredClone(store.get('localState') || {}); },
  } },
  createClerkClient: () => clerk,
  ConvexClient: FakeConvexClient,
  makeFunctionReference: (name) => name,
  createLivePolicyController,
  chrome: {
    runtime: { getManifest: () => ({ version: '1.0.0' }) },
    storage: { local: {
      async get(key) { return { [key]: store.get(key) }; },
      async set(values) { for (const [key, value] of Object.entries(values)) store.set(key, structuredClone(value)); },
    } },
  },
  crypto: { randomUUID: (() => { let n = 0; return () => `uuid-${++n}`; })() },
  AbortController,
  setTimeout: (callback, delay) => setTimeout(callback, Math.min(delay, 20)),
  clearTimeout,
  fetch: async (_url, init) => {
    const body = JSON.parse(init.body);
    calls.push(body);
    if (body.path === 'focus:getConfiguration' && failConfigOnce) {
      failConfigOnce = false;
      throw new Error('configuration temporarily unavailable');
    }
    if (body.path === 'focus:getConfiguration' && rulesConfig) return { ok: true, async json() { return { status: 'success', value: rulesConfig }; } };
    if (hangConvexRequest) return new Promise((_, reject) => init.signal.addEventListener('abort', () => {
      const error = new Error('aborted');
      error.name = 'AbortError';
      reject(error);
    }, { once: true }));
    if (body.path === 'focus:getDashboard' && changeIdentityDuringDashboard) clerk.user = { id: 'B' };
    if (body.path === 'usage:recordUsageBatch' && failUsageOnce) {
      failUsageOnce = false;
      throw new Error('offline');
    }
    if (body.path === 'usage:recordUsageBatch' && expireUsageOnce) {
      expireUsageOnce = false;
      return { ok: true, async json() { return { status: 'success', value: {
        written: 0, expired: body.args.buckets.length, oldestAcceptedDate: '2026-09-20',
      } }; } };
    }
    if (body.path === 'usage:recordUsageBatch') return { ok: true, async json() { return { status: 'success', value: { written: body.args.buckets.length } }; } };
    if (body.path === 'nuke:getNuke') return { ok: true, async json() { return { status: 'success', value: nukeState }; } };
    if (body.path === 'focus:getSyncPulse') return { ok: true, async json() {
      const permanentBlocks = [...(serverPermanentSites.get(clerk.user?.id) || new Map())].map(([targetKey, targetLabel]) => ({
        targetKind: 'website', targetKey, targetLabel,
      }));
      const nukeUpdatedAt = Number(nukeState?.updatedAt) || pulseConfig.nukeUpdatedAt;
      const currentNuke = nukeState ?? pulseConfig.nuke;
      const response = {
        sitesUpdatedAt: pulseConfig.sitesUpdatedAt,
        prefsUpdatedAt: pulseConfig.prefsUpdatedAt,
        nukeUpdatedAt,
        permanentBlocks,
        ...(body.args.sitesUpdatedAt !== pulseConfig.sitesUpdatedAt ? { sites: pulseConfig.sites } : {}),
        ...(body.args.prefsUpdatedAt !== pulseConfig.prefsUpdatedAt ? { prefs: pulseConfig.prefs } : {}),
        ...(body.args.nukeUpdatedAt !== nukeUpdatedAt ? { nuke: currentNuke } : {}),
        ...(body.args.knownPolicyVersion === undefined ? {} : {
          policyVersion: pulseConfig.policyVersion,
          ...(body.args.knownPolicyVersion !== pulseConfig.policyVersion ? { policy: pulseConfig.policy } : {}),
        }),
        ...(body.args.usageDate ? { usageSummary: pulseConfig.usageSummary } : {}),
      };
      return { status: 'success', value: response };
    } };
    if (body.path === 'focus:addPermanentBlocks') {
      const sites = serverPermanentSites.get(clerk.user.id) || new Map();
      for (const target of body.args.targets) sites.set(target.targetKey, target.targetLabel || target.targetKey);
      serverPermanentSites.set(clerk.user.id, sites);
      return { ok: true, async json() { return { status: 'success', value: { applied: true, added: body.args.targets.length } }; } };
    }
    if (body.path === 'focus:recordWork') {
      const args = body.args;
      if (!serverWorkRecords.has(args.recordId)) serverWorkRecords.set(args.recordId, {
        creditSeconds: args.earnedMinutesCredited * 60,
        tasksCompleted: args.tasksCompleted,
      });
      if (loseWorkResponseOnce) { loseWorkResponseOnce = false; throw new Error('work response lost'); }
      return { ok: true, async json() { return { status: 'success', value: { applied: true } }; } };
    }
    return { ok: true, async json() { return { status: 'success', value: { applied: body.path !== rejectMutationPath } }; } };
  },
};
clerk.session.getToken = async function getToken() {
  if (changeIdentityDuringToken) clerk.user = { id: 'B' };
  return 'token-A';
};
vm.runInNewContext(source, context, { filename: 'cloud-sync.js' });
const cloud = context.self.FocusLockCloud;

// Auth recovery exemptions use exact configured HTTPS origins only.
assert.equal(cloud.isAuthUrl('https://clerk.test/sign-in'), true);
assert.equal(cloud.isAuthUrl('https://accounts.focuslock.test/sign-in'), true);
assert.equal(cloud.isAuthUrl('https://clerk.test.evil.invalid/sign-in'), false);
assert.equal(cloud.isAuthUrl('https://accounts.focuslock.test.evil.invalid/sign-in'), false);
assert.equal(cloud.isAuthUrl('https://evil.invalid/?next=https://clerk.test'), false);
assert.equal(cloud.isAuthUrl('http://clerk.test/sign-in'), false);

// Primitive roots are repaired without breaking account initialization.
store.set('focuslock.cloud.v2', ['corrupt-root']);
const repairedRootState = stateFor(0);
await cloud.syncUsage(repairedRootState, 'repair-root');
assert.equal(Array.isArray(store.get('focuslock.cloud.v2')), false);
assert.equal(typeof store.get('focuslock.cloud.v2').accounts.A.deviceId, 'string');

// Pre-sign-in device-local Permalock entries migrate to the first account only.
clerk.user = { id: 'legacy-first-account' };
const legacyPermanentState = stateFor(0);
legacyPermanentState.permanentSites = ['pre-signin.example'];
await cloud.syncUsage(legacyPermanentState, 'first-sign-in');
assert.deepEqual([...serverPermanentSites.get('legacy-first-account').keys()], ['pre-signin.example']);
clerk.user = { id: 'A' };

// Upload batches stay under the backend limit, and each exact acknowledged
// batch advances the account's uploaded ledger.
clerk.user = { id: 'large-upload-account' };
const largeUploadState = stateFor(0);
largeUploadState.permanentSites = Array.from({ length: 1001 }, (_, index) => `site-${index}.large.test`);
calls.length = 0;
await cloud.syncUsage(largeUploadState, 'large-permanent-upload');
const permanentCalls = calls.filter((call) => call.path === 'focus:addPermanentBlocks');
assert.deepEqual(permanentCalls.map((call) => call.args.targets.length), [500, 500, 1]);
assert.equal(permanentCalls.every((call) => call.args.targets.length <= 500), true);
assert.equal(store.get('focuslock.cloud.v2').accounts['large-upload-account'].uploadedPermanentSites.length, 1001);

// Remote targets are attributed as soon as restored, so an account switch
// before a second sync cannot re-upload those device-local entries elsewhere.
clerk.user = { id: 'large-restore-account' };
const largeRemoteSites = new Map(Array.from({ length: 1001 }, (_, index) => [
  `remote-${index}.large.test`, `remote-${index}.large.test`,
]));
serverPermanentSites.set('large-restore-account', largeRemoteSites);
const largeRestoreState = stateFor(0);
calls.length = 0;
const largeRestoreResult = await cloud.syncUsage(largeRestoreState, 'large-permanent-restore');
assert.equal(largeRestoreResult.permanentBlocks.length, 1001);
largeRestoreState.permanentSites = largeRestoreResult.permanentBlocks.map((target) => target.targetKey);
const ownersAfterRestore = store.get('focuslock.cloud.permanent-site-owners.v1');
assert.equal(Object.values(ownersAfterRestore).filter((owner) => owner === 'large-restore-account').length, 1001);
clerk.user = { id: 'other-large-account' };
calls.length = 0;
await cloud.syncUsage(largeRestoreState, 'switch-after-restore');
assert.equal(calls.some((call) => call.path === 'focus:addPermanentBlocks'
  && call.args.targets.some((target) => target.targetKey.startsWith('remote-'))), false);
assert.equal(serverPermanentSites.has('other-large-account'), false);
clerk.user = { id: 'A' };

function stateFor(domainCount, prefix = 'd') {
  const stats = { '2026-09-19': {} };
  for (let i = 0; i < domainCount; i += 1) stats['2026-09-19'][`${prefix}${i}.test`] = i + 1;
  return { stats, blockedLog: [], blockedTotal: 0, cloudSites: [], cloudSitesLoaded: false, permanentSites: [] };
}

async function applySyncResult(state, result) {
  if (result.permanentBlocks !== undefined) {
    state.permanentSites = context.self.FocusLockStore.normalizePermanentSites([
      ...(state.permanentSites || []), ...result.permanentBlocks.map((target) => target.targetKey),
    ]);
  }
  if (result.sites !== undefined) {
    state.cloudSites = structuredClone(result.sites || []);
    state.cloudSitesLoaded = true;
  }
  if (result.prefs !== undefined) state.cloudPrefs = structuredClone(result.prefs || null);
  if (result.policy !== undefined) state.cloudPolicy = structuredClone(result.policy || null);
  if (result.versions) {
    state.cloudSitesVersion = result.versions.sites;
    state.cloudPrefsVersion = result.versions.prefs;
    state.cloudNukeVersion = result.versions.nuke;
    state.cloudPolicyVersion = result.versions.policy;
  }
  if (result.usage !== undefined) {
    state.cloudUsage = structuredClone(result.usage);
    state.cloudUsageBaseline = structuredClone(result.usageBaseline || {});
  }
  if (result.leisureBaseline !== undefined) state.cloudLeisureBaseline = structuredClone(result.leisureBaseline);
  await context.self.FocusLockStore.save(state);
  return state;
}

// A failed request leaves the durable queue, and a later worker instance can retry it.
const retryState = stateFor(3);
const retryMeta = store.get('focuslock.cloud.v2');
retryMeta.accounts.A.lastHeartbeatAt = 0;
store.set('focuslock.cloud.v2', retryMeta);
calls.length = 0;
failUsageOnce = true;
await assert.rejects(() => cloud.syncUsage(retryState, 'test'), /offline/);
retryState.leisureStats = { '2026-09-19': { 'd0.test': 1, 'd1.test': 3 } };
const queuedAfterFailure = store.get('focuslock.cloud.v2').accounts.A.pendingBuckets;
assert.equal(queuedAfterFailure.length, 3);
const retryResult = await cloud.syncUsage(retryState, 'retry');
await applySyncResult(retryState, retryResult);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingBuckets.length, 0);
const acceptedUsage = calls.find((call) => call.path === 'usage:recordUsageBatch');
assert.equal(typeof acceptedUsage.args.heartbeat.lastSeen, 'number');
assert.equal('deviceId' in acceptedUsage.args.heartbeat, false);
assert.equal(calls.some((call) => call.path === 'usage:recordUsageBatch'
  && call.args.buckets.some((bucket) => bucket.targetKey === 'd0.test' && bucket.leisureSeconds === 1)), true);
assert.ok(store.get('focuslock.cloud.v2').accounts.A.lastUsageSyncAt > 0);
assert.ok(store.get('focuslock.cloud.v2').accounts.A.lastHeartbeatAt > 0);
assert.equal(calls.some((call) => call.path === 'focus:getSnapshot'), false);
const firstPulse = calls.find((call) => call.path === 'focus:getSyncPulse');
assert.equal(firstPulse.args.sitesUpdatedAt, -1);
assert.equal(firstPulse.args.prefsUpdatedAt, -1);
assert.equal(firstPulse.args.nukeUpdatedAt, -1);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.nukeUpdatedAt, 0);
assert.equal(retryResult.leisureBaseline['2026-09-19']['d0.test'], 1);
assert.equal(retryState.cloudLeisureBaseline['2026-09-19']['d1.test'], 2);
assert.equal(store.get('state').cloudPolicyVersion, JSON.stringify([0, 0, 0, 0]));

// Shared Nuke is fetched on a manual sync and returned separately from local rules.
nukeState = { isActive: true, startedAt: 10, meditationCompletedAt: null, updatedAt: 10 };
const nukeSync = await cloud.syncUsage(retryState, 'manual');
assert.deepEqual(JSON.parse(JSON.stringify(nukeSync.nuke)), nukeState);
assert.equal(calls.some((call) => call.path === 'nuke:getNuke'), false);
nukeState = null;

// Expired absolute counters are acknowledged locally without deleting local history.
expireUsageOnce = true;
retryState.stats['2026-09-19']['d1.test'] += 5;
await cloud.syncUsage(retryState, 'manual');
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingBuckets.length, 0);
assert.match(store.get('focuslock.cloud.v2').accounts.A.lastWarning, /2026-09-20/);
assert.equal(retryState.stats['2026-09-19']['d1.test'] > 0, true);
assert.match((await cloud.status()).lastWarning, /2026-09-20/);

// Invalid stored queue entries are discarded while valid pending work survives.
const rootWithCorruptQueues = store.get('focuslock.cloud.v2');
rootWithCorruptQueues.accounts.A.pendingBuckets = [
  { date: '2026-09-19', targetKind: 'website', targetKey: 'queued.test', trackedSeconds: 42, updatedAt: 7 },
  null,
  { date: '2026-99-99', targetKind: 'website', targetKey: 'bad-date.test', trackedSeconds: 1 },
];
rootWithCorruptQueues.accounts.A.uploadedBuckets = { invalid: true };
rootWithCorruptQueues.accounts.A.pendingMutations = [
  { id: 'work:queued', kind: 'work', path: 'focus:addWorkRecord', args: { recordId: 'queued', durationMinutes: 15 }, revision: 'valid-revision' },
  17,
  { id: 'bad', path: 'focus:unknownMutation', args: {} },
];
store.set('focuslock.cloud.v2', rootWithCorruptQueues);
calls.length = 0;
const pendingState = stateFor(0);
pendingState.cloudAccountId = 'A';
await cloud.syncUsage(pendingState, 'repair-outbox');
assert.equal(calls.some((call) => call.path === 'usage:recordUsageBatch'
  && call.args.buckets.some((bucket) => bucket.targetKey === 'queued.test')), true);
assert.equal(calls.some((call) => call.path === 'focus:addWorkRecord'
  && call.args.recordId === 'queued'), true);
assert.deepEqual(store.get('focuslock.cloud.v2').accounts.A.pendingMutations, []);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingBuckets.length, 0);

// Chrome manual work and focus sessions share the atomic recordWork path;
// replaying a lost response keeps the same ID and never adds credit twice.
calls.length = 0;
loseWorkResponseOnce = true;
const chromeWork = { recordId: 'chrome-work-1', title: 'Study', durationMinutes: 25,
  timestamp: Date.now(), earnedMinutesCredited: 20, projectName: 'Biology' };
await assert.rejects(() => cloud.addWorkRecord(chromeWork), /work response lost/);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations
  .some((item) => item.id === 'work:chrome-work-1'), true);
await cloud.addWorkRecord(chromeWork);
const workCalls = calls.filter((call) => call.path === 'focus:recordWork' && call.args.recordId === 'chrome-work-1');
assert.equal(workCalls.length, 2);
assert.equal(workCalls[0].args.tasksCompleted, 1);
assert.equal(workCalls[0].args.projectName, 'Biology');
assert.equal(serverWorkRecords.get('chrome-work-1').creditSeconds, 1200);
assert.equal(serverWorkRecords.get('chrome-work-1').tasksCompleted, 1);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations
  .some((item) => item.id === 'work:chrome-work-1'), false);
await cloud.logFocusSession({ sessionId: 'chrome-focus-1', title: 'Focus', durationMinutes: 30,
  timestamp: Date.now(), earnedMinutesCredited: 15 });
const sessionCall = calls.find((call) => call.path === 'focus:recordWork' && call.args.recordId === 'chrome-focus-1');
assert.equal(sessionCall.args.tasksCompleted, 0);
assert.equal(serverWorkRecords.get('chrome-focus-1').creditSeconds, 900);

// Corrupt account-snapshot roots and malformed per-account state are sanitized.
store.set('focuslock.cloud.accounts.v1', ['invalid-snapshots']);
const snapshotState = stateFor(1, 'private-');
snapshotState.cloudAccountId = 'previous';
clerk.user = { id: 'B' };
await cloud.syncUsage(snapshotState, 'repair-snapshots');
assert.deepEqual(JSON.parse(JSON.stringify(snapshotState.stats)), {});
assert.equal(Array.isArray(store.get('focuslock.cloud.accounts.v1')), false);
store.set('focuslock.cloud.accounts.v1', {
  B: { stats: ['bad'], blockedLog: 'bad', blockedTotal: '9', cloudSites: [null, { domain: 'Shared.Test', isBlocked: true }, { domain: 2 }], cloudSitesLoaded: 'true' },
  C: { stats: { '2026-09-19': { 'kept.test': 7 } }, blockedLog: [], blockedTotal: 2, cloudSites: [], cloudSitesLoaded: true },
});
snapshotState.cloudAccountId = 'previous';
await cloud.syncUsage(snapshotState, 'repair-snapshot-entry');
assert.deepEqual(JSON.parse(JSON.stringify(snapshotState.stats)), {});
assert.deepEqual(JSON.parse(JSON.stringify(snapshotState.cloudSites)), [{ domain: 'shared.test', isBlocked: true }]);
assert.equal(snapshotState.cloudSitesLoaded, false);
assert.equal(store.get('focuslock.cloud.accounts.v1').C.stats['2026-09-19']['kept.test'], 7);
clerk.user = { id: 'A' };

// Navigation performs only a conditional rule check, even with new usage.
calls.length = 0;
retryState.cloudSitesLoaded = true;
retryState.stats['2026-09-19']['d0.test'] += 30;
const navigation = await cloud.syncUsage(retryState, 'navigation');
assert.equal(navigation.sites, undefined);
assert.deepEqual(calls.map((call) => call.path), ['focus:getSyncPulse']);
assert.equal(calls[0].args.sitesUpdatedAt, 0);
assert.equal(calls[0].args.prefsUpdatedAt, 0);
await cloud.syncUsage(retryState, 'alarm');
assert.equal(calls.filter((call) => call.path === 'usage:recordUsageBatch').length, 0);
const cadenceMeta = store.get('focuslock.cloud.v2');
cadenceMeta.accounts.A.lastUsageSyncAt = Date.now() - 4 * 60 * 60 * 1000 - 1;
store.set('focuslock.cloud.v2', cadenceMeta);
calls.length = 0;
await cloud.syncUsage(retryState, 'alarm');
assert.equal(calls.filter((call) => call.path === 'usage:recordUsageBatch').length, 1);

// The active-window reason uploads at most once per minute.
const activeState = stateFor(1, 'active-');
activeState.cloudAccountId = 'A';
activeState.cloudPolicy = structuredClone(retryState.cloudPolicy);
activeState.cloudPolicyVersion = retryState.cloudPolicyVersion;
activeState.cloudSitesLoaded = true;
activeState.cloudSitesVersion = retryState.cloudSitesVersion;
activeState.cloudPrefsVersion = retryState.cloudPrefsVersion;
activeState.cloudNukeVersion = retryState.cloudNukeVersion;
const activeMeta = store.get('focuslock.cloud.v2');
activeMeta.accounts.A.lastUsageSyncAt = Date.now() - 59_000;
store.set('focuslock.cloud.v2', activeMeta);
calls.length = 0;
await cloud.syncUsage(activeState, 'active');
assert.equal(calls.filter((call) => call.path === 'usage:recordUsageBatch').length, 0);
const dueMeta = store.get('focuslock.cloud.v2');
dueMeta.accounts.A.lastUsageSyncAt = Date.now() - 60_001;
store.set('focuslock.cloud.v2', dueMeta);
calls.length = 0;
await cloud.syncUsage(activeState, 'active');
assert.equal(calls.filter((call) => call.path === 'usage:recordUsageBatch').length, 1);

// Policy, sites, preferences and Nuke versions are sent from the persisted state.
const previousPulseConfig = pulseConfig;
pulseConfig = {
  sitesUpdatedAt: 41, prefsUpdatedAt: 42, nukeUpdatedAt: 43,
  policyVersion: 'policy-v2',
  sites: [{ domain: 'example.test', isBlocked: true, category: 'Social' }],
  prefs: { strictMode: true, strictSessionId: 'strict-2', globalDailyCapMinutes: 90 },
  nuke: { isActive: true, startedAt: 43 },
  policy: { state: { creditBalanceSeconds: 75, updatedAt: 1 }, groups: [{ groupId: 'g2' }], limits: [], schedules: [] },
  usageSummary: { totalTrackedSeconds: 5, totalBlockedSeconds: 1 },
};
const policyState = stateFor(0);
policyState.cloudAccountId = 'A';
policyState.cloudSitesLoaded = true;
policyState.cloudSitesVersion = 10;
policyState.cloudPrefsVersion = 20;
policyState.cloudNukeVersion = 30;
policyState.cloudPolicy = { state: null, groups: [], limits: [], schedules: [] };
policyState.cloudPolicyVersion = 'policy-v1';
calls.length = 0;
const policyPull = await cloud.syncUsage(policyState, 'navigation');
const policyCall = calls.find((call) => call.path === 'focus:getSyncPulse');
assert.deepEqual(policyCall.args, {
  sitesUpdatedAt: 10, prefsUpdatedAt: 20, nukeUpdatedAt: 30,
  knownPolicyVersion: 'policy-v1',
});
assert.deepEqual(JSON.parse(JSON.stringify(policyPull.policy)), pulseConfig.policy);
assert.deepEqual(JSON.parse(JSON.stringify(policyPull.versions)), { sites: 41, prefs: 42, nuke: 43, policy: 'policy-v2' });
await applySyncResult(policyState, policyPull);
assert.equal(store.get('state').cloudPolicyVersion, 'policy-v2');
assert.equal(store.get('state').cloudSitesVersion, 41);
calls.length = 0;
const conditionalPull = await cloud.syncUsage(policyState, 'navigation');
const conditionalArgs = calls.find((call) => call.path === 'focus:getSyncPulse').args;
assert.deepEqual(conditionalArgs, {
  sitesUpdatedAt: 41, prefsUpdatedAt: 42, nukeUpdatedAt: 43,
  knownPolicyVersion: 'policy-v2',
});
assert.equal(conditionalPull.policy, undefined);
pulseConfig = previousPulseConfig;

// More than Convex's 500-row mutation limit is split into multiple idempotent calls.
calls.length = 0;
await cloud.syncUsage(stateFor(501, 'large-'), 'large');
assert.deepEqual(calls.filter((call) => call.path === 'usage:recordUsageBatch').map((call) => call.args.buckets.length), [500, 1]);

// Account snapshots are isolated and reversible.
const accountState = stateFor(1, 'account-a-');
accountState.stats['2026-09-19']['account-a-0.test'] = 17;
accountState.permanentSites = ['a-only-perma.test'];
accountState.cloudAccountId = 'A';
accountState.cloudSites = [{ domain: 'a-only.test', isBlocked: true, category: 'Social' }];
accountState.cloudSitesLoaded = true;
accountState.cloudSitesVersion = 0;
accountState.cloudPrefsVersion = 0;
accountState.cloudNukeVersion = 0;
accountState.cloudPolicy = { state: { creditBalanceSeconds: 111 }, groups: [{ groupId: 'a-group' }], limits: [], schedules: [] };
accountState.cloudPolicyVersion = pulseConfig.policyVersion;
accountState.cloudUsage = { date: '2026-09-19', totalTrackedSeconds: 17 };
accountState.cloudUsageBaseline = { '2026-09-19': { 'account-a-0.test': 17 } };
accountState.cloudLeisureBaseline = { '2026-09-19': { 'account-a-0.test': 7 } };
accountState.leisureStats = { '2026-09-19': { 'account-a-0.test': 7 } };
const accountAMeta = store.get('focuslock.cloud.v2');
accountAMeta.accounts.A.lastUsageSyncAt = 0;
store.set('focuslock.cloud.v2', accountAMeta);
const accountAResult = await cloud.syncUsage(accountState, 'A');
await applySyncResult(accountState, accountAResult);
assert.deepEqual([...serverPermanentSites.get('A').keys()], ['a-only-perma.test']);
assert.deepEqual(JSON.parse(JSON.stringify(accountState.permanentSites)), ['a-only-perma.test']);
clerk.user = { id: 'B' };
const accountMeta = store.get('focuslock.cloud.v2');
accountMeta.accounts.B = { ...accountMeta.accounts.A, deviceId: 'browser_B', prefsLoaded: true, prefsUpdatedAt: 123 };
store.set('focuslock.cloud.v2', accountMeta);
pulseConfig = { ...pulseConfig, policyVersion: 'policy-B',
  policy: { state: { creditBalanceSeconds: 222 }, groups: [{ groupId: 'b-group' }], limits: [], schedules: [] } };
calls.length = 0;
const switchedToB = await cloud.syncUsage(accountState, 'B');
assert.equal(switchedToB.accountChanged, true);
assert.equal(calls.find((call) => call.path === 'focus:getSyncPulse').args.prefsUpdatedAt, -1);
assert.equal(calls.find((call) => call.path === 'focus:getSyncPulse').args.nukeUpdatedAt, -1);
assert.equal(accountState.cloudPolicy, null);
assert.equal(calls.some((call) => call.path === 'focus:addPermanentBlocks'
  && call.args.targets.some((target) => target.targetKey === 'a-only-perma.test')), false,
'account A permanent sites are never queued for account B');
assert.equal(serverPermanentSites.has('B'), false);
await applySyncResult(accountState, switchedToB);
assert.equal(accountState.cloudPolicy.groups[0].groupId, 'b-group');
accountState.stats['2026-09-19'] = accountState.stats['2026-09-19'] || {};
accountState.stats['2026-09-19']['account-b.test'] = 2;
accountState.permanentSites.push('b-only-perma.test');
await cloud.syncUsage(accountState, 'B-data');
assert.deepEqual([...serverPermanentSites.get('B').keys()], ['b-only-perma.test']);
clerk.user = { id: 'A' };
pulseConfig = previousPulseConfig;
calls.length = 0;
await cloud.syncUsage(accountState, 'A-again');
assert.ok(accountState.stats['2026-09-19']['account-a-0.test']);
assert.equal(accountState.stats['2026-09-19']['account-b.test'], undefined);
assert.equal(accountState.cloudPolicy.groups[0].groupId, 'a-group');
assert.deepEqual(JSON.parse(JSON.stringify(accountState.cloudSites)), [{ domain: 'a-only.test', isBlocked: true, category: 'Social' }]);
assert.equal(accountState.cloudUsageBaseline['2026-09-19']['account-a-0.test'], 17);
assert.equal(accountState.cloudLeisureBaseline['2026-09-19']['account-a-0.test'], 7);
assert.equal(calls.find((call) => call.path === 'focus:getSyncPulse').args.knownPolicyVersion, pulseConfig.policyVersion);

// A fresh local store restores the account's permanent targets from the full
// pulse even when all ordinary versions already match.
const freshRestore = stateFor(0);
freshRestore.cloudAccountId = 'A';
freshRestore.cloudPolicy = { state: null, groups: [], limits: [], schedules: [] };
freshRestore.cloudPolicyVersion = pulseConfig.policyVersion;
freshRestore.cloudSitesLoaded = true;
freshRestore.cloudSitesVersion = pulseConfig.sitesUpdatedAt;
freshRestore.cloudPrefsVersion = pulseConfig.prefsUpdatedAt;
freshRestore.cloudNukeVersion = pulseConfig.nukeUpdatedAt;
calls.length = 0;
const restored = await cloud.syncUsage(freshRestore, 'fresh-storage-restore');
assert.equal(calls.find((call) => call.path === 'focus:getSyncPulse').args.knownPolicyVersion, pulseConfig.policyVersion);
assert.deepEqual(JSON.parse(JSON.stringify(restored.permanentBlocks)), [
  { targetKind: 'website', targetKey: 'a-only-perma.test', targetLabel: 'a-only-perma.test' },
]);
await applySyncResult(freshRestore, restored);
assert.deepEqual(JSON.parse(JSON.stringify(freshRestore.permanentSites)), ['a-only-perma.test']);

// A token obtained after an identity change is rejected, while the queue remains.
clerk.user = { id: 'A' };
const savedToken = clerk.session.getToken;
clerk.session.getToken = async () => { throw new Error('token refresh offline'); };
await assert.rejects(() => cloud.syncUsage(stateFor(1, 'token-fail-'), 'token-fail'), /token refresh offline/);
assert.match(store.get('focuslock.cloud.v2').accounts.A.lastError, /token refresh offline/);
clerk.session.getToken = savedToken;
changeIdentityDuringToken = true;
await assert.rejects(() => cloud.syncUsage(stateFor(1, 'race-'), 'race'), /changed/);
changeIdentityDuringToken = false;

// A query started for one account cannot return its data after an account switch.
clerk.user = { id: 'A' };
changeIdentityDuringDashboard = true;
await assert.rejects(() => cloud.getDashboard(), /changed during sync/i);
changeIdentityDuringDashboard = false;
clerk.user = { id: 'A' };

// A hanging Convex call aborts promptly so navigation enforcement can resume.
hangConvexRequest = true;
await assert.rejects(() => cloud.getDashboard(), /timed out while contacting Convex/);
hangConvexRequest = false;

// Concurrent writes are serialized and both durable mutations survive.
clerk.user = { id: 'A' };
await Promise.all([cloud.setWebsiteBlocked('one.test', true), cloud.setWebsiteBlocked('two.test', true)]);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations.length, 0);

// Strict block additions remain durable if the fresh policy query fails. A
// cache belonging to another account must not supply this mutation's metadata.
store.set('localState', { cloudAccountId: 'B', cloudSites: [
  { domain: 'offline-boundary.test', displayName: 'Other account label', category: 'Other account category' },
], cloudPrefs: { strictMode: true, strictEndsAt: Date.now() + 60_000 } });
failConfigOnce = true;
await assert.rejects(() => cloud.setWebsiteBlocked('offline-boundary.test', true), /configuration temporarily unavailable/);
const offlineMutation = store.get('focuslock.cloud.v2').accounts.A.pendingMutations
  .find(item => item.id === 'website:offline-boundary.test');
assert.equal(offlineMutation.args.displayName, 'offline-boundary.test', 'An unrelated account cache never supplies site metadata');
assert.equal(offlineMutation.args.category, 'Web');
const beforeOfflineRetry = calls.filter(call => call.path === 'focus:setBlockedWebsite'
  && call.args.domain === 'offline-boundary.test').length;
await cloud.setWebsiteBlocked('offline-boundary.test', true);
const offlineRetryCalls = calls.filter(call => call.path === 'focus:setBlockedWebsite'
  && call.args.domain === 'offline-boundary.test');
assert.equal(beforeOfflineRetry, 0);
assert.equal(offlineRetryCalls.length, 1, 'A retained strict addition flushes once after connectivity returns');
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations
  .some(item => item.id === 'website:offline-boundary.test'), false);
store.delete('localState');

rulesConfig = {
  prefs: { strictMode: true, strictEndsAt: Date.now() + 60_000 },
  sites: [{ domain: 'existing.test', displayName: 'Research notes', category: 'Study', isBlocked: false, isCustom: false }],
};
await cloud.setWebsiteBlocked('EXISTING.TEST', true);
let siteMutation = calls.filter(call => call.path === 'focus:setBlockedWebsite').at(-1);
assert.equal(siteMutation.args.displayName, 'Research notes', 'Strict block additions preserve existing site display metadata');
assert.equal(siteMutation.args.category, 'Study', 'Strict block additions preserve existing site category metadata');
await cloud.setWebsiteBlocked('new-strict.test', true);
siteMutation = calls.filter(call => call.path === 'focus:setBlockedWebsite').at(-1);
assert.equal(siteMutation.args.domain, 'new-strict.test', 'Strict Mode permits adding a new blocked site');
const blockedMutationsBeforeUnblock = calls.filter(call => call.path === 'focus:setBlockedWebsite').length;
await assert.rejects(() => cloud.setWebsiteBlocked('existing.test', false), /Strict Mode/,
  'Strict Mode rejects shared site unblocking before queuing a mutation');
assert.equal(calls.filter(call => call.path === 'focus:setBlockedWebsite').length, blockedMutationsBeforeUnblock);
rulesConfig = null;

// A stale cloud write stays queued and is reported as unapplied to the caller.
rejectMutationPath = 'focus:setBlockedWebsite';
await assert.rejects(() => cloud.setWebsiteBlocked('stale.test', true), /cloud kept a newer website or preference setting/i);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations.some((item) => item.id === 'website:stale.test'), true);
rejectMutationPath = '';
await cloud.syncUsage(stateFor(0), 'retry-stale');
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations.some((item) => item.id === 'website:stale.test'), false);

rejectMutationPath = 'groups:saveGroups';
await assert.rejects(() => cloud.saveGroups({ groups: [], updatedAt: Date.now() }), /cloud kept a newer website or preference setting/i);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations.some((item) => item.id === 'groups'), true);
rejectMutationPath = '';
await cloud.syncUsage(stateFor(0), 'retry-groups');
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations.some((item) => item.id === 'groups'), false);

// Browser handoff refreshes the existing tracker client, including signed-out state.
let authReloads = 0;
clerk.client = { async reload() { authReloads += 1; } };
assert.equal((await cloud.refreshAuth()).signedIn, true);
assert.equal(authReloads, 1);
store.get('focuslock.cloud.v2').accounts.A.prefsLoaded = true;
clerk.session = null;
clerk.user = null;
assert.equal((await cloud.refreshAuth()).signedIn, false);
assert.equal(authReloads, 2);
clerk.user = { id: 'A' };
clerk.session = savedToken ? { async getToken() { return 'token-A'; } } : null;
await cloud.signOut();
assert.equal(store.get('focuslock.cloud.v2').accounts.A.prefsLoaded, false);

// Live Convex policy subscription: stable full pulse, strict/site pushes, and
// acknowledged usage baselines are applied without a REST refresh.
clerk.user = { id: 'A', primaryEmailAddress: { emailAddress: 'a@test' } };
clerk.session = { async getToken(options) { this.lastOptions = options; return 'live-token'; } };
const liveMeta = store.get('focuslock.cloud.v2');
liveMeta.accounts.A.uploadedBuckets = [{ date: '2026-09-19', targetKind: 'website', targetKey: 'live.test', targetLabel: 'live.test', trackedSeconds: 12, leisureSeconds: 4, updatedAt: 1 }];
store.set('focuslock.cloud.v2', liveMeta);
const liveState = { cloudAccountId: 'A', cloudPolicy: { groups: [], limits: [], schedules: [] } };
const liveResults = [];
calls.length = 0;
await cloud.startLivePolicy({ onPolicy: (result) => { liveResults.push(result); } }, liveState);
const liveClient = liveClients.at(-1);
assert.equal(liveClient.args.usageDate, undefined);
assert.equal(calls.length, 0);
assert.equal(await liveClient.authFetcher({ forceRefreshToken: true }), 'live-token');
assert.equal(clerk.session.lastOptions.template, 'convex');
assert.equal(clerk.session.lastOptions.skipCache, true);
liveClient.update({ sites: [{ domain: 'strict.test', isBlocked: true }], prefs: { strictMode: true }, nuke: null,
  permanentBlocks: [{ targetKind: 'website', targetKey: 'WWW.Live-Perma.test.' }, { targetKind: 'android', targetKey: 'ignored.test' }],
  sitesUpdatedAt: 3, prefsUpdatedAt: 4, nukeUpdatedAt: 0, policyVersion: 'p-live',
  policy: { groups: [], limits: [], schedules: [] }, usageSummary: { totalTrackedSeconds: 12 } });
await new Promise((resolve) => setImmediate(resolve));
assert.equal(liveResults[0].sites[0].domain, 'strict.test');
assert.deepEqual(JSON.parse(JSON.stringify(liveResults[0].permanentBlocks)), [
  { targetKind: 'website', targetKey: 'live-perma.test' },
]);
assert.equal(liveResults[0].prefs.strictMode, true);
assert.equal(liveResults[0].leisureBaseline['2026-09-19']['live.test'], 4);
assert.equal(liveResults[0].usageBaseline['2026-09-19']['live.test'], 12);

// Account changes and sign-out close the old socket; queued callbacks from it
// cannot apply after the identity guard changes.
const oldLive = liveClient;
clerk.user = { id: 'B', primaryEmailAddress: { emailAddress: 'b@test' } };
await clerkListeners[0]();
await new Promise((resolve) => setImmediate(resolve));
assert.equal(oldLive.closed, true);
const beforeStale = liveResults.length;
oldLive.update({ sites: [{ domain: 'stale.test', isBlocked: true }], sitesUpdatedAt: 9, prefsUpdatedAt: 9, nukeUpdatedAt: 0, policyVersion: 'stale' });
await new Promise((resolve) => setImmediate(resolve));
assert.equal(liveResults.length, beforeStale);
clerk.user = null; clerk.session = null;
await clerkListeners[0]();
await new Promise((resolve) => setImmediate(resolve));
assert.equal(liveClients.at(-1).closed, true);

// Policies with daily limits include the local day so Convex can push usage.
clerk.user = { id: 'A', primaryEmailAddress: { emailAddress: 'a@test' } };
clerk.session = { async getToken() { return 'live-token'; } };
await cloud.startLivePolicy({ onPolicy: () => {} }, { cloudAccountId: 'A', cloudPolicy: { groups: [{ dailyLimitMinutes: 10 }], limits: [], schedules: [] } });
assert.equal(liveClients.at(-1).args.usageDate, '2026-09-19');

// Collection edits preserve other devices' rows and carry the read version.
rulesConfig = { prefs: { strictMode: false }, limitsUpdatedAt: 100, schedulesUpdatedAt: 200,
  limits: [{ _id: 'native', targetKind: 'app', targetKey: 'native.test', dailyLimitMinutes: 45, sessionLimitMinutes: 10 }],
  schedules: [{ _id: 'phone', scheduleId: 'phone', label: 'Phone', targetKind: 'app', targetKey: 'native.test', days: [1], startMinute: 540, endMinute: 600, isEnabled: true }] };
await cloud.sharedRules({ type: 'sharedRuleSave', collection: 'limits', version: 100,
  row: { targetKind: 'website', targetKey: 'site.test', dailyLimitMinutes: 30 } });
let write = calls.filter(call => call.path === 'focus:saveAppLimits').at(-1);
assert.equal(write.args.updatedAt, 100);
assert.equal(write.args.limits.length, 2);
assert.equal(write.args.limits[0].sessionLimitMinutes, 10);
assert.equal(write.args.limits[0]._id, undefined, 'Server metadata never sent back as row data');
await assert.rejects(cloud.sharedRules({ type: 'sharedRuleSave', collection: 'limits', version: 99,
  row: { targetKind: 'website', targetKey: 'site.test', dailyLimitMinutes: 30 } }), /Another device/);
await cloud.sharedRules({ type: 'sharedRuleSave', collection: 'schedules', version: 200,
  row: { scheduleId: 'chrome', label: 'Study', targetKind: 'website', targetKey: 'site.test', days: [5], startMinute: 1380, endMinute: 60, isEnabled: true } });
write = calls.filter(call => call.path === 'focus:saveSchedules').at(-1);
assert.equal(write.args.schedules[0].scheduleId, 'phone');
assert.equal(write.args.schedules[1].endMinute, 60);
rulesConfig.prefs = { strictMode: true, strictEndsAt: Date.now() + 60000 };
await assert.rejects(cloud.sharedRules({ type: 'sharedRuleSave', collection: 'limits', version: 100, row: {} }), /Strict Mode/);
rulesConfig.prefs = { strictMode: false }; rejectMutationPath = 'focus:saveSchedules';
await assert.rejects(cloud.sharedRules({ type: 'sharedRuleSave', collection: 'schedules', version: 200, row: rulesConfig.schedules[0] }), /Another device/);
rejectMutationPath = '';
const beforeWrongAccount = calls.length;
assert.equal((await cloud.logFocusSession({ title: 'A-only' }, 'B')).ok, false);
assert.equal((await cloud.savePrefs({ strictMode: true }, 'B')).ok, false);
assert.equal(calls.length, beforeWrongAccount, 'Origin-account check precedes network writes');
console.log('cloud-sync durability and shared-rule edit tests passed');
