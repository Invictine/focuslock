import { useCallback, useEffect, useMemo, useState } from "react";
import { DESKTOP_FROG_ENABLED } from "./features";

// ---------------------------------------------------------------------------
// "Eat the frog" — desktop port of the Android reference:
//   app/src/main/java/com/focuslock/app/data/model/FrogTask.kt
//   app/src/main/java/com/focuslock/app/data/repository/FrogRepository.kt
//
// State is DEVICE-LOCAL: every value lives in localStorage (Android uses its own
// `focuslock_frog` DataStore), so the main window and the `#/blocked` blocker
// webview share one frog through the same origin. Nothing here syncs to Convex.
//
// Ported verbatim from Kotlin: frogCycleDate, computeFrogLocked, canArmNow,
// shouldRolloverFrogCycle, FrogPhase.from, the lazy daily rollover rule and the
// defaults (enabled = true, requiredMinutes = 30, wakeHour = 5).
// ---------------------------------------------------------------------------

/** One pickable frog: the day's single "most important task" and its allowlist. */
export type FrogTask = {
  title: string;
  projectName?: string;
  neededAppIds: string[];
  neededDomains: string[];
};

/** Lifecycle of the day's frog (Android's FrogPhase enum). */
export type FrogPhase = "not_armed" | "pick_frog" | "working" | "complete";

/** Snapshot for one cycle day, mirroring Android's FrogState data class. */
export type FrogState = {
  cycleDate: string;
  enabled: boolean;
  armed: boolean;
  phase: FrogPhase;
  frog: FrogTask | null;
  tickedOff: boolean;
  trackedSeconds: number;
  requiredSeconds: number;
  locked: boolean;
  // Desktop-only display fields (Android reads them from separate flows).
  requiredMinutes: number;
  wakeHour: number;
};

export type FrogActions = {
  setEnabled: (enabled: boolean) => void;
  setRequiredMinutes: (minutes: number) => void;
  setWakeHour: (hour: number) => void;
  armIfDue: () => boolean;
  selectFrog: (task: FrogTask) => void;
  clearFrog: () => void;
  tickOffFrog: (tickedOff?: boolean) => void;
  addTrackedSeconds: (seconds: number) => void;
  refresh: () => void;
};

// localStorage keys, namespaced like the other desktop device-local values
// ("focuslock.boundariesLock", "focuslock.prefs", ...). Android's equivalents
// live in the `focuslock_frog` DataStore.
export const FROG_STORAGE_PREFIX = "focuslock_desktop_frog_";
export const FROG_KEYS = {
  enabled: `${FROG_STORAGE_PREFIX}enabled`,
  requiredMinutes: `${FROG_STORAGE_PREFIX}required_minutes`,
  wakeHour: `${FROG_STORAGE_PREFIX}wake_hour`,
  cycleDate: `${FROG_STORAGE_PREFIX}cycle_date`,
  armed: `${FROG_STORAGE_PREFIX}armed`,
  selected: `${FROG_STORAGE_PREFIX}selected`,
  tickedOff: `${FROG_STORAGE_PREFIX}ticked_off`,
  trackedSeconds: `${FROG_STORAGE_PREFIX}tracked_seconds`,
} as const;

// Android FrogRepository defaults / bounds.
export const FROG_DEFAULT_ENABLED = true;
export const FROG_DEFAULT_REQUIRED_MINUTES = 30;
export const FROG_DEFAULT_WAKE_HOUR = 5;
export const FROG_MIN_REQUIRED_MINUTES = 1;
export const FROG_MAX_REQUIRED_MINUTES = 480;
export const FROG_MIN_WAKE_HOUR = 0;
export const FROG_MAX_WAKE_HOUR = 23;
// Desktop Settings exposes the narrower 5–180 window; the store still clamps to
// the Android 1–480 range so a value written elsewhere survives.
export const FROG_UI_MIN_REQUIRED_MINUTES = 5;
export const FROG_UI_MAX_REQUIRED_MINUTES = 180;

/** Same-tab change signal; cross-webview changes arrive via the `storage` event. */
const FROG_CHANGED_EVENT = "focuslock:frog-changed";
/** How often the hook re-runs rollover + armIfDue, mirroring Android's lazy flows. */
const FROG_TICK_MS = 30_000;
const INT_MAX = 2_147_483_647;

type FrogPrefs = {
  enabled: boolean;
  requiredMinutes: number;
  wakeHour: number;
  cycleDate: string;
  armed: boolean;
  selectedJson: string;
  tickedOff: boolean;
  trackedSeconds: number;
};

function defaultPrefs(): FrogPrefs {
  return {
    enabled: FROG_DEFAULT_ENABLED,
    requiredMinutes: FROG_DEFAULT_REQUIRED_MINUTES,
    wakeHour: FROG_DEFAULT_WAKE_HOUR,
    cycleDate: "",
    armed: false,
    selectedJson: "",
    tickedOff: false,
    trackedSeconds: 0,
  };
}

function boolValue(raw: string | null, fallback: boolean): boolean {
  if (raw === null) return fallback;
  return raw === "1" || raw === "true";
}

function intValue(raw: string | null, fallback: number): number {
  if (raw === null || raw === "") return fallback;
  const num = Number(raw);
  return Number.isFinite(num) ? Math.trunc(num) : fallback;
}

function clampRequiredMinutes(minutes: number): number {
  const value = Math.trunc(Number(minutes));
  if (!Number.isFinite(value)) return FROG_DEFAULT_REQUIRED_MINUTES;
  return Math.min(FROG_MAX_REQUIRED_MINUTES, Math.max(FROG_MIN_REQUIRED_MINUTES, value));
}

function clampWakeHour(hour: number): number {
  const value = Math.trunc(Number(hour));
  if (!Number.isFinite(value)) return FROG_DEFAULT_WAKE_HOUR;
  return Math.min(FROG_MAX_WAKE_HOUR, Math.max(FROG_MIN_WAKE_HOUR, value));
}

function localDateKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(
    date.getDate(),
  ).padStart(2, "0")}`;
}

// Corruption-hardened access, mirroring the Kotlin store: a storage failure
// falls back to defaults instead of throwing into a render.
function readPrefs(): FrogPrefs {
  try {
    const store = window.localStorage;
    return {
      enabled: boolValue(store.getItem(FROG_KEYS.enabled), FROG_DEFAULT_ENABLED),
      requiredMinutes: intValue(
        store.getItem(FROG_KEYS.requiredMinutes),
        FROG_DEFAULT_REQUIRED_MINUTES,
      ),
      wakeHour: intValue(store.getItem(FROG_KEYS.wakeHour), FROG_DEFAULT_WAKE_HOUR),
      cycleDate: store.getItem(FROG_KEYS.cycleDate) || "",
      armed: boolValue(store.getItem(FROG_KEYS.armed), false),
      selectedJson: store.getItem(FROG_KEYS.selected) || "",
      tickedOff: boolValue(store.getItem(FROG_KEYS.tickedOff), false),
      trackedSeconds: Math.max(0, intValue(store.getItem(FROG_KEYS.trackedSeconds), 0)),
    };
  } catch {
    return defaultPrefs();
  }
}

function writePrefs(prefs: FrogPrefs): void {
  try {
    const store = window.localStorage;
    store.setItem(FROG_KEYS.enabled, prefs.enabled ? "1" : "0");
    store.setItem(FROG_KEYS.requiredMinutes, String(clampRequiredMinutes(prefs.requiredMinutes)));
    store.setItem(FROG_KEYS.wakeHour, String(clampWakeHour(prefs.wakeHour)));
    store.setItem(FROG_KEYS.cycleDate, prefs.cycleDate);
    store.setItem(FROG_KEYS.armed, prefs.armed ? "1" : "0");
    store.setItem(FROG_KEYS.selected, prefs.selectedJson);
    store.setItem(FROG_KEYS.tickedOff, prefs.tickedOff ? "1" : "0");
    store.setItem(FROG_KEYS.trackedSeconds, String(Math.max(0, prefs.trackedSeconds)));
  } catch {
    // Storage blocked: keep the change in memory only (same behaviour as the
    // other desktop local flags).
  }
  notifyFrogChanged();
}

function notifyFrogChanged(): void {
  try {
    window.dispatchEvent(new Event(FROG_CHANGED_EVENT));
  } catch {
    /* nothing to do */
  }
}

function normalizeAppKey(value: string): string {
  return String(value || "").trim().toLowerCase();
}

/** Same canonical site key the Boundaries page and Rust use (drop leading www.). */
function normalizeDomainKey(value: string): string {
  return String(value || "")
    .trim()
    .replace(/^www\./i, "")
    .toLowerCase();
}

function uniqueKeys(values: string[] | undefined, normalize: (value: string) => string): string[] {
  const seen = new Set<string>();
  for (const raw of values || []) {
    const key = normalize(raw);
    if (key) seen.add(key);
  }
  return [...seen].sort();
}

/**
 * Trim a pasted URL/domain down to a bare hostname, or null when invalid.
 * Mirrors App.tsx `normalizeDomainInput` (duplicated here because App imports
 * this module — importing App back would create a cycle).
 */
export function normalizeFrogDomain(raw: string): string | null {
  let value = String(raw || "").trim().toLowerCase();
  if (!value) return null;
  if (value.includes("://")) value = value.split("://")[1] || value;
  value = value.split("/")[0].split("?")[0].split("#")[0];
  if (value.includes("@")) value = value.split("@").pop() || value;
  value = value.split(":")[0];
  value = normalizeDomainKey(value).replace(/^m\./, "");
  value = value.replace(/^[.\-_ ]+|[.\-_ ]+$/g, "");
  return /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(value)
    ? value
    : null;
}

/** Normalized copy of a frog: trimmed title, deduped/sorted canonical keys. */
export function sanitizeFrogTask(task: FrogTask): FrogTask {
  const title = String(task.title || "").trim();
  const projectName = String(task.projectName || "").trim();
  return {
    title,
    ...(projectName ? { projectName } : {}),
    neededAppIds: uniqueKeys(task.neededAppIds, normalizeAppKey),
    neededDomains: uniqueKeys(task.neededDomains, normalizeDomainKey),
  };
}

/**
 * Stable identity for "same frog?". Android compares stable TickTick task ids;
 * the desktop picker has no ids, so title + project is the closest proxy. The
 * allowlist is deliberately NOT part of the key: editing the allowlist of the
 * same task ("Change frog → add the one site I'm missing → Save") must keep
 * its ticked-off state and tracked seconds instead of restarting the lock.
 * Two genuinely different tasks with identical title/project therefore count
 * as the same frog — an accepted limitation of the id-less picker.
 */
export function frogTaskKey(task: FrogTask): string {
  return JSON.stringify([task.title.trim(), (task.projectName || "").trim()]);
}

function decodeFrog(raw: string): FrogTask | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as FrogTask;
    if (!parsed || typeof parsed.title !== "string" || !parsed.title.trim()) return null;
    return sanitizeFrogTask(parsed);
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Pure helpers — byte-for-byte ports of the FrogRepository Kotlin functions.
// ---------------------------------------------------------------------------

/**
 * Cycle date of the day [nowMillis] belongs to: before [wakeHour] local time the
 * cycle is still the previous local date, so an early-morning arm attempt belongs
 * to yesterday's (already over) cycle and cannot re-arm. [wakeHour] clamps 0..23.
 */
export function frogCycleDate(nowMillis: number, wakeHour: number): string {
  const local = new Date(nowMillis);
  const stamp = new Date(local.getFullYear(), local.getMonth(), local.getDate());
  if (local.getHours() < clampWakeHour(wakeHour)) stamp.setDate(stamp.getDate() - 1);
  return localDateKey(stamp);
}

/**
 * Frog lock is active while the feature is enabled, today's frog was armed and
 * the frog is not complete — complete means ticked off AND at least
 * [requiredSeconds] tracked.
 */
export function computeFrogLocked(
  enabled: boolean,
  armed: boolean,
  tickedOff: boolean,
  trackedSeconds: number,
  requiredSeconds: number,
): boolean {
  return enabled && armed && !(tickedOff && trackedSeconds >= requiredSeconds);
}

/**
 * Whether the frog may be armed now: enabled, not already armed, at/after the
 * wake hour, and [cycleDate] already rolled to today's local date (a stale
 * stored date means the lazy rollover has not run, so arming is refused).
 */
export function canArmNow(
  nowMillis: number,
  wakeHour: number,
  cycleDate: string,
  enabled: boolean,
  armed: boolean,
): boolean {
  if (!enabled || armed) return false;
  const local = new Date(nowMillis);
  if (local.getHours() < clampWakeHour(wakeHour)) return false;
  return cycleDate === localDateKey(local);
}

/**
 * Whether the stored cycle date must be rolled over for [computedCycleDate].
 * "yyyy-MM-dd" sorts chronologically, so ONLY a strictly older stored date
 * rolls: an equal date is current, and a NEWER stored date (clock moved back,
 * wake hour moved forward) is preserved so a completed day is never wiped.
 */
export function shouldRolloverFrogCycle(
  storedCycleDate: string | null | undefined,
  computedCycleDate: string,
): boolean {
  return (storedCycleDate || "") < computedCycleDate;
}

/** FrogPhase.from — pure derivation of the day's phase. */
export function frogPhaseFrom(
  armed: boolean,
  selected: boolean,
  tickedOff: boolean,
  trackedSeconds: number,
  requiredSeconds: number,
): FrogPhase {
  if (!armed) return "not_armed";
  if (!selected) return "pick_frog";
  if (tickedOff && trackedSeconds >= requiredSeconds) return "complete";
  return "working";
}

/** mm:ss clock used by the card and the blocker hard-lock screen. */
export function formatFrogClock(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds || 0));
  return `${String(Math.floor(safe / 60)).padStart(2, "0")}:${String(safe % 60).padStart(2, "0")}`;
}

/** Current frog snapshot for the cycle day [nowMillis] falls in. Pure read. */
export function readFrogState(nowMillis: number = Date.now()): FrogState {
  const prefs = readPrefs();
  const requiredMinutes = clampRequiredMinutes(prefs.requiredMinutes);
  const requiredSeconds = requiredMinutes * 60;
  const today = frogCycleDate(nowMillis, prefs.wakeHour);
  if (!DESKTOP_FROG_ENABLED) {
    return {
      cycleDate: today,
      enabled: false,
      armed: false,
      phase: "not_armed",
      frog: null,
      tickedOff: false,
      trackedSeconds: 0,
      requiredSeconds,
      locked: false,
      requiredMinutes,
      wakeHour: clampWakeHour(prefs.wakeHour),
    };
  }
  // Stale OR newer cycle date (clock moved back / wake hour moved forward):
  // emit the reset view, exactly like FrogRepository.currentState. The reset is
  // only persisted when the stored date is strictly older (rolloverIfNeeded).
  if (prefs.cycleDate !== today) {
    return {
      cycleDate: today,
      enabled: prefs.enabled,
      armed: false,
      phase: frogPhaseFrom(false, false, false, 0, requiredSeconds),
      frog: null,
      tickedOff: false,
      trackedSeconds: 0,
      requiredSeconds,
      locked: false,
      requiredMinutes,
      wakeHour: clampWakeHour(prefs.wakeHour),
    };
  }
  const frog = decodeFrog(prefs.selectedJson);
  const tickedOff = prefs.tickedOff;
  const trackedSeconds = Math.max(0, prefs.trackedSeconds);
  return {
    cycleDate: today,
    enabled: prefs.enabled,
    armed: prefs.armed,
    phase: frogPhaseFrom(prefs.armed, frog !== null, tickedOff, trackedSeconds, requiredSeconds),
    frog,
    tickedOff,
    trackedSeconds,
    requiredSeconds,
    locked: computeFrogLocked(prefs.enabled, prefs.armed, tickedOff, trackedSeconds, requiredSeconds),
    requiredMinutes,
    wakeHour: clampWakeHour(prefs.wakeHour),
  };
}

// ---------------------------------------------------------------------------
// Mutators (localStorage is synchronous; the window `storage` event keeps the
// blocker webview and any other same-origin webview in sync).
// ---------------------------------------------------------------------------

/**
 * Lazy daily rollover (mirrors CreditBankRepository.checkAndResetDailyStats):
 * only a strictly older stored date rolls, stamping the new date and resetting
 * the day-scoped state (armed, tick, tracked seconds, selected frog). An equal
 * or newer stored date changes nothing.
 */
export function rolloverIfNeeded(nowMillis: number = Date.now()): boolean {
  if (!DESKTOP_FROG_ENABLED) return false;
  const prefs = readPrefs();
  const today = frogCycleDate(nowMillis, prefs.wakeHour);
  if (!shouldRolloverFrogCycle(prefs.cycleDate, today)) return false;
  writePrefs({
    ...prefs,
    cycleDate: today,
    armed: false,
    tickedOff: false,
    trackedSeconds: 0,
    selectedJson: "",
  });
  return true;
}

export function setEnabled(enabled: boolean): void {
  if (!DESKTOP_FROG_ENABLED) return;
  rolloverIfNeeded();
  writePrefs({ ...readPrefs(), enabled: Boolean(enabled) });
}

/** Persists required focus minutes, clamped to the Android 1..480 range. */
export function setRequiredMinutes(minutes: number): void {
  if (!DESKTOP_FROG_ENABLED) return;
  rolloverIfNeeded();
  writePrefs({ ...readPrefs(), requiredMinutes: clampRequiredMinutes(minutes) });
}

/** Persists the wake hour, clamped to 0..23. */
export function setWakeHour(hour: number): void {
  if (!DESKTOP_FROG_ENABLED) return;
  rolloverIfNeeded();
  writePrefs({ ...readPrefs(), wakeHour: clampWakeHour(hour) });
}

/**
 * Arms today's frog when due (canArmNow): enabled, not armed, at/after the wake
 * hour, cycle date rolled to today. Idempotent. Returns true only when this call
 * actually flipped armed false -> true.
 */
export function armIfDue(nowMillis: number = Date.now()): boolean {
  if (!DESKTOP_FROG_ENABLED) return false;
  rolloverIfNeeded(nowMillis);
  const prefs = readPrefs();
  if (
    !canArmNow(nowMillis, prefs.wakeHour, prefs.cycleDate, prefs.enabled, prefs.armed)
  ) {
    return false;
  }
  writePrefs({ ...prefs, armed: true });
  return true;
}

/**
 * Selects [task] as today's frog. Switching to a different task resets tracked
 * progress/tick so a new frog cannot be completed with the previous frog's time;
 * re-selecting the same task is idempotent.
 */
export function selectFrog(task: FrogTask, nowMillis: number = Date.now()): void {
  if (!DESKTOP_FROG_ENABLED) return;
  rolloverIfNeeded(nowMillis);
  const prefs = readPrefs();
  const next = sanitizeFrogTask(task);
  const previous = decodeFrog(prefs.selectedJson);
  const sameFrog = previous !== null && frogTaskKey(previous) === frogTaskKey(next);
  writePrefs({
    ...prefs,
    selectedJson: JSON.stringify(next),
    ...(sameFrog ? {} : { tickedOff: false, trackedSeconds: 0 }),
  });
}

/** Clears the selected frog and its progress, returning the phase to pick_frog. */
export function clearFrog(nowMillis: number = Date.now()): void {
  if (!DESKTOP_FROG_ENABLED) return;
  rolloverIfNeeded(nowMillis);
  writePrefs({ ...readPrefs(), selectedJson: "", tickedOff: false, trackedSeconds: 0 });
}

/** Marks the selected frog done (default) or not done; tracked time untouched. */
export function tickOffFrog(tickedOff: boolean = true, nowMillis: number = Date.now()): void {
  if (!DESKTOP_FROG_ENABLED) return;
  rolloverIfNeeded(nowMillis);
  writePrefs({ ...readPrefs(), tickedOff: Boolean(tickedOff) });
}

/** Overwrites the tracked focus seconds (never negative). */
export function setTrackedSeconds(seconds: number, nowMillis: number = Date.now()): void {
  if (!DESKTOP_FROG_ENABLED) return;
  rolloverIfNeeded(nowMillis);
  writePrefs({
    ...readPrefs(),
    trackedSeconds: Math.min(INT_MAX, Math.max(0, Math.trunc(seconds) || 0)),
  });
}

/**
 * Adds tracked focus seconds, ignored when the phase is already COMPLETE
 * (Android's exact guard) and — because desktop meters a live timer instead of
 * only committed credit — when no frog is armed+selected. A frog that is ticked
 * off but short of the required time keeps accruing, exactly like Android, so
 * "tick off then focus" and "focus then tick off" both complete the day.
 * Caps at Int.MAX_VALUE to match the Kotlin store.
 */
export function addTrackedSeconds(seconds: number, nowMillis: number = Date.now()): void {
  if (!DESKTOP_FROG_ENABLED) return;
  if (!Number.isFinite(seconds) || seconds <= 0) return;
  rolloverIfNeeded(nowMillis);
  const prefs = readPrefs();
  const requiredSeconds = clampRequiredMinutes(prefs.requiredMinutes) * 60;
  const trackedSeconds = Math.max(0, prefs.trackedSeconds);
  const selected = decodeFrog(prefs.selectedJson);
  const phase = frogPhaseFrom(
    prefs.armed,
    selected !== null,
    prefs.tickedOff,
    trackedSeconds,
    requiredSeconds,
  );
  if (phase === "complete" || !prefs.armed || selected === null) return;
  writePrefs({
    ...prefs,
    trackedSeconds: Math.min(INT_MAX, trackedSeconds + Math.floor(seconds)),
  });
}

// ---------------------------------------------------------------------------
// React hook
// ---------------------------------------------------------------------------

/**
 * Re-reads the frog store on an interval (30s) so the lazy daily rollover and
 * armIfDue run without user interaction, and on same-tab notifications / the
 * cross-webview `storage` event so the blocker window and the main window stay
 * in sync.
 */
export function useFrogState(): { state: FrogState; actions: FrogActions } {
  const [state, setState] = useState<FrogState>(() => {
    // Opening the app counts as the "first unlock": arm the day if it is due.
    if (DESKTOP_FROG_ENABLED) armIfDue();
    return readFrogState();
  });
  const refresh = useCallback(() => setState(readFrogState()), []);

  useEffect(() => {
    const tick = () => {
      if (DESKTOP_FROG_ENABLED) armIfDue();
      refresh();
    };
    tick();
    const intervalId = window.setInterval(tick, FROG_TICK_MS);
    const onLocalChange = () => refresh();
    const onStorage = (event: StorageEvent) => {
      if (event.key === null || event.key.startsWith(FROG_STORAGE_PREFIX)) refresh();
    };
    window.addEventListener(FROG_CHANGED_EVENT, onLocalChange);
    window.addEventListener("storage", onStorage);
    return () => {
      window.clearInterval(intervalId);
      window.removeEventListener(FROG_CHANGED_EVENT, onLocalChange);
      window.removeEventListener("storage", onStorage);
    };
  }, [refresh]);

  const actions = useMemo<FrogActions>(() => {
    const run = (mutate: () => void) => {
      mutate();
      refresh();
    };
    return {
      setEnabled: (enabled) => run(() => setEnabled(enabled)),
      setRequiredMinutes: (minutes) => run(() => setRequiredMinutes(minutes)),
      setWakeHour: (hour) => run(() => setWakeHour(hour)),
      armIfDue: () => {
        const armed = armIfDue();
        refresh();
        return armed;
      },
      selectFrog: (task) => run(() => selectFrog(task)),
      clearFrog: () => run(() => clearFrog()),
      tickOffFrog: (tickedOff = true) => run(() => tickOffFrog(tickedOff)),
      addTrackedSeconds: (seconds) => run(() => addTrackedSeconds(seconds)),
      refresh,
    };
  }, [refresh]);

  return { state, actions };
}
