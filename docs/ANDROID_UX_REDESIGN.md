# Android navigation redesign

Android now separates daily actions, protection configuration, and progress review.

- **Today** shows the available leisure balance, the daily priority when Eat the Frog is enabled, a quick focus timer, manual logging, and a compact TickTick task entry. Missing-permission recovery remains contextual. Full lockdown (Nuke) is reached through More and retains its confirmation.
- **Boundaries** links to Apps and Websites for editing. Limits stay inside the existing picker. Lock boundary changes, Permanent blocks, and Where blocking applies are distinct destinations; the overview no longer repeats the editable block lists.
- **Activity** contains the real focus/leisure ratio, focus chart, app usage, and work history. Manual logging remains available here and from Today.
- **Settings** is available through the app bar on all three tabs. Its root links to Account & devices, Connections, Daily priority routine, Permissions & protection, and Preferences. Advanced TickTick setup is disclosed separately from ordinary connection and sync controls.

Back from Settings returns to the tab that opened it. Back from a Settings category returns to its root; Back from Lock boundary changes returns to Boundaries. Today and Activity share the dashboard's timer producer and keep separate scroll positions. A running timer can be hidden and resumed without ending the session.

## Policy and compatibility

Strict Mode still freezes boundary configuration. Persistent Boundaries Lock still protects removals. Permanent blocks remain in their existing dedicated flow. The redesign does not change access verdicts, credit calculation, Frog tracked-time requirements, or explicit task completion.

The legacy home layout setting is retained in storage but is no longer offered as a choice, since Today and Activity have fixed responsibilities. Authentication, TickTick OAuth, sync, and existing essential-app setup use their original implementations.

This change affects Android Compose screens only. It does not modify the Windows shared launcher under `windows/void/`, so there is no standalone Void equivalent or backport.

## Validation

Verified on October 8, 2026:

- `testDebugUnitTest`: 203 tests, zero failures.
- `assembleDebug` and `assembleDebugAndroidTest`: passed.
- `lintDebug`: passed; existing warnings remain.
- Ten emulator UI tests passed across `AndroidNavigationUiTest`, `BoundariesNavigationUiTest`, `FrogSettingsNavigationUiTest`, and `MainNavigationUiTest`.
- An additional Today/Activity test passed in dark mode with system font scale set to 150%. Screenshots were reviewed in light and dark mode; permanent/location navigation and the boundary-lock callback were exercised at 150% text size.

The navigation checks exercise Settings return destinations, category restoration after Activity recreation, category Back navigation, Boundaries-to-Strict and permanent/location routes, the routine-to-essential-apps route, and timer preservation across Today and Activity.

The restricted Windows session denied Java's real-path resolution for Android SDK and dependency JARs. Successful packaging and checks used an uncommitted local build adapter that invokes standard JDK javac for generated `BuildConfig.java` (the app has no other Java sources) and an explicitly located copy of the existing debug key. Production Gradle configuration is unchanged. Account sign-in, TickTick OAuth, cross-device sync, and physical-phone enforcement were not live-tested in this redesign task.
