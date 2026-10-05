import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const app = { packageName: "com.video", appName: "Video", isBlocked: true, category: "Entertainment" };
const site = { domain: "video.example", displayName: "Video", isBlocked: true, category: "Web" };
const limit = { targetKind: "app", targetKey: app.packageName, dailyLimitMinutes: 30 };
const schedule = { scheduleId: "school", label: "School", targetKind: "app", targetKey: app.packageName,
  days: [1, 2, 3], startMinute: 480, endMinute: 900, isEnabled: true };
const group = { groupId: "video", name: "Video", dailyLimitMinutes: 30, members: [
  { targetKind: "app" as const, targetKey: app.packageName, targetLabel: app.appName },
  { targetKind: "website" as const, targetKey: site.domain, targetLabel: site.displayName },
] };

async function committed() {
  const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
  await t.mutation(api.focus.saveBlockedApps, { apps: [app], updatedAt: 100 });
  await t.mutation(api.focus.saveBlockedWebsites, { sites: [site], updatedAt: 100 });
  await t.mutation(api.focus.saveAppLimits, { limits: [limit], updatedAt: 100 });
  await t.mutation(api.focus.saveSchedules, { schedules: [schedule], updatedAt: 100 });
  await t.mutation(api.groups.saveGroups, { groups: [group], updatedAt: 100 });
  await t.mutation(api.focus.savePrefs, { strictMode: true, strictEndsAt: Date.now() + 60_000, updatedAt: Date.now() });
  return t;
}

describe("Strict Mode boundary configuration freeze", () => {
  it("rejects additions, removals, toggles and targeting changes", async () => {
    const t = await committed();
    for (const apps of [[], [{ ...app, isBlocked: false }], [app, { ...app, packageName: "com.other" }],
      [{ ...app, specificShortsOnly: true }]]) {
      await expect(t.mutation(api.focus.saveBlockedApps, { apps, updatedAt: 200 })).rejects.toThrow("cannot change");
    }
    for (const sites of [[], [{ ...site, isBlocked: false }], [site, { ...site, domain: "other.example" }]]) {
      await expect(t.mutation(api.focus.saveBlockedWebsites, { sites, updatedAt: 200 })).rejects.toThrow("cannot change");
    }
    await expect(t.mutation(api.focus.setBlockedWebsite, { ...site, isBlocked: false, updatedAt: 200 })).rejects.toThrow("cannot change");
    await expect(t.mutation(api.focus.setBlockedWebsite, { ...site, domain: "new.example", updatedAt: 200 })).rejects.toThrow("cannot change");
    expect((await t.query(api.focus.getSnapshot, {})).apps).toMatchObject([app]);
  });

  it("freezes limits, schedules and merged groups", async () => {
    const t = await committed();
    await expect(t.mutation(api.focus.saveAppLimits, { limits: [{ ...limit, dailyLimitMinutes: 60 }], updatedAt: 200 })).rejects.toThrow("cannot change");
    await expect(t.mutation(api.focus.saveSchedules, { schedules: [{ ...schedule, isEnabled: false }], updatedAt: 200 })).rejects.toThrow("cannot change");
    await expect(t.mutation(api.groups.saveGroups, { groups: [{ ...group, dailyLimitMinutes: 90 }], updatedAt: 200 })).rejects.toThrow("cannot change");
    await expect(t.mutation(api.groups.saveGroups, { groups: [], updatedAt: 200 })).rejects.toThrow("cannot change");
    await expect(t.mutation(api.focus.savePrefs, { globalDailyCapMinutes: 90, updatedAt: Date.now() + 1 })).rejects.toThrow("cannot change");
  });

  it("accepts unchanged sync snapshots without affecting credit or usage", async () => {
    const t = await committed();
    expect(await t.mutation(api.focus.saveBlockedApps, { apps: [app], updatedAt: 200 })).toMatchObject({ applied: true });
    expect(await t.mutation(api.focus.saveBlockedWebsites, { sites: [site], updatedAt: 200 })).toMatchObject({ applied: true });
    expect(await t.mutation(api.focus.saveAppLimits, { limits: [limit], updatedAt: 200 })).toMatchObject({ applied: true });
    expect(await t.mutation(api.focus.saveSchedules, { schedules: [schedule], updatedAt: 200 })).toMatchObject({ applied: true });
    expect(await t.mutation(api.groups.saveGroups, { groups: [group], updatedAt: 200 })).toMatchObject({ applied: true });
    expect(await t.mutation(api.focus.saveState, { creditBalanceSeconds: 600, totalWorkSecondsToday: 600,
      totalScrollSecondsToday: 0, tasksCompletedToday: 1, lastResetDate: "2026-10-05", updatedAt: 200 })).toMatchObject({ applied: true });
  });

  it("accepts identical per-site retries and unlocks edits after expiry", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.focus.setBlockedWebsite, { ...site, updatedAt: Date.now() });
    await t.mutation(api.focus.savePrefs, { strictMode: true, strictEndsAt: Date.now() + 60_000, updatedAt: Date.now() });
    expect(await t.mutation(api.focus.setBlockedWebsite, { ...site, updatedAt: Date.now() + 1 })).toMatchObject({ applied: true });
    await t.run(async (ctx) => {
      const prefs = await ctx.db.query("userPrefs").first();
      await ctx.db.patch(prefs!._id, { strictEndsAt: Date.now() - 1 });
    });
    expect(await t.mutation(api.focus.setBlockedWebsite, { ...site, isBlocked: false, updatedAt: Date.now() + 2 })).toMatchObject({ applied: true });
  });
});
