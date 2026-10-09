import { describe, expect, it } from "vitest";
import { signOutProtectionPolicy } from "../desktop/src/signOutProtection";

const base = { apps: [], sites: [], permanentBlocks: [], limits: [], schedules: [], prefs: {} };
const evaluate = (patch: any = {}, groups: any[] = [], frog = false, now = 1_000) => signOutProtectionPolicy({ ...base, ...patch }, groups, frog, now);

describe("desktop sign-out protection policy", () => {
  it("fails closed while configuration or groups are missing", () => {
    expect(signOutProtectionPolicy(undefined, [], false)).toBeNull();
    expect(signOutProtectionPolicy(base, undefined, false)).toBeNull();
  });
  it("keeps configured blocked and permanent Windows/website targets restricted", () => {
    expect(evaluate({ apps: [{ category: "Windows", isBlocked: true }] })?.restricted).toBe(true);
    expect(evaluate({ sites: [{ isBlocked: true }] })?.restricted).toBe(true);
    expect(evaluate({ permanentBlocks: [{ targetKind: "windows" }] })?.restricted).toBe(true);
    expect(evaluate({ permanentBlocks: [{ targetKind: "website" }] })?.restricted).toBe(true);
  });
  it("restricts configured limits before exhaustion, including earned credit", () => {
    expect(evaluate({ limits: [{ targetKind: "app", dailyLimitMinutes: 30 }], creditBalanceSeconds: 3600 })?.restricted).toBe(true);
    expect(evaluate({}, [{ dailyLimitMinutes: 20, members: [{ targetKind: "website", targetKey: "x" }] }])?.restricted).toBe(true);
  });
  it("restricts dormant relevant schedules regardless of current interval", () => {
    expect(evaluate({ schedules: [{ targetKind: "all", isEnabled: true, startMinute: 900, endMinute: 901 }] })?.restricted).toBe(true);
    expect(evaluate({ schedules: [{ targetKind: "app", isEnabled: false }] })?.restricted).toBe(false);
  });
  it("only reports active Strict Mode and preserves its expiry", () => {
    expect(evaluate({ prefs: { strictMode: true, strictEndsAt: 2_000 } }, [], false, 1_000)).toEqual({ restricted: false, strictUntilMs: 2_000 });
    expect(evaluate({ prefs: { strictMode: true, strictEndsAt: 500 } }, [], false, 1_000)).toEqual({ restricted: false, strictUntilMs: null });
    expect(evaluate({ prefs: { strictMode: true, strictEndsAt: 0 } }, [], false, 1_000)).toEqual({ restricted: false, strictUntilMs: 0 });
  });
  it("does not treat mobile-only policy as a desktop restriction", () => {
    expect(evaluate({ apps: [{ category: "Android", isBlocked: true }], permanentBlocks: [{ targetKind: "category" }], schedules: [{ targetKind: "mobile" }] })?.restricted).toBe(false);
  });
});
