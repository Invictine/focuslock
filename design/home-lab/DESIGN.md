# FocusLock Android home concept lab

This handoff describes five distinct, interactive browser concepts for the Android Focus home. The lab is a visual and interaction prototype for comparing information hierarchy. It uses sample data only; it does not implement blocking, tracking, account sign-in, sync, or permission enforcement.

## Why explore beyond the current three

The current `Balance`, `Momentum`, and `Today` options all enter through `HomeScaffold` in `app/src/main/java/com/focuslock/app/ui/dashboard/home/HomeDesigns.kt`. They share the same date/account header, setup banner, hero, action row, next task, Frog card, task summary, chart, history, and balance footer. Their primary difference is the presentation of focus/leisure/ratio. The concepts below test five more distinct information architectures while keeping the same underlying facts and reachable actions.

## Five concepts

| Concept | Layout and visual direction | Strength | Tradeoff |
| --- | --- | --- | --- |
| **Halo** | Dark blue; circular focus-goal ring is the visual anchor. Place focus minutes in the center, leisure and ratio as compact satellites, then a wide Start focus action and a small task preview. | Makes daily progress and the primary action immediately legible. | A large ring can overstate goal completion when the work-history data is still loading; retain a clear loading state and numeric value. |
| **Daybook** | White, editorial serif titles, rust accents; date-led page with an agenda column first, followed by next task, work entries, and a compact focus/leisure ledger. | Reads like a calm daily plan and makes the next commitment prominent. | The timer and current balance can sit too far down; keep Start focus near the title and show the balance without requiring a long scroll. |
| **Signal** | Black surfaces with restrained lime indicators; instrument-panel hierarchy with protection status, live focus value, ratio signal, and action controls in high-contrast rows. | Gives protection state and today's measured activity strong visibility. | High contrast and status decoration can feel intense; use lime for actionable or meaningful status only, with accessible text labels. |
| **Mosaic** | Warm paper colors, bold Swiss typography, asymmetric blocks; large focus block paired with leisure/ratio, task card, and a narrow action rail. | Uses screen area efficiently and makes several useful facts scannable together. | Asymmetry can make small screens or enlarged text feel crowded; collapse to a simple vertical reading order when space is limited. |
| **Flow** | Calm blue palette; task-first route: next task and Start focus lead, then focus goal, leisure/ratio, Frog card, activity and history. | Connects planning directly to a focus session and gives task users a clear first step. | Users who open the app mainly to check usage may need to scroll; include a compact at-a-glance focus/leisure summary above the fold. |

## Comparison prototype behavior

Make all five concepts selectable in the browser, with the active concept rendered as a complete phone-sized screen rather than a static thumbnail. Keep a consistent sample-day dataset across concepts so users compare hierarchy rather than different numbers. Add a small sample-state switcher for: normal populated day, loading, empty day, missing permissions, TickTick disconnected, and task-fetch error. Use portrait mobile sizing first, then verify narrow width and enlarged text layouts. Provide a persistent concept selector or back-to-lab affordance so a reviewer can compare concepts without resetting the sample state.

Every visible prototype action should respond locally: concept selection changes the rendered concept; Start focus and Log work open representative dialogs; task cards open a task detail/connection/error state; history expands and collapses; setup opens a permission explainer; Nuke opens a confirmation/info/resume example; Settings, Account, and each navigation destination show clearly labeled placeholder views. These are demonstrations only. Never show a successful login, permission grant, block, device sync, timer credit, or task write as if a real service accepted it.

## Shared Android content and action contract

The Compose implementation should render from the existing hoisted model instead of inventing new sources:

- `FocusHomeState` in `app/src/main/java/com/focuslock/app/ui/dashboard/home/FocusHomeStyles.kt` supplies date, permission readiness and missing labels, focus minutes and goal, task completion and goal/state, usage summary/top app, work history and expansion state, live balance, Nuke state, account initial, cross-device groups/usage, leisure seconds, target focus-to-leisure ratio, and next-task title/detail.
- `FocusHomeCallbacks` in the same file supplies actions for TickTick, permissions, Settings, Account, work log, timer, history expansion, task retry, and Nuke confirm/info/resume. Designs call these callbacks; they do not own dialogs, timers, sync, or backend state.
- `FrogCard` is self-contained and collects its own task flows (`app/src/main/java/com/focuslock/app/ui/dashboard/home/FrogCard.kt`). Keep it present and actionable in every concept, using its existing behavior.
- `DashboardScreen.kt` remains responsible for collecting flows, deriving `FocusHomeState`, refreshing usage/tasks, and holding timer/dialog state. `MainActivity.kt` owns top-level navigation and callbacks into the dashboard. Preserve the existing destinations **Focus, Boundaries, Strict, Permalock, and Settings**, plus an Account entry in the home header. Keep Nuke available from the header.
- Preserve the current honest task states: Loading, NoAccount, Loaded, and Error (with retry). Distinguish unknown/loading history from loaded-but-empty history. Do not fill missing values with plausible-looking sample values in the real app.

### Ratio and permission truthfulness

The ratio needs both today's focus work and today's foreground use of apps selected in Boundaries. If leisure usage is `null` because Usage Access is unavailable or has not loaded, display the ratio as **unavailable** with a useful explanation; do not show a zero leisure value or a verdict. If usage has loaded and is genuinely zero, represent zero distinctly. When work-history data is not loaded, show a loading value rather than a completed zero. A missing-permission banner appears only after permission checks finish, and should lead to the existing permission flow.

## Compose conversion and accessibility

Treat each browser concept as a layout direction, not a requirement to reproduce web widgets in Android. Keep values and actions hoisted through `FocusHomeState` and `FocusHomeCallbacks`; do not change repository flows, enforcement services, persistence, sync, or authentication to support a visual concept. Reuse the app's Compose theme and responsive primitives, with a simple vertical fallback at narrow widths and larger font scales. All actions need meaningful labels, at least comfortable Android touch targets, visible focus/pressed states, and sensible screen-reader order. Do not encode ratio or protection status by color alone. Follow the official [Compose accessibility API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) and preserve semantic descriptions for Nuke, Settings, Account, charts, and action controls.

## Source map

- Current variant selector and shared state/callback types: `app/src/main/java/com/focuslock/app/ui/dashboard/home/FocusHomeStyles.kt`.
- Existing three layouts and shared scaffold: `app/src/main/java/com/focuslock/app/ui/dashboard/home/HomeDesigns.kt`.
- Data collection, derived state, refresh, timer, and dialogs: `app/src/main/java/com/focuslock/app/ui/dashboard/DashboardScreen.kt`.
- Main navigation and destination wiring: `app/src/main/java/com/focuslock/app/ui/MainActivity.kt`.
- Persisted home selection: `app/src/main/java/com/focuslock/app/data/repository/SettingsRepository.kt` and the home-style control in `app/src/main/java/com/focuslock/app/ui/settings/SettingsScreen.kt`.
- Shared task card: `app/src/main/java/com/focuslock/app/ui/dashboard/home/FrogCard.kt`.

## Function-level mapping

| Existing source function or type | Contract for every concept |
| --- | --- |
| `FocusHome(state, callbacks)` | Entry point for the selected home; concept selection changes only layout. |
| `FocusHomeHeader` | Keep date, Focus title, Nuke control, Settings, and Account reachable. |
| `SetupBannerItem` / `SetupBannerCard` | Render after checks complete when permissions are missing; route to permission setup. |
| `HomePrimaryActions` | Preserve Start focus and Log work, both connected to the hoisted callbacks. |
| `NextTaskCard` | Preserve Loading, NoAccount, Loaded, Error, and their connect/open/retry actions. |
| `FrogCardItem` / `FrogCard` | Keep the existing shared task behavior in every concept. |
| `TasksSummaryCard` | Show the real completed/goal count and retain Connect or Retry paths. |
| `FocusDayChart` / `DayChartItem` | Show hourly focus from focus work records; keep a truthful no-focus empty state. |
| `HistoryToggleButton` / `HistoryItems` | Preserve show-all toggle, recent records, and loaded-empty behavior. |
| `BankItem` | Keep the live scroll-credit balance visible somewhere in the screen. |
| `FocusHomeCallbacks` | Route taps upward; concepts own no timer, dialog, navigation, or sync state. |

The designs may change order, grouping, typography, color, and emphasis. They must
not drop a capability simply because its placement differs. Header account access
and Nuke remain available even when a design gives the next task or focus goal
the strongest visual weight.

## Data state examples to include in the browser lab

Use a deterministic sample fixture, then switch one dimension at a time so each
concept can be compared under the same conditions:

- **Populated:** permissions granted, focus history loaded, leisure usage loaded,
  TickTick connected, next task present, and at least one recent work record.
- **Loading:** work history or usage has not arrived; use explicit loading copy and
  avoid presenting zero as a measured result.
- **No focus today:** history is loaded and empty; show a useful first action to
  start the timer or log work.
- **No leisure measurement:** usage access is missing or usage is still loading;
  show the ratio as unavailable and explain that usage access is needed. A loaded
  zero is a separate, valid state.
- **Setup incomplete:** permission checks have completed and missing labels are
  available; show the setup banner and permission explanation path.
- **TickTick disconnected:** preserve the connect route. Do not invent a task or
  completed-task count.
- **Task error:** show the error and working Retry control; retry may return to
  the populated sample state.

Test the concepts with the same fixture at a narrow phone width and with enlarged
text. The lab can simulate those conditions, but should not suggest it has
verified TalkBack or a physical Android device.

The interactive browser lab is implemented in this folder. Validation results are
recorded in `results.json` and `offline-results.json`, with preview images in
`screenshots/`. These checks cover the browser prototypes. The Android app has
not been changed, and Compose conversion and physical-device accessibility
verification remain separate work.
