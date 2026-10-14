/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it, vi } from "vitest";
import schema from "../convex/schema";
import { api, internal } from "../convex/_generated/api";
const modules = import.meta.glob("../convex/**/*.ts");
const defaults = { cooldownMinutes: 2, preventUninstall: true, blockDeviceSettings: false,
  blockRecents: false, blockSplitScreen: true, blockReinstalledApps: true };
async function setup(method: "timer" | "pin" | "charger" | "cooldown" | "approval") {
  const t = convexTest(schema, modules);
  const now = Date.now(), endsAt = now + 3_600_000;
  await t.run(async ctx => {
    await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: endsAt, strictSessionId: "one", weeklyReport: true, updatedAt: now });
    await ctx.db.insert("strictAccess", { userId: "alice", method, ...defaults, revision: 1, pinHash: "hashed", pinSalt: "salt" });
  });
  const release = (extra = {}) => t.mutation(internal.strictAccess.release, { userId: "alice", strictSessionId: "one", strictEndsAt: endsAt, charging: false, ...extra });
  return { t, release, endsAt, now };
}
describe("Strict Mode access transactions", () => {
  it("requires authentication and isolates accounts without exposing PIN credentials", async () => {
    const { t } = await setup("pin");
    await expect(t.query(api.strictAccess.getConfig, {})).rejects.toThrow("Not authenticated");
    expect(await t.withIdentity({ subject: "bob" }).query(api.strictAccess.getConfig, {})).toBeNull();
    const config = await t.withIdentity({ subject: "alice" }).query(api.strictAccess.getConfig, {});
    expect(config).toMatchObject({ method: "pin", hasPin: true });
    expect(config).not.toHaveProperty("pinHash");
    expect(config).not.toHaveProperty("pinSalt");
  });
  it("freezes methods and protections during commitments and checks revisions afterward", async () => {
    const { t } = await setup("pin");
    const args = { userId: "alice", method: "charger" as const, ...defaults, expectedRevision: 1 };
    await expect(t.mutation(internal.strictAccess.saveConfig, args)).rejects.toThrow("cannot change");
    await t.run(async ctx => { const prefs = (await ctx.db.query("userPrefs").first())!; await ctx.db.patch(prefs._id, { strictMode: false, strictEndsAt: 0 }); });
    await expect(t.mutation(internal.strictAccess.saveConfig, { ...args, expectedRevision: 0 })).rejects.toThrow("another device");
    expect(await t.mutation(internal.strictAccess.saveConfig, args)).toMatchObject({ method: "charger", revision: 2 });
  });
  it("persists PIN failures and lockout instead of rolling them back", async () => {
    const { t, release } = await setup("pin");
    for (let attempt = 0; attempt < 5; attempt++) expect(await release({ verifiedHash: "wrong" })).toMatchObject({ released: false });
    expect(await release({ verifiedHash: "hashed" })).toMatchObject({ released: false, reason: expect.stringContaining("five minutes") });
    expect((await t.run(ctx => ctx.db.query("strictAccess").first()))?.pinBlockedUntil).toBeGreaterThan(Date.now());
  });
  it("checks charger and exact session, then records a replay-safe cross-device release", async () => {
    const { t, release, endsAt } = await setup("charger");
    expect(await release()).toMatchObject({ released: false });
    expect(await release({ charging: true, strictSessionId: "other" })).toMatchObject({ released: false });
    expect(await release({ charging: true })).toMatchObject({ released: true, strictApprovedEndsAt: endsAt });
    expect(await release({ charging: true })).toMatchObject({ released: false });
    expect(await t.run(ctx => ctx.db.query("userPrefs").first())).toMatchObject({ strictMode: false, strictApprovedSessionId: "one" });
    await expect(t.withIdentity({ subject: "alice" }).mutation(api.focus.savePrefs,
      { strictMode: true, strictEndsAt: endsAt, updatedAt: Date.now() + 1 })).rejects.toThrow("approved for release");
  });
  it("anchors cooldown to the commitment and cannot release before waiting", async () => {
    const { release, now } = await setup("cooldown");
    vi.spyOn(Date, "now").mockReturnValue(now);
    try {
      expect(await release()).toMatchObject({ released: false, availableAt: now + 120_000 });
      vi.mocked(Date.now).mockReturnValue(now + 119_999);
      expect(await release()).toMatchObject({ released: false });
      vi.mocked(Date.now).mockReturnValue(now + 120_000);
      expect(await release()).toMatchObject({ released: true });
    } finally { vi.restoreAllMocks(); }
  });
  it.each(["timer", "approval"] as const)("rejects generic early unlock for %s", async method => {
    const { release } = await setup(method);
    expect(await release({ charging: true, verifiedHash: "hashed" })).toMatchObject({ released: false });
  });
});
