# Android boundaries navigation

## Structure

The bottom bar has three destinations: **Focus**, **Boundaries**, and **Strict**. Settings and Account retain their existing dashboard entry points.

Boundaries is the home for blocking policies:

- **Everyday boundaries → Applications / Websites** opens the existing credit-based blocking and daily-limit pickers. Merge remains available there.
- **Permanent commitments → Permanent blocks** opens the permanent app and website lists. Adding a permanent target still requires explicit confirmation. Everyday pickers no longer put an irreversible lock icon beside the ordinary block switch.
- **Where blocking applies → Blocking location** opens home-only setup, location status, and permission recovery. Home-only mode pauses everyday rules only when a fresh, precise fix confirms the phone is away. If location is off, unavailable, stale, ambiguous, or permission is missing, existing rules remain active. Permanent blocks always remain active.

Each detail screen has a leading Back action. System Back returns to the hub; the hub retains its scroll position. Cross-device New bucket requests still open the Applications picker and consume their pending target once.

Strict is a separate destination for timed commitments and their Manual / Schedule / Location activation. Its Location option activates Strict Mode near a saved place; home-only blocking controls where all phone enforcement applies and lives under Boundaries. Protection settings, detailed rules, and trusted-person approval are disclosed under **More protection options**.

## Visual and accessibility decisions

Use the existing Material 3 theme, neutral grouped rows, semantic containers, and one prominent action per setup. Permanent setup states that it cannot be undone; home setup shows the location choice before optional renaming. Back placement is consistent across detail pages.

Interactive rows have at least 48dp targets and grow with text. At font scale 1.3 or above, Strict activation and commitment styles use full-width radio rows so labels remain readable. At ordinary sizes they retain the segmented selector. Location permission failures retain explicit recovery actions.

Permanent commitments still have no in-app removal and survive ordinary list replacement and app reinstallation. Permanent blocks remain enforced regardless of the phone's home-only location status; everyday rules are paused only by a fresh, precise away fix. No sync or enforcement policy was rewritten for this navigation change.

## Competitor inspection

On the Android 35 `emulator-5554`, installed AppBlock and Freedom from unmodified APKPure packages for UI review. AppBlock onboarding was accessible, but proceeding without an account produced a Google Play validation error. Freedom onboarding was accessible; screenshots record its account gate. StayFree installation was attempted, but its downloaded split package required a 32-bit ABI unavailable on this emulator. These are review limitations, not evidence of inspected signed-in blocking flows.

Also inspected the emulator's Android Digital Wellbeing → App limits list. Its explicit list-to-detail structure supports the hub and detail navigation used here. Freedom's documentation separates blocklists from session activation, and AppBlock documents separate Blocking and Strict Mode areas:

- https://support.freedom.to/en/articles/1773666-how-to-start-a-block-session
- https://appblock.app/how-to-customize-my-appblock/

Reference captures are in `artifacts/boundaries-ux/competitor-*.png`; FocusLock captures are in the same folder.

## Verification

Run instrumentation only on an emulator: `PermaLockUiTest` writes append-only fixture commitments and must not be run on the user's phone.

Relevant suites:

- `MainNavigationUiTest`: actual activity, bottom destinations, permanent and home detail routes, system Back, Strict activation routes, and tab-state retention.
- `BoundariesNavigationUiTest`: hub/detail/back paths and 1.5x text.
- `PermaLockUiTest`: persistence, filtering, permanent website confirmation, and one Strict activation panel at a time.
- `HomeLocationUiTest`: home status, disable callback, retained location coordinates/radius, and picker preview.

Validated on 2026-10-02:

- All 134 unit tests pass with zero failures.
- All 11 instrumentation tests in the four suites above pass on `emulator-5554` (Android 35).
- Debug lint passes with zero errors; existing warnings remain.
- Debug, instrumentation, and optimized performance APKs assemble successfully.
- The optimized performance APK was installed on the emulator and its app picker, website picker, and Strict Mode were inspected. No AndroidRuntime errors appeared in the checked launch window.
- After emulator validation, the same optimized APK was installed with `adb install -r` on the connected Nothing A063. MainActivity launched, the process remained running, and no AndroidRuntime errors appeared in the checked launch window.
- Final normal-size and 1.5x captures were inspected. Saved captures include `main-boundaries.png`, `main-permanent.png`, `boundaries-location.png`, `strict-hierarchy.png`, and `optimized-app-picker.png`.

UI testing does not establish a physical home-to-away trip or a live signed-in cross-device round trip.
