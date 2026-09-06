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
