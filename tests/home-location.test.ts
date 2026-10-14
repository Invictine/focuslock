import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");
const home = { label: "Home", latitude: 28.6139, longitude: 77.209, radiusMeters: 150 };

describe("saved home location storage", () => {
  it("requires authentication and isolates the saved place by account", async () => {
    const t = convexTest(schema, modules);
    await expect(t.query(api.homeLocation.getSaved, {})).rejects.toThrow("Not authenticated");
    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    expect(await alice.query(api.homeLocation.getSaved, {})).toEqual({ revision: 0, place: null, updatedAt: 0 });
    expect(await alice.mutation(api.homeLocation.saveSaved, { expectedRevision: 0, place: home, updatedAt: 10 }))
      .toEqual({ applied: true, revision: 1 });
    expect(await bob.query(api.homeLocation.getSaved, {})).toEqual({ revision: 0, place: null, updatedAt: 0 });
    expect(await t.withIdentity({ subject: "alice", tokenIdentifier: "new-session" })
      .query(api.homeLocation.getSaved, {})).toEqual({ revision: 1, place: home, updatedAt: 10 });
  });

  it("rejects stale revisions and retains a deletion tombstone", async () => {
    const alice = convexTest(schema, modules).withIdentity({ subject: "alice" });
    expect(await alice.mutation(api.homeLocation.saveSaved, { expectedRevision: 0, place: home, updatedAt: 10 }))
      .toEqual({ applied: true, revision: 1 });
    expect(await alice.mutation(api.homeLocation.saveSaved, { expectedRevision: 0, place: null, updatedAt: 20 }))
      .toEqual({ applied: false, revision: 1 });
    expect(await alice.mutation(api.homeLocation.saveSaved, { expectedRevision: 1, place: null, updatedAt: 20 }))
      .toEqual({ applied: true, revision: 2 });
    expect(await alice.query(api.homeLocation.getSaved, {})).toEqual({ revision: 2, place: null, updatedAt: 20 });
    expect(await alice.mutation(api.homeLocation.saveSaved, { expectedRevision: 1, place: home, updatedAt: 30 }))
      .toEqual({ applied: false, revision: 2 });
  });

  it("validates coordinates, radius, label and revision", async () => {
    const alice = convexTest(schema, modules).withIdentity({ subject: "alice" });
    for (const expectedRevision of [-1, 0.5, Number.POSITIVE_INFINITY]) {
      await expect(alice.mutation(api.homeLocation.saveSaved, { expectedRevision, place: home, updatedAt: 1 })).rejects.toThrow();
    }
    for (const place of [
      { ...home, label: " " }, { ...home, label: "x".repeat(121) },
      { ...home, latitude: 90.01 }, { ...home, longitude: -180.01 },
      { ...home, radiusMeters: 24.9 }, { ...home, radiusMeters: 5_001 },
      { ...home, latitude: Number.NaN }, { ...home, radiusMeters: Number.POSITIVE_INFINITY },
    ]) {
      await expect(alice.mutation(api.homeLocation.saveSaved, { expectedRevision: 0, place, updatedAt: 1 })).rejects.toThrow();
    }
    await expect(alice.mutation(api.homeLocation.saveSaved, { expectedRevision: 0, place: home, updatedAt: -1 })).rejects.toThrow();
  });
});
