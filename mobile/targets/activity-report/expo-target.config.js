/** @type {import('@bacons/apple-targets/app.plugin').Config} */
module.exports = {
  // Registered by plugins/with-apple-targets.cjs until upstream supports it.
  type: 'device-activity-report',
  bundleIdentifier: '.activity-report',
  name: 'FocusLockActivityReport',
  displayName: 'FocusLock Screen Time Report',
  deploymentTarget: '17.4',
  frameworks: ['FamilyControls', 'DeviceActivity', 'SwiftUI'],
  entitlements: {
    'com.apple.developer.family-controls': true,
    'com.apple.security.application-groups': ['group.com.focuslock.app.ios'],
  },
};
