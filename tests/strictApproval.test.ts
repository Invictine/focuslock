/// <reference types="vite/client" />
import { convexTest } from "convex-test";
import { describe, expect, it, vi } from "vitest";
import schema from "../convex/schema";
import { api, internal } from "../convex/_generated/api";

const modules = import.meta.glob("../convex/**/*.ts");

describe("strict approval", () => {
  it("requires authentication and freezes guardian during strict mode", async () => {
    const t = convexTest(schema, modules);
    await expect(t.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" })).rejects.toThrow("Not authenticated");
    const alice = t.withIdentity({ subject: "alice" });
    await alice.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" });
    const now = Date.now();
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: now + 10_000, strictSessionId: "session-1", weeklyReport: true, updatedAt: now });
    });
    await expect(alice.mutation(api.strictApproval.configureGuardian, { email: "other@example.com" })).rejects.toThrow("cannot change");
  });

  it("approves one exact session and rejects replay or a different session", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await alice.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" });
    const now = Date.now();
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: now + 10_000, strictSessionId: "session-1", weeklyReport: true, updatedAt: now });
    });
    const hash = "token-hash";
    const endsAt = now + 10_000;
    await t.mutation(internal.strictApproval.createRequest, { userId: "alice", strictSessionId: "session-1", strictEndsAt: endsAt, tokenHash: hash, now });
    await t.mutation(internal.strictApproval.markSent, { requestId: (await t.run((ctx) => ctx.db.query("strictApprovalRequests").first()))!._id, now });
    const approved = await t.mutation(internal.strictApproval.approveToken, { tokenHash: hash, now: now + 100 });
    expect(approved).toMatchObject({ approved: true, strictSessionId: "session-1", strictApprovedEndsAt: endsAt });
    await expect(t.mutation(internal.strictApproval.approveToken, { tokenHash: hash, now: now + 101 })).rejects.toThrow("already been used");
    const prefs = await t.run((ctx) => ctx.db.query("userPrefs").withIndex("by_user", (q) => q.eq("userId", "alice")).first());
    expect(prefs).toMatchObject({ strictMode: false, strictApprovedSessionId: "session-1", strictApprovedEndsAt: endsAt, strictApprovedAt: now + 100 });
  });

  it("rejects expired tokens and does not change permanent blocks", async () => {
    const t = convexTest(schema, modules);
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: 2_000, strictSessionId: "session-1", weeklyReport: true, updatedAt: 1 });
      await ctx.db.insert("strictGuardians", { userId: "alice", email: "guardian@example.com", configuredAt: 1, updatedAt: 1 });
      await ctx.db.insert("strictApprovalRequests", { userId: "alice", strictSessionId: "session-1", strictEndsAt: 2_000, tokenHash: "expired", expiresAt: 500, createdAt: 1, status: "sent" });
      await ctx.db.insert("blockedApps", { userId: "alice", packageName: "com.example", appName: "Example", isBlocked: true, category: "Test", updatedAt: 1 });
    });
    await expect(t.mutation(internal.strictApproval.approveToken, { tokenHash: "expired", now: 600 })).rejects.toThrow("expired");
    const rows = await t.run((ctx) => ctx.db.query("blockedApps").collect());
    expect(rows).toHaveLength(1);
  });

  it("cannot approve a request whose email send failed", async () => {
    const t = convexTest(schema, modules);
    const now = Date.now();
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: now + 60_000, strictSessionId: "session-1", weeklyReport: true, updatedAt: now });
      await ctx.db.insert("strictGuardians", { userId: "alice", email: "guardian@example.com", configuredAt: now, updatedAt: now });
      await ctx.db.insert("strictApprovalRequests", { userId: "alice", strictSessionId: "session-1", strictEndsAt: now + 60_000, tokenHash: "failed", expiresAt: now + 1_000, createdAt: now, status: "failed", failedAt: now, failureReason: "provider unavailable" });
    });
    await expect(t.mutation(internal.strictApproval.approveToken, { tokenHash: "failed", now: now + 1 })).rejects.toThrow("not available");
  });

  it("invalidates an old token when the strict commitment is extended", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const now = Date.now();
    const firstEnd = now + 60_000;
    await alice.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" });
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: firstEnd, strictSessionId: "session-1", weeklyReport: true, updatedAt: now });
      await ctx.db.insert("strictApprovalRequests", { userId: "alice", strictSessionId: "session-1", strictEndsAt: firstEnd, tokenHash: "old-session", expiresAt: now + 30_000, createdAt: now, status: "sent" });
    });
    await alice.mutation(api.focus.savePrefs, { strictMode: true, strictEndsAt: now + 120_000, updatedAt: now + 1 });
    await expect(t.mutation(internal.strictApproval.approveToken, { tokenHash: "old-session", now: now + 2 })).rejects.toThrow("no longer active");
    const state = await alice.query(api.strictApproval.getApprovalState, {});
    expect(state.strictSessionId).not.toBe("session-1");
  });

  it("enforces the request limit across a session extension", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const now = Date.now();
    const firstEnd = now + 60_000;
    await alice.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" });
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: firstEnd, strictSessionId: "session-1", weeklyReport: true, updatedAt: now });
    });
    for (let index = 0; index < 3; index++) {
      await t.mutation(internal.strictApproval.createRequest, { userId: "alice", strictSessionId: "session-1", strictEndsAt: firstEnd, tokenHash: `rate-${index}`, now: now + index });
    }
    await alice.mutation(api.focus.savePrefs, { strictMode: true, strictEndsAt: now + 120_000, updatedAt: now + 10 });
    const prefs = await alice.query(api.strictApproval.getApprovalState, {});
    await expect(t.mutation(internal.strictApproval.createRequest, { userId: "alice", strictSessionId: prefs.strictSessionId!, strictEndsAt: now + 120_000, tokenHash: "rate-new", now: now + 11 })).rejects.toThrow("rate limit");
  });

  it("rejects stale approved restart, but creates a new session for a new commitment", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const now = Date.now();
    const approvedEnd = now + 60_000;
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: false, strictEndsAt: 0, strictSessionId: "old", strictApprovedEndsAt: approvedEnd, strictApprovedAt: now - 1, strictApprovedSessionId: "old", weeklyReport: true, updatedAt: now });
    });
    await expect(alice.mutation(api.focus.savePrefs, { strictMode: true, strictEndsAt: approvedEnd, updatedAt: now + 1 })).rejects.toThrow("approved for release");
    await alice.mutation(api.focus.savePrefs, { strictMode: true, strictEndsAt: now + 120_000, updatedAt: now + 2 });
    const state = await alice.query(api.strictApproval.getApprovalState, {});
    expect(state.strictMode).toBe(true);
    expect(state.strictSessionId).not.toBe("old");
    expect(state.strictApprovedEndsAt).toBeUndefined();
  });

  it("does not allow ordinary savePrefs to disable an active commitment", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const now = Date.now();
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: now + 60_000, strictSessionId: "session-1", weeklyReport: true, updatedAt: now });
    });
    await expect(alice.mutation(api.focus.savePrefs, { strictMode: false, updatedAt: now + 1 })).rejects.toThrow("committed until");
  });

  it("does not accept client-forged approval markers", async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    await expect(alice.mutation(api.focus.savePrefs, {
      updatedAt: Date.now(), strictApprovedAt: Date.now(), strictApprovedEndsAt: Date.now() + 60_000,
    } as any)).rejects.toThrow();
  });

  it("runs the mocked email and HTTP flow without consuming on GET", async () => {
    vi.stubEnv("RESEND_API_KEY", "test-key");
    vi.stubEnv("RESEND_FROM_EMAIL", "FocusLock <test@example.com>");
    vi.stubEnv("STRICT_APPROVAL_REVIEW_URL_BASE", "https://focuslock.test");
    const sent: RequestInit[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_url: string, init: RequestInit) => {
      sent.push(init);
      return new Response("{}", { status: 200 });
    }));
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice", name: "Alice" });
    const now = Date.now();
    const endsAt = now + 60_000;
    await alice.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" });
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: endsAt, strictSessionId: "session-e2e", weeklyReport: true, updatedAt: now });
    });
    const result = await alice.action(api.strictApproval.requestApprovalEmail, { strictSessionId: "session-e2e", strictEndsAt: endsAt });
    expect(result.sent).toBe(true);
    const emailPayload = JSON.parse(String(sent[0].body));
    const reviewUrl = new URL(emailPayload.html.match(/href="([^"]+)"/)![1]);
    const token = reviewUrl.searchParams.get("token")!;
    expect(token).toBeTruthy();
    expect(JSON.stringify(result)).not.toContain(token);
    expect(emailPayload.html).toContain("Alice");

    const getResponse = await t.fetch(`/strict-approval?token=${encodeURIComponent(token)}`);
    expect(getResponse.status).toBe(200);
    expect((await t.run((ctx) => ctx.db.query("strictApprovalRequests").first()))?.status).toBe("sent");

    const postResponse = await t.fetch("/strict-approval", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ token }).toString() });
    expect(postResponse.status).toBe(200);
    expect((await alice.query(api.strictApproval.getApprovalState, {})).strictMode).toBe(false);
    const replay = await t.fetch("/strict-approval", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ token }).toString() });
    expect(replay.status).toBe(400);
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("reports a non-success provider response as unsent", async () => {
    vi.stubEnv("RESEND_API_KEY", "test-key");
    vi.stubEnv("RESEND_FROM_EMAIL", "FocusLock <test@example.com>");
    vi.stubEnv("STRICT_APPROVAL_REVIEW_URL_BASE", "https://focuslock.test");
    vi.stubGlobal("fetch", vi.fn(async () => new Response("no", { status: 503 })));
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: "alice" });
    const now = Date.now();
    await alice.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" });
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: now + 60_000, strictSessionId: "session-fail", weeklyReport: true, updatedAt: now });
    });
    const result = await alice.action(api.strictApproval.requestApprovalEmail, { strictSessionId: "session-fail", strictEndsAt: now + 60_000 });
    expect(result.sent).toBe(false);
    expect(result.reason).toContain("503");
    expect((await t.run((ctx) => ctx.db.query("strictApprovalRequests").first()))?.status).toBe("failed");
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("rejects cross-account session requests and malformed review inputs", async () => {
    const t = convexTest(schema, modules);
    const now = Date.now();
    const alice = t.withIdentity({ subject: "alice" });
    const bob = t.withIdentity({ subject: "bob" });
    await bob.mutation(api.strictApproval.configureGuardian, { email: "bob-guardian@example.com" });
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "bob", strictMode: true, strictEndsAt: now + 60_000, strictSessionId: "bob-session", weeklyReport: true, updatedAt: now });
    });
    await alice.mutation(api.strictApproval.configureGuardian, { email: "guardian@example.com" });
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: now + 60_000, strictSessionId: "alice-session", weeklyReport: true, updatedAt: now });
    });
    await expect(bob.action(api.strictApproval.requestApprovalEmail, { strictSessionId: "alice-session", strictEndsAt: now + 60_000 })).rejects.toThrow("does not match");
    expect((await t.fetch(`/strict-approval?token=${"x".repeat(257)}`)).status).toBe(400);
    expect((await t.fetch("/strict-approval", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: "token=" })).status).toBe(400);
    expect((await t.fetch("/strict-approval", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" })).status).toBe(400);
  });

  it("allows only one winner when two approvals race", async () => {
    const t = convexTest(schema, modules);
    const now = Date.now();
    await t.run(async (ctx) => {
      await ctx.db.insert("userPrefs", { userId: "alice", strictMode: true, strictEndsAt: now + 60_000, strictSessionId: "race-session", weeklyReport: true, updatedAt: now });
      await ctx.db.insert("strictApprovalRequests", { userId: "alice", strictSessionId: "race-session", strictEndsAt: now + 60_000, tokenHash: "race-token", expiresAt: now + 30_000, createdAt: now, status: "sent" });
    });
    const outcomes = await Promise.allSettled([
      t.mutation(internal.strictApproval.approveToken, { tokenHash: "race-token", now: now + 1 }),
      t.mutation(internal.strictApproval.approveToken, { tokenHash: "race-token", now: now + 2 }),
    ]);
    expect(outcomes.filter((outcome) => outcome.status === "fulfilled")).toHaveLength(1);
    expect(outcomes.filter((outcome) => outcome.status === "rejected")).toHaveLength(1);
    expect((await t.run((ctx) => ctx.db.query("strictApprovalRequests").first()))?.status).toBe("approved");
  });
});
