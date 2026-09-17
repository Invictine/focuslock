import { mutation, query } from "./_generated/server";
import { v } from "convex/values";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

const MAX_GROUPS = 100;
const MAX_MEMBERS = 50;

const memberValidator = v.object({
  targetKind: v.union(v.literal("app"), v.literal("website")),
  targetKey: v.string(),
  targetLabel: v.string(),
});

const groupValidator = v.object({
  groupId: v.string(),
  name: v.string(),
  category: v.optional(v.string()),
  members: v.array(memberValidator),
  // 0 / undefined = no limit. When set, the combined cross-device total for
  // every member counts against this single cap.
  dailyLimitMinutes: v.optional(v.number()),
  limitEnabled: v.optional(v.boolean()),
});

type Member = { targetKind: "app" | "website"; targetKey: string; targetLabel: string };
type CanonicalGroup = {
  groupId: string;
  name: string;
  category?: string;
  members: Member[];
  dailyLimitMinutes?: number;
  limitEnabled?: boolean;
};

function slug(value: string): string {
  const cleaned = value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return cleaned.slice(0, 60) || "group";
}

/** Website usage keys are always stored without a leading "www." (the desktop
 * tracker and the extension both strip it), so member keys must match. */
function normalizeMemberKey(kind: "app" | "website", raw: string): string {
  const key = raw.trim().toLowerCase().slice(0, 500);
  return kind === "website" ? key.replace(/^www\./, "") : key;
}

// A target may live in exactly one group, so merging YouTube-the-app with
// YouTube-the-website cannot double count into two overlapping buckets.
// A group that ends up with fewer than two usable members is treated as
// "ungrouped": it is dropped WITHOUT consuming its members, so a later group
// can still claim them.
function canonicalize(raw: {
  groupId: string;
  name: string;
  category?: string;
  members: Member[];
  dailyLimitMinutes?: number;
  limitEnabled?: boolean;
}[]): CanonicalGroup[] {
  const claimedMembers = new Set<string>();
  const usedIds = new Set<string>();
  const out: CanonicalGroup[] = [];

  raw.slice(0, MAX_GROUPS).forEach((group, index) => {
    const name = group.name.trim().slice(0, 80);
    if (!name) return;

    // Collect this group's members without touching the global claim set yet.
    const members: Member[] = [];
    for (const member of group.members.slice(0, MAX_MEMBERS)) {
      const targetKey = normalizeMemberKey(member.targetKind, member.targetKey);
      if (!targetKey) continue;
      members.push({
        targetKind: member.targetKind,
        targetKey,
        targetLabel: member.targetLabel.trim().slice(0, 160) || targetKey,
      });
    }

    // Fewer than two members is not a merge; leave the members unclaimed.
    if (members.length < 2) return;

    // Now that the group is viable, apply first-group-wins across all groups
    // and drop any member another (valid) group already owns.
    const accepted: Member[] = [];
    const newlyClaimed: string[] = [];
    for (const member of members) {
      const dedupeKey = `${member.targetKind}:${member.targetKey}`;
      if (claimedMembers.has(dedupeKey)) continue;
      claimedMembers.add(dedupeKey);
      newlyClaimed.push(dedupeKey);
      accepted.push(member);
    }
    if (accepted.length < 2) {
      // Everything it wanted was already claimed elsewhere: release what this
      // group just claimed so the members stay available to later groups, and
      // do not emit the group at all.
      for (const key of newlyClaimed) claimedMembers.delete(key);
      return;
    }

    let groupId = group.groupId.trim().slice(0, 120);
    if (!groupId) groupId = `${slug(name)}-${index}`;
    while (usedIds.has(groupId)) groupId = `${groupId}-${index}`;
    usedIds.add(groupId);

    const limit = group.dailyLimitMinutes;
    out.push({
      groupId,
      name,
      category: group.category?.trim().slice(0, 80),
      members: accepted,
      dailyLimitMinutes:
        limit === undefined || !Number.isFinite(limit) || limit <= 0
          ? undefined
          : Math.min(Math.floor(limit), 1440),
      limitEnabled: group.limitEnabled === undefined ? undefined : !!group.limitEnabled,
    });
  });

  return out;
}

function mapGroup(group: any) {
  return {
    groupId: group.groupId,
    name: group.name,
    category: group.category,
    members: group.members,
    dailyLimitMinutes: group.dailyLimitMinutes,
    limitEnabled: group.limitEnabled,
    updatedAt: group.updatedAt,
  };
}

async function loadGroups(ctx: any, userId: string) {
  const rows = await ctx.db
    .query("targetGroups")
    .withIndex("by_user", (q: any) => q.eq("userId", userId))
    .collect();
  return rows
    .map(mapGroup)
    .sort((a: any, b: any) => a.name.localeCompare(b.name));
}

/** The authoritative collection version. Version rows survive an empty
 * collection so clients can tell "deleted on every device" apart from
 * "never synced" — without it a client can resurrect deleted groups. */
async function loadGroupsVersion(ctx: any, userId: string, rows?: any[]): Promise<number> {
  const version = await ctx.db
    .query("syncVersions")
    .withIndex("by_user_collection", (q: any) =>
      q.eq("userId", userId).eq("collection", "targetGroups"),
    )
    .first();
  if (version) return version.updatedAt;
  const groups = rows ?? [];
  return groups.reduce((max: number, group: any) => Math.max(max, group.updatedAt ?? 0), 0);
}

export const listGroups = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    return await loadGroups(ctx, userId);
  },
});

/** Group list plus its collection version. Prefer this over `listGroups` for
 * sync clients: `updatedAt` is the LWW clock and is present even when the
 * group list is intentionally empty. */
export const groupsState = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const rows = await ctx.db
      .query("targetGroups")
      .withIndex("by_user", (q: any) => q.eq("userId", userId))
      .collect();
    return {
      groups: rows
        .map(mapGroup)
        .sort((a: any, b: any) => a.name.localeCompare(b.name)),
      updatedAt: await loadGroupsVersion(ctx, userId, rows),
    };
  },
});

export const saveGroups = mutation({
  args: {
    groups: v.array(groupValidator),
    updatedAt: v.number(),
  },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);

    const version = await ctx.db
      .query("syncVersions")
      .withIndex("by_user_collection", (q: any) =>
        q.eq("userId", userId).eq("collection", "targetGroups"),
      )
      .first();
    if (version && args.updatedAt < version.updatedAt) {
      return { applied: false, updatedAt: version.updatedAt };
    }

    const canonical = canonicalize(args.groups);

    const existing = await ctx.db
      .query("targetGroups")
      .withIndex("by_user", (q: any) => q.eq("userId", userId))
      .collect();
    for (const row of existing) await ctx.db.delete(row._id);

    for (const group of canonical) {
      await ctx.db.insert("targetGroups", {
        userId,
        groupId: group.groupId,
        name: group.name,
        category: group.category,
        members: group.members,
        dailyLimitMinutes: group.dailyLimitMinutes,
        limitEnabled: group.limitEnabled,
        updatedAt: args.updatedAt,
      });
    }

    const versionValue = { userId, collection: "targetGroups" as const, updatedAt: args.updatedAt };
    if (version) await ctx.db.patch(version._id, versionValue);
    else await ctx.db.insert("syncVersions", versionValue);

    return { applied: true, updatedAt: args.updatedAt, groupCount: canonical.length };
  },
});
