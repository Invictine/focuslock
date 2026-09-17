import { api } from "../../convex/_generated/api";

export type TrackingStatus = "active" | "paused" | "permission_required" | "error";
export type DevicePlatform = "android" | "windows" | "browser";

export type DeviceHeartbeat = {
  deviceId: string;
  name: string;
  platform: DevicePlatform;
  appVersion: string;
  trackingStatus: TrackingStatus;
  statusDetail?: string;
  lastSeen: number;
};

export type UsageBucket = {
  date: string;
  targetKind: "app" | "website";
  targetKey: string;
  targetLabel: string;
  category?: string;
  trackedSeconds: number;
  blockedSeconds?: number;
  launchCount?: number;
  updatedAt: number;
};

// ---------------------------------------------------------------------------
// Merged buckets ("groups") + usage summary — mirrors convex/groups.ts and the
// getUsageSummary return value in convex/usage.ts. Keep in sync with the server:
// targetKey is always lowercased there, group members may appear in at most one
// group, and groups with fewer than 2 members are dropped server-side.
// ---------------------------------------------------------------------------

export type TargetGroupMember = {
  targetKind: "app" | "website";
  targetKey: string;
  targetLabel: string;
};

export type TargetGroup = {
  groupId: string;
  name: string;
  category?: string;
  members: TargetGroupMember[];
  // 0/undefined = no limit; otherwise the combined cross-device daily cap.
  dailyLimitMinutes?: number;
  limitEnabled?: boolean;
  updatedAt?: number;
};

export type GroupedTarget = {
  targetKind: "app" | "website" | "group";
  targetKey: string;
  targetLabel: string;
  trackedSeconds: number;
  blockedSeconds: number;
  deviceIds: string[];
  // For group rows: every member's "kind:key". For plain rows: [own key].
  memberKeys: string[];
  memberCount: number;
  groupId?: string;
  dailyLimitMinutes?: number;
  limitEnabled?: boolean;
  category?: string;
};

export type UsageGroupMemberSummary = TargetGroupMember & {
  trackedSeconds: number;
  deviceIds: string[];
};

export type UsageGroupSummary = {
  groupId: string;
  name: string;
  category?: string;
  members: UsageGroupMemberSummary[];
  trackedSeconds: number;
  blockedSeconds: number;
  deviceIds: string[];
  dailyLimitMinutes?: number;
  limitEnabled?: boolean;
};

export type UsageTargetSummary = {
  targetKind: "app" | "website";
  targetKey: string;
  targetLabel: string;
  trackedSeconds: number;
  blockedSeconds: number;
  deviceIds: string[];
};

export type UsageDaySummary = {
  date: string;
  trackedSeconds: number;
  blockedSeconds: number;
};

export type UsageDeviceSummary = {
  deviceId: string;
  deviceName: string;
  trackedSeconds: number;
  blockedSeconds: number;
};

export type UsageSummary = {
  totalTrackedSeconds: number;
  days: UsageDaySummary[];
  devices: UsageDeviceSummary[];
  targets: UsageTargetSummary[];
  groupedTargets: GroupedTarget[];
  groups: UsageGroupSummary[];
};

// Mirrors convex/usage.ts `listKnownTargets`: the all-time, all-device
// enumeration of every target the account has ever tracked (a Windows exe key,
// an Android package and a domain all appear here), with the devices that
// produced it and its current group, if any. Undefined against an older
// deployment, which is why every consumer must tolerate undefined.
export type KnownTargetDevice = {
  deviceId: string;
  name: string;
  platform: DevicePlatform | "unknown";
};

export type KnownTarget = {
  targetKind: "app" | "website";
  targetKey: string;
  targetLabel: string;
  category?: string;
  trackedSeconds: number;
  lastDate: string;
  deviceIds: string[];
  devices: KnownTargetDevice[];
  groupId?: string;
  groupName?: string;
};

// Mirrors the Convex `userPrefs` document. `workRatio` / `taskBonusMinutes` are
// cross-platform synced settings (Android, desktop, extension) with per-field
// `*UpdatedAt` timestamps (ms epoch) used for independent last-writer-wins.
export type UserPrefs = {
  _id?: string;
  _creationTime?: number;
  userId?: string;
  strictMode?: boolean;
  weeklyReport?: boolean;
  dailyReminderMinutes?: number;
  globalDailyCapMinutes?: number;
  workRatio?: number;
  workRatioUpdatedAt?: number;
  taskBonusMinutes?: number;
  taskBonusMinutesUpdatedAt?: number;
  updatedAt?: number;
};

export const syncApi = {
  heartbeat: api.devices.heartbeat,
  listDevices: api.devices.listDevices,
  recordUsageBatch: api.usage.recordUsageBatch,
  getUsageSummary: api.usage.getUsageSummary,
  listKnownTargets: api.usage.listKnownTargets,
  getDashboard: api.focus.getDashboard,
  savePrefs: api.focus.savePrefs,
  listGroups: api.groups.listGroups,
  saveGroups: api.groups.saveGroups,
};

export function localDate(timestamp = Date.now()): string {
  const date = new Date(timestamp);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/**
 * `YYYY-MM-DD` for `daysAgo` days before today (0 = today). Uses Date#setDate so
 * month/year boundaries and DST shifts are handled by the platform.
 */
export function localDateOffset(daysAgo: number): string {
  const date = new Date();
  date.setDate(date.getDate() - Math.max(0, Math.floor(daysAgo)));
  return localDate(date.getTime());
}
