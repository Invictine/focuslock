# Today's time

The desktop and Android Focus dashboards distinguish three values:

- Focused work: today's logged work in the synced credit-bank aggregate. Daily values are ignored when their reset date is from another day; unused credits carry over.
- Leisure: measured foreground time for currently blocked or permanent Boundary apps and websites, summed across the account's uploaded device usage. It is independent of earned or consumed credits.
- Available leisure credits: spendable credit balance, including carryover and task rewards.

`usage:getUsageSummary` supplies account target totals and per-device target counters. Clients add only positive local increases above their own uploaded counters. Counters from other devices are preserved. Overlapping domain boundaries select each usage row once. Older backends without per-device counters use the maximum of local and cloud leisure as a conservative lower bound.

Android reads raw app foreground seconds from the existing cached UsageStats aggregate, applies account ownership baselines, and exposes a dated usage snapshot as observable UI state. Local Android website activity is unavailable; website time comes from synced browser/desktop uploads. Missing permission, unavailable measurements, and loading do not become an invented zero. Pull-to-refresh imports TickTick work and explicitly refreshes account usage. Routine background sync retains its existing cadence.

Desktop's today screen-time rows include current account-attributed PC activity. Historical screen-time ranges show uploaded totals. Both dashboards separate loading/sync status from metric values and guard date rollover.

Validation: root Vitest tests, desktop production build, Android unit tests/debug build, and `node scripts/verify-time-dashboard.mjs` (real desktop UI with deterministic service fixtures at 1280, 600, and 360px). The fixtures do not establish a signed-in device round trip. Installing updated clients and deploying the backend are required for full per-device merging.

This change concerns the FocusLock host dashboard and account usage. No shared Void launcher source or standalone Void user flow is changed; standalone Void has no FocusLock credit-bank or account dashboard equivalent.
