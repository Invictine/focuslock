# Desktop Eat the Frog is temporarily disabled

The desktop feature flag in `src/features.ts` is off. Frog controls, the Focus
card, and the integrated Void launch flow are inactive. Public Frog actions,
automatic daily rollover, arming, and progress metering do not write saved
preferences while the flag is off. Saved selection and progress remain available
for a future restoration.

Native tracking removes old targets whose reason is `frog` on startup and on
policy updates. Application boundaries, daily app limits, and permanent blocks
retain their own enforcement. Permanent target merging happens first so a
permanent target cannot be released by an old Frog reason. The matching native
flag is in `src-tauri/src/tracking.rs`.

Website boundaries continue to use the extension and its companion health
check. Desktop Frog alone no longer requires that check. Android and browser
extension Frog behavior are unchanged.

This change concerns the FocusLock desktop host's feature and policy, with no
shared Void launcher source changes. Standalone Void has no equivalent desktop
Frog switch and needs no backport for this suspension.
