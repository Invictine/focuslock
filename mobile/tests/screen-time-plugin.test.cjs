const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const withScreenTime = require('../plugins/with-screen-time.cjs');
const withoutScreenTime = require('../plugins/without-screen-time.cjs');

test('adds the Family Controls entitlement without changing other entitlements', () => {
  assert.deepEqual(
    withScreenTime.addFamilyControlsEntitlement({ 'aps-environment': 'development' }),
    {
      'aps-environment': 'development',
      'com.apple.developer.family-controls': true,
      'com.apple.security.application-groups': ['group.com.focuslock.app.ios'],
    },
  );
});

test('is idempotent when the plugin is applied repeatedly', () => {
  const first = withScreenTime.addFamilyControlsEntitlement({
    'com.apple.developer.family-controls': true,
    'aps-environment': 'development',
  });
  const second = withScreenTime.addFamilyControlsEntitlement(first);
  assert.deepEqual(second, first);
  assert.deepEqual(second['com.apple.security.application-groups'], ['group.com.focuslock.app.ios']);
});

test('keeps existing app groups and appends the shared Screen Time group only once', () => {
  const entitlements = {
    'com.apple.security.application-groups': ['group.example.shared', 'group.com.focuslock.app.ios'],
  };
  assert.deepEqual(withScreenTime.addFamilyControlsEntitlement(entitlements), {
    'com.apple.security.application-groups': ['group.example.shared', 'group.com.focuslock.app.ios'],
    'com.apple.developer.family-controls': true,
  });
});

test('uses Expo entitlements mod and preserves unrelated config', () => {
  const input = { name: 'FocusLock', ios: { bundleIdentifier: 'com.example.focuslock' } };
  let mod;
  const result = withScreenTime(input, {
    withEntitlementsPlist(config, action) {
      mod = action({ modResults: { 'aps-environment': 'development' } });
      return { ...config, screenTimeModApplied: true };
    },
  });

  assert.deepEqual(mod.modResults, {
    'aps-environment': 'development',
    'com.apple.developer.family-controls': true,
    'com.apple.security.application-groups': ['group.com.focuslock.app.ios'],
  });
  assert.equal(result.ios.bundleIdentifier, 'com.example.focuslock');
  assert.equal(result.screenTimeModApplied, true);
});

test('Device Activity monitor target declares the shared app group and Family Controls', () => {
  const targetRoot = path.join(__dirname, '..', 'targets', 'activity-monitor');
  const config = require(path.join(targetRoot, 'expo-target.config.js'));
  const info = fs.readFileSync(path.join(targetRoot, 'Info.plist'), 'utf8');
  assert.equal(config.type, 'device-activity-monitor');
  assert.equal(config.bundleIdentifier, '.activity-monitor');
  assert.equal(config.deploymentTarget, '17.4');
  assert.deepEqual(config.frameworks, ['FamilyControls', 'ManagedSettings', 'DeviceActivity']);
  assert.equal(config.entitlements['com.apple.developer.family-controls'], true);
  assert.deepEqual(config.entitlements['com.apple.security.application-groups'], ['group.com.focuslock.app.ios']);
  assert.match(info, /com\.apple\.deviceactivity\.monitor-extension/);
  assert.match(info, /\$\(PRODUCT_MODULE_NAME\)\.FocusLockDeviceActivityMonitor/);
});

test('Device Activity report target declares Apple report extension point and shared capabilities', () => {
  const targetRoot = path.join(__dirname, '..', 'targets', 'activity-report');
  const config = require(path.join(targetRoot, 'expo-target.config.js'));
  const info = fs.readFileSync(path.join(targetRoot, 'Info.plist'), 'utf8');
  const source = fs.readFileSync(path.join(targetRoot, 'FocusLockDailyReportExtension.swift'), 'utf8');
  assert.equal(config.type, 'device-activity-monitor');
  assert.equal(config.bundleIdentifier, '.activity-report');
  assert.equal(config.deploymentTarget, '17.4');
  assert.deepEqual(config.frameworks, ['FamilyControls', 'DeviceActivity', 'SwiftUI']);
  assert.equal(config.entitlements['com.apple.developer.family-controls'], true);
  assert.deepEqual(config.entitlements['com.apple.security.application-groups'], ['group.com.focuslock.app.ios']);
  assert.match(info, /com\.apple\.deviceactivityui\.report-extension/);
  assert.doesNotMatch(info, /NSExtensionPrincipalClass|NSExtensionMainStoryboard/);
  assert.match(source, /totalActivityDuration/);
  assert.doesNotMatch(source, /URLSession|Convex|fetch\(/i);
});

test('monitor and report targets use distinct bundle identifier suffixes', () => {
  const monitor = require('../targets/activity-monitor/expo-target.config.js');
  const report = require('../targets/activity-report/expo-target.config.js');
  const identifiers = [monitor.bundleIdentifier, report.bundleIdentifier];
  assert.equal(new Set(identifiers).size, identifiers.length);
});

test('without-screen-time removes only capability entitlements and is idempotent', () => {
  const input = {
    'aps-environment': 'development',
    'com.apple.developer.family-controls': true,
    'com.apple.security.application-groups': ['group.com.focuslock.app.ios'],
    'com.apple.developer.applesignin': ['Default'],
  };
  const first = withoutScreenTime.stripScreenTimeEntitlements(input);
  const second = withoutScreenTime.stripScreenTimeEntitlements(first);
  assert.deepEqual(first, { 'aps-environment': 'development' });
  assert.deepEqual(second, first);
  assert.deepEqual(withoutScreenTime.disableScreenTimeInInfoPlist({ CFBundleName: 'FocusLock' }), {
    CFBundleName: 'FocusLock',
    FocusLockScreenTimeEnabled: false,
  });
});

test('without-screen-time composes Info.plist and entitlement mods while preserving unrelated config', () => {
  const input = { name: 'FocusLock', ios: { bundleIdentifier: 'com.example.focuslock' } };
  const applied = [];
  const fakePlugins = {
    withInfoPlist(config, action) {
      const result = action({ modResults: { CFBundleName: 'FocusLock' } });
      applied.push(['info', result.modResults]);
      return { ...config, infoPlistModApplied: true };
    },
    withEntitlementsPlist(config, action) {
      const result = action({ modResults: {
        'aps-environment': 'development',
        'com.apple.developer.family-controls': true,
        'com.apple.security.application-groups': ['group.com.focuslock.app.ios'],
        'com.apple.developer.applesignin': ['Default'],
      } });
      applied.push(['entitlements', result.modResults]);
      return { ...config, entitlementModApplied: true };
    },
  };
  const result = withoutScreenTime(input, fakePlugins);
  assert.deepEqual(applied, [
    ['info', { CFBundleName: 'FocusLock', FocusLockScreenTimeEnabled: false }],
    ['entitlements', { 'aps-environment': 'development' }],
  ]);
  assert.equal(result.ios.bundleIdentifier, 'com.example.focuslock');
  assert.equal(result.infoPlistModApplied, true);
  assert.equal(result.entitlementModApplied, true);
});
