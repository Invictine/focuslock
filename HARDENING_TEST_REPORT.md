# Hardening test report — 2026-09-22

Roadmap: [FocusLock roadmap](https://github.com/Invictine/life-tracker/blob/main/software/focuslock/roadmap.md), last file commit `97e1f0088bcd9ee3615827730933f6999de0b69d`. The supplied master URL was stale; retrieved through GitHub API from main. Snapshot: `build/qa/roadmap-source.md`.

**Result: checks below passed after fixes, but the roadmap reliability exit is NOT met.** Force-stop bypass and account-switch commitment handling remain open. This is a bounded local hardening pass, not certification of 1.0 readiness.

## Environment and isolation

- Disposable Android 15/API 35 Google APIs x86_64 AVD `focuslock_hardening_20260922`, serial `emulator-5580`; Windows host, debug APK, offline test account state.
- Four instrumented tests run on the final APK, plus a separately invoked opt-in fixture establishing a three-day commitment and permanently blocking Chrome.
- Existing user AVDs were untouched. Test data was cleared only on this disposable AVD before the final instrumentation rerun. No production account, backend deployment, or real email was used.
- Screens were inspected using UIAutomator hierarchy dumps. Blocker screenshots are black because of its existing `FLAG_SECURE`; these are not visual-design acceptance evidence.

## Executed checks

| Roadmap area | Check | Result / evidence |
| --- | --- | --- |
| 0.7 sync/idempotency | Root Vitest: stale/offline collection snapshots, independent edits, usage retries, lost-response work deduplication, atomic work credit, durable desktop queues/account isolation | 30 total tests pass across four files; `build/qa/backend-tests.log` |
| Strict approval | Authentication, frozen guardian, wrong account/session, expired/replayed/failed tokens, extension invalidation, stale restart, server-only markers, rate limit, competing approval calls | Passed in mocked Convex runtime; provider and HTTP GET/POST tested without actual email |
| Strict approval malformed input | Empty/overlong token and unsupported POST body | Passed; unsupported body now returns HTTP 400 |
| 0.8 persistence | Permanent repository recreation/warm-up, protected packages, exact strict end and stale approval rejection, overnight schedule boundary | Four Android device tests pass; `build/qa/final-instrumentation.log` |
| Multi-day strict | Start three-day commitment, attempt disable and shorten, assert original end retained; duplicate permanent add | Separate opt-in fixture passed; `EnforcementFixtureTest` |
| 0.8 normal enforcement | Launch permanently blocked Chrome | Blocker hierarchy confirms permanent reason and unavailable unlock; `build/qa/block-launch.xml` |
| 0.8 network loss | Disable Wi-Fi and mobile data, launch Chrome | Blocked; `build/qa/offline-recovery.xml` |
| 0.8 force-stop | Force-stop FocusLock, launch Chrome | **FAIL: Chrome opens; Android removes accessibility enablement**; `build/qa/force-stop.xml` |
| 0.9 permission recovery | Reopen FocusLock after force-stop; restore accessibility | Missing permission visibly surfaced; enforcement resumed; `build/qa/permission-health.xml`, `offline-recovery.xml` |
| 0.8 reboot | Reboot with strict/permanent state, wait for boot, launch Chrome | Permanent blocker and active strict state retained; `build/qa/reboot.xml` |
| 0.8 startup / RC upgrade | Replace installed APK while Chrome is foreground | Initially failed: no foreground event after service rebind. Fixed by checking current window on connection. Retest blocks without another app switch; `build/qa/fixed-upgrade-late.xml` |
| 0.8 process death | Kill app PID using its debug UID, launch Chrome | Rebound service blocks Chrome; `build/qa/process-death.xml`. This is distinct from force-stop |
| 0.85 explanation | Permanent blocker copy | Fixed misleading instruction to turn off an irreversible boundary; verified new text in UI hierarchy |
| 0.97 checks | Android assemble/unit/lint, Convex typecheck, desktop build, extension tests/syntax/build/theme | Pass. Android: 55 JVM tests, zero failures; lint has warnings, no errors. Logs in `build/qa/` |

## Fixes made during this pass

1. Accessibility service checks the active window when it reconnects, covering an already-open app whose launch event was missed. Retries unavailable roots briefly, using existing enforcement paths.
2. Location helpers explicitly check precise permission and handle revocation at the platform call. Both lint MissingPermission errors resolved.
3. Approval POST handles unparseable form bodies with a controlled 400 response.
4. Permanent blocker describes its actual no-expiry/no-in-app-unlock behavior.
5. Fixed test setup: the cross-account requester now has its own guardian and commitment; the device approval timestamp is sampled after the commitment is persisted.

## Open reliability issues

- **Force-stop bypass:** tested failure. Reopening surfaces permission recovery, but FocusLock cannot promise enforcement while Android has stopped/disabled its service. Roadmap 0.8 exit remains unmet.
- **Account switch resets strict state:** source inspection confirms `SettingsRepository.resetForAccountSwitch()` removes the commitment, and `FocusSyncManager.prepareAccount()` invokes it when changing accounts. `restoreAccountState()` restores the destination snapshot. This is not a live signed-in reproduction. A fix must preserve device commitment enforcement while retaining account-data isolation. Permanent blocks use a separate store.
- **Rebind gap:** update/process-death tests show eventual automatic enforcement after service rebind, not zero exposure during Android's rebind interval. Initial post-update dump still showed Chrome; subsequent dump showed the blocker. No precise latency or battery claim is made.

## Roadmap work not proven by this run

| Area | Remaining evidence |
| --- | --- |
| 0.7 | Real simultaneous Android + Windows + browser consumption; browser/native overlap deduplication; full day rollover, timezone and device-clock manipulation; complete limits/schema parity |
| 0.8 | OEM battery management, split-screen/PiP, private/alternate browsers, Shorts, multi-browser tracking, Windows/extension crash recovery; full Strict/Nuke transition matrix; physical GPS/geofence behavior |
| 0.9 | Unassisted fresh onboarding and live account/email/sync recovery across clients |
| 0.93 / 0.95 | Multi-week dogfood and external beta retention require elapsed time and participants; not simulated |
| 0.97 / RC | Signed release/clean-production-config installation, migrations, privacy/secret audit, native Windows installer/update path and packaged browser deployment |

An ADB deep-link launch was rejected by the tool execution policy; that specific check was not executed. Normal launcher entry was tested successfully. No permission workaround was used.

## Reproduction

On a disposable emulator only:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
npm test -- --run
npm run typecheck
npm --prefix desktop run build
npm --prefix extension test
npm --prefix extension run check
npm --prefix extension run build
```

The optional `EnforcementFixtureTest` is skipped unless instrumentation argument `qaFixture=true` is supplied. It intentionally permanently blocks Chrome and establishes a three-day commitment. Run the ordinary four tests on fresh disposable app data, not on that seeded state. Never run the fixture against a personal device/account.
