/* FocusLock service worker — tracking + blocking engine (Cold Turkey core). */
importScripts('../src/matcher.js', '../src/store.js');

const M = self.FocusLockMatcher;
const Store = self.FocusLockStore;

let mem = { state: null, cur: { tabId: -1, url: '', since: 0 }, focused: true };

// ---------- helpers ----------
function nowMs() { return Date.now(); }

function hhmmToMin(s) {
  const [h, m] = String(s || '0:0').split(':').map(Number);
  return (h || 0) * 60 + (m || 0);
}

function inRecurring(sch) {
  const d = new Date();
  if (!sch.days || !sch.days.includes(d.getDay())) return false;
  const cur = d.getHours() * 60 + d.getMinutes();
  const a = hhmmToMin(sch.start), b = hhmmToMin(sch.end);
  if (a === b) return true;
  if (a < b) return cur >= a && cur < b;
  return cur >= a || cur < b; // overnight
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

// Core verdict — mirrors Cold Turkey: nuclear > whitelist > blacklist > daily limits
function verdictFor(urlStr, state, t) {
  t = t || nowMs();
  if (!urlStr || M.isInternalUrl(urlStr)) return { blocked: false };
  let domain = '';
  try { domain = new URL(urlStr).hostname.toLowerCase(); } catch (e) { return { blocked: false }; }
  const shortDomain = domain.replace(/^www\./, '');

  // Nuclear: block everything except allow-list
  if (state.nuclear.active && state.nuclear.until > t) {
    if (M.matchesAny(urlStr, state.nuclear.allow || [])) return { blocked: false };
    if (domainAllowedBySnooze(state, shortDomain, t)) return { blocked: false };
    return { blocked: true, mode: 'nuclear', listId: '__nuclear', listName: 'Nuclear Block', reason: 'nuclear' };
  }

  for (const list of state.lists) {
    const st = listIsActive(list, state, t);
    if (!st.active) continue;
    if (domainAllowedBySnooze(state, shortDomain, t)) continue;

    if (list.mode === 'whitelist') {
      // allow-only: block unless URL is in the allowed sites
      if (!M.matchesAny(urlStr, list.sites)) {
        if (M.matchesAny(urlStr, list.exceptions || [])) continue;
        return { blocked: true, mode: 'whitelist', listId: list.id, listName: list.name, reason: st.reason, schedule: st.schedule };
      }
      continue;
    }
    // blacklist
    if (M.matchesAny(urlStr, list.sites) && !M.matchesAny(urlStr, list.exceptions || [])) {
      // daily limit check is informational; pattern hit already blocks while active
      return { blocked: true, mode: 'blacklist', listId: list.id, listName: list.name, reason: st.reason, schedule: st.schedule };
    }
    // daily time limit: block list domains once budget exhausted (even if schedule says on)
    if (list.dailyLimitMin > 0 && M.matchesAny(urlStr, list.sites)) {
      const used = minutesUsedToday(state, list, t);
      if (used >= list.dailyLimitMin) {
        return { blocked: true, mode: 'daily-limit', listId: list.id, listName: list.name, reason: 'daily-limit', schedule: st.schedule };
      }
    }
  }
  return { blocked: false };
}

function minutesUsedToday(state, list, t) {
  const key = Store.todayKey(new Date(t || nowMs()));
  const day = state.stats[key] || {};
  let secs = 0;
  for (const [domain, s] of Object.entries(day)) {
    const probe = 'https://' + domain + '/';
    if (M.matchesAny(probe, list.sites)) secs += s;
  }
  return secs / 60;
}

// ---------- tracking ----------
async function ensureState() {
  if (!mem.state) mem.state = await Store.load();
  return mem.state;
}

async function flushActiveSlice(t) {
  t = t || nowMs();
  const { url, since } = mem.cur;
  if (!url || !since || !mem.focused) { mem.cur.since = t; return; }
  const idleOk = await new Promise(res => {
    try { chrome.idle.queryState(Store.defaultState().settings.idleTimeoutSec || 60, res); }
    catch (e) { res('active'); }
  });
  const secs = Math.max(0, Math.round((t - since) / 1000));
  mem.cur.since = t;
  if (idleOk !== 'active' || secs <= 0 || secs > 3600) return;
  if (M.isInternalUrl(url)) return;
  const domain = M.domainOf(url);
  if (!domain) return;
  const state = await ensureState();
  const key = Store.todayKey();
  state.stats[key] = state.stats[key] || {};
  state.stats[key][domain] = (state.stats[key][domain] || 0) + secs;
  // prune old days (keep 60)
  const keys = Object.keys(state.stats).sort();
  while (keys.length > 60) delete state.stats[keys.shift()];
  await Store.save(state);
  mem.state = state;
}

async function setActive(url, tabId) {
  const t = nowMs();
  await flushActiveSlice(t);
  mem.cur = { tabId: tabId ?? -1, url: url || '', since: t };
}

async function enforceTab(tabId, url) {
  if (!url) return;
  const state = await ensureState();
  const v = verdictFor(url, state, nowMs());
  if (!v.blocked) return;
  // log + redirect
  const domain = M.domainOf(url);
  state.blockedLog.unshift({ ts: nowMs(), url: url.slice(0, 500), domain, listId: v.listId, listName: v.listName });
  state.blockedLog = state.blockedLog.slice(0, 500);
  state.blockedTotal = (state.blockedTotal || 0) + 1;
  await Store.save(state);
  mem.state = state;
  const dest = chrome.runtime.getURL('blocked/blocked.html')
    + '?url=' + encodeURIComponent(url.slice(0, 800))
    + '&list=' + encodeURIComponent(v.listName || '')
    + '&mode=' + encodeURIComponent(v.mode || '');
  try {
    if (tabId >= 0) await chrome.tabs.update(tabId, { url: dest });
  } catch (e) { /* tab gone */ }
}

// ---------- events ----------
chrome.tabs.onActivated.addListener(async (info) => {
  try {
    const tab = await chrome.tabs.get(info.tabId);
    await setActive(tab.url || '', info.tabId);
    await enforceTab(info.tabId, tab.url || '');
  } catch (e) { /* ignore */ }
});

chrome.tabs.onUpdated.addListener(async (tabId, change, tab) => {
  if (change.status === 'loading' && change.url) {
    await setActive(change.url, tabId);
    await enforceTab(tabId, change.url);
  } else if (tab.active && tab.url && change.status === 'complete') {
    await enforceTab(tabId, tab.url);
  }
});

chrome.windows.onFocusChanged.addListener(async (winId) => {
  mem.focused = winId !== chrome.windows.WINDOW_ID_NONE;
  if (!mem.focused) await flushActiveSlice(nowMs());
  else {
    try {
      const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
      if (tab) await setActive(tab.url || '', tab.id);
    } catch (e) { /* ignore */ }
  }
});

chrome.webNavigation.onBeforeNavigate.addListener(async (details) => {
  if (details.frameId !== 0) return;
  if (details.url.startsWith(chrome.runtime.getURL('blocked/'))) return;
  await enforceTab(details.tabId, details.url);
});

chrome.alarms.onAlarm.addListener(async (alarm) => {
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
  if (dirty) { await Store.save(state); mem.state = state; }
  updateBadge(state);
});

function notify(title, message) {
  try { chrome.notifications.create({ type: 'basic', iconUrl: 'icons/lock.svg', title, message }); }
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
  (async () => {
    const state = await ensureState();
    if (msg.type === 'verdict') {
      sendResponse(verdictFor(msg.url, state, nowMs()));
    } else if (msg.type === 'todayStats') {
      const key = Store.todayKey();
      sendResponse({ day: state.stats[key] || {}, blockedTotal: state.blockedTotal || 0, log: state.blockedLog.slice(0, 50) });
    } else if (msg.type === 'snooze') {
      const domain = M.domainOf(msg.url);
      state.snoozes[domain] = nowMs() + (msg.minutes || 5) * 60000;
      await Store.save(state); mem.state = state;
      sendResponse({ ok: true, until: state.snoozes[domain] });
    } else if (msg.type === 'refresh') {
      mem.state = await Store.load();
      updateBadge(mem.state);
      sendResponse({ ok: true });
    } else {
      sendResponse({ ok: false });
    }
  })();
  return true;
});

chrome.runtime.onInstalled.addListener(async () => {
  mem.state = await Store.load();
  await chrome.alarms.create('focuslock-maint', { periodInMinutes: 1 });
  updateBadge(mem.state);
});

chrome.runtime.onStartup.addListener(async () => {
  mem.state = await Store.load();
  await chrome.alarms.create('focuslock-maint', { periodInMinutes: 1 });
  updateBadge(mem.state);
});

// expose for tests
self.FocusLockEngine = { verdictFor, listIsActive, scheduleActive, minutesUsedToday };
