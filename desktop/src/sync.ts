import { api } from "../../convex/_generated/api";

export type TrackingStatus = "active" | "paused" | "permission_required" | "error";
export type DevicePlatform = "android" | "windows";

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
  getDashboard: api.focus.getDashboard,
  savePrefs: api.focus.savePrefs,
};

export function localDate(timestamp = Date.now()): string {
  const date = new Date(timestamp);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}
