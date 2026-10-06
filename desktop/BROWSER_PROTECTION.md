# Browser extension protection on Windows

The extension owns website boundaries, website limits, schedules, and Frog
website enforcement. The desktop records browser usage and checks that the
extension is available; it does not cover websites with a second blocker.
Explicit application boundaries, including a browser executable selected as an
app, still use the native app blocker.

The companion check runs while website boundaries, website limits, or schedules
require protection. Desktop Eat the Frog is temporarily disabled and does not
activate the companion check. Rules and an active Strict Mode deadline persist
in the desktop activity store. The local check needs no cloud heartbeat or
account sign-in.

## Setup

1. Build/reload the extension from `build/extension-unpacked` and open the
   updated desktop app. It registers the current user's
   `com.focuslock.browser` native messaging host on startup.
2. Install or enable FocusLock in each browser profile you use.
3. Give it access to all websites and enable **Allow in incognito** (or the
   browser's equivalent).
4. Settings → Windows tracking → Browser extension protection shows the most
   recently used browser's connection state.

Chrome, Edge, Brave, Vivaldi, Opera/Opera GX, and Arc use the Chromium bridge.
Firefox has no FocusLock extension yet, so its warning asks you to use a
supported browser. Browser brands other than Chromium still need individual
native acceptance testing.

## Countdown and recovery

- Heartbeats arrive every two seconds. The native host derives the browser PID
  from its process ancestry and matches active-tab titles and window bounds
  to native Windows handles. A healthy profile cannot authorize a different
  unprotected window or process.
- Leases expire after ten seconds. Missing permissions, disabled or removed
  extensions, guest profiles, or crashed native hosts produce a missing lease.
- When an unprotected browser window is observed, a compact notice gives you
  60 seconds to install or enable the extension. The notice leaves the browser
  usable. Extension settings and browser settings remain available.
- The countdown continues when another app takes focus. Switching windows,
  profiles, or processes does not renew an unresolved browser deadline.
  Closing the notice does not cancel the countdown.
- A valid extension connection cancels that window's pending close. A later
  outage starts a fresh countdown after all outstanding windows of that browser
  have recovered. Restoring one profile does not cancel another profile's close.
- At the deadline, FocusLock asks the affected browser window to close using
  Windows' normal close message, after checking its handle, PID, and executable.
  It does not terminate unrelated browser profiles or processes. A browser may
  show its own unsaved-work confirmation; unresolved closes are retried.
- **Open extension settings** opens the affected browser's management page.
  If the browser selects another profile, switch to the affected profile before
  enabling the extension. Give it the required permissions and return to your
  browsing tab so the desktop can verify the connection.
- Tracking cannot be paused while browser protection is required. Adding
  website rules resumes a paused tracker. Closing the main window minimizes it
  while protection is active. Removing the rules outside a commitment restores
  normal closing.

The repair notice is hidden using the same Windows visibility API that shows
it, so recovery leaves no success dialog on screen. Its webview renders nothing
between incidents. Native acceptance checks the actual visible Windows handle
after recovery, as well as the extension-health state.

## Scope

This check requires the desktop app to remain running. Administrators and
programs running as the same Windows user can stop it or alter its local data.
Arbitrarily renamed/custom browsers are not detected. Window matching fails
closed when titles/bounds are missing or ambiguous; unusual displays,
languages, and browser versions need native verification.

Short-lived evidence is stored in
`%APPDATA%\com.focuslock.desktop\browser-leases`. It contains process and window
identifiers, not URLs, webpage contents, account tokens, or credentials. Tab
titles are matched in memory.

These changes concern the FocusLock desktop host. Standalone Void owns neither
website policy nor an extension, so no shared launcher source or standalone
backport is involved.

## Checks

Run desktop `npm run build`, native `cargo test`, and root
`npm test -- --run tests/browser-protection.test.ts`. The extension bridge is
unchanged by the countdown revision.

`scripts/verify-browser-protection.mjs` uses a separate Tauri app identity and
disposable Chromium profiles. Build with a temporary config whose identifier
starts with `com.focuslock.browserqa.`, copy that executable to an isolated
path, then supply its absolute path with `--exe` and the identifier with
`--identifier`. The runner verifies the isolated app-data directory before
sending policy commands. It tests normal browsing without a desktop website
overlay, settings access, the visible countdown, recovery, profile isolation,
and closure with another app in foreground.

The runner saves/restores the 12 exact native-host registry default values and
writes reports/screenshots under `build/browser-protection`. After an
interrupted run, use `--restore <registry-backup.json>`. Rebuild normally after
acceptance to restore the production executable's usual identity. Passing
builds or isolated Chromium acceptance does not establish signed-in device
sync or acceptance on every supported browser brand.

The October 6, 2026 Chromium acceptance passed: configured website targets
and extension settings produced no desktop overlay; disabling the extension
showed a nonmodal countdown; enabling it canceled the close. A second profile
without the extension closed 59.9 seconds after its countdown was observed,
with the desktop in foreground and the healthy profile still open. The repair
notice cleared afterward, and the native-host registry entries were restored.

The recovery-notice regression also passed an independent Windows handle
visibility check: the compact window was physically visible while repair was
needed and no longer visible after recovery or browser closure. The recovered
webview contained no success dialog. Checking only a null health/repair state
is insufficient to verify that a native window has disappeared.
