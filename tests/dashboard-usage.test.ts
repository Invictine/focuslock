/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");

function utcDate(daysAgo: number, now: number) {
  return new Date(now - daysAgo * 24 * 60 * 60_000).toISOString().slice(0, 10);
}

describe("dashboard usage summary", () => {
  it("returns all-device target totals plus date-scoped per-device slices, safely across retries", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const now = Date.now();
    const yesterday = utcDate(1, now);
    const twoDaysAgo = utcDate(2, now);
    const threeDaysAgo = utcDate(3, now);
    const freshCounters = {
      targetKind: "app" as const,
      targetKey: "com.example.study",
      targetLabel: "Study app",
      trackedSeconds: 110,
      updatedAt: now,
    };

    await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "phone-a",
      buckets: [{ ...freshCounters, date: yesterday }],
    });
    await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "phone-b",
      buckets: [{ ...freshCounters, trackedSeconds: 70, date: yesterday }],
    });
    await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "phone-a",
      buckets: [{ ...freshCounters, trackedSeconds: 40, date: twoDaysAgo }],
    });

    // Retrying absolute cumulative counters leaves each device's stored row unchanged.
    await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "phone-a",
      buckets: [{ ...freshCounters, date: yesterday }],
    });
    await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "phone-b",
      buckets: [{ ...freshCounters, trackedSeconds: 70, date: yesterday }],
    });

    const summary = await alice.query(api.usage.getUsageSummary, {
      fromDate: yesterday,
      toDate: yesterday,
    });

    expect(summary.totalTrackedSeconds).toBe(180);
    expect(summary.targets).toEqual([
      expect.objectContaining({
        targetKind: "app",
        targetKey: "com.example.study",
        trackedSeconds: 180,
        deviceIds: expect.arrayContaining(["phone-a", "phone-b"]),
      }),
    ]);
    expect(summary.deviceTargets).toEqual(expect.arrayContaining([
      { deviceId: "phone-a", targetKind: "app", targetKey: "com.example.study", trackedSeconds: 110 },
      { deviceId: "phone-b", targetKind: "app", targetKey: "com.example.study", trackedSeconds: 70 },
    ]));
    expect(summary.deviceTargets).toHaveLength(2);
    expect(summary.days).toEqual([{ date: yesterday, trackedSeconds: 180, blockedSeconds: 0 }]);

    const broader = await alice.query(api.usage.getUsageSummary, {
      fromDate: twoDaysAgo,
      toDate: twoDaysAgo,
    });
    expect(broader.totalTrackedSeconds).toBe(40);
    expect(broader.deviceTargets).toEqual([
      { deviceId: "phone-a", targetKind: "app", targetKey: "com.example.study", trackedSeconds: 40 },
    ]);

    const emptyRange = await alice.query(api.usage.getUsageSummary, {
      fromDate: threeDaysAgo,
      toDate: threeDaysAgo,
    });
    expect(emptyRange.deviceTargets).toEqual([]);
  });
});
