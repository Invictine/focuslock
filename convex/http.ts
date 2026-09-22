import { httpRouter } from "convex/server";
import { httpAction } from "./_generated/server";
import { internal } from "./_generated/api";
import { hashApprovalToken } from "./strictApproval";

const http = httpRouter();

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
