/** Local hardening for settings covered by an active Strict Mode commitment. */
export const STRICT_SETTINGS_STORAGE_KEY = "focuslock.strictSettings.v1";

export type StrictSettingsPrefs = {
  strictMode?: boolean;
  strictEndsAt?: number;
  strictSessionId?: string;
  strictApprovedSessionId?: string;
  strictApprovedEndsAt?: number;
};

export type StrictSettingKind =
  | "enabled"
  | "requiredMinutes"
  | "workRatio"
  | "taskBonusMinutes"
  | "wakeHour";

type StoredState = {
  version: 1;
  accounts: Record<string, { sessions: Record<string, number>; approved: Record<string, number> }>;
};

let nativeStrictSettingsLocked = false;
let persistenceFailureLocked = false;
const LEGACY_INDEFINITE_SESSION = "legacy:indefinite";
const LEGACY_INDEFINITE_END = Number.MAX_SAFE_INTEGER;

function provisionalExpiry(sessionId: string): number | null {
  const match = /^(?:legacy|timed):(\d+)$/.exec(sessionId);
  if (!match) return null;
  const expiry = Number(match[1]);
  return Number.isFinite(expiry) && expiry > 0 ? expiry : null;
}

function storage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

function parseState(raw: string | null): StoredState | null {
  if (raw === null) return { version: 1, accounts: {} };
  const parsed: unknown = JSON.parse(raw);
  if (!parsed || typeof parsed !== "object") return null;
  const state = parsed as Partial<StoredState>;
  if (state.version !== 1 || !state.accounts || typeof state.accounts !== "object" || Array.isArray(state.accounts)) {
    return null;
  }
  for (const account of Object.values(state.accounts)) {
    if (!account || typeof account !== "object" || !account.sessions || typeof account.sessions !== "object" || Array.isArray(account.sessions)) {
      return null;
    }
    if (account.approved === undefined) account.approved = {};
    if (!account.approved || typeof account.approved !== "object" || Array.isArray(account.approved)) return null;
    for (const end of Object.values(account.sessions)) {
      if (typeof end !== "number" || !Number.isFinite(end)) return null;
    }
    for (const end of Object.values(account.approved)) {
      if (typeof end !== "number" || !Number.isFinite(end)) return null;
    }
  }
  return state as StoredState;
}

/**
 * Incorporate a server snapshot synchronously before any native bridge call.
 * Session expiries only move forward. A false snapshot can clear a session
 * only when guardian approval identifies that exact session and end time.
 */
export function syncStrictSettingsSnapshot(accountId: string, prefs: StrictSettingsPrefs): void {
  const store = storage();
  if (!accountId) return;
  if (!store) {
    persistenceFailureLocked = true;
    return;
  }
  try {
    const state = parseState(store.getItem(STRICT_SETTINGS_STORAGE_KEY));
    if (!state) {
      persistenceFailureLocked = true;
      return;
    }
    const account = state.accounts[accountId] ?? { sessions: {}, approved: {} };
    const end = prefs.strictEndsAt;
    if (prefs.strictMode) {
      const hasTimedEnd = typeof end === "number" && Number.isFinite(end) && end > 0;
      if (hasTimedEnd) {
        // A newer timed commitment supersedes the legacy indefinite sentinel.
        delete account.sessions[LEGACY_INDEFINITE_SESSION];
        const sessionId = typeof prefs.strictSessionId === "string" && prefs.strictSessionId
          ? prefs.strictSessionId
          : `timed:${end}`;
        if (prefs.strictSessionId) {
          // `savePrefs` can be durably queued before its server-generated session
          // id returns. Reconcile provisional IDs for this same or earlier end
          // into the authenticated session, leaving longer independent locks.
          for (const provisionalId of Object.keys(account.sessions)) {
            if (provisionalId === sessionId) continue;
            const provisionalEnd = provisionalExpiry(provisionalId);
            if (provisionalEnd !== null && provisionalEnd <= end) {
              delete account.sessions[provisionalId];
              account.approved[provisionalId] = provisionalEnd;
            }
          }
        }
        if (account.approved[sessionId] !== end) {
          account.sessions[sessionId] = Math.max(account.sessions[sessionId] ?? 0, end);
        }
      } else {
        if (account.approved[LEGACY_INDEFINITE_SESSION] !== LEGACY_INDEFINITE_END) {
          account.sessions[LEGACY_INDEFINITE_SESSION] = LEGACY_INDEFINITE_END;
        }
      }
    }
    if (prefs.strictMode === false) {
      // The server's false value is authoritative for old indefinite sessions,
      // which have no exact timed guardian-approval identity.
      delete account.sessions[LEGACY_INDEFINITE_SESSION];
      delete account.sessions["legacy:0"];
      if (prefs.strictApprovedSessionId && typeof prefs.strictApprovedEndsAt === "number" &&
          Number.isFinite(prefs.strictApprovedEndsAt)) {
        const approvedEnd = account.sessions[prefs.strictApprovedSessionId];
        if (approvedEnd === prefs.strictApprovedEndsAt) {
          delete account.sessions[prefs.strictApprovedSessionId];
          account.approved[prefs.strictApprovedSessionId] = prefs.strictApprovedEndsAt;
        }
      }
    }
    state.accounts[accountId] = account;
    store.setItem(STRICT_SETTINGS_STORAGE_KEY, JSON.stringify(state));
    persistenceFailureLocked = false;
  } catch {
    // Storage write failures must not leave this process believing settings are unlocked.
    persistenceFailureLocked = true;
  }
}

/** The native command is polled by the desktop host; its result can only add a lock. */
export function setNativeStrictSettingsLocked(locked: boolean): void {
  nativeStrictSettingsLocked = Boolean(locked);
}

/** Corrupt or inaccessible persisted state fails closed. */
export function isStrictSettingsLocked(): boolean {
  if (nativeStrictSettingsLocked || persistenceFailureLocked) return true;
  if (typeof window === "undefined") return false;
  const store = storage();
  if (!store) return true;
  try {
    const raw = store.getItem(STRICT_SETTINGS_STORAGE_KEY);
    const state = parseState(raw);
    if (!state) return true;
    const now = Date.now();
    return Object.values(state.accounts).some((account) =>
      Object.values(account.sessions).some((end) => end > now));
  } catch {
    return true;
  }
}

/** Throws when a local setting edit would make an active commitment easier. */
export function assertStrictSettingChange(kind: StrictSettingKind, current: unknown, next: unknown): void {
  if (!isStrictSettingsLocked() || Object.is(current, next)) return;
  let forbidden = false;
  switch (kind) {
    case "enabled":
      forbidden = current === true && next === false;
      break;
    case "requiredMinutes":
    case "workRatio":
      forbidden = typeof current === "number" && typeof next === "number" && next < current;
      break;
    case "taskBonusMinutes":
      forbidden = typeof current === "number" && typeof next === "number" && next > current;
      break;
    case "wakeHour":
      forbidden = true;
      break;
  }
  if (forbidden) throw new Error("This setting cannot be made easier during Strict Mode");
}
