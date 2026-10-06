import { DESKTOP_FROG_ENABLED } from "./features";

/** Require the live extension before a web boundary or allowance can be bypassed. */
export function browserProtectionPolicy(dashboard: any, groups: any[], frogLocked: boolean, now = Date.now()) {
  const required = (DESKTOP_FROG_ENABLED && frogLocked) ||
    (dashboard?.sites || []).some((site: any) => site.isBlocked) ||
    (dashboard?.permanentBlocks || []).some((block: any) => block.targetKind === "website") ||
    (dashboard?.limits || []).some((limit: any) => limit.targetKind === "website" && Number(limit.dailyLimitMinutes) > 0) ||
    groups.some(group => group.limitEnabled !== false && Number(group.dailyLimitMinutes) > 0 &&
      (group.members || []).some((member: any) => member.targetKind === "website")) ||
    (dashboard?.schedules || []).some((schedule: any) => schedule.isEnabled !== false &&
      ["website", "site", "all"].includes(schedule.targetKind));
  const prefs = dashboard?.prefs;
  const lockedUntilMs = required && prefs?.strictMode && Number(prefs.strictEndsAt) > now
    ? Number(prefs.strictEndsAt) : 0;
  return { required, lockedUntilMs };
}
