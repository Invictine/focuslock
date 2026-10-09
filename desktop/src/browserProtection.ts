import { DESKTOP_FROG_ENABLED } from "./features";

/** Build the browser monitor policy from current website rules. The monitor itself is opt-in. */
export function browserProtectionPolicy(dashboard: any, groups: any[], frogLocked: boolean, now = Date.now()) {
  const required = Boolean(dashboard) && ((DESKTOP_FROG_ENABLED && frogLocked) ||
    (dashboard?.sites || []).some((site: any) => site.isBlocked) ||
    (dashboard?.permanentBlocks || []).some((block: any) => block.targetKind === "website") ||
    (dashboard?.limits || []).some((limit: any) => limit.targetKind === "website" && Number(limit.dailyLimitMinutes) > 0) ||
    groups.some(group => group.limitEnabled !== false && Number(group.dailyLimitMinutes) > 0 &&
      (group.members || []).some((member: any) => member.targetKind === "website")) ||
    (dashboard?.schedules || []).some((schedule: any) => schedule.isEnabled !== false &&
      ["website", "site", "all"].includes(schedule.targetKind)));
  // Strict Mode freezes boundary edits; it does not control this optional monitor.
  void now;
  return { required, lockedUntilMs: 0 };
}

/** Describe the opt-in browser checker without guessing from a missing browser row. */
export function browserProtectionStatusLabel(status: any, signedIn: boolean): string {
  if (!status?.browserProtectionEnabled) return "Off";
  if (!signedIn && !status.browserProtectionRequired) return "On · no website rules to monitor";
  if (!status.browserProtectionRequired) return "On · no website rules to monitor";
  if (status.browserProtection) {
    const browser = status.browserProtection;
    if (browser.healthy) return `${browser.browser} · Connected`;
    if (browser.reason === "browser_unsupported") return "Supported browser unavailable";
    if (browser.graceRemainingSeconds > 0) return `Connection unavailable · ${browser.graceRemainingSeconds}s`;
    return "Connection unavailable";
  }
  if (status.browserProtectionScanState === "no_browser") return "On · no browser window open";
  if (status.browserProtectionScanState === "scan_error") return "Browser check unavailable";
  return "On · checking browser status";
}
