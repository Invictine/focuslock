# Android setup and Strict Mode

The feature guide teaches one feature per page with a short example, Back/Next,
and links to the relevant settings section. Opening a feature saves the guide
position; Settings resumes an unfinished guide or replays a finished one.
Reading the guide never enables Frog, Strict Mode, Nuke or permanent blocks.

Permission setup uses one device-local saved sequence. Settings **Set up
permissions** and the Focus setup button both reopen that wizard. Dismissal,
an Android Settings trip and process recreation preserve the original steps
and current position. A completed or explicitly skipped setup starts a new
sequence at its first missing step. Instructions scroll above visible actions.

Strict Mode separates an access method from optional device protections:

| Method | Release condition |
| --- | --- |
| Timer | Chosen end time, up to 30 days |
| Follow schedules | End of enabled weekly focus windows |
| PIN | Saved PIN, with five failed attempts triggering a five-minute lockout |
| Charger | Power is connected on the device requesting release |
| Cooldown | A saved waiting period of 2–10 minutes |
| Approval | The preconfigured contact approves the email request |

PIN, charger, cooldown and approval commitments also have a maximum end time.
Access methods and protections cannot change during an active commitment.
Existing boundaries cannot weaken; stronger app/site blocks can still be added.
Permanent blocks survive an early release. A live commitment prevents switching
the local sync policy to another account's defaults.

Account releases are checked by the server and bound to the exact session and
end time. An expired, extended or replaced commitment cannot reuse a release.
PIN credentials use salted PBKDF2 and never appear in account queries. Signed-in
early releases need a connection; offline installations use local PIN, charger
and persisted cooldown checks. Email approval requires the configured email
provider and review URL. Android checks charger state; stock Android supplies
no server attestation of the cable connection.

Optional protections target Android Settings, detected FocusLock uninstall/admin
removal prompts, recognized overview activities and split-screen layouts.
Saving device protections requires Accessibility access, with a direct link to
the permission setup screen when that prerequisite is missing.
Phone/emergency tools, keyboards, required permission prompts and FocusLock
remain reachable. The Settings blocker offers only Home and Open FocusLock;
credit or ordinary leisure unlocks cannot dismiss it. OEM overview and window
behaviour needs device testing. Reinstalled apps retain their saved package-name
boundaries; this protection is always on.

Location remains under **Boundaries → Blocking location**, including existing
location commitments. Saved home label, coordinates and radius sync through an
authenticated account record with revision checks. Home-only consent and Android
location permissions stay on each device. Offline edits have independent versions,
and stale responses cannot write into another account's location store. Unknown
location keeps blocking active; permanent blocks apply everywhere. Weekly window
definitions remain local; active windows publish timed commitments to the account.

Validation uses unit/backend tests and Android instrumentation, including small
screens, large text, setup resume and Strict Settings actions. Emulator and admin
query checks do not establish real signed-in propagation on two physical devices.
