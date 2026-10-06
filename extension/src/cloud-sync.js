import { createClerkClient } from '@clerk/chrome-extension/client';
import { ConvexClient } from 'convex/browser';
import { makeFunctionReference } from 'convex/server';
import { createLivePolicyController } from './live-policy.js';

const publishableKey = process.env.CLERK_PUBLISHABLE_KEY;
const syncHost = process.env.CLERK_SYNC_HOST;
const signInUrl = process.env.CLERK_SIGN_IN_URL;
const convexUrl = process.env.CONVEX_URL.replace(/\/$/, '');
const META_KEY = 'focuslock.cloud.v2';
const ACCOUNT_STATE_KEY = 'focuslock.cloud.accounts.v1';
const PERMANENT_SITE_OWNERS_KEY = 'focuslock.cloud.permanent-site-owners.v1';
const CONVEX_REQUEST_TIMEOUT_MS = 7000;
const ACTIVE_USAGE_SYNC_MS = 60 * 1000;
let clerkPromise;
let outboxLock = Promise.resolve();
let metaWriteLock = Promise.resolve();
let permanentOwnersLock = Promise.resolve();
let livePolicy;
let livePolicyArgs;

function policyArgsFor(state) {
  const needsUsage = !state.cloudPolicy
    || (state.cloudPolicy.groups || []).some(group => group.limitEnabled !== false && group.dailyLimitMinutes > 0)
    || (state.cloudPolicy.limits || []).some(limit => limit.dailyLimitMinutes > 0);
  // A stable full query lets Convex deliver changes without repeatedly changing
  // version arguments or downloading the dashboard/work history.
  return { sitesUpdatedAt: -1, prefsUpdatedAt: -1, nukeUpdatedAt: -1, knownPolicyVersion: '',
    ...(needsUsage ? { usageDate: self.FocusLockStore.todayKey() } : {}) };
}

async function startLivePolicy(handlers, state) {
  livePolicyArgs = policyArgsFor(state);
  if (!livePolicy) {
    livePolicy = createLivePolicyController({
      url: convexUrl,
      createClient: (url, options) => new ConvexClient(url, options),
      query: makeFunctionReference('focus:getSyncPulse'),
      readIdentity: async () => {
        const clerk = await cloudClient();
        return clerk.user && clerk.session ? { userId: clerk.user.id, session: clerk.session } : null;
      },
      fetchToken: async (identity, forceRefreshToken) => {
        await assertIdentity(identity.userId, identity.session);
        const token = await identity.session.getToken({ template: 'convex', skipCache: forceRefreshToken });
        await assertIdentity(identity.userId, identity.session);
        if (!token) throw new Error('Convex authentication token is unavailable');
        return token;
      },
      onIdentityChange: handlers.onIdentityChange,
      onError: error => { console.warn('[focuslock] live policy connection', error?.message || error); },
      onPolicy: async (shared, identity) => {
        const device = await meta(identity.userId);
        if (!await identity.isCurrent()) return;
        await rememberRemotePermanentOwners(shared.permanentBlocks, identity.userId);
        const acknowledged = device.uploadedBuckets || [];
        const usageBaseline = {};
        const leisureBaseline = {};
        for (const bucket of acknowledged) {
          (usageBaseline[bucket.date] ||= {})[bucket.targetKey] = bucket.trackedSeconds;
          (leisureBaseline[bucket.date] ||= {})[bucket.targetKey] = Number(bucket.leisureSeconds) || 0;
        }
        await handlers.onPolicy({ ok: true, signedIn: true, userId: identity.userId,
          isCurrent: identity.isCurrent, sites: shared.sites, prefs: shared.prefs, nuke: shared.nuke,
          permanentBlocks: normalizePermanentBlocks(shared.permanentBlocks),
          policy: shared.policy, versions: { sites: shared.sitesUpdatedAt, prefs: shared.prefsUpdatedAt,
            nuke: Number(shared.nukeUpdatedAt) || 0, policy: shared.policyVersion || '' },
          ...(shared.usageSummary ? { usage: { date: identity.args.usageDate, ...shared.usageSummary }, usageBaseline } : {}),
          leisureBaseline });
        if (await identity.isCurrent()) await saveMeta({ lastSyncAt: Date.now(), lastError: '' }, identity.userId);
      },
    });
    const clerk = await cloudClient();
    clerk.addListener?.(() => { void livePolicy.ensure(livePolicyArgs).catch(error => {
      console.warn('[focuslock] live policy account refresh', error?.message || error);
    }); });
  }
  await livePolicy.ensure(livePolicyArgs);
}

async function ensureLivePolicy(state) {
  if (!livePolicy) return;
  livePolicyArgs = policyArgsFor(state);
  await livePolicy.ensure(livePolicyArgs);
}

function livePolicyStatus() {
  return livePolicy?.status() || { active: false, connected: false, received: false };
}

function cloudClient() {
  if (!clerkPromise) clerkPromise = Promise.resolve(createClerkClient({ publishableKey, syncHost, background: true })).catch((error) => {
    clerkPromise = null;
    throw error;
  });
  return clerkPromise;
}

async function refreshAuth() {
  const clerk = await cloudClient();
  await clerk.client.reload();
  if (livePolicy) await livePolicy.ensure(livePolicyArgs);
  return { signedIn: Boolean(clerk.session && clerk.user) };
}

async function authContext(expectedUserId, expectedSession) {
  const clerk = await cloudClient();
  if (!clerk.session || !clerk.user) return null;
  const session = clerk.session;
  const userId = clerk.user.id;
  if (expectedUserId && userId !== expectedUserId) throw new Error('Account changed while preparing sync; retrying for the active account');
  if (expectedSession && session !== expectedSession) throw new Error('Session changed while preparing sync; retrying for the active session');
  const authToken = await session.getToken({ template: 'convex' });
  if (!authToken) throw new Error('Convex authentication token is unavailable; check the Clerk "convex" JWT template');
  const current = await cloudClient();
  if (!current.session || !current.user || current.user.id !== userId || current.session !== session) {
    throw new Error('Account or session changed while obtaining the sync token; retrying');
  }
  return { clerk: current, authToken, userId, session };
}

async function assertIdentity(userId, session) {
  const current = await cloudClient();
  if (!current.session || !current.user || current.user.id !== userId || current.session !== session) {
    throw new Error('Account or session changed during sync; retrying for the active account');
  }
}

async function callConvex(kind, path, args, authToken) {
  const controller = new AbortController();
  let timeoutId;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => {
      controller.abort();
      reject(new Error(`Sync request timed out while contacting Convex (${path})`));
    }, CONVEX_REQUEST_TIMEOUT_MS);
  });
  try {
    const response = await Promise.race([fetch(`${convexUrl}/api/${kind}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${authToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ path, args }),
      signal: controller.signal,
    }), timeout]);
    const payload = await Promise.race([response.json().catch(() => ({})), timeout]);
    if (!response.ok || payload.status !== 'success') {
      throw new Error(payload.errorMessage || `Sync returned HTTP ${response.status}`);
    }
    return payload.value;
  } catch (error) {
    if (controller.signal.aborted && !String(error?.message || '').includes('timed out')) {
      throw new Error(`Sync request was cancelled while contacting Convex (${path})`);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

function isAuthUrl(value) {
  let candidate;
  try { candidate = new URL(value); } catch { return false; }
  if (candidate.protocol !== 'https:') return false;
  return [syncHost, signInUrl].some((configured) => {
    try {
      const trusted = new URL(configured);
      return trusted.protocol === 'https:' && candidate.origin === trusted.origin;
    } catch {
      return false;
    }
  });
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function validStorageKey(value) {
  return typeof value === 'string' && value.length > 0
    && !Object.prototype.hasOwnProperty.call(Object.prototype, value);
}

function nonNegativeNumber(value, fallback = 0) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : fallback;
}

function validDateKey(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function normalizeBucket(value) {
  if (!isRecord(value)
    || !validDateKey(value.date)
    || typeof value.targetKind !== 'string' || !value.targetKind.trim()
    || typeof value.targetKey !== 'string' || !value.targetKey.trim()) return null;
  const trackedSeconds = value.trackedSeconds;
  if (typeof trackedSeconds !== 'number' || !Number.isFinite(trackedSeconds) || trackedSeconds < 0) return null;
  return {
    ...value,
    date: value.date,
    targetKind: value.targetKind.trim(),
    targetKey: value.targetKey.trim(),
    targetLabel: typeof value.targetLabel === 'string' ? value.targetLabel : value.targetKey.trim(),
    category: typeof value.category === 'string' ? value.category : 'Web',
    trackedSeconds: Math.floor(trackedSeconds),
    ...(typeof value.leisureSeconds === 'number' && Number.isFinite(value.leisureSeconds)
      ? { leisureSeconds: Math.max(0, Math.min(Math.floor(value.leisureSeconds), Math.floor(trackedSeconds))) } : {}),
    updatedAt: nonNegativeNumber(value.updatedAt),
  };
}

function normalizeBuckets(value) {
  return Array.isArray(value) ? value.map(normalizeBucket).filter(Boolean) : [];
}

function normalizePermanentBlocks(value) {
  if (!Array.isArray(value)) return [];
  const targets = [];
  const seen = new Set();
  for (const item of value) {
    if (!isRecord(item) || item.targetKind !== 'website' || typeof item.targetKey !== 'string') continue;
    const domain = self.FocusLockStore.normalizePermanentSites([item.targetKey])[0];
    if (!domain || seen.has(domain)) continue;
    seen.add(domain);
    targets.push({ targetKind: 'website', targetKey: domain,
      ...(typeof item.targetLabel === 'string' && item.targetLabel.trim()
        ? { targetLabel: item.targetLabel.trim().slice(0, 253) } : {}) });
  }
  return targets;
}

const OUTBOX_MUTATIONS = new Set([
  'focus:setBlockedWebsite', 'focus:addPermanentBlocks', 'focus:addWorkRecord', 'focus:logFocusSession', 'focus:recordWork', 'focus:savePrefs', 'groups:saveGroups',
]);

function normalizeMutation(value) {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id.trim()
    || !OUTBOX_MUTATIONS.has(value.path) || !isRecord(value.args)) return null;
  const args = value.args;
  if (value.path === 'focus:setBlockedWebsite'
    && (typeof args.domain !== 'string' || !args.domain.trim()
      || typeof args.displayName !== 'string' || typeof args.isBlocked !== 'boolean'
      || typeof args.updatedAt !== 'number' || !Number.isFinite(args.updatedAt))) return null;
  if (value.path === 'focus:addPermanentBlocks'
    && (!Array.isArray(args.targets) || !args.targets.length || args.targets.length > 500
      || args.targets.some((target) => !isRecord(target) || target.targetKind !== 'website'
        || typeof target.targetKey !== 'string' || !target.targetKey.trim()))) return null;
  if (value.path === 'focus:addWorkRecord'
    && (typeof args.recordId !== 'string' || !args.recordId.trim()
      || typeof args.durationMinutes !== 'number' || !Number.isFinite(args.durationMinutes))) return null;
  if (value.path === 'focus:logFocusSession'
    && (typeof args.sessionId !== 'string' || !args.sessionId.trim()
      || typeof args.durationMinutes !== 'number' || !Number.isFinite(args.durationMinutes))) return null;
  if (value.path === 'focus:savePrefs'
    && (typeof args.updatedAt !== 'number' || !Number.isFinite(args.updatedAt))) return null;
  if (value.path === 'focus:recordWork' && (typeof args.recordId !== 'string' || !args.recordId
    || !validDateKey(args.date) || !Number.isFinite(args.durationMinutes) || !Number.isFinite(args.earnedMinutesCredited))) return null;
  if (value.path === 'groups:saveGroups' && (!Array.isArray(args.groups) || !Number.isFinite(args.updatedAt))) return null;
  return {
    id: value.id,
    kind: typeof value.kind === 'string' ? value.kind : '',
    path: value.path,
    args: value.args,
    revision: typeof value.revision === 'string' ? value.revision : '',
  };
}

function normalizeMutations(value) {
  return Array.isArray(value) ? value.map(normalizeMutation).filter(Boolean) : [];
}

function normalizeStats(value) {
  if (!isRecord(value)) return {};
  const stats = {};
  for (const [date, rawDomains] of Object.entries(value)) {
    if (!validDateKey(date) || !isRecord(rawDomains)) continue;
    const domains = {};
    for (const [domain, rawSeconds] of Object.entries(rawDomains)) {
      const seconds = rawSeconds;
      if (domain && typeof seconds === 'number' && Number.isFinite(seconds) && seconds >= 0) domains[domain] = seconds;
    }
    stats[date] = domains;
  }
  return stats;
}

function scopedState(state) {
  const source = isRecord(state) ? state : {};
  const log = Array.isArray(source.blockedLog) ? source.blockedLog.filter(isRecord) : [];
  const sites = Array.isArray(source.cloudSites) ? source.cloudSites.filter((site) =>
    isRecord(site) && typeof site.domain === 'string' && site.domain.trim())
    .map((site) => ({ domain: site.domain.trim().toLowerCase(), isBlocked: Boolean(site.isBlocked),
      ...(typeof site.category === 'string' ? { category: site.category.slice(0, 80) } : {}) })) : [];
  // Deliberately device-local: permanentSites (Permalock) is never uploaded,
  // snapshotted, or restored by an account switch. Only an explicit append on
  // this device can change it.
  return {
    stats: normalizeStats(source.stats),
    blockedLog: log,
    blockedTotal: nonNegativeNumber(source.blockedTotal),
    cloudSites: sites,
    cloudSitesLoaded: source.cloudSitesLoaded === true,
    leisureStats: normalizeStats(source.leisureStats),
    cloudPolicy: isRecord(source.cloudPolicy) ? source.cloudPolicy : null,
    cloudPrefs: isRecord(source.cloudPrefs) ? source.cloudPrefs : null,
    cloudUsage: isRecord(source.cloudUsage) ? source.cloudUsage : null,
    cloudUsageBaseline: normalizeStats(source.cloudUsageBaseline),
    cloudLeisureBaseline: normalizeStats(source.cloudLeisureBaseline),
    cloudPolicyVersion: typeof source.cloudPolicyVersion === 'string' ? source.cloudPolicyVersion : '',
    cloudSitesVersion: nonNegativeNumber(source.cloudSitesVersion, -1),
    cloudPrefsVersion: nonNegativeNumber(source.cloudPrefsVersion, -1),
    cloudNukeVersion: nonNegativeNumber(source.cloudNukeVersion, -1),
  };
}

function normalizeAccount(value) {
  const account = isRecord(value) ? value : {};
  const normalized = {
    ...account,
    deviceId: typeof account.deviceId === 'string' && account.deviceId.trim()
      ? account.deviceId.trim() : `browser_${crypto.randomUUID()}`,
    lastSyncAt: nonNegativeNumber(account.lastSyncAt),
    lastError: typeof account.lastError === 'string' ? account.lastError : '',
    lastWarning: typeof account.lastWarning === 'string' ? account.lastWarning : '',
    pendingBuckets: normalizeBuckets(account.pendingBuckets),
    pendingMutations: normalizeMutations(account.pendingMutations),
    uploadedBuckets: normalizeBuckets(account.uploadedBuckets),
    uploadedPermanentSites: self.FocusLockStore.normalizePermanentSites(account.uploadedPermanentSites),
  };
  for (const key of ['lastHeartbeatAt', 'lastUsageSyncAt', 'sitesUpdatedAt', 'prefsUpdatedAt', 'lastNukeSyncAt', 'nukeUpdatedAt']) {
    if (key in account) normalized[key] = nonNegativeNumber(account[key]);
  }
  if ('prefsLoaded' in account) normalized.prefsLoaded = account.prefsLoaded === true;
  return normalized;
}

function normalizeMetaRoot(value) {
  const source = isRecord(value) ? value : {};
  const accounts = {};
  if (isRecord(source.accounts)) {
    for (const [userId, account] of Object.entries(source.accounts)) {
      if (validStorageKey(userId)) accounts[userId] = normalizeAccount(account);
    }
  }
  return { ...source, version: 2, accounts };
}

function normalizeAccountSnapshots(value) {
  const snapshots = {};
  if (!isRecord(value)) return snapshots;
  for (const [userId, state] of Object.entries(value)) {
    if (validStorageKey(userId)) snapshots[userId] = scopedState(state);
  }
  return snapshots;
}

async function meta(userId = 'anonymous') {
  if (!validStorageKey(userId)) throw new Error('Invalid account storage key');
  const read = async () => {
    const stored = await chrome.storage.local.get(META_KEY);
    const originalRoot = stored[META_KEY];
    const root = normalizeMetaRoot(originalRoot);
    if (!root.accounts[userId]) {
      const legacy = typeof root.deviceId === 'string' && userId !== 'anonymous' ? root : null;
      root.accounts[userId] = {
        deviceId: legacy?.deviceId || `browser_${crypto.randomUUID()}`,
        lastSyncAt: legacy?.lastSyncAt || 0,
        lastError: legacy?.lastError || '',
        pendingBuckets: normalizeBuckets(legacy?.pendingBuckets),
        pendingMutations: normalizeMutations(legacy?.pendingMutations),
        uploadedBuckets: normalizeBuckets(legacy?.uploadedBuckets),
      };
      // Keep legacy metadata intact while signed out. The first real account
      // can still migrate its stable device ID later.
      if (userId !== 'anonymous') {
        delete root.deviceId;
        delete root.lastSyncAt;
        delete root.lastError;
      }
      await chrome.storage.local.set({ [META_KEY]: root });
    }
    root.accounts[userId] = normalizeAccount(root.accounts[userId]);
    if (JSON.stringify(originalRoot) !== JSON.stringify(root)) {
      await chrome.storage.local.set({ [META_KEY]: root });
    }
    return root.accounts[userId];
  };
  const run = metaWriteLock.then(read, read);
  metaWriteLock = run.catch(() => {});
  return run;
}

async function saveMeta(patch, userId = 'anonymous') {
  if (!validStorageKey(userId)) throw new Error('Invalid account storage key');
  const write = async () => {
    const stored = await chrome.storage.local.get(META_KEY);
    const root = normalizeMetaRoot(stored[META_KEY]);
    if (!root.accounts[userId]) {
      root.accounts[userId] = normalizeAccount({});
    }
    root.accounts[userId] = normalizeAccount({ ...root.accounts[userId], ...patch });
    await chrome.storage.local.set({ [META_KEY]: root });
    return root.accounts[userId];
  };
  const run = metaWriteLock.then(write, write);
  metaWriteLock = run.catch(() => {});
  return run;
}

async function switchAccountState(state, userId) {
  if (!validStorageKey(userId)) throw new Error('Invalid account storage key');
  const currentUserId = state.cloudAccountId || '';
  if (currentUserId === userId) return false;
  const stored = await chrome.storage.local.get(ACCOUNT_STATE_KEY);
  const snapshots = normalizeAccountSnapshots(stored[ACCOUNT_STATE_KEY]);
  if (currentUserId) snapshots[currentUserId] = scopedState(state);
  const target = snapshots[userId];
  if (target) Object.assign(state, target);
  else Object.assign(state, { ...(currentUserId ? { stats: {}, blockedLog: [], blockedTotal: 0 } : {}),
    cloudSites: [], cloudSitesLoaded: false, leisureStats: {}, cloudPolicy: null, cloudPrefs: null,
    cloudUsage: null, cloudUsageBaseline: {}, cloudLeisureBaseline: {}, cloudPolicyVersion: '',
    cloudSitesVersion: -1, cloudPrefsVersion: -1, cloudNukeVersion: -1 });
  state.cloudAccountId = userId;
  await chrome.storage.local.set({ [ACCOUNT_STATE_KEY]: snapshots });
  await self.FocusLockStore.save(state);
  // The durable version belongs to this account, while live prefs in the
  // worker may still represent the previously active account. Force one pull.
  await saveMeta({ prefsLoaded: false, policyVersion: '' }, userId);
  return true;
}

function bucketsFor(state) {
  const updatedAt = Date.now();
  const buckets = [];
  for (const [date, domains] of Object.entries(state.stats || {})) {
    for (const [rawDomain, rawSeconds] of Object.entries(domains || {})) {
      const domain = String(rawDomain).trim().toLowerCase();
      if (!domain) continue;
      buckets.push({
        date,
        targetKind: 'website',
        targetKey: domain,
        targetLabel: domain,
        category: 'Web',
        trackedSeconds: Math.max(0, Math.floor(Number(rawSeconds) || 0)),
        leisureSeconds: Math.min(Math.max(0, Math.floor(Number(rawSeconds) || 0)),
          Math.max(0, Math.floor(Number(state.leisureStats?.[date]?.[domain]) || 0))),
        updatedAt,
      });
    }
  }
  return buckets;
}

function mergeBuckets(previous, current) {
  const merged = new Map();
  for (const bucket of [...(previous || []), ...(current || [])]) {
    const key = `${bucket.date}\u001f${bucket.targetKind}\u001f${bucket.targetKey}`;
    const old = merged.get(key);
    if (!old || Number(bucket.updatedAt || 0) >= Number(old.updatedAt || 0)) merged.set(key, bucket);
  }
  return [...merged.values()];
}

function withOutboxLock(task) {
  const next = outboxLock.then(task, task);
  outboxLock = next.catch(() => {});
  return next;
}

async function queueMutation(userId, mutation) {
  return withOutboxLock(async () => {
    const current = await meta(userId);
    const pending = Array.isArray(current.pendingMutations) ? current.pendingMutations : [];
    const withoutDuplicate = pending.filter((item) => item.id !== mutation.id);
    await saveMeta({ pendingMutations: [...withoutDuplicate, { ...mutation, revision: crypto.randomUUID() }] }, userId);
  });
}

async function flushMutationOutbox(userId, authToken) {
  const pending = await withOutboxLock(async () => {
    const current = await meta(userId);
    return Array.isArray(current.pendingMutations) ? current.pendingMutations : [];
  });
  const acknowledged = [];
  for (const item of pending) {
    try {
      const value = await callConvex('mutation', item.path, item.args, authToken);
      if (value?.applied !== false) acknowledged.push(item);
    } catch (error) {
      throw error;
    }
  }
  await withOutboxLock(async () => {
    const current = await meta(userId);
    const currentPending = Array.isArray(current.pendingMutations) ? current.pendingMutations : [];
    const ack = new Set(acknowledged.map((item) => `${item.id}:${item.revision}`));
    await saveMeta({ pendingMutations: currentPending.filter((item) => !ack.has(`${item.id}:${item.revision}`)) }, userId);
  });
  const remaining = await withOutboxLock(async () => {
    const current = await meta(userId);
    return Array.isArray(current.pendingMutations) ? current.pendingMutations : [];
  });
  return { acknowledged, remaining };
}

async function updatePermanentSiteOwners(update) {
  const run = async () => {
    const stored = await chrome.storage.local.get(PERMANENT_SITE_OWNERS_KEY);
    const rawOwners = isRecord(stored[PERMANENT_SITE_OWNERS_KEY]) ? stored[PERMANENT_SITE_OWNERS_KEY] : {};
    const owners = {};
    for (const [domain, owner] of Object.entries(rawOwners)) {
      const normalized = self.FocusLockStore.normalizePermanentSites([domain])[0];
      if (normalized && (owner === 'anonymous' || validStorageKey(owner))) owners[normalized] = owner;
    }
    await update(owners);
    await chrome.storage.local.set({ [PERMANENT_SITE_OWNERS_KEY]: owners });
    return owners;
  };
  const next = permanentOwnersLock.then(run, run);
  permanentOwnersLock = next.catch(() => {});
  return next;
}

async function rememberRemotePermanentOwners(value, userId) {
  const targets = normalizePermanentBlocks(value);
  if (!targets.length) return;
  await updatePermanentSiteOwners((owners) => {
    for (const target of targets) {
      if (!owners[target.targetKey]) owners[target.targetKey] = userId;
    }
  });
}

async function preparePermanentSiteMutations(state, userId, priorAccountId) {
  if (!validStorageKey(userId)) throw new Error('Invalid account storage key');
  const localSites = self.FocusLockStore.normalizePermanentSites(state.permanentSites);
  const owners = await updatePermanentSiteOwners((currentOwners) => {
    for (const domain of localSites) {
      if (!currentOwners[domain]) currentOwners[domain] = priorAccountId || 'anonymous';
    }
    // Anonymous sites migrate once, on the first authenticated sync. Sites
    // observed under another account stay attributed there across switches.
    for (const [domain, owner] of Object.entries(currentOwners)) {
      if (owner === 'anonymous' && localSites.includes(domain)) currentOwners[domain] = userId;
    }
  });
  const account = await meta(userId);
  const uploaded = new Set(account.uploadedPermanentSites || []);
  const targets = localSites.filter((domain) => owners[domain] === userId && !uploaded.has(domain))
    .map((domain) => ({ targetKind: 'website', targetKey: domain, targetLabel: domain }));
  const mutations = [];
  for (let offset = 0; offset < targets.length; offset += 500) {
    const batch = targets.slice(offset, offset + 500);
    const mutation = { id: `permanent-sites:${batch[0].targetKey}`, kind: 'permanent-sites',
      path: 'focus:addPermanentBlocks', args: { targets: batch } };
    await queueMutation(userId, mutation);
    mutations.push(mutation);
  }
  return mutations;
}

async function flushRequiredMutation(userId, authToken, mutationId) {
  const result = await flushMutationOutbox(userId, authToken);
  if (result.remaining.some((item) => item.id === mutationId)) {
    throw new Error('The cloud kept a newer website or preference setting. Sync again before retrying this change.');
  }
  return result;
}

async function syncUsage(state, reason) {
  const identity = await cloudClient();
  if (!identity.session || !identity.user) return { signedIn: false, ok: false, error: '' };
  const expectedUserId = identity.user.id;
  const expectedSession = identity.session;
  const priorAccountId = typeof state.cloudAccountId === 'string' ? state.cloudAccountId : '';
  const accountChanged = await switchAccountState(state, expectedUserId);
  await preparePermanentSiteMutations(state, expectedUserId, priorAccountId);
  const device = await meta(expectedUserId);
  const now = Date.now();
  // Navigations only check rule versions. Usage is uploaded on the sync alarm
  // or an explicit sync, so a busy browsing session cannot write every few seconds.
  const initialSync = accountChanged || reason === 'installed' || reason === 'startup';
  const activeSync = reason === 'active' && now - (Number(device.lastUsageSyncAt) || 0) >= ACTIVE_USAGE_SYNC_MS;
  // Usage counters are cumulative and idempotent. Four-hour uploads keep the
  // background budget bounded; explicit Sync Now remains an immediate refresh.
  const uploadUsage = reason === 'manual' || initialSync || activeSync || (device.pendingBuckets || []).length > 0
    || now - (Number(device.lastUsageSyncAt) || 0) >= 4 * 60 * 60 * 1000;
  let uploaded = device.uploadedBuckets || [];
  if (uploadUsage) {
    const uploadedByKey = new Map(uploaded.map((bucket) => [`${bucket.date}\u001f${bucket.targetKind}\u001f${bucket.targetKey}`, bucket]));
    const changed = bucketsFor(state).filter((bucket) => {
      const old = uploadedByKey.get(`${bucket.date}\u001f${bucket.targetKind}\u001f${bucket.targetKey}`);
      return !old || Number(old.trackedSeconds) !== Number(bucket.trackedSeconds)
        || Number(old.leisureSeconds || 0) !== Number(bucket.leisureSeconds || 0);
    });
    // Queue before requesting a token. Offline counters remain durable for retry.
    await saveMeta({ pendingBuckets: mergeBuckets(device.pendingBuckets || [], changed) }, expectedUserId);
  }
  try {
    const auth = await authContext(expectedUserId, expectedSession);
    const heartbeatDue = uploadUsage && now - (Number(device.lastHeartbeatAt) || 0) >= 4 * 60 * 60 * 1000;
    const heartbeat = heartbeatDue ? {
      name: 'Chrome extension', platform: 'browser',
      appVersion: chrome.runtime.getManifest().version,
      trackingStatus: 'active', lastSeen: now,
    } : undefined;
    const pending = uploadUsage ? ((await meta(auth.userId)).pendingBuckets || []) : [];
    let expiredUsage = 0;
    let oldestAcceptedDate = '';
    for (let offset = 0; offset < pending.length; offset += 500) {
      const result = await callConvex('mutation', 'usage:recordUsageBatch', {
        deviceId: device.deviceId,
        buckets: pending.slice(offset, offset + 500),
        ...(heartbeat && offset === 0 ? { heartbeat } : {}),
      }, auth.authToken);
      expiredUsage += Math.max(0, Math.floor(Number(result?.expired) || 0));
      if (typeof result?.oldestAcceptedDate === 'string') oldestAcceptedDate = result.oldestAcceptedDate;
    }
    if (heartbeat && pending.length === 0) {
      await callConvex('mutation', 'devices:heartbeat', { deviceId: device.deviceId, ...heartbeat }, auth.authToken);
    }
    const flushedMutations = await flushMutationOutbox(auth.userId, auth.authToken);
    const uploadedPermanentTargets = flushedMutations.acknowledged
      .filter((item) => item.path === 'focus:addPermanentBlocks')
      .flatMap((item) => item.args.targets || [])
      .map((target) => target.targetKey);
    if (uploadedPermanentTargets.length) {
      await saveMeta({ uploadedPermanentSites: self.FocusLockStore.normalizePermanentSites([
        ...(await meta(auth.userId)).uploadedPermanentSites, ...uploadedPermanentTargets,
      ]) }, auth.userId);
    }
    const latest = await meta(auth.userId);
    const pulseArgs = {
      sitesUpdatedAt: state.cloudSitesLoaded && Number.isFinite(state.cloudSitesVersion) ? state.cloudSitesVersion : -1,
      prefsUpdatedAt: latest.prefsLoaded && Number.isFinite(state.cloudPrefsVersion) ? state.cloudPrefsVersion : -1,
    };
    pulseArgs.nukeUpdatedAt = !accountChanged && Number.isFinite(state.cloudNukeVersion) ? state.cloudNukeVersion : -1;
    // Conditional versions live beside the persisted data, so a failed state
    // save cannot acknowledge a policy that will be missing after a restart.
    pulseArgs.knownPolicyVersion = state.cloudPolicy && typeof state.cloudPolicyVersion === 'string' ? state.cloudPolicyVersion : '';
    // Usage summaries are needed only for shared merged/daily limits, and carry
    // the local day explicitly. Credit-only browsing uses the versioned state.
    const needsUsage = !state.cloudPolicy || (state.cloudPolicy.groups || []).some(group => group.limitEnabled !== false && group.dailyLimitMinutes > 0)
      || (state.cloudPolicy.limits || []).some(limit => limit.dailyLimitMinutes > 0);
    if (needsUsage) pulseArgs.usageDate = self.FocusLockStore.todayKey();
    const acknowledgedBuckets = mergeBuckets(uploaded, pending);
    const usageBaseline = {};
    for (const bucket of acknowledgedBuckets) {
      usageBaseline[bucket.date] ||= {};
      usageBaseline[bucket.date][bucket.targetKey] = bucket.trackedSeconds;
    }
    const shared = await callConvex('query', 'focus:getSyncPulse', pulseArgs, auth.authToken);
    await assertIdentity(auth.userId, auth.session);
    await rememberRemotePermanentOwners(shared.permanentBlocks, auth.userId);
    await saveMeta({
      lastSyncAt: now,
      lastError: '',
      ...(expiredUsage > 0 ? { lastWarning: `Older usage outside the ${oldestAcceptedDate ? `history window (before ${oldestAcceptedDate})` : '31-day history window'} stays on this device and was not uploaded.` } : {}),
      sitesUpdatedAt: shared.sitesUpdatedAt,
      prefsUpdatedAt: shared.prefsUpdatedAt,
      nukeUpdatedAt: Number(shared.nukeUpdatedAt) || 0,
      prefsLoaded: true,
      policyVersion: typeof shared.policyVersion === 'string' ? shared.policyVersion : '',
      ...(uploadUsage ? { lastUsageSyncAt: now, pendingBuckets: [], uploadedBuckets: mergeBuckets(uploaded, pending) } : {}),
      ...(heartbeat ? { lastHeartbeatAt: now } : {}),
    }, auth.userId);
    return {
      signedIn: true, ok: true, accountChanged, lastSyncAt: now,
      sites: shared.sites, prefs: shared.prefs, nuke: shared.nuke,
      permanentBlocks: normalizePermanentBlocks(shared.permanentBlocks),
      policy: shared.policy,
      versions: { sites: shared.sitesUpdatedAt, prefs: shared.prefsUpdatedAt,
        nuke: Number(shared.nukeUpdatedAt) || 0, policy: shared.policyVersion || '' },
      ...(shared.usageSummary ? { usage: { date: pulseArgs.usageDate, ...shared.usageSummary }, usageBaseline } : {}),
      // Only uploaded spending is acknowledged. A concurrent slice created
      // during the request must still reduce the locally available balance.
      leisureBaseline: Object.fromEntries([...mergeBuckets(uploaded, pending)].reduce((days, bucket) => {
        if (!days.has(bucket.date)) days.set(bucket.date, {});
        days.get(bucket.date)[bucket.targetKey] = Number(bucket.leisureSeconds) || 0;
        return days;
      }, new Map())),
    };
  } catch (error) {
    await saveMeta({ lastError: error?.message || 'Sync failed' }, expectedUserId);
    throw error;
  }
}

// A single-site mutation avoids replacing another device's entire boundaries
// collection with a stale extension snapshot.
async function setWebsiteBlocked(domain, isBlocked) {
  const identity = await cloudClient();
  if (!identity.session || !identity.user) return { signedIn: false, ok: false };
  const normalized = String(domain || '').trim().toLowerCase().replace(/^www\./, '');
  if (!normalized) throw new Error('Choose a website first');
  const args = {
    domain: normalized, displayName: normalized, isBlocked: Boolean(isBlocked),
    category: 'Web', updatedAt: Date.now(),
  };
  const mutation = { id: `website:${normalized}`, kind: 'website', path: 'focus:setBlockedWebsite', args };
  const userId = identity.user.id;
  await queueMutation(userId, mutation);
  const auth = await authContext(userId, identity.session);
  if (!auth) return { signedIn: false, ok: false };
  await flushRequiredMutation(auth.userId, auth.authToken, mutation.id);
  return { signedIn: true, ok: true };
}

async function getSnapshot(state, shouldSync) {
  const clerk = await cloudClient();
  const auth = await authContext();
  if (!auth) return { signedIn: false, devices: [], summary: null, ...(await meta()) };
  let syncResult = null;
  if (shouldSync) syncResult = await syncUsage(state, 'manual');
  const today = self.FocusLockStore.todayKey();
  const [devices, summary] = await Promise.all([
    callConvex('query', 'devices:listDevices', {}, auth.authToken),
    callConvex('query', 'usage:getUsageSummary', { fromDate: today, toDate: today }, auth.authToken),
  ]);
  await assertIdentity(auth.userId, auth.session);
  return {
    signedIn: Boolean(clerk.session),
    accountId: clerk.session ? clerk.user?.id || '' : '',
    user: clerk.user ? { id: clerk.user.id, email: clerk.user.primaryEmailAddress?.emailAddress || '' } : null,
    devices: devices || [],
    summary,
    syncResult,
    ...(await meta(auth.userId)),
  };
}

async function signOut() {
  const clerk = await cloudClient();
  const userId = clerk.user?.id;
  if (userId) await saveMeta({ prefsLoaded: false, lastNukeSyncAt: 0 }, userId);
  await livePolicy?.stop();
  await clerk.signOut();
  return { ok: true };
}

// Lightweight local-only status for protection checklists (no network round-trip).
async function status() {
  const clerk = await cloudClient();
  const m = await meta(clerk.user?.id || 'anonymous');
  return {
    signedIn: Boolean(clerk.session),
    accountId: clerk.session ? clerk.user?.id || '' : '',
    lastSyncAt: Number(m.lastSyncAt) || 0,
    lastError: m.lastError || '',
    lastWarning: m.lastWarning || '',
    deviceId: m.deviceId || '',
  };
}

// Focus dashboard snapshot (leisure balance, records, sessions, prefs, devices).
async function getDashboard(fromDate, toDate) {
  const auth = await authContext();
  if (!auth) return { signedIn: false, dashboard: null };
  const args = {};
  if (fromDate) args.fromDate = fromDate;
  if (toDate) args.toDate = toDate;
  const [dashboard, groupsState] = await Promise.all([
    callConvex('query', 'focus:getDashboard', args, auth.authToken),
    callConvex('query', 'groups:groupsState', {}, auth.authToken),
  ]);
  await assertIdentity(auth.userId, auth.session);
  return { signedIn: true, dashboard: { ...dashboard, groups: groupsState?.groups || [], groupsUpdatedAt: groupsState?.updatedAt || 0 } };
}

async function saveGroups({ groups, updatedAt }) {
  const identity = await cloudClient();
  if (!identity.session || !identity.user) return { signedIn: false, ok: false };
  const args = { groups, updatedAt: Number(updatedAt) || Date.now() };
  const id = 'groups';
  await queueMutation(identity.user.id, { id, kind: 'groups', path: 'groups:saveGroups', args });
  const auth = await authContext(identity.user.id, identity.session);
  await flushRequiredMutation(auth.userId, auth.authToken, id);
  return { signedIn: true, ok: true };
}

// Idempotent manual work log (recordId dedupes retries server-side).
async function addWorkRecord(record) {
  const identity = await cloudClient();
  if (!identity.session || !identity.user) return { signedIn: false, ok: false };
  const recordId = record.recordId || `work_${crypto.randomUUID()}`;
  const args = {
    recordId,
    title: String(record.title || 'Work'),
    durationMinutes: Math.max(0, Math.round(Number(record.durationMinutes) || 0)),
    timestamp: Number(record.timestamp) || Date.now(),
    source: record.source || 'chrome-extension',
    earnedMinutesCredited: Math.max(0, Math.round(Number(record.earnedMinutesCredited) || 0)),
    ...(record.projectName ? { projectName: String(record.projectName) } : {}),
  };
  args.date = self.FocusLockStore.todayKey(new Date(args.timestamp));
  args.tasksCompleted = 1;
  await queueMutation(identity.user.id, { id: `work:${recordId}`, kind: 'work', path: 'focus:recordWork', args });
  const auth = await authContext(identity.user.id, identity.session);
  if (!auth) return { signedIn: false, ok: false };
  await flushRequiredMutation(auth.userId, auth.authToken, `work:${recordId}`);
  return { signedIn: true, ok: true, id: recordId };
}

// Idempotent focus-session log (sessionId dedupes retries server-side).
async function logFocusSession(session, expectedAccountId) {
  const identity = await cloudClient();
  if (expectedAccountId && identity.user?.id !== expectedAccountId) return { signedIn: false, ok: false };
  if (!identity.session || !identity.user) return { signedIn: false, ok: false };
  const sessionId = session.sessionId || `focus_${crypto.randomUUID()}`;
  const args = {
    recordId: sessionId,
    title: String(session.title || 'Focus session'),
    durationMinutes: Math.max(0, Math.round(Number(session.durationMinutes) || 0)),
    timestamp: Number(session.timestamp) || Date.now(),
    source: session.source || 'chrome-extension',
    earnedMinutesCredited: Math.max(0, Math.round(Number(session.earnedMinutesCredited) || 0)),
  };
  args.date = self.FocusLockStore.todayKey(new Date(args.timestamp));
  args.tasksCompleted = 0;
  await queueMutation(identity.user.id, { id: `session:${sessionId}`, kind: 'session', path: 'focus:recordWork', args });
  const auth = await authContext(identity.user.id, identity.session);
  if (!auth) return { signedIn: false, ok: false };
  await flushRequiredMutation(auth.userId, auth.authToken, `session:${sessionId}`);
  return { signedIn: true, ok: true, id: sessionId };
}

// Upsert userPrefs. Callers omit optional fields to keep server defaults — never
// coerce an omitted field to a default, or a ratio-only push would wipe strictMode.
function finiteOrNull(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

async function savePrefs(prefs, expectedAccountId) {
  const identity = await cloudClient();
  if (expectedAccountId && identity.user?.id !== expectedAccountId) return { signedIn: false, ok: false };
  if (!identity.session || !identity.user) return { signedIn: false, ok: false };
  const args = { updatedAt: finiteOrNull(prefs.updatedAt) ?? Date.now() };
  if (prefs.strictMode != null) args.strictMode = Boolean(prefs.strictMode);
  if (prefs.strictEndsAt != null) args.strictEndsAt = finiteOrNull(prefs.strictEndsAt);
  if (prefs.strictPreset != null) args.strictPreset = String(prefs.strictPreset);
  if (prefs.strictNukeAfterFive != null) args.strictNukeAfterFive = Boolean(prefs.strictNukeAfterFive);
  if (prefs.weeklyReport != null) args.weeklyReport = Boolean(prefs.weeklyReport);
  const dailyReminder = finiteOrNull(prefs.dailyReminderMinutes);
  if (dailyReminder != null) args.dailyReminderMinutes = dailyReminder;
  const globalCap = finiteOrNull(prefs.globalDailyCapMinutes);
  if (globalCap != null) args.globalDailyCapMinutes = globalCap;
  // Cross-platform credit settings (last-writer-wins per field via *_UpdatedAt).
  const workRatio = finiteOrNull(prefs.workRatio);
  if (workRatio != null) args.workRatio = workRatio;
  const workRatioUpdatedAt = finiteOrNull(prefs.workRatioUpdatedAt);
  if (workRatioUpdatedAt != null) args.workRatioUpdatedAt = workRatioUpdatedAt;
  const taskBonusMinutes = finiteOrNull(prefs.taskBonusMinutes);
  if (taskBonusMinutes != null) args.taskBonusMinutes = taskBonusMinutes;
  const taskBonusMinutesUpdatedAt = finiteOrNull(prefs.taskBonusMinutesUpdatedAt);
  if (taskBonusMinutesUpdatedAt != null) args.taskBonusMinutesUpdatedAt = taskBonusMinutesUpdatedAt;
  await queueMutation(identity.user.id, { id: `prefs:${args.updatedAt}`, kind: 'prefs', path: 'focus:savePrefs', args });
  const auth = await authContext(identity.user.id, identity.session);
  if (!auth) return { signedIn: false, ok: false };
  await flushRequiredMutation(auth.userId, auth.authToken, `prefs:${args.updatedAt}`);
  return { signedIn: true, ok: true, id: args.updatedAt };
}

async function activateNuke() {
  const auth = await authContext();
  if (!auth) return { signedIn: false, ok: false };
  await callConvex('mutation', 'nuke:activate', {}, auth.authToken);
  await assertIdentity(auth.userId, auth.session);
  return { signedIn: true, ok: true };
}

async function guardian(message) {
  const auth = await authContext();
  if (!auth) return { signedIn: false, ok: false, error: 'Sign in to configure a trusted person.' };
  const kind = message.type;
  const args = kind === 'guardianSave' ? { email: String(message.email || '').trim() }
    : kind === 'guardianRequest' ? { strictSessionId: String(message.sessionId || ''), strictEndsAt: Number(message.endsAt) } : {};
  const path = kind === 'guardianSave' ? 'strictApproval:configureGuardian'
    : kind === 'guardianRequest' ? 'strictApproval:requestApprovalEmail' : 'strictApproval:getGuardian';
  const result = await callConvex(kind === 'guardianRequest' ? 'action' : kind === 'guardianSave' ? 'mutation' : 'query', path, args, auth.authToken);
  await assertIdentity(auth.userId, auth.session);
  return { ok: true, signedIn: true, result };
}

async function sharedRules(message) {
  const auth = await authContext();
  if (!auth) return { ok: false, signedIn: false, error: 'Sign in to manage account schedules and limits.' };
  const config = await callConvex('query', 'focus:getConfiguration', {}, auth.authToken);
  await assertIdentity(auth.userId, auth.session);
  if (message.type === 'sharedRulesGet') return { ok: true, config };
  if (config.prefs?.strictMode && (!config.prefs.strictEndsAt || config.prefs.strictEndsAt > Date.now())) throw new Error('Shared boundaries cannot change during Strict Mode.');
  const schedules = message.collection === 'schedules';
  const key = schedules ? 'schedules' : 'limits';
  const version = Number(config[schedules ? 'schedulesUpdatedAt' : 'limitsUpdatedAt']) || 0;
  if (Number(message.version) !== version) throw new Error('Another device changed these rules. Refresh and review before saving.');
  const fields = schedules ? ['scheduleId', 'label', 'targetKind', 'targetKey', 'days', 'startMinute', 'endMinute', 'isEnabled']
    : ['targetKind', 'targetKey', 'label', 'dailyLimitMinutes', 'sessionLimitMinutes', 'isBlockedNow'];
  const clean = row => Object.fromEntries(fields.filter(field => row[field] !== undefined).map(field => [field, row[field]]));
  const same = row => schedules ? row.scheduleId === message.row?.scheduleId
    : row.targetKind === message.row?.targetKind && row.targetKey === message.row?.targetKey;
  const rows = (config[key] || []).filter(row => !same(row)).map(clean);
  if (message.remove !== true) rows.push(clean(message.row || {}));
  // The API advances a changed collection to at least storedVersion + 1.
  // Supplying its read version rejects concurrent replacements after that read.
  const result = await callConvex('mutation', schedules ? 'focus:saveSchedules' : 'focus:saveAppLimits',
    { [key]: rows, updatedAt: version }, auth.authToken);
  await assertIdentity(auth.userId, auth.session);
  if (result?.applied === false) throw new Error('Another device changed these rules. Refresh and review before saving.');
  return { ok: true };
}

self.FocusLockCloud = {
  startLivePolicy, ensureLivePolicy, livePolicyStatus,
  syncUsage, getSnapshot, signOut, status, refreshAuth, isAuthUrl,
  getDashboard, addWorkRecord, logFocusSession, savePrefs, saveGroups, setWebsiteBlocked, activateNuke, guardian, sharedRules,
};
