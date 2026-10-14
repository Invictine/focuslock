import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const base = {
  cooldownMinutes: 2,
  preventUninstall: true,
  blockDeviceSettings: true,
  blockRecents: false,
  blockSplitScreen: false,
  blockReinstalledApps: true,
};

async function beginCommitment(t: ReturnType<typeof convexTest>, userId: string, sessionId = "session-1", endsAt = Date.now() + 600_000) {
  await t.run(async (ctx) => {
    await ctx.db.insert("userPrefs", {
      userId,
      strictMode: true,
      strictEndsAt: endsAt,
      strictSessionId: sessionId,
      weeklyReport: true,
      updatedAt: Date.now(),
    });
  });
  return endsAt;
}

describe("Strict access configuration and account actions", () => {
  it("requires auth and keeps config, PIN credentials, and revisions isolated by account", async () => {
    const t = convexTest(schema, modules);
    await expect(t.query(api.strictAccess.getConfig, {})).rejects.toThrow("Not authenticated");
    await expect(t.action(api.strictAccess.configure, { ...base, method: "timer", expectedRevision: 0 }))
      .rejects.toThrow("Not authenticated");
    await expect(t.action(api.strictAccess.unlock, { strictSessionId: "session", strictEndsAt: Date.now() + 1000 }))
      .rejects.toThrow("Not authenticated");

    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    const saved = await alice.action(api.strictAccess.configure, {
      ...base, method: "pin", pin: "482913", expectedRevision: 0,
    });
    expect(saved).toMatchObject({ method: "pin", hasPin: true, revision: 1 });
    expect(saved).not.toHaveProperty("pinHash");
    expect(saved).not.toHaveProperty("pinSalt");
    expect(await bob.query(api.strictAccess.getConfig, {})).toBeNull();
    expect(await alice.query(api.strictAccess.getConfig, {})).toEqual(saved);

    const stored = await t.run((ctx) => ctx.db.query("strictAccess").withIndex("by_user", q => q.eq("userId", "alice")).first());
    expect(stored?.pinHash).toMatch(/^[0-9a-f]{64}$/);
    expect(stored?.pinSalt).toMatch(/^[0-9a-f]{48}$/);
    expect(stored?.pinHash).not.toBe("482913");
    await expect(alice.action(api.strictAccess.configure, {
      ...base, method: "timer", expectedRevision: 0,
    })).rejects.toThrow("changed on another device");
    expect(await t.withIdentity({ subject: "alice", tokenIdentifier: "fresh-session" })
      .query(api.strictAccess.getConfig, {})).toMatchObject({ method: "pin", revision: 1, hasPin: true });
  });

  it("validates methods, cooldown range, PIN shape, guardian setup, and freezes active settings", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    for (const cooldownMinutes of [1, 11, 2.5, Number.POSITIVE_INFINITY]) {
      await expect(alice.action(api.strictAccess.configure, {
        ...base, cooldownMinutes, method: "timer", expectedRevision: 0,
      })).rejects.toThrow();
    }
    await expect(alice.action(api.strictAccess.configure, {
      ...base, method: "not-a-method", expectedRevision: 0,
    } as never)).rejects.toThrow();
    for (const pin of ["123", "1234567890123", "abcd", " 1234"]) {
      await expect(alice.action(api.strictAccess.configure, {
        ...base, method: "pin", pin, expectedRevision: 0,
      })).rejects.toThrow("4 to 12 digits");
    }
    await expect(alice.action(api.strictAccess.configure, {
      ...base, method: "approval", expectedRevision: 0,
    })).rejects.toThrow("approval contact");

    await t.run(async (ctx) => {
      await ctx.db.insert("strictGuardians", {
        userId: "alice", email: "guardian@example.com", configuredAt: Date.now(), updatedAt: Date.now(),
      });
    });
    await alice.action(api.strictAccess.configure, { ...base, method: "approval", expectedRevision: 0 });
    const now = Date.now();
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", {
        userId: "alice", strictMode: true, strictEndsAt: now + 600_000,
        strictSessionId: "active-1", weeklyReport: true, updatedAt: now,
      });
    });
    await expect(alice.action(api.strictAccess.configure, {
      ...base, method: "timer", expectedRevision: 1,
    })).rejects.toThrow("cannot change during Strict Mode");
    expect(await alice.query(api.strictAccess.getConfig, {})).toMatchObject({ method: "approval", revision: 1 });
  });

  it("rate-limits PIN failures, verifies the derived PIN, and releases only the exact active session", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await alice.action(api.strictAccess.configure, {
      ...base, method: "pin", pin: "482913", expectedRevision: 0,
    });
    const endsAt = await beginCommitment(t, "alice");
    for (let attempt = 0; attempt < 5; attempt++) {
      const result = await alice.action(api.strictAccess.unlock, {
        strictSessionId: "session-1", strictEndsAt: endsAt, pin: "000000",
      });
      expect(result.released).toBe(false);
      if (attempt < 4) expect(result.reason).toBe("Incorrect PIN.");
    }
    let config = await t.run((ctx) => ctx.db.query("strictAccess").withIndex("by_user", q => q.eq("userId", "alice")).first());
    expect(config?.pinFailures).toBe(0);
    expect(config?.pinBlockedUntil).toBeGreaterThan(Date.now());
    expect((await alice.action(api.strictAccess.unlock, {
      strictSessionId: "session-1", strictEndsAt: endsAt, pin: "482913",
    })).reason).toContain("Wait five minutes");

    await t.run(async (ctx) => {
      const row = await ctx.db.query("strictAccess").withIndex("by_user", q => q.eq("userId", "alice")).first();
      if (row) await ctx.db.patch(row._id, { pinBlockedUntil: 0 });
    });
    expect((await alice.action(api.strictAccess.unlock, {
      strictSessionId: "wrong-session", strictEndsAt: endsAt, pin: "482913",
    })).released).toBe(false);
    expect((await alice.action(api.strictAccess.unlock, {
      strictSessionId: "session-1", strictEndsAt: endsAt - 1, pin: "482913",
    })).released).toBe(false);
    expect((await alice.action(api.strictAccess.unlock, {
      strictSessionId: "session-1", strictEndsAt: endsAt, pin: "482913",
    })).released).toBe(true);
    const prefs = await alice.query(api.focus.getSnapshot, {});
    expect(prefs.prefs).toMatchObject({ strictMode: false, strictApprovedSessionId: "session-1", strictApprovedEndsAt: endsAt });
  });

  it("anchors cooldown to the exact commitment session and releases only after its server deadline", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await alice.action(api.strictAccess.configure, { ...base, method: "cooldown", cooldownMinutes: 2, expectedRevision: 0 });
    const endsAt = await beginCommitment(t, "alice");
    const first = await alice.action(api.strictAccess.unlock, { strictSessionId: "session-1", strictEndsAt: endsAt });
    expect(first.released).toBe(false);
    const anchor = await t.run((ctx) => ctx.db.query("strictAccess").withIndex("by_user", q => q.eq("userId", "alice")).first());
    expect(anchor?.unlockRequestedAt).toBeGreaterThan(0);
    expect(anchor?.unlockSessionId).toBe("session-1");
    expect(anchor?.unlockEndsAt).toBe(endsAt);
    const retry = await alice.action(api.strictAccess.unlock, { strictSessionId: "session-1", strictEndsAt: endsAt });
    expect(retry.availableAt).toBe(first.availableAt);

    const secondEndsAt = endsAt + 120_000;
    await t.run(async (ctx) => {
      const prefs = await ctx.db.query("userPrefs").withIndex("by_user", q => q.eq("userId", "alice")).first();
      if (prefs) await ctx.db.patch(prefs._id, { strictSessionId: "session-2", strictEndsAt: secondEndsAt });
    });
    const secondSession = await alice.action(api.strictAccess.unlock, {
      strictSessionId: "session-2", strictEndsAt: secondEndsAt,
    });
    expect(secondSession.released).toBe(false);
    expect(secondSession.availableAt).toBeGreaterThan(first.availableAt);
    const resetAnchor = await t.run((ctx) => ctx.db.query("strictAccess").withIndex("by_user", q => q.eq("userId", "alice")).first());
    expect(resetAnchor?.unlockSessionId).toBe("session-2");
    expect(resetAnchor?.unlockEndsAt).toBe(secondEndsAt);

    await t.run(async (ctx) => {
      const row = await ctx.db.query("strictAccess").withIndex("by_user", q => q.eq("userId", "alice")).first();
      if (row) await ctx.db.patch(row._id, { unlockRequestedAt: Date.now() - 121_000 });
    });
    expect((await alice.action(api.strictAccess.unlock, {
      strictSessionId: "session-2", strictEndsAt: secondEndsAt,
    })).released).toBe(true);

    const other = convexTest(schema, modules);
    const bob = other.withIdentity({ subject: "bob" });
    await bob.action(api.strictAccess.configure, { ...base, method: "cooldown", expectedRevision: 0 });
    const bobEndsAt = await beginCommitment(other, "bob", "bob-session");
    await bob.action(api.strictAccess.unlock, { strictSessionId: "bob-session", strictEndsAt: bobEndsAt });
    const bobAnchor = await other.run((ctx) => ctx.db.query("strictAccess").withIndex("by_user", q => q.eq("userId", "bob")).first());
    expect(bobAnchor?.unlockSessionId).toBe("bob-session");
  });

  it("does not let charger, timer, schedule, or approval paths release by skipping their condition", async () => {
    for (const method of ["timer", "schedules", "charger", "approval"] as const) {
      const t = convexTest(schema, modules);
      const alice = t.withIdentity({ subject: "alice" });
      if (method === "approval") {
        await t.run(async (ctx) => ctx.db.insert("strictGuardians", {
          userId: "alice", email: "guardian@example.com", configuredAt: Date.now(), updatedAt: Date.now(),
        }));
      }
      await alice.action(api.strictAccess.configure, { ...base, method, expectedRevision: 0 });
      const endsAt = await beginCommitment(t, "alice");
      const denied = await alice.action(api.strictAccess.unlock, {
        strictSessionId: "session-1", strictEndsAt: endsAt, charging: false,
      });
      expect(denied.released).toBe(false);
      if (method === "charger") expect(denied.reason).toContain("charger");
      if (method === "approval") expect(denied.reason).toContain("approval contact");
      if (method === "timer" || method === "schedules") expect(denied.reason).toContain("Wait until");
      if (method === "charger") {
        const connected = await alice.action(api.strictAccess.unlock, {
          strictSessionId: "session-1", strictEndsAt: endsAt, charging: true,
        });
        expect(connected.released).toBe(true);
      }
    }
  });
});
