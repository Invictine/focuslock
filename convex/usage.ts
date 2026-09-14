import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

const usageBucket = v.object({
  date: v.string(),
  targetKind: v.union(v.literal("app"), v.literal("website")),
  targetKey: v.string(),
  targetLabel: v.string(),
  category: v.optional(v.string()),
  trackedSeconds: v.number(),
  blockedSeconds: v.optional(v.number()),
  launchCount: v.optional(v.number()),
  updatedAt: v.number(),
});

/**
 * Upsert absolute counters for one device. Sending the same batch twice is a
 * no-op; it cannot double-count time after a retry or network reconnect.
 */
export const recordUsageBatch = mutation({
  args: {
    deviceId: v.string(),
    buckets: v.array(usageBucket),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const deviceId = args.deviceId.trim();
    if (!deviceId || deviceId.length > 160) throw new Error("Invalid device ID");
    if (args.buckets.length > 500) throw new Error("Usage batch exceeds 500 buckets");

    // A domain can be observed through multiple browsers. Collapse duplicate
    // logical buckets before the upsert so Chrome + Edge time is added rather
    // than whichever row happened to be last winning.
    const normalized = new Map<string, (typeof args.buckets)[number]>();
    const dates = new Set<string>();
    for (const raw of args.buckets) {
      dates.add(raw.date);
      const targetKey = raw.targetKey.trim().toLowerCase().slice(0, 500);
      const key = `${raw.date}\u001f${raw.targetKind}\u001f${targetKey}`;
      const prior = normalized.get(key);
      normalized.set(key, prior
        ? {
            ...raw,
            targetKey,
            trackedSeconds: prior.trackedSeconds + raw.trackedSeconds,
            blockedSeconds: (prior.blockedSeconds ?? 0) + (raw.blockedSeconds ?? 0),
            launchCount: (prior.launchCount ?? 0) + (raw.launchCount ?? 0),
            updatedAt: Math.max(prior.updatedAt, raw.updatedAt),
          }
        : { ...raw, targetKey });
    }

    // Read every existing row for this device across the batch's date span in
    // ONE indexed query instead of one .first() per bucket (N+1).
    let minDate: string | undefined;
    let maxDate: string | undefined;
    for (const date of dates) {
      if (minDate === undefined || date < minDate) minDate = date;
      if (maxDate === undefined || date > maxDate) maxDate = date;
    }
    const existingRows = minDate === undefined
      ? []
      : await ctx.db
          .query("deviceUsage")
          .withIndex("by_user_device_date", (q) =>
            q
              .eq("userId", userId)
              .eq("deviceId", deviceId)
              .gte("date", minDate!)
              .lte("date", maxDate!),
          )
          .collect();
    const existingByKey = new Map(
      existingRows.map((row) => [
        `${row.date}\u001f${row.targetKind}\u001f${row.targetKey}`,
        row,
      ]),
    );

    let written = 0;
    for (const bucket of normalized.values()) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(bucket.date)) throw new Error("Invalid usage date");
      const targetKey = bucket.targetKey.trim().toLowerCase().slice(0, 500);
      if (!targetKey) throw new Error("Usage target is required");
      const trackedSeconds = Math.max(0, Math.floor(bucket.trackedSeconds));
      const blockedSeconds = bucket.blockedSeconds === undefined
        ? undefined
        : Math.max(0, Math.floor(bucket.blockedSeconds));
      const launchCount = bucket.launchCount === undefined
        ? undefined
        : Math.max(0, Math.floor(bucket.launchCount));
      const existing = existingByKey.get(
        `${bucket.date}\u001f${bucket.targetKind}\u001f${targetKey}`,
      );
      if (existing && bucket.updatedAt < existing.updatedAt) continue;
      const value = {
        userId,
        deviceId,
        date: bucket.date,
        targetKind: bucket.targetKind,
        targetKey,
        targetLabel: bucket.targetLabel.trim().slice(0, 160) || targetKey,
        category: bucket.category?.trim().slice(0, 80),
        trackedSeconds,
        blockedSeconds,
        launchCount,
        updatedAt: bucket.updatedAt,
      };
      if (existing) await ctx.db.patch(existing._id, value);
      else await ctx.db.insert("deviceUsage", value);
      written++;
    }
    return { written };
  },
});

export const getUsageSummary = query({
  args: {
    fromDate: v.optional(v.string()),
    toDate: v.optional(v.string()),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const buckets = args.fromDate && args.toDate
      ? await ctx.db.query("deviceUsage").withIndex("by_user_date", (q) =>
          q.eq("userId", userId).gte("date", args.fromDate!).lte("date", args.toDate!),
        ).collect()
      : args.fromDate
        ? await ctx.db.query("deviceUsage").withIndex("by_user_date", (q) =>
            q.eq("userId", userId).gte("date", args.fromDate!),
          ).collect()
        : args.toDate
          ? await ctx.db.query("deviceUsage").withIndex("by_user_date", (q) =>
              q.eq("userId", userId).lte("date", args.toDate!),
            ).collect()
          : await ctx.db.query("deviceUsage").withIndex("by_user", (q) => q.eq("userId", userId)).collect();

    const devices = await ctx.db
      .query("devices")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const deviceNames = new Map(devices.map((device) => [device.deviceId, device.name]));
    const byDay = new Map<string, { trackedSeconds: number; blockedSeconds: number }>();
    const byDevice = new Map<string, { trackedSeconds: number; blockedSeconds: number }>();
    const byTarget = new Map<string, {
      targetKind: "app" | "website";
      targetKey: string;
      targetLabel: string;
      trackedSeconds: number;
      deviceIds: Set<string>;
    }>();

    for (const row of buckets) {
      const day = byDay.get(row.date) ?? { trackedSeconds: 0, blockedSeconds: 0 };
      day.trackedSeconds += row.trackedSeconds;
      day.blockedSeconds += row.blockedSeconds ?? 0;
      byDay.set(row.date, day);

      const device = byDevice.get(row.deviceId) ?? { trackedSeconds: 0, blockedSeconds: 0 };
      device.trackedSeconds += row.trackedSeconds;
      device.blockedSeconds += row.blockedSeconds ?? 0;
      byDevice.set(row.deviceId, device);

      const key = `${row.targetKind}:${row.targetKey}`;
      const target = byTarget.get(key) ?? {
        targetKind: row.targetKind,
        targetKey: row.targetKey,
        targetLabel: row.targetLabel,
        trackedSeconds: 0,
        deviceIds: new Set<string>(),
      };
      target.trackedSeconds += row.trackedSeconds;
      target.deviceIds.add(row.deviceId);
      byTarget.set(key, target);
    }

    return {
      totalTrackedSeconds: buckets.reduce((sum, row) => sum + row.trackedSeconds, 0),
      days: [...byDay.entries()]
        .map(([date, totals]) => ({ date, ...totals }))
        .sort((a, b) => b.date.localeCompare(a.date)),
      devices: [...byDevice.entries()]
        .map(([deviceId, totals]) => ({
          deviceId,
          deviceName: deviceNames.get(deviceId) ?? "Unknown device",
          ...totals,
        }))
        .sort((a, b) => b.trackedSeconds - a.trackedSeconds),
      targets: [...byTarget.values()]
        .map((target) => ({ ...target, deviceIds: [...target.deviceIds] }))
        .sort((a, b) => b.trackedSeconds - a.trackedSeconds),
    };
  },
});
