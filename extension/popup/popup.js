import { createClerkClient } from '@clerk/chrome-extension/client';

const publishableKey = process.env.CLERK_PUBLISHABLE_KEY;
const syncHost = process.env.CLERK_SYNC_HOST;
const M = self.FocusLockMatcher;
const S = self.FocusLockStore;
const P = self.FocusLockPolicy;
const app = document.getElementById('app');
let clerk = null;
let renderedAccountId = null;
let accountError = '';

let activeTab = null;
let activeUrl = '';
let domain = '';
let localState = null;
let cloud = null;
let featureStatus = null;
let busy = false;
let message = '';
let messageTimer = null;
let featureBusy = false;

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
  const fresh = Date.now() - device.lastSeen < 20 * 60 * 1000;
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
  if (!clerk?.loaded) return;
  const frogInput = document.activeElement?.id === 'frogTitleInput' ? document.activeElement : null;
  const frogDraft = frogInput?.value || '';
  const frogSelection = frogInput ? [frogInput.selectionStart, frogInput.selectionEnd] : null;
  app.setAttribute('aria-busy', String(busy));
  const signedIn = Boolean(clerk.user && clerk.session);
  const strict = localState?.strictMode === true
    && (!Number(localState.strictEndsAt) || Number(localState.strictEndsAt) > Date.now());
  const strictActive = featureStatus?.strictMode === true || strict;
  const savedBalance = localState?.cloudPolicy?.state ? P.effectiveBalance(localState) : null;
  const localSeconds = localState?.stats?.[S.todayKey()]?.[domain] || 0;
  const localTotal = Object.values(localState?.stats?.[S.todayKey()] || {}).reduce((sum, value) => sum + Number(value || 0), 0);
  const totalSeconds = signedIn && cloud?.summary ? cloud.summary.totalTrackedSeconds : localTotal;
  const browserDevice = cloud?.devices?.find((device) => device.platform === 'browser');
  const androidDevice = cloud?.devices?.find((device) => device.platform === 'android');
  const browser = signedIn ? deviceStatus(browserDevice, 'Chrome extension') : { state: 'off', title: 'Chrome extension', detail: 'Sign in to sync tracking' };
  const android = signedIn ? deviceStatus(androidDevice, 'Android app') : { state: 'off', title: 'Android app', detail: 'Waiting for account connection' };
  const initials = clerk.user?.firstName?.[0] || clerk.user?.primaryEmailAddress?.emailAddress?.[0] || 'F';
  const frog = featureStatus?.frog;
  const showFrog = frog?.enabled === true;
  const frogPercent = frog?.requiredSeconds > 0 ? Math.min(100, Math.round(frog.trackedSeconds / frog.requiredSeconds * 100)) : 0;

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
        <div><p class="label" id="today-heading">Screen time today</p><h1>${formatDuration(totalSeconds)}</h1><p class="earned-balance">Focus available <strong>${savedBalance == null ? '—' : formatDuration(savedBalance)}</strong></p></div>
        <p class="summary-meta">${cloud?.lastWarning ? escapeHtml(cloud.lastWarning) : cloud?.summary ? `Across ${cloud?.devices?.length || 1} connected device${(cloud?.devices?.length || 1) === 1 ? '' : 's'}` : 'This browser · sync pending'}</p>
      </section>

      <section class="connections" aria-label="Connection status">
        <article class="connection"><span class="device-icon">${icon('globe')}</span><div><strong>${escapeHtml(browser.title)}</strong><p>${escapeHtml(browser.detail)}</p></div><span class="state ${browser.state}">${browser.state === 'ok' ? 'Connected' : browser.state === 'idle' ? 'Attention' : 'Offline'}</span></article>
        <article class="connection"><span class="device-icon">${icon('phone')}</span><div><strong>${escapeHtml(android.title)}</strong><p>${escapeHtml(android.detail)}</p></div><span class="state ${android.state}">${android.state === 'ok' ? 'Connected' : android.state === 'idle' ? 'Last seen' : 'Not linked'}</span></article>
      </section>
    `}

    ${accountError ? `<p class="error" role="alert">${escapeHtml(accountError)} <button class="text-button" id="account-retry" type="button">Open account settings</button></p>` : ''}

    <section class="current-site">
      <div class="site-heading"><div><p class="label">Current website</p><h2>${escapeHtml(domain || 'Chrome page')}</h2></div><strong>${formatDuration(localSeconds)}</strong></div>
      <div class="progress" role="progressbar" aria-label="Current website share of today" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.min(100, totalSeconds ? Math.round(localSeconds / totalSeconds * 100) : 0)}"><i style="width:${Math.min(100, totalSeconds ? localSeconds / totalSeconds * 100 : 0)}%"></i></div>
      <div class="site-actions">
        <button class="primary" id="block-site" ${!domain || strictActive ? 'disabled' : ''}>Block this site</button>
        <button class="secondary" id="allow-site" ${!domain ? 'disabled' : ''}>Allow 5 min</button>
      </div>
      ${strictActive ? '<p class="strict-status" role="status">Strict Mode is active. Boundaries are locked until it ends.</p>' : ''}
    </section>

    ${showFrog ? `<section class="frog-card" aria-labelledby="frog-heading">
      <div class="frog-head"><div><p class="label">Eat the Frog · Chrome</p><h2 id="frog-heading">${escapeHtml(frog.frog?.title || 'Choose today’s task')}</h2></div><span class="state ${frog.locked ? 'idle' : 'ok'}">${frog.locked ? 'Active' : 'Complete'}</span></div>
      <p class="frog-copy">${frog.frog ? `${formatDuration(frog.trackedSeconds)} of ${formatDuration(frog.requiredSeconds)} focused${frog.tickedOff ? ' · task marked done' : ' · mark done when finished'}` : 'Choose the task you need to finish before opening boundary websites.'}</p>
      ${frog.frog ? `<div class="progress" role="progressbar" aria-label="Frog focus progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${frogPercent}"><i style="width:${frogPercent}%"></i></div>` : ''}
      ${!frog.frog && frog.armed && frog.locked ? `<div class="frog-select"><input id="frogTitleInput" maxlength="200" placeholder="Today’s important task" aria-label="Today’s important task" value="${escapeHtml(frogDraft)}" ${featureBusy ? 'disabled' : ''}><button class="primary" id="frogSelect" ${featureBusy ? 'disabled' : ''}>Choose</button></div>` : ''}
      ${frog.frog ? `<div class="frog-actions">${featureStatus?.timer
        ? `<span class="frog-timer">Focus session running</span><button class="secondary" id="frogTimerFinish" ${featureBusy ? 'disabled' : ''}>Finish session</button>`
        : (frog.locked ? `<button class="primary" id="frogTimerStart" ${featureBusy ? 'disabled' : ''}>Focus ${Math.max(1, Math.ceil((frog.requiredSeconds - frog.trackedSeconds) / 60))} min</button>` : '')}
        <button class="secondary" id="frogDone" ${featureBusy ? 'disabled' : ''}>${frog.tickedOff ? 'Task not finished' : 'Mark task done'}</button></div>` : ''}
    </section>` : ''}

    ${message ? `<p class="feedback" role="status">${escapeHtml(message)}</p>` : ''}
    ${cloud?.error ? `<p class="error" role="alert">Sync paused: ${escapeHtml(cloud.error)}</p>` : ''}

    <footer><button class="text-button" id="settings">Account &amp; sync</button>${signedIn ? '<button class="text-button" id="signout">Sign out</button>' : ''}</footer>
  `;

  bindEvents(signedIn);
  if (frogInput) {
    const nextInput = document.getElementById('frogTitleInput');
    nextInput?.focus();
    if (frogSelection && Number.isInteger(frogSelection[0]) && Number.isInteger(frogSelection[1])) {
      nextInput?.setSelectionRange(...frogSelection);
    }
  }
}

function flash(text) {
  message = text;
  render();
  clearTimeout(messageTimer);
  messageTimer = setTimeout(() => { message = ''; render(); }, 2800);
}

function bindEvents(signedIn) {
  document.getElementById('settings')?.addEventListener('click', () => openAccountPage(signedIn ? 'profile' : 'signin'));
  document.getElementById('account')?.addEventListener('click', () => openAccountPage(signedIn ? 'profile' : 'signin'));
  document.getElementById('signin')?.addEventListener('click', () => openAccountPage('signin'));
  document.getElementById('account-retry')?.addEventListener('click', () => openAccountPage('signin'));
  document.getElementById('signout')?.addEventListener('click', async () => {
    try { await clerk.signOut(); await chrome.runtime.sendMessage({ type: 'cloudSignOut' }); cloud = null; render(); }
    catch (error) { flash(error?.message || 'Could not sign out.'); }
  });
  document.getElementById('sync')?.addEventListener('click', () => refreshCloud(true));
  document.getElementById('allow-site')?.addEventListener('click', async () => {
    try {
      const result = await chrome.runtime.sendMessage({ type: 'snooze', url: activeUrl, minutes: 5 });
      if (result?.ok !== true) throw new Error(result?.error || 'This site cannot be paused right now.');
      flash('Allowed for 5 minutes.');
    } catch (error) { flash(error?.message || 'Could not allow this website.'); }
  });
  document.getElementById('frogSelect')?.addEventListener('click', () => runFeatureAction({
    type: 'frogSelect', title: document.getElementById('frogTitleInput')?.value || '',
  }, 'Frog task saved.'));
  document.getElementById('frogTimerStart')?.addEventListener('click', () => runFeatureAction({
    type: 'focusTimerStart', minutes: Math.max(1, Math.ceil(((featureStatus?.frog?.requiredSeconds || 60) - (featureStatus?.frog?.trackedSeconds || 0)) / 60)),
  }, 'Focus session started.'));
  document.getElementById('frogTimerFinish')?.addEventListener('click', () => runFeatureAction({ type: 'focusTimerFinish' }, 'Focus progress saved.'));
  document.getElementById('frogDone')?.addEventListener('click', () => runFeatureAction({
    type: 'frogTick', tickedOff: !featureStatus?.frog?.tickedOff,
  }, featureStatus?.frog?.tickedOff ? 'Task marked unfinished.' : 'Task marked done.'));
  document.getElementById('block-site')?.addEventListener('click', async () => {
    if (signedIn) {
      let result;
      try { result = await chrome.runtime.sendMessage({ type: 'setSharedSite', domain, isBlocked: true }); }
      catch (error) { flash(error?.message || 'Could not sync this website.'); return; }
      if (!result?.ok) { flash(result?.error || 'Could not sync this website.'); return; }
      localState = await S.load();
      flash(`Blocked ${domain} on your account`);
      return;
    }
    let outcome = 'blocked';
    try { await S.update((state) => {
      if (state.strictMode && (!state.strictEndsAt || state.strictEndsAt > Date.now())) { outcome = 'strict'; return state; }
      const list = state.lists.find((item) => item.id === 'list_social') || state.lists[0];
      if (!list) { outcome = 'missing'; return state; }
      if (list.lockedUntil > Date.now()) { outcome = 'locked'; return state; }
      if (!list.sites.includes(domain)) list.sites.push(domain);
      list.enabled = true;
      return state;
    });
      if (outcome !== 'blocked') {
        flash(outcome === 'strict' ? 'Strict Mode is active. Boundaries are locked until it ends.' : outcome === 'locked' ? 'This block list is frozen and cannot be edited.' : 'Create a block list before blocking this site.');
        return;
      }
      await chrome.runtime.sendMessage({ type: 'refresh' });
      localState = await S.load();
      flash(`Blocked ${domain}`);
    } catch (error) { flash(error?.message || 'Could not block this website.'); }
  });
}

async function runFeatureAction(action, successMessage) {
  if (featureBusy) return;
  featureBusy = true;
  render();
  let feedback = successMessage;
  try {
    const result = await chrome.runtime.sendMessage(action);
    if (!result?.ok) throw new Error(result?.error || 'FocusLock could not save this Frog update.');
    featureStatus = await chrome.runtime.sendMessage({ type: 'featureStatus' });
    localState = await S.load();
  } catch (error) { feedback = error?.message || 'FocusLock could not save this Frog update.'; }
  featureBusy = false;
  message = feedback;
  render();
  clearTimeout(messageTimer);
  messageTimer = setTimeout(() => { message = ''; render(); }, 2800);
}

function openAccountPage(view) {
  const url = new URL(chrome.runtime.getURL('options/options.html'));
  url.searchParams.set('account', view);
  chrome.tabs.create({ url: url.href });
  window.close();
}

async function refreshCloud(forceSync) {
  if (!clerk?.session) return;
  const accountId = clerk.user?.id;
  const session = clerk.session;
  busy = true;
  render();
  try {
    const result = await chrome.runtime.sendMessage({ type: 'cloudSnapshot', sync: Boolean(forceSync) });
    if (clerk.user?.id === accountId && clerk.session === session) cloud = result;
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
  featureStatus = await chrome.runtime.sendMessage({ type: 'featureStatus' }).catch(() => null);
  let authTimeout;
  try {
    clerk = await Promise.race([
      createClerkClient({ publishableKey, syncHost, background: true }),
      new Promise((_, reject) => { authTimeout = setTimeout(() => reject(new Error('Account connection timed out')), 5000); }),
    ]);
  } catch {
    accountError = 'Account connection is unavailable. Local tracking and boundaries are still ready.';
    clerk = { loaded: true, user: null, session: null, addListener: () => () => {} };
  } finally { clearTimeout(authTimeout); }
  renderedAccountId = clerk.user?.id || null;
  clerk.addListener?.(() => {
    const accountId = clerk.user?.id || null;
    if (accountId !== renderedAccountId) { cloud = null; renderedAccountId = accountId; }
    render();
    if (clerk.session && !cloud && !busy) void refreshCloud(true);
  });
  render();
  if (clerk.session) {
    try {
      await chrome.runtime.sendMessage({ type: 'cloudAuthRefresh' });
      await refreshCloud(true);
    } catch { accountError = 'Account sync is unavailable. Local tracking and boundaries are still active.'; render(); }
  }
}

chrome.storage.onChanged?.addListener((changes, area) => {
  if (area !== 'local' || !changes[S.KEY]) return;
  void Promise.all([S.load(), chrome.runtime.sendMessage({ type: 'featureStatus' }).catch(() => null)])
    .then(([latest, status]) => { localState = latest; featureStatus = status; render(); }).catch(() => {});
});

init().catch((error) => {
  app.innerHTML = `<section class="fatal"><h1>FocusLock couldn't start</h1><p>${escapeHtml(error?.message || error)}</p><p>Rebuild the extension and verify its Clerk configuration.</p></section>`;
});
