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
      blockedSeconds: number;
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
        blockedSeconds: 0,
        deviceIds: new Set<string>(),
      };
      target.trackedSeconds += row.trackedSeconds;
      target.blockedSeconds += row.blockedSeconds ?? 0;
      target.deviceIds.add(row.deviceId);
      byTarget.set(key, target);
    }

    // Merged buckets: fold every group's members into one logical target so a
    // phone app and the matching desktop site report a single cumulative time.
    const groups = await ctx.db
      .query("targetGroups")
      .withIndex("by_user", (q: any) => q.eq("userId", userId))
      .collect();
    const memberToGroup = new Map<string, string>();
    for (const group of groups) {
      for (const member of group.members) {
        memberToGroup.set(`${member.targetKind}:${member.targetKey}`, group.groupId);
      }
    }

    type GroupMemberOut = {
      targetKind: "app" | "website";
      targetKey: string;
      targetLabel: string;
      trackedSeconds: number;
      deviceIds: string[];
    };
    type GroupOut = {
      groupId: string;
      name: string;
      category?: string;
      members: GroupMemberOut[];
      trackedSeconds: number;
      blockedSeconds: number;
      deviceIds: string[];
      dailyLimitMinutes?: number;
      limitEnabled?: boolean;
    };
    type GroupedTargetOut = {
      targetKind: "app" | "website" | "group";
      targetKey: string;
      targetLabel: string;
      trackedSeconds: number;
      blockedSeconds: number;
      deviceIds: string[];
      memberKeys: string[];
      memberCount: number;
      groupId?: string;
      dailyLimitMinutes?: number;
      limitEnabled?: boolean;
      category?: string;
    };

    const groupSummaries: GroupOut[] = groups
      .map((group: any) => {
        const members: GroupMemberOut[] = [];
        const deviceIds = new Set<string>();
        let trackedSeconds = 0;
        let blockedSeconds = 0;
        for (const member of group.members) {
          const target = byTarget.get(`${member.targetKind}:${member.targetKey}`);
          members.push({
            targetKind: member.targetKind,
            targetKey: member.targetKey,
            targetLabel: member.targetLabel,
            trackedSeconds: target?.trackedSeconds ?? 0,
            deviceIds: target ? [...target.deviceIds] : [],
          });
          trackedSeconds += target?.trackedSeconds ?? 0;
          blockedSeconds += target?.blockedSeconds ?? 0;
          if (target) for (const id of target.deviceIds) deviceIds.add(id);
        }
        members.sort((a, b) => b.trackedSeconds - a.trackedSeconds);
        return {
          groupId: group.groupId,
          name: group.name,
          category: group.category,
          members,
          trackedSeconds,
          blockedSeconds,
          deviceIds: [...deviceIds],
          dailyLimitMinutes: group.dailyLimitMinutes,
          limitEnabled: group.limitEnabled,
        };
      })
      .sort((a: GroupOut, b: GroupOut) => b.trackedSeconds - a.trackedSeconds);

    const groupedTargets: GroupedTargetOut[] = [
      ...groupSummaries.map((group) => ({
        targetKind: "group" as const,
        targetKey: `group:${group.groupId}`,
        targetLabel: group.name,
        trackedSeconds: group.trackedSeconds,
        blockedSeconds: group.blockedSeconds,
        deviceIds: group.deviceIds,
        memberKeys: group.members.map((member) => `${member.targetKind}:${member.targetKey}`),
        memberCount: group.members.length,
        groupId: group.groupId,
        dailyLimitMinutes: group.dailyLimitMinutes,
        limitEnabled: group.limitEnabled,
        category: group.category,
      })),
      ...[...byTarget.entries()]
        .filter(([key]) => !memberToGroup.has(key))
        .map(([key, target]) => ({
          targetKind: target.targetKind,
          targetKey: target.targetKey,
          targetLabel: target.targetLabel,
          trackedSeconds: target.trackedSeconds,
          blockedSeconds: target.blockedSeconds,
          deviceIds: [...target.deviceIds],
          memberKeys: [key],
          memberCount: 1,
        })),
    ].sort((a, b) => b.trackedSeconds - a.trackedSeconds);

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
      // Merge-aware view: groups first (with member breakdown), then every
      // target that does not belong to a group. Clients render this directly.
      groupedTargets,
      groups: groupSummaries,
    };
  },
});

/**
 * Every target this account has ever been observed using, from ANY device,
 * regardless of date range. This is the enumeration the merge UI needs: a
 * Windows app, an Android package and a domain are all just members of one
 * user-declared bucket, and nothing about the key's origin should stop a user
 * from saying "these are the same thing".
 *
 * `trackedSeconds` is all-time (server rows are not pruned; each device prunes
 * only its own local store), and `groupId`/`groupName` let a picker show that
 * a target is already part of another bucket.
 */
export const listKnownTargets = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);

    const buckets = await ctx.db
      .query("deviceUsage")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const devices = await ctx.db
      .query("devices")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const groups = await ctx.db
      .query("targetGroups")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();

    const deviceMeta = new Map(
      devices.map((device) => [
        device.deviceId,
        { deviceId: device.deviceId, name: device.name, platform: device.platform },
      ]),
    );
    const memberToGroup = new Map<string, { groupId: string; name: string }>();
    for (const group of groups) {
      for (const member of group.members) {
        memberToGroup.set(`${member.targetKind}:${member.targetKey}`, {
          groupId: group.groupId,
          name: group.name,
        });
      }
    }

    const byTarget = new Map<string, {
      targetKind: "app" | "website";
      targetKey: string;
      targetLabel: string;
      category?: string;
      trackedSeconds: number;
      lastDate: string;
      deviceIds: Set<string>;
    }>();

    for (const row of buckets) {
      const key = `${row.targetKind}:${row.targetKey}`;
      const target = byTarget.get(key) ?? {
        targetKind: row.targetKind,
        targetKey: row.targetKey,
        targetLabel: row.targetLabel,
        category: row.category,
        trackedSeconds: 0,
        lastDate: row.date,
        deviceIds: new Set<string>(),
      };
      target.trackedSeconds += row.trackedSeconds;
      if (row.date > target.lastDate) target.lastDate = row.date;
      target.deviceIds.add(row.deviceId);
      byTarget.set(key, target);
    }

    return [...byTarget.entries()]
      .map(([key, target]) => ({
        targetKind: target.targetKind,
        targetKey: target.targetKey,
        targetLabel: target.targetLabel,
        category: target.category,
        trackedSeconds: target.trackedSeconds,
        lastDate: target.lastDate,
        deviceIds: [...target.deviceIds],
        devices: [...target.deviceIds].map(
          (deviceId) =>
            deviceMeta.get(deviceId) ?? { deviceId, name: "Unknown device", platform: "unknown" },
        ),
        groupId: memberToGroup.get(key)?.groupId,
        groupName: memberToGroup.get(key)?.name,
      }))
      .sort((a, b) => b.trackedSeconds - a.trackedSeconds);
  },
});
