/** Add only the part of a source counter not already included in the catalog.
 * Source markers and aggregates commit in the same transaction, so migration,
 * retention and live uploads can interleave without double counting. */
export async function ensureCatalogContribution(ctx: any, row: {
  userId: string; deviceId: string; targetKind: "app" | "website";
  targetKey: string; targetLabel: string; category?: string; date: string;
  trackedSeconds: number; catalogedTrackedSeconds?: number;
}): Promise<void> {
  const delta = Math.max(0, row.trackedSeconds - (row.catalogedTrackedSeconds ?? 0));
  const existing = await ctx.db.query("usageCatalog")
    .withIndex("by_user_device_target", (q: any) => q.eq("userId", row.userId)
      .eq("deviceId", row.deviceId).eq("targetKind", row.targetKind).eq("targetKey", row.targetKey)).first();
  const value = {
    userId: row.userId, deviceId: row.deviceId,
    targetKind: row.targetKind, targetKey: row.targetKey,
    targetLabel: existing && existing.lastDate > row.date ? existing.targetLabel : row.targetLabel,
    category: existing && existing.lastDate > row.date ? existing.category : row.category,
    trackedSeconds: (existing?.trackedSeconds ?? 0) + delta,
    lastDate: existing && existing.lastDate > row.date ? existing.lastDate : row.date,
  };
  if (!existing) await ctx.db.insert("usageCatalog", value);
  else if (delta > 0 || (row.date >= existing.lastDate &&
      (existing.targetLabel !== row.targetLabel || existing.category !== row.category))) {
    await ctx.db.patch(existing._id, value);
  }
}
