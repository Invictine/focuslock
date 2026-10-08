# Android system controls and popup blocking

Settings, system UI, permissions, emergency/phone controls, FocusLock itself, and
packages registered with Android as input methods remain available. All registered
keyboards qualify, including Samsung Keyboard when another keyboard is selected.
The service checks this device access policy before permanent, Nuke, Frog, app
limit, group limit, schedule, and ordinary app-boundary decisions, and rechecks it
before launching a blocker. Existing commitments are preserved in storage.

This is an exact package/registered-role policy. It does not exempt every system
app or every package containing `settings` or `keyboard`. Browsers and Google Play
still follow their existing blocking rules. Existing Frog shortcuts, approved
tools, update recovery, and official billing exceptions retain their own policy.

Popup shields retain native system and input-method window geometry as occluders,
subtracting their higher-layer bounds from blocked regions on the same display.
Those windows do not become popup enforcement targets or count as a second app.
Keyboard settings activities remain foreground app windows and receive the device
access exemption. Closing a control restores shielding of the blocked app.

## Verification

- `DeviceAccessPolicyTest`: Samsung and inactive registered keyboards, exact
  settings matches, missing resolvers, and rejection of browsers/stores/spoof names.
- `InteractiveWindowPolicyTest`: keyboard/system occlusion, keyboard settings in
  front of a blocked app, display separation, and restoration after controls close.
- `DeviceAccessPlatformTest`: actual Android IME registration, Frog and permanent
  block protection, and browser/store exclusion on a device or emulator.

Run Android unit tests, debug/instrumentation assembly, and lint. On a connected
device, run the platform tests and `PopupShieldLayoutTest`. Samsung acceptance also
requires checking Keyboard settings, keyboard switching, Quick Settings, and
blocked apps/sites in Popup view and DeX. Emulator checks do not establish Samsung
hardware acceptance.
