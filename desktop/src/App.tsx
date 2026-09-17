import {
  Authenticated,
  AuthLoading,
  Unauthenticated,
  useMutation,
  useQuery,
} from "convex/react";
import { SignIn } from "@clerk/clerk-react";
import { invoke } from "@tauri-apps/api/core";
import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api as convexApi } from "../../convex/_generated/api";
import {
  localDate,
  localDateOffset,
  syncApi,
  type KnownTarget,
  type KnownTargetDevice,
  type TargetGroup,
  type TargetGroupMember,
  type UsageBucket,
  type UsageSummary,
  type UserPrefs,
} from "./sync";
import { useFocusAuth } from "./auth";
import NukeOverlay, { NukeButton } from "./NukeOverlay";
import "./styles.css";
import "./loading.css";

const api: any = convexApi;
type Tab = "focus" | "boundaries" | "settings" | "account";
type AppItem = {
  packageName: string;
  appName: string;
  isBlocked: boolean;
  category: string;
  specificShortsOnly?: boolean;
};
type SiteItem = {
  domain: string;
  displayName: string;
  isBlocked: boolean;
  category: string;
  isCustom?: boolean;
};
type WorkRecord = {
  recordId?: string;
  title: string;
  durationMinutes: number;
  timestamp: number;
  source?: string;
  earnedMinutesCredited?: number;
};
type NativeIdentity = {
  id: string;
  name: string;
  platform: string;
  createdAtMs: number;
};
type NativeCurrent = {
  capturedAtMs: number;
  appId: string;
  appName: string;
  windowTitle: string;
  browserDomain?: string;
  deviceId: string;
  deviceName: string;
  idle: boolean;
  blocked?: boolean;
};
type NativeUsage = {
  date: string;
  appId: string;
  appName: string;
  browserDomain?: string;
  activeSeconds: number;
  deviceId: string;
  deviceName: string;
  platform: string;
};
type NativeSnapshot = {
  device: NativeIdentity;
  current?: NativeCurrent;
  usage: NativeUsage[];
  running: boolean;
  blockedTargets?: { appIds: string[]; domains: string[] };
  config?: {
    sampleIntervalMs: number;
    idleThresholdSeconds: number;
    captureBrowserDomains: boolean;
  };
};
type NativeStatus = {
  running: boolean;
  enforcementActive: boolean;
  current?: NativeCurrent;
  lastError?: string | null;
};

function Icon({
  name,
  size = 20,
}: {
  name:
    | "focus"
    | "grid"
    | "settings"
    | "user"
    | "clock"
    | "monitor"
    | "phone"
    | "globe"
    | "search"
    | "check"
    | "pause"
    | "play"
    | "sync"
    | "lock"
    | "plus";
  size?: number;
}) {
  const paths: Record<string, React.ReactNode> = {
    focus: (
      <>
        <rect x="4" y="4" width="6" height="6" rx="1" />
        <rect x="14" y="4" width="6" height="6" rx="1" />
        <rect x="4" y="14" width="6" height="6" rx="1" />
        <rect x="14" y="14" width="6" height="6" rx="1" />
      </>
    ),
    grid: (
      <>
        <circle cx="6" cy="6" r="2" />
        <circle cx="12" cy="6" r="2" />
        <circle cx="18" cy="6" r="2" />
        <circle cx="6" cy="12" r="2" />
        <circle cx="12" cy="12" r="2" />
        <circle cx="18" cy="12" r="2" />
        <circle cx="6" cy="18" r="2" />
        <circle cx="12" cy="18" r="2" />
        <circle cx="18" cy="18" r="2" />
      </>
    ),
    settings: (
      <>
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.1A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h.1A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.14.38.36.72.65 1 .3.27.68.4 1.08.4H21v4h-.1a1.7 1.7 0 0 0-1.5.6Z" />
      </>
    ),
    user: (
      <>
        <circle cx="12" cy="8" r="4" />
        <path d="M4 21a8 8 0 0 1 16 0" />
      </>
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </>
    ),
    monitor: (
      <>
        <rect x="3" y="4" width="18" height="13" rx="2" />
        <path d="M8 21h8m-4-4v4" />
      </>
    ),
    phone: (
      <>
        <rect x="7" y="2" width="10" height="20" rx="2" />
        <path d="M11 18h2" />
      </>
    ),
    globe: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
      </>
    ),
    search: (
      <>
        <circle cx="10.5" cy="10.5" r="6.5" />
        <path d="m16 16 5 5" />
      </>
    ),
    check: <path d="m5 12 4 4L19 6" />,
    pause: (
      <>
        <path d="M9 6v12M15 6v12" />
      </>
    ),
    play: <path d="m9 6 9 6-9 6Z" />,
    sync: (
      <>
        <path d="M20 7h-5V2" />
        <path d="M20 7a9 9 0 1 0 1 7" />
      </>
    ),
    lock: (
      <>
        <rect x="5" y="10" width="14" height="11" rx="2" />
        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
      </>
    ),
    plus: <path d="M12 5v14M5 12h14" />,
  };
  return (
    <svg
      className="icon-svg"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name]}
    </svg>
  );
}

function fmt(seconds: number) {
  const mins = Math.max(0, Math.round(seconds / 60));
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h ? `${h}h ${m}m` : `${m}m`;
}
function formatBank(seconds: number) {
  const total = Math.max(0, Math.floor(seconds || 0));
  return `${Math.floor(total / 60)}m ${String(total % 60).padStart(2, "0")}s`;
}
function todayLabel() {
  return new Intl.DateTimeFormat(undefined, {
    weekday: "long",
    month: "short",
    day: "numeric",
  }).format(new Date());
}
function tauriAvailable() {
  return Boolean((window as any).__TAURI_INTERNALS__);
}

// Real device id: prefer the Tauri tracker identity, else a random id persisted in
// localStorage. Returns null when neither is available (e.g. storage blocked) so
// callers can skip heartbeat/usage uploads instead of sending a junk id.
function getStoredDeviceId(): string | null {
  try {
    const store = window.localStorage;
    const existing = store.getItem("focuslock.desktopDeviceId");
    if (existing) return existing;
    const c = window.crypto as any;
    const fresh = `windows:${c && typeof c.randomUUID === "function" ? c.randomUUID() : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`}`;
    store.setItem("focuslock.desktopDeviceId", fresh);
    return fresh;
  } catch {
    return null;
  }
}

// Cross-platform prefs come from the `userPrefs` doc (focus:getDashboard). Each
// numeric field carries its own `*UpdatedAt` (ms epoch) so Android, desktop and
// the extension resolve last-writer-wins independently. A localStorage copy
// keeps the value before the query resolves and across re-sign-ins; when the
// local copy is newer than the account it is pushed so other platforms converge.
const DEFAULT_WORK_RATIO = 4;
const DEFAULT_TASK_BONUS_MINUTES = 5;
const WORK_RATIO_MIN = 1;
const WORK_RATIO_MAX = 10;
const TASK_BONUS_MIN = 0;
const TASK_BONUS_MAX = 20;
const PREFS_CACHE_KEY = "focuslock.prefs";
const LEGACY_WORK_RATIO_KEY = "focuslock.workRatio";
const LEGACY_TASK_BONUS_KEY = "focuslock.taskBonus";
const PREFS_PUSH_DEBOUNCE_MS = 600;

type SyncedPrefs = {
  workRatio: number;
  workRatioUpdatedAt: number;
  taskBonusMinutes: number;
  taskBonusMinutesUpdatedAt: number;
};

function clampPref(n: unknown, fallback: number, min: number, max: number) {
  const num = Number(n);
  if (!Number.isFinite(num)) return fallback;
  return Math.min(max, Math.max(min, Math.round(num)));
}

function readLocalPrefs(): SyncedPrefs {
  const base: SyncedPrefs = {
    workRatio: DEFAULT_WORK_RATIO,
    workRatioUpdatedAt: 0,
    taskBonusMinutes: DEFAULT_TASK_BONUS_MINUTES,
    taskBonusMinutesUpdatedAt: 0,
  };
  try {
    const raw = window.localStorage.getItem(PREFS_CACHE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<SyncedPrefs>;
      return {
        workRatio: clampPref(
          parsed.workRatio,
          base.workRatio,
          WORK_RATIO_MIN,
          WORK_RATIO_MAX,
        ),
        workRatioUpdatedAt: Number(parsed.workRatioUpdatedAt) || 0,
        taskBonusMinutes: clampPref(
          parsed.taskBonusMinutes,
          base.taskBonusMinutes,
          TASK_BONUS_MIN,
          TASK_BONUS_MAX,
        ),
        taskBonusMinutesUpdatedAt:
          Number(parsed.taskBonusMinutesUpdatedAt) || 0,
      };
    }
    // Migrate the old device-only keys. They had no timestamp, so treat them as
    // ts 0: a remote value always wins, but an offline user keeps their choice.
    const migratedRatio = window.localStorage.getItem(LEGACY_WORK_RATIO_KEY);
    const migratedBonus = window.localStorage.getItem(LEGACY_TASK_BONUS_KEY);
    if (migratedRatio !== null || migratedBonus !== null) {
      return {
        ...base,
        workRatio: clampPref(
          migratedRatio,
          base.workRatio,
          WORK_RATIO_MIN,
          WORK_RATIO_MAX,
        ),
        taskBonusMinutes: clampPref(
          migratedBonus,
          base.taskBonusMinutes,
          TASK_BONUS_MIN,
          TASK_BONUS_MAX,
        ),
      };
    }
  } catch {
    /* storage blocked: keep values in memory only */
  }
  return base;
}

function writeLocalPrefs(prefs: SyncedPrefs) {
  try {
    window.localStorage.setItem(PREFS_CACHE_KEY, JSON.stringify(prefs));
  } catch {
    /* private mode: keep in memory */
  }
}

function samePrefs(a: SyncedPrefs, b: SyncedPrefs) {
  return (
    a.workRatio === b.workRatio &&
    a.workRatioUpdatedAt === b.workRatioUpdatedAt &&
    a.taskBonusMinutes === b.taskBonusMinutes &&
    a.taskBonusMinutesUpdatedAt === b.taskBonusMinutesUpdatedAt
  );
}

// Per-field last-writer-wins. Returns the merged values plus which fields the
// local copy is ahead on (those must be pushed so other platforms converge).
function mergeRemotePrefs(
  remote: UserPrefs | null | undefined,
  local: SyncedPrefs,
) {
  const remoteRatioAt = remote?.workRatioUpdatedAt || 0;
  const remoteBonusAt = remote?.taskBonusMinutesUpdatedAt || 0;
  const remoteRatioNewer =
    typeof remote?.workRatio === "number" &&
    remoteRatioAt > local.workRatioUpdatedAt;
  const remoteBonusNewer =
    typeof remote?.taskBonusMinutes === "number" &&
    remoteBonusAt > local.taskBonusMinutesUpdatedAt;
  const merged: SyncedPrefs = {
    workRatio: remoteRatioNewer
      ? clampPref(
          remote?.workRatio,
          local.workRatio,
          WORK_RATIO_MIN,
          WORK_RATIO_MAX,
        )
      : local.workRatio,
    workRatioUpdatedAt: remoteRatioNewer
      ? remoteRatioAt
      : local.workRatioUpdatedAt,
    taskBonusMinutes: remoteBonusNewer
      ? clampPref(
          remote?.taskBonusMinutes,
          local.taskBonusMinutes,
          TASK_BONUS_MIN,
          TASK_BONUS_MAX,
        )
      : local.taskBonusMinutes,
    taskBonusMinutesUpdatedAt: remoteBonusNewer
      ? remoteBonusAt
      : local.taskBonusMinutesUpdatedAt,
  };
  return {
    merged,
    pushRatio: local.workRatioUpdatedAt > remoteRatioAt,
    pushBonus: local.taskBonusMinutesUpdatedAt > remoteBonusAt,
  };
}

// Per-device flag persisted locally. Matches how Android stores its equivalent
// settings (DataStore) without inventing a Convex field that does not exist.
function useLocalFlag(key: string, initial = false): [boolean, (value: boolean) => void] {
  const [value, setValue] = useState<boolean>(() => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw === null ? initial : raw === "1";
    } catch {
      return initial;
    }
  });
  const update = useCallback(
    (next: boolean) => {
      setValue(next);
      try {
        window.localStorage.setItem(key, next ? "1" : "0");
      } catch {
        // Ignore storage failures — the in-memory value still applies.
      }
    },
    [key],
  );
  return [value, update];
}

function useSyncedPrefs(dashboard: any) {
  const savePrefs = useMutation(syncApi.savePrefs);
  const [prefs, setPrefs] = useState<SyncedPrefs>(() => readLocalPrefs());
  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;
  const remoteUpdatedAtRef = useRef(0);
  const pendingRef = useRef({ ratio: false, bonus: false });
  const pushTimerRef = useRef<number | null>(null);

  const pushPrefs = useCallback(
    (snapshot: SyncedPrefs, fields: { ratio: boolean; bonus: boolean }) => {
      if (!fields.ratio && !fields.bonus) return;
      // Patch-only body: never send strictMode/weeklyReport/... so this write
      // cannot clobber the strict-mode toggle (and vice versa).
      const patch: {
        updatedAt: number;
        workRatio?: number;
        workRatioUpdatedAt?: number;
        taskBonusMinutes?: number;
        taskBonusMinutesUpdatedAt?: number;
      } = {
        updatedAt: Math.max(Date.now(), remoteUpdatedAtRef.current),
      };
      if (fields.ratio) {
        patch.workRatio = snapshot.workRatio;
        patch.workRatioUpdatedAt = snapshot.workRatioUpdatedAt;
      }
      if (fields.bonus) {
        patch.taskBonusMinutes = snapshot.taskBonusMinutes;
        patch.taskBonusMinutesUpdatedAt = snapshot.taskBonusMinutesUpdatedAt;
      }
      savePrefs(patch).catch((err) =>
        console.warn("[focuslock] savePrefs (ratio/bonus) failed", err),
      );
    },
    [savePrefs],
  );

  const flushPush = useCallback(() => {
    if (pushTimerRef.current !== null) {
      window.clearTimeout(pushTimerRef.current);
      pushTimerRef.current = null;
    }
    const fields = pendingRef.current;
    pendingRef.current = { ratio: false, bonus: false };
    pushPrefs(prefsRef.current, fields);
  }, [pushPrefs]);

  // Debounce rapid slider drags: keep the newest value, push once they settle.
  const schedulePush = useCallback(
    (fields: { ratio: boolean; bonus: boolean }) => {
      pendingRef.current = {
        ratio: pendingRef.current.ratio || fields.ratio,
        bonus: pendingRef.current.bonus || fields.bonus,
      };
      if (pushTimerRef.current !== null)
        window.clearTimeout(pushTimerRef.current);
      pushTimerRef.current = window.setTimeout(
        flushPush,
        PREFS_PUSH_DEBOUNCE_MS,
      );
    },
    [flushPush],
  );

  // Merge when the dashboard (re)loads. Our own pushes also change the query
  // result, so only strictly-newer remote fields are adopted and only strictly-
  // newer local fields are pushed — otherwise the two would ping-pong.
  useEffect(() => {
    const remote = dashboard?.prefs as UserPrefs | null | undefined;
    if (remote === undefined) return;
    remoteUpdatedAtRef.current = remote?.updatedAt || 0;
    const local = prefsRef.current;
    const { merged, pushRatio, pushBonus } = mergeRemotePrefs(remote, local);
    if (!samePrefs(merged, local)) {
      prefsRef.current = merged;
      setPrefs(merged);
      writeLocalPrefs(merged);
    }
    if (pushRatio || pushBonus)
      schedulePush({ ratio: pushRatio, bonus: pushBonus });
  }, [dashboard?.prefs, schedulePush]);

  // Best-effort flush if the app closes mid-debounce.
  useEffect(
    () => () => {
      if (pushTimerRef.current !== null) flushPush();
    },
    [flushPush],
  );

  const setWorkRatio = useCallback(
    (next: number) => {
      const value = clampPref(
        next,
        prefsRef.current.workRatio,
        WORK_RATIO_MIN,
        WORK_RATIO_MAX,
      );
      const updated: SyncedPrefs = {
        ...prefsRef.current,
        workRatio: value,
        workRatioUpdatedAt: Date.now(),
      };
      prefsRef.current = updated;
      setPrefs(updated);
      writeLocalPrefs(updated);
      schedulePush({ ratio: true, bonus: false });
    },
    [schedulePush],
  );

  const setTaskBonus = useCallback(
    (next: number) => {
      const value = clampPref(
        next,
        prefsRef.current.taskBonusMinutes,
        TASK_BONUS_MIN,
        TASK_BONUS_MAX,
      );
      const updated: SyncedPrefs = {
        ...prefsRef.current,
        taskBonusMinutes: value,
        taskBonusMinutesUpdatedAt: Date.now(),
      };
      prefsRef.current = updated;
      setPrefs(updated);
      writeLocalPrefs(updated);
      schedulePush({ ratio: false, bonus: true });
    },
    [schedulePush],
  );

  return {
    workRatio: prefs.workRatio,
    taskBonus: prefs.taskBonusMinutes,
    setWorkRatio,
    setTaskBonus,
  } as const;
}

// Stable empty arrays: a fresh [] literal per render would defeat the
// React.memo / useMemo identity checks added below.
const EMPTY_DEVICES: any[] = [];
const EMPTY_USAGE: NativeUsage[] = [];
const EMPTY_TARGETS: any[] = [];
const EMPTY_GROUPS: any[] = [];

// Stable args object for no-argument Convex queries — a fresh {} per render
// would change the useMemo key inside convex/react's useQuery and resubscribe.
const EMPTY_ARGS: Record<string, never> = {};

type UsageRange = "today" | "7d" | "30d" | "all";
const USAGE_RANGES: { id: UsageRange; label: string }[] = [
  { id: "today", label: "Today" },
  { id: "7d", label: "7 days" },
  { id: "30d", label: "30 days" },
  { id: "all", label: "All time" },
];
function usageRangeLabel(range: UsageRange): string {
  return USAGE_RANGES.find((entry) => entry.id === range)?.label || "Today";
}

// ---------------------------------------------------------------------------
// Daily-limit enforcement
//
// Desktop has no Rust-side limit engine: `TrackerRuntime` merely minimises the
// foreground window when its `BlockedTargets` match (exact appId, or domain
// equality / subdomain suffix). A daily limit is therefore enforced by UNIONing
// the exhausted target into the same `set_blocked_targets` payload the
// Boundaries page already drives ("over the limit" == "blocked"). The payload is
// rebuilt from the current dashboard + today's summary on every pass, so
// previously-blocked targets are always preserved (union, never replace).
//
// Usage rule: combinedSeconds = max(localSecondsToday, serverSecondsToday).
// - The ~25s upload cadence means the server total lags and may not include
//   another device's usage yet, i.e. it can only under-report. max() never
//   under-blocks and still fires from local data while offline.
// - Trade-off: a stale or larger server figure can over-block until the next
//   summary refresh; with a single shared cap that is the safe direction.
// ---------------------------------------------------------------------------

/**
 * Canonical website key: trimmed, lowercased and with a leading `www.` stripped
 * — exactly what Rust (`BlockedTargets::normalized`) and the Convex
 * `normalizeMemberKey` canonicalizer store. Exported so every desktop call site
 * shares one rule instead of drifting (a legacy `www.foo.com` row must match a
 * captured `foo.com` usage row). App/package keys are NOT passed through this:
 * `www.` is a website-only concern.
 */
export function normalizeSiteKey(value: string | null | undefined): string {
  return String(value || "")
    .trim()
    .replace(/^www\./i, "")
    .toLowerCase();
}

/** `"kind:key"` identity of a target: website keys canonicalized, app keys only
 * lowercased (Convex stores both lowercased; only websites drop `www.`). */
function targetKeyFor(kind: string, rawKey: string): string {
  return kind === "website"
    ? normalizeSiteKey(rawKey)
    : String(rawKey || "").toLowerCase();
}

/** Same mapping the upload path uses, normalized like Convex stores it. */
function usageTargetFor(entry: NativeUsage): {
  targetKind: "app" | "website";
  targetKey: string;
} {
  return entry.browserDomain
    ? {
        targetKind: "website",
        targetKey: normalizeSiteKey(entry.browserDomain),
      }
    : {
        targetKind: "app",
        targetKey: String(entry.appId || "").toLowerCase(),
      };
}

/** Today's local seconds keyed by `"kind:key"` with the same canonical keys
 * Convex stores (websites via normalizeSiteKey, apps lowercased). */
function localSecondsByTarget(
  usage: NativeUsage[],
  date: string,
): Map<string, number> {
  const map = new Map<string, number>();
  for (const entry of usage) {
    if (entry.date !== date) continue;
    const { targetKind, targetKey } = usageTargetFor(entry);
    const key = `${targetKind}:${targetKey}`;
    map.set(key, (map.get(key) || 0) + Math.max(0, entry.activeSeconds || 0));
  }
  return map;
}

/** Same normalization Rust applies in BlockedTargets::normalized (clean_values). */
function normalizeTargetKeys(values: string[] | undefined): string[] {
  const seen = new Set<string>();
  for (const raw of values || []) {
    const value = String(raw || "")
      .trim()
      .replace(/^www\./i, "")
      .toLowerCase();
    if (value) seen.add(value);
  }
  return [...seen].sort();
}

function canonicalBlockedTargets(targets: {
  appIds?: string[];
  domains?: string[];
}): string {
  return JSON.stringify({
    appIds: normalizeTargetKeys(targets?.appIds),
    domains: normalizeTargetKeys(targets?.domains),
  });
}

// Exported so pure-function tests can pin the union + key-normalization rules;
// the component itself consumes it only through the enforcement effect.
export function evaluateBlockedTargets({
  dashboard,
  groups,
  summary,
  usage,
}: {
  dashboard: any;
  groups: TargetGroup[] | undefined;
  summary: UsageSummary | undefined;
  usage: NativeUsage[];
}): { targets: { appIds: string[]; domains: string[] }; exceeded: string[] } {
  // Base set: exactly what the previous dashboard-driven effect sent.
  const appIds = new Set<string>(
    (dashboard?.apps || [])
      .filter((item: AppItem) => item.isBlocked && item.category === "Windows")
      .map((item: AppItem) => item.packageName),
  );
  const domains = new Set<string>(
    (dashboard?.sites || [])
      .filter((item: SiteItem) => item.isBlocked)
      .map((item: SiteItem) => normalizeSiteKey(item.domain))
      .filter(Boolean),
  );

  const today = localDate();
  const localSeconds = localSecondsByTarget(usage, today);
  const localSecondsFor = (kind: string, rawKey: string) =>
    localSeconds.get(`${kind}:${targetKeyFor(kind, rawKey)}`) || 0;
  const exceeded: string[] = [];

  // 1. Groups: one shared cap over every member, summed across devices today.
  const groupSource: any[] = summary?.groups?.length
    ? summary.groups
    : groups || EMPTY_GROUPS;
  for (const group of groupSource) {
    const limitMinutes = Number(group?.dailyLimitMinutes) || 0;
    if (limitMinutes <= 0 || group?.limitEnabled === false) continue;
    const members: TargetGroupMember[] = group?.members || [];
    if (!members.length) continue;
    let local = 0;
    for (const member of members) {
      local += localSecondsFor(member.targetKind, member.targetKey);
    }
    const server = Number(group?.trackedSeconds) || 0;
    if (Math.max(local, server) < limitMinutes * 60) continue;
    for (const member of members) {
      const key = targetKeyFor(member.targetKind, member.targetKey);
      if (member.targetKind === "app") appIds.add(key);
      else if (member.targetKind === "website") domains.add(key);
    }
    exceeded.push(String(group?.name || group?.groupId || "group"));
  }

  // 2. Legacy per-target `appLimits` rows authored on Android. Unsupported
  //    kinds ("category", schedules, ...) are skipped.
  const serverTargetSeconds = new Map<string, number>();
  for (const target of summary?.targets || []) {
    serverTargetSeconds.set(
      `${target.targetKind}:${targetKeyFor(target.targetKind, target.targetKey)}`,
      Number(target.trackedSeconds) || 0,
    );
  }
  for (const row of dashboard?.limits || []) {
    const kind = String(row?.targetKind || "");
    if (kind !== "app" && kind !== "website") continue;
    const limitMinutes = Number(row?.dailyLimitMinutes) || 0;
    if (limitMinutes <= 0) continue;
    const key = targetKeyFor(kind, row?.targetKey || "");
    if (!key) continue;
    const combined = Math.max(
      localSecondsFor(kind, key),
      serverTargetSeconds.get(`${kind}:${key}`) || 0,
    );
    if (combined < limitMinutes * 60) continue;
    if (kind === "app") appIds.add(key);
    else domains.add(key);
    exceeded.push(String(row?.label || key));
  }

  return { targets: { appIds: [...appIds], domains: [...domains] }, exceeded };
}

// Polling: the tracker samples every second, but the UI only needs to replace
// state when the payload actually changed. Each poll serializes the incoming
// snapshot/status once and skips setState on identical data, so idle periods
// (and the paused-tracker case where nothing changes) produce zero re-renders.
function useNativeTracking() {
  const [snapshot, setSnapshot] = useState<NativeSnapshot | null>(null);
  const [status, setStatus] = useState<NativeStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const snapshotJsonRef = useRef<string | null>(null);
  const statusJsonRef = useRef<string | null>(null);

  const applySnapshot = useCallback((next: NativeSnapshot) => {
    const nextJson = JSON.stringify(next);
    if (nextJson === snapshotJsonRef.current) return;
    snapshotJsonRef.current = nextJson;
    setSnapshot(next);
  }, []);

  const applyStatus = useCallback((next: NativeStatus) => {
    const nextJson = JSON.stringify(next);
    if (nextJson === statusJsonRef.current) return;
    statusJsonRef.current = nextJson;
    setStatus(next);
  }, []);

  // Full refresh (snapshot + status). `force` drops the change-detection
  // memo so callers like "Sync Now" always get a fresh state object.
  const refresh = useCallback(
    async (force = false) => {
      if (!tauriAvailable()) {
        setError("Open the installed Windows app to start native tracking.");
        return;
      }
      try {
        const [nextSnapshot, nextStatus] = await Promise.all([
          invoke<NativeSnapshot>("get_tracking_snapshot"),
          invoke<NativeStatus>("get_tracker_status"),
        ]);
        if (force) {
          snapshotJsonRef.current = null;
          statusJsonRef.current = null;
        }
        applySnapshot(nextSnapshot);
        applyStatus(nextStatus);
        setError(null);
      } catch (e) {
        setError(`Tracker unavailable: ${String(e)}`);
      }
    },
    [applySnapshot, applyStatus],
  );

  useEffect(() => {
    refresh();
    // get_tracker_status is a couple of mutex reads on the Rust side, so it
    // keeps the tighter 3s cadence; the snapshot (full sorted usage) polls
    // at 5s.
    const snapshotId = window.setInterval(() => {
      if (!tauriAvailable()) return;
      invoke<NativeSnapshot>("get_tracking_snapshot")
        .then(applySnapshot)
        .then(() => setError(null))
        .catch((e) => setError(`Tracker unavailable: ${String(e)}`));
    }, 5000);
    const statusId = window.setInterval(() => {
      if (!tauriAvailable()) return;
      invoke<NativeStatus>("get_tracker_status")
        .then(applyStatus)
        .catch((e) => setError(`Tracker unavailable: ${String(e)}`));
    }, 3000);
    return () => {
      window.clearInterval(snapshotId);
      window.clearInterval(statusId);
    };
  }, [refresh, applySnapshot, applyStatus]);
  return { snapshot, status, error, refresh };
}

export default function App() {
  // The native provider owns browser/PKCE auth and attaches the token directly
  // to Convex. Its plain ConvexProvider has no browser auth-helper context.
  if (tauriAvailable()) return <NativeApp />;
  return (
    <>
      <AuthLoading>
        <AppLoading />
      </AuthLoading>
      <Unauthenticated>
        <SignInPage />
      </Unauthenticated>
      <Authenticated>
        <DesktopApp />
      </Authenticated>
    </>
  );
}

function NativeApp() {
  const auth = useFocusAuth();
  if (auth.loading) return <AppLoading />;
  return auth.user ? <DesktopApp /> : <SignInPage />;
}

function AppLoading() {
  return (
    <main className="app-loading" aria-live="polite">
      <span className="brand-mark">
        <Icon name="lock" size={22} />
      </span>
      <p>Connecting FocusLock…</p>
    </main>
  );
}

function SignInPage() {
  const auth = useFocusAuth();
  const native = tauriAvailable();
  const [opening, setOpening] = useState(false);
  async function openBrowser() {
    setOpening(true);
    try {
      await auth.signInInBrowser();
    } finally {
      setOpening(false);
    }
  }
  return (
    <main className="auth-page">
      <section className="auth-intro">
        <div className="brand-mark">
          <Icon name="lock" size={22} />
        </div>
        <p className="eyebrow">FocusLock for Windows</p>
        <h1>Your time, on every device.</h1>
        <p>
          Sign in with the same account as Android. Your balance, boundaries,
          work history and tracked device usage stay together.
        </p>
        <div className="auth-points">
          <span>
            <Icon name="monitor" /> Windows app tracking
          </span>
          <span>
            <Icon name="globe" /> Website tracking
          </span>
          <span>
            <Icon name="sync" /> Account sync
          </span>
        </div>
      </section>
      <section className="auth-panel" aria-label="Sign in">
        {native ? (
          <div className="browser-auth">
            <Icon name="user" size={28} />
            <h2>Continue in your browser</h2>
            <p>
              FocusLock opens your default browser to sign in with your existing
              account.
            </p>
            <button
              className="primary-button"
              onClick={openBrowser}
              disabled={opening}
            >
              {opening ? "Opening browser…" : "Sign in in browser"}
            </button>
            {auth.error && <p className="inline-error">{auth.error}</p>}
          </div>
        ) : (
          <SignIn
            routing="hash"
            signUpUrl="#/sign-up"
            appearance={{
              variables: {
                colorPrimary: "var(--fl-primary)",
                colorText: "var(--fl-on-surface)",
                colorTextSecondary: "var(--fl-on-surface-variant)",
                colorBackground: "var(--fl-surface)",
                colorInputBackground: "var(--fl-surface-container-lowest)",
                colorInputText: "var(--fl-on-surface)",
              },
              elements: {
                card: "clerk-card",
                headerTitle: "clerk-heading",
                headerSubtitle: "clerk-subtitle",
                formFieldLabel: "clerk-label",
                formFieldInput: "clerk-input",
                formButtonPrimary: "clerk-primary",
                socialButtonsBlockButton: "clerk-social",
                footerActionLink: "clerk-link",
              },
            }}
          />
        )}
      </section>
    </main>
  );
}

function DesktopApp() {
  const auth = useFocusAuth();
  const [tab, setTab] = useState<Tab>("focus");
  const { snapshot, status, error: trackerError, refresh } = useNativeTracking();
  const dashboard: any = useQuery(syncApi.getDashboard, {});
  // Focus-page range selector. Lives here (above useQuery) so the query args can
  // depend on it; memoized so a re-render never resubscribes the query.
  const [usageRange, setUsageRange] = useState<UsageRange>("today");
  // Day key that rolls over at midnight without depending on unrelated renders
  // (a memoized `localDate()` would otherwise freeze yesterday's date).
  const [todayKey, setTodayKey] = useState(() => localDate());
  useEffect(() => {
    const id = window.setInterval(() => {
      setTodayKey((current) => {
        const next = localDate();
        return next === current ? current : next;
      });
    }, 60_000);
    return () => window.clearInterval(id);
  }, []);
  const usageArgs = useMemo(() => {
    if (usageRange === "all") return {};
    if (usageRange === "today")
      return { fromDate: todayKey, toDate: todayKey };
    return {
      // 7d = today + the 6 previous days, 30d = today + the 29 previous days.
      fromDate: localDateOffset(usageRange === "7d" ? 6 : 29),
      toDate: todayKey,
    };
  }, [usageRange, todayKey]);
  const usage: any = useQuery(syncApi.getUsageSummary, usageArgs);
  // Enforcement must NOT follow the range selector: limits are daily, so this
  // stays today-scoped (Convex dedupes it with the "today" range above).
  const todayUsageArgs = useMemo(
    () => ({ fromDate: todayKey, toDate: todayKey }),
    [todayKey],
  );
  const todayUsage: UsageSummary | undefined = useQuery(
    syncApi.getUsageSummary,
    todayUsageArgs,
  ) as any;
  const groups: TargetGroup[] | undefined = useQuery(
    syncApi.listGroups,
    EMPTY_ARGS,
  ) as any;
  // All-time, all-device target enumeration for the group picker. This is the
  // only source that can surface a target tracked solely on another device
  // (phone package or extension domain). Tolerates undefined on older
  // deployments / while signed out — the picker falls back to local sources.
  const knownTargets: KnownTarget[] | undefined = useQuery(
    syncApi.listKnownTargets,
    EMPTY_ARGS,
  ) as any;
  const devices: any[] | undefined = useQuery(syncApi.listDevices, {});
  const heartbeat = useMutation(syncApi.heartbeat);
  const recordUsage = useMutation(syncApi.recordUsageBatch);
  const { workRatio, taskBonus, setWorkRatio, setTaskBonus } =
    useSyncedPrefs(dashboard);
  const deviceId = snapshot?.device.id || getStoredDeviceId();
  const lastUploadRef = useRef(0);
  const usageJsonRef = useRef<string | null>(null);
  const uploadInFlightRef = useRef(false);
  // Refs mirror the latest props for the heartbeat interval below (and avoid
  // re-subscribing the effect on every tracker sample).
  const snapshotRef = useRef<NativeSnapshot | null>(snapshot);
  snapshotRef.current = snapshot;
  const trackerErrorRef = useRef<string | null>(trackerError);
  trackerErrorRef.current = trackerError;
  const [lastSyncAt, setLastSyncAt] = useState(0);
  const [syncing, setSyncing] = useState(false);
  const [boundariesLock, setBoundariesLock] = useLocalFlag(
    "focuslock.boundariesLock",
  );
  // "Merge…" handoff from the Focus usage list: the row's target is parked
  // here, the tab switches to Boundaries, and BoundariesPage opens its create
  // dialog pre-filled with it (then clears this via onInitialDraftConsumed).
  const [pendingMerge, setPendingMerge] = useState<{
    members: TargetGroupMember[];
    sourceLabel: string;
  } | null>(null);
  // Skip uploads until a real device id exists; failures are logged and the bucket
  // is dropped (no offline queue yet — same gap as Android). Heartbeats keep
  // their ~25s cadence via the interval (so a paused tracker still shows the
  // device online); usage buckets are only rebuilt and uploaded when the usage
  // data itself changed — the snapshot only re-fires this effect when its
  // serialized payload changed.
  useEffect(() => {
    if (!tauriAvailable() || !deviceId) return;
    let cancelled = false;
    const push = async () => {
      const snap = snapshotRef.current;
      if (!snap || uploadInFlightRef.current) return;
      const usageJson = JSON.stringify(snap.usage);
      const usageChanged = usageJson !== usageJsonRef.current;
      if (!usageChanged && Date.now() - lastUploadRef.current < 25000) return;
      const buckets: UsageBucket[] = usageChanged
        ? snap.usage.map((u) => {
            // Shared with limit enforcement (usageTargetFor) so local keys can
            // never drift from the keys Convex stores — both lowercased there.
            const { targetKind, targetKey } = usageTargetFor(u);
            return {
              date: u.date,
              targetKind,
              targetKey,
              targetLabel: u.browserDomain || u.appName,
              category: u.browserDomain ? "Web" : "Windows",
              trackedSeconds: u.activeSeconds,
              updatedAt: Date.now(),
            };
          })
        : [];
      uploadInFlightRef.current = true;
      try {
        await heartbeat({
        deviceId,
        name: snap.device.name,
        platform: "windows",
        appVersion: "1.0.0",
        trackingStatus: snap.running ? "active" : "paused",
        statusDetail: trackerErrorRef.current || undefined,
        lastSeen: Date.now(),
        });
        if (buckets.length) await recordUsage({ deviceId, buckets });
        // Advance both clocks only after Convex accepts the upload. A failed
        // request must be retried with the same cumulative usage snapshot.
        usageJsonRef.current = usageJson;
        lastUploadRef.current = Date.now();
        if (!cancelled) setLastSyncAt(Date.now());
      } catch (err) {
        console.warn("[focuslock] heartbeat/usage upload failed; retrying", err);
      } finally {
        uploadInFlightRef.current = false;
      }
    };
    push();
    // Fallback tick so heartbeats continue while the tracker is paused and
    // snapshots stop changing.
    const id = window.setInterval(push, 25000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [snapshot, deviceId, heartbeat, recordUsage]);
  // Blocked-targets payload: union of dashboard blocks + exhausted daily limits.
  // Recomputed on dashboard/summary/snapshot changes, but invoked only when the
  // union actually changed and at most once per debounce window (no invoke spam).
  const blockedTargetsRef = useRef<{ appIds: string[]; domains: string[] } | null>(
    null,
  );
  const blockedInvokedJsonRef = useRef("");
  const blockedTimerRef = useRef<number | null>(null);
  const flushBlockedTargets = useCallback(() => {
    blockedTimerRef.current = null;
    const payload = blockedTargetsRef.current;
    if (!payload) return;
    // Prefer Rust's actual current set (part of every tracker snapshot): the
    // Boundaries page also invokes set_blocked_targets with a narrower payload,
    // so "what we last sent" is not a safe enough dedupe key. Falls back to the
    // last-sent JSON when the tracker is unavailable.
    const rustTargets = snapshotRef.current?.blockedTargets;
    const json = JSON.stringify(payload);
    if (rustTargets) {
      if (canonicalBlockedTargets(payload) === canonicalBlockedTargets(rustTargets)) {
        blockedInvokedJsonRef.current = json;
        return;
      }
    } else if (json === blockedInvokedJsonRef.current) {
      return;
    }
    blockedInvokedJsonRef.current = json;
    invoke("set_blocked_targets", { targets: payload }).catch(() => undefined);
  }, []);
  useEffect(() => {
    if (!tauriAvailable() || !dashboard) return;
    const { targets, exceeded } = evaluateBlockedTargets({
      dashboard,
      groups,
      summary: todayUsage,
      usage: snapshotRef.current?.usage || EMPTY_USAGE,
    });
    if (exceeded.length) {
      console.info("[focuslock] daily limit reached; blocking", exceeded);
    }
    blockedTargetsRef.current = targets;
    if (blockedTimerRef.current !== null)
      window.clearTimeout(blockedTimerRef.current);
    blockedTimerRef.current = window.setTimeout(flushBlockedTargets, 350);
    // Latest-wins: a re-run replaces the pending timer instead of cleaning up.
  }, [dashboard, groups, todayUsage, snapshot, flushBlockedTargets]);
  useEffect(
    () => () => {
      if (blockedTimerRef.current !== null) {
        window.clearTimeout(blockedTimerRef.current);
        blockedTimerRef.current = null;
      }
    },
    [],
  );
  // Manual "Sync Now": clear the throttle and refresh so the upload effect runs,
  // then re-read devices/usage. Real upload happens in the heartbeat effect.
  const syncNow = useCallback(async () => {
    setSyncing(true);
    lastUploadRef.current = 0;
    try {
      // Force mode: even if the snapshot payload is unchanged, re-apply it so
      // the upload effect re-runs with the cleared throttle.
      await refresh(true);
    } finally {
      window.setTimeout(() => setSyncing(false), 600);
    }
  }, [refresh]);
  // Stable (setters only) so the memoized FocusPage keeps skipping re-renders
  // on unrelated state updates.
  const handleMergeTarget = useCallback(
    (member: TargetGroupMember, sourceLabel: string) => {
      setPendingMerge({ members: [member], sourceLabel });
      setTab("boundaries");
    },
    [],
  );
  const clearPendingMerge = useCallback(() => setPendingMerge(null), []);
  return (
    <div className="app-frame">
      <aside className="rail">
        <div className="brand">
          <span className="brand-mark">
            <Icon name="lock" />
          </span>
          <span>FocusLock</span>
        </div>
        <nav aria-label="Primary">
          <NavButton
            active={tab === "focus"}
            icon="focus"
            label="Focus"
            onClick={() => setTab("focus")}
          />
          <NavButton
            active={tab === "boundaries"}
            icon="grid"
            label="Boundaries"
            onClick={() => setTab("boundaries")}
          />
          <NavButton
            active={tab === "settings"}
            icon="settings"
            label="Settings"
            onClick={() => setTab("settings")}
          />
          <NavButton
            active={tab === "account"}
            icon="user"
            label="Account"
            onClick={() => setTab("account")}
          />
        </nav>
        <div className={`tracker-pill ${snapshot?.running ? "online" : ""}`}>
          <span />
          {snapshot?.running ? "Tracking this PC" : "Tracking paused"}
        </div>
        {auth.user?.imageUrl ? (
          <img
            className="rail-avatar"
            src={auth.user.imageUrl}
            alt="Signed-in account"
          />
        ) : (
          <span className="rail-avatar fallback">
            <Icon name="user" />
          </span>
        )}
      </aside>
      <main className="workspace" id="main-content">
        {dashboard === undefined ? (
          <DashboardSkeleton />
        ) : tab === "focus" ? (
          <FocusPage
            dashboard={dashboard}
            usage={usage}
            devices={devices || EMPTY_DEVICES}
            snapshot={snapshot}
            status={status}
            trackerError={trackerError}
            workRatio={workRatio}
            taskBonus={taskBonus}
            usageRange={usageRange}
            onUsageRangeChange={setUsageRange}
            onMerge={handleMergeTarget}
          />
        ) : tab === "boundaries" ? (
          <BoundariesPage
            dashboard={dashboard}
            snapshot={snapshot}
            usage={usage}
            todayUsage={todayUsage}
            boundariesLock={boundariesLock}
            knownTargets={knownTargets}
            initialDraftMembers={pendingMerge?.members || null}
            mergeSourceLabel={pendingMerge?.sourceLabel || null}
            onInitialDraftConsumed={clearPendingMerge}
          />
        ) : tab === "settings" ? (
          <SettingsPage
            dashboard={dashboard}
            snapshot={snapshot}
            status={status}
            trackerError={trackerError}
            refresh={refresh}
            workRatio={workRatio}
            setWorkRatio={setWorkRatio}
            taskBonus={taskBonus}
            setTaskBonus={setTaskBonus}
            boundariesLock={boundariesLock}
            setBoundariesLock={setBoundariesLock}
            lastSyncAt={lastSyncAt}
            syncing={syncing}
            onSyncNow={syncNow}
          />
        ) : (
          <AccountPage
            devices={devices || EMPTY_DEVICES}
            lastSyncAt={lastSyncAt}
            syncing={syncing}
            onSyncNow={syncNow}
            trackerError={trackerError}
          />
        )}
      </main>
      <NukeOverlay />
    </div>
  );
}

function NavButton({
  active,
  icon,
  label,
  onClick,
}: {
  active: boolean;
  icon: any;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      className={`nav-button ${active ? "active" : ""}`}
      onClick={onClick}
    >
      <Icon name={icon} />
      <span>{label}</span>
    </button>
  );
}

// Memoized: all props are stable between tracker polls (snapshot/status are
// change-detected upstream, devices uses a stable EMPTY fallback), so
// unrelated DesktopApp state updates skip re-rendering the whole page.
const FocusPage = memo(function FocusPage({
  dashboard,
  usage,
  devices,
  snapshot,
  status,
  trackerError,
  workRatio,
  taskBonus,
  usageRange = "today",
  onUsageRangeChange,
  onMerge,
}: any) {
  const state = dashboard?.state || {};
  const records: WorkRecord[] = dashboard?.records || [];
  const total = usage?.totalTrackedSeconds || 0;
  const rangeLabel = usageRangeLabel(usageRange);
  // deviceId -> platform lookup built once per devices change instead of an
  // O(devices) find per usage row per render.
  const devicePlatformById = useMemo(() => {
    const map = new Map<string, string>();
    (devices || []).forEach((device: any) =>
      map.set(device.deviceId, device.platform),
    );
    return map;
  }, [devices]);
  const deviceUsage: any[] = usage?.devices || EMPTY_DEVICES;
  const windowsUsage = deviceUsage.filter(
    (d: any) => devicePlatformById.get(d.deviceId) === "windows",
  );
  const windowsSeconds = windowsUsage.reduce(
    (sum: number, device: any) => sum + device.trackedSeconds,
    0,
  );
  const browserSeconds = deviceUsage
    .filter((d: any) => devicePlatformById.get(d.deviceId) === "browser")
    .reduce((sum: number, device: any) => sum + device.trackedSeconds, 0);
  // Real per-platform totals from listDevices().platform — no subtraction hack.
  const androidSeconds = deviceUsage
    .filter((d: any) => devicePlatformById.get(d.deviceId) === "android")
    .reduce((sum: number, device: any) => sum + device.trackedSeconds, 0);
  const trackedDeviceIds = new Set(
    (usage?.devices || [])
      .filter((device: any) => device.trackedSeconds > 0)
      .map((device: any) => device.deviceId),
  );
  if (snapshot?.device.id) trackedDeviceIds.add(snapshot.device.id);
  const trackedDeviceCount = trackedDeviceIds.size;
  const syncedWindowsDevice = devices.find((device: any) =>
    windowsUsage.some((source: any) => source.deviceId === device.deviceId),
  );
  const windowsLabel =
    snapshot?.device.name || syncedWindowsDevice?.name || "Windows PC";
  const sessions: WorkRecord[] = dashboard?.sessions || [];
  const [showAllHistory, setShowAllHistory] = useState(false);
  const [activeSection, setActiveSection] = useState(0);
  const ringsRef = useRef<HTMLElement | null>(null);
  const graphRef = useRef<HTMLElement | null>(null);
  const historyRef = useRef<HTMLElement | null>(null);
  // Page dots mirror the Android dashboard: overview -> focus graph -> recent work.
  useEffect(() => {
    const nodes = [ringsRef.current, graphRef.current, historyRef.current].filter(
      Boolean,
    ) as HTMLElement[];
    if (!nodes.length || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (!visible) return;
        const index = nodes.indexOf(visible.target as HTMLElement);
        if (index >= 0) setActiveSection(index);
      },
      { rootMargin: "-20% 0px -55% 0px", threshold: [0.15, 0.4, 0.75] },
    );
    nodes.forEach((node) => observer.observe(node));
    return () => observer.disconnect();
  }, []);
  const focusMinutes = Math.round((state.totalWorkSecondsToday || 0) / 60);
  const tasksDone = state.tasksCompletedToday || 0;
  const visibleRecords = showAllHistory ? records : records.slice(0, 3);
  function jumpToSection(index: number) {
    const node = [ringsRef.current, graphRef.current, historyRef.current][index];
    node?.scrollIntoView({ behavior: "smooth", block: "start" });
  }
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <p className="eyebrow">{todayLabel()}</p>
          <h1>Focus</h1>
        </div>
        <div className={`status-badge ${snapshot?.running ? "ok" : "warn"}`}>
          <span />
          {snapshot?.running ? "Protection active" : "Setup needed"}
        </div>
      </header>
      <ProtectionWarning
        trackerError={trackerError}
        snapshot={snapshot}
        status={status}
        devices={devices}
      />
      <section className="focus-overview" ref={ringsRef}>
        <div className="rings-panel">
          <ProgressRings focusMinutes={focusMinutes} tasksDone={tasksDone} />
          <div className="rings-cards">
            <TopAppCard usage={snapshot?.usage || EMPTY_USAGE} />
            <TasksCard done={tasksDone} goal={DAILY_TASKS_GOAL} />
          </div>
        </div>
        <PageDots active={activeSection} onSelect={jumpToSection} />
        <div className="overview-actions">
          <button
            className="primary-button"
            onClick={() =>
              document
                .getElementById("manual-log")
                ?.scrollIntoView({ behavior: "smooth", block: "start" })
            }
          >
            <Icon name="plus" /> Log work
          </button>
          <button className="secondary-button" onClick={() => jumpToSection(1)}>
            <Icon name="clock" /> Focus graph
          </button>
        </div>
        <button
          className="secondary-button history-jump"
          onClick={() => {
            setShowAllHistory((value) => !value);
            jumpToSection(2);
          }}
        >
          <Icon name="clock" />
          {showAllHistory ? "Show less" : "History — show all"}
        </button>
      </section>
      <section className="hero-balance">
        <div>
          <p className="section-label">Time to unwind</p>
          <div className="balance">{fmt(state.creditBalanceSeconds || 0)}</div>
          <p className="balance-caption">
            {(state.creditBalanceSeconds || 0) > 0
              ? "Ready when you are"
              : "Earn a little breathing room"}
          </p>
          <p>Focused work earns leisure time across Android, Windows, and Chrome.</p>
        </div>
        <FocusTimer dashboard={dashboard} workRatio={workRatio} />
      </section>
      <ManualWorkLog
        dashboard={dashboard}
        workRatio={workRatio}
        taskBonus={taskBonus}
      />
      <section className="device-summary">
        <div className="section-heading">
          <div>
            <p className="section-label">Screen time · {rangeLabel}</p>
            <h2>{fmt(total)}</h2>
          </div>
          <p>
            {usageRange === "today" ? "Today" : rangeLabel} across{" "}
            {trackedDeviceCount} tracked device
            {trackedDeviceCount === 1 ? "" : "s"}
          </p>
        </div>
        <div
          className="segment usage-range"
          role="tablist"
          aria-label="Usage range"
        >
          {USAGE_RANGES.map((entry) => (
            <button
              key={entry.id}
              type="button"
              role="tab"
              aria-selected={usageRange === entry.id}
              className={usageRange === entry.id ? "active" : ""}
              onClick={() => onUsageRangeChange?.(entry.id)}
            >
              {entry.label}
            </button>
          ))}
        </div>
        <p className="usage-range-note">
          Cumulative time is summed across every device, not just this PC.
        </p>
        <div className="device-split">
          <DeviceMetric
            icon="monitor"
            label={windowsLabel}
            value={fmt(windowsSeconds)}
            detail={
              snapshot?.current?.idle
                ? "Idle"
                : snapshot?.current?.browserDomain ||
                  snapshot?.current?.appName ||
                  (windowsSeconds
                    ? "Synced Windows activity"
                    : "Waiting for activity")
            }
          />
          <DeviceMetric
            icon="phone"
            label="Android"
            value={fmt(androidSeconds)}
            detail={
              androidSeconds
                ? "Synced from phone"
                : `No phone usage in ${rangeLabel.toLowerCase()}`
            }
          />
          <DeviceMetric
            icon="globe"
            label="Chrome"
            value={fmt(browserSeconds)}
            detail={
              browserSeconds
                ? "Synced from extension"
                : `No extension usage in ${rangeLabel.toLowerCase()}`
            }
          />
        </div>
        <UsageBars
          summary={usage}
          devices={devices}
          usageRange={usageRange}
          onMerge={onMerge}
        />
      </section>
      <section className="stats-row">
        <Metric
          label="Focused work"
          value={fmt(state.totalWorkSecondsToday || 0)}
        />
        <Metric
          label="Tasks finished"
          value={String(state.tasksCompletedToday || 0)}
        />
        <Metric
          label="Leisure used"
          value={fmt(state.totalScrollSecondsToday || 0)}
        />
      </section>
      <section className="content-section day-graph-section" ref={graphRef}>
        <FocusThroughDay sessions={sessions} records={records} />
      </section>
      <section className="content-section" ref={historyRef}>
        <div className="section-heading">
          <div>
            <p className="section-label">Recent work</p>
            <h2>What you earned</h2>
          </div>
          {records.length > 0 && <p>{records.length} total</p>}
        </div>
        {records.length ? (
          <div className="activity-list">
            {visibleRecords.map((r, i) => (
              <div className="activity-row" key={r.recordId || i}>
                <span className="activity-icon">
                  <Icon name="check" />
                </span>
                <div>
                  <strong>{r.title}</strong>
                  <p>
                    {r.durationMinutes} min focus ·{" "}
                    {new Date(r.timestamp).toLocaleString()} ·{" "}
                    {(r.source || "manual").toLowerCase().replaceAll("_", " ")}
                  </p>
                </div>
                <b>+{r.earnedMinutesCredited || 0}m</b>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="No focused work yet"
            body="Run a focus timer here or finish work on Android. It will appear across your connected devices."
          />
        )}
        {records.length > 3 && (
          <button
            className="secondary-button history-toggle"
            onClick={() => setShowAllHistory((value) => !value)}
          >
            {showAllHistory ? "Show less" : `Show all ${records.length}`}
          </button>
        )}
      </section>
      <ScrollBankCaption seconds={state.creditBalanceSeconds || 0} />
    </div>
  );
});

const DAILY_FOCUS_GOAL_MINUTES = 120;
const DAILY_TASKS_GOAL = 7;

function ProgressRings({
  focusMinutes,
  tasksDone,
}: {
  focusMinutes: number;
  tasksDone: number;
}) {
  const focusProgress = Math.min(
    1,
    Math.max(0, focusMinutes / DAILY_FOCUS_GOAL_MINUTES),
  );
  const tasksProgress = Math.min(1, Math.max(0, tasksDone / DAILY_TASKS_GOAL));
  const focusPct = Math.round(focusProgress * 100);
  const size = 170;
  const stroke = 13;
  const gap = 8;
  const center = size / 2;
  const outerR = center - stroke / 2 - 2;
  const innerR = outerR - stroke - gap;
  const outerC = 2 * Math.PI * outerR;
  const innerC = 2 * Math.PI * innerR;
  return (
    <div className="rings-wrap">
      <svg
        className="rings-svg"
        viewBox={`0 0 ${size} ${size}`}
        role="img"
        aria-label={`Focus ${focusPct}% of a ${DAILY_FOCUS_GOAL_MINUTES} minute goal; ${tasksDone} of ${DAILY_TASKS_GOAL} tasks done`}
      >
        <circle
          className="ring-track"
          cx={center}
          cy={center}
          r={outerR}
          strokeWidth={stroke}
          fill="none"
        />
        <circle
          className="ring-track"
          cx={center}
          cy={center}
          r={innerR}
          strokeWidth={stroke}
          fill="none"
        />
        <circle
          className="ring-focus"
          cx={center}
          cy={center}
          r={outerR}
          strokeWidth={stroke}
          fill="none"
          strokeLinecap="round"
          strokeDasharray="7 7"
          strokeDashoffset={outerC * (1 - focusProgress)}
          transform={`rotate(-90 ${center} ${center})`}
        />
        <circle
          className="ring-tasks"
          cx={center}
          cy={center}
          r={innerR}
          strokeWidth={stroke}
          fill="none"
          strokeLinecap="round"
          strokeDasharray={innerC}
          strokeDashoffset={innerC * (1 - tasksProgress)}
          transform={`rotate(-90 ${center} ${center})`}
        />
      </svg>
      <div className="rings-center">
        <strong>{focusPct}%</strong>
        <span>Focus</span>
      </div>
      <span className="rings-badge">+{tasksDone}</span>
    </div>
  );
}

// Memoized + single-pass max over today's app entries: previously this copied
// and sorted the full usage array on every render just to find the top app.
const TopAppCard = memo(function TopAppCard({
  usage,
}: {
  usage: NativeUsage[];
}) {
  const today = localDate();
  const top = useMemo(() => {
    let best: NativeUsage | null = null;
    for (const entry of usage) {
      if (entry.date !== today || entry.browserDomain) continue;
      if (!best || entry.activeSeconds > best.activeSeconds) best = entry;
    }
    return best;
  }, [usage, today]);
  return (
    <article className="overview-card top-app-card">
      <span className="overview-icon">
        <Icon name="monitor" />
      </span>
      <div>
        <strong>{top ? top.appName : "Top app"}</strong>
        <b>
          {top
            ? `${Math.round(top.activeSeconds / 60)} min`
            : tauriAvailable()
              ? "No data"
              : "Desktop app"}
        </b>
      </div>
    </article>
  );
});

function TasksCard({ done, goal }: { done: number; goal: number }) {
  return (
    <article className="overview-card tasks-card">
      <span className="overview-icon">
        <Icon name="check" />
      </span>
      <div>
        <strong>Tasks</strong>
        <b>
          {done}/{goal} done
        </b>
      </div>
    </article>
  );
}

function FocusThroughDay({
  sessions,
  records,
}: {
  sessions: WorkRecord[];
  records: WorkRecord[];
}) {
  const source = sessions.length ? sessions : records;
  const buckets = useMemo(() => {
    const hours = new Array<number>(24).fill(0);
    const today = localDate();
    for (const entry of source) {
      if (localDate(entry.timestamp) !== today) continue;
      const hour = new Date(entry.timestamp).getHours();
      hours[hour] += entry.durationMinutes || 0;
    }
    return hours;
  }, [source]);
  const max = Math.max(0, ...buckets);
  return (
    <>
      <div className="section-heading">
        <div>
          <p className="section-label">Focus through the day</p>
          <h2>{max > 0 ? `max ${max}m` : "Today"}</h2>
        </div>
      </div>
      {max <= 0 ? (
        <EmptyState
          title="No focus yet today"
          body="Log work or finish a focus timer and today's hourly focus will appear here."
        />
      ) : (
        <>
          <div
            className="day-bars"
            role="img"
            aria-label={`Focus by hour, peak ${max} minutes`}
          >
            {buckets.map((minutes, hour) => (
              <div
                className="day-bar-slot"
                key={hour}
                title={`${hour}:00 — ${minutes}m`}
              >
                <span
                  className={`day-bar ${minutes > 0 ? "has-focus" : ""}`}
                  style={{
                    height: `${minutes <= 0 ? 4 : Math.max(6, (minutes / max) * 100)}%`,
                  }}
                />
              </div>
            ))}
          </div>
          <div className="day-bar-labels">
            {["0", "6", "12", "18", "23"].map((label) => (
              <span key={label}>{label}</span>
            ))}
          </div>
        </>
      )}
    </>
  );
}

function PageDots({
  active,
  onSelect,
}: {
  active: number;
  onSelect: (index: number) => void;
}) {
  const labels = ["Overview", "Focus graph", "Recent work"];
  return (
    <div className="page-dots" role="tablist" aria-label="Focus sections">
      {labels.map((label, index) => (
        <button
          key={label}
          type="button"
          role="tab"
          aria-label={label}
          aria-selected={active === index}
          className={`page-dot ${active === index ? "active" : ""}`}
          onClick={() => onSelect(index)}
        />
      ))}
    </div>
  );
}

function ProtectionWarning({ trackerError, snapshot, status, devices }: any) {
  const issues: { key: string; title: string; body: string }[] = [];
  if (trackerError) {
    issues.push({
      key: "tracker",
      title: "Native tracking unavailable",
      body: trackerError,
    });
  }
  if (status?.lastError) {
    issues.push({
      key: "last-error",
      title: "Tracker reported a problem",
      body: String(status.lastError),
    });
  }
  if (snapshot && !snapshot.running) {
    issues.push({
      key: "paused",
      title: "Windows tracking is paused",
      body: "Turn App activity back on in Settings so today's screen time and blocks stay accurate.",
    });
  }
  const enforcement =
    status?.enforcementActive ??
    Boolean(
      snapshot?.blockedTargets &&
        ((snapshot.blockedTargets.appIds?.length || 0) > 0 ||
          (snapshot.blockedTargets.domains?.length || 0) > 0),
    );
  if (snapshot?.running && !enforcement) {
    issues.push({
      key: "enforcement",
      title: "No blocked targets enforced",
      body: "Block at least one app or website in Boundaries so Windows protection is active.",
    });
  }
  if (snapshot && !(devices?.length > 0)) {
    issues.push({
      key: "sync",
      title: "No devices synced yet",
      body: "Keep this window open while signed in to register this PC, or sign in on Android to sync.",
    });
  }
  if (!issues.length) return null;
  return (
    <section className="protection-warning" aria-live="polite">
      <span className="warning-icon">
        <Icon name="monitor" />
      </span>
      <div>
        <strong>Finish setting up protection</strong>
        <ul>
          {issues.map((issue) => (
            <li key={issue.key}>
              <b>{issue.title}.</b> {issue.body}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function ScrollBankCaption({ seconds }: { seconds: number }) {
  return (
    <div className="scroll-bank-caption">
      <Icon name="clock" size={16} />
      <span>Scroll bank (from focus): {formatBank(seconds)}</span>
    </div>
  );
}

function FocusTimer({
  dashboard,
  workRatio,
}: {
  dashboard: any;
  workRatio: number;
}) {
  const addWorkRecord = useMutation(api.focus.addWorkRecord);
  const saveState = useMutation(api.focus.saveState);
  const [minutes, setMinutes] = useState(25);
  const [left, setLeft] = useState(25 * 60);
  const [running, setRunning] = useState(false);
  const [saved, setSaved] = useState(false);
  // Mirror latest props in refs so the completion effect never uses a stale closure.
  const dashRef = useRef(dashboard);
  dashRef.current = dashboard;
  const ratioRef = useRef(workRatio);
  ratioRef.current = workRatio;
  useEffect(() => {
    if (!running) setLeft(minutes * 60);
  }, [minutes, running]);
  useEffect(() => {
    if (!running) return;
    const id = window.setInterval(
      () => setLeft((v) => Math.max(0, v - 1)),
      1000,
    );
    return () => clearInterval(id);
  }, [running]);
  // NOTE: the backend has no atomic credit-increment mutation (saveState is LWW
  // full-replace), so a concurrent phone write can still win. Mitigation: a single
  // saveState built from the freshest subscribed snapshot, idempotent recordId,
  // updatedAt=max(now, remote). Timer preserves tasksCompletedToday (tasks come
  // from the manual log / mobile); it only banks earned time + work seconds.
  useEffect(() => {
    if (left !== 0 || saved) return;
    setRunning(false);
    setSaved(true);
    const ratio = Math.max(1, ratioRef.current || 4);
    const earned = Math.floor(minutes / ratio);
    const now = Date.now();
    const recordId = `windows_${now}`;
    const latest = dashRef.current?.state || {};
    const updatedAt = Math.max(now, latest.updatedAt || 0);
    addWorkRecord({
      recordId,
      title: "Desktop focus",
      durationMinutes: minutes,
      timestamp: now,
      source: "DESKTOP_TIMER",
      earnedMinutesCredited: earned,
    })
      .then(() =>
        saveState({
          creditBalanceSeconds:
            (latest.creditBalanceSeconds || 0) + earned * 60,
          totalWorkSecondsToday:
            (latest.totalWorkSecondsToday || 0) + minutes * 60,
          totalScrollSecondsToday: latest.totalScrollSecondsToday || 0,
          tasksCompletedToday: latest.tasksCompletedToday || 0,
          lastResetDate: localDate(now),
          updatedAt,
        }),
      )
      .catch((err) => console.warn("[focuslock] focus timer save failed", err));
  }, [left, saved, minutes]);
  const mm = Math.floor(left / 60),
    ss = left % 60;
  return (
    <div className="timer-panel">
      <div className="timer-presets">
        {[15, 25, 50].map((m) => (
          <button
            key={m}
            className={minutes === m ? "active" : ""}
            disabled={running}
            onClick={() => {
              setMinutes(m);
              setSaved(false);
            }}
          >
            {m}m
          </button>
        ))}
      </div>
      <div className="timer-value">
        {String(mm).padStart(2, "0")}:{String(ss).padStart(2, "0")}
      </div>
      <button
        className="primary-button"
        onClick={() => {
          if (left === 0) {
            setLeft(minutes * 60);
            setSaved(false);
          }
          setRunning(!running);
        }}
      >
        <Icon name={running ? "pause" : "play"} />
        {running ? "Pause" : left === 0 ? "Start again" : "Start focus"}
      </button>
    </div>
  );
}

function ManualWorkLog({
  dashboard,
  workRatio,
  taskBonus,
}: {
  dashboard: any;
  workRatio: number;
  taskBonus: number;
}) {
  const addWorkRecord = useMutation(api.focus.addWorkRecord);
  const logFocusSession = useMutation(api.focus.logFocusSession);
  const saveState = useMutation(api.focus.saveState);
  const [minsText, setMinsText] = useState("25");
  const [tasksText, setTasksText] = useState("0");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const dashRef = useRef(dashboard);
  dashRef.current = dashboard;
  async function submit() {
    const mins = Math.max(1, Math.floor(Number(minsText) || 0));
    if (!mins) return;
    const taskCount = Math.max(0, Math.floor(Number(tasksText) || 0));
    setBusy(true);
    setDone(null);
    try {
      const now = Date.now();
      const id = `windows_manual_${now}`;
      const earned =
        Math.floor(mins / Math.max(1, workRatio || 4)) +
        taskCount * Math.max(0, taskBonus || 0);
      const latest = dashRef.current?.state || {};
      const updatedAt = Math.max(now, latest.updatedAt || 0);
      const label = title.trim() || "Manual work log";
      await addWorkRecord({
        recordId: id,
        title: label,
        durationMinutes: mins,
        timestamp: now,
        source: "DESKTOP_MANUAL",
        earnedMinutesCredited: earned,
      });
      try {
        await logFocusSession({
          sessionId: id,
          title: label,
          durationMinutes: mins,
          timestamp: now,
          source: "DESKTOP_MANUAL",
          earnedMinutesCredited: earned,
        });
      } catch (err) {
        console.warn(
          "[focuslock] logFocusSession failed (work record kept)",
          err,
        );
      }
      await saveState({
        creditBalanceSeconds: (latest.creditBalanceSeconds || 0) + earned * 60,
        totalWorkSecondsToday: (latest.totalWorkSecondsToday || 0) + mins * 60,
        totalScrollSecondsToday: latest.totalScrollSecondsToday || 0,
        tasksCompletedToday: (latest.tasksCompletedToday || 0) + taskCount,
        lastResetDate: localDate(now),
        updatedAt,
      });
      setDone(
        `Logged ${mins}m${taskCount ? ` + ${taskCount} task${taskCount === 1 ? "" : "s"}` : ""} → +${earned}m earned.`,
      );
    } catch (err) {
      console.warn("[focuslock] manual work log failed", err);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="content-section" id="manual-log">
      <div className="section-heading">
        <div>
          <p className="section-label">Manual log</p>
          <h2>Log work directly</h2>
        </div>
        <p>Same path as the timer — no TickTick needed on PC.</p>
      </div>
      <div className="boundary-toolbar">
        <label className="search">
          <Icon name="clock" />
          <input
            value={minsText}
            onChange={(e) => setMinsText(e.target.value)}
            inputMode="numeric"
            placeholder="Minutes"
            aria-label="Work minutes"
          />
        </label>
        <label className="search">
          <Icon name="check" />
          <input
            value={tasksText}
            onChange={(e) => setTasksText(e.target.value)}
            inputMode="numeric"
            placeholder="Tasks"
            aria-label="Tasks finished"
          />
        </label>
      </div>
      <div className="boundary-toolbar">
        <label className="search">
          <Icon name="plus" />
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="What did you work on? (optional)"
            aria-label="Work title"
          />
        </label>
        <button className="primary-button" onClick={submit} disabled={busy}>
          {busy ? "Logging…" : "Log work"}
        </button>
      </div>
      {done && <p className="balance-caption">{done}</p>}
    </section>
  );
}

function DeviceMetric({ icon, label, value, detail }: any) {
  return (
    <article className="device-card">
      <span className="device-icon">
        <Icon name={icon} />
      </span>
      <div>
        <p>{label}</p>
        <strong>{value}</strong>
        <small>{detail}</small>
      </div>
    </article>
  );
}
function Metric({ label, value }: { label: string; value: string }) {
  return (
    <article>
      <p>{label}</p>
      <strong>{value}</strong>
    </article>
  );
}
function UsageBars({ summary, devices, usageRange = "today", onMerge }: any) {
  // deviceId -> name lookup built once per devices/summary change instead of
  // a devices.find per deviceIds entry per render.
  const deviceNameById = useMemo(() => {
    const map = new Map<string, string>();
    (devices || []).forEach((device: any) =>
      map.set(device.deviceId, device.name),
    );
    return map;
  }, [devices]);
  // groupedTargets is the merge-aware view (groups first, then ungrouped
  // targets). Fall back to `targets` so the app still works against an older
  // deployment that predates groups.
  const rows: any[] = useMemo(() => {
    const grouped = summary?.groupedTargets;
    if (Array.isArray(grouped) && grouped.length) return grouped;
    return summary?.targets || EMPTY_TARGETS;
  }, [summary]);
  const groupById = useMemo(() => {
    const map = new Map<string, any>();
    (summary?.groups || []).forEach((group: any) => map.set(group.groupId, group));
    return map;
  }, [summary]);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const top = useMemo(() => rows.slice(0, 6), [rows]);
  const max = Math.max(1, ...top.map((t: any) => t.trackedSeconds || 0));
  const rangeIsToday = usageRange === "today";
  function deviceNames(ids: string[] | undefined): string {
    const names = (ids || []).map(
      (id) => deviceNameById.get(id) || "Unknown device",
    );
    return names.length ? names.join(" + ") : "No device data";
  }
  return (
    <div className="usage-list">
      {top.map((t: any) => {
        const rowKey = `${t.targetKind}:${t.targetKey}`;
        const isGroup = t.targetKind === "group";
        const group = isGroup ? groupById.get(t.groupId) : undefined;
        const limitMinutes = Number(t.dailyLimitMinutes) || 0;
        const hasLimit = limitMinutes > 0 && t.limitEnabled !== false;
        const usedMinutes = Math.round((t.trackedSeconds || 0) / 60);
        const exceeded =
          rangeIsToday && hasLimit && (t.trackedSeconds || 0) >= limitMinutes * 60;
        const isOpen = Boolean(expanded[rowKey]);
        return (
          <div
            className={`usage-row ${exceeded ? "limit-exceeded" : ""}`}
            key={rowKey}
          >
            <span className="target-icon">
              <Icon
                name={
                  isGroup
                    ? "grid"
                    : t.targetKind === "website"
                      ? "globe"
                      : "monitor"
                }
              />
            </span>
            <div>
              <div className="usage-copy">
                <strong>
                  {isGroup ? (
                    <button
                      type="button"
                      className="group-toggle"
                      aria-expanded={isOpen}
                      onClick={() =>
                        setExpanded((value) => ({
                          ...value,
                          [rowKey]: !value[rowKey],
                        }))
                      }
                    >
                      {t.targetLabel}
                      <span className="merge-badge">
                        merged · {t.memberCount || (t.memberKeys || []).length}
                      </span>
                    </button>
                  ) : (
                    <span className="usage-label">{t.targetLabel}</span>
                  )}
                  {/* Groups cannot be members of another group, so the merge
                      entry point exists on app/website rows only. */}
                  {!isGroup && onMerge && (
                    <button
                      type="button"
                      className="merge-row-button"
                      title={`Merge ${t.targetLabel} into a new bucket in Boundaries`}
                      aria-label={`Merge ${t.targetLabel} into a new bucket in Boundaries`}
                      onClick={() =>
                        onMerge(
                          mergeMemberFromUsageRow(t),
                          usageRangeLabel(usageRange),
                        )
                      }
                    >
                      Merge…
                    </button>
                  )}
                  {hasLimit && (
                    <span className={`limit-pill ${exceeded ? "exceeded" : ""}`}>
                      {rangeIsToday
                        ? `${usedMinutes}m / ${limitMinutes}m`
                        : `limit ${limitMinutes}m/day`}
                    </span>
                  )}
                </strong>
                <span>{fmt(t.trackedSeconds)}</span>
              </div>
              <div className="bar">
                <i
                  style={{
                    width: `${((t.trackedSeconds || 0) / max) * 100}%`,
                  }}
                />
              </div>
              <small className="device-chips">
                {(t.deviceIds || []).map((id: string) => (
                  <span className="device-chip" key={id}>
                    {deviceNameById.get(id) || "Unknown device"}
                  </span>
                ))}
                {!(t.deviceIds || []).length && (
                  <span className="device-chip">No device data</span>
                )}
              </small>
              {isGroup && isOpen && (
                <div className="usage-members">
                  {(group?.members || []).map((member: any) => (
                    <div
                      className="usage-member"
                      key={`${member.targetKind}:${member.targetKey}`}
                    >
                      <span className="usage-member-name">
                        <Icon
                          name={member.targetKind === "website" ? "globe" : "monitor"}
                          size={14}
                        />
                        {member.targetLabel}
                      </span>
                      <span>
                        {fmt(member.trackedSeconds)} · {deviceNames(member.deviceIds)}
                      </span>
                    </div>
                  ))}
                  {!group?.members?.length && (
                    <div className="usage-member">
                      <span className="usage-member-name">No members</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        );
      })}
      {!top.length && (
        <EmptyState
          title="No tracked activity yet"
          body="Use another app for a minute. FocusLock will list it here with its device and source."
        />
      )}
    </div>
  );
}

// Server replace semantics (delete+insert): upload the server-known list with the
// toggle applied. Pure-observed, never-synced, unblocked entries stay local-only so
// every Windows-observed app is not persisted as isBlocked:false (list pollution).
function serverAppsForUpload(known: AppItem[], toggled: AppItem[]) {
  const map = new Map(known.map((a) => [a.packageName, a]));
  for (const t of toggled) map.set(t.packageName, t);
  return [...map.values()].map(
    ({ packageName, appName, isBlocked, category, specificShortsOnly }) => ({
      packageName,
      appName,
      isBlocked,
      category,
      specificShortsOnly,
    }),
  );
}

type BoundaryAppRow = {
  key: string;
  name: string;
  category: string;
  isBlocked: boolean;
  minutes: number;
  source: "windows" | "android";
  native: boolean;
  // Desktop has no Android-style system flag. "system" means an app seen only by
  // the Windows tracker with no tracked time today; it is hidden unless the
  // System toggle is on or the app is blocked, mirroring Android's system filter.
  system: boolean;
};

const BOUNDARY_DOMAIN_REGEX =
  /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;

/** Trim a pasted URL/domain down to a bare hostname, or null when invalid. */
function normalizeDomainInput(raw: string): string | null {
  let value = raw.trim().toLowerCase();
  if (!value) return null;
  if (value.includes("://")) value = value.split("://")[1] || value;
  value = value.split("/")[0].split("?")[0].split("#")[0];
  if (value.includes("@")) value = value.split("@").pop() || value;
  value = value.split(":")[0];
  // www-stripping goes through the shared helper; `m.` is an input nicety only.
  value = normalizeSiteKey(value).replace(/^m\./, "");
  value = value.replace(/^[.\-_ ]+|[.\-_ ]+$/g, "");
  return BOUNDARY_DOMAIN_REGEX.test(value) ? value : null;
}

function stripSite(site: SiteItem): SiteItem {
  return {
    domain: site.domain,
    displayName: site.displayName,
    isBlocked: site.isBlocked,
    category: site.category,
    isCustom: site.isCustom,
  };
}

// --- Merged buckets (groups) -------------------------------------------------

type GroupDraft = {
  groupId: string | null; // null = creating
  name: string;
  limitMinutes: string; // free text; "" = no daily limit
  members: TargetGroupMember[];
};

/** `"kind:key"` — the identity Convex stores for group members (website keys
 * canonicalized via normalizeSiteKey, app keys only lowercased). */
export function memberKeyOf(member: { targetKind: string; targetKey: string }): string {
  return `${member.targetKind}:${targetKeyFor(member.targetKind, member.targetKey)}`;
}

function groupMemberFromApp(row: { key: string; name: string }): TargetGroupMember {
  return {
    targetKind: "app",
    targetKey: row.key.toLowerCase(),
    targetLabel: row.name,
  };
}

function groupMemberFromSite(site: SiteItem): TargetGroupMember {
  return {
    targetKind: "website",
    targetKey: normalizeSiteKey(site.domain),
    targetLabel: site.displayName || site.domain,
  };
}

// A picker candidate is a group member plus optional device provenance (only
// the server's listKnownTargets carries it, so local-only rows omit it).
type GroupCandidate = TargetGroupMember & { devices?: KnownTargetDevice[] };

/**
 * Picker candidates from every source, deduped by memberKeyOf.
 *
 * Priority: remote `knownTargets` first (all-time, every device), so a target
 * that only ever existed on another device is a first-class candidate and the
 * version carrying device provenance wins the dedupe. Local sources only fill
 * gaps afterwards; draft members go last so a manually typed key that matches
 * nothing else still appears in the list as picked.
 */
export function buildGroupCandidates({
  knownTargets,
  appRows,
  sites,
  usageTargets,
  draftMembers,
}: {
  knownTargets?: KnownTarget[] | null;
  appRows: { key: string; name: string }[];
  sites: SiteItem[];
  usageTargets?:
    | { targetKind: string; targetKey: string; targetLabel: string }[]
    | null;
  draftMembers?: TargetGroupMember[] | null;
}): GroupCandidate[] {
  const map = new Map<string, GroupCandidate>();
  (knownTargets || []).forEach((target) => {
    const member: GroupCandidate = {
      targetKind: target.targetKind,
      targetKey: targetKeyFor(target.targetKind, target.targetKey),
      targetLabel: target.targetLabel,
      devices: target.devices || [],
    };
    map.set(memberKeyOf(member), member);
  });
  const addIfAbsent = (member: GroupCandidate) => {
    const key = memberKeyOf(member);
    if (!map.has(key)) map.set(key, member);
  };
  appRows.forEach((row) => addIfAbsent(groupMemberFromApp(row)));
  sites.forEach((site) => addIfAbsent(groupMemberFromSite(site)));
  (usageTargets || []).forEach((target) => {
    if (target.targetKind === "group") return;
    addIfAbsent({
      targetKind: target.targetKind as "app" | "website",
      targetKey: targetKeyFor(target.targetKind, target.targetKey),
      targetLabel: target.targetLabel,
    });
  });
  (draftMembers || []).forEach((member) => addIfAbsent(member));
  return [...map.values()].sort((a, b) =>
    a.targetLabel.toLowerCase().localeCompare(b.targetLabel.toLowerCase()),
  );
}

/**
 * Manual "add any key by hand" entry. Normalizes exactly like the server
 * (websites drop a leading `www.` and lowercase; apps lowercase) and returns
 * null when nothing usable remains, so whitespace-only input cannot be added.
 */
export function manualMemberFor(
  kind: "app" | "website",
  typed: string,
): TargetGroupMember | null {
  const raw = String(typed || "").trim();
  const targetKey = targetKeyFor(kind, raw);
  if (!targetKey) return null;
  return { targetKind: kind, targetKey, targetLabel: raw || targetKey };
}

/**
 * Add/remove by memberKeyOf identity — the same dedupe the picker and the chip
 * row use, so a manually typed key can never duplicate an existing candidate.
 */
export function toggleMemberInList(
  members: TargetGroupMember[],
  member: TargetGroupMember,
): TargetGroupMember[] {
  const key = memberKeyOf(member);
  return members.some((entry) => memberKeyOf(entry) === key)
    ? members.filter((entry) => memberKeyOf(entry) !== key)
    : [...members, member];
}

/**
 * Maps a usage row to the member handed to `onMerge`. Canonical rows pass
 * through unchanged; the normalization is only a defensive no-op.
 */
export function mergeMemberFromUsageRow(row: {
  targetKind: string;
  targetKey: string;
  targetLabel: string;
}): TargetGroupMember {
  return {
    targetKind: row.targetKind === "website" ? "website" : "app",
    targetKey: targetKeyFor(row.targetKind, row.targetKey),
    targetLabel: row.targetLabel,
  };
}

/** Exact saveGroups validator shape (drops `updatedAt` and unknown fields). */
function groupForSave(group: TargetGroup) {
  return {
    groupId: group.groupId,
    name: group.name,
    category: group.category,
    members: group.members.map((member) => ({
      targetKind: member.targetKind,
      targetKey: member.targetKey,
      targetLabel: member.targetLabel,
    })),
    dailyLimitMinutes: group.dailyLimitMinutes,
    limitEnabled: group.limitEnabled,
  };
}

function BoundariesPage({
  dashboard,
  snapshot,
  usage,
  todayUsage,
  boundariesLock = false,
  knownTargets,
  initialDraftMembers,
  mergeSourceLabel,
  onInitialDraftConsumed,
}: any) {
  const saveApps = useMutation(api.focus.saveBlockedApps);
  const saveSites = useMutation(api.focus.saveBlockedWebsites);
  const saveGroups = useMutation(syncApi.saveGroups);
  const apps: AppItem[] = dashboard?.apps || [];
  const sites: SiteItem[] = dashboard?.sites || [];
  // Full group list (editable source of truth). The range summary below only
  // supplies tracked seconds; listGroups keeps groups that have zero usage.
  const remoteGroups: TargetGroup[] | undefined = useQuery(
    syncApi.listGroups,
    EMPTY_ARGS,
  ) as any;
  const groups: TargetGroup[] = remoteGroups || EMPTY_GROUPS;

  const [kind, setKind] = useState<"apps" | "sites">("apps");
  const [q, setQ] = useState("");
  const [debouncedQ, setDebouncedQ] = useState("");
  const [category, setCategory] = useState("All");
  const [showSystem, setShowSystem] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [showAddSite, setShowAddSite] = useState(false);
  const [siteInput, setSiteInput] = useState("");
  const [siteError, setSiteError] = useState<string | null>(null);
  // Groups: draft dialog (create when groupId is null), merge multi-select and
  // the non-blocking LWW notice.
  const [groupDraft, setGroupDraft] = useState<GroupDraft | null>(null);
  const [groupError, setGroupError] = useState<string | null>(null);
  const [groupNotice, setGroupNotice] = useState<string | null>(null);
  const [pickerQuery, setPickerQuery] = useState("");
  const [selectMode, setSelectMode] = useState(false);
  const [selectedMembers, setSelectedMembers] = useState<TargetGroupMember[]>([]);
  // Manual "add any key" entry inside the picker area (kind + raw key).
  const [manualKind, setManualKind] = useState<"app" | "website">("app");
  const [manualKey, setManualKey] = useState("");
  // Context line for a draft opened via the Focus page's "Merge…" action.
  const [mergeNote, setMergeNote] = useState<string | null>(null);

  const selectionKeys = useMemo(
    () => new Set(selectedMembers.map(memberKeyOf)),
    [selectedMembers],
  );

  function toggleMemberSelection(member: TargetGroupMember) {
    const key = memberKeyOf(member);
    setSelectedMembers((current) =>
      current.some((entry) => memberKeyOf(entry) === key)
        ? current.filter((entry) => memberKeyOf(entry) !== key)
        : [...current, member],
    );
  }

  function openCreateGroup(members: TargetGroupMember[] = []) {
    setGroupError(null);
    setPickerQuery("");
    setMergeNote(null);
    setGroupDraft({
      groupId: null,
      name: "",
      limitMinutes: "",
      members: members.map((member) => ({ ...member })),
    });
  }

  function openEditGroup(group: TargetGroup) {
    setGroupError(null);
    setPickerQuery("");
    setMergeNote(null);
    setGroupDraft({
      groupId: group.groupId,
      name: group.name,
      limitMinutes: group.dailyLimitMinutes
        ? String(group.dailyLimitMinutes)
        : "",
      members: group.members.map((member) => ({ ...member })),
    });
  }

  function toggleDraftMember(member: TargetGroupMember) {
    setGroupDraft((draft) =>
      draft
        ? { ...draft, members: toggleMemberInList(draft.members, member) }
        : draft,
    );
    setGroupError(null);
  }

  // Manual entry: normalize the typed key and toggle it in like any candidate,
  // so a target that is only visible on another device can still be merged.
  function submitManualMember() {
    const member = manualMemberFor(manualKind, manualKey);
    if (!member) return;
    toggleDraftMember(member);
    setManualKey("");
  }

  // The Focus page's "Merge…" action parks one target in DesktopApp and
  // switches here. Open the create dialog pre-filled with it, show which view
  // it came from, and clear the handoff immediately so closing the dialog
  // never re-opens it. openCreateGroup is recreated per render and must not be
  // a dependency.
  useEffect(() => {
    if (!initialDraftMembers?.length) return;
    openCreateGroup(initialDraftMembers);
    setMergeNote(mergeSourceLabel || null);
    onInitialDraftConsumed?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialDraftMembers, mergeSourceLabel, onInitialDraftConsumed]);

  // Full-replace save: always send the whole list with a fresh updatedAt.
  async function persistGroups(next: TargetGroup[], message: string) {
    setBusy(true);
    setGroupError(null);
    try {
      const result = (await saveGroups({
        groups: next.map(groupForSave),
        updatedAt: Date.now(),
      })) as { applied?: boolean } | undefined;
      if (result && result.applied === false) {
        // Another device wrote newer data. listGroups is reactive, so the list
        // has already reloaded — surface it without blocking.
        setGroupNotice("Updated on another device — reloaded.");
        setGroupDraft(null);
        return;
      }
      setGroupNotice(message);
      setGroupDraft(null);
      setSelectedMembers([]);
    } catch (e) {
      setGroupError(`Couldn't save groups: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function submitGroup() {
    if (!groupDraft) return;
    const name = groupDraft.name.trim();
    if (!name) {
      setGroupError("Give the group a name.");
      return;
    }
    // A target may live in only one group, so drop members already claimed by
    // another group (they were disabled in the picker; this is a stale-data
    // guard). Mirrors the server canonicalization.
    const available = groupDraft.members.filter((member) => {
      const owner = memberOwnerByKey.get(memberKeyOf(member));
      return !owner || owner.groupId === groupDraft.groupId;
    });
    if (available.length < 2) {
      setGroupError(
        available.length < groupDraft.members.length
          ? "Some members already belong to another group — pick at least 2 available members."
          : "Pick at least 2 members to merge.",
      );
      return;
    }
    const limitValue = Math.floor(Number(groupDraft.limitMinutes));
    const hasLimit = Number.isFinite(limitValue) && limitValue > 0;
    const entry: TargetGroup = {
      groupId:
        groupDraft.groupId ||
        (window.crypto?.randomUUID
          ? `group-${window.crypto.randomUUID()}`
          : `group-${Date.now().toString(36)}`),
      name,
      members: available,
      dailyLimitMinutes: hasLimit ? Math.min(1440, limitValue) : undefined,
      limitEnabled: hasLimit ? true : undefined,
    };
    const editing = Boolean(groupDraft.groupId);
    const next = editing
      ? groups.map((group) =>
          group.groupId === groupDraft.groupId ? entry : group,
        )
      : [...groups, entry];
    await persistGroups(next, editing ? `Updated ${name}.` : `Created ${name}.`);
  }

  function deleteGroup(group: TargetGroup) {
    void persistGroups(
      groups.filter((item) => item.groupId !== group.groupId),
      `Deleted ${group.name}.`,
    );
  }

  // Group stats and limit badges follow today (daily limits are daily), not the
  // Focus range selector; the range summary is the fallback while today's
  // summary is still loading.
  const statsSummary = todayUsage?.groups?.length ? todayUsage : usage;

  // Tracked seconds per member, from today's (or range) usage summary.
  const trackedSecondsByMember = useMemo(() => {
    const map = new Map<string, number>();
    (statsSummary?.groups || []).forEach((group: any) => {
      (group.members || []).forEach((member: any) =>
        map.set(memberKeyOf(member), member.trackedSeconds || 0),
      );
    });
    (statsSummary?.groupedTargets || []).forEach((target: any) => {
      if (target.targetKind === "group") return;
      const key = memberKeyOf(target);
      if (!map.has(key)) map.set(key, target.trackedSeconds || 0);
    });
    return map;
  }, [statsSummary]);

  const groupSummaryById = useMemo(() => {
    const map = new Map<string, any>();
    (statsSummary?.groups || []).forEach((group: any) =>
      map.set(group.groupId, group),
    );
    return map;
  }, [statsSummary]);

  // Which group already owns a target — the server allows at most one.
  const memberOwnerByKey = useMemo(() => {
    const map = new Map<string, { groupId: string; name: string }>();
    groups.forEach((group) => {
      (group.members || []).forEach((member) =>
        map.set(memberKeyOf(member), {
          groupId: group.groupId,
          name: group.name,
        }),
      );
    });
    return map;
  }, [groups]);

  // Debounced (250ms) search so typing never re-filters the list per keystroke.
  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedQ(q), 250);
    return () => window.clearTimeout(id);
  }, [q]);

  // Only the usage array — not the whole snapshot object (which also carries
  // the per-sample "current" observation) — feeds the rows, so memo identities
  // stay stable while tracking is idle.
  const nativeUsage: NativeUsage[] = snapshot?.usage || EMPTY_USAGE;

  const appRows: BoundaryAppRow[] = useMemo(() => {
    const today = localDate();
    const minutes = new Map<string, number>();
    nativeUsage
      .filter((u: NativeUsage) => !u.browserDomain)
      .forEach((u: NativeUsage) => {
        if (u.date !== today) return;
        minutes.set(u.appId, (minutes.get(u.appId) || 0) + u.activeSeconds);
      });
    const byKey = new Map<string, BoundaryAppRow>();
    // 1. Apps the Windows tracker actually observed, with real minutes today.
    nativeUsage
      .filter((u: NativeUsage) => !u.browserDomain)
      .forEach((u: NativeUsage) => {
        byKey.set(u.appId, {
          key: u.appId,
          name: u.appName,
          category: "Windows",
          isBlocked: false,
          minutes: Math.round((minutes.get(u.appId) || 0) / 60),
          source: "windows",
          native: true,
          system: false,
        });
      });
    // 2. Synced selections from Android/Convex override observation metadata.
    apps.forEach((a) => {
      const existing = byKey.get(a.packageName);
      byKey.set(a.packageName, {
        key: a.packageName,
        name: a.appName,
        category: a.category || existing?.category || "Apps",
        isBlocked: a.isBlocked,
        minutes: existing?.minutes ?? 0,
        source: existing ? "windows" : "android",
        native: Boolean(existing),
        system: false,
      });
    });
    // Anything seen only once on Windows with no time today is system/idle noise.
    const known = new Set(apps.map((a) => a.packageName));
    byKey.forEach((row) => {
      if (!known.has(row.key) && row.minutes === 0) row.system = true;
    });
    return [...byKey.values()].sort((a, b) =>
      a.name.toLowerCase().localeCompare(b.name.toLowerCase()),
    );
  }, [apps, nativeUsage]);

  const siteMinutes = useMemo(() => {
    const today = localDate();
    const map = new Map<string, number>();
    nativeUsage
      .filter((u: NativeUsage) => Boolean(u.browserDomain))
      .forEach((u: NativeUsage) => {
        if (u.date !== today || !u.browserDomain) return;
        const key = normalizeSiteKey(u.browserDomain);
        map.set(key, (map.get(key) || 0) + u.activeSeconds);
      });
    return map;
  }, [nativeUsage]);

  const categories = useMemo(() => {
    const source: { category: string }[] = kind === "apps" ? appRows : sites;
    const distinct = [...new Set(source.map((row) => row.category).filter(Boolean))].sort();
    return ["All", "Blocked", ...distinct];
  }, [kind, appRows, sites]);

  const filteredApps = useMemo(() => {
    const needle = debouncedQ.toLowerCase();
    return appRows.filter((row) => {
      const matchesSearch =
        !needle ||
        row.name.toLowerCase().includes(needle) ||
        row.key.toLowerCase().includes(needle);
      const matchesCategory =
        category === "All" ||
        (category === "Blocked" ? row.isBlocked : row.category === category);
      const matchesSystem = showSystem || !row.system || row.isBlocked;
      return matchesSearch && matchesCategory && matchesSystem;
    });
  }, [appRows, debouncedQ, category, showSystem]);

  const filteredSites = useMemo(() => {
    const needle = debouncedQ.toLowerCase();
    return sites.filter((site) => {
      const matchesSearch =
        !needle ||
        site.domain.toLowerCase().includes(needle) ||
        site.displayName.toLowerCase().includes(needle);
      const matchesCategory =
        category === "All" ||
        (category === "Blocked" ? site.isBlocked : site.category === category);
      return matchesSearch && matchesCategory;
    });
  }, [sites, debouncedQ, category]);

  const blockedAppCount = appRows.filter((row) => row.isBlocked).length;
  const blockedSiteCount = sites.filter((site) => site.isBlocked).length;

  // Picker candidates: every known target from every device (server, all-time)
  // seeded first so remote-only targets carry device provenance, then
  // boundaries apps/sites and the current range summary as local fallbacks.
  const groupCandidates = useMemo(
    () =>
      buildGroupCandidates({
        knownTargets,
        appRows,
        sites,
        usageTargets: usage?.groupedTargets,
        draftMembers: groupDraft?.members,
      }),
    [knownTargets, appRows, sites, usage, groupDraft?.members],
  );

  const pickerCandidates = useMemo(() => {
    const needle = pickerQuery.trim().toLowerCase();
    if (!needle) return groupCandidates;
    return groupCandidates.filter(
      (member) =>
        member.targetLabel.toLowerCase().includes(needle) ||
        member.targetKey.toLowerCase().includes(needle) ||
        (member.devices || []).some((device) =>
          device.name.toLowerCase().includes(needle),
        ),
    );
  }, [groupCandidates, pickerQuery]);

  function toAppItems(rows: BoundaryAppRow[]): AppItem[] {
    return rows.map((row) => ({
      packageName: row.key,
      appName: row.name,
      isBlocked: row.isBlocked,
      category: row.category,
    }));
  }

  function changeKind(next: "apps" | "sites") {
    setKind(next);
    setQ("");
    setDebouncedQ("");
    setCategory("All");
    setNotice(null);
  }

  // Boundaries Lock genuinely blocks removal/unblocking while ON, mirroring Android.
  function guardUnlock(isUnblocking: boolean): boolean {
    if (boundariesLock && isUnblocking) {
      setNotice("Boundaries Lock is ON — turn it off in Settings to change this.");
      return false;
    }
    return true;
  }
  async function applyNative(nextApps: BoundaryAppRow[], nextSites: SiteItem[]) {
    if (!tauriAvailable()) return;
    // Send the SAME union the enforcement effect sends (dashboard blocks +
    // exhausted limits/groups), with the in-flight toggle folded into the
    // dashboard shape. A base-only payload here replaced Rust's blocked-target
    // set and transiently dropped over-limit groups until the next snapshot
    // tick (~5s).
    const { targets } = evaluateBlockedTargets({
      dashboard: {
        ...(dashboard || {}),
        apps: toAppItems(nextApps),
        sites: nextSites.map(stripSite),
      },
      groups,
      summary: todayUsage,
      usage: snapshot?.usage || EMPTY_USAGE,
    });
    await invoke("set_blocked_targets", { targets }).catch(() => undefined);
  }

  async function persistApps(toggled: BoundaryAppRow[], nextApps: BoundaryAppRow[], message: string) {
    setBusy(true);
    try {
      // Upload only server-known rows plus the toggled targets so observed-only
      // Windows apps are never persisted as isBlocked:false (list pollution).
      const result = await saveApps({
        apps: serverAppsForUpload(apps, toAppItems(toggled)),
        updatedAt: Date.now(),
      });
      if (result?.applied === false) throw new Error("Another device updated your app boundaries. Reload and retry.");
      await applyNative(nextApps, sites);
      setNotice(message);
    } catch (e) {
      setNotice(`Couldn't save changes: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function persistSites(nextSites: SiteItem[], message: string) {
    setBusy(true);
    try {
      const result = await saveSites({
        sites: nextSites.map(stripSite),
        updatedAt: Date.now(),
      });
      if (result?.applied === false) throw new Error("Another device updated your websites. Reload and retry.");
      await applyNative(appRows, nextSites);
      setNotice(message);
    } catch (e) {
      setNotice(`Couldn't save changes: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function toggleApp(row: BoundaryAppRow) {
    if (row.isBlocked && !guardUnlock(true)) return;
    const toggled = { ...row, isBlocked: !row.isBlocked };
    const nextApps = appRows.map((item) => (item.key === row.key ? toggled : item));
    await persistApps(
      [toggled],
      nextApps,
      `${toggled.isBlocked ? "Blocked" : "Allowed"} ${row.name}.`,
    );
  }

  async function toggleSite(site: SiteItem) {
    if (site.isBlocked && !guardUnlock(true)) return;
    const nextSites = sites.map((item) =>
      item.domain === site.domain ? { ...item, isBlocked: !item.isBlocked } : item,
    );
    await persistSites(
      nextSites,
      `${nextSites.find((s) => s.domain === site.domain)?.isBlocked ? "Blocked" : "Allowed"} ${site.domain}.`,
    );
  }

  async function blockAppsInCategories(cats: string[], label: string) {
    const targets = appRows.filter(
      (row) => cats.includes(row.category) && !row.isBlocked,
    );
    if (!targets.length) {
      setNotice(`No ${label} apps to block.`);
      return;
    }
    const toggled = targets.map((row) => ({ ...row, isBlocked: true }));
    const toggledKeys = new Set(toggled.map((row) => row.key));
    const nextApps = appRows.map((row) =>
      toggledKeys.has(row.key) ? { ...row, isBlocked: true } : row,
    );
    await persistApps(toggled, nextApps, `Blocked ${toggled.length} ${label} app(s).`);
  }

  async function blockSitesInCategories(cats: string[], label: string) {
    const targets = sites.filter(
      (site) => cats.includes(site.category) && !site.isBlocked,
    );
    if (!targets.length) {
      setNotice(`No ${label} websites to block.`);
      return;
    }
    const nextSites = sites.map((site) =>
      cats.includes(site.category) ? { ...site, isBlocked: true } : site,
    );
    await persistSites(nextSites, `Blocked ${targets.length} ${label} site(s).`);
  }

  async function unblockAllApps() {
    if (!guardUnlock(true)) return;
    const targets = appRows.filter((row) => row.isBlocked);
    if (!targets.length) {
      setNotice("No blocked apps to unblock.");
      return;
    }
    const toggled = targets.map((row) => ({ ...row, isBlocked: false }));
    const toggledKeys = new Set(toggled.map((row) => row.key));
    const nextApps = appRows.map((row) =>
      toggledKeys.has(row.key) ? { ...row, isBlocked: false } : row,
    );
    await persistApps(toggled, nextApps, `Unblocked ${toggled.length} app(s).`);
  }

  async function unblockAllSites() {
    if (!guardUnlock(true)) return;
    const targets = sites.filter((site) => site.isBlocked);
    if (!targets.length) {
      setNotice("No blocked websites to unblock.");
      return;
    }
    const nextSites = sites.map((site) => ({ ...site, isBlocked: false }));
    await persistSites(nextSites, `Unblocked ${targets.length} site(s).`);
  }

  async function submitAddSite() {
    const normalized = normalizeDomainInput(siteInput);
    if (!normalized) {
      setSiteError("Enter a valid domain, like example.com or a full URL.");
      return;
    }
    if (sites.some((site) => normalizeSiteKey(site.domain) === normalized)) {
      setSiteError("That domain is already in your list.");
      return;
    }
    setBusy(true);
    try {
      const nextSites: SiteItem[] = [
        ...sites,
        {
          domain: normalized,
          displayName: normalized,
          isBlocked: true,
          category: "Custom",
          isCustom: true,
        },
      ];
      const result = await saveSites({ sites: nextSites.map(stripSite), updatedAt: Date.now() });
      if (result?.applied === false) throw new Error("Another device updated your websites. Reload and retry.");
      await applyNative(appRows, nextSites);
      setShowAddSite(false);
      setSiteInput("");
      setSiteError(null);
      setNotice(`Added ${normalized}.`);
    } catch (e) {
      setSiteError(`Couldn't add: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function deleteSite(site: SiteItem) {
    if (!guardUnlock(true)) return;
    await persistSites(
      sites.filter((item) => item.domain !== site.domain),
      `Removed ${site.domain}.`,
    );
  }
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <p className="eyebrow">Synced controls</p>
          <h1>Your boundaries</h1>
          <p>
            Choose what waits until after your work. Windows observations and
            Android selections are clearly labelled.
          </p>
        </div>
      </header>

      {boundariesLock && (
        <p className="boundary-notice lock-notice">
          Boundaries Lock is ON — blocked apps and websites can't be removed or
          unblocked.
        </p>
      )}

      <section className="settings-group group-section">
        <div className="section-heading">
          <div>
            <p className="section-label">Merged buckets</p>
            <h2>Groups</h2>
          </div>
          <div className="group-heading-actions">
            <button
              type="button"
              className={`secondary-button ${selectMode ? "active" : ""}`}
              onClick={() => {
                setSelectMode((value) => !value);
                setNotice(null);
              }}
            >
              <Icon name="check" />
              {selectMode ? "Done selecting" : "Merge selected"}
            </button>
            <button
              type="button"
              className="primary-button"
              disabled={busy}
              onClick={() => openCreateGroup()}
            >
              <Icon name="plus" /> New group
            </button>
          </div>
        </div>
        <p className="group-hint">
          Merge an app and a website — or several targets — into one bucket with
          a single daily limit shared across every device. A target can belong to
          one group only.
        </p>
        {groupNotice && <p className="boundary-notice">{groupNotice}</p>}
        {groups.length ? (
          <div className="group-list">
            {groups.map((group) => {
              const stats = groupSummaryById.get(group.groupId);
              const limit = Number(group.dailyLimitMinutes) || 0;
              const limitOn = limit > 0 && group.limitEnabled !== false;
              const usedSeconds = Number(stats?.trackedSeconds) || 0;
              const exceeded = limitOn && usedSeconds >= limit * 60;
              return (
                <article className="group-card" key={group.groupId}>
                  <div className="group-card-head">
                    <div>
                      <strong>{group.name}</strong>
                      <p>
                        {fmt(usedSeconds)} tracked
                        {statsSummary === todayUsage ? " today" : ""} ·{" "}
                        {group.members.length} member
                        {group.members.length === 1 ? "" : "s"} ·{" "}
                        {limitOn ? `${limit}m/day limit` : "no limit"}
                      </p>
                    </div>
                    <div className="group-card-actions">
                      {exceeded && (
                        <span className="limit-pill exceeded">
                          limit reached
                        </span>
                      )}
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={busy}
                        onClick={() => openEditGroup(group)}
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        className="site-delete"
                        disabled={busy}
                        onClick={() => deleteGroup(group)}
                        aria-label={`Delete ${group.name}`}
                      >
                        ×
                      </button>
                    </div>
                  </div>
                  <div className="chip-row">
                    {group.members.map((member) => {
                      const key = memberKeyOf(member);
                      const stat = (stats?.members || []).find(
                        (entry: any) => memberKeyOf(entry) === key,
                      );
                      return (
                        <span
                          className={`member-chip ${member.targetKind}`}
                          key={key}
                          title={member.targetKey}
                        >
                          <Icon
                            name={
                              member.targetKind === "website"
                                ? "globe"
                                : "monitor"
                            }
                            size={14}
                          />
                          {member.targetLabel}
                          {stat ? ` · ${fmt(stat.trackedSeconds)}` : ""}
                        </span>
                      );
                    })}
                  </div>
                </article>
              );
            })}
          </div>
        ) : (
          <EmptyState
            title="No merged buckets yet"
            body="Use Merge selected on the lists below, or create a group, to combine an app and a website under one daily limit."
          />
        )}
      </section>

      <div className="segment">
        <button
          className={kind === "apps" ? "active" : ""}
          onClick={() => changeKind("apps")}
        >
          Applications <span className="tab-count">{blockedAppCount}</span>
        </button>
        <button
          className={kind === "sites" ? "active" : ""}
          onClick={() => changeKind("sites")}
        >
          Websites <span className="tab-count">{blockedSiteCount}</span>
        </button>
      </div>

      <div className="boundary-toolbar">
        <label className="search">
          <Icon name="search" />
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={kind === "apps" ? "Search apps" : "Search websites"}
          />
          {q && (
            <button
              type="button"
              className="search-clear"
              onClick={() => {
                setQ("");
                setDebouncedQ("");
              }}
              aria-label="Clear search"
            >
              ×
            </button>
          )}
        </label>
        {kind === "sites" && (
          <button
            type="button"
            className="primary-button add-site-button"
            onClick={() => {
              setSiteError(null);
              setShowAddSite(true);
            }}
          >
            <Icon name="plus" />
            Add Website
          </button>
        )}
      </div>

      <div className="preset-row">
        <button
          type="button"
          className="preset-chip social"
          disabled={busy}
          onClick={() =>
            kind === "apps"
              ? blockAppsInCategories(["Social", "Social Media"], "Social")
              : blockSitesInCategories(["Social", "Social Media"], "Social")
          }
        >
          Block All Social
        </button>
        <button
          type="button"
          className="preset-chip video"
          disabled={busy}
          onClick={() =>
            kind === "apps"
              ? blockAppsInCategories(["Entertainment", "Video"], "Video")
              : blockSitesInCategories(["Entertainment", "Video"], "Video")
          }
        >
          Block All Video
        </button>
        <button
          type="button"
          className="preset-chip"
          disabled={busy || boundariesLock}
          title={
            boundariesLock
              ? "Boundaries Lock is ON — turn it off in Settings"
              : "Unblock every blocked app or website"
          }
          onClick={() => (kind === "apps" ? unblockAllApps() : unblockAllSites())}
        >
          Unblock All
        </button>
      </div>

      <div className="category-row">
        {categories.map((cat) => (
          <button
            type="button"
            key={cat}
            className={`filter-chip ${category === cat ? "active" : ""}`}
            onClick={() => setCategory(cat)}
          >
            {cat}
          </button>
        ))}
      </div>

      <div className="boundary-meta">
        <span>
          {kind === "apps"
            ? `Showing ${filteredApps.length} of ${appRows.length} apps`
            : `Showing ${filteredSites.length} of ${sites.length} sites`}
        </span>
        {kind === "apps" && (
          <div className="system-toggle">
            <span>System</span>
            <button
              type="button"
              className={`switch ${showSystem ? "on" : ""}`}
              role="switch"
              aria-checked={showSystem}
              aria-label="Show system and idle apps"
              title="Desktop has no Android system flag. This hides apps seen only by the Windows tracker with no tracked time today."
              onClick={() => setShowSystem((value) => !value)}
            >
              <span />
            </button>
          </div>
        )}
      </div>

      {notice && <p className="boundary-notice">{notice}</p>}

      {selectMode && (
        <div className="selection-tray">
          <div className="chip-row">
            {selectedMembers.map((member) => (
              <button
                type="button"
                className={`member-chip ${member.targetKind}`}
                key={memberKeyOf(member)}
                title="Remove from selection"
                onClick={() => toggleMemberSelection(member)}
              >
                <Icon
                  name={member.targetKind === "website" ? "globe" : "monitor"}
                  size={14}
                />
                {member.targetLabel} ×
              </button>
            ))}
            {!selectedMembers.length && (
              <span className="selection-hint">
                Tick apps and websites to merge — selection can span both lists.
                At least 2 are required.
              </span>
            )}
          </div>
          <div className="selection-actions">
            <span className="tab-count">{selectedMembers.length}</span>
            <button
              type="button"
              className="secondary-button"
              disabled={!selectedMembers.length}
              onClick={() => setSelectedMembers([])}
            >
              Clear
            </button>
            <button
              type="button"
              className="primary-button"
              disabled={selectedMembers.length < 2 || busy}
              onClick={() => openCreateGroup(selectedMembers)}
            >
              Merge selected
            </button>
          </div>
        </div>
      )}

      <div className="boundary-list">
        {kind === "apps"
          ? filteredApps.map((row) => (
              <article
                className={`boundary-row ${selectMode ? "selecting" : ""}`}
                key={row.key}
              >
                {selectMode &&
                  (() => {
                    const member = groupMemberFromApp(row);
                    const owner = memberOwnerByKey.get(memberKeyOf(member));
                    const picked = selectionKeys.has(memberKeyOf(member));
                    return (
                      <button
                        type="button"
                        className={`pick-box ${picked ? "selected" : ""}`}
                        aria-pressed={picked}
                        disabled={Boolean(owner)}
                        title={
                          owner
                            ? `Already in ${owner.name} — remove it there first`
                            : `Select ${row.name} for merging`
                        }
                        aria-label={`Select ${row.name} for merging`}
                        onClick={() => toggleMemberSelection(member)}
                      >
                        <Icon name="check" size={14} />
                      </button>
                    );
                  })()}
                <span className="letter-icon">
                  {row.name.charAt(0).toUpperCase()}
                </span>
                <div>
                  <strong>{row.name}</strong>
                  <p>
                    <span className="category-label">{row.category}</span> ·{" "}
                    {row.minutes > 0 ? `${row.minutes} min today` : "No time today"} ·{" "}
                    {row.source === "windows"
                      ? "Observed on this PC"
                      : "Synced from Android"}
                  </p>
                </div>
                <button
                  className={`switch ${row.isBlocked ? "on" : ""}`}
                  disabled={busy}
                  onClick={() => toggleApp(row)}
                  aria-label={`${row.isBlocked ? "Allow" : "Block"} ${row.name}`}
                >
                  <span />
                </button>
              </article>
            ))
          : filteredSites.map((site) => (
              <article
                className={`boundary-row ${selectMode ? "selecting" : ""}`}
                key={site.domain}
              >
                {selectMode &&
                  (() => {
                    const member = groupMemberFromSite(site);
                    const owner = memberOwnerByKey.get(memberKeyOf(member));
                    const picked = selectionKeys.has(memberKeyOf(member));
                    return (
                      <button
                        type="button"
                        className={`pick-box ${picked ? "selected" : ""}`}
                        aria-pressed={picked}
                        disabled={Boolean(owner)}
                        title={
                          owner
                            ? `Already in ${owner.name} — remove it there first`
                            : `Select ${site.domain} for merging`
                        }
                        aria-label={`Select ${site.domain} for merging`}
                        onClick={() => toggleMemberSelection(member)}
                      >
                        <Icon name="check" size={14} />
                      </button>
                    );
                  })()}
                <span className="letter-icon">
                  {(site.displayName || site.domain).charAt(0).toUpperCase()}
                </span>
                <div>
                  <strong>{site.displayName || site.domain}</strong>
                  <p>
                    <span className="category-label">{site.category}</span> ·{" "}
                    {site.domain}
                    {siteMinutes.get(normalizeSiteKey(site.domain))
                      ? ` · ${Math.round((siteMinutes.get(normalizeSiteKey(site.domain)) || 0) / 60)} min today`
                      : ""}
                  </p>
                </div>
                {site.isCustom && (
                  <button
                    className="site-delete"
                    disabled={busy}
                    onClick={() => deleteSite(site)}
                    aria-label={`Remove ${site.domain}`}
                  >
                    ×
                  </button>
                )}
                <button
                  className={`switch ${site.isBlocked ? "on" : ""}`}
                  disabled={busy}
                  onClick={() => toggleSite(site)}
                  aria-label={`${site.isBlocked ? "Allow" : "Block"} ${site.domain}`}
                >
                  <span />
                </button>
              </article>
            ))}
        {kind === "apps" && !filteredApps.length && (
          <EmptyState
            title="No apps match"
            body="Try a different search or category, or turn on the System toggle."
          />
        )}
        {kind === "sites" && !filteredSites.length && (
          <EmptyState
            title={sites.length ? "No websites match" : "No websites yet"}
            body={
              sites.length
                ? "Try a different search or category."
                : "Add the first website you want FocusLock to block."
            }
          />
        )}
      </div>

      {showAddSite && (
        <div
          className="boundary-dialog-backdrop"
          role="presentation"
          onClick={() => setShowAddSite(false)}
        >
          <div
            className="boundary-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="Add website"
            onClick={(e) => e.stopPropagation()}
          >
            <h2>Add Website</h2>
            <p>Paste a URL or enter a domain. FocusLock keeps the hostname only.</p>
            <input
              autoFocus
              value={siteInput}
              onChange={(e) => {
                setSiteInput(e.target.value);
                setSiteError(null);
              }}
              onKeyDown={(e) => e.key === "Enter" && submitAddSite()}
              placeholder="example.com or https://example.com/page"
              aria-invalid={Boolean(siteError)}
            />
            {siteError && <p className="inline-error">{siteError}</p>}
            <div className="dialog-actions">
              <button
                type="button"
                className="secondary-button"
                onClick={() => setShowAddSite(false)}
              >
                Cancel
              </button>
              <button
                type="button"
                className="primary-button"
                onClick={submitAddSite}
                disabled={busy || !siteInput.trim()}
              >
                Add Website
              </button>
            </div>
          </div>
        </div>
      )}

      {groupDraft && (
        <div
          className="boundary-dialog-backdrop"
          role="presentation"
          onClick={() => setGroupDraft(null)}
        >
          <div
            className="boundary-dialog group-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={groupDraft.groupId ? "Edit group" : "Create group"}
            onClick={(e) => e.stopPropagation()}
          >
            <h2>{groupDraft.groupId ? "Edit group" : "New group"}</h2>
            <p>
              Members share one daily limit and count as one cumulative bucket
              across every device. A target can belong to one group only.
            </p>
            {mergeNote && (
              <p className="selection-hint">
                Added from the {mergeNote} view — pick at least one more member
                (a bucket needs 2 or more).
              </p>
            )}
            <label className="group-field">
              <span>Name</span>
              <input
                autoFocus
                value={groupDraft.name}
                onChange={(e) => {
                  setGroupDraft({ ...groupDraft, name: e.target.value });
                  setGroupError(null);
                }}
                onKeyDown={(e) => e.key === "Enter" && submitGroup()}
                placeholder="e.g. YouTube"
                aria-invalid={Boolean(groupError) && !groupDraft.name.trim()}
              />
            </label>
            <label className="group-field">
              <span>Daily limit (minutes, optional)</span>
              <input
                value={groupDraft.limitMinutes}
                onChange={(e) => {
                  setGroupDraft({ ...groupDraft, limitMinutes: e.target.value });
                  setGroupError(null);
                }}
                inputMode="numeric"
                placeholder="No limit"
              />
            </label>
            <div className="chip-row">
              {groupDraft.members.map((member) => (
                <button
                  type="button"
                  className={`member-chip ${member.targetKind}`}
                  key={memberKeyOf(member)}
                  title="Remove member"
                  onClick={() => toggleDraftMember(member)}
                >
                  <Icon
                    name={member.targetKind === "website" ? "globe" : "monitor"}
                    size={14}
                  />
                  {member.targetLabel} ×
                </button>
              ))}
              {!groupDraft.members.length && (
                <span className="selection-hint">
                  No members yet — pick at least 2 below.
                </span>
              )}
            </div>
            <label className="search">
              <Icon name="search" />
              <input
                value={pickerQuery}
                onChange={(e) => setPickerQuery(e.target.value)}
                placeholder="Search apps and websites"
                aria-label="Search group members"
              />
            </label>
            <div className="key-entry">
              <div
                className="segment key-entry-kind"
                role="group"
                aria-label="Key kind"
              >
                <button
                  type="button"
                  className={manualKind === "app" ? "active" : ""}
                  aria-pressed={manualKind === "app"}
                  onClick={() => setManualKind("app")}
                >
                  App
                </button>
                <button
                  type="button"
                  className={manualKind === "website" ? "active" : ""}
                  aria-pressed={manualKind === "website"}
                  onClick={() => setManualKind("website")}
                >
                  Website
                </button>
              </div>
              <input
                value={manualKey}
                onChange={(e) => setManualKey(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && submitManualMember()}
                placeholder={
                  manualKind === "app" ? "e.g. chrome.exe" : "e.g. youtube.com"
                }
                aria-label="Add a target key manually"
              />
              <button
                type="button"
                className="secondary-button"
                onClick={submitManualMember}
                disabled={!manualKey.trim()}
              >
                Add
              </button>
            </div>
            <p className="selection-hint">
              Not listed? Add the exact key by hand — e.g. an app that only runs
              on your phone. It is normalized (lowercase; websites drop a
              leading www.), and any device whose tracked key matches will
              contribute to this bucket.
            </p>
            <div className="picker-list">
              {pickerCandidates.map((member) => {
                const key = memberKeyOf(member);
                const owner = memberOwnerByKey.get(key);
                const ownedElsewhere = Boolean(
                  owner && owner.groupId !== groupDraft.groupId,
                );
                const picked = groupDraft.members.some(
                  (entry) => memberKeyOf(entry) === key,
                );
                const tracked = trackedSecondsByMember.get(key);
                return (
                  <button
                    type="button"
                    className={`picker-row ${picked ? "picked" : ""}`}
                    key={key}
                    disabled={ownedElsewhere}
                    title={
                      ownedElsewhere
                        ? `Already in ${owner?.name}`
                        : member.targetKey
                    }
                    onClick={() => toggleDraftMember(member)}
                  >
                    <span className="picker-icon">
                      <Icon
                        name={
                          member.targetKind === "website" ? "globe" : "monitor"
                        }
                        size={14}
                      />
                    </span>
                    <span className="picker-copy">
                      <strong>{member.targetLabel}</strong>
                      <small>
                        {member.targetKind === "app" ? "App" : "Website"}
                        {tracked ? ` · ${fmt(tracked)} tracked` : ""}
                        {ownedElsewhere ? ` · in ${owner?.name}` : ""}
                      </small>
                      {Boolean(member.devices?.length) && (
                        <small className="device-chips">
                          {(member.devices || []).map((device) => (
                            <span className="device-chip" key={device.deviceId}>
                              {device.name}
                              {device.platform && device.platform !== "unknown"
                                ? ` · ${device.platform}`
                                : ""}
                            </span>
                          ))}
                        </small>
                      )}
                    </span>
                    <span className={`pick-box ${picked ? "selected" : ""}`}>
                      <Icon name="check" size={14} />
                    </span>
                  </button>
                );
              })}
              {!pickerCandidates.length && (
                <p className="picker-empty">No targets match that search.</p>
              )}
            </div>
            <p
              className={`group-rule ${
                groupDraft.members.length < 2 ? "warn" : ""
              }`}
            >
              {groupDraft.members.length < 2
                ? `At least 2 members are required (${groupDraft.members.length} selected).`
                : `${groupDraft.members.length} members selected.`}
            </p>
            {groupError && <p className="inline-error">{groupError}</p>}
            <div className="dialog-actions">
              <button
                type="button"
                className="secondary-button"
                onClick={() => setGroupDraft(null)}
              >
                Cancel
              </button>
              <button
                type="button"
                className="primary-button"
                onClick={submitGroup}
                disabled={
                  busy ||
                  !groupDraft.name.trim() ||
                  groupDraft.members.length < 2
                }
              >
                {groupDraft.groupId ? "Save group" : "Create group"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function SettingsPage({
  dashboard,
  snapshot,
  status,
  trackerError,
  refresh,
  workRatio,
  setWorkRatio,
  taskBonus,
  setTaskBonus,
  boundariesLock,
  setBoundariesLock,
  lastSyncAt,
  syncing,
  onSyncNow,
}: any) {
  const [busy, setBusy] = useState(false);
  const auth = useFocusAuth();
  const running = Boolean(snapshot?.running);
  const enforcementActive = Boolean(
    status?.enforcementActive ??
      ((dashboard?.apps || []).some(
        (app: AppItem) => app.isBlocked && app.category === "Windows",
      ) ||
        (dashboard?.sites || []).some((site: SiteItem) => site.isBlocked)),
  );
  const idleSeconds = snapshot?.config?.idleThresholdSeconds || 60;
  const syncAgeMs = lastSyncAt ? Date.now() - lastSyncAt : null;
  const syncFresh = syncAgeMs !== null && syncAgeMs < 90_000;
  const syncStatusText = !auth.user
    ? "Signed out — sign in to upload usage"
    : syncing
      ? "Syncing now…"
      : syncAgeMs === null
        ? "Waiting for first upload"
        : syncFresh
          ? "Synced just now"
          : `Last upload ${Math.round(syncAgeMs / 1000)}s ago`;
  const trackerIssue = status?.lastError || trackerError || null;
  async function toggle() {
    if (!tauriAvailable()) return;
    setBusy(true);
    try {
      await invoke(running ? "stop_tracking" : "start_tracking");
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  const prefs = dashboard?.prefs || {};
  const savePrefs = useMutation(syncApi.savePrefs);
  const [prefsBusy, setPrefsBusy] = useState(false);
  async function setStrictMode(next: boolean) {
    setPrefsBusy(true);
    try {
      await savePrefs({
        strictMode: next,
        weeklyReport: prefs.weeklyReport ?? false,
        dailyReminderMinutes: prefs.dailyReminderMinutes,
        globalDailyCapMinutes: prefs.globalDailyCapMinutes,
        updatedAt: Math.max(Date.now(), prefs.updatedAt || 0),
      });
    } catch (err) {
      console.warn("[focuslock] savePrefs failed", err);
    } finally {
      setPrefsBusy(false);
    }
  }
  return (
    <div className="page narrow">
      <header className="page-header">
        <div>
          <p className="eyebrow">This device</p>
          <h1>Settings</h1>
          <p>
            Tracking stays local first, then uploads absolute counters to your
            signed-in account.
          </p>
        </div>
      </header>
      <section className="settings-group">
        <h2>Windows tracking</h2>
        <SettingRow
          icon="monitor"
          title="App activity"
          detail="Foreground application time, sampled while you are active"
        >
          <button
            className={`switch ${running ? "on" : ""}`}
            onClick={toggle}
            disabled={busy || !tauriAvailable()}
          >
            <span />
          </button>
        </SettingRow>
        <SettingRow
          icon="globe"
          title="Website domains"
          detail="Reads the active browser domain when Windows exposes it"
        >
          <span className="setting-value">
            {snapshot?.config?.captureBrowserDomains !== false ? "On" : "Off"}
          </span>
        </SettingRow>
        <SettingRow
          icon="clock"
          title="Idle timeout"
          detail="Time away from input is never counted"
        >
          <span className="setting-value">
            {snapshot?.config?.idleThresholdSeconds || 60}s
          </span>
        </SettingRow>
        {trackerError && <p className="inline-error">{trackerError}</p>}
      </section>
      <section className="settings-group">
        <h2>Earned time</h2>
        <div className="setting-row">
          <span className="setting-icon">
            <Icon name="focus" />
          </span>
          <div>
            <strong>Work-to-leisure ratio {workRatio}:1</strong>
            <p>
              Synced across Android, Windows, and Chrome. Work earns leisure at
              this ratio on every device.
            </p>
            <input
              type="range"
              min={1}
              max={10}
              step={1}
              value={workRatio}
              onChange={(e) => setWorkRatio(Number(e.target.value))}
              aria-label="Work to leisure ratio"
              style={{ width: "100%" }}
            />
          </div>
          <span className="setting-value">{workRatio}:1</span>
        </div>
        <div className="setting-row">
          <span className="setting-icon">
            <Icon name="plus" />
          </span>
          <div>
            <strong>Task completion bonus</strong>
            <p>
              Extra minutes per finished task in the manual log. Synced across
              your devices.
            </p>
            <input
              type="range"
              min={0}
              max={20}
              step={1}
              value={taskBonus}
              onChange={(e) => setTaskBonus(Number(e.target.value))}
              aria-label="Task completion bonus minutes"
              style={{ width: "100%" }}
            />
          </div>
          <span className="setting-value">+{taskBonus}m</span>
        </div>
        <SettingRow
          icon="lock"
          title="Strict mode"
          detail="Synced to your account via focus:savePrefs; enforced on Android."
        >
          <button
            className={`switch ${prefs.strictMode ? "on" : ""}`}
            onClick={() => setStrictMode(!prefs.strictMode)}
            disabled={prefsBusy}
          >
            <span />
          </button>
        </SettingRow>
      </section>
      <section className="settings-group">
        <h2>Protection</h2>
        <SettingRow
          icon="lock"
          title="Lockdown Mode"
          detail="Android-only 24-hour hard lock with no unlocks. Windows has no equivalent lock — use the Nuke below to reset every device."
        >
          <span className="setting-value">Android only</span>
        </SettingRow>
        <SettingRow
          icon="lock"
          title="Boundaries Lock"
          detail="When ON, blocked apps and websites can't be unblocked or removed from Boundaries."
        >
          <button
            className={`switch ${boundariesLock ? "on" : ""}`}
            onClick={() => setBoundariesLock(!boundariesLock)}
            aria-label="Toggle Boundaries Lock"
          >
            <span />
          </button>
        </SettingRow>
        <SettingRow
          icon="sync"
          title="Background auto-sync"
          detail="Uploads screen time and device status roughly every 25 seconds while signed in. Android uses notification sync; Windows has no notification listener."
        >
          <div className="setting-inline">
            <span className="setting-value">{syncStatusText}</span>
            <button
              className="secondary-button"
              onClick={onSyncNow}
              disabled={syncing || !tauriAvailable()}
            >
              <Icon name="sync" /> {syncing ? "Syncing…" : "Sync Now"}
            </button>
          </div>
        </SettingRow>
        <SettingRow
          icon="check"
          title="TickTick"
          detail="TickTick task verification runs in the Android app. Open TickTick on the web to review tasks, then log finished work here or with the focus timer."
        >
          <div className="setting-inline">
            <span className="setting-value">Not connected on desktop</span>
            <a
              className="secondary-button"
              href="https://ticktick.com/webapp"
              target="_blank"
              rel="noreferrer noopener"
              onClick={() => window.open("https://ticktick.com/webapp", "_blank")}
            >
              Open TickTick
            </a>
          </div>
        </SettingRow>
      </section>
      <section className="settings-group">
        <h2>System Protection Status</h2>
        <ProtectionCheck
          title="Activity tracking"
          detail="Windows foreground time is sampled while you are active."
          ok={running}
          value={running ? "Active" : snapshot ? "Paused" : "Desktop app"}
        />
        <ProtectionCheck
          title="Boundary enforcement"
          detail="Windows minimises apps and domains on your blocked list."
          ok={enforcementActive}
          value={enforcementActive ? "Active" : "No targets"}
        />
        <ProtectionCheck
          title="Account sync"
          detail="Device status and usage uploads reach your FocusLock account."
          ok={Boolean(auth.user) && syncFresh}
          value={!auth.user ? "Signed out" : syncFresh ? "Syncing" : "Waiting"}
        />
        <ProtectionCheck
          title="Idle timeout"
          detail="Time away from input is never counted as screen time."
          ok
          value={`${idleSeconds}s`}
        />
        {trackerIssue && (
          <ProtectionCheck
            title="Tracker error"
            detail={String(trackerIssue)}
            ok={false}
            value="Attention"
          />
        )}
      </section>
      <section className="danger-zone">
        <NukeButton />
      </section>
    </div>
  );
}

function ProtectionCheck({ title, detail, ok, value }: any) {
  return (
    <div className={`protection-check ${ok ? "ok" : "warn"}`}>
      <span className="check-dot" />
      <div>
        <strong>{title}</strong>
        <p>{detail}</p>
      </div>
      <span className="setting-value">{value}</span>
    </div>
  );
}
function SettingRow({ icon, title, detail, children }: any) {
  return (
    <div className="setting-row">
      <span className="setting-icon">
        <Icon name={icon} />
      </span>
      <div>
        <strong>{title}</strong>
        <p>{detail}</p>
      </div>
      {children}
    </div>
  );
}

function AccountPage({
  devices,
  lastSyncAt,
  syncing,
  onSyncNow,
  trackerError,
}: {
  devices: any[];
  lastSyncAt?: number;
  syncing?: boolean;
  onSyncNow?: () => void;
  trackerError?: string | null;
}) {
  const auth = useFocusAuth();
  const user = auth.user;
  const isSignedIn = Boolean(user);
  const syncAgeMs = lastSyncAt ? Date.now() - lastSyncAt : null;
  const syncFresh = syncAgeMs !== null && syncAgeMs < 90_000;
  const syncStatus = syncing
    ? "Syncing now…"
    : trackerError
      ? `Tracker issue: ${trackerError}`
      : syncAgeMs === null
        ? "Waiting for first upload"
        : syncFresh
          ? "Synced just now"
          : `Last upload ${Math.round(syncAgeMs / 1000)}s ago`;

  return (
    <div className="page narrow">
      <header className="page-header">
        <div>
          <p className="eyebrow">FocusLock account</p>
          <h1>Account</h1>
        </div>
      </header>
      {auth.loading ? (
        <section className="settings-group">
          <p className="setting-value">Checking your account…</p>
        </section>
      ) : isSignedIn ? (
        <>
          <section className="account-hero">
            {user?.imageUrl ? (
              <img
                className="account-avatar"
                src={user.imageUrl}
                alt="Account avatar"
              />
            ) : (
              <span className="account-avatar fallback">
                <Icon name="user" />
              </span>
            )}
            <div className="account-identity">
              <h2>{user?.name || "Priority Member"}</h2>
              {user?.email && <p>{user.email}</p>}
            </div>
            <span className="priority-badge">
              <Icon name="check" /> Priority Active
            </span>
          </section>
          <section className="settings-group">
            <div className="account-sync-row">
              <div>
                <p className="section-label">Cloud status</p>
                <strong>{syncStatus}</strong>
              </div>
              <button
                className="secondary-button"
                onClick={onSyncNow}
                disabled={syncing || !onSyncNow}
              >
                <Icon name="sync" /> {syncing ? "Syncing…" : "Sync Now"}
              </button>
            </div>
          </section>
          <section className="account-note">
            <strong>Multi-Device Protection Active</strong>
            <p>
              Auto-sync runs every ~30 seconds and instantly after every focus
              event. A Nuke on your phone simultaneously locks your desktop
              companion and browser extensions.
            </p>
          </section>
          <section className="settings-group">
            <div className="section-heading">
              <div>
                <p className="section-label">Connected devices</p>
                <h2>Tracking sources</h2>
              </div>
            </div>
            {devices.map((d) => (
              <div className="device-account-row" key={d.deviceId}>
                <span className="setting-icon">
                  <Icon name={d.platform === "android" ? "phone" : "monitor"} />
                </span>
                <div>
                  <strong>{d.name}</strong>
                  <p>
                    {d.platform} · {d.statusDetail || d.trackingStatus}
                  </p>
                </div>
                <span
                  className={`status-badge ${Date.now() - d.lastSeen < 120000 ? "ok" : ""}`}
                >
                  <span />
                  {Date.now() - d.lastSeen < 120000 ? "Online" : "Last seen"}
                </span>
              </div>
            ))}
            {!devices.length && (
              <EmptyState
                title="No devices registered"
                body="Keep this app open while signed in; your Windows device will register automatically."
              />
            )}
          </section>
          <button className="secondary-button danger-text" onClick={auth.signOut}>
            <Icon name="lock" /> Sign Out
          </button>
        </>
      ) : (
        <>
          <section className="account-hero signed-out">
            <span className="account-avatar fallback">
              <Icon name="lock" />
            </span>
            <div className="account-identity">
              <h2>FocusLock Priority Account</h2>
              <p>Cross-device sync &amp; lock enforcement</p>
            </div>
          </section>
          <section className="account-features">
            <AccountFeature
              icon="lock"
              title="Multi-Device Nuke Lock"
              description="Locking on mobile locks desktop companion & browser."
            />
            <AccountFeature
              icon="sync"
              title="Real-Time Cloud Sync"
              description="Credits, task records, and boundaries sync across devices."
            />
            <AccountFeature
              icon="monitor"
              title="Desktop Companion"
              description="Connects with the macOS/Windows desktop companion app."
            />
          </section>
          <button className="primary-button" onClick={() => auth.signInInBrowser()}>
            <Icon name="user" /> Sign In to Priority Account
          </button>
          <section className="account-note">
            <strong>Currently in Offline Mode</strong>
            <p>
              Focus credits, boundaries, and app locks are stored locally on this
              device until you sign in.
            </p>
          </section>
        </>
      )}
    </div>
  );
}

function AccountFeature({ icon, title, description }: any) {
  return (
    <div className="account-feature">
      <span className="account-feature-icon">
        <Icon name={icon} />
      </span>
      <div>
        <strong>{title}</strong>
        <p>{description}</p>
      </div>
    </div>
  );
}
function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="empty-state">
      <span>
        <Icon name="clock" />
      </span>
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  );
}
function DashboardSkeleton() {
  return (
    <div className="page">
      <div className="skeleton sk-title" />
      <div className="skeleton sk-hero" />
      <div className="skeleton sk-panel" />
    </div>
  );
}
