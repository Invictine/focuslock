# FocusLock — Cold Turkey for Chrome 🔒

MV3 Chrome extension: per-site **usage tracking** + Cold Turkey-style **blocking**.

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

## Install (unpacked)

1. `chrome://extensions` → Developer mode ON → **Load unpacked** → select `extension/`.
2. Pin FocusLock, open **Dashboard** (options page) to configure lists.
3. Popup = quick block current site, start 15/25/50/90-min lock, ❄ Frozen, ☢ Nuclear.

## Structure

```
extension/
  manifest.json
  src/matcher.js        shared URL-pattern engine
  src/store.js          storage schema + password hashing
  background/service-worker.js   tracking + blocking verdicts + alarms
  content/content-guard.js       SPA-navigation fallback
  blocked/              motivational block page + emergency break
  popup/                quick actions + timers
  options/              full dashboard (lists, schedules, stats, settings)
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

## Dev

No build step — vanilla JS. Syntax check: `node --check` each JS file. Manual test: load unpacked, block `example.com`, visit it, expect redirect to block page; check Dashboard → Statistics for seconds accruing.
