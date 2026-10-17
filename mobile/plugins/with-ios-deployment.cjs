// Registered first because Expo runs mod callbacks in reverse order. Clerk's
// plugin otherwise overwrites the app's 17.4 target with its own 17.0 minimum.
module.exports = function withIosDeployment(config) {
  const { withPodfileProperties, withXcodeProject } = require('@expo/config-plugins');
  config = withPodfileProperties(config, (mod) => {
    mod.modResults['ios.deploymentTarget'] = '17.4';
    return mod;
  });
  return withXcodeProject(config, (mod) => {
    for (const entry of Object.values(mod.modResults.pbxXCBuildConfigurationSection())) {
      if (typeof entry !== 'object' || !entry.buildSettings) continue;
      const current = parseFloat(String(entry.buildSettings.IPHONEOS_DEPLOYMENT_TARGET ?? '0').replace(/"/g, ''));
      if (!Number.isFinite(current) || current < 17.4) entry.buildSettings.IPHONEOS_DEPLOYMENT_TARGET = '17.4';
    }
    return mod;
  });
};
