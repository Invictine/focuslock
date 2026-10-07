import type { UsageBucket, UsageSummary } from './sync';

type Boundaries = {
  apps?: { packageName: string; isBlocked: boolean; isPermanent?: boolean }[];
  sites?: { domain: string; isBlocked: boolean; isPermanent?: boolean }[];
  permanentBlocks?: { targetKind: string; targetKey: string }[];
};

function domainKey(value: string): string {
  try {
    return new URL(value.includes('://') ? value : `https://${value}`).hostname
      .toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
  } catch { return ''; }
}

/** Measured Boundary usage, independent of credit earned, spent or carried over. */
export function boundaryLeisureSeconds(
  summary: UsageSummary | undefined,
  local: UsageBucket[] | undefined,
  deviceId: string,
  date: string,
  boundaries: Boundaries,
): number | null {
  const apps = new Set((boundaries.apps ?? []).filter(a => a.isBlocked || a.isPermanent)
    .map(a => a.packageName.trim().toLowerCase()));
  const sites = new Set((boundaries.sites ?? []).filter(s => s.isBlocked || s.isPermanent)
    .map(s => domainKey(s.domain)).filter(Boolean));
  for (const target of boundaries.permanentBlocks ?? []) {
    if (target.targetKind === 'website') sites.add(domainKey(target.targetKey));
    else apps.add(target.targetKey.trim().toLowerCase());
  }
  const included = (kind: string, key: string) => {
    if (kind === 'app') return apps.has(key.trim().toLowerCase());
    const domain = domainKey(key);
    return !!domain && [...sites].some(site => !!site && (domain === site || domain.endsWith(`.${site}`)));
  };
  const safe = (seconds: number) => Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
  const cloud = (summary?.targets ?? []).reduce((sum, row) =>
    sum + (included(row.targetKind, row.targetKey) ? safe(row.trackedSeconds) : 0), 0);
  const fresh = (local ?? []).filter(row => row.date === date && included(row.targetKind, row.targetKey));
  if (!summary && !local) return null;
  // Older backends cannot separate this device's already uploaded contribution.
  // Use a lower bound until per-device slices arrive, never sum it twice.
  if (!summary?.deviceTargets) return Math.max(cloud, fresh.reduce((sum, row) => sum + safe(row.trackedSeconds), 0));
  const own = new Map(summary.deviceTargets.filter(row => row.deviceId === deviceId)
    .map(row => [`${row.targetKind}:${row.targetKey.toLowerCase()}`, safe(row.trackedSeconds)]));
  return cloud + fresh.reduce((sum, row) => sum + Math.max(0,
    safe(row.trackedSeconds) - (own.get(`${row.targetKind}:${row.targetKey.toLowerCase()}`) ?? 0)), 0);
}

export function todayWorkSeconds(state: { lastResetDate?: string; totalWorkSecondsToday?: number } | null | undefined, date: string): number {
  return state?.lastResetDate === date && Number.isFinite(state.totalWorkSecondsToday)
    ? Math.max(0, state.totalWorkSecondsToday ?? 0) : 0;
}

/** Keep today's screen-time rows consistent with the live leisure card. */
export function mergeLiveTodayUsage(summary: UsageSummary | undefined, local: UsageBucket[] | undefined,
  deviceId: string, deviceName: string, date: string): UsageSummary | undefined {
  if (!summary?.deviceTargets || !local) return summary;
  const key = (row: { targetKind: string; targetKey: string }) => `${row.targetKind}:${row.targetKey.toLowerCase()}`;
  const own = new Map(summary.deviceTargets.filter(row => row.deviceId === deviceId).map(row => [key(row), row.trackedSeconds]));
  const deltas = new Map(local.filter(row => row.date === date).map(row => [key(row),
    { row, seconds: Math.max(0, row.trackedSeconds - (own.get(key(row)) ?? 0)) }]));
  const increase = [...deltas.values()].reduce((sum, value) => sum + value.seconds, 0);
  if (!increase) return summary;
  const targets = (summary.targets ?? []).map(row => ({ ...row, trackedSeconds: row.trackedSeconds + (deltas.get(key(row))?.seconds ?? 0),
    deviceIds: deltas.get(key(row))?.seconds ? [...new Set([...(row.deviceIds ?? []), deviceId])] : row.deviceIds }));
  for (const [targetKey, delta] of deltas) if (delta.seconds && !targets.some(row => key(row) === targetKey)) {
    targets.push({ ...delta.row, trackedSeconds: delta.seconds, blockedSeconds: 0, deviceIds: [deviceId] });
  }
  const byTarget = new Map(targets.map(row => [key(row), row]));
  const groups = (summary.groups ?? []).map(group => {
    const members = group.members.map(member => ({ ...member, trackedSeconds: byTarget.get(key(member))?.trackedSeconds ?? member.trackedSeconds,
      deviceIds: byTarget.get(key(member))?.deviceIds ?? member.deviceIds }));
    return { ...group, members, trackedSeconds: members.reduce((sum, member) => sum + member.trackedSeconds, 0),
      deviceIds: [...new Set(members.flatMap(member => member.deviceIds))] };
  });
  const groupedKeys = new Set(groups.flatMap(group => group.members.map(key)));
  const groupedTargets = [
    ...groups.map(group => ({ targetKind: 'group' as const, targetKey: `group:${group.groupId}`, targetLabel: group.name,
      trackedSeconds: group.trackedSeconds, blockedSeconds: group.blockedSeconds, deviceIds: group.deviceIds,
      memberKeys: group.members.map(key), memberCount: group.members.length, groupId: group.groupId,
      dailyLimitMinutes: group.dailyLimitMinutes, limitEnabled: group.limitEnabled, category: group.category })),
    ...targets.filter(row => !groupedKeys.has(key(row))).map(row => ({ ...row, memberKeys: [key(row)], memberCount: 1 })),
  ].sort((a, b) => b.trackedSeconds - a.trackedSeconds);
  const devices = (summary.devices ?? []).map(row => row.deviceId === deviceId ? { ...row, trackedSeconds: row.trackedSeconds + increase } : row);
  if (!devices.some(row => row.deviceId === deviceId)) devices.push({ deviceId, deviceName, trackedSeconds: increase, blockedSeconds: 0 });
  return { ...summary, totalTrackedSeconds: summary.totalTrackedSeconds + increase, targets, groups, groupedTargets, devices,
    days: [{ date, trackedSeconds: summary.totalTrackedSeconds + increase, blockedSeconds: summary.days?.[0]?.blockedSeconds ?? 0 }] };
}

export function formatTimeDuration(seconds: number): string {
  const safe = Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
  if (safe > 0 && safe < 60) return '<1m';
  const mins = Math.floor(safe / 60);
  return mins >= 60 ? `${Math.floor(mins / 60)}h ${mins % 60}m` : `${mins}m`;
}

export function timeRatio(focus: number, leisure: number | null, target: number): string {
  if (leisure === null) return 'Waiting for leisure data';
  const goal = Number.isFinite(target) && target > 0 ? target : 4;
  if (leisure === 0) return focus > 0 ? `No leisure tracked · Goal ${goal}:1` : `Start a focus session · Goal ${goal}:1`;
  const missing = Math.max(0, Math.ceil((leisure * goal - focus) / 60));
  const ratio = `${(focus / leisure).toFixed(1)}:1`;
  return missing > 0 ? `${ratio} · ${formatTimeDuration(missing * 60)} more focus to reach ${goal}:1`
    : `${ratio} · Meeting your ${goal}:1 goal`;
}
