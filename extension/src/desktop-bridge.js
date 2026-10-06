(function (scope) {
  'use strict';

  const HOST_NAME = 'com.focuslock.browser';
  const ALARM_NAME = 'focuslock-desktop-bridge-reconnect';
  const HEARTBEAT_MS = 2000;
  const RETRY_MS = 5000;
  const FAST_MISSING_HOST_ATTEMPTS = 6;
  const MAX_WINDOWS = 128;
  const MAX_TITLE_LENGTH = 512;

  function makeBridge() {
    let api = null;
    let running = false;
    let port = null;
    let portListeners = [];
    let generation = 0;
    let collectionGeneration = 0;
    let heartbeatTimer = null;
    let reconnectTimer = null;
    let collecting = false;
    let collectionDirty = false;
    let listeners = [];
    let missingHostFailures = 0;
    let connectedAt = 0;

    function listen(event, handler) {
      if (!event || typeof event.addListener !== 'function') return;
      event.addListener(handler);
      listeners.push([event, handler]);
    }

    function safely(promise) {
      return Promise.resolve(promise).catch(() => undefined);
    }

    async function collectSnapshot() {
      const windowsPromise = safely(api.windows.getAll({ populate: true }));
      const permissionsPromise = safely(api.permissions.contains({
        origins: ['http://*/*', 'https://*/*']
      }));
      const incognitoPromise = safely(api.extension.isAllowedIncognitoAccess());
      const [windows, allUrls, incognitoAllowed] = await Promise.all([
        windowsPromise,
        permissionsPromise,
        incognitoPromise
      ]);

      const reports = [];
      for (const window of Array.isArray(windows) ? windows : []) {
        if (window.type !== 'normal' || window.state === 'minimized') continue;
        const activeTab = Array.isArray(window.tabs)
          ? window.tabs.find(tab => tab && tab.active)
          : null;
        if (!activeTab) continue;

        const report = {
          id: Number(window.id),
          title: String(activeTab.title || '').slice(0, MAX_TITLE_LENGTH),
          left: Number(window.left) || 0,
          top: Number(window.top) || 0,
          width: Number(window.width) || 0,
          height: Number(window.height) || 0
        };
        if (!Number.isFinite(report.id)) continue;
        reports.push(report);
        if (reports.length >= MAX_WINDOWS) break;
      }

      return {
        type: 'heartbeat',
        version: 1,
        allUrls: allUrls === true,
        incognitoAllowed: incognitoAllowed === true,
        windows: reports
      };
    }

    function requestHeartbeat() {
      if (!running || !port) return;
      if (collecting) {
        collectionDirty = true;
        return;
      }
      collecting = true;
      collectionDirty = false;
      const currentPort = port;
      const currentGeneration = generation;
      const currentCollection = ++collectionGeneration;

      collectSnapshot().then(payload => {
        if (!running || currentPort !== port || currentGeneration !== generation) return;
        if (collectionDirty) {
          collectionDirty = false;
          collecting = false;
          requestHeartbeat();
          return;
        }
        try {
          currentPort.postMessage(payload);
        } catch (_) {
          disconnectCurrent(currentPort, currentGeneration);
        }
      }).catch(() => {
        // A transient Chrome API failure should not become an unhandled rejection.
      }).finally(() => {
        if (currentCollection !== collectionGeneration) return;
        collecting = false;
        if (collectionDirty && running && port) requestHeartbeat();
      });
    }

    function clearHeartbeatTimer() {
      if (heartbeatTimer !== null) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
      }
    }

    function ensureReconnectAlarm() {
      if (!api || !api.alarms || typeof api.alarms.create !== 'function') return;
      try {
        api.alarms.create(ALARM_NAME, { periodInMinutes: 1 });
      } catch (_) {
        // Alarms are unavailable in some extension contexts.
      }
    }

    function clearReconnectAlarm() {
      if (!api || !api.alarms || typeof api.alarms.clear !== 'function') return;
      try {
        const result = api.alarms.clear(ALARM_NAME);
        if (result && typeof result.catch === 'function') result.catch(() => {});
      } catch (_) {
        // Ignore unavailable alarm APIs.
      }
    }

    function scheduleRetry() {
      if (!running) return;
      if (reconnectTimer !== null) clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, RETRY_MS);
    }

    function disconnectCurrent(disconnectedPort, disconnectedGeneration, retry = true) {
      if (disconnectedPort !== port || disconnectedGeneration !== generation) return;
      for (const [event, handler] of portListeners) {
        try { event.removeListener(handler); } catch (_) { /* partial port API */ }
      }
      portListeners = [];
      port = null;
      generation += 1;
      clearHeartbeatTimer();
      ensureReconnectAlarm();
      if (retry) scheduleRetry();
    }

    function handleConnectFailure(message) {
      ensureReconnectAlarm();
      if (/not found|not registered|forbidden/i.test(String(message || ''))) {
        missingHostFailures += 1;
        if (missingHostFailures <= FAST_MISSING_HOST_ATTEMPTS) scheduleRetry();
        return;
      }
      scheduleRetry();
    }

    function connect() {
      if (!running || port || !api || !api.runtime || typeof api.runtime.connectNative !== 'function') return;
      if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }

      let nextPort;
      try {
        nextPort = api.runtime.connectNative(HOST_NAME);
      } catch (error) {
        handleConnectFailure(error && error.message);
        return;
      }
      if (!nextPort || !nextPort.onDisconnect || typeof nextPort.postMessage !== 'function') {
        handleConnectFailure('Native messaging host is unavailable.');
        return;
      }

      port = nextPort;
      connectedAt = Date.now();
      generation += 1;
      const ownGeneration = generation;
      clearReconnectAlarm();
      portListeners = [];
      const portListen = (event, handler) => {
        if (!event || typeof event.addListener !== 'function') return;
        event.addListener(handler);
        portListeners.push([event, handler]);
      };
      portListen(nextPort.onDisconnect, () => {
        // Read lastError inside the callback to consume Chrome's port error.
        let errorMessage = '';
        try { errorMessage = api.runtime.lastError && api.runtime.lastError.message || ''; }
        catch (_) { /* absent in test/non-Chrome contexts */ }
        if (Date.now() - connectedAt >= 30_000) missingHostFailures = 0;
        disconnectCurrent(nextPort, ownGeneration, false);
        handleConnectFailure(errorMessage || 'Native host exited.');
      });
      if (nextPort.onMessage) portListen(nextPort.onMessage, () => {});

      requestHeartbeat();
      clearHeartbeatTimer();
      heartbeatTimer = setInterval(requestHeartbeat, HEARTBEAT_MS);
    }

    function onAlarm(alarm) {
      if (alarm && alarm.name === ALARM_NAME && running && !port) connect();
    }

    function start(chromeApi) {
      if (running) return;
      if (!chromeApi || !chromeApi.runtime || typeof chromeApi.runtime.connectNative !== 'function' ||
          !chromeApi.windows || typeof chromeApi.windows.getAll !== 'function' ||
          !chromeApi.permissions || typeof chromeApi.permissions.contains !== 'function' ||
          !chromeApi.extension || typeof chromeApi.extension.isAllowedIncognitoAccess !== 'function') return;
      api = chromeApi;
      running = true;

      const changed = () => requestHeartbeat();
      listen(api.tabs && api.tabs.onActivated, changed);
      listen(api.tabs && api.tabs.onUpdated, (tabId, changeInfo) => {
        if (!changeInfo || changeInfo.title !== undefined || changeInfo.status === 'complete') changed();
      });
      listen(api.windows && api.windows.onFocusChanged, changed);
      listen(api.windows && api.windows.onBoundsChanged, changed);
      listen(api.windows && api.windows.onCreated, changed);
      listen(api.windows && api.windows.onRemoved, changed);
      listen(api.permissions && api.permissions.onAdded, changed);
      listen(api.permissions && api.permissions.onRemoved, changed);
      listen(api.alarms && api.alarms.onAlarm, onAlarm);

      connect();
      if (!port) ensureReconnectAlarm();
    }

    function stop() {
      if (!running && !api) return;
      running = false;
      clearHeartbeatTimer();
      if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      clearReconnectAlarm();
      for (const [event, handler] of listeners) {
        try { event.removeListener(handler); } catch (_) { /* tolerate partial APIs */ }
      }
      listeners = [];
      const oldPort = port;
      port = null;
      generation += 1;
      for (const [event, handler] of portListeners) {
        try { event.removeListener(handler); } catch (_) { /* tolerate partial APIs */ }
      }
      portListeners = [];
      if (oldPort && typeof oldPort.disconnect === 'function') {
        try { oldPort.disconnect(); } catch (_) { /* already disconnected */ }
      }
      api = null;
      collecting = false;
      collectionDirty = false;
      collectionGeneration += 1;
    }

    return { start, stop };
  }

  scope.FocusLockDesktopBridge = makeBridge();
})(typeof self !== 'undefined' ? self : globalThis);
