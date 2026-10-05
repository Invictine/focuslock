# Eat the Frog on Android

Frog starts at the configured local wake hour. On the first usable unlock, FocusLock opens a quiet black screen. If the display stays on across the wake hour, the accessibility monitor checks again without needing an app switch.

The black screen contains only the unlock message, app icons, and a large **Open FocusLock** button. It hides the status bar. Android can reveal transient system bars with an edge gesture.

1. Open FocusLock and pick a cached TickTick task or enter a task yourself.
2. Confirm any extra apps needed for the task inside the Frog card. Previous extra tools appear as suggestions; they are not automatically allowed.
3. Work using the confirmed tools and default shortcuts. All other app packages are blocked, including apps absent from the normal boundary list.
4. Mark the task done and meet the configured tracked-focus requirement. Both are required for completion.

Tool confirmation is fixed for the selected task. A different task resets progress and requires a fresh confirmation. The next daily cycle clears the task/confirmation and retains tool suggestions.

Configure the saved default shortcuts in **Settings → Essential apps**. TickTick, Phone, Clock, and Messages are pinned; WhatsApp, ChatGPT, Spotify, and Google Pay are optional defaults. With no saved selection, all eight default shortcuts are enabled. The saved selection applies across Frog tasks. Task tools are separate: confirm them inside the Frog card for the selected task, and changing tasks requires fresh confirmation. The displayed defaults are available before task-tool confirmation when selected. FocusLock, the keyboard, and necessary system/recovery surfaces also remain available. Permanent blocks and Nuke keep precedence; a Frog task tool cannot bypass them. Browser website boundaries still apply.

## Home gestures

Choose **Prevent launcher escape** in the Frog card and select FocusLock as Android's Home app. Home then goes directly to `FrogHomeActivity`, avoiding the previous accessibility redirect through the ordinary launcher. FocusLock remembers the previous Home component before the switch and opens that launcher after Frog completion, before the wake hour, while Frog is disabled, or when home-only mode reliably confirms you are away. If location is unavailable or uncertain, home-only mode continues enforcing Frog and FocusLock remains the Home app. Switching Android's default Home app back restores the original arrangement.

This is a Home route with accessibility enforcement. It does not prevent opening Android Overview or changing system settings. Android's multi-app lock-task kiosk mode requires managed-device provisioning; ordinary Device Admin force-lock permission is insufficient. No device-owner provisioning or single-app screen pinning is attempted.

The existing home-only preference continues to gate Frog and ordinary notification filtering. No location or router settings are changed by this feature.

## Notifications

With FocusLock Notification Access enabled, the listener dismisses new notifications from currently blocked apps and sweeps existing notifications when relevant policies change. Essentials and currently allowed task tools retain notifications. Once Frog completes, the normal boundary/strict/limit policy determines filtering again.

This is notification dismissal, not a change to another app's system notification permission. Android can play a sound or display a banner before dismissal, and some system notifications cannot be removed. The implementation does not enable global Do Not Disturb or mute essential calls/messages. Dismissed notifications are not recreated after unlock.

## Scheduling and recovery

The daily alarm is inexact and may be delayed by Android idle/battery policy. Unlock, foreground activity, and an awake-screen monitor provide additional arming paths. The screen waits for the keyguard to be unlocked and requires a healthy accessibility-service binding. Reboot, app update, clock/time-zone changes, and wake-hour edits reconcile the next alarm.

Focus tracking and completion happen inside FocusLock or through TickTick. The minimal launcher never auto-marks the task complete.

## Test isolation

Instrumentation tests inject a dedicated `PreferenceDataStoreFactory` store into `FrogRepository`. A `ContextWrapper` with a different files directory is insufficient: the top-level `preferencesDataStore` delegate caches its first store instance. Never use a wrapper alone to isolate Frog tests from the installed app's preferences.
