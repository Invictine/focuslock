const MAX_COLLECTION_ITEMS = 500;

/** Enforce bounded, unambiguous identities before replacing a synced collection. */
export function validateCollection<T>(
  items: T[],
  label: string,
  identityParts: (item: T) => string[],
): void {
  if (items.length > MAX_COLLECTION_ITEMS) {
    throw new Error(`${label} cannot contain more than ${MAX_COLLECTION_ITEMS} items`);
  }

  const seen = new Set<string>();
  for (const item of items) {
    const parts = identityParts(item).map((part) => part.trim());
    if (parts.some((part) => !part)) throw new Error(`${label} identity cannot be empty`);
    const key = JSON.stringify(parts);
    if (seen.has(key)) throw new Error(`${label} contains duplicate identities`);
    seen.add(key);
  }
}

export function validateUpdatedAt(updatedAt: number): void {
  if (!Number.isFinite(updatedAt)) throw new Error("updatedAt must be finite");
}

function identityKey<T>(item: T, identityParts: (item: T) => string[]): string {
  return JSON.stringify(identityParts(item).map((part) => part.trim()));
}

function sameValue(left: any, right: any): boolean {
  if (left === right) return true;
  if (left === undefined || right === undefined || left === null || right === null) return false;
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length &&
      left.every((value, index) => sameValue(value, right[index]));
  }
  if (typeof left === "object" || typeof right === "object") {
    if (typeof left !== "object" || typeof right !== "object") return false;
    const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
    for (const key of keys) {
      if (left[key] === undefined && right[key] === undefined) continue;
      if (!sameValue(left[key], right[key])) return false;
    }
    return true;
  }
  return false;
}

function sameContent<T extends Record<string, any>>(current: any, item: T): boolean {
  const fields = new Set([
    ...Object.keys(item),
    ...Object.keys(current).filter((field) =>
      !field.startsWith("_") && field !== "userId" && field !== "updatedAt"),
  ]);
  for (const field of fields) {
    if (!sameValue(current[field], item[field])) return false;
  }
  return true;
}

/** Apply a full collection snapshot while retaining storage identity for rows
 * whose content is unchanged. The caller performs stale-write and domain guards. */
export async function applyCollectionDiff<T extends Record<string, any>>(
  ctx: any,
  table: string,
  userId: string,
  existing: any[],
  incoming: T[],
  identityParts: (item: T) => string[],
  updatedAt: number,
): Promise<number> {
  let mutatedRows = 0;
  const existingByKey = new Map<string, any>();
  const duplicates: any[] = [];
  for (const row of existing) {
    const key = identityKey(row as T, identityParts);
    if (existingByKey.has(key)) duplicates.push(row);
    else existingByKey.set(key, row);
  }

  const nextByKey = new Map(incoming.map((item) => [identityKey(item, identityParts), item]));
  for (const row of existing) {
    if (!nextByKey.has(identityKey(row as T, identityParts))) {
      await ctx.db.delete(row._id);
      mutatedRows++;
    }
  }
  for (const row of duplicates) {
    if (nextByKey.has(identityKey(row as T, identityParts))) {
      await ctx.db.delete(row._id);
      mutatedRows++;
    }
  }

  for (const [key, item] of nextByKey) {
    const current = existingByKey.get(key);
    if (current && sameContent(current, item)) continue;

    const value: Record<string, any> = { ...item, userId, updatedAt };
    if (current) {
      for (const field of Object.keys(current)) {
        if (!field.startsWith("_") && field !== "userId" && field !== "updatedAt" && !(field in item)) {
          value[field] = undefined;
        }
      }
      await ctx.db.patch(current._id, value);
      mutatedRows++;
    } else {
      await ctx.db.insert(table, value);
      mutatedRows++;
    }
  }

  return mutatedRows;
}
