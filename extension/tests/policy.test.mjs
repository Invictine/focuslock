import assert from 'node:assert/strict';
import '../src/matcher.js';
import '../src/policy.js';

const policy = globalThis.FocusLockPolicy;
const monday0130 = new Date(2026, 8, 28, 1, 30).getTime();
const today = '2026-09-28';

function state(overrides = {}) {
  return {
    cloudSites: [{ domain: 'youtube.com', isBlocked: true, category: 'Video' }],
    cloudPolicy: {
      state: { creditBalanceSeconds: 600, lastResetDate: today, totalScrollSecondsToday: 0 },
      groups: [], limits: [], schedules: [],
    },
    cloudPrefs: {},
    cloudUsage: { date: today, targets: [], groups: [] },
    cloudUsageBaseline: {},
    leisureStats: {},
    cloudLeisureBaseline: {},
    stats: {},
    snoozes: {},
    ...overrides,
  };
}

// Policy remains separate from legacy shared-boundary handling until a snapshot exists.
assert.equal(policy.verdict('https://youtube.com/', {}, monday0130), null);

// A selected shared boundary is leisure while effective cross-device credit remains.
const funded = state({
  leisureStats: { [today]: { 'youtube.com': 30 } },
  cloudLeisureBaseline: { [today]: { 'youtube.com': 10 } },
});
assert.equal(policy.effectiveBalance(funded), 580);
assert.equal(policy.verdict('https://m.youtube.com/watch', funded, monday0130), null);
assert.equal(policy.isLeisure('https://m.youtube.com/watch', funded, monday0130), true);
funded.leisureStats[today]['youtube.com'] = 610;
assert.equal(policy.effectiveBalance(funded), 0, 'local spend exhausts the synced balance');
assert.equal(policy.verdict('https://youtube.com/', funded, monday0130).mode, 'earned-time');
assert.equal(policy.isLeisure('https://youtube.com/', funded, monday0130), false);

// Strict commitments lock boundary edits but do not change browsing policy.
const strict = state({ strictMode: true, strictEndsAt: monday0130 + 60_000,
  snoozes: { 'youtube.com': monday0130 + 60_000 } });
assert.equal(policy.verdict('https://youtube.com/', strict, monday0130), null,
  'Strict Mode does not block an otherwise funded boundary or cancel its snooze');
assert.equal(policy.isLeisure('https://youtube.com/', strict, monday0130), false,
  'Snoozed time is not counted as leisure usage');
const strictNoCredit = state({ strictMode: true, strictEndsAt: monday0130 + 60_000,
  cloudPolicy: { state: { creditBalanceSeconds: 0 }, groups: [], limits: [], schedules: [] } });
assert.equal(policy.verdict('https://youtube.com/', strictNoCredit, monday0130).mode, 'earned-time',
  'Strict Mode preserves the ordinary zero-credit block');
strictNoCredit.snoozes['youtube.com'] = monday0130 + 60_000;
assert.equal(policy.verdict('https://youtube.com/', strictNoCredit, monday0130), null,
  'A snooze can pause an actual zero-credit block during Strict Mode');

// A snooze permits an otherwise selected site without making it a leisure session.
const snoozed = state({ snoozes: { 'youtube.com': monday0130 + 60_000 } });
assert.equal(policy.verdict('https://youtube.com/', snoozed, monday0130), null);
assert.equal(policy.isLeisure('https://youtube.com/', snoozed, monday0130), false);
const subdomainSnoozed = state({ snoozes: { 'm.youtube.com': monday0130 + 60_000 } });
assert.equal(policy.verdict('https://m.youtube.com/watch', subdomainSnoozed, monday0130), null,
  'snooze lookup uses the actual URL host rather than the selected parent boundary');

// The after-midnight schedule tail belongs to the preceding configured day.
const overnight = state({ cloudPolicy: {
  state: { creditBalanceSeconds: 600 }, groups: [], limits: [],
  schedules: [{ scheduleId: 'sun-night', label: 'Sunday night', targetKind: 'site',
    targetKey: 'youtube.com', days: [0], startMinute: 23 * 60, endMinute: 2 * 60, isEnabled: true }],
} });
assert.equal(policy.verdict('https://youtube.com/', overnight, monday0130).reason, 'schedule');
overnight.strictMode = true;
overnight.strictEndsAt = monday0130 + 60_000;
assert.equal(policy.verdict('https://youtube.com/', overnight, monday0130).reason, 'schedule',
  'Strict Mode leaves an active schedule block intact');

// Merged usage includes app + synced website seconds, then only the local site
// delta since the summary baseline. Overlapping member domains never double count.
const merged = state({
  cloudSites: [],
  cloudPolicy: { state: { creditBalanceSeconds: 600 }, limits: [], schedules: [], groups: [
    { groupId: 'media', name: 'Media', members: [
      { targetKind: 'app', targetKey: 'com.video.app' },
      { targetKind: 'website', targetKey: 'youtube.com' },
      { targetKind: 'website', targetKey: 'm.youtube.com' },
    ], dailyLimitMinutes: 10, limitEnabled: true },
  ] },
  cloudUsage: { date: today, targets: [], groups: [{ groupId: 'media', trackedSeconds: 500 }] },
  cloudUsageBaseline: { [today]: { 'm.youtube.com': 20 } },
  stats: { [today]: { 'm.youtube.com': 60 } },
});
assert.equal(policy.verdict('https://m.youtube.com/watch', merged, monday0130), null,
  '500 remote seconds plus 40 unuploaded local seconds remains below the 570-second cap');
merged.cloudUsage.groups[0].trackedSeconds = 560;
assert.equal(policy.verdict('https://m.youtube.com/watch', merged, monday0130).reason, 'limit');
assert.equal(policy.verdict('https://m.youtube.com/watch', merged, monday0130).listId, 'group:media');
delete merged.cloudPolicy.groups[0].limitEnabled;
assert.equal(policy.verdict('https://m.youtube.com/watch', merged, monday0130).reason, 'limit',
  'legacy groups default to an enabled limit, matching Android');
merged.cloudPolicy.groups[0].limitEnabled = false;
assert.equal(policy.verdict('https://m.youtube.com/watch', merged, monday0130), null,
  'an explicitly disabled group cap is parked');

// Group caps apply even after the boundary is unselected; a standalone site cap
// also reads remote usage plus only newly tracked local seconds.
const targetLimit = state({
  cloudSites: [{ domain: 'youtube.com', isBlocked: false }],
  cloudPolicy: { state: { creditBalanceSeconds: 600 }, groups: [], schedules: [], limits: [
    { targetKind: 'site', targetKey: 'youtube.com', dailyLimitMinutes: 1, isBlockedNow: false },
  ] },
  cloudUsage: { date: today, targets: [{ targetKind: 'website', targetKey: 'youtube.com', trackedSeconds: 50 }], groups: [] },
  cloudUsageBaseline: { [today]: { 'youtube.com': 5 } },
  stats: { [today]: { 'youtube.com': 15 } },
});
assert.equal(policy.verdict('https://youtube.com/', targetLimit, monday0130).reason, 'limit');
targetLimit.strictMode = true;
targetLimit.strictEndsAt = monday0130 + 60_000;
assert.equal(policy.verdict('https://youtube.com/', targetLimit, monday0130).reason, 'limit',
  'Strict Mode leaves an exhausted target limit intact');

// Blocked-now is an explicit boundary flag even with no daily cap configured.
const blockedNowLimit = state({ cloudSites: [], cloudPolicy: {
  state: { creditBalanceSeconds: 600 }, groups: [], schedules: [],
  limits: [{ targetKind: 'site', targetKey: 'youtube.com', isBlockedNow: true }],
} });
assert.equal(policy.verdict('https://youtube.com/', blockedNowLimit, monday0130).reason, 'limit');

// A new day without a fresh summary still uses today's domain counters and baseline.
const noSummary = state({ cloudUsage: { date: '2026-09-27', targets: [], groups: [] },
  cloudPolicy: { state: { creditBalanceSeconds: 600 }, groups: [{
    groupId: 'today-group', members: [{ targetKind: 'website', targetKey: 'youtube.com' }],
    dailyLimitMinutes: 1, limitEnabled: true,
  }], limits: [], schedules: [] },
  cloudUsageBaseline: { [today]: { 'youtube.com': 0 } },
  stats: { [today]: { 'youtube.com': 60 } },
});
assert.equal(policy.verdict('https://youtube.com/', noSummary, monday0130).reason, 'limit',
  'the missing or stale usage snapshot must not discard local usage for the current date');

// Shared global leisure cap combines current server-day use and pending local
// selected-site spend, and does not carry yesterday into today's total.
const capped = state({
  cloudPrefs: { globalDailyCapMinutes: 2 },
  cloudPolicy: { state: { creditBalanceSeconds: 600, lastResetDate: today, totalScrollSecondsToday: 90 },
    groups: [], limits: [], schedules: [] },
  leisureStats: { [today]: { 'youtube.com': 40 } },
});
assert.equal(policy.verdict('https://youtube.com/', capped, monday0130).reason, 'limit');
capped.cloudPolicy.state.lastResetDate = '2026-09-27';
assert.equal(policy.verdict('https://youtube.com/', capped, monday0130), null,
  'a previous-day cloud counter is reset before adding today’s local delta');

// Local Permalock cannot be lifted by an account allowance or snooze.
const permanent = state({ permanentSites: ['youtube.com'], snoozes: { 'youtube.com': monday0130 + 60_000 } });
assert.equal(policy.verdict('https://youtube.com/', permanent, monday0130).mode, 'permanent');
assert.equal(policy.isLeisure('https://youtube.com/', permanent, monday0130), false);

console.log('extension shared policy tests passed');
