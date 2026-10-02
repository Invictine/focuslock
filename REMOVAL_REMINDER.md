# Personal removal reminder

In Android Settings inside FocusLock, the **Removal reminder** section lets the user
enable a confirmation popup and choose an audio or video clip. Recording opens an
installed camera or sound recorder; if none handles the request, the file picker
is offered instead. Preview does not change device-admin status.

## When it appears

- Deactivating admin from FocusLock opens the reminder before removing admin.
- Android's admin-disable request and disabled callbacks attempt to show the reminder.
  The system's text warning remains available even if Android suppresses the activity.
- With FocusLock accessibility enabled, recognized system uninstall dialogs targeting
  FocusLock trigger the reminder even after device admin has been disabled.
- Continuing or dismissing the reminder briefly suppresses repeated triggers so the
  system removal flow remains usable. The app never presses the system's uninstall button.

External callbacks are best effort. Android background activity restrictions, OEM
dialog differences, missing accessibility access, and notification permissions can
prevent the custom reminder from appearing. A tappable notification is the fallback
where possible. The app cannot play anything after it has been uninstalled.

## Media

Files are copied to private, backup-excluded device storage; no cloud upload or sync
is performed. Imports are limited to 50 MB and validated as audio/video. A failed
import keeps the previous clip. Playback uses the device's media volume and stops
when the reminder leaves the foreground. Recordings created by another app may also
remain in that recorder's own storage.

## Verification

Automated coverage lives in `RemovalAttemptPolicyTest`, `RemovalReminderStoreTest`,
and `RemovalReminderUiTest`. Physical-device verification should include:

1. Import and preview audio, then video; test pause, replay, rotation, Home, and Back.
2. Try recording with and without a compatible recorder installed.
3. Enable the reminder and deactivate admin from FocusLock; test both choices.
4. Request admin deactivation from Android Settings; check the system warning,
   custom reminder or notification fallback, and that continuing is still possible.
5. Open the system uninstall confirmation for FocusLock with accessibility enabled;
   cancel instead of completing uninstall. Verify other apps' uninstall dialogs and
   FocusLock installation/update dialogs do not trigger it.
6. Repeat with notification/overlay/accessibility permissions unavailable to verify
   graceful fallback. A missing reminder must never be presented as hard protection.
