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

  // Version rows survive an intentionally empty collection, allowing clients
  // to distinguish "deleted everywhere" from "never synced".
  syncVersions: defineTable({
    userId: v.string(),
    collection: v.union(v.literal("blockedApps"), v.literal("blockedWebsites"), v.literal("appLimits"), v.literal("blockSchedules")),
    updatedAt: v.number(),
  }).index("by_user_collection", ["userId", "collection"]),

  // StayFree parity: per-app / per-site limits (daily cap + per-session cap).
  appLimits: defineTable({
    userId: v.string(),
    targetKind: v.string(), // "app" | "site" | "category"
    targetKey: v.string(), // packageName, domain, or category name
    label: v.optional(v.string()),
    dailyLimitMinutes: v.optional(v.number()), // 0/undefined = no limit
    sessionLimitMinutes: v.optional(v.number()),
    isBlockedNow: v.optional(v.boolean()),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_target", ["userId", "targetKind", "targetKey"]),

  // StayFree parity: scheduled blocks (e.g. Mon–Fri 09:00–17:00).
  blockSchedules: defineTable({
    userId: v.string(),
    scheduleId: v.string(),
    label: v.string(),
    targetKind: v.string(), // "app" | "site" | "category" | "all"
    targetKey: v.string(), // key or "*" for all
    days: v.array(v.number()), // 0=Sun..6=Sat
    startMinute: v.number(), // minutes since midnight
    endMinute: v.number(),
    isEnabled: v.boolean(),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_schedule", ["userId", "scheduleId"]),

  // StayFree parity: focus / pomodoro sessions (earn credit on desktop too).
  focusSessions: defineTable({
    userId: v.string(),
    sessionId: v.string(),
    title: v.string(),
    durationMinutes: v.number(),
    timestamp: v.number(),
    source: v.string(),
    earnedMinutesCredited: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_session", ["userId", "sessionId"]),

  // StayFree parity: daily usage rollups for dateRange reports + cross-device stats.
  dailyUsage: defineTable({
    userId: v.string(),
    date: v.string(), // YYYY-MM-DD
    totalScreenMinutes: v.number(),
    appCount: v.number(),
    unlockCount: v.optional(v.number()),
    topApps: v.array(
      v.object({
        packageName: v.string(),
        appName: v.string(),
        minutes: v.number(),
      }),
    ),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_date", ["userId", "date"]),

  // A stable installation record.  Device IDs are generated and persisted by
  // each client; they are never inferred from a device name.
  devices: defineTable({
    userId: v.string(),
    deviceId: v.string(),
    name: v.string(),
    platform: v.union(v.literal("android"), v.literal("windows"), v.literal("browser")),
    appVersion: v.string(),
    trackingStatus: v.union(
      v.literal("active"),
      v.literal("paused"),
      v.literal("permission_required"),
      v.literal("error"),
    ),
    statusDetail: v.optional(v.string()),
    lastSeen: v.number(),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_device", ["userId", "deviceId"]),

  // Absolute per-device counters make retries idempotent. Multiple devices
  // never overwrite one aggregate row, and summaries add the buckets.
  deviceUsage: defineTable({
    userId: v.string(),
    deviceId: v.string(),
    date: v.string(), // local device day, YYYY-MM-DD
    targetKind: v.union(v.literal("app"), v.literal("website")),
    targetKey: v.string(),
    targetLabel: v.string(),
    category: v.optional(v.string()),
    trackedSeconds: v.number(),
    blockedSeconds: v.optional(v.number()),
    launchCount: v.optional(v.number()),
    updatedAt: v.number(),
  })
    .index("by_user", ["userId"])
    .index("by_user_date", ["userId", "date"])
    .index("by_user_device_date", ["userId", "deviceId", "date"])
    .index("by_user_usage_bucket", ["userId", "deviceId", "date", "targetKind", "targetKey"]),

  // StayFree parity: global prefs (strict mode, reminders, exports).
  // workRatio / taskBonusMinutes are cross-platform synced settings (Android,
  // desktop, extension) with per-field timestamps for independent last-writer-wins.
  userPrefs: defineTable({
    userId: v.string(),
    strictMode: v.boolean(),
    weeklyReport: v.boolean(),
    dailyReminderMinutes: v.optional(v.number()),
    globalDailyCapMinutes: v.optional(v.number()),
    workRatio: v.optional(v.number()),
    workRatioUpdatedAt: v.optional(v.number()),
    taskBonusMinutes: v.optional(v.number()),
    taskBonusMinutesUpdatedAt: v.optional(v.number()),
    updatedAt: v.number(),
  }).index("by_user", ["userId"]),

  // Nuke mode: total phone+PC lock until 10-min reset + coach check-in.
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
