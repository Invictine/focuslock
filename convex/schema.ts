import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";

export default defineSchema({
  focusState: defineTable({
    userId: v.string(),
    creditBalanceSeconds: v.number(),
    totalWorkSecondsToday: v.number(),
    totalScrollSecondsToday: v.number(),
    tasksCompletedToday: v.number(),
    lastResetDate: v.string(),
    updatedAt: v.number(),
  }).index("by_user", ["userId"]),

  blockedApps: defineTable({
    userId: v.string(),
    packageName: v.string(),
    appName: v.string(),
    isBlocked: v.boolean(),
    category: v.string(),
    specificShortsOnly: v.optional(v.boolean()),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_package", ["userId", "packageName"]),

  blockedWebsites: defineTable({
    userId: v.string(),
    domain: v.string(),
    displayName: v.string(),
    isBlocked: v.boolean(),
    category: v.string(),
    isCustom: v.optional(v.boolean()),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_domain", ["userId", "domain"]),

  workRecords: defineTable({
    userId: v.string(),
    recordId: v.string(),
    title: v.string(),
    durationMinutes: v.number(),
    timestamp: v.number(),
    source: v.string(),
    earnedMinutesCredited: v.number(),
    projectName: v.optional(v.string()),
  })
    .index("by_user", ["userId"])
    .index("by_user_record", ["userId", "recordId"]),

  nukeState: defineTable({
    userId: v.string(),
    isActive: v.boolean(),
    startedAt: v.number(),
    meditationCompletedAt: v.optional(v.number()),
    planText: v.optional(v.string()),
    unlockedAt: v.optional(v.number()),
    updatedAt: v.number(),
  }).index("by_user", ["userId"]),
});
