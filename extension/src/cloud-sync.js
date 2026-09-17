import { createClerkClient } from '@clerk/chrome-extension/client';

const publishableKey = process.env.CLERK_PUBLISHABLE_KEY;
const convexUrl = process.env.CONVEX_URL.replace(/\/$/, '');
const META_KEY = 'focuslock.cloud.v1';
let clerkPromise;

function cloudClient() {
  if (!clerkPromise) clerkPromise = createClerkClient({ publishableKey, background: true });
  return clerkPromise;
}

async function token() {
  const clerk = await cloudClient();
  if (!clerk.session) return null;
  return clerk.session.getToken({ template: 'convex' });
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

async function meta() {
  const stored = await chrome.storage.local.get(META_KEY);
  if (stored[META_KEY]) return stored[META_KEY];
  const value = { deviceId: `browser_${crypto.randomUUID()}`, lastSyncAt: 0, lastError: '' };
  await chrome.storage.local.set({ [META_KEY]: value });
  return value;
}

async function saveMeta(patch) {
  const value = { ...(await meta()), ...patch };
  await chrome.storage.local.set({ [META_KEY]: value });
  return value;
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
  return buckets.slice(-500);
}

async function syncUsage(state, reason) {
  const authToken = await token();
  if (!authToken) return { signedIn: false, ok: false, error: '' };
  const device = await meta();
  const now = Date.now();
  try {
    await callConvex('mutation', 'devices:heartbeat', {
      deviceId: device.deviceId,
      name: 'Chrome extension',
      platform: 'browser',
      appVersion: chrome.runtime.getManifest().version,
      trackingStatus: 'active',
      statusDetail: reason ? `Last ${reason} sync completed` : undefined,
      lastSeen: now,
    }, authToken);
    const buckets = bucketsFor(state);
    if (buckets.length) {
      await callConvex('mutation', 'usage:recordUsageBatch', { deviceId: device.deviceId, buckets }, authToken);
    }
    const shared = await callConvex('query', 'focus:getSnapshot', {}, authToken);
    await saveMeta({ lastSyncAt: now, lastError: '' });
    return { signedIn: true, ok: true, lastSyncAt: now, sites: shared.sites || [] };
  } catch (error) {
    await saveMeta({ lastError: error?.message || 'Sync failed' });
    throw error;
  }
}

// A single-site mutation avoids replacing another device's entire boundaries
// collection with a stale extension snapshot.
async function setWebsiteBlocked(domain, isBlocked) {
  const authToken = await token();
  if (!authToken) return { signedIn: false, ok: false };
  const normalized = String(domain || '').trim().toLowerCase().replace(/^www\./, '');
  if (!normalized) throw new Error('Choose a website first');
  const value = await callConvex('mutation', 'focus:setBlockedWebsite', {
    domain: normalized, displayName: normalized, isBlocked: Boolean(isBlocked),
    category: 'Web', updatedAt: Date.now(),
  }, authToken);
  return { signedIn: true, ok: true, value };
}

async function getSnapshot(state, shouldSync) {
  const clerk = await cloudClient();
  const authToken = await token();
  if (!authToken) return { signedIn: false, devices: [], summary: null, ...(await meta()) };
  let syncResult = null;
  if (shouldSync) syncResult = await syncUsage(state, 'manual');
  const today = self.FocusLockStore.todayKey();
  const [devices, summary] = await Promise.all([
    callConvex('query', 'devices:listDevices', {}, authToken),
    callConvex('query', 'usage:getUsageSummary', { fromDate: today, toDate: today }, authToken),
  ]);
  return {
    signedIn: Boolean(clerk.session),
    user: clerk.user ? { id: clerk.user.id, email: clerk.user.primaryEmailAddress?.emailAddress || '' } : null,
    devices: devices || [],
    summary,
    syncResult,
    ...(await meta()),
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
  const m = await meta();
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
  const authToken = await token();
  if (!authToken) return { signedIn: false, ok: false };
  const value = await callConvex('mutation', 'focus:addWorkRecord', {
    recordId: record.recordId || `work_${crypto.randomUUID()}`,
    title: String(record.title || 'Work'),
    durationMinutes: Math.max(0, Math.round(Number(record.durationMinutes) || 0)),
    timestamp: Number(record.timestamp) || Date.now(),
    source: record.source || 'chrome-extension',
    earnedMinutesCredited: Math.max(0, Math.round(Number(record.earnedMinutesCredited) || 0)),
    ...(record.projectName ? { projectName: String(record.projectName) } : {}),
  }, authToken);
  return { signedIn: true, ok: true, id: value };
}

// Idempotent focus-session log (sessionId dedupes retries server-side).
async function logFocusSession(session) {
  const authToken = await token();
  if (!authToken) return { signedIn: false, ok: false };
  const value = await callConvex('mutation', 'focus:logFocusSession', {
    sessionId: session.sessionId || `focus_${crypto.randomUUID()}`,
    title: String(session.title || 'Focus session'),
    durationMinutes: Math.max(0, Math.round(Number(session.durationMinutes) || 0)),
    timestamp: Number(session.timestamp) || Date.now(),
    source: session.source || 'chrome-extension',
    earnedMinutesCredited: Math.max(0, Math.round(Number(session.earnedMinutesCredited) || 0)),
  }, authToken);
  return { signedIn: true, ok: true, id: value };
}

// Upsert userPrefs. Callers omit optional fields to keep server defaults — never
// coerce an omitted field to a default, or a ratio-only push would wipe strictMode.
function finiteOrNull(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

async function savePrefs(prefs) {
  const authToken = await token();
  if (!authToken) return { signedIn: false, ok: false };
  const args = { updatedAt: finiteOrNull(prefs.updatedAt) ?? Date.now() };
  if (prefs.strictMode != null) args.strictMode = Boolean(prefs.strictMode);
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
  const value = await callConvex('mutation', 'focus:savePrefs', args, authToken);
  return { signedIn: true, ok: true, id: value };
}

self.FocusLockCloud = {
  syncUsage, getSnapshot, signOut, status,
  getDashboard, addWorkRecord, logFocusSession, savePrefs, setWebsiteBlocked,
};
