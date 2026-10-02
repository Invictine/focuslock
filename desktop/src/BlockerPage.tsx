import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { useEffect, useRef, useState } from "react";
import { formatFrogClock, useFrogState, type FrogTask } from "./frog";
import "./blocker.css";

// ---------------------------------------------------------------------------
// Full-screen blocker webview (the Rust side opens `index.html#/blocked`).
// Mirrors Android's PixelBlockerScreen / FrogBlockerScreen in the same charcoal
// theme as NukeOverlay, and reads frog progress straight from the shared
// localStorage (same origin as the main window), so ticking off in the main
// window updates this screen through the `storage` event.
//
// Frozen IPC: get_blocker_state -> { visible, target, kind, reason };
// `focuslock://blocker` events carry { target, kind, reason }; blocker_action
// accepts "eat_frog" | "dismiss" — both are REFUSED by Rust while the active
// reason is "permanent". Everything is guarded by tauriAvailable() so a plain
// browser (#/blocked in web preview) renders a harmless message.
// ---------------------------------------------------------------------------

type BlockerPayload = {
  visible?: boolean;
  target?: string;
  kind?: string;
  reason?: string;
};

function tauriAvailable(): boolean {
  return Boolean((window as any).__TAURI_INTERNALS__);
}

function FrogGlyph({ size = 18 }: { size?: number }) {
  return (
    <svg
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
      <path d="M5 14a7 7 0 0 1 14 0" />
      <path d="M5 14v2a4 4 0 0 0 4 4h6a4 4 0 0 0 4-4v-2" />
      <path d="M8.5 10.5h.01M15.5 10.5h.01" />
    </svg>
  );
}

function AppGlyph({ size = 13 }: { size?: number }) {
  return (
    <svg
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
      <rect x="3" y="4" width="18" height="13" rx="2" />
      <path d="M8 21h8m-4-4v4" />
    </svg>
  );
}

function SiteGlyph({ size = 13 }: { size?: number }) {
  return (
    <svg
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
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
    </svg>
  );
}

function ShieldGlyph({ size = 13 }: { size?: number }) {
  return (
    <svg
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
      <path d="M12 3l7 3v5c0 4.4-2.9 7.4-7 9-4.1-1.6-7-4.6-7-9V6Z" />
      <path d="m9 12 2 2 4-4" />
    </svg>
  );
}

function AllowlistChips({ frog }: { frog: FrogTask }) {
  if (!frog.neededAppIds.length && !frog.neededDomains.length) {
    return <p className="blocker-progress-copy">No allowlist picked — everything stays locked.</p>;
  }
  return (
    <div className="blocker-chips">
      {frog.neededAppIds.map((key) => (
        <span className="blocker-chip" key={`app:${key}`}>
          <AppGlyph />
          {key}
        </span>
      ))}
      {frog.neededDomains.map((key) => (
        <span className="blocker-chip website" key={`site:${key}`}>
          <SiteGlyph />
          {key}
        </span>
      ))}
    </div>
  );
}

export default function BlockerPage() {
  const native = tauriAvailable();
  const [blocker, setBlocker] = useState<BlockerPayload | null>(null);
  const { state: frog } = useFrogState();
  const dismissSentRef = useRef(false);

  useEffect(() => {
    if (!native) return;
    let cancelled = false;
    let unlisten: (() => void) | undefined;
    invoke<BlockerPayload>("get_blocker_state")
      .then((state) => {
        if (!cancelled) setBlocker(state);
      })
      .catch(() => undefined);
    listen<BlockerPayload>("focuslock://blocker", (event) => {
      if (cancelled) return;
      // The blocker webview lives for the whole app session, so a new lock
      // episode must re-arm the one-shot auto-dismiss below (Rust emits this
      // event only when a block starts or is re-targeted).
      dismissSentRef.current = false;
      setBlocker({ visible: true, ...(event.payload || {}) });
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
  }, [native]);

  const reason = String(blocker?.reason || "");
  const target = String(blocker?.target || "").trim();
  const frogComplete = reason === "frog" && frog.phase === "complete";
  const requiredMinutes = Math.round(frog.requiredSeconds / 60);
  const progress =
    frog.requiredSeconds > 0
      ? Math.min(1, Math.max(0, frog.trackedSeconds / frog.requiredSeconds))
      : 0;

  // Mirror Android's FrogBlockerScreen: the moment the frog completes, the hard
  // lock dismisses itself instead of sticking around. Leaving the complete
  // phase (new day, changed frog) re-arms the latch for the next completion.
  useEffect(() => {
    if (!frogComplete) {
      dismissSentRef.current = false;
      return;
    }
    if (!native || dismissSentRef.current) return;
    dismissSentRef.current = true;
    invoke("blocker_action", { action: "dismiss" }).catch(() => undefined);
  }, [native, frogComplete]);

  function blockerAction(action: "eat_frog" | "dismiss") {
    if (!native) return;
    invoke("blocker_action", { action }).catch(() => undefined);
  }

  // Web preview (no Tauri internals): render a plain, harmless notice.
  if (!native) {
    return (
      <main className="blocker-page">
        <section className="blocker-card">
          <p className="blocker-pill boundary">FocusLock</p>
          <h1>Blocked</h1>
          <p className="blocker-copy">
            This page is the FocusLock blocker window. Open the installed desktop app to manage
            your boundaries.
          </p>
        </section>
      </main>
    );
  }

  if (reason === "frog") {
    return (
      <main className="blocker-page" role="dialog" aria-modal="true" aria-labelledby="blocker-title">
        <section className="blocker-card">
          <p className="blocker-pill hard-lock">
            <FrogGlyph size={13} /> Hard lock
          </p>
          <h1 id="blocker-title">Eat the frog first</h1>
          <p className="blocker-copy">
            Every boundary app and website stays locked until today's frog is ticked off and{" "}
            {requiredMinutes} minutes of focus are tracked on it. The lock resets at the next wake
            hour.
          </p>

          <div className="blocker-frog">
            <span>Today's frog</span>
            <strong>{frog.frog?.title || "No frog selected yet"}</strong>
            {frog.frog?.projectName && <small>{frog.frog.projectName}</small>}
          </div>

          <div
            className="blocker-progress"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(progress * 100)}
            aria-label={`Frog progress ${Math.floor(frog.trackedSeconds / 60)} of ${requiredMinutes} minutes`}
          >
            <span style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
          <p className="blocker-progress-copy">
            {formatFrogClock(frog.trackedSeconds)} / {formatFrogClock(frog.requiredSeconds)} ·{" "}
            {frog.tickedOff ? "ticked off" : "not ticked off"}
          </p>

          {frog.frog && <AllowlistChips frog={frog.frog} />}

          {frogComplete ? (
            <p className="blocker-complete">Frog complete — unlocking…</p>
          ) : (
            <div className="blocker-actions">
              <button
                type="button"
                className="blocker-primary"
                onClick={() => blockerAction("eat_frog")}
              >
                Eat the frog now
              </button>
              <button
                type="button"
                className="blocker-secondary"
                onClick={() => blockerAction("dismiss")}
              >
                Back to work
              </button>
            </div>
          )}
        </section>
      </main>
    );
  }

  if (reason === "limit") {
    return (
      <main className="blocker-page" role="dialog" aria-modal="true" aria-labelledby="blocker-title">
        <section className="blocker-card">
          <p className="blocker-pill limit">Daily limit</p>
          <h1 id="blocker-title">Daily limit reached</h1>
          <p className="blocker-copy">
            {target ? `${target} hit its daily limit.` : "This target hit its daily limit."} It
            resets tomorrow — focused work is the way to earn more time today.
          </p>
          <div className="blocker-actions">
            <button
              type="button"
              className="blocker-secondary"
              onClick={() => blockerAction("dismiss")}
            >
              Back to work
            </button>
          </div>
        </section>
      </main>
    );
  }

  if (reason === "permanent") {
    // No `.blocker-actions` at all, on purpose: Rust also refuses
    // blocker_action("dismiss"/"eat_frog") while the reason is permanent, so
    // there is no removal, credit or emergency-pass path from here.
    return (
      <main className="blocker-page" role="dialog" aria-modal="true" aria-labelledby="blocker-title">
        <section className="blocker-card">
          <p className="blocker-pill permanent">
            <ShieldGlyph /> Permanent
          </p>
          <h1 id="blocker-title">Permanently blocked</h1>
          <p className="blocker-copy">
            This app is permanently blocked in FocusLock. There is no removal, credits, or
            emergency pass.
          </p>
        </section>
      </main>
    );
  }

  return (
    <main className="blocker-page" role="dialog" aria-modal="true" aria-labelledby="blocker-title">
      <section className="blocker-card">
        <p className="blocker-pill boundary">{target ? "Boundary" : "FocusLock"}</p>
        <h1 id="blocker-title">Boundary</h1>
        <p className="blocker-copy">
          {target ? `${target} is on your Boundaries list.` : "This target is on your Boundaries list."}{" "}
          Finish focused work to earn leisure time instead.
        </p>
        <div className="blocker-actions">
          <button
            type="button"
            className="blocker-secondary"
            onClick={() => blockerAction("dismiss")}
          >
            Back to work
          </button>
        </div>
      </section>
    </main>
  );
}
