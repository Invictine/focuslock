# Personal removal reminder

In Android Settings inside FocusLock, the **Removal reminder** section lets the user
enable a confirmation popup and choose an audio or video clip. Recording opens an
installed camera or sound recorder; if none handles the request, the file picker
is offered instead. Preview does not change device-admin status.

## When it appears

- Deactivating admin from FocusLock opens the reminder before removing admin.
- Android's admin-disable request and disabled callbacks attempt to show the reminder.
  The system's text warning remains available even if Android suppresses the activity.
- With accessibility enabled, opening FocusLock's active device-admin detail or
  deactivate-and-uninstall screen shows the reminder before the final system action.
  This covers the Device admin apps list and the App info > Uninstall route, while
  ignoring activation screens and other apps' administrators. Continue returns to
  the same Android screen without deactivating admin or pressing Uninstall.
  The detector also uses Android's record of FocusLock's active admin state, since
  modern Settings can hide the final action's text from accessibility observers.
- With FocusLock accessibility enabled, recognized system uninstall dialogs targeting
  FocusLock trigger the reminder even after device admin has been disabled. This
  includes explicit confirmation dialogs hosted by Android Settings; the ordinary
  FocusLock App info screen and its Uninstall button alone do not trigger it.
- Continue suppresses repeated triggers for one minute so the system removal flow
  remains usable. Back, outside taps, and Keep return away from the removal screen
  without authorizing another attempt. The app never presses the system's uninstall button.

External callbacks are best effort. Android background activity restrictions, OEM
dialog differences, missing accessibility access, and notification permissions can
prevent the custom reminder from appearing. A tappable notification is the fallback
where possible. The app cannot play anything after it has been uninstalled.
Android deliberately restricts app switches and overlays during the final admin
warning, so the custom popup is shown earlier. If accessibility is already off,
the receiver's Android-owned text warning remains the fallback for admin deactivation;
the custom media popup cannot be guaranteed on every OEM screen.

## Media

Files are copied to private, backup-excluded device storage; no cloud upload or sync
is performed. Imports are limited to 50 MB and validated as audio/video. A failed
import keeps the previous clip. Playback uses the device's media volume and stops
when the reminder leaves the foreground. Recordings created by another app may also
remain in that recorder's own storage.

## Verification

Automated coverage lives in `RemovalAttemptPolicyTest`, `RemovalReminderStoreTest`,
`RemovalReminderUiTest`, and `RemovalReminderSystemTest`. The system test exercises
real emulator Settings, keeps accessibility services running during instrumentation,
and cancels before removal. Physical-device verification should include:

1. Import and preview audio, then video; test pause, replay, rotation, Home, and Back.
2. Try recording with and without a compatible recorder installed.
3. Enable the reminder and deactivate admin from FocusLock; test both choices.
4. Request admin deactivation from Android Settings; check the system warning,
   custom reminder or notification fallback, and that continuing is still possible.
5. Open the system uninstall confirmation for FocusLock from Android Settings and
   with accessibility enabled; cancel instead of completing uninstall. Verify
   FocusLock's ordinary App info screen, other apps' uninstall dialogs, and
   FocusLock installation/update dialogs do not trigger it.
6. Repeat with notification/overlay/accessibility permissions unavailable to verify
   graceful fallback. A missing reminder must never be presented as hard protection.
