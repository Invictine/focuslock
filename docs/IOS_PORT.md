# FocusLock iOS port: implementation and handoff

## Current status

The iOS client source, backend function references, local Screen Time module, geofence task, and Activity Monitor/Report extension sources are present. On Windows, `npm test` passes (44 tests), `npm run typecheck` passes, and `npm run export:ios` produces the iOS JavaScript bundle. The export warned that `ios.appleTeamId` is not set. These checks validate JavaScript, configuration, and bundling; they do not compile the Swift targets or verify any behavior on an iPhone. Native compilation/CI and device acceptance are still pending. iOS heartbeat wiring is present in source, but the backend deployment and a verified device heartbeat are still pending; do not count the phone as an account device until those are verified.

The person preparing this port does not have a paid Apple Developer Program account. The full Screen Time build requires Family Controls entitlement approval and paid-team signing before its controls can be installed and tested. Apple documents the capability setup [here](https://developer.apple.com/documentation/xcode/configuring-family-controls). Distribution approval is required separately for the app and each app extension that uses the capability. The local app config also needs the correct `ios.appleTeamId` when a team is available. No Mac availability or iPhone installation has been confirmed.

There is a **basic development mode** for non-Screen-Time app work. Set `EXPO_PUBLIC_SCREEN_TIME_ENABLED=0` (or select EAS profile `basic-development`) to omit the Activity Monitor/Report targets and remove Family Controls, app-group, and Sign in with Apple entitlements. The basic mode reports native Screen Time as unavailable and cannot test app selection, shielding, home-only enforcement, or the private Screen Time report. On a Mac, a local Xcode build of this reduced-capability mode can use Personal Team signing; this does not make an EAS-signed build available on the free tier. EAS signing still requires paid Apple developer credentials. JavaScript tests and unsigned CI do not require an Apple developer account.

## What the source implements

| Area | Current iOS implementation | Boundary to keep in mind |
| --- | --- | --- |
| Focus sessions | Start, pause, resume, capped timer, and local persistence. Completed timer records use actual elapsed time and never mark tasks complete. | Work is sent through an account-bound durable queue when authenticated and online. |
| Manual work | Explicit manual work entries, including explicitly entered completed-task counts. | On-device local-mode records stay local and do not migrate into a later signed-in account. |
| Account state | Clerk native auth, Convex dashboard, account work history/credit, account settings, and account tools. Edits use backend authorization/strict guards and the available revision/partial-write checks. | Real Clerk OAuth/account acceptance has not been completed. Native/API source presence is not live backend deployment. |
| Device rules | Native app selections with schedule, daily-limit, permanent, timed, and Frog-task rules; device-local strict lock and emergency suspend. | Rules depend on approved Family Controls access, installed native extensions, and iOS delivery. They are not Android uninstall, Settings, Recents, or other system protections. |
| Home boundary | Explicit foreground/background location consent; local, account-namespaced home coordinates; background enter/exit events update native home-only mode. Unknown presence preserves shields. | Geofencing is device-local and best effort. The separate saved account home is a cloud setting; it is not the geofence store. No actual device boundary test has run. |
| Screen Time report | Apple Device Activity report view for today's device usage. | Raw report data stays in Apple's on-device report flow and is not uploaded or translated into FocusLock credits. |
| TickTick | UI and Convex action source for authorization, task list/completion, and focus import. OAuth callback is pasted as a complete loopback redirect containing a short-lived signed state/code. | New `ticktickIos` actions are not deployed yet. Configure `TICKTICK_CLIENT_ID` and `TICKTICK_CLIENT_SECRET` on the Convex server only; never place them in the app or `.env`. Real OAuth is untested. |
| Account Strict Mode / Nuke | Account settings and recovery UI are present; the native bridge can apply Nuke shielding to the foreground app. | Shielding is limited to the union of manually selected and rule-selected apps, categories, and web domains. It does not expand to every app or promise background enforcement. |

The port is not full feature parity. Automatic account-credit-to-iOS-leisure debiting remains separate and is not implemented here. Account policy is refreshed through foreground/account UI queries; there is no always-on background account-policy enforcement sync. The exact Android Frog first-interaction grace cannot observe global app opens on iOS. A manual task rule is an explicit device task and does not reproduce that global-open behavior. Android uninstall/device-settings/Recents protections have no iOS equivalent in this port.

Account-owned queues are separated by owner ID. Signing out preserves device-native policy and local work; it does not silently attach local-mode records to an account. Emergency suspend temporarily stops device rules while preserving their configuration. Home-boundary consent and local coordinates are separate from the cloud saved-home preference.

## Toolchain and configuration

- Use Node 22.x (prefer the latest Node 22 patch) for Expo SDK 58 / React Native 0.88.
- The app and both Apple extension targets declare iOS 17.4 as their deployment target. Use a macOS/Xcode version compatible with React Native 0.88; Windows cannot compile these Swift targets.
- The native requirements are defined by `app.config.ts`, `plugins/with-screen-time.cjs`, `modules/focuslock-screen-time/`, and `targets/`. The module uses the Family Controls entitlement and app group; it includes a Device Activity monitor target and report target.
- Clerk uses `@clerk/expo` 4.x and `@clerk/expo-native-components` 0.2.x. The native API path is implemented; real OAuth has not been verified.
- `mobile/.env.example` contains public client configuration placeholders. Put the real `EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY` and `EXPO_PUBLIC_CONVEX_URL` in ignored `mobile/.env`. Never put server credentials or access tokens in the mobile app.
- Set `EXPO_PUBLIC_SCREEN_TIME_ENABLED=0` for the reduced-capability local development build. The default (`1`) includes Apple Screen Time targets and entitlements and needs the approved full native signing setup.
- TickTick actions require server-only Convex environment variables and deployment of the new backend functions. OAuth uses the existing loopback redirect flow: copy/paste the complete callback within the signed state's 10-minute validity window.

## Commands

From `mobile/`:

```sh
npm ci
npm run typecheck
npm test
npm run export:ios
npm start
```

The export creates `mobile/dist/`; it is generated output, not an iOS app. For a native build on a Mac with Xcode:

```sh
npx expo run:ios
```

The default build includes Screen Time capabilities. To build the reduced-capability app locally with Personal Team signing, use `EXPO_PUBLIC_SCREEN_TIME_ENABLED=0 npx expo run:ios` in a Mac shell. The EAS `basic-development` profile also sets the flag, but EAS signing is not a free substitute for local Personal Team signing.

For an EAS development build, configure the Apple team and approved entitlements first, then run:

```sh
eas build --profile development --platform ios
```

Do not use Expo Go to claim native authentication, Family Controls, background geofencing, or Screen Time acceptance. A native build still needs installation and the checks below.

## Native acceptance checklist

Run these checks on a signed iPhone using the approved development build. Record iOS version, build, entitlement/signing state, permissions, and observed result; distinguish failures caused by signing, permission, native code, and backend deployment.

1. Sign in with Clerk, confirm the account identity, background/foreground, sign out, and sign into a second account. Confirm work records and credit never cross accounts and local-mode work is not uploaded.
2. Grant Family Controls access, create a rule, open Apple's app picker, cancel it, then select apps and verify only the approved selection is saved. Relaunch and confirm selections and rules persist.
3. Test manual shielding and each supported rule type. Verify schedule weekdays, overnight boundaries, minimum monitoring interval, daily limit reset/threshold, timed expiry/restart, and task unlock time.
4. For Frog/task rules, test focus below, at, and above 30 minutes; verify exemption behavior separately from manual task completion. Confirm no timer automatically completes a task.
5. Explicitly check a task, relaunch, and confirm the native progress is retained. Test an empty task selection and denied authorization.
6. Suspend all rules and resume them. Confirm suspension is immediately available during other work and preserves rules, app selections, and permanent-rule configuration.
7. Revoke Screen Time permission in iOS Settings, force-quit/relaunch, and confirm the UI reports unavailability without claiming enforcement. Repeat after app restart and device restart.
8. Enable a local home boundary only after both location prompts. Test inside, outside, unknown location, permission revocation, app force-quit, relaunch, and sign-out. Confirm GPS is not sent to Convex and permanent blocks remain outside the boundary.
9. View the Apple Screen Time report and confirm raw usage does not change FocusLock account credit.
10. Test account work syncing offline and after reconnect, including a duplicate retry and an older-day record. Confirm the account queue acknowledges only accepted record IDs.
11. If TickTick server actions are deployed, test OAuth callback expiry/replay, account switching, reconnect, task completion, and duplicate focus import. Until server credentials/actions are deployed and OAuth is tested, keep this feature marked unverified.
12. Exercise server strict guards/revision conflicts for account edits and verify a rejected mutation leaves the displayed and stored value intact.

Passing JS tests or an Expo bundle is not a substitute for these device checks. Do not report iPhone enforcement, signed distribution, OAuth, or account-sync acceptance until the corresponding live checks actually pass.

## Dependency audit note

The current mobile dependency audit reports 29 findings (10 moderate, 19 high), primarily in transitive Expo/React Native tooling. No safe fix was identified without forcing dependency changes. Review the audit again before release; do not run `npm audit fix --force` as a shortcut.
