// apple-targets 5 has no report target definition. Treating a report as another
// monitor makes its type-based target finder overwrite the real monitor.
// Register the missing Apple extension type using the same generic appex build
// settings, keeping target detection and signing identifiers distinct.
function registerReportTarget(targets, configurations) {
  const type = 'device-activity-report';
  const identifier = 'com.apple.deviceactivityui.report-extension';
  targets.TARGET_REGISTRY[type] = {
    extensionPointIdentifier: identifier,
    frameworks: ['DeviceActivity', 'SwiftUI'],
    appGroupsByDefault: true,
    displayName: 'Device Activity Report',
  };
  targets.KNOWN_EXTENSION_POINT_IDENTIFIERS[identifier] = type;
  targets.SHOULD_USE_APP_GROUPS_BY_DEFAULT[type] = true;
  if (!targets.__focuslockReportType) {
    const originalPlist = targets.getTargetInfoPlistForType;
    targets.getTargetInfoPlistForType = (value) => value === type
      ? { NSExtension: { NSExtensionPointIdentifier: identifier } }
      : originalPlist(value);
    const originalConfiguration = configurations.createConfigurationListForType;
    configurations.createConfigurationListForType = (project, props) => originalConfiguration(project,
      props.type === type ? { ...props, type: 'device-activity-monitor' } : props);
    targets.__focuslockReportType = true;
  }
}

module.exports = function withFocusLockAppleTargets(config) {
  const targets = require('@bacons/apple-targets/build/target');
  const configurations = require('@bacons/apple-targets/build/configuration-list');
  registerReportTarget(targets, configurations);
  return require('@bacons/apple-targets/app.plugin')(config);
};
module.exports.registerReportTarget = registerReportTarget;
