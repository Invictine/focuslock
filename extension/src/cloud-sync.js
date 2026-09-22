import { createClerkClient } from '@clerk/chrome-extension/client';

const publishableKey = process.env.CLERK_PUBLISHABLE_KEY;
const syncHost = process.env.CLERK_SYNC_HOST;
const convexUrl = process.env.CONVEX_URL.replace(/\/$/, '');
const META_KEY = 'focuslock.cloud.v2';
const ACCOUNT_STATE_KEY = 'focuslock.cloud.accounts.v1';
let clerkPromise;
let outboxLock = Promise.resolve();
let metaWriteLock = Promise.resolve();

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

async function token() {
  const auth = await authContext();
  return auth?.authToken || null;
}

async function callConvex(kind, path, args, authToken) {
  const response = await fetch(`${convexUrl}/api/${kind}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${authToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, args }),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.status !== 'success') {
    throw new Error(payload.errorMessage || `Sync returned HTTP ${response.status}`);
  }
  return payload.value;
}

async function meta(userId = 'anonymous') {
  const read = async () => {
    const stored = await chrome.storage.local.get(META_KEY);
    const root = stored[META_KEY] || { version: 2, accounts: {} };
    if (!root.accounts || typeof root.accounts !== 'object') root.accounts = {};
    if (!root.accounts[userId]) {
      const legacy = root.deviceId && userId !== 'anonymous' ? root : null;
      root.accounts[userId] = {
        deviceId: legacy?.deviceId || `browser_${crypto.randomUUID()}`,
        lastSyncAt: legacy?.lastSyncAt || 0,
        lastError: legacy?.lastError || '',
        pendingBuckets: legacy?.pendingBuckets || [],
        pendingMutations: legacy?.pendingMutations || [],
        uploadedBuckets: legacy?.uploadedBuckets || [],
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
    return root.accounts[userId];
  };
  const run = metaWriteLock.then(read, read);
  metaWriteLock = run.catch(() => {});
  return run;
}

async function saveMeta(patch, userId = 'anonymous') {
  const write = async () => {
    const stored = await chrome.storage.local.get(META_KEY);
    const root = stored[META_KEY] || { version: 2, accounts: {} };
    root.accounts = root.accounts || {};
    if (!root.accounts[userId]) {
      root.accounts[userId] = {
        deviceId: `browser_${crypto.randomUUID()}`,
        lastSyncAt: 0,
        lastError: '',
        pendingBuckets: [],
        pendingMutations: [],
        uploadedBuckets: [],
      };
    }
    root.accounts[userId] = { ...root.accounts[userId], ...patch };
    await chrome.storage.local.set({ [META_KEY]: root });
    return root.accounts[userId];
  };
  const run = metaWriteLock.then(write, write);
  metaWriteLock = run.catch(() => {});
  return run;
}

function scopedState(state) {
  return {
    stats: state.stats || {},
    blockedLog: state.blockedLog || [],
    blockedTotal: state.blockedTotal || 0,
    cloudSites: state.cloudSites || [],
    cloudSitesLoaded: Boolean(state.cloudSitesLoaded),
  };
}

async function switchAccountState(state, userId) {
  const currentUserId = state.cloudAccountId || '';
  if (currentUserId === userId) return;
  const stored = await chrome.storage.local.get(ACCOUNT_STATE_KEY);
  const snapshots = stored[ACCOUNT_STATE_KEY] && typeof stored[ACCOUNT_STATE_KEY] === 'object'
    ? stored[ACCOUNT_STATE_KEY] : {};
  if (currentUserId) snapshots[currentUserId] = scopedState(state);
  const target = snapshots[userId];
  if (target) Object.assign(state, target);
  else if (currentUserId) Object.assign(state, { stats: {}, blockedLog: [], blockedTotal: 0, cloudSites: [], cloudSitesLoaded: false });
  state.cloudAccountId = userId;
  await chrome.storage.local.set({ [ACCOUNT_STATE_KEY]: snapshots });
  await self.FocusLockStore.save(state);
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
      if (!(item.kind === 'website' && value && value.applied === false)) acknowledged.push(item);
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
  return acknowledged;
}

async function syncUsage(state, reason) {
  const identity = await cloudClient();
  if (!identity.session || !identity.user) return { signedIn: false, ok: false, error: '' };
  const expectedUserId = identity.user.id;
  const expectedSession = identity.session;
  await switchAccountState(state, expectedUserId);
  const device = await meta(expectedUserId);
  const now = Date.now();
  const buckets = bucketsFor(state);
  const previous = await meta(expectedUserId);
  const uploaded = previous.uploadedBuckets || [];
  const uploadedByKey = new Map(uploaded.map((bucket) => [`${bucket.date}\u001f${bucket.targetKind}\u001f${bucket.targetKey}`, bucket]));
  const changed = buckets.filter((bucket) => {
    const old = uploadedByKey.get(`${bucket.date}\u001f${bucket.targetKind}\u001f${bucket.targetKey}`);
    return !old || Number(old.trackedSeconds) !== Number(bucket.trackedSeconds);
  });
  const oldPending = previous.pendingBuckets || [];
  // Queue before requesting a token. If the browser is offline or Clerk's
  // session refresh fails, the absolute counters remain durable for retry.
  await saveMeta({ pendingBuckets: mergeBuckets(oldPending, changed) }, expectedUserId);
  try {
    const auth = await authContext(expectedUserId, expectedSession);
    await callConvex('mutation', 'devices:heartbeat', {
      deviceId: device.deviceId,
      name: 'Chrome extension',
      platform: 'browser',
      appVersion: chrome.runtime.getManifest().version,
      trackingStatus: 'active',
      statusDetail: reason ? `Last ${reason} sync completed` : undefined,
      lastSeen: now,
    }, auth.authToken);
    const pending = (await meta(auth.userId)).pendingBuckets || buckets;
    for (let offset = 0; offset < pending.length; offset += 500) {
      await callConvex('mutation', 'usage:recordUsageBatch', {
        deviceId: device.deviceId,
        buckets: pending.slice(offset, offset + 500),
      }, auth.authToken);
    }
    await flushMutationOutbox(auth.userId, auth.authToken);
    const shared = await callConvex('query', 'focus:getSnapshot', {}, auth.authToken);
    await saveMeta({ lastSyncAt: now, lastError: '', pendingBuckets: [], uploadedBuckets: mergeBuckets(uploaded, pending) }, auth.userId);
    return { signedIn: true, ok: true, lastSyncAt: now, sites: shared.sites || [], prefs: shared.prefs || null };
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
  await flushMutationOutbox(auth.userId, auth.authToken);
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
    user: clerk.user ? { id: clerk.user.id, email: clerk.user.primaryEmailAddress?.emailAddress || '' } : null,
    devices: devices || [],
    summary,
    syncResult,
    ...(await meta(auth.userId)),
  };
}

async function signOut() {
  const clerk = await cloudClient();
  await clerk.signOut();
  return { ok: true };
}

// Lightweight local-only status for protection checklists (no network round-trip).
async function status() {
  const clerk = await cloudClient();
  const m = await meta(clerk.user?.id || 'anonymous');
  return {
    signedIn: Boolean(clerk.session),
    lastSyncAt: Number(m.lastSyncAt) || 0,
    lastError: m.lastError || '',
    deviceId: m.deviceId || '',
  };
}

// Focus dashboard snapshot (leisure balance, records, sessions, prefs, devices).
async function getDashboard(fromDate, toDate) {
  const authToken = await token();
  if (!authToken) return { signedIn: false, dashboard: null };
  const args = {};
  if (fromDate) args.fromDate = fromDate;
  if (toDate) args.toDate = toDate;
  const dashboard = await callConvex('query', 'focus:getDashboard', args, authToken);
  return { signedIn: true, dashboard };
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
  await queueMutation(identity.user.id, { id: `work:${recordId}`, kind: 'work', path: 'focus:addWorkRecord', args });
  const auth = await authContext(identity.user.id, identity.session);
  if (!auth) return { signedIn: false, ok: false };
  await flushMutationOutbox(auth.userId, auth.authToken);
  return { signedIn: true, ok: true, id: recordId };
}

// Idempotent focus-session log (sessionId dedupes retries server-side).
async function logFocusSession(session) {
  const identity = await cloudClient();
  if (!identity.session || !identity.user) return { signedIn: false, ok: false };
  const sessionId = session.sessionId || `focus_${crypto.randomUUID()}`;
  const args = {
    sessionId,
    title: String(session.title || 'Focus session'),
    durationMinutes: Math.max(0, Math.round(Number(session.durationMinutes) || 0)),
    timestamp: Number(session.timestamp) || Date.now(),
    source: session.source || 'chrome-extension',
    earnedMinutesCredited: Math.max(0, Math.round(Number(session.earnedMinutesCredited) || 0)),
  };
  await queueMutation(identity.user.id, { id: `session:${sessionId}`, kind: 'session', path: 'focus:logFocusSession', args });
  const auth = await authContext(identity.user.id, identity.session);
  if (!auth) return { signedIn: false, ok: false };
  await flushMutationOutbox(auth.userId, auth.authToken);
  return { signedIn: true, ok: true, id: sessionId };
}

// Upsert userPrefs. Callers omit optional fields to keep server defaults — never
// coerce an omitted field to a default, or a ratio-only push would wipe strictMode.
function finiteOrNull(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

async function savePrefs(prefs) {
  const identity = await cloudClient();
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
  await flushMutationOutbox(auth.userId, auth.authToken);
  return { signedIn: true, ok: true, id: args.updatedAt };
}

async function activateNuke() {
  const authToken = await token();
  if (!authToken) return { signedIn: false, ok: false };
  await callConvex('mutation', 'nuke:activate', {}, authToken);
  return { signedIn: true, ok: true };
}

self.FocusLockCloud = {
  syncUsage, getSnapshot, signOut, status, refreshAuth,
  getDashboard, addWorkRecord, logFocusSession, savePrefs, setWebsiteBlocked, activateNuke,
};
