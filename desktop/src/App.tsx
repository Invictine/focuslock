import {
  Authenticated,
  AuthLoading,
  Unauthenticated,
  useMutation,
  useQuery,
} from "convex/react";
import { SignIn } from "@clerk/clerk-react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
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
import { accountUsage, acknowledgeSync, enqueueSync, peekSync, unacknowledgedUsage } from "./offlineQueue";
import { boundaryLeisureSeconds, formatTimeDuration, mergeLiveTodayUsage, timeRatio, todayWorkSeconds } from "./timeMetrics";
import { accountClient, flushMutations, useDurableMutation, useMutationReplay } from "./durableSync";
import { claimPermanentTargets, discoverPermanentTargets, permanentTargetsOwnedByAccount } from "./permanentSync";
import { useFocusAuth } from "./auth";
import { browserProtectionPolicy, browserProtectionStatusLabel } from "./browserProtection";
import { signOutProtectionPolicy } from "./signOutProtection";
import { DESKTOP_FROG_ENABLED } from "./features";
import FrogCard from "./FrogCard";
import { VoidLauncherBridge } from "./voidLauncher";
import ApprovalUnlockPanel from "./ApprovalUnlockPanel";
import { useStrictActive } from "./useStrictActive";
import { useStrictUninstallGuard } from "./strictUninstall";
import { MAX_STRICT_HOURS, STRICT_HOUR_OPTIONS, strictDurationLabel, strictEndError } from "./strictTiming";
import {
  FROG_UI_MAX_REQUIRED_MINUTES,
  FROG_UI_MIN_REQUIRED_MINUTES,
  addTrackedSeconds,
  useFrogState,
} from "./frog";
import NukeOverlay, { NukeButton } from "./NukeOverlay";
import "./styles.css";
import "./loading.css";

const api: any = convexApi;
type Tab = "focus" | "boundaries" | "permalock" | "settings" | "account";
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
  permanent?: boolean;
  accountPermanent?: boolean;
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
  browserProtectionRequired?: boolean;
  browserProtectionEnabled?: boolean;
  browserProtection?: { browser: string; healthy: boolean; graceRemainingSeconds: number; reason?: string | null } | null;
  browserProtectionScanState?: "idle" | "checking" | "no_browser" | "browser" | "scan_error";
  browserProtectionError?: string | null;
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
    | "shield"
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
    shield: (
      <>
        <path d="M12 3l7 3v5c0 4.4-2.9 7.4-7 9-4.1-1.6-7-4.6-7-9V6Z" />
        <path d="m9 12 2 2 4-4" />
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
  return formatTimeDuration(seconds);
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

function accountStorageKey(accountKey: string | null | undefined) {
  return accountKey ? `${PREFS_CACHE_KEY}:${encodeURIComponent(accountKey)}` : `${PREFS_CACHE_KEY}:offline`;
}

function readLocalPrefs(accountKey?: string | null): SyncedPrefs {
  const base: SyncedPrefs = {
    workRatio: DEFAULT_WORK_RATIO,
    workRatioUpdatedAt: 0,
    taskBonusMinutes: DEFAULT_TASK_BONUS_MINUTES,
    taskBonusMinutesUpdatedAt: 0,
  };
  try {
    const scopedKey = accountStorageKey(accountKey);
    const raw = window.localStorage.getItem(scopedKey) || (accountKey ? null : window.localStorage.getItem(PREFS_CACHE_KEY));
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

function writeLocalPrefs(prefs: SyncedPrefs, accountKey?: string | null) {
  try {
    window.localStorage.setItem(accountStorageKey(accountKey), JSON.stringify(prefs));
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

function useSyncedPrefs(dashboard: any, accountKey?: string | null) {
  const savePrefs = useDurableMutation(syncApi.savePrefs);
  const [prefs, setPrefs] = useState<SyncedPrefs>(() => readLocalPrefs(accountKey));
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
      writeLocalPrefs(merged, accountKey);
    }
    if (pushRatio || pushBonus)
      schedulePush({ ratio: pushRatio, bonus: pushBonus });
  }, [accountKey, dashboard?.prefs, schedulePush]);

  useEffect(() => {
    const next = readLocalPrefs(accountKey);
    prefsRef.current = next;
    setPrefs(next);
    pendingRef.current = { ratio: false, bonus: false };
  }, [accountKey]);

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
      writeLocalPrefs(updated, accountKey);
      schedulePush({ ratio: true, bonus: false });
    },
    [accountKey, schedulePush],
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
      writeLocalPrefs(updated, accountKey);
      schedulePush({ ratio: false, bonus: true });
    },
    [accountKey, schedulePush],
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
// Native app boundaries and daily app limits use `BlockedTargets`; website
// entries require the browser extension, which owns website enforcement. The
// desktop still monitors browser-extension health and gives the user a repair
// window if that connection is lost.
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
//
// `frog` is optional so the union can also be evaluated without the lock (older
// call sites / future tests). When the frog lock is active the union additionally
// blocks every app and website in the Boundaries catalog except the frog
// allowlist, and the allowlist overrides base blocks and daily limits.
export type FrogLockContext = {
  locked: boolean;
  neededAppIds: string[];
  neededDomains: string[];
} | null | undefined;

// The frog lock never injects shell-critical executables, because the Windows
// shell (and FocusLock itself, which owns the recovery paths) must survive a
// hard lock even when it shows up in the observed/running-app list. Desktop's
// Boundaries page models "system" as a per-row boolean (observed-only apps with
// no tracked time today), not a category string, so the frog catalog mirrors
// that rule separately (see `systemKeys` in evaluateBlockedTargets).
export const FROG_NEVER_BLOCK_APP_IDS: ReadonlySet<string> = new Set([
  "explorer.exe",
  "dwm.exe",
  "winlogon.exe",
  "csrss.exe",
  "smss.exe",
  "wininit.exe",
  "services.exe",
  "lsass.exe",
  "taskhostw.exe",
  "sihost.exe",
  "ctfmon.exe",
  "startmenuexperiencehost.exe",
  "searchhost.exe",
  "shellexperiencehost.exe",
  // OS/UWP hosts: blocking one of these takes whole classes of windows with
  // it and cannot be undone from the app allowlist (its key is the host exe).
  "applicationframehost.exe",
  "runtimebroker.exe",
  "textinputhost.exe",
  "lockapp.exe",
  "logonui.exe",
  "shellhost.exe",
  "searchapp.exe",
  // Exact helper emitted by the bundled Void build.
  "focuslock.void.exe",
]);

// Browsers the Windows tracker recognizes (`windows_capture::is_supported_browser`),
// mirrored here. While the frog lock needs websites the final union must not
// contain a browser .exe: Rust matches the app target before the captured
// domain, so an app-level frog block would make `neededDomains` unreachable.
export const FROG_BROWSER_APP_IDS: ReadonlySet<string> = new Set([
  "chrome.exe",
  "msedge.exe",
  "brave.exe",
  "firefox.exe",
  "vivaldi.exe",
  "opera.exe",
  "opera_gx.exe",
  "arc.exe",
]);

function isFrogBlockableApp(rawKey: string, category?: string): boolean {
  const key = targetKeyFor("app", rawKey);
  if (!key) return false;
  if (FROG_NEVER_BLOCK_APP_IDS.has(key)) return false;
  if (key.includes("focuslock")) return false;
  return String(category || "").trim().toLowerCase() !== "system";
}

/** True when `domain` equals `parent` or is a subdomain of it — the same
 * parent-label walk Rust's `match_domain`/`domain_reason` apply. */
function domainBelongsTo(domain: string, parent: string): boolean {
  return domain === parent || domain.endsWith(`.${parent}`);
}

export function evaluateBlockedTargets({
  dashboard,
  groups,
  summary,
  usage,
  frog,
  permanentAppIds,
}: {
  dashboard: any;
  groups: TargetGroup[] | undefined;
  summary: UsageSummary | undefined;
  usage: NativeUsage[];
  frog?: FrogLockContext;
  // Device-local permanent blocks (Rust `permanent_targets`). At least as
  // authoritative as the frog lock: they are re-added after every removal pass
  // and stamped with the "permanent" reason, so the JS payload mirrors the
  // merge Rust applies to every `set_blocked_targets` call.
  permanentAppIds?: string[];
}): {
  targets: { appIds: string[]; domains: string[] };
  reasons: Record<string, string>;
  exceeded: string[];
} {
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
  const permanentWebsiteKeys = new Set<string>(
    (dashboard?.permanentBlocks || [])
      .filter((item: any) => item?.targetKind === "website")
      .map((item: any) => normalizeSiteKey(item.targetKey))
      .filter(Boolean),
  );

  const today = localDate();
  const localSeconds = localSecondsByTarget(usage, today);
  const localSecondsFor = (kind: string, rawKey: string) =>
    localSeconds.get(`${kind}:${targetKeyFor(kind, rawKey)}`) || 0;
  const exceeded: string[] = [];
  // Targets injected because a limit is exhausted; also the reasons-map source.
  const limitApps = new Set<string>();
  const limitDomains = new Set<string>();

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
      if (member.targetKind === "app") {
        appIds.add(key);
        limitApps.add(key);
      } else if (member.targetKind === "website") {
        domains.add(key);
        limitDomains.add(key);
      }
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
    if (kind === "app") {
      appIds.add(key);
      limitApps.add(key);
    } else {
      domains.add(key);
      limitDomains.add(key);
    }
    exceeded.push(String(row?.label || key));
  }

  // 3. Frog hard lock: block the whole Boundaries catalog (synced apps/sites,
  //    group members and observed/running apps) except the frog allowlist. Skip
  //    shell-critical/system entries, and let the allowlist win over base blocks
  //    and exhausted limits so the frog can actually be worked on.
  const frogApps = new Set<string>();
  const frogDomains = new Set<string>();
  if (frog?.locked) {
    const allowApps = new Set(
      (frog.neededAppIds || []).map((key) => targetKeyFor("app", key)).filter(Boolean),
    );
    const allowDomains = new Set(
      (frog.neededDomains || []).map((key) => targetKeyFor("website", key)).filter(Boolean),
    );

    const catalogApps = new Map<string, string>();
    // Keys that must never be frog-injected: the category:"System" rows (Android
    // sync) plus the same `system` signal the Boundaries page derives for
    // Windows (observed-only app with no tracked time today, App.tsx ~3100).
    // Tracked as a set so a later catalog/group merge can't overwrite it.
    const systemKeys = new Set<string>();
    const syncedAppKeys = new Set<string>();
    (dashboard?.apps || []).forEach((item: AppItem) => {
      const key = targetKeyFor("app", item.packageName);
      if (!key) return;
      syncedAppKeys.add(key);
      if (String(item.category || "").trim().toLowerCase() === "system") {
        systemKeys.add(key);
        return;
      }
      // First writer wins: never let a later merge downgrade a category.
      if (!catalogApps.has(key)) catalogApps.set(key, String(item.category || ""));
    });
    // Explicit group members are user-authored targets, so the observed-only
    // "system noise" rule must not reclassify them.
    const groupAppKeys = new Set<string>();
    for (const group of groupSource) {
      for (const member of (group?.members || []) as TargetGroupMember[]) {
        if (member.targetKind !== "app") continue;
        const key = targetKeyFor("app", member.targetKey);
        if (key) groupAppKeys.add(key);
      }
    }
    for (const entry of usage) {
      if (entry.browserDomain) continue;
      const key = targetKeyFor("app", entry.appId);
      if (!key) continue;
      if (
        !syncedAppKeys.has(key) &&
        !groupAppKeys.has(key) &&
        Math.round(localSecondsFor("app", key) / 60) === 0
      ) {
        systemKeys.add(key);
      }
      if (!catalogApps.has(key)) catalogApps.set(key, "Windows");
    }
    for (const key of groupAppKeys) {
      if (!catalogApps.has(key)) catalogApps.set(key, "");
    }
    for (const [key, category] of catalogApps) {
      if (systemKeys.has(key)) continue;
      if (!isFrogBlockableApp(key, category)) continue;
      if (allowApps.has(key)) continue;
      appIds.add(key);
      frogApps.add(key);
    }

    const catalogDomains = new Set<string>();
    (dashboard?.sites || []).forEach((site: SiteItem) => {
      const key = targetKeyFor("website", site.domain);
      if (key) catalogDomains.add(key);
    });
    for (const group of groupSource) {
      for (const member of (group?.members || []) as TargetGroupMember[]) {
        if (member.targetKind !== "website") continue;
        const key = targetKeyFor("website", member.targetKey);
        if (key) catalogDomains.add(key);
      }
    }
    for (const key of catalogDomains) {
      if (allowDomains.has(key)) continue;
      domains.add(key);
      frogDomains.add(key);
    }

    for (const value of [...appIds]) {
      if (allowApps.has(targetKeyFor("app", value))) appIds.delete(value);
    }
    // Suffix-aware removal: Rust matches `m.youtube.com` against a blocked
    // `youtube.com`, so an allowlisted parent domain must also release its
    // catalogued subdomains (exact-only removal left that deadlocked).
    for (const value of [...domains]) {
      const key = targetKeyFor("website", value);
      for (const allow of allowDomains) {
        if (domainBelongsTo(key, allow)) {
          domains.delete(value);
          break;
        }
      }
    }

    // Browsers must stay reachable whenever the frog needs websites: Rust
    // matches the app target before the captured domain, so an app-level frog
    // block on chrome.exe/msedge.exe would keep `neededDomains` unreachable.
    // Domain rules (base blocks, limits and the allowlist removals above) still
    // govern browsing.
    if ((frog.neededDomains || []).length > 0) {
      for (const value of [...appIds]) {
        if (FROG_BROWSER_APP_IDS.has(targetKeyFor("app", value))) {
          appIds.delete(value);
          frogApps.delete(value);
        }
      }
    }
  }

  // Account-owned website commitments survive boundary edits and Frog's
  // allowlist, just as native permanent app targets do.
  for (const key of permanentWebsiteKeys) domains.add(key);

  // Reasons for the final union: "permanent" for device-local permanent blocks
  // (re-added here so the frog allowlist can never remove one), "frog" for
  // lock-injected targets, "limit" for exhausted daily limits, "blocked"
  // otherwise. Keys mirror the target arrays.
  const permanentApps = new Set(
    (permanentAppIds || [])
      .map((key) => targetKeyFor("app", key))
      .filter(Boolean),
  );
  for (const key of permanentApps) appIds.add(key);
  const reasons: Record<string, string> = {};
  for (const key of appIds) {
    reasons[key] = permanentApps.has(key)
      ? "permanent"
      : frogApps.has(key)
        ? "frog"
        : limitApps.has(key)
          ? "limit"
          : "blocked";
  }
  for (const key of domains) {
    reasons[key] = permanentWebsiteKeys.has(targetKeyFor("website", key))
      ? "permanent"
      : frogDomains.has(key)
        ? "frog"
        : limitDomains.has(key)
          ? "limit"
          : "blocked";
  }

  return { targets: { appIds: [...appIds], domains: [...domains] }, reasons, exceeded };
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
        <AccountApp />
      </Authenticated>
    </>
  );
}

function NativeApp() {
  const auth = useFocusAuth();
  if (auth.loading) return <AppLoading />;
  return auth.user ? <AccountApp /> : <SignInPage />;
}

function AccountApp() {
  const auth = useFocusAuth();
  return <DesktopApp key={auth.user?.id || "signed-out"} />;
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
  useMutationReplay();
  const [tab, setTab] = useState<Tab>("focus");
  const { snapshot, status, error: trackerError, refresh } = useNativeTracking();
  const configuration: any = useQuery(syncApi.getConfiguration, EMPTY_ARGS);
  const nuke: any = useQuery(api.nuke.getNuke, EMPTY_ARGS);
  const nativeState: any = useQuery(syncApi.getState, EMPTY_ARGS);
  const history: any = useQuery(syncApi.getHistory, tab === "focus" ? EMPTY_ARGS : "skip");
  // Keep the large configuration subscription separate from frequently changing
  // credit state. History is only watched while its Focus page is visible.
  const dashboard: any = useMemo(() => configuration && nativeState !== undefined ? {
    ...configuration,
    state: nativeState?.state ?? null,
    records: history?.records ?? [],
    sessions: history?.sessions ?? [],
  } : undefined, [configuration, nativeState, history]);
  // Device-local permanent blocks (Rust `permanent_targets`). Root state so the
  // Permalock page and the Boundaries page render the same enforcement truth:
  // Rust unions this list back into every set_blocked_targets payload, so the
  // UI must never present a permanent app as removable.
  const [permanentTargets, setPermanentTargets] = useState<string[]>([]);
  const deviceId = snapshot?.device.id || getStoredDeviceId();
  const refreshPermanentTargets = useCallback(async () => {
    if (!tauriAvailable()) return;
    try {
      const ids = await invoke<string[]>("get_permanent_targets");
      setPermanentTargets(Array.isArray(ids) ? ids : []);
    } catch {
      // Tracker unavailable (web preview); enforcement still lives in Rust.
    }
  }, []);
  useEffect(() => {
    void refreshPermanentTargets();
  }, [refreshPermanentTargets]);
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
  const usage: any = useQuery(syncApi.getUsageSummary, tab === "focus" ? usageArgs : "skip");
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
    tab === "focus" || tab === "boundaries" ? EMPTY_ARGS : "skip",
  ) as any;
  const devices: any[] | undefined = useQuery(
    syncApi.listDevices,
    tab === "focus" || tab === "account" ? EMPTY_ARGS : "skip",
  );
  const accountKey = auth.user?.id || null;
  const uninstallGuardError = useStrictUninstallGuard(accountKey, configuration?.prefs);
  const [localTime, setLocalTime] = useState<{ account: string; device: string; buckets: UsageBucket[] } | null>(null);
  useEffect(() => {
    if (!accountKey || !deviceId || !snapshot) { setLocalTime(null); return; }
    try {
      const buckets = accountUsage(accountKey, deviceId, snapshot.usage.map(entry => ({
        date: entry.date, ...usageTargetFor(entry), targetLabel: entry.browserDomain || entry.appName,
        trackedSeconds: entry.activeSeconds, updatedAt: Date.now(),
      })));
      setLocalTime({ account: accountKey, device: deviceId, buckets });
    } catch { setLocalTime(null); }
  }, [accountKey, deviceId, snapshot]);
  const permanentTargetsSignature = useMemo(
    () => [...new Set(permanentTargets.map((target) => targetKeyFor("app", target)).filter(Boolean))]
      .sort()
      .join("\n"),
    [permanentTargets],
  );
  const onPermanentTargetsAdded = useCallback((targets: string[]) => {
    if (!deviceId || !targets.length) return;
    const normalized = targets.map((target) => targetKeyFor("app", target)).filter(Boolean);
    if (accountKey) claimPermanentTargets(window.localStorage, deviceId, normalized, accountKey);
    else discoverPermanentTargets(window.localStorage, deviceId, normalized);
  }, [accountKey, deviceId]);
  // Keep provenance for native targets that existed before account sync. They
  // can be adopted by the first verified account, while targets already claimed
  // by another account remain local and are never copied across account edges.
  useEffect(() => {
    if (deviceId && permanentTargets.length) {
      discoverPermanentTargets(window.localStorage, deviceId, permanentTargets.map((target) => targetKeyFor("app", target)));
    }
  }, [deviceId, permanentTargets]);
  // Account permanence is append-only. Restore this account's Windows targets
  // into Rust, then upload native targets only when their persisted provenance
  // is unclaimed or already includes this account. The token subject is checked
  // by accountClient before any mutation, and failed writes retry on reconnect.
  useEffect(() => {
    const cloudTargets = configuration?.permanentBlocks;
    const accountKeyAtStart = accountKey;
    const deviceKey = deviceId;
    if (!tauriAvailable() || !accountKeyAtStart || !deviceKey || !Array.isArray(cloudTargets)) return;
    let cancelled = false;
    let busy = false;
    let retryNeeded = true;
    const syncPermanentTargets = async () => {
      if (cancelled || busy) return;
      busy = true;
      try {
        const token = await auth.getSyncToken();
        if (!token || cancelled) return;
        const client = accountClient(accountKeyAtStart, token);
        const windowsTargets: string[] = [...new Set<string>(cloudTargets
          .filter((target: any) => target?.targetKind === "windows")
          .map((target: any) => targetKeyFor("app", target.targetKey))
          .filter((target: string) => Boolean(target)))];
        if (windowsTargets.length) {
          if (!claimPermanentTargets(window.localStorage, deviceKey, windowsTargets, accountKeyAtStart)) {
            throw new Error("Could not save permanent-target account ownership locally");
          }
          if (cancelled) return;
          await invoke("add_permanent_targets", { appIds: windowsTargets });
          if (cancelled) return;
          await refreshPermanentTargets();
        }

        const localTargets = await invoke<string[]>("get_permanent_targets");
        if (cancelled) return;
        const normalizedLocal: string[] = [...new Set<string>((Array.isArray(localTargets) ? localTargets : [])
          .map((target) => targetKeyFor("app", target))
          .filter((target) => Boolean(target)))];
        const discoveredOwnership = discoverPermanentTargets(window.localStorage, deviceKey, normalizedLocal);
        if (!discoveredOwnership) throw new Error("Could not read permanent-target account ownership locally");
        const unclaimed = discoveredOwnership.unclaimedTargets.filter((target) => normalizedLocal.includes(target));
        const ownership = unclaimed.length
          ? claimPermanentTargets(window.localStorage, deviceKey, unclaimed, accountKeyAtStart)
          : discoveredOwnership;
        if (!ownership) throw new Error("Could not claim existing permanent targets locally");
        const uploadable = permanentTargetsOwnedByAccount(ownership, normalizedLocal, accountKeyAtStart);
        for (let offset = 0; offset < uploadable.length; offset += 500) {
          if (cancelled) return;
          await client.mutation(api.focus.addPermanentBlocks, {
            targets: uploadable.slice(offset, offset + 500).map((target) => ({
              targetKind: "windows",
              targetKey: target,
              targetLabel: target,
            })),
          });
        }
        retryNeeded = false;
      } catch (error) {
        if (!cancelled) {
          retryNeeded = true;
          console.warn("[focuslock] permanent targets remain pending account sync", error);
        }
      } finally {
        busy = false;
      }
    };
    void syncPermanentTargets();
    const retryId = window.setInterval(() => {
      if (retryNeeded) void syncPermanentTargets();
    }, 30_000);
    const onOnline = () => { void syncPermanentTargets(); };
    window.addEventListener("online", onOnline);
    return () => {
      cancelled = true;
      window.clearInterval(retryId);
      window.removeEventListener("online", onOnline);
    };
  }, [accountKey, auth.getSyncToken, configuration?.permanentBlocks, deviceId, permanentTargetsSignature, refreshPermanentTargets]);
  const { workRatio, taskBonus, setWorkRatio, setTaskBonus } =
    useSyncedPrefs(dashboard, accountKey);
  // "Eat the frog" is device-local (localStorage, like Android's DataStore).
  // This instance drives the hard-lock union and Settings; FrogCard owns its own
  // instance, so both re-read through the store's notifications.
  const frog = useFrogState();
  const signOutStrictActive = useStrictActive(Boolean(configuration?.prefs?.strictMode), configuration?.prefs?.strictEndsAt);
  const signOutPolicy = useMemo(
    () => {
      const policy = signOutProtectionPolicy(configuration, groups, Boolean(frog.state.locked));
      return policy && nuke !== undefined ? { ...policy, restricted: policy.restricted || Boolean(nuke?.isActive) } : null;
    },
    [configuration, groups, frog.state.locked, signOutStrictActive, nuke],
  );
  const signOutPolicyJson = JSON.stringify(signOutPolicy);
  const [savedSignOutPolicy, setSavedSignOutPolicy] = useState("");
  const [signOutPolicyError, setSignOutPolicyError] = useState<string | null>(null);
  useEffect(() => {
    if (!tauriAvailable()) return;
    setSavedSignOutPolicy("");
    if (!accountKey || !signOutPolicy) return;
    let cancelled = false;
    setSignOutPolicyError(null);
    invoke("sync_account_protection", { accountId: accountKey, ...signOutPolicy })
      .then(() => { if (!cancelled) setSavedSignOutPolicy(signOutPolicyJson); })
      .catch((reason) => { if (!cancelled) setSignOutPolicyError(String(reason)); });
    return () => { cancelled = true; };
  }, [accountKey, signOutPolicyJson]);
  const signOutBlockedReason = !signOutPolicy
    ? "Checking your restrictions before sign-out…"
    : signOutPolicy.restricted || signOutPolicy.strictUntilMs !== null || permanentTargets.length > 0
      ? "Sign-out is unavailable while boundaries, Strict Mode, or Nuke are active. Remove editable boundaries or finish your commitment first."
      : signOutPolicyError
        ? `Could not verify sign-out protection: ${signOutPolicyError}`
        : tauriAvailable() && savedSignOutPolicy !== signOutPolicyJson
          ? "Saving sign-out protection…"
          : null;
  const frogLock = useMemo(
    () => ({
      locked: DESKTOP_FROG_ENABLED && frog.state.locked,
      neededAppIds: DESKTOP_FROG_ENABLED ? frog.state.frog?.neededAppIds || [] : [],
      neededDomains: DESKTOP_FROG_ENABLED ? frog.state.frog?.neededDomains || [] : [],
    }),
    [frog.state.locked, frog.state.frog],
  );
  const lastHeartbeatRef = useRef(0);
  const lastUsageUploadRef = useRef(0);
  const usageJsonRef = useRef<string | null>(null);
  const uploadOwnerRef = useRef("");
  const uploadInFlightRef = useRef(false);
  // Refs mirror the latest props for the heartbeat interval below (and avoid
  // re-subscribing the effect on every tracker sample).
  const snapshotRef = useRef<NativeSnapshot | null>(snapshot);
  snapshotRef.current = snapshot;
  const trackerErrorRef = useRef<string | null>(trackerError);
  trackerErrorRef.current = trackerError;
  const [lastSyncAt, setLastSyncAt] = useState(0);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [syncWarning, setSyncWarning] = useState<string | null>(null);
  const pushRef = useRef<((force?: boolean) => Promise<void>) | null>(null);
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
  // Queue each account/device payload before uploading. Convex usage writes are
  // absolute counters, so retries are idempotent; the queue survives app and
  // machine restarts and is acknowledged only after both requests succeed.
  useEffect(() => {
    if (!tauriAvailable() || !deviceId || !accountKey) return;
    let cancelled = false;
    if (uploadOwnerRef.current !== `${accountKey}:${deviceId}`) {
      uploadOwnerRef.current = `${accountKey}:${deviceId}`;
      lastHeartbeatRef.current = 0;
      lastUsageUploadRef.current = 0;
      usageJsonRef.current = null;
      setSyncWarning(null);
    }
    const push = async (force = false) => {
      const snap = snapshotRef.current;
      if (!snap || uploadInFlightRef.current) return;
      const now = Date.now();
      // Device and display names can change without changing any tracked
      // seconds. Only usage identity and counters should trigger an upload.
      const usageJson = JSON.stringify(snap.usage.map((entry) => {
        const { targetKind, targetKey } = usageTargetFor(entry);
        return [entry.date, targetKind, targetKey, entry.activeSeconds];
      }));
      const usageChanged = usageJson !== usageJsonRef.current;
      const uploadUsage = force || (usageChanged && now - lastUsageUploadRef.current >= 4 * 60 * 60_000);
      const sendHeartbeat = force || now - lastHeartbeatRef.current >= 4 * 60 * 60_000;
      // A failed upload remains in the durable queue and is retried on the
      // next tick, even if no fresh heartbeat or usage is due.
      let queued;
      try {
        queued = peekSync(accountKey, deviceId);
      } catch (error) {
        if (!cancelled) setSyncError(`Could not read the local sync queue: ${String(error)}`);
        return;
      }
      if (!uploadUsage && !sendHeartbeat && !queued.heartbeat && !queued.usage.length) return;
      const currentBuckets: UsageBucket[] = uploadUsage
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
              updatedAt: now,
            };
          })
        : [];
      let pending;
      try {
        if (sendHeartbeat || uploadUsage) {
          // The queue requires a heartbeat alongside usage so both writes can
          // be acknowledged together. Usage-only uploads still carry one.
          enqueueSync(accountKey, deviceId, {
            deviceId,
            name: snap.device.name,
            platform: "windows",
            appVersion: "0.6.17",
            trackingStatus: snap.running ? "active" : "paused",
            statusDetail: trackerErrorRef.current || undefined,
            lastSeen: now,
          }, uploadUsage
            ? unacknowledgedUsage(
                accountUsage(accountKey, deviceId, currentBuckets),
                queued.acknowledged,
              )
            : []);
        }
        pending = peekSync(accountKey, deviceId);
      } catch (error) {
        if (!cancelled) setSyncError(`Could not save the local sync queue: ${String(error)}`);
        return;
      }
      if (!pending.heartbeat) return;
      uploadInFlightRef.current = true;
      try {
        const token = await auth.getSyncToken();
        if (!token) throw new Error("Sign in again to sync");
        const pinnedClient = accountClient(accountKey, token);
        let expiredUsage = 0;
        let oldestAcceptedDate = "";
        if (pending.usage.length) {
          for (let offset = 0; offset < pending.usage.length; offset += 500) {
            const { deviceId: _deviceId, ...heartbeat } = pending.heartbeat;
            const result: any = await pinnedClient.mutation(syncApi.recordUsageBatch, {
              deviceId,
              buckets: pending.usage.slice(offset, offset + 500),
              ...(offset === 0 ? { heartbeat } : {}),
            });
            expiredUsage += Math.max(0, Math.floor(Number(result?.expired) || 0));
            if (typeof result?.oldestAcceptedDate === "string") oldestAcceptedDate = result.oldestAcceptedDate;
          }
        } else {
          await pinnedClient.mutation(syncApi.heartbeat, pending.heartbeat);
        }
        acknowledgeSync(accountKey, deviceId, pending);
        if (!cancelled && expiredUsage > 0) {
          const windowLabel = oldestAcceptedDate
            ? `history window (before ${oldestAcceptedDate})`
            : "31-day history window";
          setSyncWarning(`Older usage outside the ${windowLabel} stays on this device and was not uploaded.`);
        }
        // Advance both clocks only after Convex accepts the upload. A failed
        // request must be retried with the same cumulative usage snapshot.
        if (uploadUsage) {
          usageJsonRef.current = usageJson;
          lastUsageUploadRef.current = Date.now();
        }
        lastHeartbeatRef.current = Date.now();
        if (!cancelled) { setLastSyncAt(Date.now()); setSyncError(null); }
      } catch (err) {
        if (!cancelled) setSyncError(`Upload pending: ${String(err)}`);
        console.warn("[focuslock] heartbeat/usage upload failed; retrying", err);
      } finally {
        uploadInFlightRef.current = false;
      }
    };
    pushRef.current = push;
    void push();
    // Upload cumulative usage and heartbeat at most every four hours. Initial
    // sign-in, explicit refresh, reconnect, and durable edits still push at once.
    const id = window.setInterval(push, 4 * 60 * 60_000);
    const onOnline = () => { void push(); };
    window.addEventListener("online", onOnline);
    return () => {
      cancelled = true;
      if (pushRef.current === push) pushRef.current = null;
      window.clearInterval(id);
      window.removeEventListener("online", onOnline);
    };
  }, [accountKey, deviceId, auth.getSyncToken]);
  // Blocked-targets payload: union of dashboard blocks + exhausted daily limits
  // + (while the frog lock is on) the whole Boundaries catalog minus the frog
  // allowlist. Recomputed on dashboard/summary/snapshot/frog changes, but invoked
  // only when the union actually changed and at most once per debounce window.
  const blockedTargetsRef = useRef<{
    targets: { appIds: string[]; domains: string[] };
    reasons: Record<string, string>;
  } | null>(null);
  const blockedInvokedJsonRef = useRef("");
  const browserPolicyJsonRef = useRef("");
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
      // Targets already match Rust; only re-send when the reasons map changed
      // (e.g. a target switched from "blocked" to "frog") since the last send.
      if (
        canonicalBlockedTargets(payload.targets) === canonicalBlockedTargets(rustTargets) &&
        json === blockedInvokedJsonRef.current
      ) {
        return;
      }
    } else if (json === blockedInvokedJsonRef.current) {
      return;
    }
    blockedInvokedJsonRef.current = json;
    invoke("set_blocked_targets", {
      targets: payload.targets,
      reasons: payload.reasons,
    }).catch(() => undefined);
  }, []);
  useEffect(() => {
    if (!tauriAvailable() || !dashboard) return;
    const { targets, reasons, exceeded } = evaluateBlockedTargets({
      dashboard,
      groups,
      summary: todayUsage,
      usage: snapshotRef.current?.usage || EMPTY_USAGE,
      frog: frogLock,
      permanentAppIds: permanentTargets,
    });
    if (exceeded.length) {
      console.info("[focuslock] daily limit reached; blocking", exceeded);
    }
    blockedTargetsRef.current = { targets, reasons };
    if (blockedTimerRef.current !== null)
      window.clearTimeout(blockedTimerRef.current);
    blockedTimerRef.current = window.setTimeout(flushBlockedTargets, 350);
    // Latest-wins: a re-run replaces the pending timer instead of cleaning up.
  }, [dashboard, groups, todayUsage, snapshot, frogLock, permanentTargets, flushBlockedTargets]);
  useEffect(
    () => () => {
      if (blockedTimerRef.current !== null) {
        window.clearTimeout(blockedTimerRef.current);
        blockedTimerRef.current = null;
      }
    },
    [],
  );
  useEffect(() => {
    if (!tauriAvailable()) return;
    // Missing auth/data is not permission to release an existing restriction.
    if (!auth.user || !dashboard || groups === undefined) return;
    const policy = browserProtectionPolicy(dashboard, groups, Boolean(frogLock?.locked));
    const json = JSON.stringify(policy);
    if (json === browserPolicyJsonRef.current) return;
    browserPolicyJsonRef.current = json;
    invoke("set_browser_protection_policy", policy).catch(() => {
      if (browserPolicyJsonRef.current === json) browserPolicyJsonRef.current = "";
    });
  }, [auth.user, dashboard, groups, frogLock]);
  // Main-window navigation from Rust: `focuslock://navigate` { view: "home" }
  // switches back to the Focus (Home) tab — used by the blocker's "Eat the frog
  // now" action.
  useEffect(() => {
    if (!tauriAvailable()) return;
    let cancelled = false;
    let unlisten: (() => void) | undefined;
    listen<{ view?: string }>("focuslock://navigate", (event) => {
      if (event.payload?.view === "home") setTab("focus");
    })
      .then((stop) => {
        if (cancelled) stop();
        else unlisten = stop;
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, []);
  // Keep the progress state until the real requests finish.
  const syncNow = useCallback(async () => {
    setSyncing(true);
    try {
      // Force mode: even if the snapshot payload is unchanged, re-apply it so
      // the upload effect re-runs with the cleared throttle.
      await refresh(true);
      if (!accountKey) throw new Error("Account identity is unavailable; sign in again.");
      const token = await auth.getSyncToken();
      if (!token) throw new Error("Sign in again to sync.");
      const replayed = await flushMutations(accountKey, token);
      for (const result of replayed.values()) {
        if (!result.ok) throw result.error;
      }
      await accountClient(accountKey, token).query(api.focus.getAccount, {});
      await pushRef.current?.(true);
    } catch (error) {
      setSyncError(String(error));
    } finally {
      setSyncing(false);
    }
  }, [refresh, accountKey, auth.getSyncToken]);
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
      {uninstallGuardError && <p role="alert" className="error-text">{uninstallGuardError}</p>}
      {DESKTOP_FROG_ENABLED && (
        <VoidLauncherBridge frog={frog.state} actions={frog.actions} workRatio={workRatio} />
      )}
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
            active={tab === "permalock"}
            icon="shield"
            label="Permalock"
            onClick={() => setTab("permalock")}
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
        {dashboard === undefined && tab !== "account" ? (
          <DashboardSkeleton />
        ) : tab === "focus" ? (
          <FocusPage
            dashboard={dashboard}
            usage={usage}
            todayUsage={todayUsage}
            todayKey={todayKey}
            localUsage={localTime?.account === accountKey && localTime.device === deviceId ? localTime.buckets : undefined}
            localDeviceId={deviceId}
            syncing={syncing}
            syncError={syncError}
            lastSyncAt={lastSyncAt}
            onSyncNow={syncNow}
            devices={devices || EMPTY_DEVICES}
            historyLoading={history === undefined}
            historyWarning={syncWarning}
            snapshot={snapshot}
            status={status}
            trackerError={trackerError}
            workRatio={workRatio}
            taskBonus={taskBonus}
            knownTargets={knownTargets}
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
            frog={frogLock}
            permanentTargets={permanentTargets}
            initialDraftMembers={pendingMerge?.members || null}
            mergeSourceLabel={pendingMerge?.sourceLabel || null}
            onInitialDraftConsumed={clearPendingMerge}
          />
        ) : tab === "permalock" ? (
          <PermalockPage
            dashboard={dashboard}
            snapshot={snapshot}
            permanentTargets={permanentTargets}
            refreshPermanentTargets={refreshPermanentTargets}
            onPermanentTargetsAdded={onPermanentTargetsAdded}
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
            frog={frog}
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
            syncError={syncError}
            syncWarning={syncWarning}
            signOutBlockedReason={signOutBlockedReason}
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
      type="button"
      className={`nav-button ${active ? "active" : ""}`}
      aria-current={active ? "page" : undefined}
      onClick={onClick}
    >
      <Icon name={icon} />
      <span>{label}</span>
    </button>
  );
}

// Permalock: the device-local list of permanently blocked apps. There is no
// remove control anywhere on this page, mirroring Rust (`add_permanent_targets`
// has no inverse) and Android. Candidates reuse the Boundaries row sources
// (synced apps union observed apps), excluding protected shell executables that
// Rust rejects anyway (`PROTECTED_APP_IDS` + FocusLock itself).
const PermalockPage = memo(function PermalockPage({
  dashboard,
  snapshot,
  permanentTargets,
  refreshPermanentTargets,
  onPermanentTargetsAdded,
}: any) {
  const [selected, setSelected] = useState<Set<string>>(() => new Set<string>());
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const permanent = useMemo(
    () => [...(permanentTargets || [])].sort(),
    [permanentTargets],
  );
  const permanentSet = useMemo(() => new Set(permanent), [permanent]);

  // Display-name lookup: synced dashboard names win, observed names fill gaps,
  // and a permanent id with no metadata at all falls back to the raw id.
  const nameByKey = useMemo(() => {
    const map = new Map<string, string>();
    (dashboard?.apps || []).forEach((app: AppItem) => {
      const key = targetKeyFor("app", app.packageName);
      if (key) map.set(key, app.appName);
    });
    (snapshot?.usage || []).forEach((entry: NativeUsage) => {
      const key = targetKeyFor("app", entry.appId);
      if (key && !map.has(key)) map.set(key, entry.appName);
    });
    return map;
  }, [dashboard, snapshot]);

  const candidates = useMemo(() => {
    const rows = new Map<string, { key: string; name: string; detail: string }>();
    (dashboard?.apps || []).forEach((app: AppItem) => {
      const key = targetKeyFor("app", app.packageName);
      if (!key || !isFrogBlockableApp(key, app.category)) return;
      rows.set(key, { key, name: app.appName, detail: app.category || "Apps" });
    });
    (snapshot?.usage || []).forEach((entry: NativeUsage) => {
      if (entry.browserDomain) return;
      const key = targetKeyFor("app", entry.appId);
      if (!key || !isFrogBlockableApp(key, "Windows")) return;
      if (!rows.has(key)) {
        rows.set(key, { key, name: entry.appName, detail: "Observed on this PC" });
      }
    });
    return [...rows.values()]
      .filter((row) => !permanentSet.has(row.key))
      .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  }, [dashboard, snapshot, permanentSet]);

  const selectedNames = useMemo(
    () =>
      [...selected]
        .map((key) => nameByKey.get(key) || key)
        .sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase())),
    [selected, nameByKey],
  );

  function toggleCandidate(key: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  async function confirmPermanent() {
    const ids = [...selected];
    if (!ids.length || busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const result = await invoke<{
        added: string[];
        rejected: { id: string; reason: string }[];
      }>("add_permanent_targets", { appIds: ids });
      onPermanentTargetsAdded?.(result?.added || []);
      await refreshPermanentTargets?.();
      setSelected(new Set());
      setConfirming(false);
      const added = result?.added?.length || 0;
      const rejected = result?.rejected || [];
      if (rejected.length) {
        const detail = rejected
          .map(
            (entry) =>
              `${entry.id} (${
                entry.reason === "protected" ? "protected system app" : entry.reason
              })`,
          )
          .join(", ");
        setNotice(
          `${added ? `${added} app${added === 1 ? "" : "s"} blocked permanently. ` : ""}Not added: ${detail}.`,
        );
      } else {
        setNotice(added === 1 ? "App blocked permanently." : `${added} apps blocked permanently.`);
      }
    } catch (error) {
      setNotice(`Couldn't add permanent blocks: ${String(error)}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <p className="eyebrow">Permanent</p>
          <h1>Permalock</h1>
          <p>
            Windows apps committed here stay blocked on this PC and sync to your FocusLock account
            when you’re signed in. You can add apps here during Strict Mode. No timers, credits,
            emergency passes, or in-app removal.
          </p>
        </div>
      </header>

      {notice && <p className="boundary-notice">{notice}</p>}

      <section className="settings-group group-section">
        <div className="section-heading">
          <div>
            <p className="section-label">On this device</p>
            <h2>Permanently blocked</h2>
          </div>
          <span className="permanent-badge">{permanent.length} permanent</span>
        </div>
        {permanent.length ? (
          <div className="boundary-list">
            {permanent.map((id) => {
              const name = nameByKey.get(id) || id;
              return (
                <article className="boundary-row" key={id}>
                  <span className="letter-icon">{name.charAt(0).toUpperCase()}</span>
                  <div>
                    <strong>{name}</strong>
                    <p>
                      <span className="category-label">{id}</span> · Windows permanent · No expiry
                    </p>
                  </div>
                  <span className="permanent-badge">Permanent</span>
                </article>
              );
            })}
          </div>
        ) : (
          <EmptyState
            title="Nothing is permanent yet"
            body="Choose apps below to block them permanently. Permanent blocks cannot be removed in FocusLock."
          />
        )}
      </section>

      <section className="settings-group">
        <div className="section-heading">
          <div>
            <p className="section-label">Add permanently</p>
            <h2>Choose apps</h2>
          </div>
          <button
            type="button"
            className="primary-button"
            disabled={!selected.size || busy}
            onClick={() => setConfirming(true)}
          >
            Block permanently
          </button>
        </div>
        <p className="group-hint">
          Permanently blocked apps stay blocked indefinitely. FocusLock will not offer credits,
          emergency passes, grace time, or an in-app removal control for them.
        </p>
        {candidates.length ? (
          <div className="boundary-list">
            {candidates.map((row) => {
              const picked = selected.has(row.key);
              return (
                <article className="boundary-row selecting" key={row.key}>
                  <button
                    type="button"
                    className={`pick-box ${picked ? "selected" : ""}`}
                    aria-pressed={picked}
                    aria-label={`Select ${row.name} for permanent blocking`}
                    onClick={() => toggleCandidate(row.key)}
                  >
                    <Icon name="check" size={14} />
                  </button>
                  <span className="letter-icon">{row.name.charAt(0).toUpperCase()}</span>
                  <div>
                    <strong>{row.name}</strong>
                    <p>
                      <span className="category-label">{row.detail}</span> · {row.key}
                    </p>
                  </div>
                </article>
              );
            })}
          </div>
        ) : (
          <EmptyState
            title="No apps to choose"
            body="Every eligible app is already permanent, or only protected system apps have been observed."
          />
        )}
      </section>

      {confirming && (
        <div
          className="boundary-dialog-backdrop"
          role="presentation"
          onClick={() => !busy && setConfirming(false)}
        >
          <div
            className="boundary-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="Confirm permanent block"
            onClick={(event) => event.stopPropagation()}
          >
            <h2>Permanently block {selectedNames.join(", ")}</h2>
            <p>
              This app will stay blocked indefinitely. FocusLock will not offer credits, emergency
              passes, grace time, or an in-app removal control for it.
            </p>
            <div className="dialog-actions">
              <button
                type="button"
                className="secondary-button"
                disabled={busy}
                onClick={() => setConfirming(false)}
              >
                Cancel
              </button>
              <button
                type="button"
                className="danger-button"
                disabled={busy}
                onClick={() => void confirmPermanent()}
              >
                {busy ? "Blocking…" : "Block permanently"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
});

// Memoized: all props are stable between tracker polls (snapshot/status are
// change-detected upstream, devices uses a stable EMPTY fallback), so
// unrelated DesktopApp state updates skip re-rendering the whole page.
const FocusPage = memo(function FocusPage({
  dashboard,
  usage: syncedUsage,
  todayUsage,
  todayKey,
  localUsage,
  localDeviceId,
  syncing,
  syncError,
  lastSyncAt,
  onSyncNow,
  devices,
  historyLoading,
  historyWarning,
  snapshot,
  status,
  trackerError,
  workRatio,
  taskBonus,
  knownTargets,
  usageRange = "today",
  onUsageRangeChange,
  onMerge,
}: any) {
  const usage = useMemo(() => usageRange === "today"
    ? mergeLiveTodayUsage(syncedUsage, localUsage, localDeviceId, snapshot?.device.name || "Windows PC", todayKey)
    : syncedUsage, [syncedUsage, localUsage, localDeviceId, snapshot?.device.name, todayKey, usageRange]);
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
  // Picker catalog for the frog allowlist: the same synced sources the Boundaries
  // rows render from — dashboard apps/sites, every known target, plus this PC's
  // observed/running apps from the tracker snapshot.
  const frogCatalog = useMemo(() => {
    const apps = new Map<string, string>();
    (dashboard?.apps || []).forEach((app: AppItem) => {
      const key = String(app.packageName || "").toLowerCase();
      if (key) apps.set(key, app.appName || app.packageName);
    });
    (knownTargets || []).forEach((target: KnownTarget) => {
      if (target.targetKind !== "app") return;
      const key = String(target.targetKey || "").toLowerCase();
      if (key) apps.set(key, target.targetLabel || target.targetKey);
    });
    for (const entry of snapshot?.usage || []) {
      if (entry.browserDomain) continue;
      const key = String(entry.appId || "").toLowerCase();
      if (key) apps.set(key, entry.appName || entry.appId);
    }
    const sites = new Map<string, string>();
    (dashboard?.sites || []).forEach((site: SiteItem) => {
      const key = normalizeSiteKey(site.domain);
      if (key) sites.set(key, site.displayName || site.domain);
    });
    (knownTargets || []).forEach((target: KnownTarget) => {
      if (target.targetKind !== "website") return;
      const key = normalizeSiteKey(target.targetKey);
      if (key) sites.set(key, target.targetLabel || target.targetKey);
    });
    const byLabel = (a: { label: string }, b: { label: string }) =>
      a.label.toLowerCase().localeCompare(b.label.toLowerCase());
    return {
      apps: [...apps.entries()].map(([key, label]) => ({ key, label })).sort(byLabel),
      sites: [...sites.entries()].map(([key, label]) => ({ key, label })).sort(byLabel),
    };
  }, [dashboard?.apps, dashboard?.sites, knownTargets, snapshot?.usage]);
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
  const focusSeconds = todayWorkSeconds(dashboard?.state, todayKey);
  const focusMinutes = Math.floor(focusSeconds / 60);
  const leisureSeconds = boundaryLeisureSeconds(todayUsage, localUsage, localDeviceId, todayKey, dashboard);
  const tasksDone = state.lastResetDate === todayKey ? state.tasksCompletedToday || 0 : 0;
  const visibleRecords = showAllHistory ? records : records.slice(0, 3);
  function jumpToSection(index: number) {
    const node = [ringsRef.current, graphRef.current, historyRef.current][index];
    node?.scrollIntoView({ behavior: "smooth", block: "start" });
  }
  return (
    <div className="page focus-page">
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
      {DESKTOP_FROG_ENABLED && <FrogCard catalog={frogCatalog} />}
      <section className="today-time" aria-label="Today's time">
        <div className="section-heading">
          <h2>Today's time</h2>
          <button className="secondary-button" onClick={onSyncNow} disabled={syncing}>
            {syncing ? "Syncing…" : "Sync now"}
          </button>
        </div>
        <div className="time-pair">
          <article>
            <p>Focused work</p><strong>{fmt(focusSeconds)}</strong>
            <small>Logged work across devices</small>
          </article>
          <article>
            <p>Leisure</p><strong>{leisureSeconds === null ? "—" : fmt(leisureSeconds)}</strong>
            <small>Time in Boundary apps and websites</small>
          </article>
        </div>
        <p className="time-ratio">{timeRatio(focusSeconds, leisureSeconds, workRatio)}</p>
        <p className={`time-sync ${syncError ? "error" : ""}`} role="status">
          {syncing ? "Syncing your time across devices…" : syncError ? `Sync failed · ${syncError}`
            : todayUsage === undefined ? "Loading synced leisure · Showing available time from this PC"
            : !todayUsage.deviceTargets ? "Available measurements · Leisure is a minimum until all device measurements are available"
            : lastSyncAt ? `Synced time + current PC activity · PC uploaded ${new Date(lastSyncAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`
            : "Synced time + current PC activity · Sync now to send the latest PC time"}
        </p>
      </section>
      <section className="hero-balance">
        <div>
          <p className="section-label">Available leisure credits</p>
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
          {usageRange === "today" && usage?.deviceTargets ? "Synced screen time plus current PC activity."
            : "Synced screen time across devices. Sync now to include the latest PC activity."}
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
          label="Tasks finished"
          value={String(tasksDone)}
        />
        <Metric
          label="Credits spent today"
          value={fmt(state.lastResetDate === todayKey ? state.totalScrollSecondsToday || 0 : 0)}
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
        {historyWarning && <p className="sync-window-warning">{historyWarning}</p>}
        {historyLoading ? (
          <p className="history-loading">Loading synced work history…</p>
        ) : records.length ? (
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
  const recordWork = useDurableMutation(api.focus.recordWork);
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
    setLeft(minutes * 60);
  }, [minutes]);
  useEffect(() => {
    if (!running) return;
    const id = window.setInterval(
      () => setLeft((v) => Math.max(0, v - 1)),
      1000,
    );
    return () => clearInterval(id);
  }, [running]);
  // FROG METERING: while the timer runs, credit the armed frog one second at a
  // time in ~5s batches (and flush on pause/stop/unmount), so localStorage is
  // not written every tick. addTrackedSeconds itself ignores frogs that are not
  // armed+selected+incomplete, so an idle frog never accrues.
  const frogPendingRef = useRef(0);
  const flushFrogPendingRef = useRef(() => {});
  flushFrogPendingRef.current = () => {
    if (frogPendingRef.current > 0) {
      addTrackedSeconds(frogPendingRef.current);
      frogPendingRef.current = 0;
    }
  };
  useEffect(() => {
    if (!running) return;
    const id = window.setInterval(() => {
      frogPendingRef.current += 1;
      if (frogPendingRef.current >= 5) {
        addTrackedSeconds(frogPendingRef.current);
        frogPendingRef.current = 0;
      }
    }, 1000);
    return () => {
      window.clearInterval(id);
      flushFrogPendingRef.current();
    };
  }, [running]);
  useEffect(() => {
    const onExclusiveTimer = (event: Event) => {
      const owner = (event as CustomEvent<{ owner?: string }>).detail?.owner;
      if (owner === "focus" && !running) return;
      if (owner !== "focus" && running) {
        flushFrogPendingRef.current();
        setRunning(false);
      }
    };
    window.addEventListener("focuslock:exclusive-timer", onExclusiveTimer);
    return () => window.removeEventListener("focuslock:exclusive-timer", onExclusiveTimer);
  }, [running]);
  // The history entry and earned credit commit atomically and deduplicate by record ID.
  useEffect(() => {
    if (left !== 0 || saved) return;
    setRunning(false);
    setSaved(true);
    const ratio = Math.max(1, ratioRef.current || 4);
    const earned = Math.floor(minutes / ratio);
    const now = Date.now();
    const recordId = `windows_${now}`;
    recordWork({
      recordId, title: "Desktop focus", durationMinutes: minutes,
      timestamp: now, source: "DESKTOP_TIMER", earnedMinutesCredited: earned,
      date: localDate(now), tasksCompleted: 0,
    }).catch((err) => console.warn("[focuslock] focus timer saved for retry", err));
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
          if (!running) {
            window.dispatchEvent(new CustomEvent("focuslock:exclusive-timer", { detail: { owner: "focus" } }));
          }
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
  const recordWork = useDurableMutation(api.focus.recordWork);
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
      const label = title.trim() || "Manual work log";
      await recordWork({
        recordId: id, title: label, durationMinutes: mins,
        timestamp: now, source: "DESKTOP_MANUAL", earnedMinutesCredited: earned,
        date: localDate(now), tasksCompleted: taskCount,
      });
      addTrackedSeconds(mins * 60);
      setDone(
        `Logged ${mins}m${taskCount ? ` + ${taskCount} task${taskCount === 1 ? "" : "s"}` : ""} → +${earned}m earned.`,
      );
    } catch (err) {
      setDone(String(err));
      console.warn("[focuslock] manual work log saved for retry", err);
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
  // Device-local permanent block (Rust `permanent_targets`): always blocked and
  // never removable from this page.
  permanent: boolean;
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
  frog,
  permanentTargets = [],
  initialDraftMembers,
  mergeSourceLabel,
  onInitialDraftConsumed,
}: any) {
  const saveApps = useDurableMutation(api.focus.saveBlockedApps);
  const saveSites = useDurableMutation(api.focus.saveBlockedWebsites);
  const saveSite = useDurableMutation(api.focus.setBlockedWebsite);
  const saveGroups = useDurableMutation(syncApi.saveGroups);
  const apps: AppItem[] = dashboard?.apps || [];
  const permanentWebsites = useMemo(() => new Map<string, string>(
    (dashboard?.permanentBlocks || [])
      .filter((target: any) => target?.targetKind === "website")
      .map((target: any) => [normalizeSiteKey(target.targetKey), String(target.targetLabel || target.targetKey)] as const)
      .filter(([key]: [string, string]) => Boolean(key)),
  ), [dashboard?.permanentBlocks]);
  const sites: SiteItem[] = useMemo(() => {
    const rows = new Map<string, SiteItem>();
    (dashboard?.sites || []).forEach((site: SiteItem) => {
      const key = normalizeSiteKey(site.domain);
      const accountLabel = permanentWebsites.get(key);
      rows.set(key, accountLabel
        ? { ...site, displayName: site.displayName || accountLabel, isBlocked: true, permanent: true }
        : site);
    });
    for (const [domain, displayName] of permanentWebsites) {
      if (!rows.has(domain)) {
        rows.set(domain, {
          domain,
          displayName,
          isBlocked: true,
          category: "Permanent",
          permanent: true,
          accountPermanent: true,
        });
      }
    }
    return [...rows.values()];
  }, [dashboard?.sites, permanentWebsites]);
  const strictActive = useStrictActive(Boolean(dashboard?.prefs?.strictMode), dashboard?.prefs?.strictEndsAt);
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
    if (!guardUnlock(false)) return;
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
    if (!guardUnlock(false)) return;
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

  const permanentSet = useMemo(
    () =>
      new Set(
        (permanentTargets || [])
          .map((id: string) => targetKeyFor("app", id))
          .filter(Boolean),
      ),
    [permanentTargets],
  );

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
          permanent: false,
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
        permanent: false,
      });
    });
    // Anything seen only once on Windows with no time today is system/idle noise.
    const known = new Set(apps.map((a) => a.packageName));
    byKey.forEach((row) => {
      if (!known.has(row.key) && row.minutes === 0) row.system = true;
    });
    const rows = [...byKey.values()];
    rows.forEach((row) => {
      if (!permanentSet.has(row.key)) return;
      // Rust unions permanent ids back into every payload, so the row must
      // never render as removable: that would contradict enforcement.
      row.isBlocked = true;
      row.permanent = true;
    });
    return rows.sort((a, b) =>
      a.name.toLowerCase().localeCompare(b.name.toLowerCase()),
    );
  }, [apps, nativeUsage, permanentSet]);

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

  // Strict Mode freezes boundary weakening, while still allowing new protections.
  // Boundaries Lock has the same removal/unblocking restriction when ON.
  function guardUnlock(isUnblocking: boolean): boolean {
    if ((strictActive || boundariesLock) && isUnblocking) {
      setNotice(strictActive
        ? "Strict Mode lets you add protections, but existing boundaries stay blocked until the commitment ends."
        : "Boundaries Lock is ON — turn it off in Settings to change this.");
      return false;
    }
    return true;
  }
  async function applyNative(nextApps: BoundaryAppRow[], nextSites: SiteItem[]) {
    if (!tauriAvailable()) return;
    // Send the SAME union the enforcement effect sends (dashboard blocks +
    // exhausted limits/groups + the frog hard lock), with the in-flight toggle
    // folded into the dashboard shape. A base-only payload here replaced Rust's
    // blocked-target set and transiently dropped over-limit groups/frog targets
    // until the next snapshot tick (~5s).
    const { targets, reasons } = evaluateBlockedTargets({
      dashboard: {
        ...(dashboard || {}),
        apps: toAppItems(nextApps),
        sites: nextSites.map(stripSite),
      },
      groups,
      summary: todayUsage,
      usage: snapshot?.usage || EMPTY_USAGE,
      frog,
      permanentAppIds: permanentTargets,
    });
    await invoke("set_blocked_targets", { targets, reasons }).catch(() => undefined);
  }

  async function persistApps(toggled: BoundaryAppRow[], nextApps: BoundaryAppRow[], message: string) {
    if (!guardUnlock(false)) return;
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
    if (!guardUnlock(false)) return;
    setBusy(true);
    try {
      const saveableSites = nextSites.filter((site) => !site.accountPermanent);
      const removed = sites.some((site) => !site.accountPermanent && !nextSites.some((next) => next.domain === site.domain));
      const results = removed
        ? [await saveSites({ sites: saveableSites.map(stripSite), updatedAt: Date.now() })]
        : await Promise.all(saveableSites.filter((next) => {
            const previous = sites.find((site) => site.domain === next.domain);
            return !previous || previous.isBlocked !== next.isBlocked;
          }).map((site) => saveSite({ domain: site.domain, displayName: site.displayName,
            category: site.category, isBlocked: site.isBlocked, updatedAt: Date.now() })));
      if (results.some((result) => result?.applied === false)) throw new Error("Another device updated your websites. Reload and retry.");
      await applyNative(appRows, nextSites);
      setNotice(message);
    } catch (e) {
      setNotice(`Couldn't save changes: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function toggleApp(row: BoundaryAppRow) {
    if (row.permanent) {
      // Rust would union the id straight back in; never pretend it can be
      // removed. Mirrors Android's removal feedback.
      setNotice("Permanent blocks cannot be removed in FocusLock.");
      return;
    }
    if (!guardUnlock(row.isBlocked)) return;
    const toggled = { ...row, isBlocked: !row.isBlocked };
    const nextApps = appRows.map((item) => (item.key === row.key ? toggled : item));
    await persistApps(
      [toggled],
      nextApps,
      `${toggled.isBlocked ? "Blocked" : "Allowed"} ${row.name}.`,
    );
  }

  async function toggleSite(site: SiteItem) {
    if (site.permanent) {
      setNotice("Permanent website blocks cannot be removed in FocusLock.");
      return;
    }
    if (!guardUnlock(site.isBlocked)) return;
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
    // Permanent rows stay blocked: the envelope save would otherwise flip
    // `isBlocked` locally until Rust re-merged the id on the next flush.
    const targets = appRows.filter((row) => row.isBlocked && !row.permanent);
    if (!targets.length) {
      setNotice(
        appRows.some((row) => row.permanent)
          ? "Permanent blocks cannot be removed in FocusLock."
          : "No blocked apps to unblock.",
      );
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
    const targets = sites.filter((site) => site.isBlocked && !site.permanent);
    if (!targets.length) {
      setNotice(sites.some((site) => site.permanent)
        ? "Permanent website blocks cannot be removed in FocusLock."
        : "No blocked websites to unblock.");
      return;
    }
    const nextSites = sites.map((site) => site.permanent ? site : { ...site, isBlocked: false });
    await persistSites(nextSites, `Unblocked ${targets.length} site(s).`);
  }

  async function submitAddSite() {
    if (busy) return;
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
    setSiteError(null);
    try {
      const newSite: SiteItem = {
        domain: normalized,
        displayName: normalized,
        isBlocked: true,
        category: "Custom",
        isCustom: true,
      };
      const nextSites: SiteItem[] = [
        ...sites,
        newSite,
      ];
      // Add only this domain: a stale desktop snapshot must never replace
      // another device's changes or upload synthetic permanent-block rows.
      const result = await saveSite({
        domain: newSite.domain,
        displayName: newSite.displayName,
        isBlocked: newSite.isBlocked,
        category: newSite.category,
        updatedAt: Date.now(),
      });
      if (result?.applied !== true) throw new Error("Your website wasn't confirmed by sync. Refresh and retry.");
      // Clear the picker before handing the new blocked target to Rust. The
      // Windows UI Automation reader can observe an edit control for one more
      // sample while the native payload is being applied; leaving the typed
      // value mounted would make a transient `x.com` look like a navigated
      // browser domain.
      setShowAddSite(false);
      setSiteInput("");
      setSiteError(null);
      setQ("");
      setDebouncedQ("");
      setCategory("All");
      await applyNative(appRows, nextSites);
      setNotice(`Added ${normalized}.`);
    } catch (e) {
      setSiteError(`Couldn't add: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }

  async function deleteSite(site: SiteItem) {
    if (site.permanent) {
      setNotice("Permanent website blocks cannot be removed in FocusLock.");
      return;
    }
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

      {(boundariesLock || strictActive) && (
        <p className="boundary-notice lock-notice">
          {strictActive ? "Strict Mode is active — you can add protections, but can't remove or unblock existing boundaries until the commitment ends." : "Boundaries Lock is on — blocked apps and websites can't be removed or unblocked."}
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
              disabled={busy || strictActive}
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
                        disabled={busy || strictActive}
                        onClick={() => openEditGroup(group)}
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        className="site-delete"
                        disabled={busy || strictActive}
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
            aria-label={kind === "apps" ? "Search apps" : "Search websites"}
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
            disabled={busy}
            title={strictActive ? "Add this website as a blocked boundary during Strict Mode" : undefined}
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
          disabled={busy || boundariesLock || strictActive}
          title={
            strictActive
              ? "Strict Mode keeps boundaries until the commitment ends"
              : boundariesLock
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
              disabled={selectedMembers.length < 2 || busy || strictActive}
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
                {row.permanent && <span className="permanent-badge">Permanent</span>}
                <button
                  className={`switch ${row.isBlocked ? "on" : ""} ${row.permanent ? "permanent" : ""}`}
                  disabled={busy || (strictActive && row.isBlocked)}
                  aria-disabled={row.permanent || undefined}
                  title={
                    row.permanent
                      ? "Permanent blocks cannot be removed in FocusLock."
                      : undefined
                  }
                  onClick={() => toggleApp(row)}
                  aria-label={
                    row.permanent
                      ? `${row.name} is permanently blocked`
                      : `${row.isBlocked ? "Allow" : "Block"} ${row.name}`
                  }
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
                    <span className="category-label">{site.permanent ? "Permanent" : site.category}</span> ·{" "}
                    {site.domain}
                    {siteMinutes.get(normalizeSiteKey(site.domain))
                      ? ` · ${Math.round((siteMinutes.get(normalizeSiteKey(site.domain)) || 0) / 60)} min today`
                      : ""}
                  </p>
                </div>
                {site.isCustom && !site.permanent && (
                  <button
                    className="site-delete"
                    disabled={busy || strictActive}
                    onClick={() => deleteSite(site)}
                    aria-label={`Remove ${site.domain}`}
                  >
                    ×
                  </button>
                )}
                <button
                  className={`switch ${site.isBlocked ? "on" : ""} ${site.permanent ? "permanent" : ""}`}
                  disabled={busy || (strictActive && site.isBlocked) || site.permanent}
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
          onClick={() => !busy && setShowAddSite(false)}
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
              aria-label="Website domain"
              disabled={busy}
              onChange={(e) => {
                setSiteInput(e.target.value);
                setSiteError(null);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  void submitAddSite();
                }
              }}
              placeholder="example.com or https://example.com/page"
              aria-invalid={Boolean(siteError)}
            />
            {siteError && <p className="inline-error" role="alert">{siteError}</p>}
            <div className="dialog-actions">
              <button
                type="button"
                className="secondary-button"
                disabled={busy}
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
                {busy ? "Adding…" : "Add Website"}
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

function clampFrogMinutes(minutes: number): number {
  const value = Math.trunc(Number(minutes));
  if (!Number.isFinite(value)) return FROG_UI_MIN_REQUIRED_MINUTES;
  return Math.min(FROG_UI_MAX_REQUIRED_MINUTES, Math.max(FROG_UI_MIN_REQUIRED_MINUTES, value));
}

function frogWakeLabel(hour: number): string {
  return `${String(Math.min(23, Math.max(0, Math.trunc(hour) || 0))).padStart(2, "0")}:00`;
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
  frog,
  boundariesLock,
  setBoundariesLock,
  lastSyncAt,
  syncing,
  onSyncNow,
}: any) {
  const [busy, setBusy] = useState(false);
  const [browserProtectionBusy, setBrowserProtectionBusy] = useState(false);
  const [trackerNotice, setTrackerNotice] = useState<string | null>(null);
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
  const syncFresh = syncAgeMs !== null && syncAgeMs < 4 * 60 * 60_000 + 5 * 60_000;
  const syncStatusText = !auth.user
    ? "Signed out — sign in to upload usage"
    : syncing
      ? "Syncing now…"
      : syncAgeMs === null
        ? "Waiting for first upload"
        : syncFresh
          ? "Up to date"
          : `Last upload ${Math.round(syncAgeMs / 1000)}s ago`;
  const trackerIssue = status?.lastError || trackerError || null;
  async function toggle() {
    if (!tauriAvailable()) return;
    setBusy(true);
    setTrackerNotice(null);
    try {
      await invoke(running ? "stop_tracking" : "start_tracking");
      await refresh();
    } catch (error) {
      // Rust refuses to pause while permanent blocks exist. `running` comes
      // from the next snapshot, so nothing flips optimistically; surface the
      // rejection so the on switch visibly cannot be turned off.
      setTrackerNotice(
        typeof error === "string" && error
          ? error
          : "Could not change the tracker state. Try again.",
      );
    } finally {
      setBusy(false);
    }
  }
  const prefs = dashboard?.prefs || {};
  const savePrefs = useDurableMutation(syncApi.savePrefs);
  const [prefsBusy, setPrefsBusy] = useState(false);
  const strictActive = useStrictActive(Boolean(prefs.strictMode), prefs.strictEndsAt);
  const [strictHours, setStrictHours] = useState(24);
  const [strictError, setStrictError] = useState<string | null>(null);
  const [strictUntil, setStrictUntil] = useState("");
  const [strictPlan, setStrictPlan] = useState<"duration" | "until">("duration");
  const strictUntilInput = strictUntil;
  async function setStrictMode(next: boolean) {
    if (!next && strictActive) return;
    const selectedEnd = strictPlan === "until"
      ? new Date(strictUntil).getTime()
      : Date.now() + strictHours * 60 * 60 * 1000;
    const error = next ? strictEndError(selectedEnd, Date.now()) : null;
    setStrictError(error);
    if (error) return;
    setPrefsBusy(true);
    try {
      await savePrefs({
        strictMode: next,
        weeklyReport: prefs.weeklyReport ?? false,
        dailyReminderMinutes: prefs.dailyReminderMinutes,
        globalDailyCapMinutes: prefs.globalDailyCapMinutes,
        strictEndsAt: next ? selectedEnd : 0,
        strictPreset: prefs.strictPreset || "custom",
        strictNukeAfterFive: false,
        updatedAt: Math.max(Date.now(), prefs.updatedAt || 0),
      });
    } catch (err) {
      setStrictError(err instanceof Error ? err.message : "Could not save your commitment. Try again.");
      console.warn("[focuslock] savePrefs failed", err);
    } finally {
      setPrefsBusy(false);
    }
  }
  async function updateStrictPlan(plan: "duration" | "until", value?: string | number) {
    const endsAt = plan === "duration"
      ? Date.now() + Math.min(MAX_STRICT_HOURS, Math.max(1, Number(value) || 24)) * 60 * 60 * 1000
      : new Date(String(value || strictUntilInput)).getTime();
    const error = strictEndError(endsAt, Date.now(), strictActive ? prefs.strictEndsAt : 0);
    setStrictError(error);
    if (error) return;
    setPrefsBusy(true);
    try {
      await savePrefs({ strictMode: true, strictEndsAt: endsAt, strictPreset: prefs.strictPreset || "custom", strictNukeAfterFive: false, updatedAt: Date.now() });
    } catch (err) {
      setStrictError(err instanceof Error ? err.message : "Could not extend your commitment. Try again.");
    } finally { setPrefsBusy(false); }
  }
  async function setStrictPreset(preset: string) {
    const plan = preset === "exam" ? 4 : preset === "deep_work" ? 2 : preset === "sleep" ? 8 : 24;
    setStrictHours(plan);
    setStrictPlan("duration");
    setPrefsBusy(true);
    try {
      await savePrefs({ strictPreset: preset, strictNukeAfterFive: false, updatedAt: Date.now() });
    } finally { setPrefsBusy(false); }
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
        {trackerNotice && (
          <p className="boundary-notice" role="alert">
            {trackerNotice}
          </p>
        )}
        <div className="setting-row">
          <span className="setting-icon"><Icon name="clock" /></span>
          <div>
            <strong>Strict commitment</strong>
            <p>{strictActive && prefs.strictEndsAt ? `Active until ${new Date(prefs.strictEndsAt).toLocaleString()}. Windows uninstall is locked during this commitment.` : "Lock boundary settings and Windows uninstall for a duration. App access follows your existing rules."}</p>
            <div className="setting-inline">
              <select value={prefs.strictPreset || "custom"} onChange={(e) => void setStrictPreset(e.target.value)} aria-label="Strict mode preset">
                <option value="custom">Custom</option>
                <option value="deep_work">Deep work · 2h</option>
                <option value="exam">Exam · 4h</option>
                <option value="sleep">Sleep · 8h</option>
              </select>
              <select value={strictPlan} onChange={(e) => setStrictPlan(e.target.value as "duration" | "until")} aria-label="Strict mode timing">
                <option value="duration">For a duration</option>
                <option value="until">Until a time</option>
              </select>
              {strictPlan === "duration" ? (
                <select value={strictHours} onChange={(e) => setStrictHours(Number(e.target.value))} aria-label="Strict mode duration">
                  {STRICT_HOUR_OPTIONS.map((h) => <option key={h} value={h}>{strictDurationLabel(h)}</option>)}
                </select>
              ) : (
                <input type="datetime-local" value={strictUntilInput} onChange={(e) => setStrictUntil(e.target.value)} aria-label="Strict mode end time" />
              )}
            </div>
            <button onClick={() => void (strictActive ? updateStrictPlan(strictPlan, strictPlan === "until" ? strictUntil : strictHours) : setStrictMode(true))}
              disabled={prefsBusy || (strictPlan === "until" && !strictUntil)}>
              {strictActive ? "Extend commitment" : "Start commitment"}
            </button>
            {strictError && <p role="alert">{strictError}</p>}
          </div>
        </div>
        <ApprovalUnlockPanel />
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
          icon="shield"
          title="Browser extension protection"
          detail={auth.user
            ? "Optional website-rule protection. When enabled, FocusLock checks the extension connection and closes the affected browser window after a 60-second grace period if it stays unavailable. Allow access to all websites; incognito access is optional."
            : status?.browserProtectionEnabled
              ? "Protection is paused until you sign in. You can turn it off here."
              : "Sign in to enable optional browser extension protection."}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <span className="setting-value" role="status">
              {browserProtectionStatusLabel(status, Boolean(auth.user))}
            </span>
            <button
              className={`switch ${status?.browserProtectionEnabled ? "on" : ""}`}
              type="button"
              role="switch"
              aria-label="Browser extension protection"
              aria-checked={Boolean(status?.browserProtectionEnabled)}
              disabled={browserProtectionBusy || (!auth.user && !status?.browserProtectionEnabled)}
              onClick={async () => {
                if (browserProtectionBusy || !tauriAvailable()) return;
                const enabled = !status?.browserProtectionEnabled;
                if (enabled && !auth.user) return;
                setBrowserProtectionBusy(true);
                try {
                  await invoke<boolean>("set_browser_protection_enabled", { enabled });
                  await refresh();
                } catch (error) {
                  setTrackerNotice(`Could not update browser protection: ${String(error)}`);
                } finally {
                  setBrowserProtectionBusy(false);
                }
              }}
            >
              <span />
            </button>
          </div>
        </SettingRow>
        {status?.browserProtectionEnabled && auth.user && status.browserProtectionScanState === "scan_error" && (
          <p className="boundary-notice" role="alert">
            Browser check unavailable: {status.browserProtectionError || "Could not inspect browser windows."}
          </p>
        )}
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
          detail={strictActive ? "You can add blocked boundaries; existing protections and other settings stay locked until the selected end time." : "Choose a duration above to lock boundary settings."}
        >
          <button
            className={`switch ${prefs.strictMode ? "on" : ""}`}
            onClick={() => setStrictMode(!prefs.strictMode)}
            disabled={prefsBusy || strictActive}
          >
            <span />
          </button>
        </SettingRow>
      </section>
      {!DESKTOP_FROG_ENABLED ? (
        <section className="settings-group">
          <h2>Eat the Frog</h2>
          <p className="frog-settings-note">Eat the Frog is temporarily disabled on desktop.</p>
        </section>
      ) : (
      <section className="settings-group">
        <h2>Eat the Frog</h2>
        <SettingRow
          icon="lock"
          title="Eat the Frog"
          detail="Hard-lock every boundary app and website until today's frog is ticked off with enough focus tracked. Device-local, like Android."
        >
          <button
            className={`switch ${frog?.state?.enabled ? "on" : ""}`}
            role="switch"
            aria-checked={Boolean(frog?.state?.enabled)}
            aria-label="Toggle Eat the Frog"
            onClick={() => frog?.actions?.setEnabled(!frog?.state?.enabled)}
          >
            <span />
          </button>
        </SettingRow>
        {frog?.state?.enabled && (
          <>
            <div className="setting-row">
              <span className="setting-icon">
                <Icon name="clock" />
              </span>
              <div>
                <strong>Focus minutes required</strong>
                <p>
                  Tracked focus on the selected frog ({FROG_UI_MIN_REQUIRED_MINUTES}–
                  {FROG_UI_MAX_REQUIRED_MINUTES} min). The lock releases when the frog is
                  ticked off and this much focus is tracked.
                </p>
                <input
                  type="range"
                  min={FROG_UI_MIN_REQUIRED_MINUTES}
                  max={FROG_UI_MAX_REQUIRED_MINUTES}
                  step={5}
                  value={clampFrogMinutes(frog?.state?.requiredMinutes)}
                  onChange={(e) => frog?.actions?.setRequiredMinutes(Number(e.target.value))}
                  aria-label="Frog focus minutes required"
                  style={{ width: "100%" }}
                />
              </div>
              <span className="setting-value">
                {clampFrogMinutes(frog?.state?.requiredMinutes)}m
              </span>
            </div>
            <div className="setting-row">
              <span className="setting-icon">
                <Icon name="clock" />
              </span>
              <div>
                <strong>Wake hour</strong>
                <p>
                  24-hour clock (5 = 05:00). The frog arms on the first open at/after it, and
                  progress resets at the next one.
                </p>
                <input
                  type="range"
                  min={0}
                  max={23}
                  step={1}
                  value={Math.min(23, Math.max(0, Math.trunc(frog?.state?.wakeHour) || 0))}
                  onChange={(e) => frog?.actions?.setWakeHour(Number(e.target.value))}
                  aria-label="Frog wake hour"
                  style={{ width: "100%" }}
                />
              </div>
              <span className="setting-value">{frogWakeLabel(frog?.state?.wakeHour)}</span>
            </div>
            <p className="frog-settings-note">
              On the first open after {frogWakeLabel(frog?.state?.wakeHour)}, every boundary app
              and website locks until today's frog is ticked off and{" "}
              {clampFrogMinutes(frog?.state?.requiredMinutes)} minutes of focus are tracked.
              Progress resets at the next {frogWakeLabel(frog?.state?.wakeHour)}.
            </p>
          </>
        )}
      </section>
      )}
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
              detail="Uploads screen time and device status every four hours while signed in, with immediate checks on sign-in, edits, and reconnect. Use Sync Now for an immediate refresh."
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
  syncError,
  syncWarning,
  signOutBlockedReason,
}: {
  devices: any[];
  lastSyncAt?: number;
  syncing?: boolean;
  onSyncNow?: () => void;
  trackerError?: string | null;
  syncError?: string | null;
  syncWarning?: string | null;
  signOutBlockedReason?: string | null;
}) {
  const auth = useFocusAuth();
  const user = auth.user;
  const isSignedIn = Boolean(user);
  const syncAgeMs = lastSyncAt ? Date.now() - lastSyncAt : null;
  const syncFresh = syncAgeMs !== null && syncAgeMs < 4 * 60 * 60_000 + 5 * 60_000;
  const syncStatus = syncing
    ? "Syncing now…"
    : syncError
      ? syncError
    : trackerError
      ? `Tracker issue: ${trackerError}`
      : syncAgeMs === null
        ? "Waiting for first upload"
        : syncFresh
          ? "Up to date"
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
              <Icon name="check" /> Signed in
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
            <strong>Account sync</strong>
            <p>
              Use the same account on every device. Saved changes retry when
              connected; devices need to be online to receive new boundaries.
            </p>
            {syncWarning && <p className="sync-window-warning">{syncWarning}</p>}
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
                  className={`status-badge ${Date.now() - d.lastSeen < 20 * 60_000 ? "ok" : ""}`}
                >
                  <span />
                  {Date.now() - d.lastSeen < 20 * 60_000 ? "Recently active" : "Last seen"}
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
          <button className="secondary-button danger-text" onClick={auth.signOut} disabled={Boolean(signOutBlockedReason)} aria-describedby={signOutBlockedReason ? "sign-out-protection" : undefined}>
            <Icon name="lock" /> Sign Out
          </button>
          {signOutBlockedReason && <p id="sign-out-protection" className="field-help">{signOutBlockedReason}</p>}
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
