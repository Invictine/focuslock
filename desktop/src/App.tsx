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
  syncApi,
  type UsageBucket,
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
  const usage: any = useQuery(syncApi.getUsageSummary, {
    fromDate: localDate(),
    toDate: localDate(),
  });
  const devices: any[] | undefined = useQuery(syncApi.listDevices, {});
  const heartbeat = useMutation(syncApi.heartbeat);
  const recordUsage = useMutation(syncApi.recordUsageBatch);
  const { workRatio, taskBonus, setWorkRatio, setTaskBonus } =
    useSyncedPrefs(dashboard);
  const deviceId = snapshot?.device.id || getStoredDeviceId();
  const lastUploadRef = useRef(0);
  const usageJsonRef = useRef<string | null>(null);
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
      if (!snap) return;
      const usageJson = JSON.stringify(snap.usage);
      const usageChanged = usageJson !== usageJsonRef.current;
      if (!usageChanged && Date.now() - lastUploadRef.current < 25000) return;
      const buckets: UsageBucket[] = usageChanged
        ? snap.usage.map((u) => ({
            date: u.date,
            targetKind: u.browserDomain ? "website" : "app",
            targetKey: u.browserDomain || u.appId,
            targetLabel: u.browserDomain || u.appName,
            category: u.browserDomain ? "Web" : "Windows",
            trackedSeconds: u.activeSeconds,
            updatedAt: Date.now(),
          }))
        : [];
      if (usageChanged) usageJsonRef.current = usageJson;
      lastUploadRef.current = Date.now();
      heartbeat({
        deviceId,
        name: snap.device.name,
        platform: "windows",
        appVersion: "1.0.0",
        trackingStatus: snap.running ? "active" : "paused",
        statusDetail: trackerErrorRef.current || undefined,
        lastSeen: Date.now(),
      })
        .then(() =>
          buckets.length ? recordUsage({ deviceId, buckets }) : undefined,
        )
        .then(() => {
          if (!cancelled) setLastSyncAt(Date.now());
        })
        .catch((err) =>
          console.warn(
            "[focuslock] heartbeat/usage upload failed; bucket dropped",
            err,
          ),
        );
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
  useEffect(() => {
    if (!tauriAvailable() || !dashboard) return;
    const appIds = (dashboard.apps || [])
      .filter((item: AppItem) => item.isBlocked && item.category === "Windows")
      .map((item: AppItem) => item.packageName);
    const domains = (dashboard.sites || [])
      .filter((item: SiteItem) => item.isBlocked)
      .map((item: SiteItem) => item.domain);
    invoke("set_blocked_targets", { targets: { appIds, domains } }).catch(
      () => undefined,
    );
  }, [dashboard]);
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
          />
        ) : tab === "boundaries" ? (
          <BoundariesPage
            dashboard={dashboard}
            snapshot={snapshot}
            boundariesLock={boundariesLock}
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
  trackerError,
  workRatio,
  taskBonus,
}: any) {
  const state = dashboard?.state || {};
  const records: WorkRecord[] = dashboard?.records || [];
  const total = usage?.totalTrackedSeconds || 0;
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
  const androidSeconds = Math.max(0, total - windowsSeconds - browserSeconds);
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
            <p className="section-label">Screen time</p>
            <h2>{fmt(total)}</h2>
          </div>
          <p>
            Today across {trackedDeviceCount} tracked device
            {trackedDeviceCount === 1 ? "" : "s"}
          </p>
        </div>
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
              androidSeconds ? "Synced from phone" : "No usage uploaded today"
            }
          />
          <DeviceMetric
            icon="globe"
            label="Chrome"
            value={fmt(browserSeconds)}
            detail={browserSeconds ? "Synced from extension" : "No website usage uploaded today"}
          />
        </div>
        <UsageBars targets={usage?.targets || EMPTY_TARGETS} devices={devices} />
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
function UsageBars({ targets, devices }: any) {
  // deviceId -> name lookup built once per devices/targets change instead of
  // a devices.find per deviceIds entry per render.
  const deviceNameById = useMemo(() => {
    const map = new Map<string, string>();
    (devices || []).forEach((device: any) =>
      map.set(device.deviceId, device.name),
    );
    return map;
  }, [devices]);
  const top = useMemo(() => targets.slice(0, 6), [targets]);
  const max = Math.max(1, ...top.map((t: any) => t.trackedSeconds));
  return (
    <div className="usage-list">
      {top.map((t: any) => (
        <div className="usage-row" key={`${t.targetKind}:${t.targetKey}`}>
          <span className="target-icon">
            <Icon name={t.targetKind === "website" ? "globe" : "monitor"} />
          </span>
          <div>
            <div className="usage-copy">
              <strong>{t.targetLabel}</strong>
              <span>{fmt(t.trackedSeconds)}</span>
            </div>
            <div className="bar">
              <i style={{ width: `${(t.trackedSeconds / max) * 100}%` }} />
            </div>
            <small>
              {t.deviceIds
                .map((id: string) => deviceNameById.get(id) || "Unknown device")
                .join(" + ")}
            </small>
          </div>
        </div>
      ))}
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
  value = value.replace(/^www\./, "").replace(/^m\./, "");
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

function BoundariesPage({ dashboard, snapshot, boundariesLock = false }: any) {
  const saveApps = useMutation(api.focus.saveBlockedApps);
  const saveSites = useMutation(api.focus.saveBlockedWebsites);
  const apps: AppItem[] = dashboard?.apps || [];
  const sites: SiteItem[] = dashboard?.sites || [];

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
        map.set(u.browserDomain, (map.get(u.browserDomain) || 0) + u.activeSeconds);
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
    await invoke("set_blocked_targets", {
      targets: {
        appIds: nextApps
          .filter((row) => row.native && row.isBlocked)
          .map((row) => row.key),
        domains: nextSites.filter((site) => site.isBlocked).map((site) => site.domain),
      },
    }).catch(() => undefined);
  }

  async function persistApps(toggled: BoundaryAppRow[], nextApps: BoundaryAppRow[], message: string) {
    setBusy(true);
    try {
      // Upload only server-known rows plus the toggled targets so observed-only
      // Windows apps are never persisted as isBlocked:false (list pollution).
      await saveApps({
        apps: serverAppsForUpload(apps, toAppItems(toggled)),
        updatedAt: Date.now(),
      });
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
      await saveSites({
        sites: nextSites.map(stripSite),
        updatedAt: Date.now(),
      });
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
    if (sites.some((site) => site.domain === normalized)) {
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
      await saveSites({ sites: nextSites.map(stripSite), updatedAt: Date.now() });
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

      <div className="boundary-list">
        {kind === "apps"
          ? filteredApps.map((row) => (
              <article className="boundary-row" key={row.key}>
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
              <article className="boundary-row" key={site.domain}>
                <span className="letter-icon">
                  {(site.displayName || site.domain).charAt(0).toUpperCase()}
                </span>
                <div>
                  <strong>{site.displayName || site.domain}</strong>
                  <p>
                    <span className="category-label">{site.category}</span> ·{" "}
                    {site.domain}
                    {siteMinutes.get(site.domain)
                      ? ` · ${Math.round((siteMinutes.get(site.domain) || 0) / 60)} min today`
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
