/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const targets = [
  { targetKind: "android" as const, targetKey: "com.example.video", targetLabel: "Video" },
  { targetKind: "windows" as const, targetKey: "video player.exe", targetLabel: "Video Player" },
  { targetKind: "website" as const, targetKey: "video.example", targetLabel: "Video website" },
];

describe("permanent account commitments", () => {
  it("restores apps and sites in a fresh session after ordinary boundary reset", async () => {
    const t = convexTest(schema, modules);
    const account = t.withIdentity({ subject: "alice" });
    await account.mutation(api.focus.addPermanentBlocks, { targets });
    // Older clients, fresh installations and ordinary boundary resets have no
    // authority to remove the independent permanent collection.
    await account.mutation(api.focus.saveBlockedApps, { apps: [], updatedAt: 500 });
    await account.mutation(api.focus.saveBlockedWebsites, { sites: [], updatedAt: 500 });
    await account.mutation(api.focus.setBlockedWebsite, {
      domain: "video.example", displayName: "Video", isBlocked: false, category: "Web", updatedAt: 600,
    });
    const reinstalled = t.withIdentity({ subject: "alice", tokenIdentifier: "fresh-install" });
    expect((await reinstalled.query(api.focus.getSnapshot, {})).permanentBlocks).toEqual(targets);
    expect((await reinstalled.query(api.focus.getConfiguration, {})).permanentBlocks).toEqual(targets);
    expect((await reinstalled.query(api.focus.getSyncPulse, { sitesUpdatedAt: -1, prefsUpdatedAt: -1 })).permanentBlocks)
      .toEqual(targets);
  });

  it("retries idempotently, merges installations, and keeps account ownership", async () => {
    const t = convexTest(schema, modules);
    await expect(t.mutation(api.focus.addPermanentBlocks, { targets })).rejects.toThrow("Not authenticated");
    const account = t.withIdentity({ subject: "alice" });
    expect(await account.mutation(api.focus.addPermanentBlocks, { targets: targets.slice(0, 1) }))
      .toEqual({ applied: true, added: 1 });
    expect(await account.mutation(api.focus.addPermanentBlocks, { targets }))
      .toEqual({ applied: true, added: 2 });
    expect(await account.mutation(api.focus.addPermanentBlocks, { targets: [] }))
      .toEqual({ applied: true, added: 0 });
    expect(await account.mutation(api.focus.addPermanentBlocks, { targets }))
      .toEqual({ applied: true, added: 0 });
    expect((await t.withIdentity({ subject: "bob" }).query(api.focus.getSnapshot, {})).permanentBlocks).toEqual([]);
    expect((await account.query(api.focus.getSnapshot, {})).permanentBlocks).toEqual(targets);
  });

  it("delivers new permanence even with unchanged boundary and policy versions", async () => {
    const account = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const initial = await account.query(api.focus.getSnapshot, {});
    const policy = await account.query(api.focus.getSyncPulse, {
      sitesUpdatedAt: 0, prefsUpdatedAt: 0, knownPolicyVersion: "",
    });
    await account.mutation(api.focus.addPermanentBlocks, { targets });
    const restored = await account.query(api.focus.getSnapshot, { knownVersion: initial.version });
    expect(restored.unchanged).toBe(true);
    expect(restored.permanentBlocks).toEqual(targets);
    const update = await account.query(api.focus.getSyncPulse, {
      sitesUpdatedAt: 0, prefsUpdatedAt: 0, knownPolicyVersion: policy.policyVersion,
    });
    expect(update.policy).toBeUndefined();
    expect(update.permanentBlocks).toEqual(targets);
  });

  it("normalizes identities and rejects invalid batches atomically", async () => {
    const account = convexTest(schema, modules).withIdentity({ subject: "alice" });
    expect(await account.mutation(api.focus.addPermanentBlocks, { targets: [
      { targetKind: "website", targetKey: "https://WWW.Video.Example/watch" },
      { targetKind: "website", targetKey: "video.example" },
    ] })).toEqual({ applied: true, added: 1 });
    await expect(account.mutation(api.focus.addPermanentBlocks, { targets: [
      targets[0], { targetKind: "website", targetKey: "https://user:pass@invalid.example" },
    ] })).rejects.toThrow("Invalid permanent website");
    expect((await account.query(api.focus.getSnapshot, {})).permanentBlocks)
      .toEqual([{ targetKind: "website", targetKey: "video.example", targetLabel: "video.example" }]);
  });
});
