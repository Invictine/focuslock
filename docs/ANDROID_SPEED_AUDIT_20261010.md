# Android speed and settings audit — 10 October 2026

## Findings and changes

| Finding | Change |
| --- | --- |
| Firebase's manual and post-commit distribution assembled `debug`, replacing the previously optimized local build with an unoptimized tester update. | Default both distribution paths to the existing R8/resource-shrunk `performance` variant. Keep the development signing key, increasing build counter, unit tests, lint, and APK validation. Manual diagnostics can explicitly select `-BuildVariant Debug`. |
| `FrogHomeLauncher.captureFallback()` enumerated every Home candidate during each MainActivity/blocker creation, even when the saved launcher was still current. | Resolve the current Home first and skip enumeration for an unchanged fallback or FocusLock Home. A changed external launcher is still validated, and opening the fallback still validates installed candidates. |
| The accessibility popup ticker scanned all windows again every second even when its first snapshot had no blocked popup candidates. | Clear stale shields and finish immediately on that path. Blocked candidates still get fresh-window verification before shielding. |
| Settings combined an outer generic title with an inner category title/back row. Essential apps nested a second toolbar and retained the main bottom navigation. | Use one destination title and toolbar, hide main tabs inside Settings, and consume host insets before passing them into child content. Essential apps supports a standalone toolbar for isolated use. |
| Advanced TickTick options expanded inside the Connections card. | Open a separate child page; toolbar/system Back returns to Connections. Preserve token, manual callback, and OAuth controls. |
| The same Settings scroll state carried offsets between different pages and reset during permission emissions. | Give every category and the advanced child page its own saveable scroll state. Preserve the explicit permission-shortcut scroll behavior. |
| Essential-app names were restricted to one line, and fixed guidance occupied list space on small/large-text screens. | Let labels wrap and rows grow. Pin search and Save, put guidance in the lazy list, and remove duplicate keyboard padding from content. Keep boundary precedence, core/safety choices, and explicit Save behavior. |

## Other paths inspected

Installed-app inventory and icon conversion already run on IO, with a shared inventory cache and single-flight icon cache. The picker already uses lazy rows. UsageStats aggregation and dashboard task loading retain the caching introduced by the earlier audit. Startup migration/reminder work already uses the application IO scope. No speculative cache rewrite or blocking-policy relaxation was added.

This audit concerns Android and its tester-distribution scripts. It does not change shared Windows Void source or require a standalone Void backport. Pre-existing release metadata, desktop, backend, and extension edits are outside this task's commit.

## Validation

- Android unit tests: 229 passed, including three launcher-capture cases.
- Debug APK, instrumentation APK, optimized APK, and debug lint completed successfully. Lint: 0 errors, 114 warnings, 14 hints.
- Thirteen targeted emulator cases passed across the initial suite and essential-app rerun: Settings/category/advanced Back navigation, activity recreation, Frog settings navigation, Save/cancel behavior, 1.6x font layout, live boundary precedence, and native shield layout. The first large-font test incorrectly waited for an off-screen lazy row; it now scrolls to the row after the list loads.
- Inspected the integrated optimized Settings and Essential apps screens: one toolbar, no main tab bar, visible search and Save. Inspected the large-font capture for wrapping and row growth.
- Optimized APK signature verified; package metadata has no debuggable flag. Optimized emulator launch succeeded in 663 ms (warm, one smoke sample).
- Distribution queue smoke test passed, including exact-commit isolation, serialized uploads, failure recording, idempotence, and explicit selection of Performance. Local distribution validation passed without contacting Firebase.
- After the phone was connected, Android listed FocusLock itself as uninstalled (only its test package remained). Installed the optimized APK successfully on A063/P222C6000273. Android reported a 1,167 ms cold activity launch; the phone returned to the lock screen, so this is not a visible navigation/frame benchmark or a signed-in enforcement check.

Evidence and screenshots are retained locally under ignored `artifacts/android-audit-20261010/`. The installable APK is `app/build/outputs/apk/performance/app-performance.apk`.

## Measurement limits

The phone had no previously installed build to use as a baseline. Emulator UI and optimized-startup checks validate behavior, but cannot establish the phone's frame latency or a percentage speedup. Initial emulator startup coincided with boot and software-renderer initialization; its frame statistics are unsuitable for comparison. An unlocked, visible app is required for a controlled physical navigation/scroll sample and native popup/enforcement checks. Account setup and any missing Android permissions also need to be completed after the fresh installation.

See [the previous audit](../ANDROID_PERFORMANCE_AUDIT.md) for historical measurements; those values are not current results. Android's [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance) recommends checking optimized release builds, and its [inset guidance](https://developer.android.com/develop/ui/compose/system/insets) explains system-bar and keyboard layout handling.
