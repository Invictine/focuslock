/** @type {import('@bacons/apple-targets/app.plugin').Config} */
module.exports = {
  type: 'device-activity-monitor',
  bundleIdentifier: '.activity-monitor',
  name: 'FocusLockActivityMonitor',
  displayName: 'FocusLock Screen Time Monitor',
  deploymentTarget: '17.4',
  frameworks: ['FamilyControls', 'ManagedSettings', 'DeviceActivity'],
  entitlements: {
    'com.apple.developer.family-controls': true,
    'com.apple.security.application-groups': ['group.com.focuslock.app.ios'],
  },
};
