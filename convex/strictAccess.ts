import { action, internalMutation, internalQuery, query } from "./_generated/server";
import { internal } from "./_generated/api";
import { v } from "convex/values";

const method = v.union(v.literal("timer"), v.literal("schedules"), v.literal("pin"), v.literal("charger"), v.literal("cooldown"), v.literal("approval"));
type StrictMethod = "timer" | "schedules" | "pin" | "charger" | "cooldown" | "approval";
const methods = v.array(method);
const configArgs = {
  method, methods: v.optional(methods), cooldownMinutes: v.number(), preventUninstall: v.boolean(), blockDeviceSettings: v.boolean(),
  blockRecents: v.boolean(), blockSplitScreen: v.boolean(), blockReinstalledApps: v.boolean(),
};
async function user(ctx: any): Promise<string> {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error("Not authenticated");
  return identity.subject;
}
async function rowFor(ctx: any, userId: string) {
  return ctx.db.query("strictAccess").withIndex("by_user", (q: any) => q.eq("userId", userId)).first();
}
function publicConfig(row: any) {
  if (!row) return null;
  return { method: row.method, methods: row.methods ?? [row.method], cooldownMinutes: row.cooldownMinutes, preventUninstall: row.preventUninstall,
    blockDeviceSettings: row.blockDeviceSettings, blockRecents: row.blockRecents, blockSplitScreen: row.blockSplitScreen,
    blockReinstalledApps: row.blockReinstalledApps, hasPin: !!row.pinHash, revision: row.revision,
    unlockRequestedAt: row.unlockRequestedAt, unlockEndsAt: row.unlockEndsAt, unlockSessionId: row.unlockSessionId };
}
async function pinDigest(pin: string, salt: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: new TextEncoder().encode(salt), iterations: 100_000, hash: "SHA-256" }, key, 256);
  return Array.from(new Uint8Array(bits), b => b.toString(16).padStart(2, "0")).join("");
}

export const getConfig = query({ args: {}, handler: async ctx => publicConfig(await rowFor(ctx, await user(ctx))) });
export const credentials = internalQuery({ args: { userId: v.string() }, handler: async (ctx, args) => rowFor(ctx, args.userId) });

/** Credentials never appear in a query result or the general account snapshot. */
export const configure = action({
  args: { ...configArgs, pin: v.optional(v.string()), expectedRevision: v.number() },
  handler: async (ctx, args): Promise<any> => {
    const userId = await user(ctx);
    const selected = args.methods ?? [args.method];
    if (!selected.length || new Set(selected).size !== selected.length) throw new Error("Choose at least one unique access route");
    if (!selected.includes(args.method)) throw new Error("The primary access method must be one of the selected routes");
    if (selected.includes("approval")) {
      const guardian = await ctx.runQuery(internal.strictApproval.guardianExists, { userId });
      if (!guardian) throw new Error("Save an approval contact first");
    }
    if (!Number.isInteger(args.cooldownMinutes) || args.cooldownMinutes < 2 || args.cooldownMinutes > 10) throw new Error("Choose a cooldown of 2 to 10 minutes");
    let pinHash: string | undefined, pinSalt: string | undefined;
    if (args.pin !== undefined) {
      if (!/^\d{4,12}$/.test(args.pin)) throw new Error("Use a PIN with 4 to 12 digits");
      const bytes = crypto.getRandomValues(new Uint8Array(24));
      pinSalt = Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
      pinHash = await pinDigest(args.pin, pinSalt);
    }
    const { pin: _pin, ...safeArgs } = args;
    return ctx.runMutation(internal.strictAccess.saveConfig, { ...safeArgs, methods: selected, userId, pinHash, pinSalt });
  },
});

export const saveConfig = internalMutation({
  args: { ...configArgs, userId: v.string(), expectedRevision: v.number(), pinHash: v.optional(v.string()), pinSalt: v.optional(v.string()) },
  handler: async (ctx, args) => {
    const prefs = await ctx.db.query("userPrefs").withIndex("by_user", q => q.eq("userId", args.userId)).first();
    const previous = await rowFor(ctx, args.userId);
    if ((previous?.revision ?? 0) !== args.expectedRevision) throw new Error("Strict Mode settings changed on another device. Reload and try again.");
    const chosen = args.methods ?? [args.method];
    const active = !!prefs?.strictMode && (!prefs.strictEndsAt || prefs.strictEndsAt > Date.now());
    if (active) {
      // Accounts created before strictAccess existed are using timer access
      // with the original default protections until a config row is saved.
      const oldMethods = previous?.methods ?? (previous ? [previous.method] : ["timer"]);
      const sameMethods = oldMethods.length === chosen.length && oldMethods.every((value: StrictMethod) => chosen.includes(value));
      const sameCredentials = args.pinHash === undefined && args.pinSalt === undefined;
      const protections = ["preventUninstall", "blockDeviceSettings", "blockRecents", "blockSplitScreen", "blockReinstalledApps"] as const;
      const onlyStrengthened = protections.every(key => !(previous?.[key] ?? false) || args[key]);
      if (!sameMethods || !sameCredentials || args.cooldownMinutes !== (previous?.cooldownMinutes ?? 5) || !onlyStrengthened) {
        throw new Error("Access methods and protections cannot change during Strict Mode; they cannot be weakened");
      }
    }
    if (chosen.includes("pin") && !(args.pinHash ?? previous?.pinHash)) throw new Error("Set a PIN before choosing PIN access");
    if (chosen.includes("approval")) {
      const guardian = await ctx.db.query("strictGuardians").withIndex("by_user", q => q.eq("userId", args.userId)).first();
      if (!guardian) throw new Error("Save an approval contact first");
    }
    const { expectedRevision: _revision, ...values } = args;
    const next = { ...values, methods: chosen, pinHash: args.pinHash ?? previous?.pinHash, pinSalt: args.pinSalt ?? previous?.pinSalt,
      revision: (previous?.revision ?? 0) + 1,
      unlockRequestedAt: active ? previous?.unlockRequestedAt : undefined,
      unlockEndsAt: active ? previous?.unlockEndsAt : undefined,
      unlockSessionId: active ? previous?.unlockSessionId : undefined,
      pinFailures: active ? previous?.pinFailures : 0,
      pinBlockedUntil: active ? previous?.pinBlockedUntil : 0 };
    if (previous) await ctx.db.patch(previous._id, next);
    else await ctx.db.insert("strictAccess", next);
    return publicConfig(next);
  },
});

/** No generic early-disable mutation exists: the configured condition is checked atomically. */
export const unlock = action({
  args: { strictSessionId: v.string(), strictEndsAt: v.number(), method: v.optional(method), pin: v.optional(v.string()), charging: v.optional(v.boolean()) },
  handler: async (ctx, args): Promise<any> => {
    const userId = await user(ctx);
    const config = await ctx.runQuery(internal.strictAccess.credentials, { userId });
    const routes = config?.methods ?? (config ? [config.method] : []);
    const selectedMethod = args.method ?? config?.method;
    if (selectedMethod && !routes.includes(selectedMethod)) return { released: false, reason: "That access route is not configured." };
    let verifiedHash: string | undefined;
    if (selectedMethod === "pin") {
      if ((config.pinBlockedUntil ?? 0) > Date.now()) return { released: false, reason: "Too many PIN attempts. Wait five minutes." };
      if (args.pin && /^\d{4,12}$/.test(args.pin) && config.pinSalt) verifiedHash = await pinDigest(args.pin, config.pinSalt);
    }
    return ctx.runMutation(internal.strictAccess.release, { userId, strictSessionId: args.strictSessionId, strictEndsAt: args.strictEndsAt,
      charging: args.charging ?? false, verifiedHash, method: selectedMethod });
  },
});

export const release = internalMutation({
  args: { userId: v.string(), strictSessionId: v.string(), strictEndsAt: v.number(), charging: v.boolean(), verifiedHash: v.optional(v.string()), method: v.optional(method) },
  handler: async (ctx, args) => {
    const prefs = await ctx.db.query("userPrefs").withIndex("by_user", q => q.eq("userId", args.userId)).first();
    const config = await rowFor(ctx, args.userId);
    const now = Date.now();
    if (!prefs?.strictMode || prefs.strictSessionId !== args.strictSessionId || prefs.strictEndsAt !== args.strictEndsAt || args.strictEndsAt <= now) {
      return { released: false, reason: "The active commitment changed. Sync and try again." };
    }
    if (!config) return { released: false, reason: "This commitment uses a timer. Wait until it ends." };
    const routes = config.methods ?? [config.method];
    const selected = args.method ?? config.method;
    if (!routes.includes(selected)) return { released: false, reason: "That access route is not configured." };
    if (selected === "pin") {
      if ((config.pinBlockedUntil ?? 0) > now) return { released: false, reason: "Too many PIN attempts. Wait five minutes." };
      if (!args.verifiedHash || args.verifiedHash !== config.pinHash) {
        const failures = (config.pinFailures ?? 0) + 1;
        await ctx.db.patch(config._id, { pinFailures: failures >= 5 ? 0 : failures, pinBlockedUntil: failures >= 5 ? now + 300_000 : 0 });
        return { released: false, reason: failures >= 5 ? "Too many PIN attempts. Wait five minutes." : "Incorrect PIN." };
      }
    } else if (selected === "charger") {
      // Android verifies BatteryManager status immediately before this authenticated call.
      // Stock Android has no trusted server attestation of a cable connection.
      if (!args.charging) return { released: false, reason: "Connect this device to a charger first." };
    } else if (selected === "cooldown") {
      if (config.unlockSessionId !== args.strictSessionId || config.unlockEndsAt !== args.strictEndsAt || !config.unlockRequestedAt) {
        await ctx.db.patch(config._id, { unlockSessionId: args.strictSessionId, unlockEndsAt: args.strictEndsAt, unlockRequestedAt: now });
        return { released: false, availableAt: now + config.cooldownMinutes * 60_000, reason: "Cooldown started. Come back when the waiting period ends." };
      }
      const availableAt = config.unlockRequestedAt + config.cooldownMinutes * 60_000;
      if (now < availableAt) return { released: false, availableAt, reason: "Your cooldown is still running." };
    } else return { released: false, reason: selected === "approval" ? "Ask your approval contact to end this commitment." : "Wait until the timer or schedule ends." };
    await ctx.db.patch(config._id, { pinFailures: 0, pinBlockedUntil: 0, unlockRequestedAt: undefined, unlockEndsAt: undefined, unlockSessionId: undefined });
    await ctx.db.patch(prefs._id, { strictMode: false, strictEndsAt: 0, strictApprovedEndsAt: args.strictEndsAt,
      strictApprovedAt: now, strictApprovedSessionId: args.strictSessionId, updatedAt: now });
    return { released: true, strictApprovedEndsAt: args.strictEndsAt, strictApprovedAt: now };
  },
});
