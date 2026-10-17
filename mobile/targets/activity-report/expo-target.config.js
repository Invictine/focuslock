/** @type {import('@bacons/apple-targets/app.plugin').Config} */
module.exports = {
  // apple-targets does not yet expose a Device Activity Report target type;
  // the explicit Info.plist below configures Apple's report extension point.
  type: 'device-activity-monitor',
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
