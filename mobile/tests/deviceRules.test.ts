import assert from 'node:assert/strict';
import test from 'node:test';
import {
  formatClockTime,
  formatWeekdays,
  parseClockTime,
  ruleValidationError,
  schedulesOverlap,
  type RuleDraft,
} from '../src/deviceRules';

test('clock fields parse strictly and format calendar minutes', () => {
  assert.equal(parseClockTime('9:05'), 545);
  assert.equal(parseClockTime('23:59'), 1439);
  assert.equal(parseClockTime('24:00'), null);
  assert.equal(parseClockTime('12:60'), null);
  assert.equal(parseClockTime('noon'), null);
  assert.equal(formatClockTime(545), '09:05');
  assert.equal(formatClockTime(1440), '--:--');
});

test('weekday labels use calendar numbering and deduplicate before display', () => {
  assert.equal(formatWeekdays([2, 3, 4, 5, 6]), 'Mon, Tue, Wed, Thu, Fri');
  assert.equal(formatWeekdays([7, 1, 1]), 'Sun, Sat');
});

test('schedules detect overlaps across midnight and the Saturday-to-Sunday boundary', () => {
  const saturdayNight = {
    kind: 'schedule' as const, enabled: true, weekdays: [7], startMinute: 1380, endMinute: 60,
  };
  const sundayEarly = {
    kind: 'schedule' as const, enabled: true, weekdays: [1], startMinute: 30, endMinute: 120,
  };
  assert.equal(schedulesOverlap(saturdayNight, sundayEarly), true);
  assert.equal(schedulesOverlap(sundayEarly, saturdayNight), true);

  const nextWindow = {
    kind: 'schedule' as const, enabled: true, weekdays: [1], startMinute: 120, endMinute: 180,
  };
  assert.equal(schedulesOverlap(sundayEarly, nextWindow), false, 'touching endpoints do not overlap');
});

test('enabled schedules require consent, app selection, valid weekdays, and a 15-minute monitoring interval', () => {
  const base: RuleDraft = {
    id: 'new-rule', title: 'Weekday focus', kind: 'schedule', enabled: true,
    selectionCount: 1, startMinute: 9 * 60, endMinute: 17 * 60, weekdays: [2, 3, 4, 5, 6],
  };
  const existing = [{
    id: 'existing', kind: 'schedule' as const, enabled: true,
    startMinute: 16 * 60, endMinute: 18 * 60, weekdays: [2],
  }];
  assert.equal(ruleValidationError(base, existing, 'approved'), null, 'independent app selections may use overlapping time windows');
  assert.match(ruleValidationError(base, [], 'denied')!, /Approve Screen Time/);
  assert.match(ruleValidationError({ ...base, selectionCount: 0 }, [], 'approved')!, /Choose at least one app/);
  assert.match(ruleValidationError({ ...base, weekdays: [2, 2] }, [], 'approved')!, /once/);
  assert.match(ruleValidationError({ ...base, startMinute: 9 * 60, endMinute: 9 * 60 + 14 }, [], 'approved')!, /15 minutes/);
  assert.equal(ruleValidationError({ ...base, startMinute: 23 * 60 + 50, endMinute: 5 }, [], 'approved'), null, 'overnight interval totals 15 minutes');
  assert.equal(ruleValidationError({ ...base, enabled: false, selectionCount: 0 }, [], 'notDetermined'), null);
});

test('daily limits, timed blocks, tasks, and title constraints are checked', () => {
  const basic = { id: 'rule', title: 'Rule', enabled: false, selectionCount: 0 };
  assert.match(ruleValidationError({ ...basic, kind: 'dailyLimit', limitMinutes: 0 })!, /daily limit/);
  assert.equal(ruleValidationError({ ...basic, kind: 'dailyLimit', limitMinutes: 30 }), null);
  assert.match(ruleValidationError({ ...basic, kind: 'timed', endsAt: 1000 }, [], 'approved', 1000)!, /15 minutes/);
  assert.equal(ruleValidationError({ ...basic, kind: 'timed', endsAt: 1_000_000 }, [], 'approved', 1000), null);
  assert.match(ruleValidationError({ ...basic, kind: 'task', startMinute: 7 * 60, requiredFocusMinutes: 0 })!, /focus requirement/);
  assert.equal(ruleValidationError({ ...basic, kind: 'task', startMinute: 7 * 60, requiredFocusMinutes: 25 }), null);
  assert.match(ruleValidationError({ ...basic, kind: 'task', startMinute: 1425, requiredFocusMinutes: 25 })!, /task time/);
  assert.equal(ruleValidationError({ ...basic, kind: 'task', startMinute: 1424, requiredFocusMinutes: 25 }), null);
  assert.match(ruleValidationError({ ...basic, kind: 'permanent', title: 'x'.repeat(81) })!, /1 and 80/);
  assert.match(ruleValidationError({ ...basic, id: 'id'.repeat(17), kind: 'permanent' })!, /ID/);
});

test('expired timed rules are rejected until the editor supplies a fresh duration', () => {
  const basic = { id: 'r-abc-12345678', title: 'Focus block', enabled: false, selectionCount: 1, kind: 'timed' as const };
  assert.match(ruleValidationError({ ...basic, endsAt: 1_000 }, [], 'approved', 1_000)!, /15 minutes/);
  assert.equal(ruleValidationError({ ...basic, endsAt: 1_000 + 15 * 60_000 }, [], 'approved', 1_000), null);
});
