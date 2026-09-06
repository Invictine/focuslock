# FocusLock sync setup — Clerk + Convex

> ✅ Already done via Clerk CLI: **FocusLock** app created (`app_3IuoDaYmD1A1YRKHlCa4KBknMj5`),
> project linked, publishable key wired into `local.properties` + `desktop/.env`.
> Issuer: `https://touching-ringtail-2562.clerk.accounts.dev`. Remaining: 2 dashboard
> clicks + `npx convex dev` below.

## Still needed (2 dashboard clicks)

1. **Convex integration** (provisions the `convex` JWT template — required for sync auth):
   Clerk dashboard → FocusLock → Integrations → **Convex** → Activate.
   (Not API-exposed, so this one is a click.)
2. **Native API for Android**: Clerk dashboard → Native applications → Enable,
   add package `com.focuslock.app`.
3. **Convex**: `npm install`, then `npx convex dev` (browser login on first run) →
   paste the deployment URL into
   - `local.properties: convex.url=https://….convex.cloud`
   - `desktop/.env: VITE_CONVEX_URL=https://….convex.cloud`
   - Convex dashboard → Environment variables →
     `CLERK_JWT_ISSUER_DOMAIN=https://touching-ringtail-2562.clerk.accounts.dev`
5. **Run**: Android (`.\gradlew.bat :app:assembleDebug`, sign in → Account → Sync now),
   desktop (`cd desktop; npm install; npm run dev`).

## Verify auto-sync

1. Sign in with the SAME account on phone + desktop.
2. Phone: change a block toggle or earn credit → desktop updates within seconds.
3. Desktop: toggle a site → phone Account → Sync now (or wait ≤30s).
4. Convex dashboard → Data shows `focusState/blockedApps/blockedWebsites/workRecords`.

## Troubleshooting

- `Not authenticated` in Convex logs → JWT template not named `convex`, or
  `CLERK_JWT_ISSUER_DOMAIN` mismatch, or Convex integration not activated.
- Android stays "offline mode" → `clerk.publishableKey` missing/blank in
  `local.properties` (must start with `pk_`).
- Desktop "Couldn't load" → `.env` missing or Convex deployment sleeping —
  run `npx convex dev` once.
- Tauri OAuth doesn't complete → use browser dev build for SSO (see desktop/README).
