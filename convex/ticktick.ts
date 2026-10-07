import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

const MAX_TOKEN_LENGTH = 16_384;
const MAX_USER_NAME_LENGTH = 256;

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

const connectionValidator = v.object({
  accessToken: v.string(),
  refreshToken: v.optional(v.string()),
  expiresAt: v.optional(v.number()),
  userName: v.optional(v.string()),
});

/** Read only the authenticated account's TickTick OAuth link. */
export const getConnection = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const row = await ctx.db
      .query("ticktickConnections")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();

    if (!row) return { revision: 0, connection: null };
    if (!row.accessToken) return { revision: row.revision, connection: null };
    return {
      revision: row.revision,
      connection: {
        accessToken: row.accessToken,
        ...(row.refreshToken === undefined ? {} : { refreshToken: row.refreshToken }),
        ...(row.expiresAt === undefined ? {} : { expiresAt: row.expiresAt }),
        ...(row.userName === undefined ? {} : { userName: row.userName }),
      },
    };
  },
});

/**
 * Save or disconnect a TickTick link if the caller's revision is current.
 * A disconnect keeps a tombstone row so an older offline save cannot revive it.
 */
export const saveConnection = mutation({
  args: {
    expectedRevision: v.number(),
    connection: v.union(v.null(), connectionValidator),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    if (!Number.isSafeInteger(args.expectedRevision) || args.expectedRevision < 0) {
      throw new Error("Invalid expected revision");
    }

    if (args.connection) {
      const connection = args.connection;
      if (!connection.accessToken.trim() || connection.accessToken.length > MAX_TOKEN_LENGTH) {
        throw new Error("Invalid TickTick access token");
      }
      if (connection.refreshToken !== undefined &&
          (!connection.refreshToken.trim() || connection.refreshToken.length > MAX_TOKEN_LENGTH)) {
        throw new Error("Invalid TickTick refresh token");
      }
      if (connection.expiresAt !== undefined &&
          (!Number.isFinite(connection.expiresAt) || connection.expiresAt < 0)) {
        throw new Error("Invalid TickTick token expiry");
      }
      if (connection.userName !== undefined &&
          (connection.userName.length > MAX_USER_NAME_LENGTH || !connection.userName.trim())) {
        throw new Error("Invalid TickTick user name");
      }
    }

    const existing = await ctx.db
      .query("ticktickConnections")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    const currentRevision = existing?.revision ?? 0;
    if (args.expectedRevision !== currentRevision) {
      return { applied: false as const, revision: currentRevision };
    }

    const revision = currentRevision + 1;
    const credentials = args.connection
      ? {
          accessToken: args.connection.accessToken,
          refreshToken: args.connection.refreshToken,
          expiresAt: args.connection.expiresAt,
          userName: args.connection.userName,
        }
      : {
          accessToken: undefined,
          refreshToken: undefined,
          expiresAt: undefined,
          userName: undefined,
        };

    if (existing) {
      await ctx.db.patch(existing._id, { revision, ...credentials });
    } else {
      await ctx.db.insert("ticktickConnections", { userId, revision, ...credentials });
    }
    return { applied: true as const, revision };
  },
});
