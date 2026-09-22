/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const site = { domain: "example.com", displayName: "Example", isBlocked: true, category: "Web" };
const bucket = { date: "2026-09-19", targetKind: "website" as const, targetKey: "example.com",
  targetLabel: "Example", trackedSeconds: 120, blockedSeconds: 20, launchCount: 3, updatedAt: 100 };

describe("account sync durability", () => {
  it("rejects signed-out access and isolates account data", async () => {
    const t = convexTest(schema, modules);
    await expect(t.query(api.focus.getSnapshot, {})).rejects.toThrow("Not authenticated");
    await expect(t.mutation(api.focus.setBlockedWebsite, { ...site, updatedAt: 100 })).rejects.toThrow("Not authenticated");
    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    await alice.mutation(api.focus.setBlockedWebsite, { ...site, updatedAt: 100 });
    expect((await bob.query(api.focus.getSnapshot, {})).sites).toEqual([]);
    expect(await alice.query(api.focus.getAccount, {})).toEqual({ userId: "alice" });
    // A new client/session using the same subject restores the stored state.
    const restored = t.withIdentity({ subject: "alice", tokenIdentifier: "another-session" });
    expect((await restored.query(api.focus.getSnapshot, {})).sites).toMatchObject([site]);
  });

  it("preserves deleted collections and rejects an offline stale snapshot", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.focus.saveBlockedWebsites, { sites: [site], updatedAt: 100 });
    await t.mutation(api.focus.saveBlockedWebsites, { sites: [], updatedAt: 200 });
    expect(await t.mutation(api.focus.saveBlockedWebsites, { sites: [site], updatedAt: 150 }))
      .toEqual({ applied: false, updatedAt: 200 });
    expect(await t.query(api.focus.getSnapshot, {})).toMatchObject({ sites: [], sitesUpdatedAt: 200 });
    expect(await t.query(api.focus.getDashboard, {})).toMatchObject({ sites: [], sitesUpdatedAt: 200 });
  });

  it("merges different website edits from two devices", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.focus.setBlockedWebsite, { ...site, updatedAt: 100 });
    await t.mutation(api.focus.setBlockedWebsite, { ...site, domain: "another.example", updatedAt: 100 });
    expect((await t.query(api.focus.getSnapshot, {})).sites).toHaveLength(2);
  });

  it("protects data from older deployments without collection version rows", async () => {
    const t = convexTest(schema, modules);
    await t.run(async (ctx) => {
      await ctx.db.insert("blockedWebsites", { ...site, userId: "alice", updatedAt: 500 });
    });
    const alice = t.withIdentity({ subject: "alice" });
    expect(await alice.mutation(api.focus.saveBlockedWebsites, { sites: [], updatedAt: 100 }))
      .toEqual({ applied: false, updatedAt: 500 });
    expect((await alice.query(api.focus.getSnapshot, {})).sites).toHaveLength(1);
  });

  it("does not let a delayed site edit replace a more recent device edit", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const first = await t.mutation(api.focus.setBlockedWebsite, { ...site, updatedAt: 100 });
    const rejected = await t.mutation(api.focus.setBlockedWebsite, { ...site, isBlocked: false, updatedAt: 50 });
    expect(rejected).toEqual({ applied: false, updatedAt: first.updatedAt });
    expect((await t.query(api.focus.getSnapshot, {})).sites[0].isBlocked).toBe(true);
  });

  it("retains an empty merged-group version across restore", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const group = { groupId: "video", name: "Video", members: [
      { targetKind: "app" as const, targetKey: "com.video", targetLabel: "Video" },
      { targetKind: "website" as const, targetKey: "video.example", targetLabel: "Video web" },
    ] };
    await t.mutation(api.groups.saveGroups, { groups: [group], updatedAt: 100 });
    await t.mutation(api.groups.saveGroups, { groups: [], updatedAt: 200 });
    expect(await t.mutation(api.groups.saveGroups, { groups: [group], updatedAt: 150 }))
      .toMatchObject({ applied: false, updatedAt: 200 });
    expect(await t.query(api.groups.groupsState, {})).toEqual({ groups: [], updatedAt: 200 });
  });

  it("retries usage idempotently and never erases durable counters after local reset", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    for (let i = 0; i < 2; i++) {
      await alice.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [bucket] });
    }
    await alice.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [
      { ...bucket, trackedSeconds: 10, blockedSeconds: 0, launchCount: 1, updatedAt: 200 },
    ] });
    await alice.mutation(api.usage.recordUsageBatch, { deviceId: "phone", buckets: [bucket] });
    expect(await alice.query(api.usage.getUsageSummary, {})).toMatchObject({ totalTrackedSeconds: 240 });
    expect(await t.withIdentity({ subject: "bob" }).query(api.usage.getUsageSummary, {}))
      .toMatchObject({ totalTrackedSeconds: 0 });
    const stored = await t.run((ctx) => ctx.db.query("deviceUsage").collect());
    expect(stored.find((row) => row.deviceId === "browser"))
      .toMatchObject({ trackedSeconds: 120, blockedSeconds: 20, launchCount: 3 });
  });

  it("preserves independent preference edits across out-of-order delivery", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.focus.savePrefs, { workRatio: 2, workRatioUpdatedAt: 100, updatedAt: 100 });
    await t.mutation(api.focus.savePrefs, { weeklyReport: false, updatedAt: 300 });
    await t.mutation(api.focus.savePrefs, { workRatio: 3, workRatioUpdatedAt: 200, updatedAt: 200 });
    await t.mutation(api.focus.savePrefs, { workRatio: 1, workRatioUpdatedAt: 150, updatedAt: 400 });
    expect((await t.query(api.focus.getSnapshot, {})).prefs)
      .toMatchObject({ workRatio: 3, workRatioUpdatedAt: 200, weeklyReport: false });
  });

  it("deduplicates work records after a lost upload response", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const record = { recordId: "durable-job", title: "Study", durationMinutes: 30,
      timestamp: 100, source: "android", earnedMinutesCredited: 15 };
    await t.mutation(api.focus.addWorkRecord, record);
    await t.mutation(api.focus.addWorkRecord, record);
    expect((await t.query(api.focus.getSnapshot, {})).records).toHaveLength(1);
  });

  it("commits work history and credit atomically and replays without double credit", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const record = { recordId: "offline-work", title: "Study", durationMinutes: 30,
      timestamp: 100, source: "DESKTOP_MANUAL", earnedMinutesCredited: 15,
      date: "2026-09-19", tasksCompleted: 1 };
    await t.mutation(api.focus.recordWork, record);
    await t.mutation(api.focus.recordWork, record);
    await t.mutation(api.focus.recordWork, { ...record, recordId: "other-device" });
    const restored = await t.query(api.focus.getDashboard, {});
    expect(restored.records).toHaveLength(2);
    expect(restored.sessions).toHaveLength(2);
    expect(restored.state).toMatchObject({ creditBalanceSeconds: 1800,
      totalWorkSecondsToday: 3600, tasksCompletedToday: 2 });
  });
});
