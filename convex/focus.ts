import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

type SyncCollection = "blockedApps" | "blockedWebsites" | "appLimits" | "blockSchedules";

/** The verified account key, shared by OAuth and Clerk SDK clients. */
export const getAccount = query({
  args: {},
  handler: async (ctx) => ({ userId: await requireUserId(ctx) }),
});

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
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
    const prefs = await ctx.db.query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId)).first();
    if (prefs?.strictMode && (prefs.strictEndsAt ?? 0) > Date.now() &&
        existing.some((app) => app.isBlocked &&
          !args.apps.some((incoming) => incoming.packageName === app.packageName && incoming.isBlocked))) {
      throw new Error("Blocked apps cannot be removed during Strict Mode");
    }
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
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
    const prefs = await ctx.db.query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId)).first();
    if (prefs?.strictMode && (prefs.strictEndsAt ?? 0) > Date.now() &&
        existing.some((site) => site.isBlocked &&
          !args.sites.some((incoming) => incoming.domain === site.domain && incoming.isBlocked))) {
      throw new Error("Blocked websites cannot be removed during Strict Mode");
    }
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
    if (existing && args.updatedAt < existing.updatedAt) {
      return { applied: false, updatedAt: existing.updatedAt };
    }
    if (existing?.isBlocked && !args.isBlocked) {
      const prefs = await ctx.db.query("userPrefs")
        .withIndex("by_user", (q) => q.eq("userId", userId)).first();
      if (prefs?.strictMode && (prefs.strictEndsAt ?? 0) > Date.now()) {
        throw new Error("Blocked websites cannot be removed during Strict Mode");
      }
    }
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
/** Log desktop work and bank its credit in one retry-safe transaction. */
export const recordWork = mutation({
  args: {
    recordId: v.string(), title: v.string(), durationMinutes: v.number(),
    timestamp: v.number(), source: v.string(), earnedMinutesCredited: v.number(),
    date: v.string(), tasksCompleted: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (![args.durationMinutes, args.earnedMinutesCredited, args.tasksCompleted, args.timestamp].every(Number.isFinite) ||
        args.durationMinutes < 0 || args.earnedMinutesCredited < 0 || args.tasksCompleted < 0 ||
        !/^\d{4}-\d{2}-\d{2}$/.test(args.date)) throw new Error("Invalid work record");
    const existing = await ctx.db.query("workRecords").withIndex("by_user_record", (q) =>
      q.eq("userId", userId).eq("recordId", args.recordId)).first();
    if (existing) return { applied: true, duplicate: true };
    const { date, tasksCompleted, ...record } = args;
    await ctx.db.insert("workRecords", { ...record, userId });
    const { recordId, ...session } = record;
    const existingSession = await ctx.db.query("focusSessions").withIndex("by_user_session", (q) =>
      q.eq("userId", userId).eq("sessionId", recordId)).first();
    if (!existingSession) await ctx.db.insert("focusSessions", { ...session, sessionId: recordId, userId });
    const state = await ctx.db.query("focusState").withIndex("by_user", (q) => q.eq("userId", userId)).first();
    const sameDay = state?.lastResetDate === date;
    const olderDay = !!state && state.lastResetDate > date;
    const next = {
      userId,
      creditBalanceSeconds: (state?.creditBalanceSeconds ?? 0) + args.earnedMinutesCredited * 60,
      totalWorkSecondsToday: olderDay ? state.totalWorkSecondsToday :
        (sameDay ? state.totalWorkSecondsToday : 0) + args.durationMinutes * 60,
      totalScrollSecondsToday: sameDay || olderDay ? state!.totalScrollSecondsToday : 0,
      tasksCompletedToday: olderDay ? state.tasksCompletedToday :
        (sameDay ? state.tasksCompletedToday : 0) + tasksCompleted,
      lastResetDate: olderDay ? state.lastResetDate : date,
      updatedAt: Math.max(Date.now(), (state?.updatedAt ?? 0) + 1),
    };
    if (state) await ctx.db.patch(state._id, next);
    else await ctx.db.insert("focusState", next);
    return { applied: true, duplicate: false };
  },
});

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
    const versions = await Promise.all(
      (["blockedApps", "blockedWebsites", "appLimits", "blockSchedules"] as const)
        .map((collection) => getCollectionVersion(ctx, userId, collection)),
    );
    return {
      state, apps, sites, records, limits, schedules, sessions, usage, prefs, devices,
      stateUpdatedAt: state?.updatedAt ?? 0,
      appsUpdatedAt: versions[0]?.updatedAt ?? Math.max(0, ...apps.map((row) => row.updatedAt)),
      sitesUpdatedAt: versions[1]?.updatedAt ?? Math.max(0, ...sites.map((row) => row.updatedAt)),
      limitsUpdatedAt: versions[2]?.updatedAt ?? Math.max(0, ...limits.map((row) => row.updatedAt)),
      schedulesUpdatedAt: versions[3]?.updatedAt ?? Math.max(0, ...schedules.map((row) => row.updatedAt)),
    };
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
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
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
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
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
    strictEndsAt: v.optional(v.number()),
    strictNukeAfterFive: v.optional(v.boolean()),
    strictPreset: v.optional(v.string()),
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
    const patch: Record<string, unknown> = { updatedAt: Math.max(args.updatedAt, existing?.updatedAt ?? 0) };
    if (args.strictMode !== undefined) patch.strictMode = args.strictMode;
    if (args.strictEndsAt !== undefined) patch.strictEndsAt = Math.max(0, args.strictEndsAt);
    if (args.strictNukeAfterFive !== undefined) patch.strictNukeAfterFive = args.strictNukeAfterFive;
    if (args.strictPreset !== undefined) patch.strictPreset = args.strictPreset.slice(0, 40);
    if (args.weeklyReport !== undefined) patch.weeklyReport = args.weeklyReport;
    if (args.dailyReminderMinutes !== undefined) patch.dailyReminderMinutes = args.dailyReminderMinutes;
    if (args.globalDailyCapMinutes !== undefined) patch.globalDailyCapMinutes = args.globalDailyCapMinutes;
    if (args.workRatio !== undefined) patch.workRatio = args.workRatio;
    if (args.workRatioUpdatedAt !== undefined) patch.workRatioUpdatedAt = args.workRatioUpdatedAt;
    if (args.taskBonusMinutes !== undefined) patch.taskBonusMinutes = args.taskBonusMinutes;
    if (args.taskBonusMinutesUpdatedAt !== undefined) patch.taskBonusMinutesUpdatedAt = args.taskBonusMinutesUpdatedAt;
    // Approval markers are server-owned. An offline device must not resurrect
    // the exact commitment that a guardian already approved.
    if (args.strictMode === true && existing?.strictApprovedEndsAt &&
        args.strictEndsAt === existing.strictApprovedEndsAt && !existing.strictMode) {
      throw new Error("This commitment was approved for release. Sync before starting another commitment.");
    }
    if ((args.strictMode === true || (existing?.strictMode && args.strictEndsAt !== undefined)) &&
        args.updatedAt >= (existing?.updatedAt ?? 0)) {
      const end = args.strictEndsAt ?? existing?.strictEndsAt;
      const now = Date.now();
      // Existing commitments may be re-synced after their end time; clients
      // already treat these as expired. New commitments require a future end.
      const unchanged = existing?.strictMode && end === existing.strictEndsAt;
      if (!unchanged && (end === undefined || !Number.isFinite(end) || end <= now || end > now + 30 * 86_400_000)) {
        throw new Error("Choose a Strict Mode duration between now and 30 days.");
      }
      if (!unchanged) {
        patch.strictSessionId = `${now}:${end}:${args.updatedAt}`;
        patch.strictApprovedEndsAt = undefined;
        patch.strictApprovedAt = undefined;
        patch.strictApprovedSessionId = undefined;
      } else if (!existing?.strictSessionId) {
        patch.strictSessionId = `${now}:${end}:${args.updatedAt}`;
      }
    }
    if (existing) {
      // Ratio and bonus edits have independent clocks. A later Strict Mode
      // update must not silently discard an offline edit to either setting.
      if (args.updatedAt < existing.updatedAt) {
        for (const field of ["strictMode", "strictEndsAt", "strictNukeAfterFive", "strictPreset",
          "weeklyReport", "dailyReminderMinutes", "globalDailyCapMinutes"]) delete patch[field];
      }
      for (const field of ["workRatio", "taskBonusMinutes"] as const) {
        const clock = `${field}UpdatedAt` as const;
        if (args[field] === undefined || (args[clock] ?? args.updatedAt) < (existing[clock] ?? 0)) {
          delete patch[field];
          delete patch[clock];
        } else {
          patch[clock] = args[clock] ?? args.updatedAt;
        }
      }
      // A commitment can be extended, but another client cannot end or shorten it.
      if (existing.strictMode && (existing.strictEndsAt ?? 0) > Date.now()) {
        if (patch.strictMode === false ||
            (typeof patch.strictEndsAt === "number" && patch.strictEndsAt < existing.strictEndsAt!)) {
          throw new Error("Strict Mode is committed until its end time");
        }
      }
      await ctx.db.patch(existing._id, patch as never);
      return existing._id;
    }
    if (args.workRatio !== undefined) patch.workRatioUpdatedAt = args.workRatioUpdatedAt ?? args.updatedAt;
    if (args.taskBonusMinutes !== undefined) patch.taskBonusMinutesUpdatedAt = args.taskBonusMinutesUpdatedAt ?? args.updatedAt;
    return await ctx.db.insert("userPrefs", {
      userId,
      strictMode: args.strictMode ?? false,
      weeklyReport: args.weeklyReport ?? true,
      ...patch,
    } as never);
  },
});
