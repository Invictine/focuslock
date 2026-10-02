import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");

const video = {
  groupId: "video",
  name: "Video",
  members: [
    { targetKind: "app" as const, targetKey: "com.video", targetLabel: "Video app" },
    { targetKind: "website" as const, targetKey: "video.example", targetLabel: "Video site" },
  ],
};

const games = {
  groupId: "games",
  name: "Games",
  members: [
    { targetKind: "app" as const, targetKey: "com.game.one", targetLabel: "Game one" },
    { targetKind: "app" as const, targetKey: "com.game.two", targetLabel: "Game two" },
  ],
};

async function storedGroups(t: ReturnType<typeof convexTest>) {
  return await t.run(async (ctx) => {
    const rows = await ctx.db.query("targetGroups").collect();
    return rows.sort((a, b) => a.groupId.localeCompare(b.groupId));
  });
}

describe("target group collection storage", () => {
  it("preserves row identity and timestamps when content is unchanged", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.groups.saveGroups, { groups: [video], updatedAt: 100 });
    const before = await storedGroups(t);

    await t.mutation(api.groups.saveGroups, { groups: [video], updatedAt: 200 });
    const after = await storedGroups(t);

    expect(after).toHaveLength(1);
    expect(after[0]._id).toBe(before[0]._id);
    expect(after[0].updatedAt).toBe(before[0].updatedAt);
    expect((await t.query(api.groups.groupsState, {})).updatedAt).toBe(200);
  });

  it("updates only the changed group and keeps other rows stable", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.groups.saveGroups, { groups: [video, games], updatedAt: 100 });
    const before = await storedGroups(t);
    const beforeById = new Map(before.map((row) => [row.groupId, row]));

    await t.mutation(api.groups.saveGroups, {
      groups: [{ ...video, name: "Video and streaming" }, games],
      updatedAt: 200,
    });
    const after = await storedGroups(t);
    const afterById = new Map(after.map((row) => [row.groupId, row]));

    expect(afterById.get("video")).toMatchObject({
      _id: beforeById.get("video")!._id,
      name: "Video and streaming",
      updatedAt: 200,
    });
    expect(afterById.get("games")).toMatchObject({
      _id: beforeById.get("games")!._id,
      updatedAt: beforeById.get("games")!.updatedAt,
    });
  });

  it("prevents a stale deletion from removing groups", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.groups.saveGroups, { groups: [video], updatedAt: 100 });
    const before = await storedGroups(t);

    expect(await t.mutation(api.groups.saveGroups, { groups: [], updatedAt: 99 }))
      .toEqual({ applied: false, updatedAt: 100 });
    const after = await storedGroups(t);
    expect(after).toHaveLength(1);
    expect(after[0]._id).toBe(before[0]._id);
    expect(after[0].groupId).toBe("video");
  });

  it("advances equal-time changed groups so a conditional pull sees them", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.groups.saveGroups, { groups: [video], updatedAt: 100 });
    const initial = await t.query(api.focus.getSnapshot, { knownGroupsUpdatedAt: 0 });
    expect(initial.groupsState?.updatedAt).toBe(100);

    const saved = await t.mutation(api.groups.saveGroups, {
      groups: [{ ...video, name: "Video and streaming" }], updatedAt: 100,
    });
    expect(saved).toMatchObject({ applied: true, updatedAt: 101 });
    const pulled = await t.query(api.focus.getSnapshot, { knownGroupsUpdatedAt: 100 });
    expect(pulled.groupsState).toMatchObject({
      updatedAt: 101,
      groups: [{ groupId: "video", name: "Video and streaming" }],
    });
  });

  it("does not bump a collection clock for an equal-time unchanged retry", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await t.mutation(api.groups.saveGroups, { groups: [video], updatedAt: 100 });
    const before = await storedGroups(t);
    expect(await t.mutation(api.groups.saveGroups, { groups: [video], updatedAt: 100 }))
      .toMatchObject({ applied: true, updatedAt: 100 });
    const after = await storedGroups(t);
    expect(after[0]._id).toBe(before[0]._id);
    expect(after[0].updatedAt).toBe(100);
    expect((await t.query(api.groups.groupsState, {})).updatedAt).toBe(100);
  });
});

describe("other synced collection storage", () => {
  it("preserves blocked-app rows and rejects a stale deletion", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const app = { packageName: "com.example", appName: "Example", isBlocked: true, category: "Work" };
    await t.mutation(api.focus.saveBlockedApps, { apps: [app], updatedAt: 100 });
    const before = await t.run((ctx) => ctx.db.query("blockedApps").collect());
    await t.mutation(api.focus.saveBlockedApps, { apps: [app], updatedAt: 200 });
    expect(await t.mutation(api.focus.saveBlockedApps, { apps: [], updatedAt: 150 }))
      .toEqual({ applied: false, updatedAt: 200 });
    const after = await t.run((ctx) => ctx.db.query("blockedApps").collect());
    expect(after).toHaveLength(1);
    expect(after[0]._id).toBe(before[0]._id);
    expect(after[0].updatedAt).toBe(before[0].updatedAt);
  });

  it("preserves blocked-website rows and rejects a stale deletion", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const site = { domain: "example.com", displayName: "Example", isBlocked: true, category: "Work" };
    await t.mutation(api.focus.saveBlockedWebsites, { sites: [site], updatedAt: 100 });
    const before = await t.run((ctx) => ctx.db.query("blockedWebsites").collect());
    await t.mutation(api.focus.saveBlockedWebsites, { sites: [site], updatedAt: 200 });
    expect(await t.mutation(api.focus.saveBlockedWebsites, { sites: [], updatedAt: 150 }))
      .toEqual({ applied: false, updatedAt: 200 });
    const after = await t.run((ctx) => ctx.db.query("blockedWebsites").collect());
    expect(after).toHaveLength(1);
    expect(after[0]._id).toBe(before[0]._id);
    expect(after[0].updatedAt).toBe(before[0].updatedAt);
  });

  it("bumps equal-time site edits for conditional pulls but leaves no-op clocks alone", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const site = { domain: "example.com", displayName: "Example", isBlocked: true, category: "Work" };
    await t.mutation(api.focus.saveBlockedWebsites, { sites: [site], updatedAt: 100 });
    const initial = await t.query(api.focus.getSnapshot, {});
    const changed = await t.mutation(api.focus.saveBlockedWebsites, {
      sites: [{ ...site, displayName: "Example site" }], updatedAt: 100,
    });
    expect(changed).toMatchObject({ applied: true, updatedAt: 101 });
    const pull = await t.query(api.focus.getSnapshot, { knownVersion: initial.version });
    expect(pull.sitesUpdatedAt).toBe(101);
    expect(pull.sites?.[0].displayName).toBe("Example site");

    expect(await t.mutation(api.focus.saveBlockedWebsites, {
      sites: [{ ...site, displayName: "Example site" }], updatedAt: 101,
    })).toMatchObject({ applied: true, updatedAt: 101 });
    const rows = await t.run((ctx) => ctx.db.query("blockedWebsites").collect());
    expect(rows[0].updatedAt).toBe(101);
    expect((await t.query(api.focus.getSnapshot, {})).sitesUpdatedAt).toBe(101);
  });

  it("preserves app-limit rows and rejects a stale deletion", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const limit = { targetKind: "app", targetKey: "com.example", dailyLimitMinutes: 60 };
    await t.mutation(api.focus.saveAppLimits, { limits: [limit], updatedAt: 100 });
    const before = await t.run((ctx) => ctx.db.query("appLimits").collect());
    await t.mutation(api.focus.saveAppLimits, { limits: [limit], updatedAt: 200 });
    expect(await t.mutation(api.focus.saveAppLimits, { limits: [], updatedAt: 150 }))
      .toEqual({ applied: false, updatedAt: 200 });
    const after = await t.run((ctx) => ctx.db.query("appLimits").collect());
    expect(after).toHaveLength(1);
    expect(after[0]._id).toBe(before[0]._id);
    expect(after[0].updatedAt).toBe(before[0].updatedAt);
  });

  it("preserves schedule rows and rejects a stale deletion", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const schedule = {
      scheduleId: "school", label: "School", targetKind: "all", targetKey: "*",
      days: [1, 2, 3, 4, 5], startMinute: 480, endMinute: 900, isEnabled: true,
    };
    await t.mutation(api.focus.saveSchedules, { schedules: [schedule], updatedAt: 100 });
    const before = await t.run((ctx) => ctx.db.query("blockSchedules").collect());
    await t.mutation(api.focus.saveSchedules, { schedules: [schedule], updatedAt: 200 });
    expect(await t.mutation(api.focus.saveSchedules, { schedules: [], updatedAt: 150 }))
      .toEqual({ applied: false, updatedAt: 200 });
    const after = await t.run((ctx) => ctx.db.query("blockSchedules").collect());
    expect(after).toHaveLength(1);
    expect(after[0]._id).toBe(before[0]._id);
    expect(after[0].updatedAt).toBe(before[0].updatedAt);
  });

  it("rejects empty and duplicate identities and oversized snapshots", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const app = { packageName: "com.example", appName: "Example", isBlocked: true, category: "Work" };
    await expect(t.mutation(api.focus.saveBlockedApps, {
      apps: [{ ...app, packageName: " " }], updatedAt: 100,
    })).rejects.toThrow("identity cannot be empty");
    await expect(t.mutation(api.focus.saveBlockedApps, {
      apps: [app, app], updatedAt: 100,
    })).rejects.toThrow("duplicate identities");
    await expect(t.mutation(api.focus.saveBlockedApps, {
      apps: Array.from({ length: 501 }, (_, index) => ({ ...app, packageName: `com.example.${index}` })),
      updatedAt: 100,
    })).rejects.toThrow("cannot contain more than 500 items");
  });

  it("rejects non-finite collection timestamps", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await expect(t.mutation(api.focus.saveBlockedApps, { apps: [], updatedAt: Number.NaN }))
      .rejects.toThrow();
    await expect(t.mutation(api.groups.saveGroups, { groups: [], updatedAt: Number.POSITIVE_INFINITY }))
      .rejects.toThrow();
  });
});
