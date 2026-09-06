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

The dashboard prioritizes the available time balance, followed by TickTick, optional local focus tools, screen usage, and work history. Daily totals use quiet list rows instead of competing colored cards. Boundaries groups apps and websites under one destination. Shared shapes, native typography, system light/dark mode, and wallpaper-derived dynamic colors keep the screens consistent. Content width is bounded in large windows.

## Verification (2026-09-05)

19 unit tests cover credit math, parsing, domains, OAuth request headers/encoding, and state validation. A phone is needed to verify browser consent, callback delivery, persisted login, and rendered layouts at normal and enlarged font sizes. No device or emulator was available during this update. The registered TickTick redirect has not been independently verified.

Persistent project context: https://github.com/Invictine/life-tracker/tree/main/software/focuslock
