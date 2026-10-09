# FocusLock Desktop (Tauri + React + Clerk + Convex)

Windows companion that auto-syncs with the Android app. Same Clerk account = same
credit balance, block lists, work history.

## Windows background protection

Closing the main window keeps FocusLock running in the system tray, with tracking, sync, and enforcement active. Reopen it from the tray or its normal shortcut. **Quit when protection is off** refuses to quit while native boundaries, permanent blocks, or the enabled browser checker are enforcing. Clear the applicable rules in FocusLock before quitting. Strict Mode continues to freeze boundary edits only.

Desktop sign-out is unavailable while Windows/website boundaries, configured daily limits or schedules, permanent blocks, Strict Mode, or Nuke remain active. Allowance remaining or a schedule outside its interval does not permit signing out. The Account page waits for a complete policy and native persistence before enabling Sign Out. The native command checks the persisted policy and existing native guards before deleting the session. A different account cannot replace a protected account; signing back into the same account remains available after session expiry. The opted-in browser checker retains its website policy across authentication loss and restart; unavailable account data cannot clear it. These account and enforcement changes belong to the FocusLock desktop host; standalone Void has no account sign-out or FocusLock restriction policy to backport.

Installed release builds start in the background at Windows sign-in. A per-user **FocusLock Recovery** scheduled task runs the recovery supervisor outside the desktop's process tree. Ending the desktop or its process tree restarts it in the background after about two seconds plus startup time, with its persisted native rules. If both desktop and supervisor are stopped, the task's one-minute check starts recovery again. Repeated crashes back off. An allowed tray Quit disarms recovery until the next normal launch. The task uses the signed-in user's session and limited privileges, works on battery, and prevents duplicate supervisors. Task-registration or startup failures appear in Settings, with the original child watchdog retained as crash-only fallback. Windows recovery belongs to FocusLock's tracking and blocking host, so this change has no standalone Void equivalent.

Development builds and binaries under `target/` do not register startup or scheduled recovery. Native recovery acceptance uses an isolated `com.focuslock.browserqa.*` identifier with `--recovery-test-task` and `scripts/verify-task-manager-recovery.ps1`; it checks process-tree termination, termination of both processes, rule persistence, and the disarm protocol. This is automatic recovery, not an unkillable process: stopping/disabling the scheduled task, deleting its files, uninstalling FocusLock, or using administrator control can still stop protection. No privileged service or process-permission restrictions are installed.

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

## Windows Frog launcher (Void)

Pick today's Frog and its approved apps/websites on the Focus page, then choose **Open Frog launcher**. FocusLock opens a quiet native black screen with the task, approved tool shortcuts, a configurable focus block, progress, and a task-done action. You can return to FocusLock to change tools or settings. The launcher is Windows-only; the web preview keeps the dashboard available.

Frog completion still requires both ticking off the task and meeting its focus-time requirement. Timer completion does not mark the task done. Only one desktop focus timer can run at a time. Closing the launcher pauses its timer and leaves FocusLock's blocking rules active. Progress comes from the explicit focus timer, not verified foreground activity in a tool. Frog task/allowlist/progress remains local to this PC; existing account sync does not make it a shared phone/PC Frog session.

The native launcher uses shared standalone Void source in `../windows/void/`, packaged as `FocusLock.Void.exe` with a versioned local pipe. FocusLock owns policy, Frog state, and credit; the helper owns the quiet Windows surface and tool launching. Existing permanent blocks and website boundaries keep their precedence. Integrated mode does not run a second foreground guard or change monitor/taskbar configuration. The return shortcut is **Ctrl+Alt+Space**; the emergency exit is **Ctrl+Alt+Shift+F12**. Shortcut conflicts are shown in the launcher. Loss of the host connection closes the helper safely.

`npm run tauri:dev` and `npm run tauri:build` first publish the helper with the .NET SDK. `npm run void:build` prepares it separately. Plain `npm run build` remains a web-only build. Native builds require the .NET 8 SDK as well as the Rust toolchain. Generated helper files are ignored by Git and bundled into the Windows installer.

If the SDK is installed in a custom directory, set `FOCUSLOCK_DOTNET` to its `dotnet.exe` path before the native build. A runtime-only installation cannot compile the helper.

Shared launcher changes must be backported to `Invictine/Void` in the same task. Follow the root `AGENTS.md` and run `node scripts/sync-void.mjs --void-root <standalone-checkout>` from the FocusLock root to verify source parity.

## Windows installation and shortcuts

The per-user NSIS installer registers **FocusLock** in Windows Settings > Apps > Installed apps and installs to `%LOCALAPPDATA%\FocusLock` by default. Every install, including silent/passive installs and `/UPDATE`, creates or repairs the Start menu and desktop shortcuts. Both open the normal app window, with no `--background` argument. Explicit `/NS` installs opt out of shortcuts.

After the Strict Mode removal check succeeds, the uninstaller stops this installation's recovery task/processes and removes the legacy Startup-folder shortcut. Normal uninstall also removes its sign-in startup entry and browser native-host registrations; upgrades preserve those integrations. App data follows the uninstaller's existing **Delete application data** choice. These changes concern FocusLock's desktop packaging and recovery only; the shared Void launcher and standalone Void installer are unchanged.

Native installer checks: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-installer-shortcuts.ps1`, `scripts/verify-installer-cleanup.ps1`, and `scripts/verify-strict-uninstall.ps1` from the repository root. The fixtures use disposable install/profile paths and do not uninstall the user's app.

## Windows Strict Mode uninstall protection

The NSIS Windows installer refuses removal while a Strict Mode commitment saved on this PC is active. Windows Settings, the registered uninstaller, silent uninstall, and `/UPDATE` all use the same native check before any files are removed. Installer upgrades that need removal also wait until the commitment ends; `/UPDATE` is deliberately not an exemption. Windows builds publish NSIS installers only, because MSI removal does not run this hook. Existing installations need the new installer before their uninstall path is protected.

FocusLock mirrors account Strict Mode into `strict-uninstall-v1.json` in its Tauri app-data directory. The check runs without a window, sign-in, network connection, tracker, or recovery process. Signing out, switching accounts, quitting, and restarting do not erase timed commitments. Expiry releases the lock automatically; a synced guardian approval releases only its matching account, current session, and end time. Failed persistence is shown in the dashboard and retried. Corrupt or unreadable guard state refuses uninstall until it can be read again. Cross-device starts/approvals apply once this desktop receives the account update; offline desktops cannot discover a new phone commitment.

This is normal-uninstaller protection in a per-user Windows app. An administrator or a user who manually deletes/modifies the installation or its data can bypass it; this is not a privileged anti-tamper service. The policy belongs to the FocusLock desktop host, so standalone Void has no matching Strict/account/uninstall integration and no shared launcher source changes are required.

Run `powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/verify-strict-uninstall.ps1` after building the native release to test the actual NSIS hook and native probe in a disposable app-data/installation sandbox. It never uninstalls the user's FocusLock installation.

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
