/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const today = () => new Date().toISOString().slice(0, 10);
const yesterday = () => new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);
const usageBucket = (overrides: Record<string, unknown> = {}) => ({
  date: today(), targetKind: "website" as const, targetKey: "example.com", targetLabel: "Example",
  trackedSeconds: 100, updatedAt: 100, ...overrides,
});

describe("Chrome policy and leisure parity", () => {
  it("returns a versioned policy once, omits unchanged policy, and returns deletion plus requested usage", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.run(async (ctx) => {
      await ctx.db.insert("focusState", {
        userId: "alice", creditBalanceSeconds: 900, totalWorkSecondsToday: 0,
        totalScrollSecondsToday: 35, tasksCompletedToday: 0, lastResetDate: today(), updatedAt: 100,
      });
      await ctx.db.insert("targetGroups", {
        userId: "alice", groupId: "combined", name: "Combined", members: [
          { targetKind: "app", targetKey: "video.app", targetLabel: "Video" },
          { targetKind: "website", targetKey: "video.example", targetLabel: "Video web" },
        ], updatedAt: 110,
      });
      await ctx.db.insert("appLimits", {
        userId: "alice", targetKind: "app", targetKey: "video.app", dailyLimitMinutes: 30, updatedAt: 120,
      });
      await ctx.db.insert("blockSchedules", {
        userId: "alice", scheduleId: "school", label: "School", targetKind: "app", targetKey: "video.app",
        days: [1, 2, 3, 4, 5], startMinute: 540, endMinute: 1020, isEnabled: true, updatedAt: 130,
      });
      await ctx.db.insert("userPrefs", {
        userId: "alice", strictMode: false, strictSessionId: "session-a", strictApprovedSessionId: "session-old",
        strictApprovedEndsAt: 500, strictApprovedAt: 450, globalDailyCapMinutes: 80,
        weeklyReport: true, updatedAt: 140,
      });
    });

    const first = await t.query(api.focus.getSyncPulse, {
      sitesUpdatedAt: 0, prefsUpdatedAt: 0, knownPolicyVersion: "stale", usageDate: today(),
    });
    expect(first.policyVersion).toBe(JSON.stringify([100, 110, 120, 130]));
    expect(first.policy).toMatchObject({
      state: { creditBalanceSeconds: 900 }, groups: [{ groupId: "combined" }],
      limits: [{ targetKey: "video.app" }], schedules: [{ scheduleId: "school" }],
    });
    expect(first.prefs).toMatchObject({
      globalDailyCapMinutes: 80, strictSessionId: "session-a", strictApprovedSessionId: "session-old",
      strictApprovedEndsAt: 500, strictApprovedAt: 450,
    });
    expect(first.usageSummary).toBeDefined();

    const unchanged = await t.query(api.focus.getSyncPulse, {
      sitesUpdatedAt: 0, prefsUpdatedAt: 140, knownPolicyVersion: first.policyVersion!, usageDate: today(),
    });
    expect(unchanged.policyVersion).toBe(first.policyVersion);
    expect(unchanged).not.toHaveProperty("policy");
    expect(unchanged).toHaveProperty("usageSummary");

    await t.mutation(api.groups.saveGroups, { groups: [], updatedAt: 200 });
    await t.mutation(api.focus.saveAppLimits, { limits: [], updatedAt: 200 });
    await t.mutation(api.focus.saveSchedules, { schedules: [], updatedAt: 200 });
    const deleted = await t.query(api.focus.getSyncPulse, {
      sitesUpdatedAt: 0, prefsUpdatedAt: 140, knownPolicyVersion: first.policyVersion!,
    });
    expect(deleted.policy).toMatchObject({ groups: [], limits: [], schedules: [] });
    expect(deleted.policyVersion).not.toBe(first.policyVersion);
  });

  it("rejects invalid usage dates on the opt-in pulse", async () => {
    const alice = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await expect(alice.query(api.focus.getSyncPulse, {
      sitesUpdatedAt: 0, prefsUpdatedAt: 0, usageDate: "2026-02-30",
    })).rejects.toThrow("Invalid usage date");
  });

  it("accepts Chrome project labels on the atomic work-record mutation", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.focus.recordWork, {
      recordId: "chrome-task-1", title: "Read chapter", durationMinutes: 25,
      timestamp: Date.now(), source: "CHROME_MANUAL", earnedMinutesCredited: 20,
      date: today(), tasksCompleted: 1, projectName: "Biology",
    });
    expect(await t.run((ctx) => ctx.db.query("workRecords").collect()))
      .toMatchObject([{ title: "Read chapter", projectName: "Biology" }]);
    expect(await t.query(api.focus.getState, {})).toMatchObject({
      state: {
        creditBalanceSeconds: 1200, tasksCompletedToday: 1,
        externalEarnedSeconds: 1200, externalSpentSeconds: 0,
        externalDate: today(), externalWorkSecondsToday: 1500,
        externalScrollSecondsToday: 0, externalTasksCompletedToday: 1,
      },
    });
  });

  it("charges cumulative Chrome leisure spending once, including increments and stale counter updates", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await t.run(async (ctx) => {
      await ctx.db.insert("focusState", {
        userId: "alice", creditBalanceSeconds: 500, totalWorkSecondsToday: 0,
        totalScrollSecondsToday: 10, tasksCompletedToday: 0, lastResetDate: today(), updatedAt: 100,
      });
    });

    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ leisureSeconds: 30 })],
    })).toEqual({ written: 1 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ leisureSeconds: 30, updatedAt: 200 })],
    })).toEqual({ written: 0 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ leisureSeconds: 45, updatedAt: 90 })],
    })).toEqual({ written: 1 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ leisureSeconds: 20, trackedSeconds: 20, updatedAt: 300 })],
    })).toEqual({ written: 0 });
    expect(await t.withIdentity({ subject: "bob" }).mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ leisureSeconds: 50 })],
    })).toEqual({ written: 1 });

    const stored = await t.run((ctx) => ctx.db.query("deviceUsage").collect());
    expect(stored.find((row) => row.userId === "alice")).toMatchObject({ leisureSeconds: 45, trackedSeconds: 100, updatedAt: 100 });
    expect(await alice.query(api.focus.getState, {})).toMatchObject({
      state: {
        creditBalanceSeconds: 455, totalScrollSecondsToday: 55, updatedAt: expect.any(Number),
        externalEarnedSeconds: 0, externalSpentSeconds: 45, externalDate: today(),
        externalScrollSecondsToday: 45, externalWorkSecondsToday: 0, externalTasksCompletedToday: 0,
      },
    });
    expect(await t.withIdentity({ subject: "bob" }).query(api.focus.getState, {}))
      .toMatchObject({ state: { creditBalanceSeconds: 0, totalScrollSecondsToday: 50 } });
  });

  it("keeps legacy callers uncharged and rolls scroll totals forward without letting old days reset today", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.run(async (ctx) => {
      await ctx.db.insert("focusState", {
        userId: "alice", creditBalanceSeconds: 90, totalWorkSecondsToday: 0,
        totalScrollSecondsToday: 17, tasksCompletedToday: 0, lastResetDate: today(), updatedAt: 500,
      });
    });
    await t.mutation(api.usage.recordUsageBatch, { deviceId: "android", buckets: [usageBucket()] });
    expect(await t.query(api.focus.getState, {})).toMatchObject({
      state: { creditBalanceSeconds: 90, totalScrollSecondsToday: 17 },
    });
    await t.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ date: yesterday(), leisureSeconds: 40 })],
    });
    expect(await t.query(api.focus.getState, {})).toMatchObject({
      state: { creditBalanceSeconds: 50, totalScrollSecondsToday: 17, lastResetDate: today() },
    });
    await t.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ leisureSeconds: 20, updatedAt: 700 })],
    });
    expect(await t.query(api.focus.getState, {})).toMatchObject({
      state: { creditBalanceSeconds: 30, totalScrollSecondsToday: 37, lastResetDate: today() },
    });
  });

  it("sums duplicate leisure buckets and caps debits at the available balance", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.run(async (ctx) => {
      await ctx.db.insert("focusState", {
        userId: "alice", creditBalanceSeconds: 15, totalWorkSecondsToday: 0,
        totalScrollSecondsToday: 0, tasksCompletedToday: 0, lastResetDate: today(), updatedAt: 500,
      });
    });
    const base = usageBucket({ leisureSeconds: 10 });
    await t.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [base, { ...base, leisureSeconds: 20, trackedSeconds: 100, updatedAt: 110 }],
    });
    expect(await t.query(api.focus.getState, {})).toMatchObject({
      state: { creditBalanceSeconds: 0, totalScrollSecondsToday: 30, externalSpentSeconds: 15 },
    });
    await expect(t.mutation(api.usage.recordUsageBatch, {
      deviceId: "chrome", buckets: [usageBucket({ leisureSeconds: 101 })],
    })).rejects.toThrow(/Leisure seconds/);
  });

  it("rejects a stale native compare-and-set after atomic Chrome work updates the bank", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.run(async (ctx) => {
      await ctx.db.insert("focusState", {
        userId: "alice", creditBalanceSeconds: 100, totalWorkSecondsToday: 0,
        totalScrollSecondsToday: 0, tasksCompletedToday: 0, lastResetDate: today(), updatedAt: 100,
        externalEarnedSeconds: 10, externalSpentSeconds: 5, externalDate: today(),
        externalWorkSecondsToday: 30, externalScrollSecondsToday: 5, externalTasksCompletedToday: 1,
      });
    });
    const nativeState = {
      creditBalanceSeconds: 100, totalWorkSecondsToday: 0, totalScrollSecondsToday: 0,
      tasksCompletedToday: 0, lastResetDate: today(), updatedAt: 101,
    };
    expect(await t.mutation(api.focus.saveState, { ...nativeState, expectedUpdatedAt: 99,
      acknowledgedExternalEarnedSeconds: 10, acknowledgedExternalSpentSeconds: 5 }))
      .toEqual({ applied: false, updatedAt: 100 });

    await t.mutation(api.focus.recordWork, {
      recordId: "chrome-race", title: "Study", durationMinutes: 10, timestamp: Date.now(),
      source: "CHROME_MANUAL", earnedMinutesCredited: 2, date: today(), tasksCompleted: 1,
    });
    const afterWork = await t.query(api.focus.getState, {});
    expect(afterWork.state).toMatchObject({
      creditBalanceSeconds: 220, updatedAt: expect.any(Number),
      externalEarnedSeconds: 130, externalSpentSeconds: 5,
    });

    expect(await t.mutation(api.focus.saveState, { ...nativeState, expectedUpdatedAt: 100,
      acknowledgedExternalEarnedSeconds: 10, acknowledgedExternalSpentSeconds: 5 }))
      .toEqual({ applied: false, updatedAt: afterWork.state!.updatedAt });
    expect((await t.query(api.focus.getState, {})).state)
      .toMatchObject({ creditBalanceSeconds: 220, externalEarnedSeconds: 130, externalSpentSeconds: 5 });

    expect(await t.mutation(api.focus.saveState, {
      ...nativeState,
      expectedUpdatedAt: afterWork.state!.updatedAt,
      acknowledgedExternalEarnedSeconds: 10,
      acknowledgedExternalSpentSeconds: 5,
    })).toEqual({ applied: false, updatedAt: afterWork.state!.updatedAt });
    expect((await t.query(api.focus.getState, {})).state?.creditBalanceSeconds).toBe(220);
  });

  it("preserves external fields across legacy full-state writes and accepts matching acknowledgements", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.run(async (ctx) => {
      await ctx.db.insert("focusState", {
        userId: "alice", creditBalanceSeconds: 300, totalWorkSecondsToday: 60,
        totalScrollSecondsToday: 30, tasksCompletedToday: 2, lastResetDate: today(), updatedAt: 100,
        externalEarnedSeconds: 200, externalSpentSeconds: 40, externalDate: today(),
        externalWorkSecondsToday: 120, externalScrollSecondsToday: 40, externalTasksCompletedToday: 1,
      });
    });
    const state = {
      creditBalanceSeconds: 250, totalWorkSecondsToday: 90, totalScrollSecondsToday: 20,
      tasksCompletedToday: 3, lastResetDate: today(), updatedAt: 101,
    };
    expect(await t.mutation(api.focus.saveState, { ...state, expectedUpdatedAt: 100,
      acknowledgedExternalEarnedSeconds: 200, acknowledgedExternalSpentSeconds: 40 }))
      .toEqual({ applied: true, updatedAt: 101 });
    expect(await t.mutation(api.focus.saveState, { ...state, updatedAt: 102 }))
      .toEqual({ applied: true, updatedAt: 102 });
    expect((await t.query(api.focus.getState, {})).state).toMatchObject({
      creditBalanceSeconds: 250, totalWorkSecondsToday: 90,
      externalEarnedSeconds: 200, externalSpentSeconds: 40, externalDate: today(),
      externalWorkSecondsToday: 120, externalScrollSecondsToday: 40, externalTasksCompletedToday: 1,
    });
  });
});
