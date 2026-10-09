import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { useCallback, useEffect, useRef, useState } from "react";
import { useDurableMutation } from "./durableSync";
import { api as convexApi } from "../../convex/_generated/api";
import { frogTaskKey, normalizeFrogDomain, type FrogActions, type FrogState } from "./frog";
import { localDate } from "./sync";
import { canAcceptVoidStart, claimVoidCompletion, sampleVoidClock } from "./voidTimer";

const api: any = convexApi;
const UI_EVENT = "focuslock:void-launcher-ui";
const TIMER_EVENT = "focuslock:exclusive-timer";
const MAX_SAMPLE_GAP_MS = 5_000;
const MAX_BLOCK_MINUTES = 480;

export type VoidTool = {
  id: string;
  label: string;
  executablePath?: string;
  appUserModelId?: string;
};

export type VoidLauncherState = {
  protocolVersion: 1;
  title: string;
  projectName?: string;
  phase: string;
  cycleDate: string;
  trackedSeconds: number;
  requiredSeconds: number;
  tickedOff: boolean;
  running: boolean;
  remainingSeconds: number;
  blockMinutes: number;
  graceRemainingSeconds: number;
  tools: VoidTool[];
  domains: string[];
};

export type VoidAction =
  | { action: "toggle_timer" }
  | { action: "tick_off" }
  | { action: "open_focuslock" }
  | { action: "closed" }
  | { action: "select_frog"; title: string; appIds: string[]; domains: string[] }
  | { action: "set_duration"; minutes: number }
  | { action: "launch_error"; message?: string };

export type VoidLauncherUi = {
  active: boolean;
  busy: boolean;
  ready: boolean;
  error: string | null;
  preview: boolean;
};

export const INITIAL_VOID_UI: VoidLauncherUi = {
  active: false,
  busy: false,
  ready: false,
  error: null,
  preview: false,
};
let latestVoidUi = INITIAL_VOID_UI;

export function isVoidLauncherState(value: unknown): value is VoidLauncherState {
  if (!value || typeof value !== "object") return false;
  const state = value as Partial<VoidLauncherState>;
  return state.protocolVersion === 1 && typeof state.title === "string" &&
    typeof state.phase === "string" && typeof state.cycleDate === "string" &&
    Number.isFinite(state.trackedSeconds) && Number.isFinite(state.requiredSeconds) &&
    typeof state.tickedOff === "boolean" && typeof state.running === "boolean" &&
    Number.isFinite(state.remainingSeconds) && Number.isFinite(state.blockMinutes) &&
    (state.graceRemainingSeconds === undefined || (Number.isFinite(state.graceRemainingSeconds) && state.graceRemainingSeconds >= 0 && state.graceRemainingSeconds <= 300)) &&
    Array.isArray(state.tools) && Array.isArray(state.domains) &&
    state.tools.every((tool) => tool && typeof tool.id === "string" && typeof tool.label === "string") &&
    state.domains.every((domain) => typeof domain === "string");
}

export function parseVoidAction(value: unknown): VoidAction | null {
  if (!value || typeof value !== "object") return null;
  const event = value as { action?: unknown; minutes?: unknown; message?: unknown; title?: unknown; appIds?: unknown; domains?: unknown };
  switch (event.action) {
    case "toggle_timer":
    case "tick_off":
    case "open_focuslock":
    case "closed":
      return { action: event.action };
    case "set_duration":
      return Number.isFinite(event.minutes)
        ? { action: "set_duration", minutes: Math.min(MAX_BLOCK_MINUTES, Math.max(1, Math.trunc(Number(event.minutes)))) }
        : null;
    case "select_frog": {
      if (typeof event.title !== "string" || !event.title.trim() || event.title.trim().length > 200 ||
          !Array.isArray(event.appIds) || event.appIds.length > 64 ||
          !Array.isArray(event.domains) || event.domains.length > 64 ||
          !event.appIds.every((id) => typeof id === "string" && /^[^\\/\x00]{1,260}\.exe$/i.test(id)) ||
          !event.domains.every((domain) => typeof domain === "string" && domain.length <= 253 && normalizeFrogDomain(domain) === domain)) return null;
      return { action: "select_frog", title: event.title.trim(), appIds: [...new Set(event.appIds)], domains: [...new Set(event.domains)] };
    }
    case "launch_error":
      return typeof event.message === "string" && event.message.trim()
        ? { action: "launch_error", message: event.message.slice(0, 500) }
        : null;
    default:
      return null;
  }
}

export function buildVoidLauncherState(
  frog: FrogState,
  running: boolean,
  remainingSeconds: number,
  blockMinutes: number,
): VoidLauncherState | null {
  if (!frog.enabled || !["grace", "pick_frog", "working"].includes(frog.phase)) return null;
  return {
    protocolVersion: 1,
    title: frog.phase === "grace" ? "Get ready" : frog.frog?.title || "Pick one task",
    ...(frog.frog?.projectName ? { projectName: frog.frog.projectName } : {}),
    phase: frog.phase,
    cycleDate: frog.cycleDate,
    trackedSeconds: frog.trackedSeconds,
    requiredSeconds: frog.requiredSeconds,
    tickedOff: frog.tickedOff,
    running: frog.phase === "working" && running,
    remainingSeconds: Math.max(0, Math.trunc(remainingSeconds)),
    blockMinutes: Math.min(MAX_BLOCK_MINUTES, Math.max(1, Math.trunc(blockMinutes))),
    graceRemainingSeconds: frog.graceRemainingSeconds || 0,
    tools: [
      ...(frog.phase === "working" ? frog.frog?.neededAppIds || [] : []).map((id) => ({ id, label: id })),
    ],
    domains: frog.phase === "working" ? [...frog.frog?.neededDomains || []] : [],
  };
}

function dispatchUi(ui: VoidLauncherUi) {
  latestVoidUi = ui;
  window.dispatchEvent(new CustomEvent<VoidLauncherUi>(UI_EVENT, { detail: ui }));
}

export function useVoidLauncherUi(): VoidLauncherUi {
  const [ui, setUi] = useState(latestVoidUi);
  useEffect(() => {
    const update = (event: Event) => {
      const detail = (event as CustomEvent<unknown>).detail;
      if (detail && typeof detail === "object" && "active" in detail && "busy" in detail) {
        setUi(detail as VoidLauncherUi);
      }
    };
    window.addEventListener(UI_EVENT, update);
    return () => window.removeEventListener(UI_EVENT, update);
  }, []);
  return ui;
}

/** Mounted once at the desktop root so a child launcher stays synchronized across navigation. */
export function VoidLauncherBridge({ frog, actions, workRatio }: {
  frog: FrogState;
  actions: FrogActions;
  workRatio: number;
}) {
  const recordWork = useDurableMutation(api.focus.recordWork);
  const [ui, setUi] = useState(INITIAL_VOID_UI);
  const [running, setRunning] = useState(false);
  const [blockMinutes, setBlockMinutes] = useState(25);
  const [remainingSeconds, setRemainingSeconds] = useState(25 * 60);
  const frogRef = useRef(frog);
  frogRef.current = frog;
  const actionRef = useRef(actions);
  actionRef.current = actions;
  const recordRef = useRef(recordWork);
  recordRef.current = recordWork;
  const ratioRef = useRef(workRatio);
  ratioRef.current = workRatio;
  const runningRef = useRef(false);
  const activeRef = useRef(false);
  const clockRef = useRef<number | null>(null);
  const subsecondMsRef = useRef(0);
  const remainingRef = useRef(25 * 60);
  const blockMinutesRef = useRef(25);
  const frogIdentityRef = useRef("");
  const sessionSecondsRef = useRef(0);
  const sessionIdRef = useRef("");
  const sessionTitleRef = useRef("");
  const recordIdsRef = useRef(new Set<string>());
  const closedDuringLaunchRef = useRef(false);
  const openedCycleRef = useRef("");
  const countdownCycleRef = useRef("");
  const uiRef = useRef(ui);
  uiRef.current = ui;

  const setUiState = useCallback((next: VoidLauncherUi) => {
    uiRef.current = next;
    setUi(next);
    dispatchUi(next);
  }, []);

  const currentState = useCallback((isRunning = runningRef.current) =>
    buildVoidLauncherState(frogRef.current, isRunning, remainingRef.current, blockMinutesRef.current), []);

  const updateNative = useCallback(async () => {
    if (!activeRef.current) return;
    const state = currentState();
    if (!state) return;
    try {
      await invoke("update_void_launcher", { state });
    } catch (error) {
      runningRef.current = false;
      setRunning(false);
      clockRef.current = null;
      setUiState({ ...uiRef.current, active: false, busy: false, error: `Frog launcher disconnected: ${String(error)}` });
      activeRef.current = false;
      void invoke("stop_void_launcher").catch(() => undefined);
    }
  }, [currentState, setUiState]);

  const pause = useCallback((nativeUpdate = true) => {
    if (!runningRef.current) return;
    runningRef.current = false;
    setRunning(false);
    clockRef.current = null;
    if (nativeUpdate) void updateNative();
  }, [updateNative]);

  const launch = useCallback(async () => {
    if (!uiRef.current.ready || uiRef.current.busy) return;
    if (!voidLauncherAvailable()) {
      setUiState({ ...uiRef.current, active: false, busy: false, error: null, preview: true });
      return;
    }
    if (activeRef.current) {
      setUiState({ ...uiRef.current, active: true, busy: true, error: null });
      try {
        await invoke("show_void_launcher");
        setUiState({ ...uiRef.current, active: true, busy: false, error: null });
      } catch (error) {
        try {
          const status = await invoke<{ running: boolean }>("get_void_launcher_status");
          if (!status?.running) {
            pause(false);
            activeRef.current = false;
            setUiState({ ...uiRef.current, active: false, busy: false, error: "Frog launcher is no longer running. Open it again to restart." });
            return;
          }
        } catch {
          pause(false);
          activeRef.current = false;
          setUiState({ ...uiRef.current, active: false, busy: false, error: "Could not check whether Frog launcher is still running." });
          return;
        }
        setUiState({ ...uiRef.current, active: true, busy: false, error: `Could not show Frog launcher: ${String(error)}` });
      }
      return;
    }
    const state = currentState(false);
    if (!state) return;
    closedDuringLaunchRef.current = false;
    setUiState({ ...uiRef.current, active: false, busy: true, error: null, preview: false });
    try {
      await invoke("start_void_launcher", { state });
      const status = await invoke<{ running: boolean; launchError?: string | null }>("get_void_launcher_status");
      if (!canAcceptVoidStart(Boolean(status?.running), closedDuringLaunchRef.current)) {
        activeRef.current = false;
        setUiState({ ...uiRef.current, active: false, busy: false, error: status?.launchError || "Frog launcher closed while it was starting." });
        return;
      }
      activeRef.current = true;
      frogIdentityRef.current = frogRef.current.frog ? `${frogRef.current.cycleDate}:${frogTaskKey(frogRef.current.frog)}` : "";
      if (frogRef.current.frog) {
        sessionTitleRef.current = frogRef.current.frog.title;
      }
      setUiState({ ...uiRef.current, active: true, busy: false, error: null, preview: false });
    } catch (error) {
      activeRef.current = false;
      setUiState({ ...uiRef.current, active: false, busy: false, error: `Could not open Frog launcher: ${String(error)}` });
    }
  }, [currentState, pause, setUiState]);

  // Present the daily picker once when this device's countdown expires.
  useEffect(() => {
    if (!ui.ready || !frog.enabled || !frog.locked || openedCycleRef.current === frog.cycleDate) return;
    openedCycleRef.current = frog.cycleDate;
    void launch();
  }, [frog.cycleDate, frog.enabled, frog.locked, ui.ready, launch]);

  // The native grace surface is a small draggable countdown, before any takeover.
  useEffect(() => {
    if (!ui.ready || !frog.enabled || frog.phase !== "grace" || countdownCycleRef.current === frog.cycleDate) return;
    countdownCycleRef.current = frog.cycleDate;
    void launch();
  }, [frog.cycleDate, frog.enabled, frog.phase, ui.ready, launch]);

  const saveCompletedBlock = useCallback(() => {
    const seconds = sessionSecondsRef.current;
    const sessionId = sessionIdRef.current;
    if (seconds < 60 || !sessionId || !claimVoidCompletion(recordIdsRef.current, sessionId)) return;
    const durationMinutes = Math.floor(seconds / 60);
    const now = Date.now();
    const ratio = Math.max(1, ratioRef.current || 4);
    void recordRef.current({
      recordId: `void_${sessionId}`,
      title: `Frog focus: ${sessionTitleRef.current || "Desktop work"}`,
      durationMinutes,
      timestamp: now,
      source: "VOID_LAUNCHER",
      earnedMinutesCredited: Math.floor(durationMinutes / ratio),
      date: localDate(now),
      tasksCompleted: 0,
    }).catch((error: unknown) => console.warn("[focuslock] Frog launcher block saved for retry", error));
    sessionSecondsRef.current = 0;
    sessionIdRef.current = "";
  }, []);

  // Keep the helper aligned with frog edits and daily rollover; an old task never keeps accumulating.
  useEffect(() => {
    const nextId = frog.frog && frog.enabled
      ? `${frog.cycleDate}:${frogTaskKey(frog.frog)}` : "";
    const sameTask = Boolean(frogIdentityRef.current && nextId === frogIdentityRef.current);
    if (frogIdentityRef.current && !sameTask) {
      pause();
      remainingRef.current = blockMinutesRef.current * 60;
      setRemainingSeconds(remainingRef.current);
      sessionSecondsRef.current = 0;
      sessionIdRef.current = "";
      sessionTitleRef.current = "";
      subsecondMsRef.current = 0;
      if (activeRef.current && !frog.enabled) {
        setUiState({ ...INITIAL_VOID_UI, ready: uiRef.current.ready });
        activeRef.current = false;
        void invoke("stop_void_launcher").catch(() => undefined);
      }
    }
    frogIdentityRef.current = nextId;
    if (sameTask && frog.phase === "complete") {
      pause();
      saveCompletedBlock();
      if (activeRef.current) {
        activeRef.current = false;
        setUiState({ ...INITIAL_VOID_UI, ready: uiRef.current.ready });
        void invoke("stop_void_launcher").catch(() => undefined);
      }
    } else if (activeRef.current && ["grace", "pick_frog", "working"].includes(frog.phase)) {
      void updateNative();
    } else if (activeRef.current && (!frog.enabled || frog.phase === "not_armed")) {
      pause(false);
      activeRef.current = false;
      setUiState({ ...INITIAL_VOID_UI, ready: uiRef.current.ready });
      void invoke("stop_void_launcher").catch(() => undefined);
    }
  }, [frog.cycleDate, frog.enabled, frog.phase, frog.frog, pause, saveCompletedBlock, setUiState, updateNative]);

  // Monotonic elapsed time, sampled every second and capped after suspend/resume.
  useEffect(() => {
    if (!running) return;
    const id = window.setInterval(() => {
      const now = performance.now();
      const sample = sampleVoidClock(clockRef.current, now, subsecondMsRef.current, remainingRef.current, MAX_SAMPLE_GAP_MS);
      clockRef.current = sample.sampledAt;
      subsecondMsRef.current = sample.remainderMs;
      const wholeSeconds = sample.wholeSeconds;
      if (wholeSeconds < 1) return;
      const live = frogRef.current;
      if (!live.enabled || live.phase !== "working" || !live.frog || live.cycleDate !== frogIdentityRef.current.split(":")[0] || `${live.cycleDate}:${frogTaskKey(live.frog)}` !== frogIdentityRef.current) {
        pause();
        return;
      }
      const credited = Math.min(wholeSeconds, remainingRef.current);
      actionRef.current.addTrackedSeconds(credited);
      sessionSecondsRef.current += credited;
      remainingRef.current = Math.max(0, remainingRef.current - credited);
      setRemainingSeconds(remainingRef.current);
      if (remainingRef.current === 0) {
        pause();
        saveCompletedBlock();
      }
    }, 1_000);
    return () => window.clearInterval(id);
  }, [running, pause, saveCompletedBlock]);

  // Native sidecar action stream. A malformed or unexpected message pauses and closes the child.
  useEffect(() => {
    if (!tauriAvailable()) return;
    let disposed = false;
    let unlisten: (() => void) | undefined;
    setUiState({ ...uiRef.current, ready: false });
    listen<unknown>("focuslock-void-action", ({ payload }) => {
      const event = parseVoidAction(payload);
      if (!event) {
        pause(false);
        activeRef.current = false;
        void invoke("stop_void_launcher").catch(() => undefined);
        setUiState({ ...uiRef.current, active: false, busy: false, error: "Frog launcher sent an invalid message and was closed." });
        return;
      }
      switch (event.action) {
        case "toggle_timer":
          if (runningRef.current) {
            pause();
          } else if (frogRef.current.phase === "working" && frogRef.current.enabled && frogRef.current.frog) {
            window.dispatchEvent(new CustomEvent(TIMER_EVENT, { detail: { owner: "void" } }));
            if (remainingRef.current <= 0) {
              remainingRef.current = blockMinutesRef.current * 60;
              sessionSecondsRef.current = 0;
              sessionIdRef.current = "";
              subsecondMsRef.current = 0;
              setRemainingSeconds(remainingRef.current);
            }
            if (!sessionIdRef.current) {
              sessionIdRef.current = crypto.randomUUID();
              sessionTitleRef.current = frogRef.current.frog.title;
            }
            runningRef.current = true;
            setRunning(true);
            clockRef.current = performance.now();
            void updateNative();
          }
          break;
        case "set_duration":
          if (runningRef.current) break;
          pause();
          blockMinutesRef.current = event.minutes;
          remainingRef.current = event.minutes * 60;
          sessionSecondsRef.current = 0;
          sessionIdRef.current = "";
          subsecondMsRef.current = 0;
          setBlockMinutes(event.minutes);
          setRemainingSeconds(remainingRef.current);
          void updateNative();
          break;
        case "tick_off":
          actionRef.current.tickOffFrog(true);
          break;
        case "select_frog":
          if (frogRef.current.enabled && frogRef.current.phase === "pick_frog") {
            actionRef.current.selectFrog({ title: event.title, neededAppIds: event.appIds, neededDomains: event.domains });
          }
          break;
        case "open_focuslock":
          window.dispatchEvent(new CustomEvent("focuslock:open-main"));
          break;
        case "closed":
          closedDuringLaunchRef.current = true;
          pause(false);
          activeRef.current = false;
          setUiState({ ...INITIAL_VOID_UI, ready: uiRef.current.ready });
          break;
        case "launch_error":
          // Tool launch failures do not terminate the launcher or its timer.
          setUiState({ ...uiRef.current, error: event.message || "Could not launch that tool." });
          break;
      }
    }).then((stop) => {
      if (disposed) stop();
      else {
        unlisten = stop;
        setUiState({ ...uiRef.current, ready: true });
      }
    }).catch((error) => {
      setUiState({ ...uiRef.current, ready: false, error: `Frog launcher events are unavailable: ${String(error)}` });
    });
    return () => { disposed = true; unlisten?.(); };
  }, [pause, setUiState, updateNative]);

  // Any timer started in the main Focus page pauses this timer first.
  useEffect(() => {
    const onExclusive = (event: Event) => {
      const owner = (event as CustomEvent<{ owner?: string }>).detail?.owner;
      if (owner !== "void") pause();
    };
    window.addEventListener(TIMER_EVENT, onExclusive);
    return () => window.removeEventListener(TIMER_EVENT, onExclusive);
  }, [pause]);

  // While the child is alive, check its status and send fresh frog state once per second.
  useEffect(() => {
    if (!ui.active) return;
    let checking = false;
    const id = window.setInterval(() => {
      if (checking || !activeRef.current) return;
      checking = true;
      void invoke<{ running: boolean }>("get_void_launcher_status").then((status) => {
        if (!status?.running) {
          pause(false);
          activeRef.current = false;
          setUiState({ ...INITIAL_VOID_UI, ready: uiRef.current.ready, error: "Frog launcher closed unexpectedly." });
        } else {
          void updateNative();
        }
      }).catch((error) => {
        pause(false);
        activeRef.current = false;
        setUiState({ ...INITIAL_VOID_UI, ready: uiRef.current.ready, error: `Frog launcher disconnected: ${String(error)}` });
      }).finally(() => { checking = false; });
    }, 1_000);
    return () => window.clearInterval(id);
  }, [ui.active, pause, setUiState, updateNative]);

  // Mirrors state to the card's isolated UI hook.
  useEffect(() => { dispatchUi(ui); }, [ui]);

  // Cleanup stops local accrual; the child exits with the desktop app process.
  useEffect(() => () => {
    runningRef.current = false;
    if (activeRef.current) void invoke("stop_void_launcher").catch(() => undefined);
  }, []);

  return <VoidLauncherControls onOpen={launch} />;
}

function tauriAvailable(): boolean {
  return Boolean((window as any).__TAURI_INTERNALS__);
}

export function voidLauncherAvailable(): boolean {
  return tauriAvailable() &&
    (/windows/i.test(navigator.userAgent) || /win/i.test(navigator.platform || ""));
}

function VoidLauncherControls({ onOpen }: { onOpen: () => void }) {
  // The controls are rendered invisibly: FrogCard owns the visible button and reads this bridge state.
  const [ui, setUi] = useState(latestVoidUi);
  useEffect(() => {
    const update = (event: Event) => setUi((event as CustomEvent<VoidLauncherUi>).detail || INITIAL_VOID_UI);
    window.addEventListener(UI_EVENT, update);
    return () => window.removeEventListener(UI_EVENT, update);
  }, []);
  const actionRef = useRef(onOpen);
  actionRef.current = onOpen;
  useEffect(() => {
    const open = () => actionRef.current();
    window.addEventListener("focuslock:void-open", open);
    return () => {
      window.removeEventListener("focuslock:void-open", open);
    };
  }, []);
  return (
    <div className="void-launcher-live-status" aria-live="polite">
      {ui.busy ? ui.active ? "Returning to Frog launcher…" : "Opening Frog launcher…" : ui.active ? "Frog launcher is open." : ""}
    </div>
  );
}
