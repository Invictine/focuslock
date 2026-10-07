# Android tester distribution

FocusLock distributes the existing **debug** Android build through Firebase App Distribution. The script does not install anything on a connected phone. It builds, verifies, and uploads the APK, then Firebase emails the configured testers.

## One-time setup

1. Install JDK 17, Android SDK command-line/build tools, Node.js, and Firebase CLI 15.32.1:

   ```powershell
   npm install --global firebase-tools@15.32.1
   firebase login
   ```

2. In the Firebase console, create or select a project, add an Android app whose package is `com.focuslock.app`, and enable App Distribution. Copy its project ID and Android App ID. Alternatively, create the project with `firebase projects:create <project-id> --display-name FocusLock` and register the app with `firebase apps:create ANDROID FocusLock --package-name com.focuslock.app --project <project-id>`; then obtain the app ID with `firebase apps:list --project <project-id>`.
3. Copy `firebase-distribution.example.json` to `firebase-distribution.local.json` in the repository root. Fill in `projectId`, `appId`, and at least one of `testers` (email addresses) or `groups` (Firebase tester group aliases). Keep this local file private; it is ignored by Git.
4. Make sure the same Android debug signing key is used for every build. Firebase testers should install the App Tester app or accept Firebase's invitation, then use the release link they receive.

Firebase authentication here is handled by the local Firebase CLI account. The app does not need a Firebase runtime SDK or committed service-account credentials for distribution.

## Validate and distribute

From the repository root:

```powershell
npm run android:distribute:check
npm run android:distribute
```

The normal command runs Android unit tests and lint, assembles the debug variant, verifies its signature and package/version metadata, and uploads it to the configured testers/groups. It uses a monotonically increasing Android `versionCode`; generated counter state is stored under ignored `artifacts/` and the user-visible app version remains unchanged. To provide release notes or choose a specific higher code:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/distribute-android.ps1 -Notes "What changed`nSecond line" -VersionCode 2000000000
```

Omit `-VersionCode` to use the persisted automatic counter. A supplied code must exceed the last persisted code. `-ValidateOnly` checks the config and local tools without contacting Firebase, building, or uploading.
