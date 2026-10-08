# Android navigation redesign

Android now separates daily actions, protection configuration, and progress review through three primary tabs.

- **Today** shows the available leisure balance, the daily priority when Eat the Frog is enabled, a quick focus timer, manual logging, and a compact TickTick task entry. Missing-permission recovery remains contextual.
- **Boundaries** links to Apps and Websites for editing. Limits stay inside the existing picker. Lock boundary changes, Permanent blocks, and Where blocking applies are distinct destinations; the overview no longer repeats the editable block lists.
- **Activity** contains the real focus/leisure ratio, focus chart, app usage, and work history. Manual logging remains available here and from Today.
- The **Today**, **Boundaries**, and **Activity** app bars expose **Nuke**, **Settings**, and **Profile** icons in that exact order. There is no More menu. Nuke retains its confirmation flow, and an active Nuke state exposes Resume.
- **Settings** groups Account & devices, Connections, Daily priority routine, Permissions & protection, and Preferences. Advanced TickTick setup is disclosed separately from ordinary connection and sync controls. Guide replay and resume live under Preferences.

The redesign uses consistent 16dp gutters and card interiors. Actions stack responsively at large font scales or narrow widths, and polished interactive controls use 48dp minimum touch targets.

Back from Settings returns to the tab that opened it. Back from a Settings category returns to its root; Back from Lock boundary changes returns to Boundaries. Today and Activity share the dashboard's timer producer and keep separate scroll positions. A running timer can be hidden and resumed without ending the session.

## Policy and compatibility

Strict Mode still freezes boundary configuration. Persistent Boundaries Lock still protects removals. Permanent blocks remain in their existing dedicated flow. The redesign does not change access verdicts, credit calculation, Frog tracked-time requirements, or explicit task completion.

The legacy home layout setting is retained in storage but is no longer offered as a choice, since Today and Activity have fixed responsibilities. Authentication, TickTick OAuth, sync, and existing essential-app setup use their original implementations.

This change affects Android Compose screens only. It does not modify the Windows shared launcher under `windows/void/`, so there is no standalone Void equivalent or backport.

## Validation

Verified on October 8, 2026 using the standard Gradle build:

- `testDebugUnitTest`: 211 tests, zero failures.
- `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`: passed.
- 16 emulator UI tests passed across Android navigation, Boundaries, Frog settings, main navigation, product onboarding, the startup auth gate, and the in-app account sign-in surface.
- Two additional header/navigation checks passed in dark mode at 150% system text size. Light and dark screenshots were reviewed; permanent/location and Strict navigation were also exercised at 150% text size.

The validation pass covers Settings return destinations, category restoration after Activity recreation, category Back navigation, Boundaries-to-Strict and permanent/location routes, the routine-to-essential-apps route, and timer preservation across Today and Activity. Google authentication, TickTick OAuth, cross-device sync, and physical-phone enforcement were not live-tested in this redesign task.

The branch was rebased on `origin/master` at `45fa0b0`, preserving the latest onboarding fixes and the broken Android in-app updater removal as separate changes.
