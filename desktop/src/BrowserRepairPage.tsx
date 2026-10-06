import { invoke } from "@tauri-apps/api/core";
import { useCallback, useEffect, useState } from "react";
import "./browser-repair.css";

type BrowserRepairState = {
  browser: string;
  appId: string;
  graceRemainingSeconds: number;
  reason: "extension_missing" | "browser_unsupported" | string;
};

function browserName(state: BrowserRepairState): string {
  const appId = `${state.appId} ${state.browser}`.toLowerCase();
  if (appId.includes("msedge")) return "Edge";
  if (appId.includes("brave")) return "Brave";
  if (appId.includes("vivaldi")) return "Vivaldi";
  if (appId.includes("opera")) return "Opera";
  if (appId.includes("firefox")) return "Firefox";
  const name = state.browser.trim();
  if (name && !name.toLowerCase().endsWith(".exe")) return name;
  return "Chrome";
}

export default function BrowserRepairPage() {
  const [repair, setRepair] = useState<BrowserRepairState | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [openingSettings, setOpeningSettings] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await invoke<BrowserRepairState | null>("get_browser_repair_state");
      setRepair(next);
      setLoadFailed(false);
    } catch {
      setLoadFailed(true);
    }
  }, []);

  useEffect(() => {
    let active = true;
    const poll = async () => {
      try {
        const next = await invoke<BrowserRepairState | null>("get_browser_repair_state");
        if (active) {
          setRepair(next);
          setLoadFailed(false);
        }
      } catch {
        if (active) setLoadFailed(true);
      }
    };
    void poll();
    const timer = window.setInterval(() => void poll(), 1_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  const openSettings = async () => {
    if (!repair || openingSettings) return;
    setOpeningSettings(true);
    setActionError(null);
    try {
      await invoke("open_browser_extension_settings", { appId: repair.appId });
    } catch (error) {
      setActionError(String(error));
    } finally {
      setOpeningSettings(false);
      void refresh();
    }
  };

  const name = repair ? browserName(repair) : "your browser";
  const unsupported = repair?.reason === "browser_unsupported";
  const seconds = Math.max(0, Math.ceil(repair?.graceRemainingSeconds ?? 0));

  return (
    <main className="browser-repair-shell" aria-live="polite">
      <section className="browser-repair-card">
        {repair ? (
          <>
            <p className="browser-repair-eyebrow">Browser extension</p>
            <h1>{unsupported ? "Use a supported browser" : "Reconnect FocusLock"}</h1>
            <p className="browser-repair-copy">
              {unsupported
                ? `${name} is not supported for website boundaries. Switch to Chrome, Edge, Brave, Vivaldi, Opera, or Arc.`
                : `FocusLock’s extension is missing or disabled in this ${name} profile. Install or enable it to keep this browser open.`}
            </p>
            {!unsupported && (
              <>
                <p className="browser-repair-guidance">
                  Allow FocusLock on all websites and in incognito.
                </p>
                <div className="browser-repair-countdown" role="timer" aria-label={`${seconds} seconds until ${name} closes`}>
                  <strong>{seconds}</strong>
                  <span>{name} will close in {seconds} seconds</span>
                </div>
              </>
            )}
            {unsupported && (
              <div className="browser-repair-countdown">
                <strong>{seconds}</strong>
                <span>{name} will close in {seconds} seconds</span>
              </div>
            )}
            {actionError && <p className="browser-repair-error" role="alert">{actionError}</p>}
            {!unsupported && (
              <button className="browser-repair-button" type="button" onClick={openSettings} disabled={openingSettings}>
                {openingSettings ? "Opening extensions…" : "Open extension settings"}
              </button>
            )}
          </>
        ) : loadFailed ? (
          <>
            <p className="browser-repair-eyebrow">Browser extension</p>
            <h1>Checking connection…</h1>
            <p className="browser-repair-copy">FocusLock is checking the extension status in your browser.</p>
          </>
        ) : (
          <>
            <p className="browser-repair-eyebrow">Browser extension</p>
            <h1>Connection restored</h1>
            <p className="browser-repair-copy">FocusLock’s extension is responding again.</p>
          </>
        )}
      </section>
    </main>
  );
}
