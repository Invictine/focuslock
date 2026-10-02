/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api, internal } from "../convex/_generated/api";
import { isUsageDateRetained, usageRetentionCutoffDate } from "../convex/retention";

const modules = import.meta.glob("../convex/**/*.ts");

describe("usage retention", () => {
  it("uses a UTC cutoff with a one-day buffer around 30 days of detail", () => {
    const now = Date.UTC(2026, 8, 30, 12);
    expect(usageRetentionCutoffDate(now)).toBe("2026-08-30");
    expect(isUsageDateRetained("2026-08-30", now)).toBe(true);
    expect(isUsageDateRetained("2026-08-29", now)).toBe(false);
  });

  it("archives only expired rows, keeps per-device targets and daily totals, and is retry safe", async () => {
    const t = convexTest(schema, modules);
    await t.run(async (ctx) => {
      await ctx.db.insert("deviceUsage", {
        userId: "alice", deviceId: "phone", date: "2026-09-08", targetKind: "app",
        targetKey: "com.example", targetLabel: "Example old", category: "Social",
        trackedSeconds: 120, blockedSeconds: 30, launchCount: 2, updatedAt: 10,
      });
      await ctx.db.insert("deviceUsage", {
        userId: "alice", deviceId: "desktop", date: "2026-09-08", targetKind: "app",
        targetKey: "com.example", targetLabel: "Example old", trackedSeconds: 90,
        blockedSeconds: 15, launchCount: 1, updatedAt: 10,
      });
      await ctx.db.insert("deviceUsage", {
        userId: "alice", deviceId: "phone", date: "2026-09-10", targetKind: "website",
        targetKey: "recent.example", targetLabel: "Recent", trackedSeconds: 45,
        blockedSeconds: 5, launchCount: 3, updatedAt: 10,
      });
      await ctx.db.insert("deviceUsage", {
        userId: "bob", deviceId: "phone", date: "2026-09-08", targetKind: "app",
        targetKey: "com.example", targetLabel: "Example old", trackedSeconds: 60,
        updatedAt: 10,
      });
    });

    const result = await t.mutation(internal.retention.archiveUsagePage, { cutoffDate: "2026-09-10" });
    expect(result).toMatchObject({ scanned: 3, archived: 3, isDone: true, cutoffDate: "2026-09-10" });
    const archives = await t.run((ctx) => ctx.db.query("usageArchives").collect());
    expect(archives).toHaveLength(2);
    const alice = archives.find((row) => row.userId === "alice")!;
    expect(alice.month).toBe("2026-09");
    expect(alice.targets).toHaveLength(2);
    expect(alice.targets).toEqual(expect.arrayContaining([
      expect.objectContaining({ deviceId: "phone", targetKey: "com.example", trackedSeconds: 120, blockedSeconds: 30, launchCount: 2 }),
      expect.objectContaining({ deviceId: "desktop", targetKey: "com.example", trackedSeconds: 90, blockedSeconds: 15, launchCount: 1 }),
    ]));
    expect(alice.days).toEqual([{ date: "2026-09-08", trackedSeconds: 210, blockedSeconds: 45 }]);
    const catalog = await t.run((ctx) => ctx.db.query("usageCatalog").collect());
    expect(catalog).toEqual(expect.arrayContaining([
      expect.objectContaining({ userId: "alice", deviceId: "phone", targetKey: "com.example", trackedSeconds: 120 }),
      expect.objectContaining({ userId: "alice", deviceId: "desktop", targetKey: "com.example", trackedSeconds: 90 }),
      expect.objectContaining({ userId: "bob", deviceId: "phone", targetKey: "com.example", trackedSeconds: 60 }),
    ]));

    const remaining = await t.run((ctx) => ctx.db.query("deviceUsage").collect());
    expect(remaining).toHaveLength(1);
    expect(remaining[0]).toMatchObject({ userId: "alice", date: "2026-09-10", targetKey: "recent.example" });

    const retry = await t.mutation(internal.retention.archiveUsagePage, { cutoffDate: "2026-09-10" });
    expect(retry.scanned).toBe(0);
    const afterRetry = await t.run((ctx) => ctx.db.query("usageArchives").collect());
    expect(afterRetry).toEqual(archives);
  });

  it("archives large backlogs in bounded pages and preserves sums across page boundaries", async () => {
    const t = convexTest(schema, modules);
    await t.run(async (ctx) => {
      for (let i = 0; i < 205; i++) {
        await ctx.db.insert("deviceUsage", {
          userId: "alice", deviceId: i % 2 === 0 ? "phone" : "desktop", date: "2026-08-01",
          targetKind: i % 3 === 0 ? "website" : "app", targetKey: `target-${i}`,
          targetLabel: `Target ${i}`, trackedSeconds: i + 1, blockedSeconds: i % 4,
          launchCount: i % 5, updatedAt: i,
        });
      }
    });

    let cursor: string | undefined;
    let scanned = 0;
    let pages = 0;
    let isDone = false;
    while (!isDone) {
      const page = await t.mutation(internal.retention.archiveUsagePage, {
        cutoffDate: "2026-09-01",
        ...(cursor ? { cursor } : {}),
      });
      scanned += page.scanned;
      pages++;
      isDone = page.isDone;
      cursor = page.continueCursor ?? undefined;
    }

    expect(pages).toBe(3);
    expect(scanned).toBe(205);
    expect(await t.run((ctx) => ctx.db.query("deviceUsage").collect())).toHaveLength(0);
    const [archive] = await t.run((ctx) => ctx.db.query("usageArchives").collect());
    expect(archive.targets).toHaveLength(205);
    expect(archive.targets.reduce((sum, target) => sum + target.trackedSeconds, 0)).toBe(205 * 206 / 2);
    expect(archive.targets.reduce((sum, target) => sum + target.blockedSeconds, 0)).toBe(
      Array.from({ length: 205 }, (_, i) => i % 4).reduce((a, b) => a + b, 0),
    );
    expect(archive.days).toEqual([{
      date: "2026-08-01",
      trackedSeconds: 205 * 206 / 2,
      blockedSeconds: Array.from({ length: 205 }, (_, i) => i % 4).reduce((a, b) => a + b, 0),
    }]);
  });

  it("backfills catalog markers once and reconciles a newer live counter", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await t.run(async (ctx) => {
      await ctx.db.insert("usageCatalogState", { userId: "alice", ready: false });
      await ctx.db.insert("deviceUsage", {
        userId: "alice", deviceId: "phone", date: "2026-09-05", targetKind: "app",
        targetKey: "com.study", targetLabel: "Study", trackedSeconds: 120, updatedAt: 10,
      });
      await ctx.db.insert("deviceUsage", {
        userId: "alice", deviceId: "phone", date: "2026-09-06", targetKind: "app",
        targetKey: "com.study", targetLabel: "Study", trackedSeconds: 80, updatedAt: 10,
      });
    });

    const first = await alice.mutation(internal.retention.backfillUserCatalog, { userId: "alice" });
    expect(first).toMatchObject({ scanned: 2, isDone: true });
    expect(await t.run((ctx) => ctx.db.query("usageCatalogState").collect()))
      .toEqual([expect.objectContaining({ userId: "alice", ready: true })]);
    expect(await t.run((ctx) => ctx.db.query("usageCatalog").collect()))
      .toEqual([expect.objectContaining({ trackedSeconds: 200 })]);

    await alice.mutation(api.usage.recordUsageBatch, {
      deviceId: "phone",
      buckets: [{ date: "2026-09-05", targetKind: "app", targetKey: "com.study",
        targetLabel: "Study", trackedSeconds: 200, updatedAt: 20 }],
    });
    expect(await t.run((ctx) => ctx.db.query("usageCatalog").collect()))
      .toEqual([expect.objectContaining({ trackedSeconds: 280 })]);

    const repeated = await alice.mutation(internal.retention.backfillUserCatalog, { userId: "alice" });
    expect(repeated).toMatchObject({ scanned: 2, isDone: true });
    expect(await t.run((ctx) => ctx.db.query("usageCatalog").collect()))
      .toEqual([expect.objectContaining({ trackedSeconds: 280 })]);
    const rows = await t.run((ctx) => ctx.db.query("deviceUsage").collect());
    expect(rows.find((row) => row.date === "2026-09-05"))
      .toMatchObject({ trackedSeconds: 200, catalogedTrackedSeconds: 200 });
  });
});
