import { describe, expect, it } from "vitest";
import { browserProtectionPolicy } from "../desktop/src/browserProtection";

describe("native browser protection policy", () => {
  it("requires the extension while a selected site still has earned time", () => {
    expect(browserProtectionPolicy({ sites: [{ isBlocked: true }], state: { creditBalanceSeconds: 600 } }, [], false).required).toBe(true);
  });
  it("protects website limits before their allowance is exhausted and mixed groups", () => {
    expect(browserProtectionPolicy({ limits: [{ targetKind: "website", dailyLimitMinutes: 30 }] }, [], false).required).toBe(true);
    const groups = [{ dailyLimitMinutes: 15, members: [{ targetKind: "website" }] }];
    expect(browserProtectionPolicy({}, groups, false).required).toBe(true);
    expect(browserProtectionPolicy({}, [{ ...groups[0], limitEnabled: false }], false).required).toBe(false);
  });
  it("keeps application-only rules independent and honors Frog", () => {
    expect(browserProtectionPolicy({ apps: [{ isBlocked: true }], limits: [{ targetKind: "app", dailyLimitMinutes: 30 }] }, [], false).required).toBe(false);
    expect(browserProtectionPolicy({}, [], true).required).toBe(true);
  });
  it("carries only a currently active commitment deadline", () => {
    const dashboard = { sites: [{ isBlocked: true }], prefs: { strictMode: true, strictEndsAt: 2000 } };
    expect(browserProtectionPolicy(dashboard, [], false, 1000).lockedUntilMs).toBe(2000);
    expect(browserProtectionPolicy(dashboard, [], false, 3000).lockedUntilMs).toBe(0);
  });
});
