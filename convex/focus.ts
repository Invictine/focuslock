import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

/** Full snapshot for the signed-in user — powers auto-sync on both clients. */
export const getSnapshot = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const state = await ctx.db
      .query("focusState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const apps = await ctx.db
      .query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const sites = await ctx.db
      .query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const records = await ctx.db
      .query("workRecords")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .order("desc")
      .take(200);
    const nuke = await ctx.db
      .query("nukeState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    return { state, apps, sites, records, nuke };
  },
});

/** Upsert credit/balance state (last-writer-wins via updatedAt). */
export const saveState = mutation({
  args: {
    creditBalanceSeconds: v.number(),
    totalWorkSecondsToday: v.number(),
    totalScrollSecondsToday: v.number(),
    tasksCompletedToday: v.number(),
    lastResetDate: v.string(),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("focusState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    if (existing) {
      // Ignore stale writes from a device with an old clock/cache.
      if (args.updatedAt < existing.updatedAt) return existing._id;
      await ctx.db.patch(existing._id, { ...args, userId });
      return existing._id;
    }
    return await ctx.db.insert("focusState", { ...args, userId });
  },
});

/** Replace the full blocked-app list (small list, simple LWW). */
export const saveBlockedApps = mutation({
  args: {
    apps: v.array(
      v.object({
        packageName: v.string(),
        appName: v.string(),
        isBlocked: v.boolean(),
        category: v.string(),
        specificShortsOnly: v.optional(v.boolean()),
      }),
    ),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const app of args.apps) {
      await ctx.db.insert("blockedApps", { ...app, userId, updatedAt: args.updatedAt });
    }
  },
});

/** Replace the full blocked-website list. */
export const saveBlockedWebsites = mutation({
  args: {
    sites: v.array(
      v.object({
        domain: v.string(),
        displayName: v.string(),
        isBlocked: v.boolean(),
        category: v.string(),
        isCustom: v.optional(v.boolean()),
      }),
    ),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const site of args.sites) {
      await ctx.db.insert("blockedWebsites", { ...site, userId, updatedAt: args.updatedAt });
    }
  },
});

/** Idempotent work-record insert (dedupe on recordId per user). */
export const addWorkRecord = mutation({
  args: {
    recordId: v.string(),
    title: v.string(),
    durationMinutes: v.number(),
    timestamp: v.number(),
    source: v.string(),
    earnedMinutesCredited: v.number(),
    projectName: v.optional(v.string()),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("workRecords")
      .withIndex("by_user_record", (q) => q.eq("userId", userId).eq("recordId", args.recordId))
      .first();
    if (existing) return existing._id;
    return await ctx.db.insert("workRecords", { ...args, userId });
  },
});

/** StayFree-parity full dashboard snapshot (old getSnapshot kept for Android compat). */
export const getDashboard = query({
  args: {
    fromDate: v.optional(v.string()),
    toDate: v.optional(v.string()),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const state = await ctx.db
      .query("focusState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const apps = await ctx.db
      .query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const sites = await ctx.db
      .query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const records = await ctx.db
      .query("workRecords")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .order("desc")
      .take(200);
    const limits = await ctx.db
      .query("appLimits")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const schedules = await ctx.db
      .query("blockSchedules")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const sessions = await ctx.db
      .query("focusSessions")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .order("desc")
      .take(200);
    let usageQuery = ctx.db.query("dailyUsage").withIndex("by_user", (q) => q.eq("userId", userId));
    let usage = await usageQuery.collect();
    if (args.fromDate) usage = usage.filter((u) => u.date >= args.fromDate!);
    if (args.toDate) usage = usage.filter((u) => u.date <= args.toDate!);
    usage.sort((a, b) => (a.date < b.date ? 1 : -1));
    const prefs = await ctx.db
      .query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    return { state, apps, sites, records, limits, schedules, sessions, usage: usage.slice(0, 90), prefs };
  },
});

/** Replace the full per-target limits list (small list, simple LWW). */
export const saveAppLimits = mutation({
  args: {
    limits: v.array(
      v.object({
        targetKind: v.string(),
        targetKey: v.string(),
        label: v.optional(v.string()),
        dailyLimitMinutes: v.optional(v.number()),
        sessionLimitMinutes: v.optional(v.number()),
        isBlockedNow: v.optional(v.boolean()),
      }),
    ),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("appLimits")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const l of args.limits) {
      await ctx.db.insert("appLimits", { ...l, userId, updatedAt: args.updatedAt });
    }
  },
});

/** Replace the full schedule list. */
export const saveSchedules = mutation({
  args: {
    schedules: v.array(
      v.object({
        scheduleId: v.string(),
        label: v.string(),
        targetKind: v.string(),
        targetKey: v.string(),
        days: v.array(v.number()),
        startMinute: v.number(),
        endMinute: v.number(),
        isEnabled: v.boolean(),
      }),
    ),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("blockSchedules")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const s of args.schedules) {
      await ctx.db.insert("blockSchedules", { ...s, userId, updatedAt: args.updatedAt });
    }
  },
});

/** Idempotent focus-session insert. */
export const logFocusSession = mutation({
  args: {
    sessionId: v.string(),
    title: v.string(),
    durationMinutes: v.number(),
    timestamp: v.number(),
    source: v.string(),
    earnedMinutesCredited: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("focusSessions")
      .withIndex("by_user_session", (q) => q.eq("userId", userId).eq("sessionId", args.sessionId))
      .first();
    if (existing) return existing._id;
    return await ctx.db.insert("focusSessions", { ...args, userId });
  },
});

/** Upsert one day of usage rollup (LWW). */
export const saveDailyUsage = mutation({
  args: {
    date: v.string(),
    totalScreenMinutes: v.number(),
    appCount: v.number(),
    unlockCount: v.optional(v.number()),
    topApps: v.array(
      v.object({ packageName: v.string(), appName: v.string(), minutes: v.number() }),
    ),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("dailyUsage")
      .withIndex("by_user_date", (q) => q.eq("userId", userId).eq("date", args.date))
      .first();
    if (existing) {
      if (args.updatedAt < existing.updatedAt) return existing._id;
      await ctx.db.patch(existing._id, { ...args, userId });
      return existing._id;
    }
    return await ctx.db.insert("dailyUsage", { ...args, userId });
  },
});

/** Upsert global prefs. */
export const savePrefs = mutation({
  args: {
    strictMode: v.boolean(),
    weeklyReport: v.boolean(),
    dailyReminderMinutes: v.optional(v.number()),
    globalDailyCapMinutes: v.optional(v.number()),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    if (existing) {
      if (args.updatedAt < existing.updatedAt) return existing._id;
      await ctx.db.patch(existing._id, { ...args, userId });
      return existing._id;
    }
    return await ctx.db.insert("userPrefs", { ...args, userId });
  },
});
