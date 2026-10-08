# Android Google sign-in

Both onboarding and the signed-out Account screen expose a direct **Continue
with Google** action. Android Credential Manager's explicit Google button flow
(`GetSignInWithGoogleOption`) opens the system account picker. The app then
passes the ID token to Clerk for validation and account/session creation.
There is no Clerk sign-in modal or browser fallback. Email, verification codes,
required profile fields, passwords, and supported second factors use FocusLock's
own full-screen Compose UI.

The Google Web client ID is public configuration, supplied through
`google.webClientId` in ignored `local.properties` or `GOOGLE_WEB_CLIENT_ID` in
the build environment. It must match the Web client configured in Clerk.
The existing distribution worker copies `local.properties` into each build.

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
- Verify the signed-in account and a real sync round trip. Google cancellation
  should stay on the originating screen without an error. Back from Account's
  email route should return to Account. Continue offline should preserve the
  offline workflow at startup.

`AccountSignInUiTest` verifies the direct Google callback, inline errors, and
the full-screen screen fixture. `MainAccountSignInRouteTest` launches the actual
MainActivity and verifies Account's email route and system back navigation.
`GoogleTokenExchangeTest` verifies that existing
accounts retain their required verification and only the explicit unknown
external account response can create a new account. These tests do not
establish a completed Google sign-in on a real device. An unavailable Google
account/provider yields an inline hint with an email alternative.

On October 8, 2026, the debug build, lint, 216 unit tests, and seven targeted
emulator UI tests passed. Tapping the actual Account Google button opened
Google Play services directly. The emulator had no Google account, so completed
Google authentication and a signed-in sync round trip remain device checks.
The custom flow gates sessions that require post-sign-in organization selection
or security enrollment; those tasks are not enabled in the current instance.

The Google mark is Google's unmodified [official PNG asset](https://developers.google.com/static/identity/images/g-logo.png).
The native button follows [Google's Android button-flow guidance](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation).
