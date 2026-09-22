import { action, internalMutation, mutation, query } from "./_generated/server";
import { internal } from "./_generated/api";
import { v } from "convex/values";

const REQUEST_TTL_MS = 30 * 60 * 1000;
const RATE_WINDOW_MS = 60 * 60 * 1000;
const MAX_REQUESTS_PER_HOUR = 3;

function escapeHtml(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function requireEmail(email: string): string {
  const normalized = email.trim().toLowerCase();
  if (normalized.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) {
    throw new Error("A valid guardian email is required");
  }
  return normalized;
}

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function hex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function hashApprovalToken(token: string): Promise<string> {
  const bytes = new TextEncoder().encode(token);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return hex(digest);
}

/** Configure the one guardian before strict mode starts. */
export const configureGuardian = mutation({
  args: { email: v.string() },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const email = requireEmail(args.email);
    const prefs = await ctx.db.query("userPrefs").withIndex("by_user", (q) => q.eq("userId", userId)).first();
    if (prefs?.strictMode && (prefs.strictEndsAt ?? 0) > Date.now()) {
      throw new Error("Guardian cannot change while Strict Mode is active");
    }
    const existing = await ctx.db.query("strictGuardians").withIndex("by_user", (q) => q.eq("userId", userId)).first();
    const now = Date.now();
    if (existing) {
      await ctx.db.patch(existing._id, { email, updatedAt: now });
    } else {
      await ctx.db.insert("strictGuardians", { userId, email, configuredAt: now, updatedAt: now });
    }
    return { email };
  },
});

export const getGuardian = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const row = await ctx.db.query("strictGuardians").withIndex("by_user", (q) => q.eq("userId", userId)).first();
    return row ? { email: row.email } : null;
  },
});

/** Internal transaction: checks the active commitment and enforces request rate limits. */
export const createRequest = internalMutation({
  args: { userId: v.string(), strictSessionId: v.string(), strictEndsAt: v.number(), tokenHash: v.string(), now: v.number() },
  handler: async (ctx, args) => {
    const guardian = await ctx.db.query("strictGuardians").withIndex("by_user", (q) => q.eq("userId", args.userId)).first();
    if (!guardian) throw new Error("Configure a guardian before requesting approval");
    const prefs = await ctx.db.query("userPrefs").withIndex("by_user", (q) => q.eq("userId", args.userId)).first();
    if (!prefs?.strictMode || (prefs.strictEndsAt ?? 0) <= args.now) throw new Error("Strict Mode is not active");
    if (!prefs.strictSessionId || prefs.strictSessionId !== args.strictSessionId || prefs.strictEndsAt !== args.strictEndsAt) {
      throw new Error("Approval request does not match the active Strict Mode session");
    }
    const recent = await ctx.db.query("strictApprovalRequests")
      .withIndex("by_user_created", (q) => q.eq("userId", args.userId).gte("createdAt", args.now - RATE_WINDOW_MS))
      .take(MAX_REQUESTS_PER_HOUR);
    const count = recent.length;
    if (count >= MAX_REQUESTS_PER_HOUR) throw new Error("Approval email rate limit reached");
    const id = await ctx.db.insert("strictApprovalRequests", {
      userId: args.userId,
      strictSessionId: args.strictSessionId,
      strictEndsAt: args.strictEndsAt,
      tokenHash: args.tokenHash,
      expiresAt: args.now + REQUEST_TTL_MS,
      createdAt: args.now,
      status: "pending",
    });
    return { id, guardianEmail: guardian.email, expiresAt: args.now + REQUEST_TTL_MS };
  },
});

export const markSent = internalMutation({
  args: { requestId: v.id("strictApprovalRequests"), now: v.number() },
  handler: async (ctx, args) => {
    const request = await ctx.db.get(args.requestId);
    if (!request || request.status !== "pending") return { applied: false };
    await ctx.db.patch(args.requestId, { status: "sent", sentAt: args.now });
    return { applied: true };
  },
});

export const markFailed = internalMutation({
  args: { requestId: v.id("strictApprovalRequests"), now: v.number(), reason: v.string() },
  handler: async (ctx, args) => {
    const request = await ctx.db.get(args.requestId);
    if (!request || request.status !== "pending") return { applied: false };
    await ctx.db.patch(args.requestId, { status: "failed", failedAt: args.now, failureReason: args.reason.slice(0, 300) });
    return { applied: true };
  },
});

/** Internal transaction: token consumption and approval marker are atomic. */
export const approveToken = internalMutation({
  args: { tokenHash: v.string(), now: v.number() },
  handler: async (ctx, args) => {
    const request = await ctx.db.query("strictApprovalRequests")
      .withIndex("by_token_hash", (q) => q.eq("tokenHash", args.tokenHash)).first();
    if (!request) throw new Error("Approval link is invalid");
    if (request.status === "approved" || request.consumedAt !== undefined) throw new Error("Approval link has already been used");
    if (request.status !== "sent") throw new Error("Approval link is not available");
    if (request.expiresAt <= args.now) {
      await ctx.db.patch(request._id, { status: "expired" });
      throw new Error("Approval link has expired");
    }
    const prefs = await ctx.db.query("userPrefs").withIndex("by_user", (q) => q.eq("userId", request.userId)).first();
    if (!prefs?.strictMode || (prefs.strictEndsAt ?? 0) <= args.now || prefs.strictSessionId !== request.strictSessionId || prefs.strictEndsAt !== request.strictEndsAt) {
      throw new Error("Strict Mode session is no longer active");
    }
    await ctx.db.patch(request._id, { status: "approved", consumedAt: args.now });
    await ctx.db.patch(prefs._id, { strictMode: false, strictEndsAt: 0, strictApprovedEndsAt: request.strictEndsAt, strictApprovedAt: args.now, strictApprovedSessionId: request.strictSessionId, updatedAt: args.now });
    return { approved: true, strictSessionId: request.strictSessionId, strictApprovedEndsAt: request.strictEndsAt, strictApprovedAt: args.now };
  },
});

export const getApprovalState = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const prefs = await ctx.db.query("userPrefs").withIndex("by_user", (q) => q.eq("userId", userId)).first();
    return { strictMode: prefs?.strictMode ?? false, strictEndsAt: prefs?.strictEndsAt, strictApprovedEndsAt: prefs?.strictApprovedEndsAt, strictApprovedAt: prefs?.strictApprovedAt, strictApprovedSessionId: prefs?.strictApprovedSessionId, strictSessionId: prefs?.strictSessionId };
  },
});

/** Authenticated requester only. The raw token is sent solely to the guardian email. */
export const requestApprovalEmail = action({
  args: { strictSessionId: v.string(), strictEndsAt: v.number() },
  handler: async (ctx, args): Promise<{ sent: boolean; reason?: string; expiresAt?: number }> => {
    const userId = await requireUserId(ctx);
    const token = randomToken();
    const request: { id: any; guardianEmail: string; expiresAt: number } = await ctx.runMutation(internal.strictApproval.createRequest, {
      userId, strictSessionId: args.strictSessionId, strictEndsAt: args.strictEndsAt,
      tokenHash: await hashApprovalToken(token), now: Date.now(),
    });
    const baseUrl = process.env.STRICT_APPROVAL_REVIEW_URL_BASE;
    const apiKey = process.env.RESEND_API_KEY;
    const from = process.env.RESEND_FROM_EMAIL;
    if (!baseUrl || !apiKey || !from) {
      await ctx.runMutation(internal.strictApproval.markFailed, { requestId: request.id, now: Date.now(), reason: "Email provider is not configured" });
      return { sent: false, reason: "Email provider is not configured" };
    }
    try {
      const reviewUrl = new URL("/strict-approval", baseUrl);
      if (reviewUrl.protocol !== "https:") throw new Error("Approval review URL must use HTTPS");
      reviewUrl.searchParams.set("token", token);
      const identity = await ctx.auth.getUserIdentity();
      const requester = identity?.name || identity?.email || "A FocusLock user";
      const endLabel = new Date(args.strictEndsAt).toUTCString();
      const response = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json", "Idempotency-Key": `strict-approval-${request.id}` },
        body: JSON.stringify({ from, to: [request.guardianEmail], subject: "FocusLock approval request",
          html: `<p>${escapeHtml(requester)} asked you to end their Strict Mode commitment early.</p><p>Scheduled end: ${escapeHtml(endLabel)}.</p><p><a href="${escapeHtml(reviewUrl.toString())}">Review and approve</a></p><p>This link expires in 30 minutes. Opening it does not approve the request; confirm on the review page. Permanent blocks will stay active. Ignore this email to leave the commitment running.</p>`,
          text: `${requester} asked you to end their FocusLock Strict Mode commitment early. Scheduled end: ${endLabel}. Review and approve: ${reviewUrl}. The link expires in 30 minutes. Permanent blocks stay active. Ignore this email to leave the commitment running.`,
        }),
      });
      if (!response.ok) throw new Error(`Email provider returned ${response.status}`);
      await ctx.runMutation(internal.strictApproval.markSent, { requestId: request.id, now: Date.now() });
      return { sent: true, expiresAt: request.expiresAt };
    } catch (error) {
      const reason = error instanceof Error ? error.message : "Email provider request failed";
      await ctx.runMutation(internal.strictApproval.markFailed, { requestId: request.id, now: Date.now(), reason });
      return { sent: false, reason };
    }
  },
});
