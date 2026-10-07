/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const linked = {
  accessToken: "access-token-1",
  refreshToken: "refresh-token-1",
  expiresAt: 1_900_000_000_000,
  userName: "Aniru",
};

describe("TickTick connection storage", () => {
  it("requires authentication, isolates subjects, and restores for a new session", async () => {
    const t = convexTest(schema, modules);
    await expect(t.query(api.ticktick.getConnection, {})).rejects.toThrow("Not authenticated");
    await expect(t.mutation(api.ticktick.saveConnection, {
      expectedRevision: 0, connection: linked,
    })).rejects.toThrow("Not authenticated");

    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    expect(await alice.query(api.ticktick.getConnection, {})).toEqual({ revision: 0, connection: null });
    expect(await alice.mutation(api.ticktick.saveConnection, { expectedRevision: 0, connection: linked }))
      .toEqual({ applied: true, revision: 1 });
    expect(await bob.query(api.ticktick.getConnection, {})).toEqual({ revision: 0, connection: null });

    const restored = t.withIdentity({ subject: "alice", tokenIdentifier: "another-session" });
    expect(await restored.query(api.ticktick.getConnection, {})).toEqual({ revision: 1, connection: linked });
    expect(await restored.query(api.focus.getSnapshot, {})).not.toHaveProperty("ticktickConnection");
    expect(await restored.query(api.focus.getDashboard, {})).not.toHaveProperty("ticktickConnection");
  });

  it("updates refresh credentials using revision checks", async () => {
    const alice = convexTest(schema, modules).withIdentity({ subject: "alice" });
    await alice.mutation(api.ticktick.saveConnection, { expectedRevision: 0, connection: linked });

    const refreshed = { ...linked, accessToken: "access-token-2", refreshToken: "refresh-token-2" };
    expect(await alice.mutation(api.ticktick.saveConnection, { expectedRevision: 1, connection: refreshed }))
      .toEqual({ applied: true, revision: 2 });
    expect(await alice.query(api.ticktick.getConnection, {})).toEqual({ revision: 2, connection: refreshed });
  });

  it("retains disconnect tombstones and rejects stale saves that would resurrect credentials", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await alice.mutation(api.ticktick.saveConnection, { expectedRevision: 0, connection: linked });
    expect(await alice.mutation(api.ticktick.saveConnection, { expectedRevision: 1, connection: null }))
      .toEqual({ applied: true, revision: 2 });
    expect(await alice.query(api.ticktick.getConnection, {})).toEqual({ revision: 2, connection: null });

    expect(await alice.mutation(api.ticktick.saveConnection, { expectedRevision: 1, connection: linked }))
      .toEqual({ applied: false, revision: 2 });
    expect(await alice.query(api.ticktick.getConnection, {})).toEqual({ revision: 2, connection: null });
    expect(await t.run((ctx) => ctx.db.query("ticktickConnections").collect())).toHaveLength(1);
  });

  it("rejects invalid revisions, credentials, names, and expiry values", async () => {
    const alice = convexTest(schema, modules).withIdentity({ subject: "alice" });
    for (const expectedRevision of [-1, 0.5, Number.POSITIVE_INFINITY]) {
      await expect(alice.mutation(api.ticktick.saveConnection, { expectedRevision, connection: linked }))
        .rejects.toThrow();
    }

    const invalidConnections = [
      { ...linked, accessToken: "   " },
      { ...linked, accessToken: "x".repeat(16_385) },
      { ...linked, refreshToken: " " },
      { ...linked, refreshToken: "x".repeat(16_385) },
      { ...linked, expiresAt: -1 },
      { ...linked, expiresAt: Number.POSITIVE_INFINITY },
      { ...linked, userName: " " },
      { ...linked, userName: "x".repeat(257) },
    ];
    for (const connection of invalidConnections) {
      await expect(alice.mutation(api.ticktick.saveConnection, { expectedRevision: 0, connection }))
        .rejects.toThrow();
    }
    expect(await alice.query(api.ticktick.getConnection, {})).toEqual({ revision: 0, connection: null });
  });
});
