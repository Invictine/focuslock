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

For a reduced-capability local build on a Mac, set `EXPO_PUBLIC_SCREEN_TIME_ENABLED=0` and run `npx expo run:ios`; this omits Screen Time extensions and entitlements, so device blocking and the report are unavailable. The EAS `basic-development` profile sets the same flag, but EAS signing requires paid Apple developer credentials. The full Family Controls build also needs the approved entitlement and signing team. See [docs/IOS_PORT.md](../docs/IOS_PORT.md) for the capability and device-validation checklist.
