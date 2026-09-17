import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

type SyncCollection = "blockedApps" | "blockedWebsites" | "appLimits" | "blockSchedules";

async function getCollectionVersion(
  ctx: any,
  userId: string,
  collection: SyncCollection,
) {
  return await ctx.db
    .query("syncVersions")
    .withIndex("by_user_collection", (q: any) => q.eq("userId", userId).eq("collection", collection))
    .first();
}

async function setCollectionVersion(
  ctx: any,
  userId: string,
  collection: SyncCollection,
  updatedAt: number,
) {
  const existing = await getCollectionVersion(ctx, userId, collection);
  if (existing) await ctx.db.patch(existing._id, { updatedAt });
  else await ctx.db.insert("syncVersions", { userId, collection, updatedAt });
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
    const prefs = await ctx.db
      .query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const appsVersion = await getCollectionVersion(ctx, userId, "blockedApps");
    const sitesVersion = await getCollectionVersion(ctx, userId, "blockedWebsites");
    return {
      state,
      apps,
      sites,
      records,
      nuke,
      prefs,
      stateUpdatedAt: state?.updatedAt ?? 0,
      appsUpdatedAt: appsVersion?.updatedAt ?? Math.max(0, ...apps.map((app) => app.updatedAt)),
      sitesUpdatedAt: sitesVersion?.updatedAt ?? Math.max(0, ...sites.map((site) => site.updatedAt)),
    };
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
      if (args.updatedAt < existing.updatedAt) return { applied: false, updatedAt: existing.updatedAt };
      await ctx.db.patch(existing._id, { ...args, userId });
      return { applied: true, updatedAt: args.updatedAt };
    }
    await ctx.db.insert("focusState", { ...args, userId });
    return { applied: true, updatedAt: args.updatedAt };
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
    const version = await getCollectionVersion(ctx, userId, "blockedApps");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const app of args.apps) {
      await ctx.db.insert("blockedApps", { ...app, userId, updatedAt: args.updatedAt });
    }
    await setCollectionVersion(ctx, userId, "blockedApps", args.updatedAt);
    return { applied: true, updatedAt: args.updatedAt };
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
    const version = await getCollectionVersion(ctx, userId, "blockedWebsites");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const site of args.sites) {
      await ctx.db.insert("blockedWebsites", { ...site, userId, updatedAt: args.updatedAt });
    }
    await setCollectionVersion(ctx, userId, "blockedWebsites", args.updatedAt);
    return { applied: true, updatedAt: args.updatedAt };
  },
});

/** Update one site without replacing changes made on another device. */
export const setBlockedWebsite = mutation({
  args: {
    domain: v.string(),
    displayName: v.string(),
    isBlocked: v.boolean(),
    category: v.string(),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const domain = args.domain.trim().toLowerCase().replace(/^www\./, "");
    if (!domain) throw new Error("A domain is required");
    const version = await getCollectionVersion(ctx, userId, "blockedWebsites");
    const updatedAt = Math.max(Date.now(), args.updatedAt, (version?.updatedAt ?? 0) + 1);
    const existing = await ctx.db
      .query("blockedWebsites")
      .withIndex("by_user_domain", (q) => q.eq("userId", userId).eq("domain", domain))
      .first();
    const site = {
      domain,
      displayName: args.displayName.trim() || domain,
      isBlocked: args.isBlocked,
      category: args.category.trim() || "Web",
      isCustom: true,
      updatedAt,
    };
    if (existing) await ctx.db.patch(existing._id, site);
    else await ctx.db.insert("blockedWebsites", { ...site, userId });
    await setCollectionVersion(ctx, userId, "blockedWebsites", updatedAt);
    return { applied: true, updatedAt };
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
    // Fetch only the requested window straight from the (userId, date) index,
    // newest first, capped at the 90 rows the response shape carries — instead
    // of collecting every dailyUsage row and filtering/sorting in JS.
    const usage = args.fromDate && args.toDate
      ? await ctx.db.query("dailyUsage").withIndex("by_user_date", (q) =>
          q.eq("userId", userId).gte("date", args.fromDate!).lte("date", args.toDate!),
        ).order("desc").take(90)
      : args.fromDate
        ? await ctx.db.query("dailyUsage").withIndex("by_user_date", (q) =>
            q.eq("userId", userId).gte("date", args.fromDate!),
          ).order("desc").take(90)
        : args.toDate
          ? await ctx.db.query("dailyUsage").withIndex("by_user_date", (q) =>
              q.eq("userId", userId).lte("date", args.toDate!),
            ).order("desc").take(90)
          : await ctx.db.query("dailyUsage").withIndex("by_user_date", (q) =>
              q.eq("userId", userId),
            ).order("desc").take(90);
    const prefs = await ctx.db
      .query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const devices = await ctx.db
      .query("devices")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    return { state, apps, sites, records, limits, schedules, sessions, usage, prefs, devices };
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
    const version = await getCollectionVersion(ctx, userId, "appLimits");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("appLimits")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const l of args.limits) {
      await ctx.db.insert("appLimits", { ...l, userId, updatedAt: args.updatedAt });
    }
    await setCollectionVersion(ctx, userId, "appLimits", args.updatedAt);
    return { applied: true, updatedAt: args.updatedAt };
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
    const version = await getCollectionVersion(ctx, userId, "blockSchedules");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("blockSchedules")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    for (const doc of existing) await ctx.db.delete(doc._id);
    for (const s of args.schedules) {
      await ctx.db.insert("blockSchedules", { ...s, userId, updatedAt: args.updatedAt });
    }
    await setCollectionVersion(ctx, userId, "blockSchedules", args.updatedAt);
    return { applied: true, updatedAt: args.updatedAt };
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

/** Upsert global prefs. Every field is optional so a client can update only what it owns. */
export const savePrefs = mutation({
  args: {
    strictMode: v.optional(v.boolean()),
    weeklyReport: v.optional(v.boolean()),
    dailyReminderMinutes: v.optional(v.number()),
    globalDailyCapMinutes: v.optional(v.number()),
    workRatio: v.optional(v.number()),
    workRatioUpdatedAt: v.optional(v.number()),
    taskBonusMinutes: v.optional(v.number()),
    taskBonusMinutesUpdatedAt: v.optional(v.number()),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const patch: Record<string, unknown> = { updatedAt: args.updatedAt };
    if (args.strictMode !== undefined) patch.strictMode = args.strictMode;
    if (args.weeklyReport !== undefined) patch.weeklyReport = args.weeklyReport;
    if (args.dailyReminderMinutes !== undefined) patch.dailyReminderMinutes = args.dailyReminderMinutes;
    if (args.globalDailyCapMinutes !== undefined) patch.globalDailyCapMinutes = args.globalDailyCapMinutes;
    if (args.workRatio !== undefined) patch.workRatio = args.workRatio;
    if (args.workRatioUpdatedAt !== undefined) patch.workRatioUpdatedAt = args.workRatioUpdatedAt;
    if (args.taskBonusMinutes !== undefined) patch.taskBonusMinutes = args.taskBonusMinutes;
    if (args.taskBonusMinutesUpdatedAt !== undefined) patch.taskBonusMinutesUpdatedAt = args.taskBonusMinutesUpdatedAt;
    if (existing) {
      if (args.updatedAt < existing.updatedAt) return existing._id;
      await ctx.db.patch(existing._id, patch as never);
      return existing._id;
    }
    return await ctx.db.insert("userPrefs", {
      userId,
      strictMode: args.strictMode ?? false,
      weeklyReport: args.weeklyReport ?? true,
      ...patch,
    } as never);
  },
});
