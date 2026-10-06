export type PermanentTargetOwnership = {
  unclaimedTargets: string[];
  ownersByTarget: Record<string, string[]>;
};

type StorageLike = Pick<Storage, "getItem" | "setItem">;

function storageKey(deviceId: string) {
  return `focuslock.permanent-target-owners.v1:${encodeURIComponent(deviceId)}`;
}

export function readPermanentTargetOwnership(
  storage: StorageLike,
  deviceId: string,
): PermanentTargetOwnership | null {
  try {
    const raw = storage.getItem(storageKey(deviceId));
    if (!raw) return { unclaimedTargets: [], ownersByTarget: {} };
    const parsed = JSON.parse(raw);
    const ownersByTarget: Record<string, string[]> = {};
    for (const [target, owners] of Object.entries(parsed?.ownersByTarget || {})) {
      if (Array.isArray(owners)) {
        ownersByTarget[target] = [...new Set(owners.filter(
          (owner): owner is string => typeof owner === "string" && Boolean(owner),
        ))];
      }
    }
    return {
      unclaimedTargets: Array.isArray(parsed?.unclaimedTargets)
        ? [...new Set<string>(parsed.unclaimedTargets.filter(
            (target: unknown): target is string => typeof target === "string" && Boolean(target),
          ))]
        : [],
      ownersByTarget,
    };
  } catch {
    return null;
  }
}

function writeOwnership(
  storage: StorageLike,
  deviceId: string,
  ownership: PermanentTargetOwnership,
) {
  try {
    storage.setItem(storageKey(deviceId), JSON.stringify(ownership));
    return true;
  } catch {
    return false;
  }
}

// Existing native targets predate account sync, so classify them as unclaimed.
// The first verified account may adopt them; later account switches cannot
// upload targets already claimed by a different account.
export function discoverPermanentTargets(
  storage: StorageLike,
  deviceId: string,
  targets: string[],
) {
  const ownership = readPermanentTargetOwnership(storage, deviceId);
  if (!ownership) return null;
  for (const target of targets) {
    if (!ownership.ownersByTarget[target] && !ownership.unclaimedTargets.includes(target)) {
      ownership.unclaimedTargets.push(target);
    }
  }
  return writeOwnership(storage, deviceId, ownership) ? ownership : null;
}

export function claimPermanentTargets(
  storage: StorageLike,
  deviceId: string,
  targets: string[],
  accountKey: string,
) {
  const ownership = readPermanentTargetOwnership(storage, deviceId);
  if (!ownership) return null;
  for (const target of targets) {
    ownership.unclaimedTargets = ownership.unclaimedTargets.filter((item) => item !== target);
    const owners = ownership.ownersByTarget[target] || [];
    if (!owners.includes(accountKey)) owners.push(accountKey);
    ownership.ownersByTarget[target] = owners;
  }
  return writeOwnership(storage, deviceId, ownership) ? ownership : null;
}

export function permanentTargetsOwnedByAccount(
  ownership: PermanentTargetOwnership,
  targets: string[],
  accountKey: string,
) {
  return targets.filter((target) => ownership.ownersByTarget[target]?.includes(accountKey));
}
