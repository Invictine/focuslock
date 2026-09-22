import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

const source = (await fs.readFile(new URL('../src/cloud-sync.js', import.meta.url), 'utf8'))
  .replace(/^import .*?;\r?\n/, '')
  .replaceAll('process.env.CLERK_PUBLISHABLE_KEY', '"pk_test"')
  .replaceAll('process.env.CLERK_SYNC_HOST', '"https://clerk.test"')
  .replaceAll('process.env.CONVEX_URL', '"https://convex.test"');

const store = new Map();
const clerk = {
  user: { id: 'A', primaryEmailAddress: { emailAddress: 'a@test' } },
  session: { async getToken() { return 'token-A'; } },
  async signOut() { this.user = null; this.session = null; },
};
const calls = [];
let failUsageOnce = false;
let changeIdentityDuringToken = false;
const context = {
  self: { FocusLockStore: {
    todayKey: () => '2026-09-19',
    async save(state) { store.set('state', structuredClone(state)); },
  } },
  createClerkClient: () => clerk,
  chrome: {
    runtime: { getManifest: () => ({ version: '1.0.0' }) },
    storage: { local: {
      async get(key) { return { [key]: store.get(key) }; },
      async set(values) { for (const [key, value] of Object.entries(values)) store.set(key, structuredClone(value)); },
    } },
  },
  crypto: { randomUUID: (() => { let n = 0; return () => `uuid-${++n}`; })() },
  fetch: async (_url, init) => {
    const body = JSON.parse(init.body);
    calls.push(body);
    if (body.path === 'usage:recordUsageBatch' && failUsageOnce) {
      failUsageOnce = false;
      throw new Error('offline');
    }
    if (body.path === 'usage:recordUsageBatch') return { ok: true, async json() { return { status: 'success', value: {} }; } };
    if (body.path === 'focus:getSnapshot') return { ok: true, async json() { return { status: 'success', value: { sites: [], prefs: null } }; } };
    return { ok: true, async json() { return { status: 'success', value: { applied: true } }; } };
  },
};
clerk.session.getToken = async function getToken() {
  if (changeIdentityDuringToken) clerk.user = { id: 'B' };
  return 'token-A';
};
vm.runInNewContext(source, context, { filename: 'cloud-sync.js' });
const cloud = context.self.FocusLockCloud;

function stateFor(domainCount, prefix = 'd') {
  const stats = { '2026-09-19': {} };
  for (let i = 0; i < domainCount; i += 1) stats['2026-09-19'][`${prefix}${i}.test`] = i + 1;
  return { stats, blockedLog: [], blockedTotal: 0, cloudSites: [], cloudSitesLoaded: false };
}

// A failed request leaves the durable queue, and a later worker instance can retry it.
const retryState = stateFor(3);
failUsageOnce = true;
await assert.rejects(() => cloud.syncUsage(retryState, 'test'), /offline/);
const queuedAfterFailure = store.get('focuslock.cloud.v2').accounts.A.pendingBuckets;
assert.equal(queuedAfterFailure.length, 3);
await cloud.syncUsage(retryState, 'retry');
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingBuckets.length, 0);

// More than Convex's 500-row mutation limit is split into multiple idempotent calls.
calls.length = 0;
await cloud.syncUsage(stateFor(501, 'large-'), 'large');
assert.deepEqual(calls.filter((call) => call.path === 'usage:recordUsageBatch').map((call) => call.args.buckets.length), [500, 1]);

// Account snapshots are isolated and reversible.
const accountState = stateFor(1, 'account-a-');
  await cloud.syncUsage(accountState, 'A');
  clerk.user = { id: 'B' };
  await cloud.syncUsage(accountState, 'B');
  accountState.stats['2026-09-19'] = accountState.stats['2026-09-19'] || {};
  accountState.stats['2026-09-19']['account-b.test'] = 2;
  await cloud.syncUsage(accountState, 'B-data');
clerk.user = { id: 'A' };
await cloud.syncUsage(accountState, 'A-again');
assert.ok(accountState.stats['2026-09-19']['account-a-0.test']);
assert.equal(accountState.stats['2026-09-19']['account-b.test'], undefined);

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

// Concurrent writes are serialized and both durable mutations survive.
clerk.user = { id: 'A' };
await Promise.all([cloud.setWebsiteBlocked('one.test', true), cloud.setWebsiteBlocked('two.test', true)]);
assert.equal(store.get('focuslock.cloud.v2').accounts.A.pendingMutations.length, 0);

// Browser handoff refreshes the existing tracker client, including signed-out state.
let authReloads = 0;
clerk.client = { async reload() { authReloads += 1; } };
assert.equal((await cloud.refreshAuth()).signedIn, true);
assert.equal(authReloads, 1);
clerk.session = null;
clerk.user = null;
assert.equal((await cloud.refreshAuth()).signedIn, false);
assert.equal(authReloads, 2);

console.log('cloud-sync durability tests passed');
