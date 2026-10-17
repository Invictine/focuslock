import { test } from 'node:test';
import assert from 'node:assert/strict';
import { focusProgress } from '../src/progress';
import { localDate } from '../src/domain';
import type { Dashboard } from '../src/types';
import type { WorkRecord } from '../src/domain';

test('task progress counts its own title while daily exemption uses all focus', () => {
  const timestamp = Date.now();
  const date = localDate(timestamp);
  const record: WorkRecord = { recordId: 'one', title: 'Chemistry', durationMinutes: 15, timestamp, source: 'IOS_TIMER', earnedMinutesCredited: 3, date, tasksCompleted: 0 };
  const dashboard: Dashboard = { state: { creditBalanceSeconds: 0, totalWorkSecondsToday: 40 * 60, lastResetDate: date }, blockedApps: [], blockedWebsites: [], workRecords: [record, { ...record, recordId: 'two', title: 'Maths', durationMinutes: 25 }], prefs: null };
  const progress = focusProgress(dashboard, [record], date);
  assert.equal(progress.totalMinutes, 40);
  assert.equal(progress.byTitle.Chemistry, 15);
  assert.equal(progress.byTitle.Maths, 25);
  const withPending = focusProgress(dashboard, [{ ...record, recordId: 'three', durationMinutes: 5 }], date);
  assert.equal(withPending.totalMinutes, 45);
  assert.equal(withPending.byTitle.Chemistry, 20);
});
