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
