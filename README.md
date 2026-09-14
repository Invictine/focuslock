# FocusLock

Native Android focus and leisure-time app built with Kotlin, Jetpack Compose, and Material 3.

## Build

Use JDK 17 and an Android SDK with API 36. Run:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Auth + auto-sync (Clerk + Convex)

Sign in with the same Clerk account on phone + desktop and everything syncs:
credit balance, block lists, work history. See **[SYNC_SETUP.md](SYNC_SETUP.md)**
(5–10 min: Clerk keys → `convex` JWT template → `npx convex dev`).

- Android: `clerk.publishableKey` + `convex.url` in gitignored `local.properties`
  (or `CLERK_PUBLISHABLE_KEY` / `CONVEX_URL` env). Empty = offline mode.
  Sign in → **Account** tab → Sync now; auto-sync runs every ~30s while signed in.
- Desktop: [`desktop/`](desktop/README.md) (Tauri + React). `cp .env.example .env`,
  `npm install`, `npm run dev` (web, no Rust) or `npm run tauri:dev` (native).
- Chrome: [`extension/`](extension/README.md). Its popup tracks the active
  website, shows Android/extension connection health, and uploads account-scoped
  absolute usage buckets to the same Convex deployment.
- Backend: [`convex/`](convex/schema.ts) — `focusState`, `blockedApps`,
  `blockedWebsites`, `workRecords`, all scoped by Clerk user. Scripted setup:
  `powershell -ExecutionPolicy Bypass -File scripts/setup-clerk-convex.ps1`
  (needs `$env:CLERK_SECRET_KEY`).

## TickTick connection

Set `ticktick.clientId` and `ticktick.clientSecret` in the ignored `local.properties`, alongside `sdk.dir`. In your TickTick developer app (https://developer.ticktick.com/manage), set the OAuth redirect URL to exactly `http://127.0.0.1:8080/` — TickTick only accepts http(s) URLs and rejects custom schemes such as `focuslock://` with "Please enter right url" / "at least one redirect_url must be registered".

In FocusLock, open Settings and choose **Connect with TickTick**. Approve in the browser; it will land on `http://127.0.0.1:8080/?code=…` which can't load on the phone. Copy that full address, return to FocusLock, and paste it into **Pasted redirect address or code** → **Complete connection**. If the system routes the loopback URL back to the app automatically, the callback is handled without pasting.

The authorization-code exchange uses HTTP Basic authentication. Each login has a persistent, one-use state with a ten-minute lifetime; unmatched and expired callbacks are rejected. A complete advanced credential pair takes precedence over build configuration. Token responses and credentials are not logged by the exchange.

This is a personal build: BuildConfig credentials are embedded in the APK and can be extracted. A public distribution needs a server-side token exchange. Do not publish this configured APK or local.properties.

Official reference: https://developer.ticktick.com/docs/openapi.md

## UI

Android is the reference for Focus, Boundaries, Settings, and Account across the Android app, Windows app, Chrome popup, and Chrome dashboard. All four use a fixed charcoal theme with a muted rose accent, native sans-serif typography, and matching control states. The palette stays dark regardless of OS appearance or Android wallpaper. Wide windows use a compact sidebar; compact screens adapt the navigation.

The palette lives in [`design/theme.json`](design/theme.json). Run `npm run theme:generate` after editing it and `npm run theme:check` to verify the generated CSS and Android colors, including text contrast. Desktop and extension builds run the consistency check automatically. See [`design/README.md`](design/README.md) for the visual system.

## Verification (2026-09-05)

19 unit tests cover credit math, parsing, domains, OAuth request headers/encoding, and state validation. A phone is needed to verify browser consent, callback delivery, persisted login, and rendered layouts at normal and enlarged font sizes. No device or emulator was available during this update. The registered TickTick redirect has not been independently verified.

Persistent project context: https://github.com/Invictine/life-tracker/tree/main/software/focuslock
