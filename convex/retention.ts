import { internal } from "./_generated/api";
import { internalMutation } from "./_generated/server";
import { ensureCatalogContribution } from "./usageCatalog";
import { v } from "convex/values";

const DAY_MS = 24 * 60 * 60 * 1000;
const RETENTION_DAYS = 31;
const PAGE_SIZE = 100;
const retentionApi = internal.retention;

/** Old client-local dates get a one-day buffer around the 30-day detail window. */
export function usageRetentionCutoffDate(nowMs = Date.now()): string {
  return new Date(nowMs - RETENTION_DAYS * DAY_MS).toISOString().slice(0, 10);
}

/** Keep rows on/after the cutoff; older rows are compacted into monthly archives. */
export function isUsageDateRetained(date: string, nowMs = Date.now()): boolean {
  return date >= usageRetentionCutoffDate(nowMs);
}

type TargetTotal = {
  deviceId: string;
  targetKind: "app" | "website";
  targetKey: string;
  targetLabel: string;
  category?: string;
  trackedSeconds: number;
  blockedSeconds: number;
  launchCount: number;
  lastDate: string;
};

type DayTotal = { date: string; trackedSeconds: number; blockedSeconds: number };

/**
 * Move at most 100 expired detail rows into compact user/month totals per call.
 * Archival and source deletion share one Convex transaction, so a retry can never
 * count the same source row twice. More pages are scheduled until the index range
 * is exhausted.
 */
export const archiveUsagePage = internalMutation({
  args: {
    cursor: v.optional(v.string()),
    cutoffDate: v.optional(v.string()),
  },
  handler: async (ctx, args) => {
    const cutoffDate = args.cutoffDate ?? usageRetentionCutoffDate();
    const page = await ctx.db.query("deviceUsage")
      .withIndex("by_date", (q) => q.lt("date", cutoffDate))
      .order("asc")
      .paginate({ cursor: args.cursor ?? null, numItems: PAGE_SIZE });

    const byUserMonth = new Map<string, {
      userId: string;
      month: string;
      targets: Map<string, TargetTotal>;
      days: Map<string, DayTotal>;
    }>();

    for (const row of page.page) {
      const month = row.date.slice(0, 7);
      const partitionKey = `${row.userId}\u001f${month}`;
      let partition = byUserMonth.get(partitionKey);
      if (!partition) {
        partition = { userId: row.userId, month, targets: new Map(), days: new Map() };
        byUserMonth.set(partitionKey, partition);
      }

      const targetKey = `${row.deviceId}\u001f${row.targetKind}\u001f${row.targetKey}`;
      const target = partition.targets.get(targetKey) ?? {
        deviceId: row.deviceId,
        targetKind: row.targetKind,
        targetKey: row.targetKey,
        targetLabel: row.targetLabel,
        category: row.category,
        trackedSeconds: 0,
        blockedSeconds: 0,
        launchCount: 0,
        lastDate: row.date,
      };
      target.trackedSeconds += row.trackedSeconds;
      target.blockedSeconds += row.blockedSeconds ?? 0;
      target.launchCount += row.launchCount ?? 0;
      if (row.date >= target.lastDate) {
        target.lastDate = row.date;
        target.targetLabel = row.targetLabel;
        target.category = row.category;
      }
      partition.targets.set(targetKey, target);

      const day = partition.days.get(row.date) ?? {
        date: row.date,
        trackedSeconds: 0,
        blockedSeconds: 0,
      };
      day.trackedSeconds += row.trackedSeconds;
      day.blockedSeconds += row.blockedSeconds ?? 0;
      partition.days.set(row.date, day);
    }

    for (const partition of byUserMonth.values()) {
      const existing = await ctx.db.query("usageArchives")
        .withIndex("by_user_month", (q) => q.eq("userId", partition.userId).eq("month", partition.month))
        .first();
      const targets = new Map<string, TargetTotal>();
      for (const target of existing?.targets ?? []) {
        targets.set(`${target.deviceId}\u001f${target.targetKind}\u001f${target.targetKey}`, { ...target });
      }
      for (const target of partition.targets.values()) {
        const key = `${target.deviceId}\u001f${target.targetKind}\u001f${target.targetKey}`;
        const prior = targets.get(key);
        targets.set(key, prior ? {
          ...prior,
          targetLabel: target.lastDate >= prior.lastDate ? target.targetLabel : prior.targetLabel,
          category: target.lastDate >= prior.lastDate ? target.category : prior.category,
          trackedSeconds: prior.trackedSeconds + target.trackedSeconds,
          blockedSeconds: prior.blockedSeconds + target.blockedSeconds,
          launchCount: prior.launchCount + target.launchCount,
          lastDate: target.lastDate > prior.lastDate ? target.lastDate : prior.lastDate,
        } : target);
      }

      const days = new Map<string, DayTotal>((existing?.days ?? []).map((day) => [day.date, { ...day }]));
      for (const day of partition.days.values()) {
        const prior = days.get(day.date);
        days.set(day.date, prior ? {
          date: day.date,
          trackedSeconds: prior.trackedSeconds + day.trackedSeconds,
          blockedSeconds: prior.blockedSeconds + day.blockedSeconds,
        } : day);
      }

      const value = {
        userId: partition.userId,
        month: partition.month,
        targets: [...targets.values()],
        days: [...days.values()].sort((a, b) => a.date.localeCompare(b.date)),
        updatedAt: Date.now(),
      };
      if (existing) await ctx.db.patch(existing._id, value);
      else await ctx.db.insert("usageArchives", value);
    }

    // Deletion is atomic with archive writes above. A failed transaction leaves all
    // original rows in place and has no partially accumulated archive totals.
    for (const row of page.page) {
      await ensureCatalogContribution(ctx, row);
      await ctx.db.delete(row._id);
    }

    if (!page.isDone && page.continueCursor) {
      await ctx.scheduler.runAfter(0, retentionApi.archiveUsagePage, {
        cursor: page.continueCursor,
        cutoffDate,
      });
    }

    return {
      scanned: page.page.length,
      archived: page.page.length,
      isDone: page.isDone,
      continueCursor: page.isDone ? null : page.continueCursor,
      cutoffDate,
    };
  },
});

/** One-time bounded catalog backfill for accounts whose usage predates the catalog. */
export const backfillUserCatalog = internalMutation({
  args: { userId: v.string(), cursor: v.optional(v.string()) },
  handler: async (ctx, args) => {
    const page = await ctx.db.query("deviceUsage")
      .withIndex("by_user_date", (q) => q.eq("userId", args.userId))
      .order("asc")
      .paginate({ cursor: args.cursor ?? null, numItems: PAGE_SIZE });
    for (const row of page.page) {
      await ensureCatalogContribution(ctx, row);
      await ctx.db.patch(row._id, { catalogedTrackedSeconds: row.trackedSeconds });
    }

    if (page.isDone) {
      const state = await ctx.db.query("usageCatalogState")
        .withIndex("by_user", (q) => q.eq("userId", args.userId))
        .first();
      if (state) await ctx.db.patch(state._id, { ready: true });
      else await ctx.db.insert("usageCatalogState", { userId: args.userId, ready: true });
    } else if (page.continueCursor) {
      await ctx.scheduler.runAfter(0, retentionApi.backfillUserCatalog, {
        userId: args.userId,
        cursor: page.continueCursor,
      });
    }

    return {
      userId: args.userId,
      scanned: page.page.length,
      isDone: page.isDone,
      continueCursor: page.isDone ? null : page.continueCursor,
    };
  },
});
