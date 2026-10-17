export type DeviceRuleKind = 'schedule' | 'dailyLimit' | 'permanent' | 'timed' | 'task';

export type RuleDraft = {
  id?: string;
  title: string;
  kind: DeviceRuleKind;
  enabled: boolean;
  selectionCount: number;
  startMinute?: number;
  endMinute?: number;
  weekdays?: number[];
  limitMinutes?: number;
  endsAt?: number;
  /** User-selected duration, used to renew a timed rule when it is saved. */
  timedDurationMinutes?: number;
  requiredFocusMinutes?: number;
};

export type ScheduleRule = Pick<RuleDraft, 'id' | 'kind' | 'enabled' | 'startMinute' | 'endMinute' | 'weekdays'>;

export function parseClockTime(value: string): number | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}

export function formatClockTime(value: number): string {
  if (!Number.isInteger(value) || value < 0 || value >= 1440) return '--:--';
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
}

function scheduleIntervals(rule: ScheduleRule): Array<[number, number]> {
  if (rule.kind !== 'schedule' || !rule.enabled || !rule.weekdays || rule.startMinute === undefined || rule.endMinute === undefined) return [];
  const weekMinutes = 7 * 1440;
  const intervals: Array<[number, number]> = [];
  for (const weekday of rule.weekdays) {
    const start = (weekday - 1) * 1440 + rule.startMinute;
    let end = (weekday - 1) * 1440 + rule.endMinute;
    if (rule.endMinute <= rule.startMinute) end += 1440;
    intervals.push([start, end]);
    if (end > weekMinutes) intervals.push([start - weekMinutes, end - weekMinutes]);
    if (start < 0) intervals.push([start + weekMinutes, end + weekMinutes]);
  }
  return intervals;
}

export function schedulesOverlap(a: ScheduleRule, b: ScheduleRule): boolean {
  const aIntervals = scheduleIntervals(a);
  const bIntervals = scheduleIntervals(b);
  return aIntervals.some(([aStart, aEnd]) => bIntervals.some(([bStart, bEnd]) => aStart < bEnd && bStart < aEnd));
}

export function ruleValidationError(
  draft: RuleDraft,
  _existing: readonly ScheduleRule[] = [],
  authorization: 'notDetermined' | 'denied' | 'approved' = 'approved',
  now = Date.now(),
): string | null {
  if (draft.title.trim().length === 0 || draft.title.trim().length > 80) return 'Enter a title between 1 and 80 characters.';
  if (draft.id !== undefined && (!/^[A-Za-z0-9_-]{1,32}$/.test(draft.id))) return 'This rule ID is invalid. Create the rule again.';
  if (!Number.isInteger(draft.selectionCount) || draft.selectionCount < 0) return 'The app selection is invalid.';
  if (draft.enabled && authorization !== 'approved') return 'Approve Screen Time access before enabling this rule.';
  if (draft.enabled && draft.selectionCount === 0) return 'Choose at least one app before enabling this rule.';

  if (draft.kind === 'schedule') {
    if (!Number.isInteger(draft.startMinute) || draft.startMinute! < 0 || draft.startMinute! >= 1440 ||
        !Number.isInteger(draft.endMinute) || draft.endMinute! < 0 || draft.endMinute! >= 1440 ||
        draft.startMinute === draft.endMinute) return 'Choose a valid start and end time.';
    if (!draft.weekdays?.length || draft.weekdays.some((day) => !Number.isInteger(day) || day < 1 || day > 7)) {
      return 'Choose at least one valid weekday.';
    }
    const duplicateDays = new Set(draft.weekdays).size !== draft.weekdays.length;
    if (duplicateDays) return 'Each weekday can only be selected once.';
    const duration = draft.endMinute! > draft.startMinute! ? draft.endMinute! - draft.startMinute! : draft.endMinute! + 1440 - draft.startMinute!;
    if (duration < 15) return 'Schedules must run for at least 15 minutes.';
  } else if (draft.kind === 'dailyLimit') {
    if (!Number.isInteger(draft.limitMinutes) || draft.limitMinutes! < 1 || draft.limitMinutes! > 1440) {
      return 'Set a daily limit from 1 minute to 24 hours.';
    }
  } else if (draft.kind === 'timed') {
    if (!Number.isFinite(draft.endsAt) || draft.endsAt! < now + 15 * 60_000) {
      return 'A timed block must last at least 15 minutes.';
    }
  } else if (draft.kind === 'task') {
    if (!Number.isInteger(draft.startMinute) || draft.startMinute! < 0 || draft.startMinute! > 1424) {
      return 'Choose a valid task time from 00:00 through 23:44.';
    }
    if (!Number.isInteger(draft.requiredFocusMinutes) || draft.requiredFocusMinutes! < 1 || draft.requiredFocusMinutes! > 1440) {
      return 'Set a focus requirement from 1 minute to 24 hours.';
    }
  }
  return null;
}

export function formatWeekdays(weekdays: readonly number[] = []): string {
  const labels = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  return [...new Set(weekdays)].sort((a, b) => a - b).map((day) => labels[day - 1] ?? '?').join(', ');
}
