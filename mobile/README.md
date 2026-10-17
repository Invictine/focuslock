# FocusLock iOS port

This directory contains the Expo / React Native iOS client, the local Screen Time Expo module, and the Activity Monitor and Activity Report extensions. It is an in-progress port; source coverage does not mean Android parity or verified iPhone enforcement.

Start with the [iOS port handoff](../docs/IOS_PORT.md) for capabilities, setup, validation limits, and the native-device checklist.

## Quick start

Use Node 22.x. From this directory:

```sh
npm ci
```

Copy `.env.example` to `.env` and set the Clerk publishable key and Convex HTTPS deployment URL. Keep `.env` private; the example values are placeholders.

```sh
npm run typecheck
npm test
npm run export:ios
npm start
```

`npm start` serves the development-client bundle. It does not replace an iOS development build: Clerk native authentication, Family Controls, Screen Time shields, and the Apple report view require the custom native modules and extensions. Expo Go is not a supported acceptance target.

The iOS credit gate is off until the user selects apps and enables it. Apple Device Activity records usage in minute thresholds (up to 60 minutes per account-funded allowance). On a successful foreground account sync, FocusLock submits aggregate leisure counters to `usage:recordUsageBatch` and waits for acknowledgement before refreshing its bounded allowance from the fresh bank balance. A disconnected app cannot grant a new allowance. The installation ID is persisted in the iOS Keychain. Selected app identities and Apple's usage report stay private on the device; only aggregate leisure counters enter account credit and usage analytics.

For a reduced-capability local build on a Mac, set `EXPO_PUBLIC_SCREEN_TIME_ENABLED=0` and run `npx expo run:ios`; this omits Screen Time extensions and entitlements, so device blocking, credit gating, and the report are unavailable. The EAS `basic-development` profile sets the same flag. The user has an iPhone but no paid Apple Developer Program membership, so no signed iPhone build or Family Controls acceptance has been completed. The full development build needs program membership and capability signing; distribution additionally needs Apple's entitlement approval. See [docs/IOS_PORT.md](../docs/IOS_PORT.md) for the capability and device-validation checklist.
