# FocusLock for Chrome

Manifest V3 extension for per-site usage tracking, FocusLock blocking, and
account-scoped sync with the Android and Windows apps.

The popup mirrors the main FocusLock dashboard: today's cross-device screen
time, the current website, and explicit Chrome/Android connection health.

## Cold Turkey parity

| Cold Turkey | FocusLock |
|---|---|
| Block lists (sites + apps) | ✅ Multiple named lists (sites; apps need a native helper — out of scope for pure Chrome) |
| Exceptions / allow-list | ✅ Per-list exceptions + global Nuclear allow-list |
| Blacklist vs allow-only | ✅ `blacklist` and `whitelist` (Forest) modes per list |
| Schedules / timers | ✅ Recurring weekly + one-shot timers |
| Pomodoro | ✅ Focus/break/rounds timer |
| Frozen Turkey ❄ | ✅ Locked list + locked schedule, no edit/disable/delete until expiry |
| Password lock | ✅ SHA-256 + salt, required to disable protection / stop Nuclear early |
| Daily time limits | ✅ Per-list minutes/day budget |
| Nuclear option ☢ | ✅ Block everything except allow-list |
| Keyword / wildcard blocking | ✅ `youtube.com`, `*.youtube.com`, `reddit.com/r/*`, `*keyword*`, `/regex/` |
| Statistics | ✅ Per-domain seconds/day (60-day retention), blocked-attempt log, lifetime counter |
| Motivational block page | ✅ Quote + time context + delayed emergency 5-min break |
| Breaks | ✅ Emergency snooze (type phrase + 60s wait; frozen blocks exempt) |

## Build and install (unpacked)

1. Configure `desktop/.env` or copy `.env.example` to `.env`. The Clerk key and
   Convex URL are public browser configuration; never add a secret key.
2. Run `npm install` and `npm run build` inside `extension/`.
3. Open `chrome://extensions`, enable Developer mode, choose **Load unpacked**,
   and select `extension/`.
4. Pin FocusLock, open its popup, and choose **Sign in to sync**. Authentication
   opens in the persistent extension dashboard so Chrome cannot close the form
   mid-flow. Use the account email and Clerk verification code for the same
   account used by Android and Windows; social OAuth cannot redirect directly
   to a Chrome extension. The extension uploads absolute per-domain counters about once per
   minute, so retries cannot double-count time.
5. Use **Open boundaries & stats** for block lists, schedules, Frozen lock, and
   the Nuclear option.

The manifest contains the stable public key for development extension ID
`fkkpmoiageeieaoplphafmhjkkdadcnf`. The matching private `.pem` is local and
gitignored. This origin must remain in Clerk's allowed origins, Native API must
be enabled, and Clerk bot protection must remain disabled for extension auth.
The embedded dashboard hides social OAuth and email-link methods; email
verification code and password are the supported extension-native paths.

## Structure

```
extension/
  manifest.json
  src/matcher.js        shared URL-pattern engine
  src/store.js          storage schema + password hashing
  background/service-worker.js   tracking + blocking verdicts + alarms
  content/content-guard.js       SPA-navigation fallback
  blocked/              motivational block page + emergency break
  popup/                dashboard-style usage + connection popup
  options/              full dashboard plus persistent Clerk account sign-in
  src/cloud-sync.js     Clerk session + Convex heartbeat/usage bridge
  build.mjs             bundles Clerk SDK code into MV3-safe local scripts
  icons/lock.svg
```

## How blocking works

`verdictFor(url, state)` in the service worker: internal URLs never blocked →
Nuclear (if live) → each active list (frozen-lock > schedule window > always-on) →
whitelist = block unless allowed; blacklist = block on pattern hit unless exception →
daily-limit exhaustion also blocks. Hits redirect the tab to `blocked.html` and
increment the blocked counter. A content-script guard covers SPA navigations the
`webNavigation` listener might miss.

## How tracking works

`tabs.onActivated` + `tabs.onUpdated` + `windows.onFocusChanged` maintain the
active URL; `chrome.idle` gates accumulation; a 1-min alarm flushes slices into
`stats[YYYY-MM-DD][domain]`. Counts only when Chrome is focused and you're not idle.

## Limits vs desktop Cold Turkey

- No app-blocking, no hosts-file lock, no uninstall prevention — Chrome can't do those without a native companion. Mitigation: password lock + Frozen Turkey + Nuclear make casual bypass painful.
- Determined users can still disable the extension via `chrome://extensions` (same limitation as every Chrome blocker). Strict mode + password raise the bar; OS-level enforcement needs the `desktop/` companion.

## Dev and verification

- `npm run check` syntax-checks the service worker, popup, dashboard auth, and cloud bridge.
- `npm run build` creates gitignored `dist/popup.js`, `dist/options-auth.js`, and `dist/cloud-sync.js`.
- Load the unpacked extension, browse a normal HTTP(S) site for at least one
  minute, then reopen the popup. Verify the current-site time increases.
- Sign in, press **Sync now**, and verify Chrome and Android status rows plus the
  combined daily total. The same browser device must appear in Windows Account.
- Block `example.com`, visit it, expect the FocusLock block page, then confirm
  the attempt in the options dashboard.
