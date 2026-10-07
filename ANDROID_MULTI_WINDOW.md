# Android pop-up and desktop windows

FocusLock's Android accessibility service observes `TYPE_WINDOWS_CHANGED` in addition
to app-state and browser-content events. It selects the active application window
(then the input-focused application window), rather than treating Samsung's captions,
keyboard, or an event from a background app as a foreground app switch.

For pop-up, split-screen, and desktop layouts it also checks every exposed application
window. Foreground and popup app checks use the same access decision: permanent
blocks, location, Nuke, TickTick/Frog tools and billing, daily/group limits, schedules,
and credits. Strict Mode continues to freeze configuration only.

Blocked popup content receives a touchable, opaque `TYPE_ACCESSIBILITY_OVERLAY` shield.
The shield is clipped to the target's display and to pieces not covered by higher
application windows, so a permitted app above it remains usable. It updates after
window changes and on a one-second recovery ticker. Moving, resizing, closing, or
allowing the target removes or updates its shield. Open FocusLock revalidates the
current policy and opens the appropriate blocker, using the target display where
the platform permits it. This needs the connected accessibility service; it does
not require a new overlay permission prompt.

Browser popups are read by their individual window identity, including when another
app has focus. Foreground credit tracking still has one owner. Switching between
windows of the same app does not restart the spending countdown; changing website
targets still changes the existing website tracking normally.

Service diagnostics include `foregroundWindowId`, `popupShieldRegions`, and
`popupShieldError`. A failed overlay attachment is reported, not treated as successful
enforcement. Android 11+ enumerates windows on all displays; older supported versions
enumerate the default display.

## Verification

The regression tests cover active/focused selection, multiple application windows,
display separation, popup sizing, moved/closed windows, and exact, disjoint shielding
under overlapping permitted windows. Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain
```

Native smoke testing uses a disposable Android emulator with freeform support enabled.
The existing opt-in `EnforcementFixtureTest` permanently blocks Chrome in that emulator
and disables cloud use. Never run that fixture on personal hardware. Check:

1. Chrome in a freeform window reaches the permanent blocker.
2. With FocusLock or an allowed app in a separate freeform window, exposed Chrome
   content is shielded even when Chrome is not focused.
3. A touch inside the shield does not reach Chrome; Open FocusLock opens the blocker.
4. Moving/resizing Chrome moves/resizes the shield, with no rectangle over a higher
   allowed application window. Closing Chrome removes its shield.
5. Accessibility remains bound with no crashed services or uncaught coroutine error.

On 2026-10-07 the integrated checkout passed 201 JVM tests, debug APK assembly, and
debug lint. The isolated fix passed 191 JVM tests plus debug/instrumentation APK
assembly and lint. Eight existing Android instrumentation fixture/policy tests passed
on `emulator-5554`. Native freeform Chrome blocking, shielding while Settings held
focus, clipping under the permitted window, resizing, Open FocusLock, and removal
after Chrome stopped were checked. The service remained bound without crashed
services or an uncaught coroutine error. The native run used the final fix built
against the isolated baseline; the integrated checkout's separate validation covers
the concurrent TickTick/dashboard changes without mixing them into this task's commit.

Samsung Pop-up view, DeX, blocked-domain navigation in Samsung Internet, and external
DeX display behavior still require checks on the tablet. Emulator freeform checks do
not establish Samsung-specific acceptance. The user's Samsung tablet was not connected
for this task. This change is entirely Android-specific; it changes no shared Windows
Void launcher source and needs no standalone Void backport.
