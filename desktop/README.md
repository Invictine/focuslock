# FocusLock Desktop (Tauri + React + Clerk + Convex)

Windows companion that auto-syncs with the Android app. Same Clerk account = same
credit balance, block lists, work history.

## Prereqs

- Node 20+, npm 10+
- Rust toolchain for Tauri packaging (`winget install Rustlang.Rustup`, then `rustup default stable`)
  - Web-only dev (`npm run dev`) works WITHOUT Rust.
- A Clerk app + Convex deployment (see `/SYNC_SETUP.md`)

## Quick start (web preview, no Rust needed)

```powershell
cd desktop
cp .env.example .env   # fill VITE_CLERK_PUBLISHABLE_KEY + VITE_CONVEX_URL
npm install
npm run dev            # http://localhost:1420 — sign in, toggles sync live
```

## Tauri (native window)

```powershell
cd desktop
npm install
npm run tauri:dev      # needs Rust
npm run tauri:build    # installer in src-tauri/target/release/bundle
```

## Auth notes (Clerk in Tauri — read this)

- Email/password + verification codes work inside the Tauri window.
- **OAuth/social + magic links do NOT reliably complete inside the Tauri WebView.**
  This is a known limitation of Clerk JS in Tauri (see tauri-plugin-clerk notes).
  Workaround: use `npm run dev` in your desktop browser for Google/GitHub SSO,
  or sign in once on the web and your session persists per origin.
- The app uses `@clerk/clerk-react` + `convex/react-clerk` (`ConvexProviderWithClerk`
  + `useAuth()`), per https://docs.convex.dev/auth/clerk.

## Sync model (Convex, realtime)

- `../../convex/schema.ts` is the source of truth:
  `focusState` (1 doc/user), `blockedApps`, `blockedWebsites`, `workRecords`.
- Desktop subscribes with `useQuery(api.focus.getSnapshot)` — updates from the
  phone appear instantly, no refresh.
- Writes (`saveState`, `saveBlockedApps`, `saveBlockedWebsites`, `addWorkRecord`)
  require Clerk auth (`ctx.auth.getUserIdentity()`); JWT template name is `convex`.
- Android pushes every ~30s + after focus events and pulls remote lists/records.
