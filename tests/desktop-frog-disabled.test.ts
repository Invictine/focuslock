import { afterEach, describe, expect, it, vi } from "vitest";
import { FROG_KEYS, readFrogState, rolloverIfNeeded, armIfDue, setEnabled, setRequiredMinutes, setWakeHour, selectFrog, clearFrog, tickOffFrog, setTrackedSeconds, addTrackedSeconds } from "../desktop/src/frog";

afterEach(() => vi.unstubAllGlobals());

describe("temporarily suspended desktop Frog", () => {
  it("ignores a saved armed lock and preserves task/progress across every public action", () => {
    const entries = new Map<string, string>([
      [FROG_KEYS.enabled, "1"], [FROG_KEYS.armed, "1"],
      [FROG_KEYS.cycleDate, "2026-10-06"], [FROG_KEYS.trackedSeconds, "123"],
      [FROG_KEYS.selected, JSON.stringify({ title: "Saved task", neededAppIds: ["editor.exe"], neededDomains: [] })],
    ]);
    const saved = Array.from(entries);
    const setItem = vi.fn((key: string, value: string) => entries.set(key, value));
    vi.stubGlobal("window", { localStorage: { getItem: (key: string) => entries.get(key) ?? null, setItem } });
    const now = new Date(2026, 9, 6, 12).getTime();
    expect(readFrogState(now)).toMatchObject({ enabled: false, armed: false, locked: false, phase: "not_armed" });
    expect(armIfDue(now)).toBe(false);
    expect(rolloverIfNeeded(now + 86_400_000)).toBe(false);
    setEnabled(true); setRequiredMinutes(5); setWakeHour(7);
    selectFrog({ title: "Replacement", neededAppIds: [], neededDomains: [] }, now);
    clearFrog(now); tickOffFrog(true, now); setTrackedSeconds(999, now); addTrackedSeconds(60, now);
    expect(setItem).not.toHaveBeenCalled();
    expect(Array.from(entries)).toEqual(saved);
  });
});
