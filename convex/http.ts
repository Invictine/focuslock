import { httpRouter } from "convex/server";
import { httpAction } from "./_generated/server";
import { internal } from "./_generated/api";
import { hashApprovalToken } from "./strictApproval";

const http = httpRouter();

const REMOVAL_MEDIA_MAX_BYTES = 12 * 1024 * 1024;
const SHA256_RE = /^[0-9a-f]{64}$/i;

function jsonResponse(value: unknown, status = 200, headers?: HeadersInit): Response {
  const responseHeaders = new Headers(headers);
  responseHeaders.set("content-type", "application/json; charset=utf-8");
  responseHeaders.set("cache-control", "private, no-store");
  return new Response(JSON.stringify(value), { status, headers: responseHeaders });
}

function validMediaType(value: string): boolean {
  return value.startsWith("audio/") || value === "video/mp4" || value === "video/webm";
}

async function readBoundedBody(request: Request): Promise<Uint8Array | null> {
  const declared = request.headers.get("content-length");
  if (declared !== null && (!/^\d+$/.test(declared) || Number(declared) > REMOVAL_MEDIA_MAX_BYTES)) return null;
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > REMOVAL_MEDIA_MAX_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return bytes;
}

async function currentIdentity(ctx: any): Promise<{ subject: string } | null> {
  try { return await ctx.auth.getUserIdentity(); }
  catch { return null; }
}

http.route({ path: "/removal-reminder/media", method: "POST", handler: httpAction(async (ctx, request) => {
  const identity = await currentIdentity(ctx);
  if (!identity) return jsonResponse({ error: "Not authenticated" }, 401);

  const expectedRevisionRaw = request.headers.get("X-Expected-Revision") ?? "";
  const updatedAtRaw = request.headers.get("X-Updated-At") ?? "";
  const expectedRevision = Number(expectedRevisionRaw);
  const updatedAt = Number(updatedAtRaw);
  const sha256 = (request.headers.get("X-Media-Sha256") ?? "").toLowerCase();
  const mimeType = (request.headers.get("content-type") ?? "").split(";", 1)[0].trim().toLowerCase();
  const encodedName = request.headers.get("X-Media-Name");
  let name = "";
  try { name = encodedName === null ? "" : decodeURIComponent(encodedName); }
  catch { return jsonResponse({ error: "Invalid media name" }, 400); }
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 0 ||
      !Number.isSafeInteger(updatedAt) || updatedAt < 0 || !SHA256_RE.test(sha256) ||
      !validMediaType(mimeType) || !name.trim() || name.length > 200 || /[\\/\u0000-\u001f]/.test(name)) {
    return jsonResponse({ error: "Invalid removal reminder media metadata" }, 400);
  }

  const before = await ctx.runQuery(internal.removalReminder.getPrivateState, { userId: identity.subject });
  if (before.revision !== expectedRevision || updatedAt <= before.mediaUpdatedAt) {
    const { storageId: _storageId, ...state } = before;
    return jsonResponse({ applied: false, state }, 409);
  }
  const bytes = await readBoundedBody(request);
  if (bytes === null) return jsonResponse({ error: "Media exceeds 12 MiB" }, 413);
  const bodyBuffer = bytes.buffer as ArrayBuffer;
  const actualHash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bodyBuffer)))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  if (actualHash !== sha256) return jsonResponse({ error: "Media SHA-256 mismatch" }, 400);

  // Avoid writing another storage object on retry or when metadata changes for
  // an identical file already owned by this account.
  const reusedStorageObject = before.media?.sha256 === sha256;
  const storageId = reusedStorageObject
    ? before.storageId
    : await ctx.storage.store(new Blob([bodyBuffer], { type: mimeType }));
  if (!storageId) return jsonResponse({ error: "Current media object is unavailable" }, 404);
  let result;
  try {
    result = await ctx.runMutation(internal.removalReminder.attachMedia, {
      userId: identity.subject, expectedRevision, updatedAt, sha256, mimeType,
      name: name.trim(), bytes: bytes.byteLength, storageId,
    });
  } catch (error) {
    if (!reusedStorageObject) await ctx.storage.delete(storageId);
    throw error;
  }
  if (!result.applied && result.discardStorageId) await ctx.storage.delete(result.discardStorageId);
  // A same-hash race can leave an extra just-uploaded object even though the
  // mutation kept the existing one. The internal mutation marks it for cleanup.
  if (result.applied && result.discardStorageId) await ctx.storage.delete(result.discardStorageId);
  return jsonResponse({ applied: result.applied, state: result.state }, result.applied ? 200 : 409);
}) });

http.route({ path: "/removal-reminder/media", method: "GET", handler: httpAction(async (ctx, request) => {
  const identity = await currentIdentity(ctx);
  if (!identity) return jsonResponse({ error: "Not authenticated" }, 401);
  const sha256 = new URL(request.url).searchParams.get("sha256")?.toLowerCase() ?? "";
  if (!SHA256_RE.test(sha256)) return jsonResponse({ error: "Invalid SHA-256" }, 400);
  const state = await ctx.runQuery(internal.removalReminder.getPrivateState, { userId: identity.subject });
  if (!state.media || state.media.sha256 !== sha256 || !state.storageId) {
    return jsonResponse({ error: "Media not found" }, 404);
  }
  const etag = `"${state.media.sha256}"`;
  const ifNoneMatch = request.headers.get("if-none-match");
  if (ifNoneMatch?.split(",").some((candidate) => {
    const value = candidate.trim();
    return value === "*" || value === etag || value === `W/${etag}`;
  })) {
    return new Response(null, { status: 304, headers: { etag, "cache-control": "private, no-store" } });
  }
  const blob = await ctx.storage.get(state.storageId);
  if (!blob) return jsonResponse({ error: "Media not found" }, 404);
  return new Response(blob, { headers: {
    "content-type": state.media.mimeType,
    "content-length": String(state.media.bytes),
    "content-disposition": "attachment",
    etag,
    "cache-control": "private, no-store",
    "x-content-type-options": "nosniff",
  } });
}) });

function page(body: string, status = 200): Response {
  return new Response(`<!doctype html><meta name="viewport" content="width=device-width"><title>FocusLock approval</title><main style="font:16px system-ui;max-width:36rem;margin:4rem auto;padding:1.5rem">${body}</main>`, { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "referrer-policy": "no-referrer", "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'" } });
}

// GET intentionally only renders a confirmation form. Mail scanners commonly
// prefetch links, so consuming the token here would make approvals unreliable.
http.route({ path: "/strict-approval", method: "GET", handler: httpAction(async (_ctx, request) => {
  const token = new URL(request.url).searchParams.get("token");
  if (!token || token.length > 256) return page("<h1>Invalid approval link</h1>", 400);
  const escaped = token.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
  return page(`<h1>Approve Strict Mode unlock?</h1><p>This is a one-time approval for the current FocusLock session.</p><form method="post"><input type="hidden" name="token" value="${escaped}"><button type="submit">Approve unlock</button></form>`);
}) });

http.route({ path: "/strict-approval", method: "POST", handler: httpAction(async (ctx, request) => {
  let form: FormData;
  try { form = await request.formData(); }
  catch { return page("<h1>Invalid approval request</h1>", 400); }
  const token = String(form.get("token") ?? "");
  if (!token || token.length > 256) return page("<h1>Invalid approval link</h1>", 400);
  try {
    await ctx.runMutation(internal.strictApproval.approveToken, { tokenHash: await hashApprovalToken(token), now: Date.now() });
    return page("<h1>Approved</h1><p>The current Strict Mode session can now unlock on the requester’s device.</p>");
  } catch (error) {
    const message = error instanceof Error ? error.message : "Approval failed";
    return page(`<h1>Approval unavailable</h1><p>${message.replace(/</g, "&lt;")}</p>`, 400);
  }
}) });

export default http;
