import { afterEach, describe, expect, it, vi } from "vitest";
import { DESKTOP_FROG_ENABLED } from "../desktop/src/features";
import { FROG_KEYS, armIfDue, frogCycleDate, readFrogState, selectFrog, setEnabled } from "../desktop/src/frog";
import { buildVoidLauncherState } from "../desktop/src/voidLauncher";

afterEach(() => vi.unstubAllGlobals());

describe("temporarily disabled PC Frog", () => {
  it("keeps an existing armed task inactive without erasing its saved settings or launching Void", () => {
    const now = new Date(2026, 9, 9, 8, 0, 0).getTime();
    const entries = new Map<string, string>([
      [FROG_KEYS.enabled, "1"], [FROG_KEYS.armed, "1"],
      [FROG_KEYS.cycleDate, frogCycleDate(now, 5)],
      [FROG_KEYS.selected, JSON.stringify({ title: "Study", neededAppIds: ["Code.exe"], neededDomains: [] })],
      [FROG_KEYS.trackedSeconds, "120"],
    ]);
    const saved = new Map(entries);
    vi.stubGlobal("window", {
      localStorage: { getItem: (key: string) => entries.get(key) ?? null, setItem: (key: string, value: string) => entries.set(key, value) },
      dispatchEvent: vi.fn(),
    });
    expect(DESKTOP_FROG_ENABLED).toBe(false);
    const state = readFrogState(now);
    expect(state).toMatchObject({ enabled: false, armed: false, locked: false, phase: "not_armed", frog: null });
    expect(buildVoidLauncherState(state, false, 1500, 25)).toBeNull();
    expect(armIfDue(now, false, now)).toBe(false);
    setEnabled(true);
    selectFrog({ title: "Different task", neededAppIds: [], neededDomains: [] }, now);
    expect(entries).toEqual(saved);
  });
});
