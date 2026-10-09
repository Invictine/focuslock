import { describe, expect, it } from "vitest";
import { browserProtectionPolicy, browserProtectionStatusLabel } from "../desktop/src/browserProtection";

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
  it("keeps application-only rules independent and requires protection for a locked Frog", () => {
    expect(browserProtectionPolicy({ apps: [{ isBlocked: true }], limits: [{ targetKind: "app", dailyLimitMinutes: 30 }] }, [], false).required).toBe(false);
    expect(browserProtectionPolicy({}, [], true).required).toBe(true);
  });
  it("does not lock the optional checker to a Strict Mode commitment", () => {
    const dashboard = { sites: [{ isBlocked: true }], prefs: { strictMode: true, strictEndsAt: 2000 } };
    expect(browserProtectionPolicy(dashboard, [], false, 1000).lockedUntilMs).toBe(0);
    expect(browserProtectionPolicy(dashboard, [], false, 3000).lockedUntilMs).toBe(0);
  });
  it("clears the required policy when no signed-in dashboard is available", () => {
    expect(browserProtectionPolicy(undefined, [], false).required).toBe(false);
  });
});

describe("browser protection status label", () => {
  it("shows a connected background browser supplied by the native monitor", () => {
    expect(browserProtectionStatusLabel({
      browserProtectionEnabled: true,
      browserProtectionRequired: true,
      browserProtectionScanState: "browser",
      browserProtection: { browser: "Chrome", healthy: true, graceRemainingSeconds: 0 },
    }, true)).toBe("Chrome · Connected");
  });

  it("distinguishes a completed scan with no browser window", () => {
    expect(browserProtectionStatusLabel({
      browserProtectionEnabled: true,
      browserProtectionRequired: true,
      browserProtectionScanState: "no_browser",
      browserProtection: null,
    }, true)).toBe("On · no browser window open");
  });

  it("reports scan errors without pretending the browser is missing", () => {
    expect(browserProtectionStatusLabel({
      browserProtectionEnabled: true,
      browserProtectionRequired: true,
      browserProtectionScanState: "scan_error",
      browserProtectionError: "Window enumeration failed",
      browserProtection: null,
    }, true)).toBe("Browser check unavailable");
  });

  it("keeps an opted-in checker enforcing website rules when authentication is lost", () => {
    expect(browserProtectionStatusLabel({ browserProtectionEnabled: true, browserProtectionRequired: true }, false)).toBe("On · checking browser status");
    expect(browserProtectionStatusLabel({ browserProtectionEnabled: true }, false)).toBe("On · no website rules to monitor");
  });

  it("shows Off when the optional checker is disabled", () => {
    expect(browserProtectionStatusLabel({ browserProtectionEnabled: false }, true)).toBe("Off");
  });
});
