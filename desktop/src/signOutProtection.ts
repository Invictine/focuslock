import { DESKTOP_FROG_ENABLED } from "./features";

export type SignOutProtection = { restricted: boolean; strictUntilMs: number | null };

/** Fail closed when the synced policy has not loaded yet. */
export function signOutProtectionPolicy(configuration: any, groups: any[] | undefined, frogLocked: boolean, now = Date.now()): SignOutProtection | null {
  if (!configuration || groups === undefined) return null;
  const apps = configuration.apps || [];
  const sites = configuration.sites || [];
  const permanent = configuration.permanentBlocks || [];
  const limits = configuration.limits || [];
  const schedules = configuration.schedules || [];
  const restricted = apps.some((x: any) => x?.isBlocked && x?.category === "Windows") ||
    sites.some((x: any) => x?.isBlocked) ||
    permanent.some((x: any) => x?.targetKind === "app" || x?.targetKind === "windows" || x?.targetKind === "website" || x?.targetKind === "site") ||
    limits.some((x: any) => ["app", "website"].includes(String(x?.targetKind)) && Number(x?.dailyLimitMinutes) > 0) ||
    groups.some((g: any) => g?.limitEnabled !== false && Number(g?.dailyLimitMinutes) > 0 && (g?.members || []).some((m: any) => ["app", "website"].includes(String(m?.targetKind)))) ||
    schedules.some((s: any) => s?.isEnabled !== false && ["app", "website", "site", "windows", "all"].includes(String(s?.targetKind))) ||
    (DESKTOP_FROG_ENABLED && frogLocked);
  const prefs = configuration.prefs || {};
  const ends = Number(prefs.strictEndsAt) || 0;
  const strictActive = Boolean(prefs.strictMode) && (!ends || ends > now);
  return { restricted, strictUntilMs: strictActive ? (ends > 0 ? ends : 0) : null };
}
