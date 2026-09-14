import { createClerkClient } from '@clerk/chrome-extension/client';

const publishableKey = process.env.CLERK_PUBLISHABLE_KEY;
const optionsUrl = chrome.runtime.getURL('options/options.html');
const extensionRoot = chrome.runtime.getURL('.');
const clerk = createClerkClient({ publishableKey });

const elements = {
  title: document.getElementById('accountTitle'),
  detail: document.getElementById('accountDetail'),
  state: document.getElementById('accountState'),
  signIn: document.getElementById('accountSignIn'),
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

let authMounted = false;
let cloud = null;
let syncing = false;

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
  const fresh = Date.now() - Number(device.lastSeen || 0) < 10 * 60 * 1000;
  const good = device.trackingStatus === 'active' && fresh;
  return `<span class="badge ${good ? 'good' : ''}">${escapeHtml(label)} ${good ? 'connected' : relativeTime(device.lastSeen)}</span>`;
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
    const fresh = Date.now() - Number(device.lastSeen || 0) < 10 * 60 * 1000;
    const healthy = device.trackingStatus === 'active' && fresh;
    const meta = [
      platformLabel(device.platform),
      device.appVersion ? 'v' + device.appVersion : '',
      device.statusDetail || (healthy ? 'Tracking active' : 'Tracking needs attention'),
    ].filter(Boolean).join(' · ');
    return `<div class="site-row"><div><strong>${escapeHtml(device.name || platformLabel(device.platform))}</strong>`
      + `<span class="mut">${escapeHtml(meta)}</span></div>`
      + `<span class="badge ${healthy ? 'on' : ''}">${healthy ? '● connected' : escapeHtml(relativeTime(device.lastSeen))}</span></div>`;
  }).join('');
}

function unmountAuth() {
  if (!authMounted) return;
  clerk.unmountSignIn(elements.auth);
  authMounted = false;
  elements.auth.hidden = true;
  history.replaceState({}, '', optionsUrl);
}

function themeToken(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function showAuth() {
  if (clerk.session) return;
  elements.auth.hidden = false;
  if (!authMounted) {
    clerk.mountSignIn(elements.auth, {
      routing: 'hash',
      forceRedirectUrl: optionsUrl,
      fallbackRedirectUrl: optionsUrl,
      signUpForceRedirectUrl: optionsUrl,
      signUpFallbackRedirectUrl: optionsUrl,
      afterSignOutUrl: optionsUrl,
      appearance: {
        variables: {
          colorPrimary: themeToken('--fl-primary'),
          colorTextOnPrimary: themeToken('--fl-on-primary'),
          colorText: themeToken('--fl-on-surface'),
          colorTextSecondary: themeToken('--fl-on-surface-variant'),
          colorBackground: themeToken('--fl-surface-container-low'),
          colorInputBackground: themeToken('--fl-surface-container-lowest'),
          colorInputText: themeToken('--fl-on-surface'),
          borderRadius: '12px',
        },
        elements: {
          socialButtonsRoot: { display: 'none' },
          dividerRow: { display: 'none' },
        },
      },
    });
    authMounted = true;
  }
  elements.auth.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function render() {
  const signedIn = Boolean(clerk.user && clerk.session);
  elements.signIn.hidden = signedIn;
  elements.sync.hidden = !signedIn;
  elements.signOut.hidden = !signedIn;
  elements.sync.disabled = syncing;

  if (!signedIn) {
    if (elements.badge) { elements.badge.textContent = 'Not connected'; elements.badge.classList.remove('prio'); }
    if (elements.cloud) elements.cloud.textContent = '';
    if (elements.pitch) elements.pitch.hidden = false;
    if (elements.devicesCard) elements.devicesCard.hidden = true;
    elements.title.textContent = 'Connect your FocusLock account';
    elements.detail.textContent = authMounted
      ? 'Use the same account email. Clerk will send a verification code; Google OAuth cannot return directly to a Chrome extension.'
      : 'Sign in here to sync Chrome website time with Android and Windows.';
    elements.state.innerHTML = '<span class="badge">Chrome local only</span><span class="badge">Cloud sync off</span>';
    return;
  }

  unmountAuth();
  const email = clerk.user.primaryEmailAddress?.emailAddress || clerk.user.fullName || 'FocusLock account';
  const devices = Array.isArray(cloud?.devices) ? cloud.devices : [];
  const browser = devices.find((device) => device.platform === 'browser');
  const android = devices.find((device) => device.platform === 'android');
  if (elements.badge) { elements.badge.textContent = 'Priority Active'; elements.badge.classList.add('prio'); }
  elements.title.textContent = `Connected as ${email}`;
  elements.detail.textContent = cloud?.lastSyncAt ? `Account data ${relativeTime(cloud.lastSyncAt)}.` : 'Your account is connected. Syncing device status...';
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

elements.signIn.addEventListener('click', showAuth);
if (elements.signInPitch) elements.signInPitch.addEventListener('click', showAuth);
elements.sync.addEventListener('click', () => refreshCloud(true));
elements.signOut.addEventListener('click', async () => {
  await clerk.signOut();
  await chrome.runtime.sendMessage({ type: 'cloudSignOut' }).catch(() => {});
  cloud = null;
  render();
});

async function init() {
  await clerk.load({
    afterSignOutUrl: optionsUrl,
    signInForceRedirectUrl: optionsUrl,
    signUpForceRedirectUrl: optionsUrl,
    allowedRedirectOrigins: [extensionRoot],
    allowedRedirectProtocols: ['chrome-extension:'],
  });
  clerk.addListener(() => {
    render();
    if (clerk.session && !cloud && !syncing) void refreshCloud(true);
  });
  render();
  if (clerk.session) await refreshCloud(true);
  else if (new URLSearchParams(location.search).get('account') === 'signin') showAuth();
}

init().catch((error) => {
  elements.error.textContent = `Account connection could not start: ${error?.message || error}`;
  elements.error.hidden = false;
});
