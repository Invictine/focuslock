import type { DeviceHeartbeat, UsageBucket } from "./sync";

export type PendingHeartbeat = Omit<DeviceHeartbeat, "lastSeen"> & { lastSeen: number };

export type PendingSync = {
  heartbeat?: PendingHeartbeat;
  usage: UsageBucket[];
  acknowledged?: Record<string, number>;
};

function queueKey(accountKey: string, deviceId: string) {
  return `focuslock.syncQueue.v1:${encodeURIComponent(accountKey)}:${encodeURIComponent(deviceId)}`;
}

function usageKey(bucket: UsageBucket) {
  return `${bucket.date}|${bucket.targetKind}|${bucket.targetKey}`;
}

function read(accountKey: string, deviceId: string): PendingSync {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(queueKey(accountKey, deviceId)) || "null") as Partial<PendingSync> | null;
    return {
      heartbeat: parsed?.heartbeat,
      acknowledged: parsed?.acknowledged ?? {},
      usage: Array.isArray(parsed?.usage) ? parsed!.usage.filter((entry): entry is UsageBucket => Boolean(entry && entry.date && entry.targetKind && entry.targetKey)) : [],
    };
  } catch {
    throw new Error("Could not read saved sync data. Existing data was kept; free storage or restart and retry.");
  }
}

function write(accountKey: string, deviceId: string, pending: PendingSync) {
    if (!pending.heartbeat && pending.usage.length === 0 && !Object.keys(pending.acknowledged ?? {}).length) {
      window.localStorage.removeItem(queueKey(accountKey, deviceId));
    } else {
      window.localStorage.setItem(queueKey(accountKey, deviceId), JSON.stringify(pending));
    }
}

export function enqueueSync(accountKey: string, deviceId: string, heartbeat: PendingHeartbeat, usage: UsageBucket[]) {
  const pending = read(accountKey, deviceId);
  pending.heartbeat = !pending.heartbeat || heartbeat.lastSeen >= pending.heartbeat.lastSeen ? heartbeat : pending.heartbeat;
  const byKey = new Map(pending.usage.map((entry) => [usageKey(entry), entry]));
  for (const entry of usage) {
    if ((pending.acknowledged?.[usageKey(entry)] ?? -1) >= entry.trackedSeconds) continue;
    const previous = byKey.get(usageKey(entry));
    if (!previous || entry.updatedAt >= previous.updatedAt) byKey.set(usageKey(entry), {
      ...entry, trackedSeconds: Math.max(previous?.trackedSeconds ?? 0, entry.trackedSeconds),
    });
  }
  pending.usage = [...byKey.values()];
  write(accountKey, deviceId, pending);
}

export function peekSync(accountKey: string, deviceId: string): PendingSync {
  return read(accountKey, deviceId);
}

export function acknowledgeSync(accountKey: string, deviceId: string, sent: PendingSync) {
  const pending = read(accountKey, deviceId);
  pending.acknowledged ??= {};
  for (const row of sent.usage) {
    pending.acknowledged[usageKey(row)] = Math.max(pending.acknowledged[usageKey(row)] ?? 0, row.trackedSeconds);
  }
  const sentByKey = new Map(sent.usage.map((entry) => [usageKey(entry), entry]));
  pending.usage = pending.usage.filter((entry) => {
    const acknowledged = sentByKey.get(usageKey(entry));
    return !acknowledged || acknowledged.updatedAt < entry.updatedAt || acknowledged.trackedSeconds < entry.trackedSeconds;
  });
  if (pending.heartbeat && sent.heartbeat && pending.heartbeat.lastSeen <= sent.heartbeat.lastSeen) pending.heartbeat = undefined;
  write(accountKey, deviceId, pending);
}

/** Attribute device-global native counters to one account at a time. */
export function accountUsage(account: string, device: string, input: UsageBucket[]): UsageBucket[] {
  const key = `focuslock.usageOwners.v1:${encodeURIComponent(device)}`;
  const raw = window.localStorage.getItem(key);
  const state: { active: string; last: Record<string, number>; totals: Record<string, Record<string, UsageBucket>> } =
    raw ? JSON.parse(raw) : { active: account, last: {}, totals: {} };
  const switched = state.active !== account;
  const totals = state.totals[account] ?? {};
  // Multiple browsers can report the same domain. Aggregate before computing deltas.
  const grouped = new Map<string, UsageBucket>();
  for (const row of input) {
    const k = usageKey(row);
    const previous = grouped.get(k);
    grouped.set(k, { ...row, trackedSeconds: row.trackedSeconds + (previous?.trackedSeconds ?? 0) });
  }
  for (const [k, row] of grouped) {
    const baseline = state.last[k] ?? 0;
    const delta = switched ? 0 : Math.max(0, row.trackedSeconds - baseline);
    totals[k] = { ...row, trackedSeconds: (totals[k]?.trackedSeconds ?? 0) + delta };
    state.last[k] = row.trackedSeconds;
  }
  state.active = account;
  state.totals[account] = totals;
  window.localStorage.setItem(key, JSON.stringify(state));
  return Object.values(totals).filter((row) => row.trackedSeconds > 0);
}

export type PendingMutation = { id: string; path: string; args: Record<string, unknown> };
const mutationKey = (account: string) => `focuslock.mutations.v1:${encodeURIComponent(account)}`;
export function pendingMutations(account: string): PendingMutation[] {
  return JSON.parse(window.localStorage.getItem(mutationKey(account)) || '[]');
}
export function enqueueMutation(account: string, item: PendingMutation) {
  const pending = pendingMutations(account);
  window.localStorage.setItem(mutationKey(account), JSON.stringify([...pending, item]));
}
export function acknowledgeMutation(account: string, id: string) {
  window.localStorage.setItem(mutationKey(account), JSON.stringify(pendingMutations(account).filter((item) => item.id !== id)));
}
