import { afterEach, describe, expect, it, vi } from "vitest";
import { DESKTOP_FROG_ENABLED } from "../desktop/src/features";
import { FROG_KEYS, armIfDue, frogCycleDate, readFrogState, selectFrog, setEnabled, completeSetup, emergencyDisable } from "../desktop/src/frog";

afterEach(() => vi.unstubAllGlobals());

describe("temporarily disabled PC Frog", () => {
  it("defaults off and requires explicit setup before enabling", () => {
    const now = new Date(2026, 9, 9, 8, 0, 0).getTime();
    const entries = new Map<string, string>([
      [FROG_KEYS.enabled, "1"], [FROG_KEYS.armed, "1"],
      [FROG_KEYS.cycleDate, frogCycleDate(now, 5)],
      [FROG_KEYS.selected, JSON.stringify({ title: "Study", neededAppIds: ["Code.exe"], neededDomains: [] })],
      [FROG_KEYS.trackedSeconds, "120"],
    ]);
    vi.stubGlobal("window", {
      localStorage: { getItem: (key: string) => entries.get(key) ?? null, setItem: (key: string, value: string) => entries.set(key, value) },
      dispatchEvent: vi.fn(),
    });
    expect(DESKTOP_FROG_ENABLED).toBe(true);
    entries.delete(FROG_KEYS.setupComplete);
    entries.delete(FROG_KEYS.enabled);
    const fresh = readFrogState(now);
    expect(fresh.enabled).toBe(false);
    expect(() => setEnabled(true)).toThrow(/setup/i);
    completeSetup(["code.exe"], []);
    setEnabled(true);
    emergencyDisable();
    expect(readFrogState(now).enabled).toBe(false);
    expect(readFrogState(now).emergencyDisabled).toBe(true);
    expect(() => setEnabled(true)).toThrow(/emergency/i);
    expect(armIfDue(now, false, now)).toBe(false);
    selectFrog({ title: "Different task", neededAppIds: ["other.exe"], neededDomains: [] }, now);
    expect(JSON.parse(entries.get(FROG_KEYS.selected) || "{}").neededAppIds).toEqual([]);
  });
});
