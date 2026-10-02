# Chrome extension hardening review

Date: 2026-09-30

## Scope and status

Reviewed the MV3 manifest and build, URL matching, local state, background enforcement and tracking, account sync and sign-in, popup, dashboard, and block page. Compared commitment handling and website enforcement with the Android service and existing hardening report.

This is a code and isolated-runtime hardening pass. It is not a claim of completed personal-account acceptance. Existing unrelated Android, desktop, and backend changes were retained.

## Fixes

- Corrected daily allowance decisions, overnight schedule day ownership, and frozen/Strict Mode snooze enforcement.
- Limited mutating messages to this extension's pages and content-script verdict requests to the sender's own URL. Restricted local storage to trusted extension contexts.
- Added malformed-state normalization and deterministic repeated regex matching.
- Serialized storage writes across extension contexts with Web Locks. Merge unrelated changes, reject conflicting edits, and retain a separate baseline for each loaded state so delayed worker operations cannot restore older rules after a refresh. Comparisons tolerate Chrome's object-key reordering.
- Corrected background-tab tracking and focus-loss accounting, and retained a tracking cursor across worker restarts.
- Split usage at local midnight and persist an accounting checkpoint with each usage write to prevent replaying an already-saved slice after a worker restart.
- Added history/fragment enforcement and maintenance checks for already-open sites.
- Preserved active commitments across account transitions and added shared Nuke reads using the existing authenticated backend query.
- Added Convex deadlines, account/session checks after requests, HTTPS-only auth navigation, and durable retention of rejected stale writes.
- Guarded dashboard import/edit/delete paths against active locks. Improved popup offline behavior, error feedback, keyboard focus, small-window layout, and the signed-out dashboard. A failed snooze no longer reports success.
- Added Permalock: a device-local, append-only permanent block list enforced as the highest-priority verdict (above Strict, shared Nuke, Nuclear, and lists). Snoozes are rejected at the worker for permanent domains, imports/resets/storage saves cannot drop an entry, cloud sync never uploads or restores it, and the blocked page hides every escape route.
- Added a complete unpacked runtime build under `build/extension-unpacked`; `extension/dist` is only the script bundle directory. Narrowed website permissions to HTTP(S), disabled object embeds in extension CSP, and excluded configuration/secrets from the runtime package.
- Updated compatible dependencies. The high-severity `image-size` advisory was removed by the update. Remaining npm advisories are recorded below.

## Verification

Commands are reproducible from the repository root:

```powershell
npm test
npm run typecheck
npm run sync:check-config
npm --prefix extension test
npm --prefix extension run check
npm --prefix extension run test:ui
npm --prefix extension run test:browser
```

The browser smoke test loads the actual packaged manifest, service worker, and pages in a disposable Chromium profile. Its output is `build/extension-verification/browser-results.json`, with screenshots in the same directory. The separate UI fixture test writes `build/ui-verification/extension-results.json` and screenshots. Fixture auth and real signed-in sessions are explicitly different evidence.

The isolated browser check covers actual navigation redirects and usage counting against a seeded policy with `signedIn: false`, saved boundary persistence, Strict Mode snooze rejection, offline blocking, popup fallback, and all five dashboard destinations at 1280, 840, and 390 pixels. Policy fixtures cover earned-time spend, group and per-target usage limits, schedules, and daily caps. Worker restart accounting and account transitions are regression fixtures; they are not a physical Android-to-Chrome acceptance test. The root task is still adding policy and backend tests, so final test counts are intentionally not recorded here.

Platform references: [Chrome service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle), [Chrome storage access levels](https://developer.chrome.com/docs/extensions/reference/api/storage), and [Web Locks](https://www.w3.org/TR/web-locks/).

## Remaining limits and acceptance

1. **Personal-account round trip is not verified.** Browser automation rejected access to the installed extension URL by policy. No workaround was attempted. Reload the final extension in Chrome, sign into the same account on Android, and verify a disposable boundary in both directions, an offline edit/reconnect, and account switching. Configuration checks only establish that the clients name the same deployment.
2. **Native-only protection is not provided.** Chrome can disable/uninstall an extension. The extension supports earned-time access for selected account-shared websites, shared app/website groups and their daily limits, synced website schedules and per-target limits, plus a global daily leisure cap for selected shared websites. Android does not currently enforce the matching global-cap preference. Chrome-only local lists continue to apply their own blocking rules and do not use earned time as a bypass. Native app enforcement, Android's local Frog task state and automations, and OS uninstall protection remain platform-specific.
3. **Remote early Strict approval needs a richer rule contract.** The current lightweight server response lacks the approval session markers needed to safely authorize shortening an existing device commitment. Chrome retains that commitment until its expiry. Do not treat an unrelated account's false Strict flag as approval.
4. **Shared state and budget snapshots can be stale.** While a shared target is open in the focused browser, policy, usage, and spend snapshots refresh on a one-minute cadence. Ordinary usage uploads and device heartbeats remain on a four-hour cadence; startup/account changes, reconnects, edits, and **Sync now** can refresh sooner. An offline browser uses its last saved snapshot. Concurrent use on multiple devices is best-effort and cannot guarantee an exact simultaneous cutoff. Compare-and-swap handling for external counters is still being integrated by the root task; do not claim its final guarantees until that work and its tests are complete.
5. **Dependency audit is not clean.** Compatible updates removed the high advisory, but npm still reports moderate advisories propagated through Clerk's optional Solana/Jayson dependency graph (`uuid` and `stream-json`). No forced major downgrade of Clerk was applied. Build inputs are recorded in `build/extension-bundle-inputs.json`; `stream-json` is absent from those browser inputs, while UUID browser code is present. This does not justify claiming all transitive advisories are resolved.
6. **Stored patterns and local data remain user-editable.** Password/frozen UI safeguards cannot prevent a device owner from editing an unpacked extension or deleting browser data. Advanced raw regex rules also need reasonable user input; this pass does not introduce a sandboxed regex engine.

No production backend deployment, extension-store publication, or personal-account data mutation was performed by the automated tests.

The cross-device path is not yet live-accepted: the installed Chrome extension must be reloaded from the completed root `build/extension-unpacked/` folder, and the updated Android APK must be installed and exercised on a physical device. No connected physical Android device was available for this pass. Browser fixtures and the seeded-policy smoke test do not prove a signed-in Chrome/Android round trip.

The current temporary Convex deployment was seeded from available backup data for integration work. Before restoring the primary/fallback deployment, snapshot or export the temporary deployment and preserve or merge writes created after the seed. Restoring an older backup directly over live data can erase those newer records. Do not copy deployment credentials into this document.
