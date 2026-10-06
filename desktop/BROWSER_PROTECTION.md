# Browser extension protection on Windows

FocusLock checks a live local connection from the extension while website
boundaries, website limits, or Frog are configured. No cloud heartbeat or
account sign-in is required for the native check. Rules and an active Strict
Mode deadline persist in the desktop activity store.

## Setup

1. Build/reload the extension from `build/extension-unpacked` and open the
   updated desktop app. The app registers its executable as the current user's
   `com.focuslock.browser` native messaging host on startup.
2. Install/enable FocusLock in every browser profile used for browsing.
3. Give it access to all websites and enable **Allow in incognito** (or the
   browser's equivalent). The extension's existing site blocking remains in
   charge; the desktop checks its availability.
4. Settings → Windows tracking → Browser extension protection shows the most
   recently used browser's connection state.

Chrome, Edge, Brave, Vivaldi, Opera/Opera GX, and Arc use the Chromium bridge.
Firefox is blocked while protection is required because the current FocusLock
extension has no Firefox implementation. Native host discovery and actual
behavior must be verified individually on each browser/version.

## Enforcement and recovery

- Extension heartbeats arrive every two seconds. The native host derives the
  browser PID from its process ancestry and matches reported active-tab titles
  and window bounds to visible native Windows handles. A healthy window cannot
  authorize another browser process or a window in an unprotected profile.
- Leases expire after ten seconds. Missing permission, disabled/removed
  extensions, guest profiles, or disconnected/crashed hosts cannot produce a
  healthy lease.
- The first missing connection gets one 60-second setup grace per browser
  executable for the current protection period. Changing windows/profiles or
  restarting the browser does not renew it. Healthy connections resume browsing
  immediately; further missing connections use the original grace deadline.
- After grace, the existing native blocker covers the foreground browser;
  minimizing is the fallback if its window cannot be shown. This does not kill
  the browser or discard unsaved tabs.
- Extension-management pages are blocked while the extension is healthy.
  They remain available for recovery when it is missing. Browser-reset pages
  stay blocked. A focused/edited address bar never grants the recovery exception.
- **Open extension settings** opens the browser's management page. If the
  browser selects a different profile, open the affected profile's extension
  settings manually. Re-enable FocusLock, restore permissions, and return to
  the browsing tab. A settings page opened for recovery gets 15 seconds to
  return to browsing after its connection becomes healthy. Existing app,
  domain, Frog, and permanent blocks keep their
  precedence over recovery; protection never clears those rules.
- Tracking cannot be paused in the app while browser protection is required.
  Adding website rules resumes a tracker that was paused before activation.
  Closing the main window minimizes it to the taskbar instead of stopping
  enforcement. Removing the website rules outside a commitment allows normal
  closing again.

## Scope

This is a desktop companion guard, not a force-installed enterprise policy.
It requires FocusLock to remain running. An administrator or another program
running as the same Windows user can stop the desktop app, change its data,
or forge local evidence. Arbitrarily renamed/custom browsers are not detected.
Window matching fails closed if titles/bounds are unavailable or ambiguous;
unusual displays, languages, and browser versions need native verification.

The host stores short-lived window-health evidence in
`%APPDATA%\com.focuslock.desktop\browser-leases`. It does not store web URLs,
webpage contents, account tokens, or credentials. The matching titles are
processed in memory, while leases contain only process and window identifiers.

The change is confined to the FocusLock desktop host and extension. Standalone
Void does not own website policies or a browser extension, so there is no shared
launcher change or standalone backport.

## Checks

Run desktop `npm run build`, native `cargo test`, root
`npm test -- --run tests/browser-protection.test.ts`, and extension
`npm test`, `npm run test:desktop-bridge`, `npm run check`, and `npm run build`.
Use a disposable browser profile for native enable → disable → block →
re-enable acceptance; builds and cloud-sync fixtures are not proof of this flow.

`scripts/verify-browser-protection.mjs` runs that Windows acceptance with a
separate Tauri app identity and two disposable Chromium profiles. First build
with a temporary Tauri config whose identifier starts with
`com.focuslock.browserqa.`, copy the resulting executable to an isolated QA
path, then pass its absolute path as `--exe` and its identifier as `--identifier`.
The runner verifies the registered app-data directory before changing policies.
It saves and restores the 12 exact native-host registry default values and
writes a report and blocker screenshots under `build/browser-protection`.
After an interrupted run, use `--restore <registry-backup.json>`.
Rebuild normally afterward so the production executable retains its usual
app identity.

The October 6, 2026 native Chromium acceptance verified a real heartbeat,
extension-settings blocking, normal browsing, disabling the extension, the
60-second grace, decreasing grace across profiles/focus changes, the missing
extension blocker, and restoration after re-enabling. Other browser brands
still require individual native acceptance.
