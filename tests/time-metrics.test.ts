import { expect, it } from 'vitest';
import { boundaryLeisureSeconds, formatTimeDuration, mergeLiveTodayUsage, timeRatio, todayWorkSeconds } from '../desktop/src/timeMetrics';
import type { UsageBucket, UsageSummary } from '../desktop/src/sync';

const date = '2026-10-07';
const boundaries = {
  apps: [{ packageName: 'game.exe', isBlocked: true }, { packageName: 'editor.exe', isBlocked: false }],
  sites: [{ domain: 'reddit.com', isBlocked: true }, { domain: 'm.reddit.com', isBlocked: true }],
};
const local = (key: string, seconds: number, kind: 'app' | 'website' = 'app'): UsageBucket => ({
  date, targetKind: kind, targetKey: key, targetLabel: key, trackedSeconds: seconds, updatedAt: 100,
});
const summary = {
  totalTrackedSeconds: 500, days: [], devices: [], groupedTargets: [], groups: [],
  targets: [{ targetKind: 'app', targetKey: 'game.exe', trackedSeconds: 180 },
    { targetKind: 'website', targetKey: 'm.reddit.com', trackedSeconds: 40 },
    { targetKind: 'app', targetKey: 'editor.exe', trackedSeconds: 280 }],
  deviceTargets: [{ deviceId: 'pc', targetKind: 'app', targetKey: 'game.exe', trackedSeconds: 100 }],
} as UsageSummary;

it('adds only this device’s unuploaded increase to account Boundary totals', () => {
  const rows = [local('game.exe', 120), local('editor.exe', 1000)];
  expect(boundaryLeisureSeconds(summary, rows, 'pc', date, boundaries)).toBe(240);
  const accepted = { ...summary, targets: summary.targets.map(t => t.targetKey === 'game.exe' ? { ...t, trackedSeconds: 200 } : t),
    deviceTargets: [{ ...summary.deviceTargets![0], trackedSeconds: 120 }] };
  expect(boundaryLeisureSeconds(accepted, rows, 'pc', date, boundaries)).toBe(240);
  expect(boundaryLeisureSeconds(accepted, rows, 'pc', date, boundaries)).toBe(240);
});

it('does not count matching parent/subdomain boundaries twice or unrelated dates and domains', () => {
  expect(boundaryLeisureSeconds(undefined, [local('m.reddit.com', 40, 'website'),
    local('notreddit.com', 80, 'website'), { ...local('game.exe', 1000), date: '2026-10-06' }], 'pc', date, boundaries)).toBe(40);
  expect(boundaryLeisureSeconds(undefined, [local('tool.exe', 10)], 'pc', date, {
    permanentBlocks: [{ targetKind: 'windows', targetKey: 'tool.exe' }],
  })).toBe(10);
});

it('keeps loading separate from zero and safely supports older summaries', () => {
  expect(boundaryLeisureSeconds(undefined, undefined, 'pc', date, boundaries)).toBeNull();
  expect(boundaryLeisureSeconds({ ...summary, deviceTargets: undefined }, [local('game.exe', 120)], 'pc', date, boundaries)).toBe(220);
  expect(boundaryLeisureSeconds(undefined, [], 'pc', date, boundaries)).toBe(0);
});

it('resets daily work presentation without resetting carried-over credits', () => {
  const state = { lastResetDate: '2026-10-06', totalWorkSecondsToday: 3600 };
  expect(todayWorkSeconds(state, date)).toBe(0);
  expect(todayWorkSeconds({ ...state, lastResetDate: date }, date)).toBe(3600);
});

it('uses whole-minute durations on both platforms and an honest empty ratio', () => {
  expect(formatTimeDuration(59)).toBe('<1m');
  expect(formatTimeDuration(3599)).toBe('59m');
  expect(formatTimeDuration(3600)).toBe('1h 0m');
  expect(timeRatio(0, null, 4)).toBe('Waiting for leisure data');
  expect(timeRatio(600, 300, 4)).toBe('2.0:1 · 10m more focus to reach 4:1');
});

it('keeps live screen time, device rows and merged buckets consistent with leisure', () => {
  const cloud: UsageSummary = { ...summary, targets: summary.targets.map(row => ({ ...row, deviceIds: ['pc'] })),
    groups: [{ groupId: 'games', name: 'Games', trackedSeconds: 180, blockedSeconds: 0, deviceIds: ['pc'],
      members: [{ targetKind: 'app', targetKey: 'game.exe', targetLabel: 'Game', trackedSeconds: 180, deviceIds: ['pc'] }] }] };
  const merged = mergeLiveTodayUsage(cloud, [local('game.exe', 120)], 'pc', 'PC', date)!;
  expect(merged.totalTrackedSeconds).toBe(520);
  expect(merged.groups[0].trackedSeconds).toBe(200);
  expect(merged.groupedTargets.find(row => row.groupId === 'games')?.trackedSeconds).toBe(200);
  expect(merged.devices[0]).toMatchObject({ deviceId: 'pc', trackedSeconds: 20 });
  expect(merged.targets.find(row => row.targetKey === 'game.exe')?.trackedSeconds).toBe(200);
});
