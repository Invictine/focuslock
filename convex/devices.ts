import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

export const heartbeat = mutation({
  args: {
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
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const deviceId = args.deviceId.trim();
    if (!deviceId || deviceId.length > 160) throw new Error("Invalid device ID");
    const now = Date.now();
    const existing = await ctx.db
      .query("devices")
      .withIndex("by_user_device", (q) => q.eq("userId", userId).eq("deviceId", deviceId))
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
      lastSeen: Math.min(args.lastSeen, now + 60_000),
      updatedAt: now,
      userId,
    };
    if (existing) {
      await ctx.db.patch(existing._id, value);
      return existing._id;
    }
    return await ctx.db.insert("devices", value);
  },
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
