/* FocusLockStore — chrome.storage.local schema + helpers. Loaded in SW + pages. */
(function (root) {
  const KEY = 'focuslock.v1';
  let lastLoadedState = null;
  const baselines = new WeakMap();
  let localWrites = Promise.resolve();
  const copy = value => JSON.parse(JSON.stringify(value));
  // chrome.storage may reorder object keys. Compare values, never insertion order.
  const serialized = value => JSON.stringify(value, (_key, item) =>
    item && typeof item === 'object' && !Array.isArray(item)
      ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item);
  function withStateLock(task) {
    // All extension pages and its service worker share this origin's lock.
    if (globalThis.navigator?.locks) return globalThis.navigator.locks.request(KEY, task);
    const next = localWrites.then(task, task);
    localWrites = next.catch(() => {});
    return next;
  }

  function boundarySettings(state, now) {
    return {
      lists: (state?.lists || []).map(list => ({ ...list,
        lockedUntil: Number(list.lockedUntil) > now ? list.lockedUntil : 0 })),
      schedules: (state?.schedules || []).filter(schedule =>
        !['timer', 'frozen', 'pomodoro'].includes(schedule.type) || Number(schedule.endTs) > now),
      permanentSites: normalizePermanentSites(state?.permanentSites),
    };
  }

  function onlyStrengthensBoundaries(before, after, now) {
    const oldSettings = boundarySettings(before, now);
    const nextSettings = boundarySettings(after, now);
    const nextLists = new Map(nextSettings.lists.map((list) => [list.id, list]));
    const oldIds = new Set(oldSettings.lists.map((list) => list.id));
    for (const oldList of oldSettings.lists) {
      const next = nextLists.get(oldList.id);
      if (!next) return false;
      const oldSites = Array.isArray(oldList.sites) ? oldList.sites : [];
      const nextSites = Array.isArray(next.sites) ? next.sites : [];
      if (oldSites.some((site) => !nextSites.includes(site))) return false;
      if (oldList.enabled === true && next.enabled !== true) return false;
      if (oldList.mode === 'whitelist' && oldList.enabled !== next.enabled) return false;
      if (serialized(oldSites) !== serialized(nextSites) && oldList.mode !== 'blacklist') return false;
      const oldRest = { ...oldList }; delete oldRest.sites; delete oldRest.enabled;
      const nextRest = { ...next }; delete nextRest.sites; delete nextRest.enabled;
      if (serialized(oldRest) !== serialized(nextRest)) return false;
    }
    for (const list of nextSettings.lists) {
      if (oldIds.has(list.id)) continue;
      if (list.enabled !== true || list.mode !== 'blacklist' || !Array.isArray(list.sites) || !list.sites.length) return false;
    }
    if (serialized(oldSettings.schedules) !== serialized(nextSettings.schedules)) return false;
    const permanent = new Set(nextSettings.permanentSites);
    return oldSettings.permanentSites.every((domain) => permanent.has(domain));
  }

  function uid(prefix) {
    return (prefix || 'id') + '_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function todayKey(d) {
    d = d || new Date();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return d.getFullYear() + '-' + m + '-' + day;
  }

  const PRESETS = {
    social: ['facebook.com', 'instagram.com', 'x.com', 'twitter.com', 'tiktok.com', 'reddit.com', 'linkedin.com', 'pinterest.com', 'snapchat.com', 'threads.net'],
    video: ['youtube.com', 'netflix.com', 'twitch.tv', 'hulu.com', 'disneyplus.com', 'primevideo.com', 'hbomax.com', 'vimeo.com'],
    news: ['cnn.com', 'bbc.com', 'nytimes.com', 'theguardian.com', 'dailymail.co.uk', 'foxnews.com', 'buzzfeed.com'],
    shopping: ['amazon.com', 'ebay.com', 'aliexpress.com', 'etsy.com', 'walmart.com'],
    dev_distraction: ['*.github.com', '# keep code but block social corners', 'reddit.com/r/programming*']
  };

  const PERMANENT_SITES_LIMIT = 1000;
  // Permalock: device-local, append-only domains. Normalized lowercase, no
  // www., no duplicates. Nothing except an explicit append may change this list.
  function normalizePermanentSites(value) {
    const out = [];
    const seen = new Set();
    for (const item of Array.isArray(value) ? value : []) {
      if (typeof item !== 'string') continue;
      let domain = item.trim().toLowerCase().replace(/\.+$/, '');
      if (domain.startsWith('*.')) domain = '*.' + domain.slice(2).replace(/^www\./, '');
      else domain = domain.replace(/^www\./, '');
      if (!domain || domain.length > 253 || /[\s/\\:@?#]/.test(domain) || !domain.includes('.')) continue;
      if (seen.has(domain)) continue;
      seen.add(domain);
      out.push(domain);
      if (out.length >= PERMANENT_SITES_LIMIT) break;
    }
    return out;
  }

  function defaultState() {
    return {
      version: 1,
      createdAt: Date.now(),
      lists: [
        {
          id: 'list_social', name: 'Social Media', mode: 'blacklist',
          enabled: true, alwaysOn: true,
          sites: ['facebook.com', 'instagram.com', 'x.com', 'twitter.com', 'tiktok.com', 'reddit.com', 'linkedin.com'],
          exceptions: [], lockedUntil: 0, dailyLimitMin: 0
        },
        {
          id: 'list_video', name: 'Video & Streaming', mode: 'blacklist',
          enabled: true, alwaysOn: true,
          sites: ['youtube.com', 'netflix.com', 'twitch.tv', 'hulu.com'],
          exceptions: [],
          lockedUntil: 0, dailyLimitMin: 0
        }
      ],
      schedules: [],
      nuclear: { active: false, until: 0, allow: ['docs.google.com', 'mail.google.com'] },
      snoozes: {},           // domain -> timestamp allowed until
      permanentSites: [],     // Permalock domains — never removed by any UI, import, reset, or sync
      stats: {},              // 'YYYY-MM-DD' -> { domain: seconds }
      blockedLog: [],         // { ts, url, domain, listId, listName }
      blockedTotal: 0,
      strictMode: false,
      strictEndsAt: 0,
      strictPreset: 'custom',
      strictNukeAfterFive: false,
      strictAttempts: 0,
      strictSessionKey: '',
      browserFrog: { enabled: false, wakeHour: 5, requiredSeconds: 1800 },
      focusTimer: null,
      focusDailySessions: [],
      focusDailySummary: { date: '', accountId: '', items: [], fetchedAt: 0 },
      focusActiveAccountId: '',
      pendingFocusSessions: [],
      strictPending: null,
      strictOriginAccountId: '',
      strictWeekly: null,
      security: { salt: '', hash: '', strict: true },
      settings: { idleTimeoutSec: 60, blockedDelayNote: true, quotes: true }
    };
  }

  function load() {
    return withStateLock(async () => {
      const got = await chrome.storage.local.get(KEY);
      const state = got?.[KEY] ? migrate(got[KEY]) : defaultState();
      if (serialized(got?.[KEY]) !== serialized(state)) {
        await chrome.storage.local.set({ [KEY]: state });
      }
      lastLoadedState = copy(state);
      baselines.set(state, copy(state));
      return state;
    });
  }

  function migrate(s) {
    const d = defaultState();
    if (!s || typeof s !== 'object' || Array.isArray(s)) return d;
    const finite = (v, fallback = 0) => Number.isFinite(Number(v)) ? Number(v) : fallback;
    const strings = (v) => Array.isArray(v) ? v.filter(x => typeof x === 'string').slice(0, 500) : [];
    const record = (v) => v && typeof v === 'object' && !Array.isArray(v) ? v : {};
    const lists = Array.isArray(s.lists) ? s.lists.filter(x => x && typeof x === 'object' && !Array.isArray(x)).slice(0, 200).map((x, i) => ({
      id: typeof x.id === 'string' && x.id ? x.id.slice(0, 128) : `list_migrated_${i}`,
      name: typeof x.name === 'string' ? x.name.slice(0, 120) : 'Block list',
      mode: x.mode === 'whitelist' ? 'whitelist' : 'blacklist',
      enabled: x.enabled !== false,
      alwaysOn: x.alwaysOn === true,
      sites: strings(x.sites), exceptions: strings(x.exceptions),
      lockedUntil: Math.max(0, finite(x.lockedUntil)),
      dailyLimitMin: Math.max(0, finite(x.dailyLimitMin)),
    })) : d.lists;
    const schedules = Array.isArray(s.schedules) ? s.schedules.filter(x => x && typeof x === 'object' && !Array.isArray(x) && typeof x.id === 'string' && typeof x.listId === 'string' && ['recurring', 'timer', 'frozen', 'pomodoro'].includes(x.type)).slice(0, 500).map(x => ({
      ...x, startTs: finite(x.startTs), endTs: finite(x.endTs),
      days: Array.isArray(x.days) ? x.days.filter(day => Number.isInteger(day) && day >= 0 && day <= 6) : [],
      start: typeof x.start === 'string' ? x.start : '00:00', end: typeof x.end === 'string' ? x.end : '00:00',
    })) : [];
    const stats = {};
    for (const [day, values] of Object.entries(record(s.stats)).slice(-60)) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) continue;
      stats[day] = {};
      for (const [domain, seconds] of Object.entries(record(values)).slice(0, 2000)) {
        if (typeof seconds === 'number' && Number.isFinite(seconds) && seconds >= 0) stats[day][domain.slice(0, 253)] = seconds;
      }
    }
    const snoozes = {};
    const leisureStats = {};
    for (const [day, domains] of Object.entries(stats)) {
      leisureStats[day] = {};
      for (const [domain, seconds] of Object.entries(domains)) {
        leisureStats[day][domain] = Math.max(0, Math.min(seconds, finite(record(record(s.leisureStats)[day])[domain])));
      }
    }
    for (const [domain, until] of Object.entries(record(s.snoozes)).slice(0, 500)) {
      if (typeof until === 'number' && Number.isFinite(until) && until > 0 && domain.length <= 253) snoozes[domain.toLowerCase()] = until;
    }
    const cloudSites = Array.isArray(s.cloudSites) ? s.cloudSites.filter(site => site && typeof site.domain === 'string' && site.domain.length <= 253)
      .slice(0, 10000).map(site => ({ domain: site.domain.toLowerCase(), isBlocked: Boolean(site.isBlocked),
        ...(typeof site.category === 'string' ? { category: site.category.slice(0, 80) } : {}) })) : [];
    const nuclear = record(s.nuclear);
    const security = record(s.security);
    const settings = record(s.settings);
    return {
      ...d, ...s,
      lists, schedules, stats, snoozes,
      leisureStats,
      cloudPolicy: s.cloudPolicy && typeof s.cloudPolicy === 'object' && !Array.isArray(s.cloudPolicy)
        ? { ...s.cloudPolicy, groups: Array.isArray(s.cloudPolicy.groups) ? s.cloudPolicy.groups : [],
          limits: Array.isArray(s.cloudPolicy.limits) ? s.cloudPolicy.limits : [],
          schedules: Array.isArray(s.cloudPolicy.schedules) ? s.cloudPolicy.schedules : [] } : null,
      permanentSites: normalizePermanentSites(s.permanentSites),
      nuclear: { ...d.nuclear, active: nuclear.active === true, until: Math.max(0, finite(nuclear.until)), allow: strings(nuclear.allow) },
      blockedLog: Array.isArray(s.blockedLog) ? s.blockedLog.filter(x => x && typeof x === 'object' && typeof x.url === 'string').slice(0, 500) : [],
      blockedTotal: Math.max(0, finite(s.blockedTotal)),
      cloudSites, cloudSitesLoaded: s.cloudSitesLoaded === true,
      cloudSitesSyncedAt: Math.max(0, finite(s.cloudSitesSyncedAt)),
      strictHeldSites: [], // Retire legacy commitment-held domains; Strict Mode is an edit lock only.
      nukeCommitments: Array.isArray(s.nukeCommitments) ? s.nukeCommitments
        .filter(hold => hold && typeof hold.accountId === 'string' && hold.accountId)
        .map(hold => ({ accountId: hold.accountId, startedAt: Math.max(0, finite(hold.startedAt)) })) : [],
      cloudNuke: { isActive: record(s.cloudNuke).isActive === true,
        startedAt: Math.max(0, finite(record(s.cloudNuke).startedAt)) },
      strictMode: s.strictMode === true || s.strictMode === 'true' || s.strictMode === 1,
      strictEndsAt: Math.max(0, finite(s.strictEndsAt)),
      strictPreset: typeof s.strictPreset === 'string' ? s.strictPreset : 'custom',
      strictNukeAfterFive: s.strictNukeAfterFive === true,
      strictAttempts: Math.max(0, finite(s.strictAttempts)),
      strictSessionKey: typeof s.strictSessionKey === 'string' ? s.strictSessionKey : '',
      browserFrog: root.FocusLockFeatures?.frogState(s.browserFrog) || d.browserFrog,
      focusTimer: root.FocusLockFeatures?.timerState(s.focusTimer) || null,
      focusDailySessions: Array.isArray(s.focusDailySessions) ? s.focusDailySessions.filter(row =>
        row && typeof row.id === 'string' && typeof row.accountId === 'string'
          && Number.isFinite(row.timestamp) && Number.isFinite(row.seconds) && row.seconds >= 0).slice(-500) : [],
      focusDailySummary: s.focusDailySummary && typeof s.focusDailySummary === 'object'
        ? { date: /^\d{4}-\d{2}-\d{2}$/.test(s.focusDailySummary.date || '') ? s.focusDailySummary.date : '',
          accountId: typeof s.focusDailySummary.accountId === 'string' ? s.focusDailySummary.accountId : '',
          items: Array.isArray(s.focusDailySummary.items) ? s.focusDailySummary.items.filter(row => row && typeof row.id === 'string'
            && Number.isFinite(row.seconds) && row.seconds >= 0).slice(-500) : [],
          fetchedAt: Math.max(0, Number(s.focusDailySummary.fetchedAt) || 0) }
        : d.focusDailySummary,
      focusActiveAccountId: typeof s.focusActiveAccountId === 'string' ? s.focusActiveAccountId : '',
      pendingFocusSessions: Array.isArray(s.pendingFocusSessions) ? s.pendingFocusSessions.filter(row =>
        row && typeof row.id === 'string' && typeof row.accountId === 'string' && row.session && typeof row.session === 'object').slice(-100) : [],
      strictPending: s.strictPending && typeof s.strictPending.accountId === 'string' && s.strictPending.prefs
        ? s.strictPending : null,
      strictOriginAccountId: typeof s.strictOriginAccountId === 'string' ? s.strictOriginAccountId
        : s.strictMode && typeof s.cloudAccountId === 'string' ? s.cloudAccountId : '',
      strictWeekly: root.FocusLockFeatures?.weeklyState(s.strictWeekly) || null,
      security: { ...d.security, salt: typeof security.salt === 'string' ? security.salt : '', hash: typeof security.hash === 'string' ? security.hash : '', strict: security.strict !== false },
      settings: { ...d.settings, ...settings, idleTimeoutSec: Math.min(3600, Math.max(15, finite(settings.idleTimeoutSec, 60))) },
    };
  }

  function save(state, expected = baselines.get(state) || lastLoadedState) {
    const proposed = copy(state);
    const before = expected ? copy(expected) : null;
    return withStateLock(async () => {
      const got = await chrome.storage.local.get(KEY);
      const latest = got?.[KEY];
      let merged = proposed;
      if (before && latest && typeof latest === 'object' && !Array.isArray(latest)) {
        // A dashboard edit must not overwrite usage flushed by the worker, and
        // a worker flush must not restore an older copy of the dashboard rules.
        merged = copy(latest);
        if (latest.cloudAccountId !== before.cloudAccountId) {
          throw new Error('The account changed. Reload before saving this change.');
        }
        for (const key of new Set([...Object.keys(before), ...Object.keys(proposed)])) {
          const oldValue = serialized(before[key]);
          const nextValue = serialized(proposed[key]);
          if (oldValue === nextValue) continue;
          const currentValue = serialized(latest[key]);
          if (currentValue !== oldValue && currentValue !== nextValue) {
            throw new Error('Settings changed in another FocusLock window. Reload and try again.', { cause: { field: key } });
          }
          if (Object.hasOwn(proposed, key)) merged[key] = proposed[key];
          else delete merged[key];
        }
      }
      // Permalock is append-only at the storage layer too: union whatever is
      // already persisted with whatever this save adds, so no caller (stale
      // dashboard window, worker flush, account switch) can drop an entry.
      // Check the committed state under the same lock as the write. Strict
      // Mode may have arrived from mobile after a dashboard edit began. Only
      // additions that strengthen a boundary are allowed during a commitment.
      const now = Date.now();
      if (latest?.strictMode === true && (!Number(latest.strictEndsAt) || Number(latest.strictEndsAt) > now)
          && !onlyStrengthensBoundaries(latest, merged, now)) {
        throw new Error('Strict Mode is active. Boundaries are locked until it ends.');
      }
      merged.permanentSites = normalizePermanentSites([
        ...(Array.isArray(latest?.permanentSites) ? latest.permanentSites : []),
        ...(Array.isArray(proposed.permanentSites) ? proposed.permanentSites : []),
      ]);
      if (serialized(latest) !== serialized(merged)) await chrome.storage.local.set({ [KEY]: merged });
      lastLoadedState = copy(merged);
      baselines.set(state, copy(merged));
      for (const key of Object.keys(state)) if (!Object.hasOwn(merged, key)) delete state[key];
      Object.assign(state, copy(merged));
      return state;
    });
  }

  async function update(fn) {
    const s = await load();
    const before = copy(s);
    const next = (await fn(s)) || s;
    await save(next, before);
    return next;
  }

  // --- password (SHA-256 salt+pw, no plain storage) ---
  async function sha256hex(text) {
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
    return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
  }

  async function setPassword(state, pw) {
    const salt = [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');
    state.security.salt = salt;
    state.security.hash = await sha256hex(salt + '::' + pw);
  }

  async function verifyPassword(state, pw) {
    if (!state.security.hash) return true; // no password set = open
    const h = await sha256hex(state.security.salt + '::' + pw);
    return h === state.security.hash;
  }

  root.FocusLockStore = {
    KEY, uid, todayKey, PRESETS, defaultState, load, save, update, normalizePermanentSites,
    sha256hex, setPassword, verifyPassword
  };
})(typeof self !== 'undefined' ? self : globalThis);
