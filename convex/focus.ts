import { mutation, query, type QueryCtx } from "./_generated/server";
import { v } from "convex/values";
import { loadUsageSummary, loadKnownTargets } from "./usage";
import { applyCollectionDiff, guardStrictBoundaryChanges, validateCollection, validateUpdatedAt } from "./storageDiff";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

type SyncCollection = "blockedApps" | "blockedWebsites" | "appLimits" | "blockSchedules" | "targetGroups";

async function loadPermanentBlocks(ctx: QueryCtx, userId: string) {
  const rows = await ctx.db.query("permanentBlocks")
    .withIndex("by_user", (q) => q.eq("userId", userId)).collect();
  return rows.map(({ targetKind, targetKey, targetLabel }) => ({ targetKind, targetKey, targetLabel }));
}

/** Permanent commitments only grow. Old boundary writers cannot erase them. */
export const addPermanentBlocks = mutation({
  args: { targets: v.array(v.object({
    targetKind: v.union(v.literal("android"), v.literal("windows"), v.literal("website")),
    targetKey: v.string(), targetLabel: v.optional(v.string()),
  })) },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (args.targets.length > 500) throw new Error("Too many permanent blocks");
    // Validate the entire batch before persisting any commitments.
    const targets = args.targets.map((target) => {
      let targetKey = target.targetKey.trim().toLowerCase();
      if (target.targetKind === "website") {
        const url = new URL(targetKey.includes("://") ? targetKey : `https://${targetKey}`);
        if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || /\s/.test(targetKey)) {
          throw new Error("Invalid permanent website");
        }
        targetKey = url.hostname.replace(/^www\./, "").replace(/\.$/, "");
        if (targetKey.length > 253 || !targetKey.includes(".") || targetKey.split(".").some((part) =>
          !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(part))) throw new Error("Invalid permanent website");
      } else if (!targetKey || targetKey.length > 255 || /[/\\\u0000-\u001f]/.test(targetKey) ||
          (target.targetKind === "android" && !/^[a-z0-9_]+(?:\.[a-z0-9_]+)+$/.test(targetKey))) {
        throw new Error("Invalid permanent app");
      }
      const targetLabel = target.targetLabel?.trim() || targetKey;
      if (targetLabel.length > 500) throw new Error("Permanent block label is too long");
      return { targetKind: target.targetKind, targetKey, targetLabel };
    });
    let added = 0;
    for (const target of targets) {
      const existing = await ctx.db.query("permanentBlocks").withIndex("by_user_target", (q) =>
        q.eq("userId", userId).eq("targetKind", target.targetKind).eq("targetKey", target.targetKey)).first();
      if (!existing) {
        await ctx.db.insert("permanentBlocks", { ...target, userId, createdAt: Date.now() });
        added++;
      }
    }
    return { applied: true, added };
  },
});

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
  if (existing) {
    if (existing.updatedAt !== updatedAt) await ctx.db.patch(existing._id, { updatedAt });
  } else await ctx.db.insert("syncVersions", { userId, collection, updatedAt });
}

/** Full snapshot for the signed-in user — powers auto-sync on both clients. */
export const getSnapshot = query({
  args: { knownVersion: v.optional(v.string()), knownGroupsUpdatedAt: v.optional(v.number()),
    usageDate: v.optional(v.string()), includeKnownTargets: v.optional(v.boolean()) },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const state = await ctx.db
      .query("focusState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
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
    const latestRecord = await ctx.db.query("workRecords")
      .withIndex("by_user", (q) => q.eq("userId", userId)).order("desc").first();
    // Legacy collections without version rows need a one-time scan so their
    // content is not hidden behind a misleading zero version.
    const legacyApps = appsVersion ? undefined : await ctx.db.query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId)).collect();
    const legacySites = sitesVersion ? undefined : await ctx.db.query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId)).collect();
    const appsUpdatedAt = appsVersion?.updatedAt ?? Math.max(0, ...(legacyApps ?? []).map((app) => app.updatedAt));
    const sitesUpdatedAt = sitesVersion?.updatedAt ?? Math.max(0, ...(legacySites ?? []).map((site) => site.updatedAt));
    const version = JSON.stringify([
      state?.updatedAt ?? 0, appsUpdatedAt, sitesUpdatedAt,
      latestRecord?._creationTime ?? 0, nuke?.updatedAt ?? 0, prefs?.updatedAt ?? 0,
    ]);
    // Opt-in group version piggybacks the Android pull. Older clients retain
    // their original response contract and separate groupsState endpoint.
    let groupsState;
    if (args.knownGroupsUpdatedAt !== undefined) {
      const groupsVersion = await ctx.db.query("syncVersions")
        .withIndex("by_user_collection", (q) => q.eq("userId", userId).eq("collection", "targetGroups")).first();
      const legacyGroups = groupsVersion ? undefined : await ctx.db.query("targetGroups")
        .withIndex("by_user", (q) => q.eq("userId", userId)).collect();
      const updatedAt = groupsVersion?.updatedAt ?? Math.max(0, ...(legacyGroups ?? []).map((row) => row.updatedAt));
      if (args.knownGroupsUpdatedAt !== updatedAt) {
        groupsState = { updatedAt, groups: legacyGroups ?? await ctx.db.query("targetGroups")
          .withIndex("by_user", (q) => q.eq("userId", userId)).collect() };
      }
    }
    const extras = {
      permanentBlocks: await loadPermanentBlocks(ctx, userId),
      ...(groupsState ? { groupsState } : {}),
      ...(args.usageDate ? { usageSummary: await loadUsageSummary(ctx, userId, { fromDate: args.usageDate, toDate: args.usageDate }) } : {}),
      ...(args.includeKnownTargets ? { knownTargets: await loadKnownTargets(ctx, userId) } : {}),
    };
    if (args.knownVersion === version) return { unchanged: true, version, ...extras };
    let previous: number[] | undefined;
    try {
      const parsed = JSON.parse(args.knownVersion ?? "");
      if (Array.isArray(parsed) && parsed.length === 6 && parsed.every((n) => typeof n === "number")) {
        previous = parsed;
      }
    } catch { /* first sync or an older client */ }
    const current = JSON.parse(version) as number[];
    const changed = (index: number) => !previous || previous[index] !== current[index];
    const apps = changed(1) ? legacyApps ?? await ctx.db.query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId)).collect() : undefined;
    const sites = changed(2) ? legacySites ?? await ctx.db.query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId)).collect() : undefined;
    const records = changed(3) ? await ctx.db.query("workRecords")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .order("desc").take(200) : undefined;
    return {
      unchanged: false,
      partial: !!previous,
      version,
      state: changed(0) ? state : undefined,
      apps,
      sites,
      records,
      nuke: changed(4) ? (nuke ?? null) : undefined,
      prefs: changed(5) ? (prefs ?? null) : undefined,
      ...extras,
      stateUpdatedAt: state?.updatedAt ?? 0,
      appsUpdatedAt,
      sitesUpdatedAt,
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
    expectedUpdatedAt: v.optional(v.number()),
    acknowledgedExternalEarnedSeconds: v.optional(v.number()),
    acknowledgedExternalSpentSeconds: v.optional(v.number()),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const existing = await ctx.db
      .query("focusState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const currentUpdatedAt = existing?.updatedAt ?? 0;
    if (![args.expectedUpdatedAt, args.acknowledgedExternalEarnedSeconds, args.acknowledgedExternalSpentSeconds]
      .filter((value): value is number => value !== undefined).every((value) => Number.isFinite(value) && value >= 0)) {
      throw new Error("Invalid focus-state acknowledgement");
    }
    if (args.expectedUpdatedAt !== undefined && args.expectedUpdatedAt !== currentUpdatedAt) {
      return { applied: false, updatedAt: currentUpdatedAt };
    }
    const currentExternalEarned = existing?.externalEarnedSeconds ?? 0;
    const currentExternalSpent = existing?.externalSpentSeconds ?? 0;
    if ((args.acknowledgedExternalEarnedSeconds !== undefined && args.acknowledgedExternalEarnedSeconds !== currentExternalEarned) ||
        (args.acknowledgedExternalSpentSeconds !== undefined && args.acknowledgedExternalSpentSeconds !== currentExternalSpent)) {
      return { applied: false, updatedAt: currentUpdatedAt };
    }
    const nextState = {
      creditBalanceSeconds: args.creditBalanceSeconds,
      totalWorkSecondsToday: args.totalWorkSecondsToday,
      totalScrollSecondsToday: args.totalScrollSecondsToday,
      tasksCompletedToday: args.tasksCompletedToday,
      lastResetDate: args.lastResetDate,
      updatedAt: args.updatedAt,
    };
    if (existing) {
      // Ignore stale writes from a device with an old clock/cache.
      if (args.updatedAt < existing.updatedAt) return { applied: false, updatedAt: existing.updatedAt };
      // Patch only the native absolute-state fields. External lifetime and
      // per-day counters are owned by recordWork/recordUsageBatch and survive
      // older clients that do not send acknowledgement markers.
      await ctx.db.patch(existing._id, nextState);
      return { applied: true, updatedAt: args.updatedAt };
    }
    await ctx.db.insert("focusState", { ...nextState, userId });
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
    validateUpdatedAt(args.updatedAt);
    validateCollection(args.apps, "Blocked apps", (app) => [app.packageName]);
    const version = await getCollectionVersion(ctx, userId, "blockedApps");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt ?? 0));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
    await guardStrictBoundaryChanges(ctx, userId, existing, args.apps, (app) => [app.packageName], true);
    const effectiveClock = Math.max(args.updatedAt, storedVersion + 1);
    const changedRows = await applyCollectionDiff(ctx, "blockedApps", userId, existing, args.apps,
      (app) => [app.packageName], effectiveClock);
    const collectionClock = changedRows ? effectiveClock : Math.max(args.updatedAt, storedVersion);
    await setCollectionVersion(ctx, userId, "blockedApps", collectionClock);
    return { applied: true, updatedAt: collectionClock };
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
    validateUpdatedAt(args.updatedAt);
    validateCollection(args.sites, "Blocked websites", (site) => [site.domain]);
    const version = await getCollectionVersion(ctx, userId, "blockedWebsites");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt ?? 0));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
    await guardStrictBoundaryChanges(ctx, userId, existing, args.sites, (site) => [site.domain], true);
    const effectiveClock = Math.max(args.updatedAt, storedVersion + 1);
    const changedRows = await applyCollectionDiff(ctx, "blockedWebsites", userId, existing, args.sites,
      (site) => [site.domain], effectiveClock);
    const collectionClock = changedRows ? effectiveClock : Math.max(args.updatedAt, storedVersion);
    await setCollectionVersion(ctx, userId, "blockedWebsites", collectionClock);
    return { applied: true, updatedAt: collectionClock };
  },
});

/** Small conditional pull for browser enforcement. Versions also represent an
 * intentionally empty collection, so deleting the last site reaches browsers. */
export const getSyncPulse = query({
  args: {
    sitesUpdatedAt: v.number(), prefsUpdatedAt: v.number(), nukeUpdatedAt: v.optional(v.number()),
    knownPolicyVersion: v.optional(v.string()), usageDate: v.optional(v.string()),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (args.usageDate !== undefined) {
      const parsed = new Date(`${args.usageDate}T00:00:00Z`);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(args.usageDate) || !Number.isFinite(parsed.getTime()) ||
          parsed.toISOString().slice(0, 10) !== args.usageDate) throw new Error("Invalid usage date");
    }
    const sitesVersion = await getCollectionVersion(ctx, userId, "blockedWebsites");
    const prefs = await ctx.db.query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId)).first();
    const legacySites = sitesVersion ? undefined : await ctx.db.query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId)).collect();
    const sitesUpdatedAt = sitesVersion?.updatedAt ?? Math.max(0, ...(legacySites ?? []).map((site) => site.updatedAt));
    const prefsUpdatedAt = prefs?.updatedAt ?? 0;
    const nuke = args.nukeUpdatedAt === undefined ? undefined : await ctx.db.query("nukeState")
      .withIndex("by_user", (q) => q.eq("userId", userId)).first();
    const nukeUpdatedAt = nuke?.updatedAt ?? 0;
    const sites = sitesUpdatedAt !== args.sitesUpdatedAt
      ? legacySites ?? await ctx.db.query("blockedWebsites")
        .withIndex("by_user", (q) => q.eq("userId", userId)).collect()
      : undefined;
    let policyVersion: string | undefined;
    let policy: { state: any; groups: any[]; limits: any[]; schedules: any[] } | undefined;
    if (args.knownPolicyVersion !== undefined) {
      const [state, groupsVersion, limitsVersion, schedulesVersion] = await Promise.all([
        ctx.db.query("focusState").withIndex("by_user", (q) => q.eq("userId", userId)).first(),
        getCollectionVersion(ctx, userId, "targetGroups"),
        getCollectionVersion(ctx, userId, "appLimits"),
        getCollectionVersion(ctx, userId, "blockSchedules"),
      ]);
      const [legacyGroups, legacyLimits, legacySchedules] = await Promise.all([
        groupsVersion ? Promise.resolve(undefined) : ctx.db.query("targetGroups").withIndex("by_user", (q) => q.eq("userId", userId)).collect(),
        limitsVersion ? Promise.resolve(undefined) : ctx.db.query("appLimits").withIndex("by_user", (q) => q.eq("userId", userId)).collect(),
        schedulesVersion ? Promise.resolve(undefined) : ctx.db.query("blockSchedules").withIndex("by_user", (q) => q.eq("userId", userId)).collect(),
      ]);
      const groupsUpdatedAt = groupsVersion?.updatedAt ?? Math.max(0, ...(legacyGroups ?? []).map((row) => row.updatedAt));
      const limitsUpdatedAt = limitsVersion?.updatedAt ?? Math.max(0, ...(legacyLimits ?? []).map((row) => row.updatedAt));
      const schedulesUpdatedAt = schedulesVersion?.updatedAt ?? Math.max(0, ...(legacySchedules ?? []).map((row) => row.updatedAt));
      policyVersion = JSON.stringify([state?.updatedAt ?? 0, groupsUpdatedAt, limitsUpdatedAt, schedulesUpdatedAt]);
      if (args.knownPolicyVersion !== policyVersion) {
        const [groups, limits, schedules] = await Promise.all([
          legacyGroups ?? ctx.db.query("targetGroups").withIndex("by_user", (q) => q.eq("userId", userId)).collect(),
          legacyLimits ?? ctx.db.query("appLimits").withIndex("by_user", (q) => q.eq("userId", userId)).collect(),
          legacySchedules ?? ctx.db.query("blockSchedules").withIndex("by_user", (q) => q.eq("userId", userId)).collect(),
        ]);
        policy = { state, groups, limits, schedules };
      }
    }
    const includePolicyPrefs = args.knownPolicyVersion !== undefined;
    const prefsPayload = (prefsUpdatedAt !== args.prefsUpdatedAt || includePolicyPrefs)
      ? prefs ? {
          strictMode: prefs.strictMode,
          strictEndsAt: prefs.strictEndsAt,
          strictPreset: prefs.strictPreset,
          strictNukeAfterFive: prefs.strictNukeAfterFive,
          ...(includePolicyPrefs ? {
            globalDailyCapMinutes: prefs.globalDailyCapMinutes,
            strictSessionId: prefs.strictSessionId,
            strictApprovedEndsAt: prefs.strictApprovedEndsAt,
            strictApprovedAt: prefs.strictApprovedAt,
            strictApprovedSessionId: prefs.strictApprovedSessionId,
          } : {}),
        } : null
      : undefined;
    return {
      sitesUpdatedAt,
      permanentBlocks: await loadPermanentBlocks(ctx, userId),
      prefsUpdatedAt,
      ...(args.nukeUpdatedAt === undefined ? {} : {
        nukeUpdatedAt,
        nuke: nukeUpdatedAt !== args.nukeUpdatedAt ? nuke ?? null : undefined,
      }),
      sites: sites?.map((site) => ({
        domain: site.domain,
        isBlocked: site.isBlocked,
        ...(args.knownPolicyVersion === undefined ? {} : { category: site.category }),
      })),
      prefs: prefsPayload,
      ...(policyVersion === undefined ? {} : { policyVersion, ...(policy ? { policy } : {}) }),
      ...(args.usageDate === undefined ? {} : {
        usageSummary: await loadUsageSummary(ctx, userId, { fromDate: args.usageDate, toDate: args.usageDate }),
      }),
    };
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
    const prefs = existing ? await ctx.db.query("userPrefs")
      .withIndex("by_user", (q) => q.eq("userId", userId)).first() : null;
    const preserveMetadata = prefs?.strictMode && (prefs.strictEndsAt ?? 0) > Date.now();
    const site = {
      domain,
      displayName: preserveMetadata ? existing!.displayName : args.displayName.trim() || domain,
      isBlocked: args.isBlocked,
      category: preserveMetadata ? existing!.category : args.category.trim() || "Web",
      ...(existing ? (existing.isCustom === undefined ? {} : { isCustom: existing.isCustom }) : { isCustom: true }),
      updatedAt,
    };
    // The per-site API permits added blocks and preserves existing restrictions.
    // Preserve optional legacy fields when this API does not expose them.
    const incoming = existing ? { ...existing, ...site } : site;
    await guardStrictBoundaryChanges(ctx, userId, existing ? [existing] : [], [incoming], (row) => [row.domain], true);
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
    date: v.string(), tasksCompleted: v.number(), projectName: v.optional(v.string()),
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
    // The history and session views share one durable event instead of two
    // identical indexed documents. Legacy standalone sessions stay readable.
    await ctx.db.insert("workRecords", { ...record, userId, representsFocusSession: true });
    const state = await ctx.db.query("focusState").withIndex("by_user", (q) => q.eq("userId", userId)).first();
    const sameDay = state?.lastResetDate === date;
    const olderDay = !!state && state.lastResetDate > date;
    const externalDate = state?.externalDate ?? "";
    const sameExternalDay = externalDate === date;
    const olderExternalDay = Boolean(externalDate && externalDate > date);
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
      externalEarnedSeconds: (state?.externalEarnedSeconds ?? 0) + args.earnedMinutesCredited * 60,
      externalSpentSeconds: state?.externalSpentSeconds ?? 0,
      externalDate: olderExternalDay ? externalDate : date,
      externalWorkSecondsToday: olderExternalDay ? state?.externalWorkSecondsToday ?? 0
        : (sameExternalDay ? state?.externalWorkSecondsToday ?? 0 : 0) + args.durationMinutes * 60,
      externalScrollSecondsToday: olderExternalDay ? state?.externalScrollSecondsToday ?? 0
        : sameExternalDay ? state?.externalScrollSecondsToday ?? 0 : 0,
      externalTasksCompletedToday: olderExternalDay ? state?.externalTasksCompletedToday ?? 0
        : (sameExternalDay ? state?.externalTasksCompletedToday ?? 0 : 0) + tasksCompleted,
    };
    if (state) await ctx.db.patch(state._id, next);
    else await ctx.db.insert("focusState", next);
    return { applied: true, duplicate: false };
  },
});

export const getDashboard = query({
  args: { fromDate: v.optional(v.string()), toDate: v.optional(v.string()) },
  handler: async (ctx, args) => loadDashboard(ctx, await requireUserId(ctx), args, true),
});

/** Reactive enforcement surface excludes history, usage and presence. */
export const getConfiguration = query({
  args: {},
  handler: async (ctx) => loadDashboard(ctx, await requireUserId(ctx), {}, false),
});

export const getState = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const state = await ctx.db.query("focusState").withIndex("by_user", (q) => q.eq("userId", userId)).first();
    return { state, stateUpdatedAt: state?.updatedAt ?? 0 };
  },
});

export const getHistory = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    return await loadWorkHistory(ctx, userId);
  },
});

async function loadWorkHistory(ctx: QueryCtx, userId: string) {
  const records = await ctx.db.query("workRecords").withIndex("by_user", (q) => q.eq("userId", userId)).order("desc").take(200);
  const legacy = await ctx.db.query("focusSessions").withIndex("by_user", (q) => q.eq("userId", userId)).order("desc").take(200);
  const merged = new Map(legacy.map((session) => [session.sessionId, { ...session }]));
  for (const record of records) if (record.representsFocusSession) {
    const { _id, recordId, representsFocusSession: _flag, projectName: _project, ...event } = record;
    // IDs are only presentation keys; session mutations still use sessionId.
    merged.set(recordId, { ...event, _id: _id as any, sessionId: recordId });
  }
  return { records, sessions: [...merged.values()].sort((a, b) => b._creationTime - a._creationTime).slice(0, 200) };
}

async function loadDashboard(ctx: QueryCtx, userId: string,
  args: { fromDate?: string; toDate?: string }, includeHistory: boolean) {
    const state = includeHistory ? await ctx.db
      .query("focusState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first() : null;
    const apps = await ctx.db
      .query("blockedApps")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const sites = await ctx.db
      .query("blockedWebsites")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const history = includeHistory ? await loadWorkHistory(ctx, userId) : { records: [], sessions: [] };
    const records = history.records;
    const limits = await ctx.db
      .query("appLimits")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const schedules = await ctx.db
      .query("blockSchedules")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const sessions = history.sessions;
    // Fetch only the requested window straight from the (userId, date) index,
    // newest first, capped at the 90 rows the response shape carries — instead
    // of collecting every dailyUsage row and filtering/sorting in JS.
    const usage = !includeHistory ? [] : args.fromDate && args.toDate
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
    const devices = includeHistory ? await ctx.db
      .query("devices")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect() : [];
    const versions = await Promise.all(
      (["blockedApps", "blockedWebsites", "appLimits", "blockSchedules"] as const)
        .map((collection) => getCollectionVersion(ctx, userId, collection)),
    );
    return {
      state, apps, sites, records, limits, schedules, sessions, usage, prefs, devices,
      permanentBlocks: await loadPermanentBlocks(ctx, userId),
      stateUpdatedAt: state?.updatedAt ?? 0,
      appsUpdatedAt: versions[0]?.updatedAt ?? Math.max(0, ...apps.map((row) => row.updatedAt)),
      sitesUpdatedAt: versions[1]?.updatedAt ?? Math.max(0, ...sites.map((row) => row.updatedAt)),
      limitsUpdatedAt: versions[2]?.updatedAt ?? Math.max(0, ...limits.map((row) => row.updatedAt)),
      schedulesUpdatedAt: versions[3]?.updatedAt ?? Math.max(0, ...schedules.map((row) => row.updatedAt)),
    };
}


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
    validateUpdatedAt(args.updatedAt);
    validateCollection(args.limits, "App limits", (limit) => [limit.targetKind, limit.targetKey]);
    const version = await getCollectionVersion(ctx, userId, "appLimits");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("appLimits")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt ?? 0));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
    await guardStrictBoundaryChanges(ctx, userId, existing, args.limits, (limit) => [limit.targetKind, limit.targetKey]);
    const effectiveClock = Math.max(args.updatedAt, storedVersion + 1);
    const changedRows = await applyCollectionDiff(ctx, "appLimits", userId, existing, args.limits,
      (limit) => [limit.targetKind, limit.targetKey], effectiveClock);
    const collectionClock = changedRows ? effectiveClock : Math.max(args.updatedAt, storedVersion);
    await setCollectionVersion(ctx, userId, "appLimits", collectionClock);
    return { applied: true, updatedAt: collectionClock };
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
    validateUpdatedAt(args.updatedAt);
    validateCollection(args.schedules, "Schedules", (schedule) => [schedule.scheduleId]);
    const version = await getCollectionVersion(ctx, userId, "blockSchedules");
    if (version && args.updatedAt < version.updatedAt) return { applied: false, updatedAt: version.updatedAt };
    const existing = await ctx.db
      .query("blockSchedules")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    const storedVersion = Math.max(version?.updatedAt ?? 0, ...existing.map((row) => row.updatedAt ?? 0));
    if (args.updatedAt < storedVersion) return { applied: false, updatedAt: storedVersion };
    await guardStrictBoundaryChanges(ctx, userId, existing, args.schedules, (schedule) => [schedule.scheduleId]);
    const effectiveClock = Math.max(args.updatedAt, storedVersion + 1);
    const changedRows = await applyCollectionDiff(ctx, "blockSchedules", userId, existing, args.schedules,
      (schedule) => [schedule.scheduleId], effectiveClock);
    const collectionClock = changedRows ? effectiveClock : Math.max(args.updatedAt, storedVersion);
    await setCollectionVersion(ctx, userId, "blockSchedules", collectionClock);
    return { applied: true, updatedAt: collectionClock };
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
        if (patch.globalDailyCapMinutes !== undefined && patch.globalDailyCapMinutes !== existing.globalDailyCapMinutes) {
          throw new Error("Boundaries cannot change during Strict Mode");
        }
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
