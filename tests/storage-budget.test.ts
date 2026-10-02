/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import schema from "../convex/schema";
import { api, internal } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const bucket = { date: "2026-09-29", targetKind: "website" as const, targetKey: "example.com",
  targetLabel: "Example", trackedSeconds: 120, blockedSeconds: 20, launchCount: 3, updatedAt: 100 };
const heartbeat = { name: "Browser", platform: "browser" as const, appVersion: "1",
  trackingStatus: "active" as const, lastSeen: Date.UTC(2026, 8, 30) };

beforeEach(() => vi.spyOn(Date, "now").mockReturnValue(Date.UTC(2026, 8, 30, 12)));
afterEach(() => vi.restoreAllMocks());

describe("storage and call budget regressions", () => {
  it("commits usage and presence together, and rolls both back on invalid usage", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.usage.recordUsageBatch, { deviceId: "browser", heartbeat, buckets: [bucket] });
    expect(await t.query(api.devices.listDevices, {})).toMatchObject([{ deviceId: "browser", name: "Browser" }]);
    await expect(t.mutation(api.usage.recordUsageBatch, { deviceId: "other", heartbeat,
      buckets: [{ ...bucket, date: "2026-02-30" }] })).rejects.toThrow("Invalid usage date");
    expect(await t.query(api.devices.listDevices, {})).toHaveLength(1);
    expect((await t.query(api.usage.getUsageSummary, {})).totalTrackedSeconds).toBe(120);
  });

  it("bundles groups, catalog and today's summary even when core snapshot is unchanged", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [bucket] });
    const first = await t.query(api.focus.getSnapshot, { knownGroupsUpdatedAt: -1 });
    await t.mutation(api.groups.saveGroups, { updatedAt: 100, groups: [{ groupId: "video", name: "Video", members: [
      { targetKind: "app", targetKey: "com.example", targetLabel: "App" },
      { targetKind: "website", targetKey: "example.com", targetLabel: "Web" },
    ] }] });
    const next = await t.query(api.focus.getSnapshot, { knownVersion: first.version,
      knownGroupsUpdatedAt: 0, usageDate: "2026-09-29", includeKnownTargets: true });
    expect(next.unchanged).toBe(true);
    expect(next.groupsState?.groups).toHaveLength(1);
    expect(next.knownTargets).toMatchObject([{ targetKey: "example.com", trackedSeconds: 120 }]);
    expect(next.usageSummary?.totalTrackedSeconds).toBe(120);
    expect(next.usageSummary?.groups[0].trackedSeconds).toBe(120);
  });

  it("keeps catalog totals monotonic across retries and account switches", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await alice.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [bucket] });
    await alice.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [{ ...bucket, trackedSeconds: 180, updatedAt: 200 }] });
    await alice.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [{ ...bucket, trackedSeconds: 180, updatedAt: 200 }] });
    await alice.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [{ ...bucket, trackedSeconds: 10, updatedAt: 300 }] });
    expect(await alice.query(api.usage.listKnownTargets, {})).toMatchObject([{ trackedSeconds: 180 }]);
    expect(await t.withIdentity({ subject: "bob" }).query(api.usage.listKnownTargets, {})).toEqual([]);
  });

  it("preserves archived totals in reports and acknowledges expired replays without resurrecting detail", async () => {
    const t = convexTest(schema, modules);
    await t.run(async (ctx) => {
      await ctx.db.insert("deviceUsage", { ...bucket, userId: "alice", deviceId: "browser", date: "2026-07-04" });
    });
    const alice = t.withIdentity({ subject: "alice" });
    const before = await alice.query(api.usage.getUsageSummary, {});
    await t.mutation(internal.retention.archiveUsagePage, {});
    expect(await alice.query(api.usage.getUsageSummary, {})).toEqual(before);
    expect(await alice.query(api.usage.listKnownTargets, {})).toMatchObject([{ trackedSeconds: 120, lastDate: "2026-07-04" }]);
    expect(await alice.mutation(api.usage.recordUsageBatch, { deviceId: "browser", buckets: [{ ...bucket, date: "2026-07-04" }] }))
      .toMatchObject({ written: 0, expired: 1 });
    expect(await alice.query(api.usage.getUsageSummary, {})).toEqual(before);
    expect((await alice.query(api.usage.getUsageSummary, { fromDate: "2026-09-01", toDate: "2026-09-30" })).totalTrackedSeconds).toBe(0);
  });

  it("does not count a whole archive as a partial historical range", async () => {
    const t = convexTest(schema, modules);
    await t.run(async (ctx) => {
      await ctx.db.insert("deviceUsage", { ...bucket, userId: "alice", deviceId: "browser", date: "2026-07-04" });
      await ctx.db.insert("deviceUsage", { ...bucket, userId: "alice", deviceId: "browser", date: "2026-07-20" });
    });
    await t.mutation(internal.retention.archiveUsagePage, {});
    await expect(t.withIdentity({ subject: "alice" }).query(api.usage.getUsageSummary,
      { fromDate: "2026-07-10", toDate: "2026-07-31" })).rejects.toThrow("archived monthly");
    // Merely sharing a calendar month with an archive does not make a recent
    // range invalid when none of that archive's days overlap the request.
    expect((await t.withIdentity({ subject: "alice" }).query(api.usage.getUsageSummary,
      { fromDate: "2026-07-25", toDate: "2026-09-30" })).totalTrackedSeconds).toBe(0);
  });

  it("excludes work history and device heartbeats from the configuration subscription", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.focus.recordWork, { recordId: "one", title: "Study", durationMinutes: 20,
      earnedMinutesCredited: 10, timestamp: Date.now(), source: "MANUAL", date: "2026-09-30", tasksCompleted: 1 });
    await t.mutation(api.devices.heartbeat, { deviceId: "browser", ...heartbeat });
    expect(await t.query(api.focus.getConfiguration, {})).toMatchObject({ records: [], sessions: [], usage: [], devices: [],
      state: null });
    expect(await t.query(api.focus.getState, {})).toMatchObject({ state: { creditBalanceSeconds: 600 } });
    expect(await t.run((ctx) => ctx.db.query("focusSessions").collect())).toEqual([]);
    expect(await t.query(api.focus.getDashboard, {})).toMatchObject({ records: [expect.anything()], devices: [expect.anything()] });
  });

  it("keeps reporting metadata stable across presence-only heartbeats", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.devices.heartbeat, { deviceId: "browser", ...heartbeat });
    const profiles = await t.run((ctx) => ctx.db.query("deviceProfiles").collect());
    await t.mutation(api.devices.heartbeat, { deviceId: "browser", ...heartbeat, lastSeen: heartbeat.lastSeen + 1_000 });
    expect(await t.run((ctx) => ctx.db.query("deviceProfiles").collect())).toEqual(profiles);
    expect((await t.query(api.devices.listDevices, {}))[0].lastSeen).toBe(heartbeat.lastSeen + 1_000);
  });
});
