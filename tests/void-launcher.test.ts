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
  locked: true,
  requiredMinutes: 30,
  wakeHour: 5,
};

describe("Void launcher protocol", () => {
  it("builds a versioned snapshot only for an active working frog", () => {
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
    expect(buildVoidLauncherState({ ...workingFrog, phase: "pick_frog", frog: null }, false, 1500, 25)).toBeNull();
    expect(buildVoidLauncherState({ ...workingFrog, enabled: false }, false, 1500, 25)).toBeNull();
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
