import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

const MAX_LABEL_LENGTH = 120;
const placeValidator = v.object({
  label: v.string(),
  latitude: v.number(),
  longitude: v.number(),
  radiusMeters: v.number(),
});

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

/** Read the authenticated account's saved home place (device consent is not stored here). */
export const getSaved = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const row = await ctx.db.query("homeLocations")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    return { revision: row?.revision ?? 0, place: row?.place ?? null, updatedAt: row?.updatedAt ?? 0 };
  },
});

/** Store a place using revision CAS so offline edits cannot silently overwrite a later save. */
export const saveSaved = mutation({
  args: {
    expectedRevision: v.number(),
    place: v.union(v.null(), placeValidator),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (!Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0) {
      throw new Error("Invalid expected revision");
    }
    if (!Number.isFinite(args.updatedAt) || args.updatedAt < 0) throw new Error("Invalid update time");
    if (args.place !== null) {
      const { label, latitude, longitude, radiusMeters } = args.place;
      if (!label.trim() || label.trim().length > MAX_LABEL_LENGTH) throw new Error("Invalid home label");
      if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
          !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
        throw new Error("Invalid home coordinates");
      }
      if (!Number.isFinite(radiusMeters) || radiusMeters < 25 || radiusMeters > 5_000) {
        throw new Error("Invalid home radius");
      }
    }

    const existing = await ctx.db.query("homeLocations")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const prefs = await ctx.db.query("userPrefs").withIndex("by_user", q => q.eq("userId", userId)).first();
    const incomingPlace = args.place === null ? undefined : { ...args.place, label: args.place.label.trim() };
    if (prefs?.strictMode && (!prefs.strictEndsAt || prefs.strictEndsAt > Date.now()) &&
        JSON.stringify(existing?.place) !== JSON.stringify(incomingPlace)) {
      throw new Error("Home location cannot change during Strict Mode");
    }
    const revision = existing?.revision ?? 0;
    if (args.expectedRevision !== revision) return { applied: false as const, revision };

    const nextRevision = revision + 1;
    const place = args.place === null ? undefined : { ...args.place, label: args.place.label.trim() };
    if (existing) await ctx.db.patch(existing._id, { revision: nextRevision, place, updatedAt: args.updatedAt });
    else await ctx.db.insert("homeLocations", { userId, revision: nextRevision, place, updatedAt: args.updatedAt });
    return { applied: true as const, revision: nextRevision };
  },
});
