import { internalMutation, internalQuery, mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

async function findState(ctx: any, userId: string) {
  return await ctx.db.query("removalReminders")
    .withIndex("by_user", (q: any) => q.eq("userId", userId)).first();
}

function publicState(row: any) {
  return {
    revision: row?.revision ?? 0,
    enabled: row?.enabled ?? false,
    enabledUpdatedAt: row?.enabledUpdatedAt ?? 0,
    mediaUpdatedAt: row?.mediaUpdatedAt ?? 0,
    media: row?.media ? {
      sha256: row.media.sha256,
      mimeType: row.media.mimeType,
      name: row.media.name,
      bytes: row.media.bytes,
    } : null,
  };
}

export const getState = query({
  args: {},
  handler: async (ctx) => publicState(await findState(ctx, await requireUserId(ctx))),
});

export const saveEnabled = mutation({
  args: { expectedRevision: v.number(), enabled: v.boolean(), updatedAt: v.number() },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (!Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0) throw new Error("Invalid expected revision");
    if (!Number.isSafeInteger(args.updatedAt) || args.updatedAt < 0) throw new Error("Invalid update time");
    const row = await findState(ctx, userId);
    const revision = row?.revision ?? 0;
    if (args.expectedRevision !== revision || args.updatedAt <= (row?.enabledUpdatedAt ?? 0)) {
      return { applied: false as const, state: publicState(row) };
    }
    const next = { revision: revision + 1, enabled: args.enabled, enabledUpdatedAt: args.updatedAt,
      mediaUpdatedAt: row?.mediaUpdatedAt ?? 0, media: row?.media };
    if (row) await ctx.db.patch(row._id, next);
    else await ctx.db.insert("removalReminders", { userId, ...next });
    return { applied: true as const, state: publicState(next) };
  },
});

export const clearMedia = mutation({
  args: { expectedRevision: v.number(), updatedAt: v.number() },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (!Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0) throw new Error("Invalid expected revision");
    if (!Number.isSafeInteger(args.updatedAt) || args.updatedAt < 0) throw new Error("Invalid update time");
    const row = await findState(ctx, userId);
    const revision = row?.revision ?? 0;
    if (args.expectedRevision !== revision || args.updatedAt <= (row?.mediaUpdatedAt ?? 0)) {
      return { applied: false as const, state: publicState(row) };
    }
    if (row?.media) await ctx.storage.delete(row.media.storageId);
    const next = { revision: revision + 1, enabled: row?.enabled ?? false,
      enabledUpdatedAt: row?.enabledUpdatedAt ?? 0, mediaUpdatedAt: args.updatedAt, media: undefined };
    if (row) await ctx.db.patch(row._id, next);
    else await ctx.db.insert("removalReminders", { userId, ...next });
    return { applied: true as const, state: publicState(next) };
  },
});

/** Advance a same-file media intent clock without re-uploading its bytes. */
export const touchMedia = mutation({
  args: { expectedRevision: v.number(), sha256: v.string(), updatedAt: v.number() },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (!Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0) throw new Error("Invalid expected revision");
    if (!Number.isSafeInteger(args.updatedAt) || args.updatedAt < 0) throw new Error("Invalid update time");
    if (!/^[0-9a-f]{64}$/i.test(args.sha256)) throw new Error("Invalid media SHA-256");
    const row = await findState(ctx, userId);
    const revision = row?.revision ?? 0;
    if (revision !== args.expectedRevision || !row?.media ||
        row.media.sha256 !== args.sha256.toLowerCase() || args.updatedAt <= row.mediaUpdatedAt) {
      return { applied: false as const, state: publicState(row) };
    }
    const next = { revision: revision + 1, enabled: row.enabled,
      enabledUpdatedAt: row.enabledUpdatedAt, mediaUpdatedAt: args.updatedAt, media: row.media };
    await ctx.db.patch(row._id, next);
    return { applied: true as const, state: publicState(next) };
  },
});

/** Private lookup used only by the authenticated HTTP media handlers. */
export const getPrivateState = internalQuery({
  args: { userId: v.string() },
  handler: async (ctx, { userId }) => {
    const row = await findState(ctx, userId);
    return { ...publicState(row), storageId: row?.media?.storageId };
  },
});

/** Attach an action-uploaded object atomically, deleting the replaced object. */
export const attachMedia = internalMutation({
  args: {
    userId: v.string(), expectedRevision: v.number(), updatedAt: v.number(),
    sha256: v.string(), mimeType: v.string(), name: v.string(), bytes: v.number(),
    storageId: v.id("_storage"),
  },
  handler: async (ctx, args) => {
    const row = await findState(ctx, args.userId);
    const revision = row?.revision ?? 0;
    if (revision !== args.expectedRevision || args.updatedAt <= (row?.mediaUpdatedAt ?? 0)) {
      return { applied: false as const, state: publicState(row),
        discardStorageId: row?.media?.storageId !== args.storageId ? args.storageId : undefined };
    }
    const sameObject = row?.media?.sha256 === args.sha256 ? row.media.storageId : undefined;
    if (row?.media && row.media.storageId !== sameObject) await ctx.storage.delete(row.media.storageId);
    const media = {
      storageId: sameObject ?? args.storageId, sha256: args.sha256,
      mimeType: args.mimeType, name: args.name, bytes: args.bytes,
    };
    const next = { revision: revision + 1, enabled: row?.enabled ?? false,
      enabledUpdatedAt: row?.enabledUpdatedAt ?? 0, mediaUpdatedAt: args.updatedAt, media };
    if (row) await ctx.db.patch(row._id, next);
    else await ctx.db.insert("removalReminders", { userId: args.userId, ...next });
    return { applied: true as const, state: publicState(next),
      discardStorageId: sameObject && sameObject !== args.storageId ? args.storageId : undefined };
  },
});
