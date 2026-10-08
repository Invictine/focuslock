# Android product guide

The guide opens after sign-in or choosing offline mode. It explains navigation,
accounts and sync, permissions and recovery, apps/websites/groups, home-only
blocking, focus credits, limits and schedules, Frog and launcher protection,
Strict Mode, permanent blocks, Nuke, TickTick, personalization, and backups.
The final chapter gives a practical setup checklist.

- **Continue / Back** move between chapters; **Topics** jumps directly to one.
- **Save for later**, Android Back, or a setup shortcut saves the current chapter
  without marking the guide complete. It stops opening automatically after a pause.
- **Settings → Explore FocusLock → Resume guide** returns to the saved chapter.
- **Finish guide** records completion; **Replay guide** then restarts at chapter one.
- Setup shortcuts open existing tabs. Reading or finishing the guide does not grant
  permissions, enable blocking, start Frog/Nuke, or create a Strict commitment.

Progress survives activity recreation and app restarts in device-local preferences.
The existing permission wizard is composed after the product guide closes, avoiding
two onboarding dialogs at once. The reading area scrolls independently of navigation
controls and supports theme changes, large text, heading semantics, and system Back.

`ProductOnboardingUiTest`, `ProductOnboardingStoreTest`, and
`ProductOnboardingIntegrationTest` cover navigation, setup callbacks, saved progress,
pause/completion distinctions, Settings resume/replay, and light/dark large-text UI.
