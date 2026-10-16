import { afterEach, describe, expect, it, vi } from "vitest";
import { FROG_KEYS, applyNativeFrogPolicy, armIfDue, completeSetup, emergencyDisable, frogCycleDate, readFrogState, rolloverIfNeeded, setEnabled, setNativeFrogReady } from "../desktop/src/frog";
import { setNativeStrictSettingsLocked } from "../desktop/src/strictSettings";

function store(initial: Record<string, string> = {}) {
  const entries = new Map(Object.entries(initial));
  vi.stubGlobal("window", { localStorage: { getItem: (key: string) => entries.get(key) ?? null, setItem: (key: string, value: string) => entries.set(key, value) }, dispatchEvent: vi.fn() });
  return entries;
}
afterEach(() => { setNativeStrictSettingsLocked(false); setNativeFrogReady(false); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe("desktop Frog recovery and device consent", () => {
  it("ignores a legacy enabled/armed flag without setup even during Strict Mode", () => {
    const now = new Date(2026, 9, 12, 8).getTime();
    store({ [FROG_KEYS.enabled]: "1", [FROG_KEYS.armed]: "1", [FROG_KEYS.cycleDate]: frogCycleDate(now, 5) });
    setNativeStrictSettingsLocked(true);
    expect(readFrogState(now)).toMatchObject({ enabled: false, locked: false, setupComplete: false });
    expect(armIfDue(now, false, now)).toBe(false);
  });
  it("emergency exit overrides Strict and stays disabled across next-day rollover without completing a task", () => {
    const now = new Date(2026, 9, 12, 8).getTime();
    vi.useFakeTimers();
    vi.setSystemTime(now);
    const entries = store({ [FROG_KEYS.setupComplete]: "1", [FROG_KEYS.enabled]: "1", [FROG_KEYS.armed]: "1", [FROG_KEYS.cycleDate]: frogCycleDate(now, 5), [FROG_KEYS.selected]: JSON.stringify({ title: "Study", neededAppIds: [], neededDomains: [] }), [FROG_KEYS.trackedSeconds]: "120" });
    setNativeStrictSettingsLocked(true);
    expect(() => setEnabled(false)).toThrow(/Strict/);
    emergencyDisable();
    expect(entries.get(FROG_KEYS.selected)).toContain("Study");
    expect(entries.get(FROG_KEYS.trackedSeconds)).toBe("120");
    expect(entries.get(FROG_KEYS.tickedOff)).toBe("0");
    const tomorrow = now + 24 * 60 * 60 * 1000;
    rolloverIfNeeded(tomorrow);
    expect(armIfDue(tomorrow, false, tomorrow)).toBe(false);
    expect(readFrogState(tomorrow)).toMatchObject({ enabled: false, locked: false, emergencyDisabled: true });
    completeSetup(["code.exe"], ["docs.google.com"]);
    setEnabled(true);
    expect(readFrogState(tomorrow)).toMatchObject({ enabled: true, emergencyDisabled: false });
  });
  it("requires native verification before a Tauri webview may arm persisted local consent", () => {
    const now = new Date(2026, 9, 12, 8).getTime();
    store({ [FROG_KEYS.setupComplete]: "1", [FROG_KEYS.enabled]: "1", [FROG_KEYS.armed]: "1", [FROG_KEYS.cycleDate]: frogCycleDate(now, 5) });
    (window as any).__TAURI_INTERNALS__ = {};
    setNativeFrogReady(false);
    expect(readFrogState(now)).toMatchObject({ enabled: false, locked: false });
    expect(armIfDue(now, false, now)).toBe(false);
    applyNativeFrogPolicy({ setupComplete: true, enabled: false, emergencyDisabled: true, appIds: [], domains: [] });
    setNativeFrogReady(true);
    expect(readFrogState(now)).toMatchObject({ enabled: false, armed: false, emergencyDisabled: true });
  });
});
