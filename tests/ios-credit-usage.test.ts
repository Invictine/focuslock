/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const today = () => new Date().toISOString().slice(0, 10);
const yesterday = () => new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);

function counterBucket(counterId: string, overrides: Record<string, unknown> = {}) {
  return {
    date: today(),
    targetKind: "app" as const,
    targetKey: `ios-credit:${counterId}`,
    targetLabel: "iOS leisure apps",
    trackedSeconds: 60,
    leisureSeconds: 60,
    updatedAt: Date.now(),
    ...overrides,
  };
}

async function seedState(t: ReturnType<typeof convexTest>, userId: string, creditBalanceSeconds: number) {
  await t.run(async (ctx) => {
    await ctx.db.insert("focusState", {
      userId,
      creditBalanceSeconds,
      totalWorkSecondsToday: 400,
      totalScrollSecondsToday: 20,
      tasksCompletedToday: 2,
      lastResetDate: today(),
      updatedAt: Date.now(),
      externalEarnedSeconds: 0,
      externalSpentSeconds: 0,
      externalDate: today(),
      externalWorkSecondsToday: 400,
      externalScrollSecondsToday: 20,
      externalTasksCompletedToday: 2,
    });
  });
}

describe("iOS credit usage counters", () => {
  it("debits cumulative counters once, isolates accounts, and treats a reset counter as new usage", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    await seedState(t, "alice", 600);
    await seedState(t, "bob", 1000);

    const counterId = "c36ddc7b-59b7-4972-ae1f-434c3514a8bd";
    const first = counterBucket(counterId, { updatedAt: 100, trackedSeconds: 60, leisureSeconds: 60 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable", buckets: [first],
    })).toEqual({ written: 1 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable", buckets: [counterBucket(counterId, { updatedAt: 200, trackedSeconds: 60, leisureSeconds: 60 })],
    })).toEqual({ written: 0 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable", buckets: [counterBucket(counterId, { updatedAt: 300, trackedSeconds: 120, leisureSeconds: 120 })],
    })).toEqual({ written: 1 });
    expect(await alice.query(api.focus.getState, {})).toMatchObject({
      state: { creditBalanceSeconds: 480, totalScrollSecondsToday: 140, externalSpentSeconds: 120, externalScrollSecondsToday: 140 },
    });

    // The same persisted native UUID and stable device ID are independent in
    // another authenticated account because backend usage rows are owner-keyed.
    expect(await bob.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable",
      buckets: [counterBucket(counterId, { updatedAt: 400, trackedSeconds: 30, leisureSeconds: 30 })],
    })).toEqual({ written: 1 });
    expect(await bob.query(api.focus.getState, {})).toMatchObject({
      state: { creditBalanceSeconds: 970, totalScrollSecondsToday: 50, externalSpentSeconds: 30 },
    });
    expect(await alice.query(api.focus.getState, {})).toMatchObject({ state: { creditBalanceSeconds: 480 } });

    // A recreated native ledger receives a new UUID; its cumulative counter is
    // intentionally distinct from the previous ledger and its retry is stable.
    const resetCounterId = "785e3f66-32f6-41fa-b952-5efb96c36a77";
    const reset = counterBucket(resetCounterId, { updatedAt: 500, trackedSeconds: 25, leisureSeconds: 25 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable", buckets: [reset],
    })).toEqual({ written: 1 });
    expect(await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable",
      buckets: [counterBucket(resetCounterId, { updatedAt: 600, trackedSeconds: 25, leisureSeconds: 25 })],
    })).toEqual({ written: 0 });
    expect(await alice.query(api.focus.getState, {})).toMatchObject({
      state: { creditBalanceSeconds: 455, totalScrollSecondsToday: 165, externalSpentSeconds: 145 },
    });
  });

  it("does not let yesterday's late usage roll today's focus counters backward", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await seedState(t, "alice", 600);
    await t.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable",
      buckets: [counterBucket("c36ddc7b-59b7-4972-ae1f-434c3514a8bd", {
        date: yesterday(), updatedAt: Date.now(), trackedSeconds: 300, leisureSeconds: 300,
      })],
    });
    expect(await t.query(api.focus.getState, {})).toMatchObject({
      state: {
        creditBalanceSeconds: 300,
        lastResetDate: today(),
        totalWorkSecondsToday: 400,
        totalScrollSecondsToday: 20,
        tasksCompletedToday: 2,
        externalDate: today(),
        externalWorkSecondsToday: 400,
        externalScrollSecondsToday: 20,
        externalTasksCompletedToday: 2,
      },
    });
  });

  it("rejects malformed negative or over-tracked leisure counters", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await expect(t.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable",
      buckets: [counterBucket("c36ddc7b-59b7-4972-ae1f-434c3514a8bd", { leisureSeconds: -1 })],
    })).rejects.toThrow(/Leisure seconds/);
    await expect(t.mutation(api.usage.recordUsageBatch, {
      deviceId: "ios-keychain-stable",
      buckets: [counterBucket("c36ddc7b-59b7-4972-ae1f-434c3514a8bd", { trackedSeconds: 30, leisureSeconds: 60 })],
    })).rejects.toThrow(/Leisure seconds/);
    expect(await t.run((ctx) => ctx.db.query("deviceUsage").collect())).toEqual([]);
  });
});
