# Chrome extension and Android parity

Status (2026-10-06): Chrome uses a compact popup and persistent account/sync page. Unit, UI-fixture and packaged Chromium policy verification pass. A signed-in Chrome-to-Android round trip remains unverified.

## Extension surface

- The Chrome popup shows today's usage, the active site's time, quick block/allow actions, and account sync state. **Account & sync** opens a persistent tab for sign-in, connected-device status, manual sync, and sign-out. Chrome no longer exposes its full focus, boundary-editing, or settings dashboard; use the FocusLock desktop or Android app for those controls.
- Focus timers and browser Frog progress remain in the background worker for existing sessions and enforcement. Signed-in sessions of at least five minutes earn shared credit. Failed uploads retain the stable session ID and original account, and retry on maintenance, reconnect, or **Sync now**. Anonymous sessions still advance local browser Frog progress without shared credit.
- Strict commitments support hours, days, a local end date up to 30 days, extension, and trusted-person approval through the existing backend. Configure them in FocusLock desktop or Android. Chrome keeps enforcing cached commitments; adding blocked websites and permanent blocks remains available, while removing or weakening existing boundaries stays locked. Strict Mode does not impose new blocks by itself or escalate blocked attempts into Nuclear. A commitment is enforced locally before sync; a failed account update stays pending for the account that started it. Signed-out commitments stay Chrome-local.
- A Chrome-local weekly Strict window activates at browser wake or minute maintenance, supports overnight windows, and syncs the resulting commitment when signed in. An approved occurrence does not immediately re-arm. Place-based activation remains phone-local.
- Synced website schedules, per-target caps, and group limits remain enforced in Chrome. Manage them in FocusLock desktop or Android. The Chrome extension does not expose boundary editors; native apps remain responsible for app enforcement.
- Permanent targets are excluded from ordinary website controls. Permanent blocks, Frog, frozen locks, and shared caps suppress unavailable snooze controls; Strict Mode alone does not.

## What is shared

| Area | Chrome extension | Android app |
| --- | --- | --- |
| Account and credit settings | Uses the shared FocusLock account, syncs Chrome usage, and displays account/device connection status. | Uses the same account and settings while also maintaining native usage and task integrations. |
| Earned Focus time | A positive shared balance permits access to selected account-shared websites. Chrome tracks and reports its browser spend. | Applies earned time through native app and website boundary flows. |
| Local website boundaries | Extension-local lists, allow-only rules, exceptions, schedules, frozen locks, and per-list daily limits remain local. Earned time does not bypass a local list. | Native app and website enforcement uses Android's local settings and permissions. |
| Shared target groups | Enforces synced group limits when a member website is visited, combining synced group usage with locally observed browser usage. Manage group membership and limits in FocusLock desktop or Android. Chrome cannot block an app. | Can create and edit merged app/website groups and enforce their limits on the phone using the usage available to Android. |
| Schedules and target limits | Enforces synced website schedules and per-target website caps, plus Chrome-local schedules and list limits. | Enforces native app boundaries and its supported schedules and target limits. |
| Global daily leisure cap | Currently enforced for selected account-shared websites from synced scroll totals plus locally observed pending spend. | Matching global-cap enforcement is not currently implemented in Android. |
| Eat the Frog | Opt-in browser-local manual task, required focus time, task completion, wake-hour rollover, and website enforcement. Both completion and tracked time are required; credits and snoozes cannot bypass its lock. The blocked page shows actual browser task progress. | Frog state is held in local Android storage, including TickTick/manual selection and native blocking. Phone task selection and progress do not sync to Chrome. |
| Home-only and location | Chrome rules apply wherever the computer is used. | Home location, location permission/status, home-only gating, and Strict place activation remain phone-local. |
| Native protection | Chrome's user can disable or uninstall the extension. It cannot apply Android permissions or protect its own installation. | Android can use its native permission and service surfaces; those do not transfer to Chrome. |

## Credit and boundary behavior

Account-shared websites are the earned-time surface: Focus credit permits access while the balance is positive, and browser usage consumes that balance. Schedules, target caps, group caps, Nuclear state, and a depleted balance can still block a selected shared site during Strict Mode.

Extension-local lists are independent. An active local rule blocks its matching site according to that list's mode, exceptions, schedule, freeze, and daily limit. A positive shared Focus balance is not a bypass for these rules. Permalock is also device-local and has no removal path inside the extension.

Groups are account-shared definitions. The desktop and Android apps preserve existing member ownership and send the collection version with each write. Group usage from other devices arrives in server snapshots; it is not one atomic live counter shared by every device.

## Timing and practical limits

- Only the focused active Chrome tab accrues browser usage; idle time pauses tracking.
- Chrome uses a live authenticated Convex subscription to `focus:getSyncPulse` for enforcement policy and relevant daily usage. Pushed changes apply directly to storage and recheck open websites. Local usage still flushes on the worker's minute maintenance interval and on navigation/focus transitions; this interval no longer polls policy.
- Mobile Strict Mode preference changes queue a debounced sync immediately, including behind an in-flight sync. Once saved in Convex, the live query pushes the commitment to Chrome without waiting for a minute refresh. Both clients must be online and signed into the same account.
- Incoming Strict Mode locks Chrome boundary edits, updates the popup, and keeps the cached commitment enforced offline until expiry. Existing site rules continue to determine access, including whether a snooze is allowed. Shared writes check refreshed policy before sending; local writes also check the latest stored lock under the storage write lock.
- Ordinary usage uploads and device heartbeats remain on a four-hour cadence. Startup, account changes, edits, reconnects, and **Sync now** can request earlier sync.
- Offline Chrome enforcement uses the latest saved policy and usage snapshot. Shared rules and cross-device totals can therefore be stale while offline or before the next snapshot arrives.
- Concurrent app and website use across devices can pass a shared limit before every client sees the latest usage. Treat current merged and global caps as best-effort cutoffs, not an exact simultaneous budget guarantee.
- The subscription renews Clerk Convex tokens, closes the previous account's socket on account changes/sign-out, ignores stale callbacks, and reconnects through the Convex SDK. Worker initialization recreates it from durable state. Chrome 116+ is required for WebSocket traffic to maintain an idle extension worker; cached enforcement remains available during network or worker recovery.

## Verification status

The packaged Chromium smoke test uses a seeded policy with `signedIn: false`; it verifies actual navigation redirects, browser spending, merged caps, funded access and a zero-credit snooze during incoming Strict Mode, offline enforcement, real Frog UI/enforcement, timer recovery after reload, and Strict activation/extension UI. Elapsed timer time is synthetically advanced only in the disposable test profile. It does not verify Clerk authentication or personal-account sync. The UI fixtures cover groups, hub/detail/back routes, permanent filtering, blocked-page recovery, credit calculation, and responsive layouts. Feature tests cover overnight weekly activation, 30-day limits, extension-only commitments, worker restarts, completion deduplication, and originating-account upload guards.

Live acceptance remains outstanding. Reload the installed extension from the complete root `build/extension-unpacked/` output, use the current Android app, and sign into the same account on both devices. Verify a disposable selected website, schedule, individual cap and merged-group change in both directions. Approval verification also needs the configured email service and the trusted person's participation. Build and fixture success do not establish these live results.

## Temporary deployment restore

The current temporary Convex deployment was seeded from available backup data for integration work. Before restoring the primary/fallback deployment, snapshot or export the temporary deployment and preserve or merge writes created after the seed. Applying an older backup directly over current live data can erase newer records. Keep deployment credentials out of documentation and logs.
