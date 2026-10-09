import { describe, expect, it } from "vitest";
import {
  buildVoidLauncherState,
  isVoidLauncherState,
  parseVoidAction,
} from "../desktop/src/voidLauncher";
import type { FrogState } from "../desktop/src/frog";
import { canAcceptVoidStart, claimVoidCompletion, sampleVoidClock } from "../desktop/src/voidTimer";

const workingFrog: FrogState = {
  cycleDate: "2026-10-05",
  enabled: true,
  armed: true,
  phase: "working",
  frog: {
    title: "Write the report",
    projectName: "School",
    neededAppIds: ["Code.exe"],
    neededDomains: ["docs.google.com"],
  },
  tickedOff: false,
  trackedSeconds: 120,
  requiredSeconds: 1800,
  graceRemainingSeconds: 0,
  locked: true,
  requiredMinutes: 30,
  wakeHour: 5,
};

describe("Void launcher protocol", () => {
  it("builds working, grace, and task-picking snapshots with only phase-appropriate tools", () => {
    const state = buildVoidLauncherState(workingFrog, true, 600, 10);
    expect(state).toMatchObject({
      protocolVersion: 1,
      title: "Write the report",
      projectName: "School",
      phase: "working",
      cycleDate: "2026-10-05",
      trackedSeconds: 120,
      running: true,
      remainingSeconds: 600,
      blockMinutes: 10,
      tools: [{ id: "Code.exe", label: "Code.exe" }],
      domains: ["docs.google.com"],
    });
    expect(isVoidLauncherState(state)).toBe(true);
    expect(state.tools).toEqual([{ id: "Code.exe", label: "Code.exe" }]);
    expect(state.domains).toEqual(["docs.google.com"]);

    const grace = buildVoidLauncherState({
      ...workingFrog,
      phase: "grace",
      frog: null,
      graceRemainingSeconds: 127,
    }, true, 600, 10);
    expect(grace).toMatchObject({
      phase: "grace",
      title: "Get ready",
      graceRemainingSeconds: 127,
      running: false,
      tools: [],
      domains: [],
    });
    expect(isVoidLauncherState(grace)).toBe(true);

    const picking = buildVoidLauncherState({ ...workingFrog, phase: "pick_frog", frog: null }, true, 1500, 25);
    expect(picking).toMatchObject({
      phase: "pick_frog",
      title: "Pick one task",
      graceRemainingSeconds: 0,
      running: false,
      tools: [],
      domains: [],
    });
    expect(isVoidLauncherState(picking)).toBe(true);
    expect(isVoidLauncherState({ ...state, graceRemainingSeconds: 301 })).toBe(false);
    expect(isVoidLauncherState({ ...state, graceRemainingSeconds: -1 })).toBe(false);
    expect(isVoidLauncherState({ ...state, graceRemainingSeconds: Number.NaN })).toBe(false);
    // Older protocol v1 snapshots omitted the grace field; keep them readable.
    const legacyState: Record<string, unknown> = { ...state };
    delete legacyState.graceRemainingSeconds;
    expect(isVoidLauncherState(legacyState)).toBe(true);
    expect(buildVoidLauncherState({ ...workingFrog, enabled: false }, false, 1500, 25)).toBeNull();
  });

  it("accepts only bounded task selections with normalized app and site allowlists", () => {
    expect(parseVoidAction({
      action: "select_frog",
      title: "  Study chemistry  ",
      appIds: ["Code.exe", "Code.exe", "chrome.EXE"],
      domains: ["docs.google.com", "docs.google.com"],
    })).toEqual({
      action: "select_frog",
      title: "Study chemistry",
      appIds: ["Code.exe", "chrome.EXE"],
      domains: ["docs.google.com"],
    });
    expect(parseVoidAction({ action: "select_frog", title: "  ", appIds: [], domains: [] })).toBeNull();
    expect(parseVoidAction({ action: "select_frog", title: "x".repeat(201), appIds: [], domains: [] })).toBeNull();
    expect(parseVoidAction({ action: "select_frog", title: "Task", appIds: ["../bad.exe"], domains: [] })).toBeNull();
    expect(parseVoidAction({ action: "select_frog", title: "Task", appIds: [], domains: ["http://example.com"] })).toBeNull();
    expect(parseVoidAction({ action: "select_frog", title: "Task", appIds: Array(65).fill("app.exe"), domains: [] })).toBeNull();
    expect(parseVoidAction({ action: "select_frog", title: "Task", appIds: [], domains: Array(65).fill("x.com") })).toBeNull();
  });

  it("keeps grace and task selection separate from focus timer work and explicit completion", () => {
    const grace = buildVoidLauncherState({
      ...workingFrog,
      phase: "grace",
      frog: null,
      graceRemainingSeconds: 30,
    }, true, 0, 25);
    const picking = buildVoidLauncherState({ ...workingFrog, phase: "pick_frog", frog: null }, true, 0, 25);
    expect(grace?.running).toBe(false);
    expect(picking?.running).toBe(false);
    expect(parseVoidAction({ action: "timer_expired" })).toBeNull();
    expect(parseVoidAction({ action: "tick_off" })).toEqual({ action: "tick_off" });

    const expiry = sampleVoidClock(1_000, 2_000, 0, 1);
    expect(expiry.wholeSeconds).toBe(1);
    const expiredWorking = buildVoidLauncherState({ ...workingFrog, tickedOff: false }, true, 0, 25);
    expect(expiredWorking).toMatchObject({ phase: "working", remainingSeconds: 0, tickedOff: false });
    // Timer sampling reports elapsed work; completion remains a distinct host action.
    expect(parseVoidAction({ action: "timer_expired" })).toBeNull();
  });

  it("accepts only known actions and clamps duration input", () => {
    expect(parseVoidAction({ action: "toggle_timer" })).toEqual({ action: "toggle_timer" });
    expect(parseVoidAction({ action: "set_duration", minutes: 0 })).toEqual({ action: "set_duration", minutes: 1 });
    expect(parseVoidAction({ action: "set_duration", minutes: 999 })).toEqual({ action: "set_duration", minutes: 480 });
    expect(parseVoidAction({ action: "set_duration", minutes: "25" })).toBeNull();
    expect(parseVoidAction({ action: "launch_error" })).toBeNull();
    expect(parseVoidAction({ action: "unexpected" })).toBeNull();
    expect(parseVoidAction(null)).toBeNull();
  });
});

describe("Void focus clock", () => {
  it("preserves pause and resume boundaries and carries partial seconds", () => {
    let sample = sampleVoidClock(null, 1_000, 0, 60);
    sample = sampleVoidClock(sample.sampledAt, 2_500, sample.remainderMs, 60);
    expect(sample.wholeSeconds).toBe(1);
    expect(sample.remainderMs).toBe(500);

    // Pausing clears the previous monotonic timestamp; resume starts a new span.
    // The hook resets its baseline to the resume time but retains prior active subsecond time.
    sample = sampleVoidClock(30_000, 31_000, sample.remainderMs, 59);
    expect(sample.wholeSeconds).toBe(1);
    expect(sample.remainderMs).toBe(500);
  });

  it("drops long suspend gaps and clamps credited time at block completion", () => {
    let sample = sampleVoidClock(10_000, 16_500, 0, 60);
    expect(sample).toMatchObject({ wholeSeconds: 0, remainderMs: 0, suspended: true });
    sample = sampleVoidClock(sample.sampledAt, 17_500, sample.remainderMs, 60);
    expect(sample.wholeSeconds).toBe(1);

    sample = sampleVoidClock(0, 5_000, 0, 2);
    expect(sample.wholeSeconds).toBe(2);
    expect(sample.remainderMs).toBe(0);
  });

  it("claims completion persistence once when timer expiry and task completion race", () => {
    const saved = new Set<string>();
    expect(claimVoidCompletion(saved, "session-1")).toBe(true);
    expect(claimVoidCompletion(saved, "session-1")).toBe(false);
    expect(claimVoidCompletion(saved, "session-2")).toBe(true);
  });

  it("rejects a late startup result when the helper already closed", () => {
    expect(canAcceptVoidStart(true, false)).toBe(true);
    expect(canAcceptVoidStart(false, false)).toBe(false);
    expect(canAcceptVoidStart(true, true)).toBe(false);
  });
});
