const FAMILY_CONTROLS_ENTITLEMENT = 'com.apple.developer.family-controls';
const APP_GROUPS_ENTITLEMENT = 'com.apple.security.application-groups';
const FOCUSLOCK_APP_GROUP = 'group.com.focuslock.app.ios';

function addFamilyControlsEntitlement(entitlements) {
  const groups = Array.isArray(entitlements?.[APP_GROUPS_ENTITLEMENT])
    ? entitlements[APP_GROUPS_ENTITLEMENT]
    : [];
  return {
    ...(entitlements || {}),
    [FAMILY_CONTROLS_ENTITLEMENT]: true,
    [APP_GROUPS_ENTITLEMENT]: [...new Set([...groups, FOCUSLOCK_APP_GROUP])],
  };
}

function withScreenTime(config, configPlugins) {
  const plugins = configPlugins || require('@expo/config-plugins');
  return plugins.withEntitlementsPlist(config, (modConfig) => {
    modConfig.modResults = addFamilyControlsEntitlement(modConfig.modResults);
    return modConfig;
  });
}

withScreenTime.addFamilyControlsEntitlement = addFamilyControlsEntitlement;
withScreenTime.FAMILY_CONTROLS_ENTITLEMENT = FAMILY_CONTROLS_ENTITLEMENT;
withScreenTime.APP_GROUPS_ENTITLEMENT = APP_GROUPS_ENTITLEMENT;
withScreenTime.FOCUSLOCK_APP_GROUP = FOCUSLOCK_APP_GROUP;

module.exports = withScreenTime;
