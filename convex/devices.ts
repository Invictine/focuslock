import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

export const heartbeatFields = {
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
};

/** Shared transaction helper: uploads can carry presence without a second call. */
export async function upsertHeartbeat(ctx: any, userId: string, args: {
  deviceId: string; name: string; platform: "android" | "windows" | "browser";
  appVersion: string; trackingStatus: "active" | "paused" | "permission_required" | "error";
  statusDetail?: string; lastSeen: number;
}) {
    const deviceId = args.deviceId.trim();
    if (!deviceId || deviceId.length > 160) throw new Error("Invalid device ID");
    if (!Number.isFinite(args.lastSeen)) throw new Error("Invalid heartbeat time");
    const now = Date.now();
    const existing = await ctx.db
      .query("devices")
      .withIndex("by_user_device", (q: any) => q.eq("userId", userId).eq("deviceId", deviceId))
      .first();
    const value = {
      ...args,
      deviceId,
      name: args.name.trim().slice(0, 120) || (
        args.platform === "windows" ? "Windows PC" :
        args.platform === "browser" ? "Chrome extension" :
        "Android phone"
      ),
      appVersion: args.appVersion.trim().slice(0, 40) || "unknown",
      statusDetail: args.statusDetail?.trim().slice(0, 240),
      // Do not let a bad client clock place a device indefinitely in the future.
      lastSeen: Math.max(existing?.lastSeen ?? 0, Math.min(args.lastSeen, now + 60_000)),
      updatedAt: now,
      userId,
    };
    const profile = await ctx.db.query("deviceProfiles")
      .withIndex("by_user_device", (q: any) => q.eq("userId", userId).eq("deviceId", deviceId)).first();
    const profileValue = { userId, deviceId, name: value.name, platform: value.platform };
    if (!profile) await ctx.db.insert("deviceProfiles", profileValue);
    else if (profile.name !== value.name || profile.platform !== value.platform) await ctx.db.patch(profile._id, profileValue);
    if (existing) {
      // Replaying the same presence does not invalidate device subscriptions.
      if (Object.entries(value).every(([key, item]) => key === "updatedAt" || existing[key] === item)) return existing._id;
      await ctx.db.patch(existing._id, value);
      return existing._id;
    }
    return await ctx.db.insert("devices", value);
}

/** Legacy device rows remain a fallback until an account's next heartbeat. */
export async function loadDeviceProfiles(ctx: any, userId: string): Promise<{
  deviceId: string; name: string; platform: "android" | "windows" | "browser";
}[]> {
  const profiles = await ctx.db.query("deviceProfiles")
    .withIndex("by_user_device", (q: any) => q.eq("userId", userId)).collect();
  if (profiles.length) return profiles;
  return await ctx.db.query("devices").withIndex("by_user", (q: any) => q.eq("userId", userId)).collect();
}

export const heartbeat = mutation({
  args: { deviceId: v.string(), ...heartbeatFields },
  handler: async (ctx, args) => upsertHeartbeat(ctx, await requireUserId(ctx), args),
});

export const listDevices = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const devices = await ctx.db
      .query("devices")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .collect();
    return devices.sort((a, b) => b.lastSeen - a.lastSeen);
  },
});
