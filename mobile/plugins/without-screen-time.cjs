const FAMILY_CONTROLS_ENTITLEMENT = 'com.apple.developer.family-controls';
const APP_GROUPS_ENTITLEMENT = 'com.apple.security.application-groups';
const APPLE_SIGN_IN_ENTITLEMENT = 'com.apple.developer.applesignin';
const SCREEN_TIME_INFO_KEY = 'FocusLockScreenTimeEnabled';

function stripScreenTimeEntitlements(entitlements) {
  const result = { ...(entitlements || {}) };
  delete result[FAMILY_CONTROLS_ENTITLEMENT];
  delete result[APP_GROUPS_ENTITLEMENT];
  delete result[APPLE_SIGN_IN_ENTITLEMENT];
  return result;
}

function disableScreenTimeInInfoPlist(infoPlist) {
  return { ...(infoPlist || {}), [SCREEN_TIME_INFO_KEY]: false };
}

function withScreenTimeDisabled(config, configPlugins) {
  const plugins = configPlugins || require('@expo/config-plugins');
  const withInfoDisabled = plugins.withInfoPlist(config, (modConfig) => {
    modConfig.modResults = disableScreenTimeInInfoPlist(modConfig.modResults);
    return modConfig;
  });
  return plugins.withEntitlementsPlist(withInfoDisabled, (modConfig) => {
    modConfig.modResults = stripScreenTimeEntitlements(modConfig.modResults);
    return modConfig;
  });
}

withScreenTimeDisabled.stripScreenTimeEntitlements = stripScreenTimeEntitlements;
withScreenTimeDisabled.disableScreenTimeInInfoPlist = disableScreenTimeInInfoPlist;
withScreenTimeDisabled.SCREEN_TIME_INFO_KEY = SCREEN_TIME_INFO_KEY;
withScreenTimeDisabled.FAMILY_CONTROLS_ENTITLEMENT = FAMILY_CONTROLS_ENTITLEMENT;
withScreenTimeDisabled.APP_GROUPS_ENTITLEMENT = APP_GROUPS_ENTITLEMENT;
withScreenTimeDisabled.APPLE_SIGN_IN_ENTITLEMENT = APPLE_SIGN_IN_ENTITLEMENT;

module.exports = withScreenTimeDisabled;
