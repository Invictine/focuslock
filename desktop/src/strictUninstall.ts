import { invoke } from "@tauri-apps/api/core";
import { useEffect, useState } from "react";

type StrictPrefs = {
  strictMode?: boolean;
  strictEndsAt?: number;
  strictSessionId?: string;
  strictApprovedSessionId?: string;
  strictApprovedEndsAt?: number;
};

export async function persistStrictUninstallGuard(accountId: string, prefs: StrictPrefs) {
  if (!("__TAURI_INTERNALS__" in window)) return;
  await invoke("sync_strict_uninstall_guard", {
    accountId,
    enabled: Boolean(prefs.strictMode),
    endsAt: prefs.strictEndsAt || null,
    sessionId: prefs.strictSessionId || null,
    approvedSessionId: prefs.strictApprovedSessionId || null,
    approvedEndsAt: prefs.strictApprovedEndsAt || null,
  });
}

/** Keep the native commitment through sign-out, offline use, and app restarts. */
export function useStrictUninstallGuard(accountId: string | null, prefs?: StrictPrefs) {
  const [error, setError] = useState<string | null>(null);
  const signature = prefs ? JSON.stringify({
    strictMode: prefs.strictMode,
    strictEndsAt: prefs.strictEndsAt,
    strictSessionId: prefs.strictSessionId,
    strictApprovedSessionId: prefs.strictApprovedSessionId,
    strictApprovedEndsAt: prefs.strictApprovedEndsAt,
  }) : null;
  useEffect(() => {
    if (!accountId || !signature) return;
    let cancelled = false;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const sync = async () => {
      try {
        await persistStrictUninstallGuard(accountId, JSON.parse(signature));
        if (!cancelled) setError(null);
      } catch {
        if (!cancelled) {
          setError("Windows uninstall protection could not be saved. FocusLock will retry; keep the app open.");
          retry = setTimeout(sync, 10_000);
        }
      }
    };
    void sync();
    return () => { cancelled = true; clearTimeout(retry); };
  }, [accountId, signature]);
  return error;
}
