# Strict Mode

Strict Mode protects existing boundary configuration until the commitment ends or an approved release is received. You can still add app and website boundaries or permanent blocks. It does not independently block opening an app or website.

- Apps and websites follow their normal credit, schedule, limit, permanent-block, Frog, and Nuke rules.
- Strict Mode does not suppress notifications, disable earned-credit unlocks, prevent ordinary credit snoozes, or activate Nuke after blocked launches.
- New blocked apps and websites can be added while Strict Mode is active, and an existing unblocked target can be switched to blocked. Permanent blocks can also be added. Existing targets cannot be removed or unblocked; targeting options, groups, limits, and schedules stay locked. Identical cloud snapshots may still be synchronized.
- Android manual, schedule, and location activation all apply the boundary edit lock.
- Commitments retain their existing expiry, extension, and guardian-approval behavior.

The legacy `strictNukeAfterFive` preference remains readable for compatibility with existing synced records, but current clients do not use it for enforcement or offer it as a Strict Mode option.

This change concerns FocusLock's Android, Chrome, backend, and desktop host policies. The shared launcher under `windows/void/` is unchanged; standalone Void has no FocusLock Strict Mode boundary configuration and needs no equivalent backport.

## Commitment setup and existing protections

- Android and desktop commitments support up to 30 days. Android offers separate hour/day controls and a calendar end date; active commitments can only be extended.
- Android place selection supports address search, current position, a 50–1,000 metre radius, editing saved coordinates/radius, and an external map preview. Location activation stays active unless a fresh, precise fix confirms the phone is outside every enabled place; an uncertain boundary reading keeps Strict Mode active. If location services, precise/background permission, or a fresh reliable fix is unavailable, Strict Mode stays active. This applies to Strict Mode activation only; everyday blocking is controlled separately by home-only mode.
- **Only block at home** pauses everyday Android blocking and credit spending only after a fresh, precise fix confirms the phone is outside the saved home radius. If location is off, unavailable, stale, ambiguous, or permission is missing, existing blocking rules stay active. Permanent blocks always stay active. App launches and periodic checks revalidate location; this is not an exact geofence transition guarantee. The preference is phone-local. See [setup and verification](HOME_ONLY_LOCATION.md).
- A trusted person's email can be configured before a commitment. Approval requests expire after 30 minutes, are limited to three per hour, and only unlock the exact commitment requested. Permanent blocks and automatic place/schedule rules are not removed by approval. See [email setup](STRICT_APPROVAL.md).
- Android permanent app blocks are stored separately on the device. They have no expiry, in-app removal, credit unlock, or emergency pass. Adding one requires confirmation. Phone, launcher, keyboard, Settings, and FocusLock recovery surfaces are protected. These blocks are device-local and do not sync to desktop or other phones. Android permissions and app installation must remain intact for enforcement.
- Strict Mode controls use accessible touch targets and scrollable dialogs. Countdown updates follow displayed minute boundaries and pause in the background; expanding rules avoids nested entrance animations. Desktop controls refresh at expiry without a per-second timer.

Earlier validation, before the boundary-only change: Android debug APK assembly, lint, 55 unit tests, four API 35 emulator instrumentation tests, and a separate multi-day/permanent-block fixture; desktop production build; extension tests/check/build; Convex typecheck and 30 backend/client regression tests. Emulator checks covered normal launch, offline operation, permission recovery, reboot, process death, and APK replacement. See [HARDENING_TEST_REPORT.md](HARDENING_TEST_REPORT.md) for failures and limits. Physical GPS and live approval email remain untested. Deploy the backend and configure the email provider before using email approval in the new clients.
