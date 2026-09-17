import { createClerkClient } from '@clerk/chrome-extension/client';

const publishableKey = process.env.CLERK_PUBLISHABLE_KEY;
const M = self.FocusLockMatcher;
const S = self.FocusLockStore;
const app = document.getElementById('app');
const extensionRoot = chrome.runtime.getURL('.');
const popupUrl = chrome.runtime.getURL('popup/popup.html');
const clerk = createClerkClient({ publishableKey });

let activeTab = null;
let activeUrl = '';
let domain = '';
let localState = null;
let cloud = null;
let busy = false;
let message = '';

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;',
  }[char]));
}

function formatDuration(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (hours) return `${hours}h ${minutes}m`;
  if (minutes) return `${minutes}m`;
  return `${total}s`;
}

function relativeTime(timestamp) {
  if (!timestamp) return 'Not synced yet';
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 60) return 'Synced just now';
  if (seconds < 3600) return `Synced ${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `Seen ${Math.floor(seconds / 3600)}h ago`;
  return `Seen ${Math.floor(seconds / 86400)}d ago`;
}

function deviceStatus(device, fallback) {
  if (!device) return { state: 'off', title: fallback, detail: 'Not connected to this account' };
  const fresh = Date.now() - device.lastSeen < 10 * 60 * 1000;
  const healthy = device.trackingStatus === 'active';
  return {
    state: healthy && fresh ? 'ok' : 'idle',
    title: device.name || fallback,
    detail: healthy ? relativeTime(device.lastSeen) : (device.statusDetail || 'Tracking needs attention'),
  };
}

function icon(name) {
  const paths = {
    lock: '<path d="M7 11V8a5 5 0 0 1 10 0v3"/><rect x="4" y="11" width="16" height="10" rx="3"/><path d="M12 15v2"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18"/>',
    phone: '<rect x="7" y="2.5" width="10" height="19" rx="2"/><path d="M11 18h2"/>',
    refresh: '<path d="M20 6v5h-5M4 18v-5h5"/><path d="M18.5 9A7 7 0 0 0 6 6.5L4 9m2 6a7 7 0 0 0 12 2l2-2"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3V2.8h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z"/>',
  };
  return `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name]}</svg>`;
}

function render() {
  if (!clerk.loaded) return;
  const signedIn = Boolean(clerk.user && clerk.session);
  const localSeconds = localState?.stats?.[S.todayKey()]?.[domain] || 0;
  const localTotal = Object.values(localState?.stats?.[S.todayKey()] || {}).reduce((sum, value) => sum + Number(value || 0), 0);
  const totalSeconds = signedIn && cloud?.summary ? cloud.summary.totalTrackedSeconds : localTotal;
  const browserDevice = cloud?.devices?.find((device) => device.platform === 'browser');
  const androidDevice = cloud?.devices?.find((device) => device.platform === 'android');
  const browser = signedIn ? deviceStatus(browserDevice, 'Chrome extension') : { state: 'off', title: 'Chrome extension', detail: 'Sign in to sync tracking' };
  const android = signedIn ? deviceStatus(androidDevice, 'Android app') : { state: 'off', title: 'Android app', detail: 'Waiting for account connection' };
  const initials = clerk.user?.firstName?.[0] || clerk.user?.primaryEmailAddress?.emailAddress?.[0] || 'F';

  app.innerHTML = `
    <header class="topbar">
      <div class="brand"><span class="brand-mark">${icon('lock')}</span><div><strong>FocusLock</strong><small>Website tracking</small></div></div>
      <div class="header-actions">
        <button class="icon-button" id="sync" aria-label="Sync now" title="Sync now" ${busy || !signedIn ? 'disabled' : ''}>${icon('refresh')}</button>
        <button class="avatar" id="account" aria-label="${signedIn ? 'Account menu' : 'Sign in'}">${clerk.user?.imageUrl ? `<img src="${escapeHtml(clerk.user.imageUrl)}" alt=""/>` : escapeHtml(initials.toUpperCase())}</button>
      </div>
    </header>

    ${!signedIn ? `
      <section class="signin-card">
        <span class="signin-icon">${icon('globe')}</span>
        <div><h1>Keep every device together</h1><p>Sign in with your FocusLock account to sync Chrome time with Android and Windows.</p></div>
        <button class="primary full" id="signin">Sign in to sync</button>
      </section>
    ` : `
      <section class="summary" aria-labelledby="today-heading">
        <div><p class="label" id="today-heading">Screen time today</p><h1>${formatDuration(totalSeconds)}</h1></div>
        <p class="summary-meta">Across ${cloud?.devices?.length || 1} connected device${(cloud?.devices?.length || 1) === 1 ? '' : 's'}</p>
      </section>

      <section class="connections" aria-label="Connection status">
        <article class="connection"><span class="device-icon">${icon('globe')}</span><div><strong>${escapeHtml(browser.title)}</strong><p>${escapeHtml(browser.detail)}</p></div><span class="state ${browser.state}">${browser.state === 'ok' ? 'Connected' : browser.state === 'idle' ? 'Attention' : 'Offline'}</span></article>
        <article class="connection"><span class="device-icon">${icon('phone')}</span><div><strong>${escapeHtml(android.title)}</strong><p>${escapeHtml(android.detail)}</p></div><span class="state ${android.state}">${android.state === 'ok' ? 'Connected' : android.state === 'idle' ? 'Last seen' : 'Not linked'}</span></article>
      </section>
    `}

    <section class="current-site">
      <div class="site-heading"><div><p class="label">Current website</p><h2>${escapeHtml(domain || 'Chrome page')}</h2></div><strong>${formatDuration(localSeconds)}</strong></div>
      <div class="progress" aria-label="Current website share of today"><i style="width:${Math.min(100, totalSeconds ? localSeconds / totalSeconds * 100 : 0)}%"></i></div>
      <div class="site-actions">
        <button class="primary" id="block-site" ${!domain ? 'disabled' : ''}>Block this site</button>
        <button class="secondary" id="allow-site" ${!domain ? 'disabled' : ''}>Allow 5 min</button>
      </div>
    </section>

    ${message ? `<p class="feedback" role="status">${escapeHtml(message)}</p>` : ''}
    ${cloud?.error ? `<p class="error" role="alert">Sync paused: ${escapeHtml(cloud.error)}</p>` : ''}

    <footer><button class="text-button" id="dashboard">Open boundaries & stats</button>${signedIn ? '<button class="text-button" id="signout">Sign out</button>' : ''}<button class="icon-button" id="settings" aria-label="Open extension settings">${icon('settings')}</button></footer>
  `;

  bindEvents(signedIn);
}

function flash(text) {
  message = text;
  render();
  setTimeout(() => { message = ''; render(); }, 2800);
}

function bindEvents(signedIn) {
  document.getElementById('dashboard')?.addEventListener('click', () => chrome.tabs.create({ url: chrome.runtime.getURL('options/options.html') }));
  document.getElementById('settings')?.addEventListener('click', () => chrome.tabs.create({ url: chrome.runtime.getURL('options/options.html?tab=settings') }));
  document.getElementById('account')?.addEventListener('click', () => openAccountPage(signedIn ? 'profile' : 'signin'));
  document.getElementById('signin')?.addEventListener('click', () => openAccountPage('signin'));
  document.getElementById('signout')?.addEventListener('click', async () => { await clerk.signOut(); await chrome.runtime.sendMessage({ type: 'cloudSignOut' }); });
  document.getElementById('sync')?.addEventListener('click', () => refreshCloud(true));
  document.getElementById('allow-site')?.addEventListener('click', async () => {
    await chrome.runtime.sendMessage({ type: 'snooze', url: activeUrl, minutes: 5 });
    flash('Allowed for 5 minutes.');
  });
  document.getElementById('block-site')?.addEventListener('click', async () => {
    if (signedIn) {
      const result = await chrome.runtime.sendMessage({ type: 'setSharedSite', domain, isBlocked: true });
      if (!result?.ok) { flash(result?.error || 'Could not sync this website.'); return; }
      localState = await S.load();
      flash(`Blocked ${domain} on your account`);
      return;
    }
    await S.update((state) => {
      const list = state.lists.find((item) => item.id === 'list_social') || state.lists[0];
      if (!list || list.lockedUntil > Date.now()) return state;
      if (!list.sites.includes(domain)) list.sites.push(domain);
      list.enabled = true;
      return state;
    });
    await chrome.runtime.sendMessage({ type: 'refresh' });
    localState = await S.load();
    flash(`Blocked ${domain}`);
  });
}

function openAccountPage(view) {
  const url = new URL(chrome.runtime.getURL('options/options.html'));
  url.searchParams.set('account', view);
  chrome.tabs.create({ url: url.href });
  window.close();
}

async function refreshCloud(forceSync) {
  if (!clerk.session) return;
  busy = true;
  render();
  try {
    cloud = await chrome.runtime.sendMessage({ type: 'cloudSnapshot', sync: Boolean(forceSync) });
  } catch (error) {
    cloud = { error: error?.message || 'Could not reach the background tracker' };
  } finally {
    busy = false;
    render();
  }
}

async function init() {
  [activeTab] = await chrome.tabs.query({ active: true, currentWindow: true });
  activeUrl = activeTab?.url || '';
  // Internal browser/extension pages are not blockable websites. Keep the
  // popup useful without exposing the extension id as a fake domain.
  domain = /^https?:\/\//i.test(activeUrl) ? M.domainOf(activeUrl) : '';
  localState = await S.load();
  await clerk.load({
    afterSignOutUrl: popupUrl,
    signInForceRedirectUrl: popupUrl,
    signUpForceRedirectUrl: popupUrl,
    allowedRedirectOrigins: [extensionRoot],
    allowedRedirectProtocols: ['chrome-extension:'],
  });
  clerk.addListener(() => {
    render();
    if (clerk.session && !cloud && !busy) void refreshCloud(true);
  });
  render();
  if (clerk.session) await refreshCloud(true);
}

init().catch((error) => {
  app.innerHTML = `<section class="fatal"><h1>FocusLock couldn't start</h1><p>${escapeHtml(error?.message || error)}</p><p>Rebuild the extension and verify its Clerk configuration.</p></section>`;
});
