import { mutation, query, type QueryCtx } from "./_generated/server";
import { v } from "convex/values";
import { heartbeatFields, upsertHeartbeat, loadDeviceProfiles } from "./devices";
import { ensureCatalogContribution } from "./usageCatalog";
import { internal } from "./_generated/api";
import { usageRetentionCutoffDate } from "./retention";

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
  leisureSeconds: v.optional(v.number()),
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
    heartbeat: v.optional(v.object(heartbeatFields)),
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
    const cutoff = usageRetentionCutoffDate();
    const latestAcceptedDate = new Date(Date.now() + 24 * 60 * 60_000).toISOString().slice(0, 10);
    let expired = 0;
    for (const raw of args.buckets) {
      // Validate before issuing any reads, including real calendar dates.
      const date = new Date(`${raw.date}T00:00:00Z`);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(raw.date) || !Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== raw.date) throw new Error("Invalid usage date");
      if (![raw.trackedSeconds, raw.updatedAt, raw.leisureSeconds ?? 0, raw.blockedSeconds ?? 0, raw.launchCount ?? 0].every(Number.isFinite)) throw new Error("Usage counters and timestamps must be finite");
      if (raw.leisureSeconds !== undefined && (raw.leisureSeconds < 0 || raw.leisureSeconds > raw.trackedSeconds)) {
        throw new Error("Leisure seconds must be between zero and tracked seconds");
      }
      if (raw.date > latestAcceptedDate) throw new Error("Usage date is in the future");
      // Retired detail cannot be replayed into monthly sums after its dedupe
      // counter has been removed. Acknowledge it separately so clients can
      // explain the retention window without retrying forever.
      if (raw.date < cutoff) { expired++; continue; }
      const targetKey = raw.targetKey.trim().toLowerCase().slice(0, 500);
      const key = `${raw.date}\u001f${raw.targetKind}\u001f${targetKey}`;
      const prior = normalized.get(key);
      normalized.set(key, prior
        ? {
            ...raw,
            targetKey,
            trackedSeconds: prior.trackedSeconds + raw.trackedSeconds,
            ...((prior.leisureSeconds !== undefined || raw.leisureSeconds !== undefined)
              ? { leisureSeconds: (prior.leisureSeconds ?? 0) + (raw.leisureSeconds ?? 0) } : {}),
            blockedSeconds: (prior.blockedSeconds ?? 0) + (raw.blockedSeconds ?? 0),
            launchCount: (prior.launchCount ?? 0) + (raw.launchCount ?? 0),
            updatedAt: Math.max(prior.updatedAt, raw.updatedAt),
          }
        : { ...raw, targetKey });
    }

    if (normalized.size) {
      const catalogState = await ctx.db.query("usageCatalogState")
        .withIndex("by_user", (q) => q.eq("userId", userId)).first();
      if (!catalogState) {
        const legacy = await ctx.db.query("deviceUsage").withIndex("by_user_date", (q) => q.eq("userId", userId)).first();
        await ctx.db.insert("usageCatalogState", { userId, ready: !legacy });
        if (legacy) await ctx.scheduler.runAfter(0, internal.retention.backfillUserCatalog, { userId });
      }
    }
    let written = 0;
    let leisureDebitSeconds = 0;
    const leisureByDate = new Map<string, number>();
    for (const bucket of normalized.values()) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(bucket.date)) throw new Error("Invalid usage date");
      const targetKey = bucket.targetKey.trim().toLowerCase().slice(0, 500);
      if (!targetKey) throw new Error("Usage target is required");
      if (![bucket.trackedSeconds, bucket.updatedAt, bucket.leisureSeconds ?? 0, bucket.blockedSeconds ?? 0, bucket.launchCount ?? 0].every(Number.isFinite)) {
        throw new Error("Usage counters and timestamps must be finite");
      }
      const trackedSeconds = Math.max(0, Math.floor(bucket.trackedSeconds));
      const incomingLeisureSeconds = bucket.leisureSeconds === undefined ? undefined : Math.max(0, Math.floor(bucket.leisureSeconds));
      const blockedSeconds = bucket.blockedSeconds === undefined
        ? undefined
        : Math.max(0, Math.floor(bucket.blockedSeconds));
      const launchCount = bucket.launchCount === undefined
        ? undefined
        : Math.max(0, Math.floor(bucket.launchCount));
      // Exact point lookups read only submitted counters. A sparse offline
      // batch must not scan months of unrelated history between its dates.
      const existing = await ctx.db.query("deviceUsage")
        .withIndex("by_user_usage_bucket", (q) => q.eq("userId", userId)
          .eq("deviceId", deviceId).eq("date", bucket.date)
          .eq("targetKind", bucket.targetKind).eq("targetKey", targetKey)).first();
      const stale = Boolean(existing && bucket.updatedAt < existing.updatedAt);
      const storedLeisureSeconds = existing?.leisureSeconds ?? 0;
      const nextLeisureSeconds = incomingLeisureSeconds === undefined
        ? existing?.leisureSeconds
        : Math.max(storedLeisureSeconds, incomingLeisureSeconds);
      const leisureIncrease = incomingLeisureSeconds === undefined ? 0 : Math.max(0, nextLeisureSeconds! - storedLeisureSeconds);
      const nextTrackedSeconds = stale && incomingLeisureSeconds === undefined
        ? (existing?.trackedSeconds ?? 0) : Math.max(existing?.trackedSeconds ?? 0, trackedSeconds);
      const nextBlockedSeconds = blockedSeconds === undefined || stale ? existing?.blockedSeconds
        : Math.max(existing?.blockedSeconds ?? 0, blockedSeconds);
      const nextLaunchCount = launchCount === undefined || stale ? existing?.launchCount
        : Math.max(existing?.launchCount ?? 0, launchCount);
      if (existing && nextTrackedSeconds === existing.trackedSeconds && nextLeisureSeconds === existing.leisureSeconds &&
          nextBlockedSeconds === existing.blockedSeconds && nextLaunchCount === existing.launchCount) continue;
      if (leisureIncrease > 0) {
        leisureDebitSeconds += leisureIncrease;
        leisureByDate.set(bucket.date, (leisureByDate.get(bucket.date) ?? 0) + leisureIncrease);
      }
      const value = {
        userId,
        deviceId,
        date: bucket.date,
        targetKind: bucket.targetKind,
        targetKey,
        targetLabel: bucket.targetLabel.trim().slice(0, 160) || targetKey,
        category: bucket.category?.trim().slice(0, 80),
        // Counters are cumulative for an installation/day. A cache reset or a
        // retry with a newer timestamp must never erase already stored usage.
        trackedSeconds: nextTrackedSeconds,
        leisureSeconds: nextLeisureSeconds,
        blockedSeconds: nextBlockedSeconds,
        launchCount: nextLaunchCount,
        updatedAt: Math.max(existing?.updatedAt ?? 0, bucket.updatedAt),
      };
      await ensureCatalogContribution(ctx, { ...value, catalogedTrackedSeconds: existing?.catalogedTrackedSeconds });
      const indexedValue = { ...value, catalogedTrackedSeconds: value.trackedSeconds };
      if (existing) await ctx.db.patch(existing._id, indexedValue);
      else await ctx.db.insert("deviceUsage", indexedValue);
      written++;
    }
    if (leisureDebitSeconds > 0) {
      const state = await ctx.db.query("focusState").withIndex("by_user", (q) => q.eq("userId", userId)).first();
      const sortedDates = [...leisureByDate.keys()].sort();
      const newestDate = sortedDates[sortedDates.length - 1];
      const currentDate = state?.lastResetDate ?? "";
      const applyDate = newestDate >= currentDate ? newestDate : currentDate;
      const scrollIncrease = applyDate === currentDate ? (leisureByDate.get(currentDate) ?? 0) : (leisureByDate.get(applyDate) ?? 0);
      const externalDate = state?.externalDate ?? "";
      const newerExternalDay = newestDate > externalDate;
      const sameExternalDay = newestDate === externalDate;
      const next = {
        userId,
        creditBalanceSeconds: Math.max(0, (state?.creditBalanceSeconds ?? 0) - leisureDebitSeconds),
        totalWorkSecondsToday: applyDate === currentDate ? state?.totalWorkSecondsToday ?? 0 : 0,
        totalScrollSecondsToday: (applyDate === currentDate ? state?.totalScrollSecondsToday ?? 0 : 0) + scrollIncrease,
        tasksCompletedToday: applyDate === currentDate ? state?.tasksCompletedToday ?? 0 : 0,
        lastResetDate: applyDate || newestDate,
        updatedAt: Math.max(Date.now(), (state?.updatedAt ?? 0) + 1),
        externalEarnedSeconds: state?.externalEarnedSeconds ?? 0,
        externalSpentSeconds: (state?.externalSpentSeconds ?? 0)
          + Math.min(Math.max(0, state?.creditBalanceSeconds ?? 0), leisureDebitSeconds),
        externalDate: newerExternalDay ? newestDate : externalDate || newestDate,
        externalWorkSecondsToday: newerExternalDay ? 0 : state?.externalWorkSecondsToday ?? 0,
        externalScrollSecondsToday: newerExternalDay
          ? (leisureByDate.get(newestDate) ?? 0)
          : sameExternalDay
            ? (state?.externalScrollSecondsToday ?? 0) + (leisureByDate.get(newestDate) ?? 0)
            : state?.externalScrollSecondsToday ?? 0,
        externalTasksCompletedToday: newerExternalDay ? 0 : state?.externalTasksCompletedToday ?? 0,
      };
      if (state) await ctx.db.patch(state._id, next);
      else await ctx.db.insert("focusState", next);
    }
    if (args.heartbeat) await upsertHeartbeat(ctx, userId, { ...args.heartbeat, deviceId });
    return { written, ...(expired ? { expired, oldestAcceptedDate: cutoff } : {}) };
  },
});

export const getUsageSummary = query({
  args: { fromDate: v.optional(v.string()), toDate: v.optional(v.string()) },
  handler: async (ctx, args) => loadUsageSummary(ctx, await requireUserId(ctx), args),
});

export async function loadUsageSummary(ctx: QueryCtx, userId: string,
  args: { fromDate?: string; toDate?: string }) {
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
          : await ctx.db.query("deviceUsage").withIndex("by_user_date", (q) => q.eq("userId", userId)).collect();

    const archiveCandidates = await ctx.db.query("usageArchives").withIndex("by_user_month", (q) => {
      const owner = q.eq("userId", userId);
      if (args.fromDate && args.toDate) return owner.gte("month", args.fromDate.slice(0, 7)).lte("month", args.toDate.slice(0, 7));
      if (args.fromDate) return owner.gte("month", args.fromDate.slice(0, 7));
      if (args.toDate) return owner.lte("month", args.toDate.slice(0, 7));
      return owner;
    }).collect();
    // A retained range can start later in a month that also has an archive.
    // Exclude archives with no overlapping days before checking granularity.
    const archives = archiveCandidates.filter((archive) => archive.days.some((day) =>
      (!args.fromDate || day.date >= args.fromDate) && (!args.toDate || day.date <= args.toDate)));
    // Per-target detail is intentionally retired after the retention window.
    // Never present a whole monthly sum as an exact partial-month report.
    for (const archive of archives) {
      if (archive.days.some((day) => (args.fromDate && day.date < args.fromDate) || (args.toDate && day.date > args.toDate))) {
        throw new Error("Older target detail is archived monthly. Request complete months or the retained 30-day window.");
      }
    }
    const archivedTargets = archives.flatMap((archive) => archive.targets.map((target) => ({ ...target, date: target.lastDate })));
    const allTargets = [...buckets, ...archivedTargets];

    const devices = await loadDeviceProfiles(ctx, userId);
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
    }
    for (const archive of archives) for (const entry of archive.days) {
      const day = byDay.get(entry.date) ?? { trackedSeconds: 0, blockedSeconds: 0 };
      day.trackedSeconds += entry.trackedSeconds;
      day.blockedSeconds += entry.blockedSeconds;
      byDay.set(entry.date, day);
    }

    for (const row of allTargets) {
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
      totalTrackedSeconds: allTargets.reduce((sum, row) => sum + row.trackedSeconds, 0),
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
}


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
  handler: async (ctx) => loadKnownTargets(ctx, await requireUserId(ctx)),
});

export async function loadKnownTargets(ctx: QueryCtx, userId: string) {
    const catalogState = await ctx.db.query("usageCatalogState")
      .withIndex("by_user", (q) => q.eq("userId", userId)).first();
    // During the one-time migration, serve complete legacy data. Once ready,
    // catalog reads stay proportional to unique targets, not historical days.
    const buckets = catalogState?.ready
      ? (await ctx.db.query("usageCatalog").withIndex("by_user_device_target", (q) => q.eq("userId", userId)).collect())
          .map((row) => ({ ...row, date: row.lastDate }))
      : [
          ...await ctx.db.query("deviceUsage").withIndex("by_user_date", (q) => q.eq("userId", userId)).collect(),
          ...(await ctx.db.query("usageArchives").withIndex("by_user_month", (q) => q.eq("userId", userId)).collect())
            .flatMap((archive) => archive.targets.map((target) => ({ ...target, date: target.lastDate }))),
        ];
    const devices = await loadDeviceProfiles(ctx, userId);
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
}
