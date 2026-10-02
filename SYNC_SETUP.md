# FocusLock account sync

Android, Windows, and Chrome must use the same Clerk instance and Convex deployment.
Cloud records belong to the verified Clerk user ID, not an email address or device name.

## Configuration

Run `npm run sync:check-config` to check build inputs without printing keys:

| Client | Clerk publishable key | Convex URL |
| --- | --- | --- |
| Android | `local.properties: clerk.publishableKey` | `local.properties: convex.url` |
| Windows | `desktop/.env: VITE_CLERK_PUBLISHABLE_KEY` | `desktop/.env: VITE_CONVEX_URL` |
| Chrome | `extension/.env: CLERK_PUBLISHABLE_KEY` | `extension/.env: CONVEX_URL` |

The extension build falls back to `desktop/.env`. The root `.env.local` selects the
backend deployment. Do not commit these files.

Clerk must have a JWT template named `convex` with `{"aud":"convex"}`. Convex's
`CLERK_JWT_ISSUER_DOMAIN` must match the Clerk issuer. A standard Clerk session token
is not a substitute. See [Clerk's integration guide](https://clerk.com/docs/guides/development/integrations/databases/convex).

On 2026-09-19, the development instance had no JWT templates. The missing template
was created and verified. This blocked Android and Chrome sync. Windows uses its
existing public OAuth/PKCE client and a separately accepted audience.

## Validation and deployment

From the root:

```powershell
npm test
npm run typecheck
npm run sync:check-config
npx convex dev --once --typecheck enable
```

The backend tests use isolated identities and an in-memory Convex test database.
They check account isolation, restoration in a new session, retry deduplication,
deleted collections, stale writes, independent preference edits, and cumulative
usage that cannot shrink after a local cache reset. They do not prove live sign-in.

```powershell
npm --prefix extension run check
npm --prefix extension run build
npm --prefix desktop run build
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Reload the unpacked Chrome extension after rebuilding. Install the rebuilt APK or
desktop package on the devices being tested; a build does not update a running installation.

## Real-device acceptance check

1. Sign into the same account on Android, Windows, and Chrome's persistent Account page.
2. Sync each device; confirm the account's device list contains all three.
3. Change a shared website boundary; confirm it reaches and is enforced by the others.
4. Go offline, make an edit or log work, restart, reconnect, and check that it uploads
   once without losing the edit or duplicating history.
5. Sign into another account; confirm the previous account's data is not uploaded.
   Return to the first account and check its retained data.
6. Restore on a fresh installation and check cloud history and boundaries.

## Operations and recovery

### Storage and call budget changes (2026-09-30)

Routine Android, Windows and Chrome background uploads use a four-hour cadence.
Chrome refreshes policy every minute while a shared target is actively browsed;
Android uses a minute cadence while its UI is visible. Cached policy enforcement
continues offline. Active enforcement refreshes and merged-limit usage uploads
are separate from the four-hour ordinary usage/heartbeat budget.
Sign-in, account changes, local edits, reconnects where supported, and explicit
Sync Now can add immediate calls. A boundary changed on another device may take
up to the background interval to reach a device that has no live subscription.

Usage sends only increased absolute counters. A usage batch can carry its device
heartbeat in the same transaction. Android pulls groups, due today's usage, and
due target catalogs with its conditional snapshot rather than three extra calls.
Collection saves diff rows, preserving unchanged records. Windows subscribes to
configuration, credit state and visible history separately. Stable device labels
are separate from heartbeats so presence changes do not invalidate usage reports.
New `recordWork` events use one canonical history row; session views derive the
corresponding entry without creating a duplicate `focusSessions` document.

The target catalog is materialized by installation/target, so normal picker reads
do not rescan usage history. An existing account's first new upload schedules a
bounded, idempotent catalog backfill. Legacy reads remain complete until backfill
finishes. Catalog increments and per-source markers commit together, including
when a backfill races an upload or archival transaction.

Detailed cloud usage is kept for 30 days with a one-day UTC/timezone buffer.
An indexed daily job compacts at most 100 expired rows per transaction into
monthly target/device totals and daily chart totals, then schedules continuation
pages. Archives and source removal commit atomically. All-time and complete-month
totals remain available; historical partial-month per-target queries cannot be
exact after compaction and return an explicit error instead of a misleading sum.
Monthly totals remain indefinitely, so their storage still grows with time.

Uploads dated before that retention window are acknowledged as `expired` without
recreating retired counters, which would otherwise double-count archived totals.
Clients keep their local data and report a nonfatal retention warning; offline
usage that was never uploaded within the window cannot be restored to the cloud
by replaying an old counter. Work records retain their separate durable replay.

See [CONVEX_CAPACITY.md](CONVEX_CAPACITY.md) and run `npm run convex:capacity`.
The estimate uses explicit activity/size assumptions and does not certify 1,000
DAU under Free. Calls, stored indexes, cleanup I/O, reactive reruns, retries and
other projects on the same team must all be included in a live capacity test.

A fresh pre-retention snapshot is saved privately in the ignored local file
`build/convex-before-storage-20260930.zip` (ZIP integrity verified, SHA-256
`68ab41564c106d9ab8ed83f7cf4dc4916956f16dd313996a3f71b809c5ac5d38`).
The primary development deployment (`earnest-quail-160`) was disabled by Free-plan
limits on 2026-09-30. With the user's supplied backup deployment key, the backend
was deployed temporarily to `brazen-fly-869`. The fresh snapshot above restored
361 documents into the previously empty fallback, retaining account ownership.
Live read-only checks of the restored account returned 15 app boundaries, four
website boundaries, 13 work records, and seven registered devices. These used
an administrative identity; they do not prove a real Clerk login or phone round
trip. See [CONVEX_FALLBACK.md](CONVEX_FALLBACK.md) before switching back.

Convex persists account data independently of installations. Cumulative usage is
keyed by account, installation, date, and target. Retrying a bucket cannot double
count it or reduce its stored counters. Collection versions retain intentional
empty collections so old clients cannot resurrect deleted boundaries or groups.

A pre-change export is saved to ignored local file `build/sync-backup-20260919.zip`.
It contains private account data and must not be committed. Make additional backups
with `npx convex export --path <new-file.zip>`. Restoring over the current deployment
is an explicit administrative operation, not a sync test.

This is a development deployment. Deployment output on 2026-09-19 warned that the
team's Free-plan limits were exceeded. Resolve usage or the plan limit in Convex to
avoid interruption; code fixes cannot remove provider quota restrictions.

If sign-in works but sync fails, check the JWT template, issuer, matching client URLs,
visible sync error, network access, and quota. Do not fall back to anonymous uploads
or report a failed upload as successfully synced.
