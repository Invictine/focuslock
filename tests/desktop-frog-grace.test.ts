import { afterEach, describe, expect, it, vi } from "vitest";
import { FROG_DAILY_GRACE_MS, FROG_KEYS, armIfDue, readFrogState } from "../desktop/src/frog";

afterEach(() => vi.unstubAllGlobals());

function useMemoryStorage() {
  const entries = new Map<string, string>();
  vi.stubGlobal("window", {
    localStorage: {
      getItem: (key: string) => entries.get(key) ?? null,
      setItem: (key: string, value: string) => entries.set(key, value),
    },
    dispatchEvent: vi.fn(),
  });
  return entries;
}

describe("daily device-local Frog grace", () => {
  it("starts at the first active input after wake, persists through restart, then arms at five minutes", () => {
    const entries = useMemoryStorage();
    const now = new Date(2026, 9, 9, 8, 0, 0).getTime();

    expect(armIfDue(now, true)).toBe(false);
    expect(entries.get(FROG_KEYS.graceStartedAtMs)).toBe("0");
    expect(armIfDue(now + 1_000, false, now + 1_000)).toBe(false);
    expect(readFrogState(now + 1_000)).toMatchObject({ phase: "grace", armed: false, locked: false, graceRemainingSeconds: 300 });

    // A new hook/app instance reads the same persisted wall-clock start.
    expect(readFrogState(now + 90_000)).toMatchObject({ phase: "grace", graceRemainingSeconds: 211, locked: false });
    expect(armIfDue(now + 1_000 + FROG_DAILY_GRACE_MS - 1, true)).toBe(false);
    expect(armIfDue(now + 1_000 + FROG_DAILY_GRACE_MS, true)).toBe(true);
    expect(readFrogState(now + 1_000 + FROG_DAILY_GRACE_MS)).toMatchObject({ phase: "pick_frog", armed: true, locked: true, graceRemainingSeconds: 0 });
  });

  it("does not start before the configured wake hour", () => {
    const entries = useMemoryStorage();
    entries.set(FROG_KEYS.wakeHour, "9");
    const beforeWake = new Date(2026, 9, 9, 8, 59, 0).getTime();
    expect(armIfDue(beforeWake, false, beforeWake)).toBe(false);
    const afterWake = new Date(2026, 9, 9, 9, 0, 1).getTime();
    expect(armIfDue(afterWake, false, beforeWake)).toBe(false);
    expect(entries.get(FROG_KEYS.graceStartedAtMs)).toBe("0");
  });
});
