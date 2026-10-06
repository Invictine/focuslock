# Temporary Convex deployment — 2026-09-30

On 2026-10-06, a real application query confirmed the primary still rejects
requests because Free-plan limits disabled the deployment. The backup responds
normally and remains selected by all client build inputs. The account permanent
commitment fix was deployed to both backends after exporting each to private
`build/permanent-{primary,fallback}-before-20261006.zip` snapshots. No account
data was imported, replaced, or cut over.

The user authorized the supplied backup key until the primary quota recovers.
The current build inputs use `https://brazen-fly-869.convex.cloud`; the primary is
`https://earnest-quail-160.convex.cloud`. Clerk identity and issuer remain the same.
The key is stored only in ignored `build/convex-fallback.env`. Never commit or print it.

## October 6 configuration correction

A fresh application query still reports the primary disabled by Free-plan limits.
Backup administrative reads succeed for snapshot, dashboard, policy pulse and devices;
the snapshot contains 17 app boundaries, seven website boundaries and 20 work records.
These reads verify backend availability and retained data, not real Clerk device sync.

Windows had a stale `desktop/.env.local` override selecting the primary even though
`desktop/.env` selected the backup. The override now selects the backup; its original
is preserved privately in `build/fallback-config-20261006/desktop.env.local`.
The configuration checker now includes Vite's local and production overrides and
process environment precedence so this mismatch cannot pass unnoticed.
Rebuilt desktop and Chrome bundles contain the backup endpoint and no primary endpoint.
The Windows NSIS installer completed successfully and updated the installed executable;
it matches the rebuilt binary apart from Tauri's expected NSIS bundle-type marker.
Android's generated debug configuration already selects the backup. No database was
imported or replaced during this correction.

## What has been verified

- Updated backend functions/indexes deployed successfully to the fallback.
- The initial fallback export had zero data rows, including after a failed first import.
- Restoring the saved primary snapshot into that empty deployment imported 361 documents.
  `--replace-all` was needed for schema table-ID mapping; no existing data was deleted.
- Authenticated administrative reads returned the restored account's 15 app boundaries,
  four website boundaries, 13 work records, and seven device registrations.
  Unauthenticated reads were rejected. Report: `build/fallback-read-verification.json`.
- All three client build inputs now agree on fallback URL and original Clerk instance.
- A disposable Chromium test loads the actual packaged MV3 extension and exercises
  redirects, exhausted earned time, and merged app/website limits with seeded policy.
  That browser profile is signed out. It does not establish live Clerk/phone acceptance.

## Using these builds

Reload the unpacked Chrome extension from `build/extension-unpacked`, then open its
persistent Account page and sign in to the same Clerk account used on the phone.
Install `app/build/outputs/apk/debug/app-debug.apk` after its build finishes.
Windows' updated frontend has also been built, but an existing installed executable
does not change backend merely because source configuration changed.

Without the rebuilt/reloaded clients, existing installations continue using the
disabled primary. Keep all devices on the same backend during the temporary period.
USB debugging currently has no usable connected phone, so the APK has not been installed
or checked on the user's physical device.

## Private recovery artifacts

| Artifact | Purpose |
| --- | --- |
| `build/convex-before-storage-20260930.zip` | Primary account snapshot used for fallback seed |
| `build/convex-fallback-before-20260930.zip` | Integrity-checked empty fallback before deployment/import |
| `build/convex-fallback-after-import-failure-20260930.zip` | Confirmed empty state before successful import |
| `build/fallback-config-20260930/` | Original root, desktop and Android build configuration |
| `build/convex-fallback.env` | Ignored deployment key, for authorized fallback CLI operations |

There was no `extension/.env` before the switch. The new file overrides only its
Convex URL; Clerk inputs fall back to desktop configuration.

## Returning to the primary

Quota renewal must be checked against a real primary query. The date alone does not
prove the provider has restored the deployment.

1. Export both current deployments to new private files and verify their ZIP integrity.
   Retain the initial snapshot and original configuration backups.
2. Compare primary and fallback records to the initial snapshot by account and stable
   entity keys. Preserve work recorded since the switch, cumulative usage maxima,
   deletion versions, preferences, and credit changes. A main deployment used by old
   installations can diverge independently from the fallback.
3. Prepare a reviewed reconciliation that preserves both branches. Do not blindly
   import the old snapshot or use `--replace-all` on a populated deployment. The earlier
   empty-fallback import does not authorize clobbering future primary data.
4. Prevent concurrent writes during any migration cutover; if that cannot be verified,
   leave the functioning fallback in place and report the required coordination.
5. Deploy the current backend to the primary, migrate verified changes, restore the
   original client URL inputs (remove/reset the new extension override), and rebuild.
   Run `npm run sync:check-config` and authenticated primary reads before handoff.
6. Reload Chrome and install/relaunch the matching device builds together. Verify real
   same-account boundary propagation, earned/spent credit, group usage and offline replay.

The user requested a temporary deployment. A follow-up should check primary recovery
and prepare the return without discarding new fallback activity. Full cross-device
acceptance still requires manual extension sign-in and the physical Android phone.

The existing primary-recovery follow-up was re-enabled on October 6 to check daily
at 09:00 local time (India), remaining quiet while the primary stays quota-disabled.
Keep the computer on with Codex running for access to these local files.
It follows the preservation requirements above and pauses after a completed return;
it does not promise an unconditional database replacement or automatic phone installation.
