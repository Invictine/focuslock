# Android Google sign-in

Both onboarding and the signed-out Account screen use Clerk's in-app `AuthView`
with `preferGoogleOneTap = true`. Google sign-in uses Android Credential Manager
when Clerk's environment provides `display_config.google_one_tap_client_id`.
Clerk still handles email sign-in, sign-up, MFA, and session continuation.

## Google and Clerk configuration

The FocusLock development Clerk instance uses custom Google OAuth credentials
from the existing **FocusLock Builds** Google Cloud project
(`invictine-focuslock-builds`). The Web client has Clerk's authorized redirect
URI. Its secret belongs in Clerk and private local storage, never in source or
the APK.

The Android client registers `com.focuslock.app` and the local debug signing
certificate used by both debug and performance builds. Builds signed with a
different certificate need another Android OAuth client in the same project,
including builds made on another machine or through Play App Signing.

Google's consent configuration is currently External / Testing. Add intended
test accounts in Google Auth Platform's Audience page before testing with them.
Production publishing and a production signing certificate require separate
configuration.

## Verification

- Confirm Google is enabled and Clerk's public environment contains the Web
  client ID at `display_config.google_one_tap_client_id`.
- On an Android device with a Google account and Google Play services, open
  sign-in from onboarding and Account, then select Google. Verify the native
  account picker appears and completes a Clerk session.
- Verify the signed-in account and a real sync round trip. The Account dialog
  should dismiss after sign-in; Continue offline and Back should close it.

`AccountSignInUiTest` verifies the Account action opens the in-app dialog and
Continue offline returns to Account. It does not establish a completed Google
sign-in. Clerk can fall back to browser OAuth when native Google credentials
are unavailable or the device has no eligible Google account.
