# Home-only blocking

Home-only mode is a phone-local setting. Saving a home place turns it on; turning it off restores the phone's normal FocusLock behavior. Permanent app and website blocks always apply, regardless of home-only mode, location availability, credits, schedules, or Strict Mode. The setting does not change the PC app or browser extension.

## Setup

1. Open **Boundaries → Blocking location → Choose home location**.
2. While at home, use **Use current location**, or search and select your home address. Check the map preview and radius, then save.
3. Allow precise location. Use the card's permission button to choose **Permissions → Location → Allow all the time** in Android settings.
4. Check that the card says **Active · You're at home** before relying on home blocking. After a reinstall, sign in and restore the usual accessibility/usage permissions too.

When enabled, home-only mode needs precise location permission and Android's **Allow all the time** background location permission. Only a fresh, precise fix that clearly places the phone outside the saved home radius pauses everyday blocking. If device location is off, permission is missing, the fix is stale, or the result is unavailable or ambiguous, the existing blocking rules remain active. Permanent blocks always remain active regardless of location.

Location acquisition checks GPS, network, and the platform fused provider concurrently. Android 12 and later use an explicit high-accuracy request, with up to 12 seconds to obtain a fix. Fix accuracy may be up to the saved radius (capped at 100 m); the complete uncertainty circle must still fit inside the radius to classify the phone as home. This avoids rejecting a valid 35 m-accuracy fix centered within a 50 m home radius. While location is unavailable, the card says **Active · Waiting for a fresh, accurate location**; missing permissions have separate messages.

When a fresh, precise fix confirms the phone is away, everyday app and website blocks, Strict Mode, schedules, limits, Frog, and Nuke pause on the phone. Permanent blocks still intercept their targets and their blocker screens remain locked, including when location is unavailable or permission is revoked. Scroll-time credits are not consumed while everyday enforcement is paused. The saved policies and commitments remain stored. The existing Nuke reset timer is wall-clock based, so it continues to elapse while its screen is hidden away from home. When the phone is home, everyday enforcement remains active. Location checks determine whether a fresh fix can confirm the phone is away; this is not an exact geofence transition guarantee.

## Acceptance checklist

- Set a home location, enable home-only mode, and verify a blocked app such as Instagram is blocked at home.
- Leave the saved radius and verify Instagram opens, blocking pauses, and scroll-time credits do not decrease.
- Reopen FocusLock while away and verify it remains paused.
- Return home with a blocked app already open and verify blocking resumes after the location check.
- Turn off device location or remove location permission and verify existing blocking rules remain active because an away location cannot be confirmed.
- Restart the phone and verify the saved home place and home-only setting persist.
- Disable home-only mode and verify normal phone blocking resumes wherever the phone is.
- Confirm PC blocking and the browser extension are unaffected.
- Verify permanent apps and websites stay blocked away from home and while location is unavailable; credits must not dismiss their blockers.

## UI tests

`app/src/androidTest/java/com/focuslock/app/HomeLocationUiTest.kt` supplies fake home-place, status, and enabled values directly to the composables. Its callbacks only record values in memory; the tests do not write settings, grant permissions, or launch the location picker actions. On a successful instrumentation run, it writes `home-location-card.png` and `home-location-picker.png` to the app's private cache directory for inspection with `run-as <application-id>` and `adb pull`.

Validation on 2026-10-02: all 134 Android unit tests and debug lint passed; debug and optimized performance APKs assembled. All three UI instrumentation tests passed on the connected Nothing A063 phone. The optimized APK was installed and MainActivity launched. UI screenshots are in `artifacts/home-location/`; the picker screenshot includes an existing picture-in-picture overlay. Tests supply fixture coordinates and do not grant permissions, set the user's home, or prove a physical home-to-away trip. The manual acceptance checklist remains pending.

### Historical validation evidence — 2026-10-02

The following records describe behavior and validation observed on that date; use the current behavior above for the present policy.

#### Blocking repair

The installed phone had home-only enabled with a 50 m radius and all location/accessibility permissions granted. Turning home-only off made Feurstagram's blocker appear, confirming that location classification was pausing enforcement. With the acquisition repair, a fresh fused fix reported approximately 40 m from the saved home with 23 m accuracy: its uncertainty circle crossed the 50 m boundary. The phone's radius was adjusted to 100 m. A read-only diagnostic then reported `homeOnly=true`, `status=AT_HOME`, and `shouldEnforceNow=true`.

All 135 unit tests and debug lint passed. The three home-location UI tests passed on the emulator. Debug and optimized performance APKs built, and the optimized APK was installed on the Nothing A063 phone. Accessibility was reconnected after instrumentation, with the service bound and no crashed services. A physical home-to-away trip remains unverified.

The opt-in device diagnostic does not change settings or emit coordinates. Run only `HomeEnforcementDiagnosticsTest` with instrumentation argument `homeDiagnostics=true`; `expectedHomeStatus=AT_HOME` additionally requires the actual phone to classify as home. Instrumentation can interrupt the target app's accessibility process; restore its normal build and reconnect its already-enabled accessibility service before judging app enforcement.

#### Permanent commitments ignore location

Permanent app checks run before the general home-only gate and Nuke policy. Permanent websites are classified before any location, balance, suppression, schedule, or group-limit check. Their block recording, activity launch, and visible blocker lifetime all bypass home-only restrictions. Legacy permanent entries are also recognized when the blocker rechecks its target before credit or unlock actions.

Validation: all 136 unit tests and debug lint passed. The three home UI tests and both permanent/home override instrumentation tests passed on the disposable emulator. With home-only paused and location unavailable, opening permanently blocked YouTube produced the actual permanent blocker. Local app and website blocker scenarios stayed resumed through their ten-second location recheck and an earned-credit broadcast. Website lifecycle checks used a local blocker target and did not open an external website. The optimized build was installed on the connected Nothing A063 phone; accessibility remained bound with no crashed services. A physical away-from-home trip remains unverified.
