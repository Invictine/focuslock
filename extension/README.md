# FocusLock for Chrome

Manifest V3 extension for website boundaries, local usage tracking, and account sync with Android and Windows.

## Build and load

1. Set public `CLERK_PUBLISHABLE_KEY` and `CONVEX_URL` in `extension/.env`, or use the corresponding `VITE_` settings from `desktop/.env`. Never put a secret key in browser configuration.
2. Run `npm ci` and `npm run build` in `extension/`.
3. In `chrome://extensions`, enable Developer mode and load the complete `build/extension-unpacked/` folder from the repository root. If FocusLock is already loaded from that exact folder, use **Reload** after each build. If replacing a different development install, export any device-local lists and usage first; Chrome may remove extension-local data when an extension is uninstalled.
4. Open Account and sign in with the same account used on Android. Hosted sign-in uses HTTPS; the background tracker must confirm the session before the UI reports success.

`extension/dist/` contains compiled scripts only. It is **not** a loadable extension. The build produces a complete loadable folder containing only runtime assets, excluding environment files, private keys, tests, and dependencies.

The stable development ID is `fkkpmoiageeieaoplphafmhjkkdadcnf`. Clerk must allow this extension origin and support the configured Sync Host. Optional `CLERK_SIGN_IN_URL` and `CLERK_SYNC_HOST` overrides must use HTTPS. See the root `SYNC_SETUP.md` for account setup.

## Behavior

- Account-shared selected websites use earned Focus time: a positive balance permits access, and Chrome records the spend. Local Chrome lists follow their own enable state, schedules, exceptions, and daily limits; earned time never bypasses them.
- Shared target groups combine app and website members under one daily limit. The extension can create groups from ungrouped synced targets, edit or remove groups, display app and website members, and enforce the limit when a member website is opened. Usage from other devices arrives in sync snapshots, so near-simultaneous cross-device use can exceed a cap before both clients observe it.
- Chrome enforces synced website schedules and per-target website limits as well as its local per-list schedules and limits. Native app blocking remains the job of Android and Windows.
- The global daily leisure cap is currently enforced by Chrome for selected account-shared websites using synced scroll totals plus locally observed spend. Android does not currently enforce that global preference.
- A positive daily allowance permits a matching site until its local tracked budget is exhausted. A zero allowance blocks matching sites whenever the list is active.
- Overnight schedules belong to the day on which they start.
- Strict Mode and frozen lists reject snoozes. Snoozes are capped at five minutes by the worker, regardless of the requested duration.
- Active Strict Mode commitments survive sign-out/account changes. A different account cannot shorten them or remove their held blocked domains.
- Permalock is a device-local, append-only permanent block list. Its verdict wins over snoozes, exceptions, schedules, daily allowances, Strict Mode, and both Nuclear modes; no dashboard control, import, or reset can remove an entry, and the blocked page offers no snooze or dashboard escape. It is never uploaded or restored by account sync.
- Shared Nuke is separate from local timed Nuclear mode. Shared Nuke remains active until the originating account completes its reset; the configured HTTPS sign-in origins remain reachable for account recovery.
- Browser history and fragment navigation trigger enforcement. Maintenance rechecks open active tabs as schedules, limits, and temporary passes change.
- Only the focused active tab contributes usage; idle time pauses accumulation. A session-storage cursor survives background worker restarts. Usage is kept by local date for up to 60 days and saved on the one-minute maintenance interval and navigation/focus transitions.
- Chrome subscribes to the authenticated Convex enforcement query over a WebSocket. Strict Mode, shared websites, schedules, groups, credit balance, and relevant daily usage changes apply directly when Convex pushes them; no minute policy polling is required. Chrome 116+ supports WebSocket activity in extension workers. The subscription refreshes tokens, reconnects automatically, and is recreated when a worker wakes. Ordinary usage upload and device heartbeat remain on a four-hour cadence; **Sync now** forces an immediate upload. Offline clients enforce their latest saved snapshot. Cross-device caps remain best-effort because other devices must upload their usage before it can be shared.

## Safeguards

Content scripts may ask only for a verdict on their own document. Account operations and state-changing messages require a page from this extension. Local storage access is restricted to trusted extension contexts. Imported malformed collections are normalized before enforcement.

Convex calls have a seven-second deadline. Pending writes remain durable when requests fail or the server rejects a stale update. Returned account data is checked against the current Clerk identity before being displayed. Local controls remain available when popup authentication is unavailable.

Chrome still lets a user disable or uninstall an extension. This package does not provide native application blocking, Android's local Frog task state and automations, or OS-level uninstall protection. Group limits cover shared app and website usage, but cross-device totals depend on synced snapshots and cannot guarantee exact concurrent cutoff. See [EXTENSION_MOBILE_PARITY.md](EXTENSION_MOBILE_PARITY.md) for the current feature boundary and acceptance status, and `HARDENING.md` for security and test notes.

## Verification

From `extension/`:

- `npm test` — account sync, auth, enforcement, matching, and UI guard regressions.
- `npm run check` — syntax-check every runtime JavaScript entry.
- `npm run test:ui` — isolated UI fixtures, navigation, persistence, narrow layouts, and popup behavior.
- `npm run test:browser` — build and load the real extension into disposable Chromium; test navigation blocking, offline cached enforcement, and dashboard layouts.

Browser checks require Playwright's Chromium (`npx playwright-core install chromium`). They do not sign into a personal account or modify the user's installed extension.

For live acceptance, reload the final build, sign into the same account on Chrome and Android, change a disposable website boundary, and verify propagation and blocking in both directions. Then test an offline edit/reconnect and account switching. Passing fixture tests is not evidence that this signed-in round trip succeeded.
