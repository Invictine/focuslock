# Chrome extension and Android parity

Status: implementation and fixture verification are in progress. A signed-in Chrome-to-Android round trip has not been verified on a physical device.

## What is shared

| Area | Chrome extension | Android app |
| --- | --- | --- |
| Account and credit settings | Uses the shared FocusLock account, reads synced credit preferences and balance, logs titled work and focus sessions, and updates the shared app/website groups. | Uses the same account and settings while also maintaining native usage and task integrations. |
| Earned Focus time | A positive shared balance permits access to selected account-shared websites. Chrome tracks and reports its browser spend. | Applies earned time through native app and website boundary flows. |
| Local website boundaries | Extension-local lists, allow-only rules, exceptions, schedules, frozen locks, and per-list daily limits remain local. Earned time does not bypass a local list. | Native app and website enforcement uses Android's local settings and permissions. |
| Shared target groups | Can create a group from at least two ungrouped synced app or website targets, display members, edit its daily limit, and remove the group. Chrome enforces a group limit when a member website is visited, combining synced group usage with locally observed browser usage. It cannot block an app. | Can create and edit merged app/website groups and enforce their limits on the phone using the usage available to Android. |
| Schedules and target limits | Enforces synced website schedules and per-target website caps, plus Chrome-local schedules and list limits. | Enforces native app boundaries and its supported schedules and target limits. |
| Global daily leisure cap | Currently enforced for selected account-shared websites from synced scroll totals plus locally observed pending spend. | Matching global-cap enforcement is not currently implemented in Android. |
| Eat the Frog | Does not read or change the phone's Frog selection or progress. The blocked page reports that the task stays on the phone. | Frog state is held in local Android storage and powers the native task and blocking flow. It is not synced to Chrome. |
| Native protection | Chrome's user can disable or uninstall the extension. It cannot apply Android permissions or protect its own installation. | Android can use its native permission and service surfaces; those do not transfer to Chrome. |

## Credit and boundary behavior

Account-shared websites are the earned-time surface: Focus credit permits access while the balance is positive, and browser usage consumes that balance. Synced Strict Mode, schedules, target caps, group caps, Nuclear state, and a depleted balance can still block a selected shared site.

Extension-local lists are independent. An active local rule blocks its matching site according to that list's mode, exceptions, schedule, freeze, and daily limit. A positive shared Focus balance is not a bypass for these rules. Permalock is also device-local and has no removal path inside the extension.

Groups are account-shared definitions. Creating or editing a group preserves existing member ownership and sends the collection version with each write. The UI refetches the current version before saving and asks the user to review if another client changed groups. Group usage from other devices arrives in server snapshots; it is not one atomic live counter shared by every device.

## Timing and practical limits

- Only the focused active Chrome tab accrues browser usage; idle time pauses tracking.
- Chrome uses a live authenticated Convex subscription to `focus:getSyncPulse` for enforcement policy and relevant daily usage. Pushed changes apply directly to storage and recheck open websites. Local usage still flushes on the worker's minute maintenance interval and on navigation/focus transitions; this interval no longer polls policy.
- Mobile Strict Mode preference changes queue a debounced sync immediately, including behind an in-flight sync. Once saved in Convex, the live query pushes the commitment to Chrome without waiting for a minute refresh. Both clients must be online and signed into the same account.
- Incoming Strict Mode locks Chrome boundary edits and snoozes, updates an already-open dashboard/popup, and keeps the cached commitment enforced offline until expiry. Shared writes check refreshed policy before sending; local writes also check the latest stored lock under the storage write lock.
- Ordinary usage uploads and device heartbeats remain on a four-hour cadence. Startup, account changes, edits, reconnects, and **Sync now** can request earlier sync.
- Offline Chrome enforcement uses the latest saved policy and usage snapshot. Shared rules and cross-device totals can therefore be stale while offline or before the next snapshot arrives.
- Concurrent app and website use across devices can pass a shared limit before every client sees the latest usage. Treat current merged and global caps as best-effort cutoffs, not an exact simultaneous budget guarantee.
- The subscription renews Clerk Convex tokens, closes the previous account's socket on account changes/sign-out, ignores stale callbacks, and reconnects through the Convex SDK. Worker initialization recreates it from durable state. Chrome 116+ is required for WebSocket traffic to maintain an idle extension worker; cached enforcement remains available during network or worker recovery.

## Verification status

The packaged Chromium smoke test uses a seeded policy with `signedIn: false`; it verifies actual navigation redirects and browser usage counting, not Clerk authentication or personal-account sync. The UI fixture tests cover group creation/edit/removal, target ownership, stale-version rejection, save errors, credit calculation, and responsive layouts. Policy and backend coverage is being expanded, so exact test counts are intentionally left to the root task's final verification.

Live acceptance remains outstanding. Reload the installed extension from the complete root `build/extension-unpacked/` output, rebuild/install the current Android APK, sign into the same account on both devices, then verify a disposable selected website and merged-group change in both directions. No physical Android device was connected for this verification, so build and fixture success do not establish that round trip.

## Temporary deployment restore

The current temporary Convex deployment was seeded from available backup data for integration work. Before restoring the primary/fallback deployment, snapshot or export the temporary deployment and preserve or merge writes created after the seed. Applying an older backup directly over current live data can erase newer records. Keep deployment credentials out of documentation and logs.
