# Android performance audit — 30 September 2026

## Changes

| Finding | Change |
| --- | --- |
| Every animated row independently read Android's animator setting and registered an observer | Share one motion-preference observer per themed screen, retaining the live reduced-motion setting. |
| Boundaries and Permalock composed their full overview lists, including off-screen icons | Use lazy rows with stable identities and content types, retaining the connected card appearance. |
| Permalock resolved labels through PackageManager during composition | Resolve and sort labels on IO; show existing labels while the result arrives. |
| Focus tab re-entered a screen-owned TickTick load, including an uncached open-task fan-out | Keep an activity-owned snapshot for three minutes, scoped by token and local day; share in-flight routine requests. Explicit refresh bypasses the cache. |
| Task calls ran sequentially and sorted the entire list to select one task | Load completed/open tasks concurrently and choose the next task in one pass. |
| Every dashboard entry/boundary emission invalidated the shared usage cache | Reuse its 30-second aggregate; explicit pull refresh invalidates it. Permission changes invalidate the applicable cached permission state. |
| Per-app usage checks could perform UsageStats and label queries on the caller's main dispatcher | Enforce IO dispatch within the repository and serialize aggregate invalidation with queries. Propagate coroutine cancellation. |
| Concurrent installed-app callers could independently perform the same PackageManager scan | Serialize and recheck app inventory refreshes; overlapping forced refreshes share the result. |
| Balance notifications were constructed and sent on Main | Update notifications on IO, reuse the notification manager/pending intent, and use a monotonic throttle clock. |
| Ordinary apps generated unnecessary accessibility content-change work | Drop irrelevant content events immediately. Preserve window-state events, installer reminder scans, and foreground-browser monitoring, including the browser's first window event. |
| Cooldown readers kept polling while screens were hidden and duplicated polling in the nested picker | Poll only while started, pause the overview poll while its picker is open, and stop when cooldown expires. |
| Tab disposal discarded saved scroll/form state | Save each tab's state without keeping its layout or collectors composed. |
| Refresh imposed a minimum 1.5-second spinner even when work finished sooner | End the spinner when actual work settles; clear it on cancellation/failure. |
| Phone was running an unoptimized debug APK | Add a locally signed `performance` variant with release optimization/resource shrinking; use the existing development signing key to preserve installed data. |
| AGP's embedded optimizer warned repeatedly while parsing dependency Kotlin metadata | Pin stable R8 9.1.55 using the official plugin-management override. |

The repository already contained substantial uncommitted work. This audit preserves it; results apply to the combined current checkout. No cloud functions, extension, desktop implementation, user boundaries, or account configuration were changed by this audit.

## Verification

Device: Nothing Phone (1), A063/Spacewar, `P222C6000273`, connected by USB, 1080 × 2400. Android's stay-awake-while-powered setting was enabled at the user's request (previous value 0, new value 15).

### Physical-device sampling

Each sample resets `dumpsys gfxinfo com.focuslock.app`, switches Boundaries → Strict → Permalock → Focus, and scrolls Focus up/down, repeated five times. Navigation taps use the observed bottom-tab positions. No block switches, commitments, or account settings are changed.

| Sample | Frames | Janky frames | p50 | p90 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Previously installed debug APK | 839 | 56 / 6.67% | 5 ms | 8 ms | 20 ms | 105 ms |
| Intermediate updated debug APK | 1,010 | 54 / 5.35% | 5 ms | 7 ms | 24 ms | 150 ms |
| Final debug APK | 887 | 46 / 5.19% | 5 ms | 9 ms | 36 ms | 250 ms |
| Final optimized APK, first sample | 654 | 17 / 2.60% | 6 ms | 7 ms | 12 ms | 73 ms |
| Final optimized APK, warm repeat | 630 | 17 / 2.70% | 6 ms | 7 ms | 17 ms | 53 ms |

The intermediate debug sample was taken before the shared motion-observer fix. It reduced the jank percentage but did not improve tail frame times. These are short UI smoke samples, not controlled Macrobenchmarks: builds, process warmth, caches, background sync, and device conditions differ. The previously installed APK's exact source revision is not established. Do not attribute a precise percentage speedup to individual source changes.

The optimized build improved observed jank and tail frame times. It is still capable of occasional slow frames; these samples do not establish universal smoothness. Debug-only samples did not demonstrate improved tail frame times, so the final on-device result depends substantially on using the optimized build.

Process PSS after the first optimized sample was 118,188 KiB (about 115 MiB), versus 221,558 KiB (about 216 MiB) for the previously installed debug build. The final APK is 9,316,722 bytes, versus 32,422,427 bytes for the original installed APK.

### Final validation

- `testDebugUnitTest`, `assembleDebug`, `assemblePerformance`, and `lintDebug` passed on the final source. All 105 unit tests passed, including six new task-cache/concurrency tests. Lint reports 0 errors, 88 warnings, and 14 hints.
- The first optimized launch exposed a release-only WorkManager/Room reflection failure. Added exact keep rules for `WorkDatabase` and `WorkDatabase_Impl`, rebuilt successfully, and confirmed the repaired optimized APK launches. The database implementation and the ViewModel's no-argument constructor remain present in the optimized mapping.
- Installed the repaired optimized APK with `install -r`; package is no longer debuggable. Inspected the Focus and Boundaries UI, scrolled the 123-app picker, and opened Strict/Permalock during navigation tests without changing rules. The saved 12 blocked apps and 7 blocked domains remain present.
- Verified running accessibility, notification-listener, and foreground-monitor services. After replacement, the foreground notification appeared on a normal Home → app resume; initial connection timing remains a follow-up robustness case.
- Checked the current application process's runtime log after the repaired installation: no AndroidRuntime crash entries. Left the phone on Focus, with stay-awake-while-charging enabled as requested.

## Follow-up boundaries

- Live TickTick networking cannot be timed on this phone while TickTick is disconnected. Cache/account/cancellation behavior is covered by injected-loader unit tests. The inherited completed-task API still maps some failures to an empty list; this audit does not change that contract.
- No destructive enforcement test is performed against the user's active rules. The enabled accessibility service and saved boundaries are checked after installation.
- App-specific Baseline Profiles and repeatable release Macrobenchmarks remain useful follow-up work for controlled cold-start and long-list regressions.

## Reproduction

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assemblePerformance :app:lintDebug --console=plain
adb -s P222C6000273 install -r app/build/outputs/apk/performance/app-performance.apk
adb -s P222C6000273 shell am start -W -n com.focuslock.app/.ui.MainActivity
```

The optimized build is for local use with the development key. Production signing is unchanged. Debug can be installed again with `install -r` using the same key.

Evidence is under `artifacts/android-performance/`: frame statistics, screenshots/UI dumps, build output, and a backup of the APK that was installed before the audit.

## Reference material

- [Android Compose performance guidance](https://developer.android.com/develop/ui/compose/performance) recommends release optimization and reducing unnecessary work/state reads.
- [Android Kotlin/R8 compatibility table](https://developer.android.com/build/kotlin-support) documents the supported metadata/tool versions.
- [Official R8 override instructions](https://r8.googlesource.com/r8/+/refs/heads/main/README.md#replacing-r8-in-android-gradle-plugin) document the plugin-management override used here.
