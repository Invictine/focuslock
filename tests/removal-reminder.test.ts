/// <reference types="vite/client" />
import { createHash } from "node:crypto";
import { convexTest } from "convex-test";
import { describe, expect, it } from "vitest";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");

describe("account-shared removal reminder", () => {
  it("defaults disabled, isolates accounts, and rejects stale clocks and revisions", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    await expect(t.query(api.removalReminder.getState, {})).rejects.toThrow("Not authenticated");
    expect(await alice.query(api.removalReminder.getState, {})).toEqual({
      revision: 0, enabled: false, enabledUpdatedAt: 0, mediaUpdatedAt: 0, media: null,
    });
    expect(await alice.mutation(api.removalReminder.saveEnabled, {
      expectedRevision: 0, enabled: true, updatedAt: 500,
    })).toMatchObject({ applied: true, state: { revision: 1, enabled: true, enabledUpdatedAt: 500 } });
    expect(await alice.mutation(api.removalReminder.saveEnabled, {
      expectedRevision: 1, enabled: false, updatedAt: 400,
    })).toMatchObject({ applied: false, state: { revision: 1, enabled: true } });
    expect(await alice.mutation(api.removalReminder.saveEnabled, {
      expectedRevision: 0, enabled: false, updatedAt: 600,
    })).toMatchObject({ applied: false, state: { revision: 1, enabled: true } });
    expect(await bob.query(api.removalReminder.getState, {})).toEqual({
      revision: 0, enabled: false, enabledUpdatedAt: 0, mediaUpdatedAt: 0, media: null,
    });
  });

  it("authenticates bounded binary uploads, validates hashes, deduplicates, isolates downloads, and retains clear tombstones", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    const bytes = new TextEncoder().encode("private reminder audio");
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    const headers = {
      "content-type": "audio/webm",
      "x-media-sha256": sha256,
      "x-media-name": encodeURIComponent("reminder.webm"),
      "x-expected-revision": "0",
      "x-updated-at": "100",
    };
    expect((await t.fetch("/removal-reminder/media", { method: "POST", headers, body: bytes })).status).toBe(401);
    const badHash = await alice.fetch("/removal-reminder/media", {
      method: "POST", headers: { ...headers, "x-media-sha256": "0".repeat(64) }, body: bytes,
    });
    expect(badHash.status).toBe(400);
    const uploaded = await alice.fetch("/removal-reminder/media", { method: "POST", headers, body: bytes });
    expect(uploaded.status).toBe(200);
    const firstBody = await uploaded.json();
    expect(firstBody).toMatchObject({ applied: true, state: {
      revision: 1, mediaUpdatedAt: 100,
      media: { sha256, mimeType: "audio/webm", name: "reminder.webm", bytes: bytes.byteLength },
    } });
    expect(firstBody.state.storageId).toBeUndefined();
    expect(await t.run((ctx) => ctx.db.system.query("_storage").collect())).toHaveLength(1);

    const staleUpload = await alice.fetch("/removal-reminder/media", {
      method: "POST", headers, body: bytes,
    });
    expect(staleUpload.status).toBe(409);
    expect(await t.run((ctx) => ctx.db.system.query("_storage").collect())).toHaveLength(1);

    const deduplicated = await alice.fetch("/removal-reminder/media", {
      method: "POST", headers: { ...headers, "x-expected-revision": "1", "x-updated-at": "101" }, body: bytes,
    });
    expect(deduplicated.status).toBe(200);
    expect(await deduplicated.json()).toMatchObject({ applied: true, state: { revision: 2 } });
    expect(await t.run((ctx) => ctx.db.system.query("_storage").collect())).toHaveLength(1);
    expect(await alice.mutation(api.removalReminder.touchMedia, {
      expectedRevision: 2, sha256, updatedAt: 102,
    })).toMatchObject({ applied: true, state: { revision: 3, mediaUpdatedAt: 102, media: { sha256 } } });
    expect(await t.run((ctx) => ctx.db.system.query("_storage").collect())).toHaveLength(1);
    expect(await alice.mutation(api.removalReminder.touchMedia, {
      expectedRevision: 3, sha256: "f".repeat(64), updatedAt: 103,
    })).toMatchObject({ applied: false, state: { revision: 3, mediaUpdatedAt: 102 } });
    expect(await alice.mutation(api.removalReminder.clearMedia, { expectedRevision: 3, updatedAt: 101 }))
      .toMatchObject({ applied: false, state: { revision: 3, mediaUpdatedAt: 102, media: { sha256 } } });
    const downloaded = await alice.fetch(`/removal-reminder/media?sha256=${sha256}`);
    expect(downloaded.status).toBe(200);
    expect(downloaded.headers.get("etag")).toBe(`"${sha256}"`);
    expect(downloaded.headers.get("cache-control")).toBe("private, no-store");
    expect(new Uint8Array(await downloaded.arrayBuffer())).toEqual(bytes);
    const notModified = await alice.fetch(`/removal-reminder/media?sha256=${sha256}`, {
      headers: { "if-none-match": `"${sha256}"` },
    });
    expect(notModified.status).toBe(304);
    expect(notModified.headers.get("etag")).toBe(`"${sha256}"`);
    expect((await bob.fetch(`/removal-reminder/media?sha256=${sha256}`)).status).toBe(404);

    const replacementBytes = new TextEncoder().encode("replacement reminder video");
    const replacementHash = createHash("sha256").update(replacementBytes).digest("hex");
    const replaced = await alice.fetch("/removal-reminder/media", {
      method: "POST",
      headers: {
        "content-type": "video/mp4", "x-media-sha256": replacementHash,
        "x-media-name": encodeURIComponent("reminder.mp4"), "x-expected-revision": "3", "x-updated-at": "103",
      },
      body: replacementBytes,
    });
    expect(replaced.status).toBe(200);
    expect(await t.run((ctx) => ctx.db.system.query("_storage").collect())).toHaveLength(1);
    expect((await alice.fetch(`/removal-reminder/media?sha256=${sha256}`)).status).toBe(404);
    expect(new Uint8Array(await (await alice.fetch(`/removal-reminder/media?sha256=${replacementHash}`)).arrayBuffer()))
      .toEqual(replacementBytes);

    expect(await alice.mutation(api.removalReminder.clearMedia, { expectedRevision: 4, updatedAt: 104 }))
      .toMatchObject({ applied: true, state: { revision: 5, mediaUpdatedAt: 104, media: null } });
    expect(await t.run((ctx) => ctx.db.system.query("_storage").collect())).toHaveLength(0);
    expect(await alice.mutation(api.removalReminder.saveEnabled, { expectedRevision: 5, enabled: true, updatedAt: 99 }))
      .toMatchObject({ applied: true, state: { revision: 6, enabled: true, mediaUpdatedAt: 104 } });
    expect((await alice.fetch(`/removal-reminder/media?sha256=${sha256}`)).status).toBe(404);
    expect(await alice.mutation(api.removalReminder.clearMedia, { expectedRevision: 6, updatedAt: 103 }))
      .toMatchObject({ applied: false, state: { revision: 6, mediaUpdatedAt: 104, media: null } });
  });

  it("rejects uploads beyond 12 MiB before storing them", async () => {
    const t = convexTest(schema, modules).withIdentity({ subject: "alice" });
    const body = new Uint8Array(12 * 1024 * 1024 + 1);
    const response = await t.fetch("/removal-reminder/media", {
      method: "POST",
      headers: {
        "content-type": "audio/webm",
        "x-media-sha256": "0".repeat(64),
        "x-media-name": "reminder.webm",
        "x-expected-revision": "0",
        "x-updated-at": "1",
      },
      body,
    });
    expect(response.status).toBe(413);
    expect(await t.query(api.removalReminder.getState, {})).toMatchObject({ revision: 0, media: null });
  });
});
