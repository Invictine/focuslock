const { test } = require('node:test');
const assert = require('node:assert/strict');
const { registerReportTarget } = require('../plugins/with-apple-targets.cjs');
const targets = require('@bacons/apple-targets/build/target');
const configurations = require('@bacons/apple-targets/build/configuration-list');

test('monitor and report remain separate native target types across repeated prebuild registration', () => {
  registerReportTarget(targets, configurations);
  registerReportTarget(targets, configurations);
  assert.equal(targets.KNOWN_EXTENSION_POINT_IDENTIFIERS['com.apple.deviceactivity.monitor-extension'], 'device-activity-monitor');
  assert.equal(targets.KNOWN_EXTENSION_POINT_IDENTIFIERS['com.apple.deviceactivityui.report-extension'], 'device-activity-report');
  const projectTarget = (identifier) => ({
    props: { productType: 'com.apple.product-type.app-extension' },
    getDefaultConfiguration: () => ({ props: { buildSettings: {} }, getInfoPlist: () => ({ NSExtension: { NSExtensionPointIdentifier: identifier } }) }),
  });
  const monitor = projectTarget('com.apple.deviceactivity.monitor-extension');
  const report = projectTarget('com.apple.deviceactivityui.report-extension');
  assert.equal(targets.isNativeTargetOfType(monitor, 'device-activity-monitor'), true);
  assert.equal(targets.isNativeTargetOfType(report, 'device-activity-monitor'), false);
  assert.equal(targets.isNativeTargetOfType(report, 'device-activity-report'), true);
  assert.equal(targets.isNativeTargetOfType(monitor, 'device-activity-report'), false);
  assert.deepEqual(targets.getTargetInfoPlistForType('device-activity-report'), { NSExtension: { NSExtensionPointIdentifier: 'com.apple.deviceactivityui.report-extension' } });
});
