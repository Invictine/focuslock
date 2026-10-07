# FocusLock Desktop (Tauri + React + Clerk + Convex)

Windows companion that auto-syncs with the Android app. Same Clerk account = same
credit balance, block lists, work history.

## Windows background protection

Closing the main window keeps FocusLock running in the system tray, with tracking, sync, and enforcement active. Reopen it from the tray or its normal shortcut. **Quit when protection is off** refuses to quit while native boundaries, permanent blocks, or the enabled browser checker are enforcing. Clear the applicable rules in FocusLock before quitting. Strict Mode continues to freeze boundary edits only.

Installed release builds start in the background at Windows sign-in and run a small recovery companion. If the desktop process exits unexpectedly, the companion relaunches it in the background with its persisted native rules. Intentional tray Quit stops recovery for that run; repeated crashes back off. Development builds and binaries under `target/` do not register Windows startup. Recovery is user-level: an administrator, disabling startup, or terminating both processes can still stop protection. It does not install a service or change Windows permissions.

The extension repair notice is 360×220 and moves by dragging its header. Native heartbeats require an exact browser PID and independently matched HWND, including all-sites permission and profile isolation. The bridge keeps a periodic alarm while connected and tolerates one alarm interval without discarding a healthy lease; disconnect removes that lease. Missing-extension windows still get their own repair deadline.

These changes concern FocusLock's host and its browser bridge. Shared `windows/void/` launcher source is unchanged, so standalone Void has no equivalent integration to backport.

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

Cross-device tracking uses two additional tables and four public functions:

- `devices:heartbeat` records the installation name, platform, app version and
  tracking health; `devices:listDevices` supplies the dashboard device list.
- `usage:recordUsageBatch` stores absolute per-device/day/target counters. A
  reconnect retry cannot double-count usage.
- `usage:getUsageSummary` returns combined totals plus explicit per-device and
  per-target breakdowns, so Android and Windows activity remain distinguishable.
- Missing or malformed Clerk/Convex environment values stop at a clear setup
  screen. The app never initializes placeholder credentials.

- `../../convex/schema.ts` is the source of truth:
  `focusState` (1 doc/user), `blockedApps`, `blockedWebsites`, `workRecords`.
- Desktop subscribes with `useQuery(api.focus.getSnapshot)` — updates from the
  phone appear instantly, no refresh.
- Writes (`saveState`, `saveBlockedApps`, `saveBlockedWebsites`, `addWorkRecord`)
  require Clerk auth (`ctx.auth.getUserIdentity()`); JWT template name is `convex`.
- Android pushes every ~30s + after focus events and pulls remote lists/records.
