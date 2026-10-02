import { createClerkClient } from '@clerk/chrome-extension/client';

const publishableKey = process.env.CLERK_PUBLISHABLE_KEY;
const syncHost = process.env.CLERK_SYNC_HOST;
const browserSignInUrl = process.env.CLERK_SIGN_IN_URL;
const optionsUrl = chrome.runtime.getURL('options/options.html');
const AUTH_PENDING_KEY = 'focuslock.browserAuthPending';
const AUTH_TAB_KEY = 'focuslock.browserAuthTabId';
const RETURN_TAB_KEY = 'focuslock.browserAuthReturnTabId';
const AUTH_DEADLINE_KEY = 'focuslock.browserAuthDeadline';
const AUTH_TIMEOUT_MS = 5 * 60 * 1000;
let clerk;

const elements = {
  title: document.getElementById('accountTitle'),
  detail: document.getElementById('accountDetail'),
  state: document.getElementById('accountState'),
  signIn: document.getElementById('accountSignIn'),
  emailSignIn: document.getElementById('accountEmailSignIn'),
  sync: document.getElementById('accountSync'),
  signOut: document.getElementById('accountSignOut'),
  error: document.getElementById('accountError'),
  auth: document.getElementById('accountAuth'),
  badge: document.getElementById('accountBadge'),
  cloud: document.getElementById('accountCloud'),
  pitch: document.getElementById('accountPitch'),
  signInPitch: document.getElementById('accountSignInPitch'),
  devicesCard: document.getElementById('accountDevicesCard'),
  deviceSummary: document.getElementById('accountDeviceSummary'),
  devices: document.getElementById('accountDevices'),
};

let authTabId = null;
let returnTabId = null;
let authTimer = null;
let authDeadline = 0;
let checkingAuth = false;
let cloud = null;
let syncing = false;
let openingBrowser = false;

function clearBrowserAuthPending() {
  for (const key of [AUTH_PENDING_KEY, AUTH_TAB_KEY, RETURN_TAB_KEY, AUTH_DEADLINE_KEY]) {
    sessionStorage.removeItem(key);
  }
}

function persistBrowserAuthPending() {
  sessionStorage.setItem(AUTH_PENDING_KEY, '1');
  sessionStorage.setItem(AUTH_DEADLINE_KEY, String(authDeadline));
  if (authTabId != null) sessionStorage.setItem(AUTH_TAB_KEY, String(authTabId));
  if (returnTabId != null) sessionStorage.setItem(RETURN_TAB_KEY, String(returnTabId));
}

function restoreBrowserAuthPending() {
  if (!sessionStorage.getItem(AUTH_PENDING_KEY)) return false;
  const savedDeadline = Number(sessionStorage.getItem(AUTH_DEADLINE_KEY));
  authDeadline = Number.isFinite(savedDeadline) && savedDeadline > Date.now()
    ? savedDeadline : Date.now() + AUTH_TIMEOUT_MS;
  const savedAuthTab = Number(sessionStorage.getItem(AUTH_TAB_KEY));
  const savedReturnTab = Number(sessionStorage.getItem(RETURN_TAB_KEY));
  authTabId = Number.isInteger(savedAuthTab) && savedAuthTab > 0 ? savedAuthTab : null;
  returnTabId = Number.isInteger(savedReturnTab) && savedReturnTab > 0 ? savedReturnTab : null;
  persistBrowserAuthPending();
  elements.title.textContent = 'Finish signing in in the new tab';
  elements.detail.textContent = 'Complete sign-in there. FocusLock will bring you back when your account connects.';
  return true;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[char]));
}

function relativeTime(timestamp) {
  if (!timestamp) return 'not synced yet';
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 60) return 'synced just now';
  if (seconds < 3600) return `synced ${Math.floor(seconds / 60)}m ago`;
  return `seen ${Math.floor(seconds / 3600)}h ago`;
}

function connectionBadge(label, device) {
  if (!device) return `<span class="badge">${escapeHtml(label)} not linked</span>`;
  const fresh = Date.now() - Number(device.lastSeen || 0) < 20 * 60 * 1000;
  const good = device.trackingStatus === 'active' && fresh;
  return `<span class="badge ${good ? 'good' : ''}">${escapeHtml(label)} ${good ? 'recently active' : relativeTime(device.lastSeen)}</span>`;
}

function platformLabel(platform) {
  if (platform === 'android') return 'Android';
  if (platform === 'windows') return 'Windows';
  if (platform === 'browser') return 'Chrome';
  return platform || 'Device';
}

function renderDevices(devices) {
  const list = Array.isArray(devices) ? devices : [];
  if (!elements.devices) return;
  if (!list.length) {
    elements.devices.innerHTML = '<p class="mut">No devices synced yet. Open your Android or Windows app to link it.</p>';
    return;
  }
  elements.devices.innerHTML = list.map((device) => {
    const fresh = Date.now() - Number(device.lastSeen || 0) < 20 * 60 * 1000;
    const healthy = device.trackingStatus === 'active' && fresh;
    const meta = [
      platformLabel(device.platform),
      device.appVersion ? 'v' + device.appVersion : '',
      device.statusDetail || (healthy ? 'Tracking recently active' : 'Tracking needs attention'),
    ].filter(Boolean).join(' · ');
    return `<div class="site-row"><div><strong>${escapeHtml(device.name || platformLabel(device.platform))}</strong>`
      + `<span class="mut">${escapeHtml(meta)}</span></div>`
      + `<span class="badge ${healthy ? 'on' : ''}">${healthy ? '● Recently active' : escapeHtml(relativeTime(device.lastSeen))}</span></div>`;
  }).join('');
}

async function checkBrowserSignIn() {
  if (checkingAuth || !authDeadline) return;
  if (Date.now() > authDeadline) {
    clearInterval(authTimer);
    authDeadline = 0;
    elements.signIn.disabled = false;
    clearBrowserAuthPending();
    elements.error.textContent = 'Sign-in has not reached FocusLock. Return here and try again.';
    elements.error.hidden = false;
    return;
  }
  checkingAuth = true;
  try {
    await clerk.client.reload();
    if (!clerk.session || !clerk.user) return;
    const background = await chrome.runtime.sendMessage({ type: 'cloudAuthRefresh' });
    if (!background?.signedIn) throw new Error(background?.error || 'The background tracker has not connected yet');
    clearInterval(authTimer);
    authDeadline = 0;
    clearBrowserAuthPending();
    render();
    if (returnTabId != null) {
      await chrome.tabs.update(returnTabId, { active: true });
      if (authTabId != null) await chrome.tabs.remove(authTabId).catch(() => {});
    }
    await refreshCloud(true);
  } catch (error) {
    elements.error.textContent = `Waiting for account connection: ${error?.message || error}`;
    elements.error.hidden = false;
  } finally {
    checkingAuth = false;
  }
}

async function openBrowserSignIn() {
  if (openingBrowser || authDeadline) return;
  openingBrowser = true;
  elements.signIn.disabled = true;
  elements.error.hidden = true;
  try {
    let signInUrl;
    try { signInUrl = new URL(browserSignInUrl); } catch { signInUrl = null; }
    if (!signInUrl || signInUrl.protocol !== 'https:') {
      elements.error.textContent = 'Browser sign-in is not configured. Rebuild the extension with its Clerk configuration.';
      elements.error.hidden = false;
      return;
    }
    render();
    authDeadline = Date.now() + AUTH_TIMEOUT_MS;
    persistBrowserAuthPending();
    returnTabId = (await chrome.tabs.getCurrent())?.id ?? null;
    persistBrowserAuthPending();
    authTabId = (await chrome.tabs.create({ url: browserSignInUrl, active: true })).id;
    persistBrowserAuthPending();
    clearInterval(authTimer);
    authTimer = setInterval(() => void checkBrowserSignIn(), 2000);
    void checkBrowserSignIn();
    elements.title.textContent = 'Finish signing in in the new tab';
    elements.detail.textContent = 'Complete sign-in there. FocusLock will bring you back when your account connects.';
  } catch (error) {
    clearInterval(authTimer);
    authDeadline = 0;
    clearBrowserAuthPending();
    elements.error.textContent = `Could not open browser sign-in: ${error?.message || error}. Please try again.`;
    elements.error.hidden = false;
  } finally {
    openingBrowser = false;
    elements.signIn.disabled = Boolean(authDeadline);
  }
}

function render() {
  const signedIn = Boolean(clerk.user && clerk.session);
  elements.signIn.hidden = signedIn;
  elements.signIn.disabled = openingBrowser || Boolean(authDeadline);
  if (elements.emailSignIn) elements.emailSignIn.hidden = true;
  elements.sync.hidden = !signedIn;
  elements.signOut.hidden = !signedIn;
  elements.sync.disabled = syncing;

  if (!signedIn) {
    if (elements.badge) { elements.badge.textContent = 'Not connected'; elements.badge.classList.remove('prio'); }
    if (elements.cloud) elements.cloud.textContent = '';
    if (elements.pitch) elements.pitch.hidden = true;
    if (elements.devicesCard) elements.devicesCard.hidden = true;
    elements.title.textContent = authDeadline ? 'Finish signing in in the new tab' : 'Connect your FocusLock account';
    elements.detail.textContent = authDeadline
      ? 'Complete sign-in there. FocusLock will bring you back when your account connects.'
      : 'Sign in securely in your browser to sync Chrome, Android, and Windows.';
    elements.state.innerHTML = '<span class="badge">Chrome local only</span><span class="badge">Cloud sync off</span>';
    return;
  }

  const email = clerk.user.primaryEmailAddress?.emailAddress || clerk.user.fullName || 'FocusLock account';
  const devices = Array.isArray(cloud?.devices) ? cloud.devices : [];
  const browser = devices.find((device) => device.platform === 'browser');
  const android = devices.find((device) => device.platform === 'android');
  if (elements.badge) { elements.badge.textContent = 'Priority Active'; elements.badge.classList.add('prio'); }
  elements.title.textContent = `Connected as ${email}`;
  elements.detail.textContent = cloud?.lastWarning
    || (cloud?.lastSyncAt ? `Account data ${relativeTime(cloud.lastSyncAt)}.` : 'Your account is connected. Syncing device status...');
  elements.state.innerHTML = connectionBadge('Chrome', browser) + connectionBadge('Android', android);
  if (elements.cloud) {
    elements.cloud.textContent = cloud?.lastSyncAt
      ? `Cloud ${relativeTime(cloud.lastSyncAt)} · ${devices.length} device${devices.length === 1 ? '' : 's'} linked`
      : 'Cloud connected · waiting for the first sync';
  }
  if (elements.pitch) elements.pitch.hidden = true;
  if (elements.devicesCard) elements.devicesCard.hidden = false;
  if (elements.deviceSummary) {
    elements.deviceSummary.textContent = devices.length
      ? `${devices.length} device${devices.length === 1 ? '' : 's'} on this account · last sync ${relativeTime(cloud?.lastSyncAt)}.`
      : 'No devices synced yet.';
  }
  renderDevices(devices);
}

async function refreshCloud(forceSync = false) {
  if (!clerk.session || syncing) return;
  syncing = true;
  render();
  elements.error.hidden = true;
  try {
    cloud = await chrome.runtime.sendMessage({ type: 'cloudSnapshot', sync: forceSync });
    if (cloud?.error) throw new Error(cloud.error);
  } catch (error) {
    elements.error.textContent = `Sync paused: ${error?.message || 'Could not reach the background tracker'}`;
    elements.error.hidden = false;
  } finally {
    syncing = false;
    render();
  }
}

elements.signIn.addEventListener('click', openBrowserSignIn);
if (elements.emailSignIn) elements.emailSignIn.addEventListener('click', openBrowserSignIn);
if (elements.signInPitch) elements.signInPitch.addEventListener('click', openBrowserSignIn);
elements.sync.addEventListener('click', () => refreshCloud(true));
elements.signOut.addEventListener('click', async () => {
  await clerk.signOut();
  await chrome.runtime.sendMessage({ type: 'cloudSignOut' }).catch(() => {});
  cloud = null;
  render();
});

async function init() {
  clerk = await createClerkClient({ publishableKey, syncHost, background: true });
  clerk.addListener(() => {
    render();
    if (clerk.session && !cloud && !syncing) void refreshCloud(true);
  });
  render();
  if (restoreBrowserAuthPending()) {
    render();
    authTimer = setInterval(() => void checkBrowserSignIn(), 2000);
    void checkBrowserSignIn();
  }
  if (clerk.session) {
    await chrome.runtime.sendMessage({ type: 'cloudAuthRefresh' });
    await refreshCloud(true);
  }
}

window.addEventListener('focus', () => {
  if (!sessionStorage.getItem('focuslock.browserAuthPending')) return;
  void checkBrowserSignIn();
});

init().catch((error) => {
  elements.error.textContent = `Account connection could not start: ${error?.message || error}`;
  elements.error.hidden = false;
});
