/* FocusLockStore — chrome.storage.local schema + helpers. Loaded in SW + pages. */
(function (root) {
  const KEY = 'focuslock.v1';

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
      stats: {},              // 'YYYY-MM-DD' -> { domain: seconds }
      blockedLog: [],         // { ts, url, domain, listId, listName }
      blockedTotal: 0,
      security: { salt: '', hash: '', strict: true },
      settings: { idleTimeoutSec: 60, blockedDelayNote: true, quotes: true }
    };
  }

  async function load() {
    const got = await chrome.storage.local.get(KEY);
    if (got && got[KEY]) return migrate(got[KEY]);
    const fresh = defaultState();
    await save(fresh);
    return fresh;
  }

  function migrate(s) {
    const d = defaultState();
    return Object.assign(d, s, { settings: Object.assign(d.settings, s.settings || {}) });
  }

  async function save(state) {
    await chrome.storage.local.set({ [KEY]: state });
    return state;
  }

  async function update(fn) {
    const s = await load();
    const next = (await fn(s)) || s;
    await save(next);
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
    KEY, uid, todayKey, PRESETS, defaultState, load, save, update,
    sha256hex, setPassword, verifyPassword
  };
})(typeof self !== 'undefined' ? self : globalThis);
