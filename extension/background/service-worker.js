/* FocusLock service worker — tracking + blocking engine (Cold Turkey core). */
importScripts('../src/matcher.js', '../src/features.js', '../src/store.js', '../src/policy.js', '../dist/cloud-sync.js');

const M = self.FocusLockMatcher;
const Store = self.FocusLockStore;
const storageAccessReady = chrome.storage.local.setAccessLevel
  ? chrome.storage.local.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' })
  : Promise.resolve();

let mem = {
  state: null,
  cur: { tabId: -1, url: '', since: 0 },
  focused: false,
  lastEnforced: new Map(), // tabId -> last URL a verdict was delivered for
  lastAttempt: new Map(), // tabId -> { url, at }, deduplicates duplicate browser events
  lastFlushedDay: null,    // JSON of today's stats map at the last persist
};
let syncInFlight = null;
let stateLoading = null;
let trackingQueue = Promise.resolve();
let lastCloudRefreshAttemptAt = 0;
const CLOUD_VERDICT_MAX_AGE_MS = 60 * 1000;
const ACTIVE_POLICY_MAX_AGE_MS = 60 * 1000;
const CURSOR_KEY = 'focuslock.sessionCursor';
let featureQueue = Promise.resolve();
function featureSerial(task) {
  const result = featureQueue.then(task, task);
  featureQueue = result.catch(() => {});
  return result;
}

async function featureWrite(mutator) {
  const saved = await Store.update(mutator);
  mem.state = saved;
  return saved;
}
async function recheckWebsites() {
  const tabs = await chrome.tabs.query({});
  await Promise.allSettled(tabs.filter(tab => tab.id >= 0 && /^https?:/.test(tab.url || ''))
    .map(tab => enforceTab(tab.id, tab.url)));
}
async function commitStrict(message) {
  return featureSerial(() => commitStrictNow(message));
}
async function commitStrictNow(message) {
    await syncCloud('edit');
    let status;
    try { status = await self.FocusLockCloud.status(); } catch (_) { status = { signedIn: false, accountId: '' }; }
    const now = nowMs();
    let end;
    const saved = await featureWrite(state => {
      end = self.FocusLockFeatures.strictEnd(state, message.endsAt, now);
      const wasActive = strictIsActive(state, now);
      const origin = wasActive ? state.strictOriginAccountId || state.strictPending?.accountId || '' : status.signedIn ? status.accountId || '' : '';
      state.strictOriginAccountId = origin;
      state.strictMode = true; state.strictEndsAt = end;
      state.strictPreset = ['hours', 'days', 'date', 'weekly'].includes(message.preset) ? message.preset : 'custom';
      state.strictNukeAfterFive = wasActive ? state.strictNukeAfterFive : message.nukeAfterFive === true;
      if (!wasActive) { state.strictAttempts = 0; state.strictSessionKey = `${now}:${end}`; }
      state.strictPending = origin ? { accountId: origin,
        prefs: { strictMode: true, strictEndsAt: end, strictPreset: state.strictPreset,
          strictNukeAfterFive: state.strictNukeAfterFive, updatedAt: now } } : null;
      return state;
    });
    let synced = false, error = '';
    if (saved.strictPending) {
      try { synced = await uploadStrict(saved.strictPending); }
      catch (e) { error = e?.message || 'Account sync is waiting. Your Chrome commitment is active.'; }
    }
    await recheckWebsites();
    return { ok: true, synced, localOnly: !saved.strictOriginAccountId, endsAt: end, error };
}
async function uploadStrict(pending) {
  const status = await self.FocusLockCloud.status();
  if (!status.signedIn || status.accountId !== pending.accountId) return false;
  const result = await self.FocusLockCloud.savePrefs(pending.prefs, pending.accountId);
  if (!result?.ok) return false;
  await featureWrite(state => {
    if (state.strictPending?.prefs?.updatedAt === pending.prefs.updatedAt) state.strictPending = null;
    return state;
  });
  await syncCloud('edit');
  return true;
}
async function changeFrog(message) {
  return featureSerial(async () => {
    const saved = await featureWrite(state => {
      let frog = self.FocusLockFeatures.frogState(state.browserFrog);
      if (message.type === 'frogConfigure') {
        if (frog.locked) throw new Error('Finish today’s Frog before changing its settings.');
        const required = Number(message.requiredMinutes), wake = Number(message.wakeHour);
        if (!Number.isInteger(required) || required < 1 || required > 480 || !Number.isInteger(wake) || wake < 0 || wake > 23) throw new Error('Choose 1–480 minutes and a wake hour from 0–23.');
        frog = self.FocusLockFeatures.frogState({ ...frog, enabled: message.enabled === true,
          requiredSeconds: required * 60, wakeHour: wake });
      } else if (message.type === 'frogSelect') {
        const title = String(message.title || '').trim();
        if (!frog.enabled || !frog.armed) throw new Error('Enable Eat the Frog at or after your wake hour first.');
        if (!frog.locked) throw new Error('Today’s Frog is already complete. Choose another tomorrow.');
        if (!title || title.length > 200) throw new Error('Enter a task title of up to 200 characters.');
        if (frog.frog && state.focusTimer) throw new Error('Finish the running focus session before changing your task.');
        frog.frog = { id: Store.uid('frog'), title }; frog.trackedSeconds = 0; frog.tickedOff = false;
      } else {
        if (!frog.frog) throw new Error('Choose your Frog task first.');
        frog.tickedOff = message.tickedOff === true;
      }
      state.browserFrog = self.FocusLockFeatures.frogState(frog);
      return state;
    });
    await recheckWebsites();
    return { ok: true, frog: saved.browserFrog };
  });
}
async function startFocusTimer(message) {
  return featureSerial(async () => {
    const minutes = Number(message.minutes);
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 480) throw new Error('Choose a focus duration from 1–480 minutes.');
    let status;
    try { status = await self.FocusLockCloud.status(); } catch (_) { status = { signedIn: false, accountId: '' }; }
    const saved = await featureWrite(state => {
      if (state.focusTimer) throw new Error('A focus session is already running.');
      const frog = self.FocusLockFeatures.frogState(state.browserFrog);
      state.focusTimer = { id: Store.uid('focus'), startedAt: nowMs(), targetMinutes: minutes,
        accountId: status.signedIn ? status.accountId || '' : '',
        ratio: Math.max(1, Math.min(20, Number(state.cloudPrefs?.workRatio) || Number(message.ratio) || 4)),
        frogId: frog.locked ? frog.frog?.id || '' : '', frogCycle: frog.cycleDate,
        title: frog.locked && frog.frog ? frog.frog.title : `Chrome focus ${minutes}m` };
      return state;
    });
    await chrome.alarms.create('focuslock-focus', { when: saved.focusTimer.startedAt + minutes * 60000 });
    return { ok: true, timer: saved.focusTimer };
  });
}
async function finishFocusTimer(completed) {
  return featureSerial(() => finishFocusTimerNow(completed));
}
async function finishFocusTimerNow(completed) {
  const current = (await ensureState()).focusTimer;
  if (!current) return { ok: true, minutes: 0 };
  const seconds = self.FocusLockFeatures.elapsed(current);
  if (completed && seconds < current.targetMinutes * 60) return { ok: false };
  const minutes = Math.floor(seconds / 60);
  await featureWrite(state => {
    if (state.focusTimer?.id !== current.id) return state;
    const frog = self.FocusLockFeatures.frogState(state.browserFrog);
    if (frog.frog?.id === current.frogId && frog.cycleDate === current.frogCycle && frog.locked) frog.trackedSeconds += seconds;
    state.browserFrog = self.FocusLockFeatures.frogState(frog);
    state.focusTimer = null;
    if (minutes >= 5 && current.accountId) {
      state.pendingFocusSessions = state.pendingFocusSessions || [];
      if (!state.pendingFocusSessions.some(row => row.id === current.id)) state.pendingFocusSessions.push({
        id: current.id, accountId: current.accountId,
        session: { sessionId: current.id, title: current.title, durationMinutes: minutes,
          timestamp: current.startedAt, source: 'chrome-extension', earnedMinutesCredited: Math.max(1, Math.floor(minutes / current.ratio)) } });
    }
    return state;
  });
  try { await uploadFocusSessions(); } catch (_) { /* durable account-bound pending record remains */ }
  return { ok: true, minutes, localOnly: !current.accountId, pending: (await ensureState()).pendingFocusSessions?.some(row => row.id === current.id) || false };
}
async function uploadFocusSessions() {
  const status = await self.FocusLockCloud.status();
  if (!status.signedIn || !status.accountId) return;
  for (const row of (await ensureState()).pendingFocusSessions || []) {
    if (row.accountId !== status.accountId) continue;
    const result = await self.FocusLockCloud.logFocusSession(row.session, row.accountId);
    if (!result?.ok) break;
    await featureWrite(state => { state.pendingFocusSessions = state.pendingFocusSessions.filter(item => item.id !== row.id); return state; });
  }
}
async function maintainFeatures(upload = true) {
  if (!self.FocusLockFeatures) return;
  return featureSerial(async () => {
    const state = await ensureState();
    const window = self.FocusLockFeatures.weeklyWindow(state.strictWeekly);
    if (window && state.strictWeekly.lastWindow !== window.key) {
      await featureWrite(state => { state.strictWeekly.lastWindow = window.key; return state; });
      const current = await ensureState();
      if (!strictIsActive(current) || (current.strictEndsAt && current.strictEndsAt < window.endsAt)) {
        await commitStrictNow({ endsAt: window.endsAt, preset: 'weekly', nukeAfterFive: false });
      }
    }
    if (state.focusTimer && self.FocusLockFeatures.elapsed(state.focusTimer) >= state.focusTimer.targetMinutes * 60) await finishFocusTimerNow(true);
    const frog = self.FocusLockFeatures.frogState((await ensureState()).browserFrog);
    if (JSON.stringify(frog) !== JSON.stringify((await ensureState()).browserFrog)) await featureWrite(state => { state.browserFrog = frog; return state; });
    if (!upload) return;
    try {
      const pending = (await ensureState()).strictPending;
      if (pending) await uploadStrict(pending);
      await uploadFocusSessions();
    } catch (_) { /* pending records remain durable and retry on reconnect/maintenance */ }
  });
}

// ---------- helpers ----------
function nowMs() { return Date.now(); }

function strictIsActive(state, t) {
  return Boolean(state.strictMode) && (!state.strictEndsAt || state.strictEndsAt > (t || nowMs()));
}

function hhmmToMin(s) {
  const [h, m] = String(s || '0:0').split(':').map(Number);
  return (h || 0) * 60 + (m || 0);
}

function inRecurring(sch) {
  const d = new Date();
  const cur = d.getHours() * 60 + d.getMinutes();
  const a = hhmmToMin(sch.start), b = hhmmToMin(sch.end);
  if (!Array.isArray(sch.days)) return false;
  if (a === b) return sch.days.includes(d.getDay());
  if (a < b) return sch.days.includes(d.getDay()) && cur >= a && cur < b;
  // The after-midnight tail belongs to the day on which the overnight block began.
  const previousDay = (d.getDay() + 6) % 7;
  return cur >= a ? sch.days.includes(d.getDay()) : cur < b && sch.days.includes(previousDay);
}

function scheduleActive(sch, t) {
  t = t || nowMs();
  if (sch.type === 'recurring') return inRecurring(sch);
  if (sch.type === 'timer' || sch.type === 'frozen') return t >= sch.startTs && t < sch.endTs;
  if (sch.type === 'pomodoro') return t >= sch.startTs && t < sch.endTs;
  return false;
}

function listIsLocked(list, t) {
  return list.lockedUntil && list.lockedUntil > (t || nowMs());
}

function listIsActive(list, state, t) {
  t = t || nowMs();
  if (!list.enabled) return { active: false, reason: 'disabled' };
  if (listIsLocked(list, t)) return { active: true, reason: 'frozen-lock' };
  const attached = state.schedules.filter(s => s.listId === list.id);
  if (attached.length === 0) {
    return list.alwaysOn ? { active: true, reason: 'always-on' } : { active: false, reason: 'no-schedule' };
  }
  const hit = attached.find(s => scheduleActive(s, t));
  if (hit) return { active: true, reason: 'schedule:' + hit.type, schedule: hit };
  return { active: false, reason: 'outside-schedule' };
}

function domainAllowedBySnooze(state, domain, t) {
  return state.snoozes && state.snoozes[domain] && state.snoozes[domain] > (t || nowMs());
}

// Core verdict — mirrors Cold Turkey: Permalock > nuclear > whitelist > blacklist > daily limits
function verdictFor(urlStr, state, t) {
  t = t || nowMs();
  if (!urlStr || M.isInternalUrl(urlStr)) return { blocked: false };
  const authUrl = Boolean(self.FocusLockCloud && self.FocusLockCloud.isAuthUrl && self.FocusLockCloud.isAuthUrl(urlStr));
  let domain = '';
  try { domain = new URL(urlStr).hostname.toLowerCase(); } catch (e) { return { blocked: false }; }
  const shortDomain = M.domainOf(urlStr);

  // Permalock is the highest-priority verdict. It is device-local and
  // unconditional: snoozes, exceptions, schedules, daily limits, Strict Mode,
  // and Nuclear (local or shared) can never lift it.
  if (M.matchesAny(urlStr, state.permanentSites || [])) {
    return { blocked: true, mode: 'permanent', listId: '__permanent', listName: 'Permanent block', reason: 'permanent' };
  }

  // Active account boundaries are enforced independently of Strict Mode state.
  if (state.cloudNuke?.isActive && !authUrl) {
    return { blocked: true, mode: 'shared-nuke', listId: '__shared_nuclear', listName: 'Shared Nuclear Block', reason: 'shared-nuclear' };
  }

  // Account boundaries are enforced even when they were edited on Android or
  // Windows. Keep local extension lists as additional browser-only rules.
  const sharedSite = (state.cloudSites || []).find((site) =>
    site.isBlocked && self.FocusLockMatcher.matchesAny(urlStr, [site.domain]));
  const frog = self.FocusLockFeatures?.frogState(state.browserFrog, t);
  if (frog?.locked && !authUrl && (sharedSite || state.lists.some(list => list.enabled
      && !(state.cloudSitesLoaded && ['list_social', 'list_video'].includes(list.id))
      && !M.matchesAny(urlStr, list.exceptions || [])
      && (list.mode === 'whitelist' ? !M.matchesAny(urlStr, list.sites) : M.matchesAny(urlStr, list.sites))))) {
    return { blocked: true, mode: 'frog', listId: '__frog', listName: 'Eat the Frog', reason: 'frog' };
  }
  const policyVerdict = self.FocusLockPolicy?.verdict(urlStr, state, t);
  if (policyVerdict) return policyVerdict;
  if (!state.cloudPolicy && sharedSite && !domainAllowedBySnooze(state, shortDomain, t)) {
    return { blocked: true, mode: 'blacklist', listId: '__shared', listName: 'Shared boundaries', reason: 'account' };
  }

  // Today's per-domain seconds, read once per verdict so daily-limit checks
  // don't re-lookup/re-iterate the stats map for every list.
  const day = state.stats[Store.todayKey(new Date(t))] || {};

  // Nuclear: block everything except allow-list
  if (state.nuclear.active && state.nuclear.until > t && !authUrl && !M.matchesAny(urlStr, state.nuclear.allow || [])) {
    return { blocked: true, mode: 'nuclear', listId: '__nuclear', listName: 'Nuclear Block', reason: 'nuclear' };
  }

  for (const list of state.lists) {
    // The two seed lists are onboarding defaults. Once account boundaries have
    // loaded, the shared website list owns those choices, including unblocks.
    if (state.cloudSitesLoaded && (list.id === 'list_social' || list.id === 'list_video')) continue;
    const st = listIsActive(list, state, t);
    if (!st.active) continue;
    if (!listIsLocked(list, t) && domainAllowedBySnooze(state, shortDomain, t)) continue;

    if (list.mode === 'whitelist') {
      // allow-only: block unless URL is in the allowed sites
      if (!M.matchesAny(urlStr, list.sites)) {
        if (M.matchesAny(urlStr, list.exceptions || [])) continue;
        return { blocked: true, mode: 'whitelist', listId: list.id, listName: list.name, reason: st.reason, schedule: st.schedule };
      }
      continue;
    }
    // blacklist
    const matched = M.matchesAny(urlStr, list.sites);
    if (matched && !M.matchesAny(urlStr, list.exceptions || [])) {
      if (list.dailyLimitMin > 0) {
        const used = minutesUsedForDay(day, list.sites);
        if (used >= list.dailyLimitMin) {
          return { blocked: true, mode: 'daily-limit', listId: list.id, listName: list.name, reason: 'daily-limit', schedule: st.schedule };
        }
        continue; // daily allowance permits the matched site until the budget is used
      }
      return { blocked: true, mode: 'blacklist', listId: list.id, listName: list.name, reason: st.reason, schedule: st.schedule };
    }
  }
  return { blocked: false };
}

// Sum today's tracked seconds for domains matching a list's patterns. Patterns
// are compiled ONCE per call instead of once per probed domain (the old path
// recompiled the whole pattern list for every domain in today's stats).
function minutesUsedForDay(day, sites) {
  const compiled = M.compileList(sites);
  let secs = 0;
  for (const [domain, s] of Object.entries(day)) {
    const probe = 'https://' + domain + '/';
    for (const c of compiled) {
      try { if (c.test(probe)) { secs += s; break; } } catch (e) { /* ignore */ }
    }
  }
  return secs / 60;
}

function minutesUsedToday(state, list, t) {
  const key = Store.todayKey(new Date(t || nowMs()));
  return minutesUsedForDay(state.stats[key] || {}, list.sites);
}

// ---------- tracking ----------
async function ensureState() {
  if (stateLoading) return stateLoading;
  if (mem.state) return mem.state;
  stateLoading = (async () => {
    await storageAccessReady;
    mem.state = await Store.load();
    mem.lastFlushedDay = null; // unknown what's on disk — force next flush to persist
    // storage.session survives MV3 worker restarts but is cleared at browser exit.
    try {
      const [saved, windowInfo, tabs] = await Promise.all([
        chrome.storage.session.get(CURSOR_KEY), chrome.windows.getLastFocused(),
        chrome.tabs.query({ active: true, lastFocusedWindow: true }),
      ]);
      const tab = tabs[0];
      mem.focused = Boolean(windowInfo && windowInfo.focused === true);
      if (tab) {
        const cursor = saved && saved[CURSOR_KEY];
        const canResume = mem.focused && cursor && cursor.focused === true && cursor.tabId === tab.id &&
          cursor.url === (tab.url || '') && Number.isFinite(cursor.since) && cursor.since > 0;
        const checkpoint = mem.state.trackingCheckpoint;
        const accountedThrough = checkpoint?.tabId === tab.id && checkpoint.url === tab.url
          ? Number(checkpoint.through) || 0 : 0;
        mem.cur = { tabId: tab.id, url: tab.url || '',
          since: canResume ? Math.min(nowMs(), Math.max(cursor.since, accountedThrough)) : nowMs() };
      } else {
        mem.cur = { tabId: -1, url: '', since: nowMs() };
      }
      await persistCursor();
    } catch (e) { /* tab access may be unavailable during shutdown */ }
    return mem.state;
  })();
  try { return await stateLoading; } finally { stateLoading = null; }
}

function trackSerial(task) {
  const next = trackingQueue.then(task, task);
  trackingQueue = next.catch(() => {});
  return next;
}

function flushActiveSlice(t) { return trackSerial(() => flushActiveSliceNow(t)); }

async function flushActiveSliceNow(t) {
  const state = await ensureState();
  t = t || nowMs();
  const { url, since } = mem.cur;
  if (!url || !since || !mem.focused) { mem.cur.since = t; await persistCursor(); return; }
  const idleOk = await new Promise(res => {
    try { chrome.idle.queryState(state.settings.idleTimeoutSec || 60, res); }
    catch (e) { res('active'); }
  });
  const secs = Math.max(0, (t - since) / 1000);
  const domain = M.domainOf(url);
  if (idleOk !== 'active' || secs <= 0 || secs > 3600 || !/^https?:/i.test(url) || !domain) {
    mem.cur.since = t; await persistCursor(); return;
  }
  // Split a slice crossing local midnight instead of charging yesterday to today.
  const stats = structuredClone(state.stats);
  const leisureStats = structuredClone(state.leisureStats || {});
  const spendsCredit = !verdictFor(url, state, since).blocked && self.FocusLockPolicy?.isLeisure(url, state, since);
  let from = since;
  while (from < t) {
    const nextMidnight = new Date(from);
    nextMidnight.setHours(24, 0, 0, 0);
    const until = Math.min(t, nextMidnight.getTime());
    const key = Store.todayKey(new Date(from));
    stats[key] = stats[key] || {};
    stats[key][domain] = (stats[key][domain] || 0) + (until - from) / 1000;
    if (spendsCredit) {
      leisureStats[key] ||= {};
      leisureStats[key][domain] = (leisureStats[key][domain] || 0) + (until - from) / 1000;
    }
    from = until;
  }
  // prune old days (keep 60)
  const keys = Object.keys(stats).sort();
  while (keys.length > 60) delete stats[keys.shift()];
  for (const day of Object.keys(leisureStats)) if (!stats[day]) delete leisureStats[day];
  const trackingCheckpoint = { tabId: mem.cur.tabId, url, through: t };
  // Save the counter and checkpoint together before advancing the session cursor.
  // A failed write can retry; a worker death between writes cannot double-count.
  Object.assign(state, { stats, leisureStats, trackingCheckpoint });
  const saved = await Store.save(state);
  Object.assign(state, saved);
  mem.cur.since = t;
  await persistCursor();
}

async function persistCursor() {
  try {
    await chrome.storage.session.set({ [CURSOR_KEY]: {
      tabId: mem.cur.tabId, url: mem.cur.url, since: mem.cur.since, focused: mem.focused,
    } });
  } catch (e) { /* session storage may be unavailable in tests or during shutdown */ }
}

async function syncCloud(reason, liveResult) {
  if (syncInFlight) {
    // Account preparation cannot be folded into the previous account's cycle:
    // its subscription must see the new account's cache before accepting pushes.
    if (!liveResult && reason !== 'account-change') return syncInFlight;
    await syncInFlight;
    return syncCloud(reason, liveResult);
  }
  syncInFlight = (async () => {
  try {
    const state = await ensureState();
    if (liveResult && (state.cloudAccountId !== liveResult.userId || !await liveResult.isCurrent())) {
      return { ok: false, signedIn: false };
    }
    const commitmentWasActive = strictIsActive(state);
    const heldStrict = {
      strictMode: state.strictMode, strictEndsAt: state.strictEndsAt,
      strictNukeAfterFive: state.strictNukeAfterFive,
    };
    const previousSites = JSON.stringify(state.cloudSites || []);
    const previousNuke = JSON.stringify(state.cloudNuke || null);
    const previousPolicy = JSON.stringify(state.cloudPolicy || null);
    state.nukeCommitments = Array.isArray(state.nukeCommitments) ? state.nukeCommitments : [];
    if (state.cloudNuke?.isActive && state.cloudAccountId && state.nukeCommitments.length === 0) {
      state.nukeCommitments.push({ accountId: state.cloudAccountId, startedAt: state.cloudNuke.startedAt });
    }
    // Account preparation may replace account-owned data and persist it. Holds
    // must exist before that operation, including if the network then fails.
    const result = liveResult || await self.FocusLockCloud.syncUsage(state, reason || 'background');
    if (result.ok) {
      if (Array.isArray(result.sites)) {
        const incoming = result.sites.filter(site => site && typeof site.domain === 'string' && site.domain.length <= 253)
          .map(site => ({ domain: site.domain.toLowerCase(), isBlocked: Boolean(site.isBlocked),
            ...(typeof site.category === 'string' ? { category: site.category } : {}) }));
        state.cloudSites = incoming;
        state.cloudSitesLoaded = true;
      }
      state.cloudSitesSyncedAt = nowMs();
      if (result.versions) {
        state.cloudSitesVersion = result.versions.sites;
        state.cloudPrefsVersion = result.versions.prefs;
        state.cloudNukeVersion = result.versions.nuke;
        state.cloudPolicyVersion = result.versions.policy;
      }
      if (result.policy !== undefined) state.cloudPolicy = result.policy;
      if (result.usage !== undefined) {
        state.cloudUsage = result.usage;
        state.cloudUsageBaseline = result.usageBaseline || {};
      }
      if (result.leisureBaseline !== undefined) state.cloudLeisureBaseline = result.leisureBaseline;
      if (result.nuke !== undefined) {
        const nuke = result.nuke;
        state.nukeCommitments = state.nukeCommitments.filter(hold => hold.accountId !== state.cloudAccountId);
        if (nuke?.isActive === true) {
          state.nukeCommitments.push({ accountId: state.cloudAccountId, startedAt: Number(nuke.startedAt) || nowMs() });
        }
        state.cloudNuke = { isActive: state.nukeCommitments.length > 0,
          startedAt: state.nukeCommitments[0]?.startedAt || 0 };
      }
      if (result.prefs !== undefined) {
        const p = result.prefs;
        const oldPrefs = state.cloudPrefs;
        state.cloudPrefs = p || null;
        if (!p) {
          if (!commitmentWasActive) {
            state.strictMode = false;
            state.strictEndsAt = 0;
            state.strictPreset = 'custom';
            state.strictNukeAfterFive = false;
          }
        } else {
          const wasStrictActive = commitmentWasActive;
          const remoteStrict = p.strictMode === true;
          const remoteEnd = Number(p.strictEndsAt) || 0;
          const approved = !result.accountChanged && p.strictApprovedSessionId
            && p.strictApprovedSessionId === oldPrefs?.strictSessionId
            && p.strictSessionId === oldPrefs?.strictSessionId
            && Number(p.strictApprovedEndsAt) === Number(heldStrict.strictEndsAt)
            && Number(p.strictApprovedAt) > 0;
          if (commitmentWasActive && !approved) {
            state.strictMode = true;
            state.strictEndsAt = heldStrict.strictEndsAt === 0 || (remoteStrict && remoteEnd === 0)
              ? 0 : Math.max(heldStrict.strictEndsAt, remoteStrict ? remoteEnd : 0);
            state.strictNukeAfterFive = Boolean(heldStrict.strictNukeAfterFive || p.strictNukeAfterFive);
          } else {
            state.strictMode = remoteStrict;
            state.strictEndsAt = remoteEnd;
            state.strictPreset = p.strictPreset || 'custom';
            state.strictNukeAfterFive = Boolean(p.strictNukeAfterFive);
          }
          const key = `${state.strictMode ? 1 : 0}:${state.strictEndsAt}`;
          if (!wasStrictActive && strictIsActive(state)) {
            state.strictSessionKey = key;
            state.strictAttempts = 0;
          }
        }
      }
      if (strictIsActive(state) && !commitmentWasActive) state.strictOriginAccountId = state.cloudAccountId || '';
      if (liveResult && !await liveResult.isCurrent()) return { ok: false, signedIn: false };
      await Store.save(state);
      mem.state = state;
      void self.FocusLockCloud.ensureLivePolicy?.(state).catch(() => {});
      // A boundary can be added from Android or Windows while the matching
      // page is already open. Re-check visible tabs as soon as that account
      // state arrives instead of waiting for the user to navigate again.
      if (previousSites !== JSON.stringify(state.cloudSites) || previousNuke !== JSON.stringify(state.cloudNuke || null)
          || previousPolicy !== JSON.stringify(state.cloudPolicy || null)
          || commitmentWasActive !== strictIsActive(state)) {
        const tabs = await chrome.tabs.query({});
        await Promise.allSettled(tabs
          .filter((tab) => tab.id >= 0 && tab.url && !M.isInternalUrl(tab.url))
          .map((tab) => enforceTab(tab.id, tab.url)));
      }
    }
    return result;
  } catch (error) {
    console.warn('[focuslock] cloud sync failed', error);
    return { signedIn: false, ok: false, error: error && error.message ? error.message : 'Sync failed' };
  } finally {
    syncInFlight = null;
  }
  })();
  return syncInFlight;
}

async function refreshCloudBeforeVerdict() {
  const state = await ensureState();
  const live = self.FocusLockCloud.livePolicyStatus?.();
  if (live?.active) {
    // Convex pushes policy changes and reconnects its socket automatically.
    // A navigation during connection recovery uses the last durable rules.
    return state;
  }
  const now = nowMs();
  const lastSuccess = Number(state.cloudSitesSyncedAt) || 0;
  // Check on navigation when rules are stale; the query returns only changed
  // boundaries or preferences. The alarm refreshes idle browser sessions.
  const activeShared = hasActiveSharedTarget(state);
  const maxAge = activeShared ? ACTIVE_POLICY_MAX_AGE_MS : CLOUD_VERDICT_MAX_AGE_MS;
  if (now - Math.max(lastSuccess, lastCloudRefreshAttemptAt) < maxAge) return state;
  lastCloudRefreshAttemptAt = now;
  await syncCloud(activeShared ? 'active' : 'navigation');
  return ensureState();
}

function hasActiveSharedTarget(state) {
  if (!mem.focused || !mem.cur.url || !/^https?:/i.test(mem.cur.url)) return false;
  return watchesSharedTarget(mem.cur.url, state);
}

function watchesSharedTarget(url, state) {
  return (state.cloudSites || []).some(site => site.isBlocked && M.matchesAny(url, [site.domain]))
    || (state.cloudPolicy?.groups || []).some(group => group.limitEnabled !== false && group.dailyLimitMinutes > 0
      && (group.members || []).some(member => member.targetKind === 'website' && M.matchesAny(url, [member.targetKey])))
    || (state.cloudPolicy?.limits || []).some(limit => ['website', 'site'].includes(limit.targetKind)
      && M.matchesAny(url, [limit.targetKey]));
}

function setActive(url, tabId, focused = mem.focused) {
  return trackSerial(async () => {
    const t = nowMs();
    await flushActiveSliceNow(t);
    mem.focused = focused;
    mem.cur = { tabId: tabId ?? -1, url: url || '', since: t };
    await persistCursor();
  });
}

async function enforceTab(tabId, url) {
  if (!url) return;
  let state = await ensureState();
  let v = verdictFor(url, state, nowMs());
  if (v.blocked) {
    // Never make an already-known block wait on Clerk/Convex availability.
    void refreshCloudBeforeVerdict().catch(() => {});
  } else {
    state = await refreshCloudBeforeVerdict();
    v = verdictFor(url, state, nowMs());
  }
  // Record that a verdict was delivered for this tab+URL so secondary
  // enforcement points (tabs.onUpdated) can skip duplicate work.
  mem.lastEnforced.set(tabId, url);
  if (!v.blocked) return;
  // log + redirect
  const domain = M.domainOf(url);
  const previousAttempt = mem.lastAttempt.get(tabId);
  const duplicateAttempt = previousAttempt && previousAttempt.url === url && nowMs() - previousAttempt.at < 1500;
  if (!duplicateAttempt) {
    mem.lastAttempt.set(tabId, { url, at: nowMs() });
    state.blockedLog.unshift({ ts: nowMs(), url: url.slice(0, 500), domain, listId: v.listId, listName: v.listName });
    state.blockedLog = state.blockedLog.slice(0, 500);
    state.blockedTotal = (state.blockedTotal || 0) + 1;
    try { await Store.save(state); }
    catch (error) { console.warn('[focuslock] block logging could not be saved', error); }
  }
  const dest = chrome.runtime.getURL('blocked/blocked.html')
    + '?url=' + encodeURIComponent(url.slice(0, 800))
    + '&list=' + encodeURIComponent(v.listName || '')
    + '&mode=' + encodeURIComponent(v.mode || '')
    + '&reason=' + encodeURIComponent(v.reason || '');
  try {
    if (tabId >= 0) await chrome.tabs.update(tabId, { url: dest });
  } catch (e) { /* tab gone */ }
}

// Secondary enforcement (tabs.onUpdated loading/complete): only run a verdict
// when the tab's URL actually changed since the last verdict for that tab.
// webNavigation.onBeforeNavigate stays the primary point and always enforces,
// so re-navigating to the same URL is still re-checked (no bypass).
async function enforceTabIfChanged(tabId, url) {
  if (!url || mem.lastEnforced.get(tabId) === url) return;
  await enforceTab(tabId, url);
}

// ---------- events ----------
chrome.tabs.onActivated.addListener(async (info) => {
  try {
    await ensureState();
    const windowInfo = await chrome.windows.getLastFocused();
    const tab = await chrome.tabs.get(info.tabId);
    if (tab.windowId == null || windowInfo.id === tab.windowId) {
      await setActive(tab.url || '', info.tabId, Boolean(windowInfo.focused));
    }
    await enforceTab(info.tabId, tab.url || '');
  } catch (e) { /* ignore */ }
});

chrome.tabs.onUpdated.addListener(async (tabId, change, tab) => {
  await ensureState();
  if (tab.active && change.status === 'loading' && change.url) {
    const focusedWindow = await chrome.windows.getLastFocused();
    if (focusedWindow.focused && (tab.windowId == null || focusedWindow.id === tab.windowId)) {
      await setActive(change.url, tabId, true);
    }
    await enforceTabIfChanged(tabId, change.url);
  } else if (tab.active && tab.url && change.status === 'complete') {
    await enforceTabIfChanged(tabId, tab.url);
  }
});

chrome.tabs.onRemoved.addListener((tabId) => {
  mem.lastEnforced.delete(tabId);
  mem.lastAttempt.delete(tabId);
});

chrome.windows.onFocusChanged.addListener(async (winId) => {
  await ensureState();
  if (winId === chrome.windows.WINDOW_ID_NONE) {
    await trackSerial(async () => {
      await flushActiveSliceNow(nowMs());
      mem.focused = false;
      await persistCursor();
    });
  } else {
    try {
      const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
      if (tab) await setActive(tab.url || '', tab.id, true);
      else await persistCursor();
    } catch (e) { /* ignore */ }
  }
});

chrome.webNavigation.onBeforeNavigate.addListener(async (details) => {
  if (details.frameId !== 0) return;
  if (details.url.startsWith(chrome.runtime.getURL('blocked/'))) return;
  await enforceTab(details.tabId, details.url);
});

chrome.alarms.onAlarm.addListener(async (alarm) => {
  await ensureState();
  await maintainFeatures();
  if (alarm.name === 'focuslock-focus') { await recheckWebsites(); return; }
  // Cloud sync has its own cadence (focuslock-sync, 4 hours); the
  // maintenance alarm drives blocking decisions and stays at 1 minute.
  if (alarm.name === 'focuslock-sync') {
    await flushActiveSlice(nowMs()); // sync the freshest slice, like the old combined alarm
    await syncCloud('alarm');
    return;
  }
  const state = await ensureState();
  const t = nowMs();
  let dirty = false;
  // expire nuclear
  if (state.nuclear.active && state.nuclear.until <= t) {
    state.nuclear.active = false; dirty = true;
    notify('FocusLock', 'Nuclear block has lifted. Stay strong.');
  }
  // expire list locks (frozen timers end -> keep enabled state, just unlock)
  for (const l of state.lists) {
    if (l.lockedUntil && l.lockedUntil <= t) { l.lockedUntil = 0; dirty = true; }
  }
  // expire one-shot timers / pomodoros
  const before = state.schedules.length;
  state.schedules = state.schedules.filter(s => {
    if ((s.type === 'timer' || s.type === 'frozen' || s.type === 'pomodoro') && s.endTs <= t) {
      if (s.type === 'frozen') notify('FocusLock', 'Frozen Turkey block "' + (s.name || '') + '" has ended.');
      return false;
    }
    return true;
  });
  if (state.schedules.length !== before) dirty = true;
  // expire snoozes
  if (state.snoozes) {
    for (const [d, until] of Object.entries(state.snoozes)) {
      if (until <= t) { delete state.snoozes[d]; dirty = true; }
    }
  }
  await flushActiveSlice(t);
  // Local timer/accounting maintenance also restores a terminated worker's
  // subscription and rolls daily usage arguments forward. No policy poll.
  await self.FocusLockCloud.ensureLivePolicy?.(await ensureState());
  try {
    const tabs = await chrome.tabs.query({ active: true });
    await Promise.allSettled(tabs.filter(tab => tab.id >= 0 && tab.url && !M.isInternalUrl(tab.url))
      .map(tab => enforceTab(tab.id, tab.url)));
  } catch (e) { /* tabs may be unavailable during browser shutdown */ }
  if (dirty) { await Store.save(state); mem.state = state; }
  updateBadge(state);
});
chrome.webNavigation.onHistoryStateUpdated.addListener(async (details) => {
  if (details.frameId === 0) await enforceTab(details.tabId, details.url);
});
chrome.webNavigation.onReferenceFragmentUpdated.addListener(async (details) => {
  if (details.frameId === 0) await enforceTab(details.tabId, details.url);
});

function notify(title, message) {
  try { chrome.notifications.create({ type: 'basic', iconUrl: 'icons/icon-128.png', title, message }); }
  catch (e) { /* notifications may be unavailable */ }
}

async function updateBadge(state) {
  try {
    const t = nowMs();
    let n = 0;
    for (const l of state.lists) if (listIsActive(l, state, t).active) n++;
    if (state.nuclear.active) {
      await chrome.action.setBadgeText({ text: 'NUC' });
      await chrome.action.setBadgeBackgroundColor({ color: '#dc2626' });
    } else if (n > 0) {
      await chrome.action.setBadgeText({ text: String(n) });
      await chrome.action.setBadgeBackgroundColor({ color: '#16a34a' });
    } else {
      await chrome.action.setBadgeText({ text: '' });
    }
  } catch (e) { /* ignore */ }
}

// messages from popup / options / blocked page
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const extensionPrefix = `chrome-extension://${chrome.runtime.id}/`;
  const fromExtensionPage = typeof sender.url === 'string' && sender.url.startsWith(extensionPrefix);
  const fromContentScript = !fromExtensionPage && Boolean(sender.tab && sender.id === chrome.runtime.id);
  if (!msg || typeof msg.type !== 'string' || sender.id !== chrome.runtime.id ||
      (msg.type === 'verdict' ? (!fromExtensionPage && !fromContentScript) : !fromExtensionPage)) {
    sendResponse({ ok: false, error: 'Untrusted message sender.' });
    return false;
  }
  if (msg.type === 'verdict' && (typeof msg.url !== 'string' || (fromContentScript && msg.url !== sender.url))) {
    sendResponse({ ok: false, error: 'Invalid verdict request.' });
    return false;
  }
  (async () => {
    const state = await ensureState();
    if (msg.type === 'verdict') {
      if (fromContentScript && sender.tab.id === mem.cur.tabId && mem.focused) await flushActiveSlice(nowMs());
      const cached = verdictFor(msg.url, state, nowMs());
      if (cached.blocked) {
        sendResponse({ ...cached, watch: watchesSharedTarget(msg.url, state) });
        void refreshCloudBeforeVerdict().catch(() => {});
      } else {
        const updated = await refreshCloudBeforeVerdict();
        sendResponse({ ...verdictFor(msg.url, updated, nowMs()), watch: watchesSharedTarget(msg.url, updated) });
      }
    } else if (msg.type === 'todayStats') {
      await flushActiveSlice(nowMs());
      const key = Store.todayKey();
      sendResponse({ day: state.stats[key] || {}, blockedTotal: state.blockedTotal || 0, log: state.blockedLog.slice(0, 50) });
    } else if (msg.type === 'cloudAuthRefresh') {
      sendResponse(await self.FocusLockCloud.refreshAuth());
    } else if (msg.type === 'cloudSnapshot') {
      await flushActiveSlice(nowMs());
      if (msg.sync) await syncCloud('manual');
      sendResponse(await self.FocusLockCloud.getSnapshot(await ensureState(), false));
    } else if (msg.type === 'cloudSignOut') {
      const result = await self.FocusLockCloud.signOut();
      // Preserve cached rules and active strict commitments offline and across sign-out.
      await Store.save(state); mem.state = state;
      sendResponse(result);
    } else if (msg.type === 'setSharedSite') {
      try {
        await syncCloud('edit');
        if (strictIsActive(await ensureState())) {
          sendResponse({ ok: false, error: 'Strict Mode is active. Boundaries are locked until it ends.' });
          return;
        }
        const result = await self.FocusLockCloud.setWebsiteBlocked(msg.domain, msg.isBlocked);
        if (result.ok) await syncCloud('boundary');
        sendResponse(result);
      } catch (e) { sendResponse({ ok: false, error: e?.message || 'Could not save website' }); }
    } else if (msg.type === 'getDashboard') {
      await flushActiveSlice(nowMs());
      await syncCloud('dashboard');
      sendResponse(await self.FocusLockCloud.getDashboard(msg.fromDate, msg.toDate));
    } else if (msg.type === 'focusGroupsSave') {
      await syncCloud('edit');
      if (strictIsActive(await ensureState())) {
        sendResponse({ ok: false, error: 'Merged boundaries cannot be edited during Strict Mode.' });
      } else {
        const result = await self.FocusLockCloud.saveGroups({ groups: msg.groups, updatedAt: msg.updatedAt });
        if (result.ok) await syncCloud('edit');
        sendResponse(result);
      }
    } else if (msg.type === 'frogStatus') {
      sendResponse({ supported: true, ...self.FocusLockFeatures.frogState(state.browserFrog) });
    } else if (msg.type === 'featureStatus' || msg.type === 'featureRetry') {
      await maintainFeatures(msg.type === 'featureRetry');
      const latest = await ensureState();
      sendResponse({ ok: true, frog: self.FocusLockFeatures.frogState(latest.browserFrog),
        timer: latest.focusTimer, pendingSessions: latest.pendingFocusSessions?.length || 0,
        strictPending: Boolean(latest.strictPending), strictMode: strictIsActive(latest), strictEndsAt: latest.strictEndsAt,
        strictSessionId: latest.cloudPrefs?.strictSessionId || '', accountId: latest.cloudAccountId || '',
        strictOriginAccountId: latest.strictOriginAccountId || '',
        strictWeekly: self.FocusLockFeatures.weeklyState(latest.strictWeekly) });
    } else if (msg.type === 'strictCommit') {
      sendResponse(await commitStrict(msg));
    } else if (msg.type === 'strictWeeklySave') {
      await featureSerial(async () => {
        await featureWrite(state => {
          const rule = self.FocusLockFeatures.weeklyState(msg.rule);
          if (rule.enabled && !rule.days.length) throw new Error('Choose at least one weekday.');
          // Preserve the consumed occurrence so approval never re-arms it.
          rule.lastWindow = state.strictWeekly?.lastWindow || '';
          state.strictWeekly = rule; return state;
        });
      });
      await maintainFeatures();
      sendResponse({ ok: true });
    } else if (msg.type === 'frogConfigure' || msg.type === 'frogSelect' || msg.type === 'frogTick') {
      sendResponse(await changeFrog(msg));
    } else if (msg.type === 'focusTimerStart') {
      sendResponse(await startFocusTimer(msg));
    } else if (msg.type === 'focusTimerFinish') {
      sendResponse(await finishFocusTimer(false));
    } else if (msg.type === 'guardianGet' || msg.type === 'guardianSave' || msg.type === 'guardianRequest') {
      if (msg.type === 'guardianSave' && strictIsActive(state)) throw new Error('A trusted person can only be changed before a commitment.');
      sendResponse(await self.FocusLockCloud.guardian(msg));
    } else if (msg.type === 'sharedRulesGet' || msg.type === 'sharedRuleSave') {
      if (msg.type === 'sharedRuleSave') {
        await syncCloud('edit');
        if (strictIsActive(await ensureState())) throw new Error('Shared boundaries cannot change during Strict Mode.');
      }
      const result = await self.FocusLockCloud.sharedRules(msg);
      if (result.ok && msg.type === 'sharedRuleSave') await syncCloud('boundary');
      sendResponse(result);
    } else if (msg.type === 'savePrefs') {
      try {
        const result = await self.FocusLockCloud.savePrefs(msg.prefs || {});
        if (result.ok) await syncCloud('edit');
        sendResponse(result);
      }
      catch (e) { sendResponse({ signedIn: true, ok: false, error: e && e.message ? e.message : 'Prefs save failed' }); }
    } else if (msg.type === 'addWorkRecord') {
      try {
        const result = await self.FocusLockCloud.addWorkRecord(msg.record || {});
        if (result.ok) await syncCloud('edit');
        sendResponse(result);
      }
      catch (e) { sendResponse({ signedIn: true, ok: false, error: e && e.message ? e.message : 'Work log failed' }); }
    } else if (msg.type === 'logFocusSession') {
      try {
        const result = await self.FocusLockCloud.logFocusSession(msg.session || {});
        if (result.ok) await syncCloud('edit');
        sendResponse(result);
      }
      catch (e) { sendResponse({ signedIn: true, ok: false, error: e && e.message ? e.message : 'Focus session failed' }); }
    } else if (msg.type === 'protectionStatus') {
      const t = nowMs();
      const idleTimeoutSec = (state.settings && state.settings.idleTimeoutSec) || 60;
      let idleState = 'active';
      try {
        idleState = await new Promise(res => chrome.idle.queryState(idleTimeoutSec, res));
      } catch (e) { /* idle api unavailable — assume active */ }
      let listsActive = 0;
      for (const l of state.lists) if (listIsActive(l, state, t).active) listsActive++;
      let cloudStatus = { signedIn: false, lastSyncAt: 0, lastError: '' };
      try { cloudStatus = await self.FocusLockCloud.status(); } catch (e) { /* not signed in / unavailable */ }
      sendResponse({
        engineRunning: true,
        focused: Boolean(mem.focused),
        idleState,
        idleTimeoutSec,
        listsActive,
        listsTotal: state.lists.length,
        nuclearActive: Boolean(state.cloudNuke?.isActive || (state.nuclear.active && state.nuclear.until > t)),
        sharedNukeActive: Boolean(state.cloudNuke?.isActive),
        strictActive: strictIsActive(state, t),
        nuclearUntil: state.nuclear && state.nuclear.until ? state.nuclear.until : 0,
        signedIn: Boolean(cloudStatus.signedIn),
        lastSyncAt: Number(cloudStatus.lastSyncAt) || 0,
        lastSyncError: cloudStatus.lastError || '',
        currentUrl: mem.cur.url || '',
        blockedTotal: state.blockedTotal || 0,
      });
    } else if (msg.type === 'snooze') {
      const domain = M.domainOf(msg.url);
      const target = domain && state.lists.some(list => listIsLocked(list) && list.enabled
        && (list.mode === 'whitelist' ? !M.matchesAny(msg.url, list.sites) : M.matchesAny(msg.url, list.sites))
        && !M.matchesAny(msg.url, list.exceptions || []));
      const sharedMode = self.FocusLockPolicy?.verdict(msg.url, state, nowMs())?.mode;
      if (!/^https?:\/\//i.test(msg.url || '') || !domain || target
          || ['group-limit', 'daily-limit', 'schedule', 'global-limit'].includes(sharedMode)
          || M.matchesAny(msg.url, state.permanentSites || [])
          || verdictFor(msg.url, state, nowMs()).mode === 'frog'
          || state.cloudNuke?.isActive || (state.nuclear.active && state.nuclear.until > nowMs())) {
        sendResponse({ ok: false, error: ['daily-limit', 'group-limit', 'global-limit'].includes(sharedMode)
          ? 'This shared daily limit cannot be paused.' : 'This block cannot be snoozed.' });
      } else {
        state.snoozes[domain] = nowMs() + 5 * 60000;
        await Store.save(state); mem.state = state;
        sendResponse({ ok: true, until: state.snoozes[domain] });
      }
    } else if (msg.type === 'refresh') {
      mem.state = await Store.load();
      mem.lastFlushedDay = null;
      updateBadge(mem.state);
      await recheckWebsites();
      sendResponse({ ok: true });
    } else {
      sendResponse({ ok: false });
    }
  })().catch((error) => {
    console.warn('[focuslock] message failed', error);
    sendResponse({ ok: false, error: error?.message || 'FocusLock could not complete that request.' });
  });
  return true;
});

chrome.runtime.onInstalled.addListener(async () => {
  mem.state = null;
  await ensureState();
  // Maintenance (expiry, badge, slice flush) stays at 1 min. Cloud rules are
  // checked on navigation and periodically while the browser is idle.
  await chrome.alarms.create('focuslock-maint', { periodInMinutes: 1 });
  await chrome.alarms.create('focuslock-sync', { periodInMinutes: 4 * 60 });
  updateBadge(mem.state);
  await syncCloud('installed');
});

chrome.runtime.onStartup.addListener(async () => {
  mem.state = null;
  await ensureState();
  await chrome.alarms.create('focuslock-maint', { periodInMinutes: 1 });
  await chrome.alarms.create('focuslock-sync', { periodInMinutes: 4 * 60 });
  updateBadge(mem.state);
  await syncCloud('startup');
});

// Retry durable cloud work promptly when connectivity returns. All boundary
// enforcement continues locally while the network is unavailable.
if (typeof self.addEventListener === 'function') {
  self.addEventListener('online', () => { void syncCloud('reconnect').then(() => maintainFeatures()); });
}

// expose for tests
self.FocusLockEngine = { verdictFor, listIsActive, scheduleActive, minutesUsedToday, inRecurring, strictIsActive };

// MV3 can create a fresh worker for any event, not only browser startup.
// Rehydrate account caches before attaching the authenticated live query.
if (self.FocusLockCloud.startLivePolicy) {
  void (async () => {
    await syncCloud('worker-start');
    await maintainFeatures();
    await self.FocusLockCloud.startLivePolicy({
      onPolicy: result => syncCloud('live', result),
      onIdentityChange: () => syncCloud('account-change'),
    }, await ensureState());
  })().catch(error => console.warn('[focuslock] live policy startup', error?.message || error));
}
