import { mutation, query, action } from "./_generated/server";
import { v } from "convex/values";
import { api } from "./_generated/api";

async function requireUserId(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}

export const MEDITATION_REQUIRED_MS = 10 * 60 * 1000;

export const getNuke = query({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    return await ctx.db
      .query("nukeState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
  },
});

/** Activate nuke on all devices. One tap nukes phone+PC via sync. */
export const activate = mutation({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const now = Date.now();
    const existing = await ctx.db
      .query("nukeState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    if (existing) {
      await ctx.db.patch(existing._id, {
        isActive: true,
        startedAt: now,
        meditationCompletedAt: undefined,
        planText: undefined,
        unlockedAt: undefined,
        updatedAt: now,
      });
      return existing._id;
    }
    return await ctx.db.insert("nukeState", {
      userId,
      isActive: true,
      startedAt: now,
      updatedAt: now,
    });
  },
});

export const completeMeditation = mutation({
  args: {},
  handler: async (ctx) => {
    const userId = await requireUserId(ctx);
    const doc = await ctx.db
      .query("nukeState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    if (!doc || !doc.isActive) throw new Error("Nuke not active");
    if (Date.now() - doc.startedAt < MEDITATION_REQUIRED_MS) {
      throw new Error("Meditation not complete — 10-minute reset required");
    }
    await ctx.db.patch(doc._id, {
      meditationCompletedAt: Date.now(),
      updatedAt: Date.now(),
    });
  },
});

const COACH_SYSTEM = `You are FocusLock Nuke Coach — a calm, firm mindfulness coach.
The user nuked all devices and just finished a 10-minute breathing reset.
Your job: help them state a clear, intentional plan for what they will do from now onwards.
Require: 1 concrete next action, a time-box, and what they will NOT do.
If the plan is vague ("stuff", "be productive", "idk"), ask ONE sharp follow-up — do not approve.
If the plan is concrete, approve with encouragement.
Always reply as JSON: {"approved": boolean, "reply": string}.
Keep reply under 60 words, warm, no fluff.`;

function tryParseCoachReply(text: string): { approved: boolean; reply: string } {
  try {
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start >= 0 && end > start) {
      const obj = JSON.parse(text.slice(start, end + 1));
      if (typeof obj.reply === "string") {
        return { approved: obj.approved === true, reply: obj.reply.slice(0, 500) };
      }
    }
  } catch {}
  // Fallback heuristic: approve only if message looks intentional.
  const concrete = text.length > 40 && /(next|will|plan|first|then|minutes|hour|today)/i.test(text);
  return {
    approved: false,
    reply: concrete
      ? "Good start — make it sharper: what is the ONE next action, for how many minutes, and what will you NOT touch?"
      : "Tell me concretely: what is the single next thing you will do, for how long, and what will you avoid?",
  };
}

/**
 * Conversational check-in. Client sends the user's plan + history.
 * Server calls Vertex AI Gemini Flash (provisioned via scripts/setup-nuke-vertex.ps1
 * using the gcloud CLI). Env: VERTEX_PROJECT, VERTEX_LOCATION, VERTEX_ACCESS_TOKEN
 * or GOOGLE_ACCESS_TOKEN. Falls back to heuristic gating if Vertex is unconfigured
 * so the lock can never be bypassed by a missing key.
 */
export const checkin = action({
  args: {
    message: v.string(),
    history: v.optional(v.array(v.object({ role: v.string(), text: v.string() }))),
  },
  handler: async (ctx, args): Promise<{ approved: boolean; reply: string }> => {
    const userId: string = await requireUserId(ctx as any);
    const doc: any = await ctx.runQuery(api.nuke.getNuke);
    if (!doc || !doc.isActive) throw new Error("Nuke not active");
    if (!doc.meditationCompletedAt) throw new Error("Finish the 10-minute reset first");

    const message = args.message.trim().slice(0, 2000);
    if (message.length < 3) {
      return { approved: false, reply: "Tell me in your own words: what will you do from now onwards?" };
    }

    const project = process.env.VERTEX_PROJECT ?? "";
    const location = process.env.VERTEX_LOCATION ?? "us-central1";
    const model = process.env.GEMINI_MODEL ?? "gemini-2.0-flash";
    const token = process.env.VERTEX_ACCESS_TOKEN ?? process.env.GOOGLE_ACCESS_TOKEN ?? "";

    const historyText = (args.history ?? [])
      .slice(-6)
      .map((h) => `${h.role === "coach" ? "Coach" : "User"}: ${h.text.slice(0, 500)}`)
      .join("\n");

    if (!project || !token) {
      // No Vertex credentials — strict heuristic so nuke still holds.
      const words = message.split(/\s+/).length;
      const hasTime = /(\d+\s*(min|minutes|hour|hours)|pomodoro|timebox|until)/i.test(message);
      const hasAction = /(will|going to|start|do|work on|write|code|read|build|study|clean|exercise|call|finish)\b/i.test(message);
      if (words >= 12 && hasTime && hasAction) {
        await ctx.runMutation(api.nuke.unlockWithPlan, { planText: message });
        return {
          approved: true,
          reply: "Locked in. One thing, time-boxed, distractions off. Vertex AI is not configured yet — run scripts/setup-nuke-vertex.ps1 with gcloud to enable the full coach. Go.",
        };
      }
      return {
        approved: false,
        reply:
          "I hear you — sharpen it: ONE next action + how many minutes + what you will NOT touch. (Full AI coach unlocks after `setup-nuke-vertex.ps1` connects Vertex.)",
      };
    }

    const url = `https://${location}-aiplatform.googleapis.com/v1/projects/${project}/locations/${location}/publishers/google/models/${model}:generateContent`;
    const body = {
      systemInstruction: { parts: [{ text: COACH_SYSTEM }] },
      contents: [
        ...(args.history ?? []).slice(-6).map((h) => ({
          role: h.role === "coach" ? "model" : "user",
          parts: [{ text: h.text.slice(0, 1000) }],
        })),
        { role: "user", parts: [{ text: `My plan after the reset:\n${message}\n\nPrior context:\n${historyText || "(none)"}` }] },
      ],
      generationConfig: { temperature: 0.7, maxOutputTokens: 200 },
    };

    let replyText = "";
    try {
      const res = await fetch(url, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`Vertex ${res.status}`);
      const data: any = await res.json();
      replyText = data?.candidates?.[0]?.content?.parts?.map((p: any) => p.text ?? "").join("") ?? "";
    } catch (e) {
      return {
        approved: false,
        reply: "Coach is unreachable right now — stay with the breath and restate your plan: one action, minutes, and what you avoid.",
      };
    }

    const parsed = tryParseCoachReply(replyText || message);
    if (parsed.approved) {
      await ctx.runMutation(api.nuke.unlockWithPlan, { planText: message });
    }
    void userId;
    return parsed;
  },
});

export const unlockWithPlan = mutation({
  args: { planText: v.string() },
  handler: async (ctx, args) => {
    const userId = await requireUserId(ctx);
    const doc = await ctx.db
      .query("nukeState")
      .withIndex("by_user", (q) => q.eq("userId", userId))
      .first();
    if (!doc || !doc.isActive) throw new Error("Nuke not active");
    if (!doc.meditationCompletedAt) throw new Error("Finish the 10-minute reset first");
    const now = Date.now();
    await ctx.db.patch(doc._id, {
      isActive: false,
      planText: args.planText.slice(0, 2000),
      unlockedAt: now,
      updatedAt: now,
    });
  },
});
