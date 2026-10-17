import type { ExpoConfig } from 'expo/config';

const screenTimeEnabled = process.env.EXPO_PUBLIC_SCREEN_TIME_ENABLED !== '0';

const config: ExpoConfig = {
  name: 'FocusLock',
  slug: 'focuslock-ios',
  version: '0.7.0',
  scheme: 'focuslock',
  platforms: ['ios'],
  orientation: 'portrait',
  userInterfaceStyle: 'dark',
  backgroundColor: '#0d0d0f',
  ios: {
    bundleIdentifier: 'com.focuslock.app.ios',
    buildNumber: '1',
    deploymentTarget: '17.4',
    supportsTablet: false,
    ...(screenTimeEnabled ? { entitlements: { 'com.apple.security.application-groups': ['group.com.focuslock.app.ios'] } } : {}),
    infoPlist: { ITSAppUsesNonExemptEncryption: false, FocusLockScreenTimeEnabled: screenTimeEnabled },
  },
  plugins: [
    // Expo mod callbacks run in reverse registration order. Register this first
    // so restricted entitlements added by SDK plugins are removed last.
    ...(!screenTimeEnabled ? ['./plugins/without-screen-time.cjs'] : []),
    'expo-secure-store',
    '@clerk/expo',
    '@clerk/expo-native-components',
    ...(screenTimeEnabled ? ['./plugins/with-screen-time.cjs', '@bacons/apple-targets'] : []),
    ['expo-notifications', { enableRemoteNotifications: false }],
    'expo-sharing',
    ['expo-location', {
      locationWhenInUsePermission: 'Use your location only when you choose to save your home place.',
      locationAlwaysAndWhenInUsePermission: 'Detect entering or leaving your saved home only after you enable home-only blocking.',
      isIosBackgroundLocationEnabled: true,
    }],
  ],
};

export default config;
