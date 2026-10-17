import assert from 'node:assert/strict';
import test from 'node:test';
import {
  appendWorkRecord,
  acknowledgeRecord,
  completeSession,
  createManualRecord,
  createSession,
  elapsedMilliseconds,
  emptyEnvelope,
  formatDuration,
  localDate,
  parseEnvelope,
  pauseSession,
  resumeSession,
  type Envelope,
  type Session,
} from '../src/domain';

const minute = 60_000;

function session(overrides: Partial<Parameters<typeof createSession>[0]> = {}): Session {
  return createSession({
    id: 'session-1', ownerId: 'user-1', title: 'Deep work', targetMinutes: 60,
    workRatio: 2, now: 1_700_000_000_000, ...overrides,
  });
}

test('session clock handles pauses, restart, background time, and target caps', () => {
  const started = session();
  assert.equal(elapsedMilliseconds(started, started.startedAt + 5 * minute), 5 * minute);
  assert.equal(elapsedMilliseconds(started, started.startedAt - minute), 0, 'a backward clock adds no time');

  const paused = pauseSession(started, started.startedAt + 5 * minute);
  assert.equal(paused.accumulatedMs, 5 * minute);
  assert.equal(elapsedMilliseconds(paused, started.startedAt + 2 * 60 * minute), 5 * minute, 'paused background time is ignored');
  const resumed = resumeSession(paused, started.startedAt + 2 * 60 * minute);
  assert.equal(elapsedMilliseconds(resumed, started.startedAt + 2 * 60 * minute + 3 * minute), 8 * minute);

  const capped = pauseSession(resumed, started.startedAt + 2 * 60 * minute + 100 * minute);
  assert.equal(capped.accumulatedMs, 60 * minute);
  assert.equal(elapsedMilliseconds(capped, started.startedAt + 500 * minute), 60 * minute);
});

test('input validation trims titles and rejects invalid session configuration', () => {
  assert.equal(session({ title: '  Deep work  ' }).title, 'Deep work');
  for (const patch of [
    { title: '   ' },
    { title: 'x'.repeat(161) },
    { targetMinutes: 0 },
    { targetMinutes: 241 },
    { targetMinutes: 1.5 },
    { workRatio: 0.5 },
    { workRatio: Number.POSITIVE_INFINITY },
    { now: Number.NaN },
  ]) {
    assert.throws(() => session(patch), { name: /TypeError|RangeError/ });
  }
});

test('persisted envelopes validate shape, bounds, account ownership, and pending records', () => {
  const saved: Envelope = {
    ...emptyEnvelope('user-1'),
    session: session(),
    pending: [],
  };
  assert.deepEqual(parseEnvelope(JSON.stringify(saved), 'user-1'), saved);
  assert.deepEqual(parseEnvelope(null, 'user-2'), emptyEnvelope('user-2'));
  assert.throws(() => parseEnvelope(JSON.stringify(saved), 'user-2'), /different account/);
  assert.throws(() => parseEnvelope('{broken', 'user-1'), /valid JSON/);
  assert.throws(() => parseEnvelope(JSON.stringify({ ...saved, session: { ...saved.session, ownerId: 'user-2' } }), 'user-1'), /session owner/);
  assert.throws(() => parseEnvelope(JSON.stringify({ ...saved, session: { ...saved.session, targetMinutes: 999 } }), 'user-1'), /targetMinutes/);
  assert.throws(() => parseEnvelope(JSON.stringify({ ...saved, pending: [{ recordId: 'r', tasksCompleted: 1 }] }), 'user-1'), /title/);
});

test('completion creates one task-free record from actual elapsed time and retries keep its ID', () => {
  const active = session({ title: '  Read chemistry  ', targetMinutes: 10, workRatio: 2 });
  const envelope: Envelope = { ...emptyEnvelope('user-1'), session: active };
  const now = active.startedAt + 5 * minute + 59_999;
  assert.throws(() => completeSession(envelope, active.startedAt + minute - 1), /at least one/);

  const completed = completeSession(envelope, now);
  assert.equal(completed.session, null);
  assert.equal(completed.pending.length, 1);
  assert.deepEqual(completed.pending[0], {
    recordId: 'ios_timer_session-1', title: 'Read chemistry', durationMinutes: 5,
    timestamp: now, source: 'IOS_TIMER', earnedMinutesCredited: 2,
    date: localDate(now), tasksCompleted: 0,
  });
  const retried = completeSession({ ...envelope, pending: completed.pending }, now + minute);
  assert.equal(retried.pending.length, 1);
  assert.equal(retried.pending[0].recordId, completed.pending[0].recordId);
  assert.equal(retried.pending[0].timestamp, now, 'an existing record is preserved as-is');
});

test('persisted completion dates survive timezone changes', () => {
  const originalTimezone = process.env.TZ;
  try {
    process.env.TZ = 'UTC';
    const active = session();
    const completed = completeSession(
      { ...emptyEnvelope('user-1'), session: active },
      Date.UTC(2024, 11, 31, 23, 30),
    );
    assert.equal(completed.pending[0].date, '2024-12-31');

    process.env.TZ = 'Pacific/Kiritimati';
    assert.equal(localDate(completed.pending[0].timestamp), '2025-01-01');
    const restored = parseEnvelope(JSON.stringify(completed), 'user-1');
    assert.equal(restored.pending[0].date, '2024-12-31', 'the original local date remains durable');
  } finally {
    if (originalTimezone === undefined) delete process.env.TZ;
    else process.env.TZ = originalTimezone;
  }
});

test('completion IDs include the session prefix and a full pending queue rejects new records', () => {
  const longId = 's'.repeat(180);
  const active = session({ id: longId, targetMinutes: 5 });
  const completed = completeSession(
    { ...emptyEnvelope('user-1'), session: active },
    active.startedAt + minute,
  );
  assert.equal(completed.pending[0].recordId, `ios_timer_${longId}`);
  assert.doesNotThrow(() => parseEnvelope(JSON.stringify(completed), 'user-1'));
  assert.throws(() => session({ id: 's'.repeat(181) }), /at most 180/);

  const fullQueue: Envelope = {
    ...emptyEnvelope('user-1'),
    session: session({ id: 'next-session', targetMinutes: 5 }),
    pending: Array.from({ length: 1000 }, (_, index) => ({
      recordId: `pending-${index}`,
      title: 'Prior work',
      durationMinutes: 1,
      timestamp: 1_700_000_000_000,
      source: 'IOS_TIMER' as const,
      earnedMinutesCredited: 0,
      date: localDate(1_700_000_000_000),
      tasksCompleted: 0 as const,
    })),
  };
  assert.throws(() => completeSession(fullQueue, fullQueue.session!.startedAt + minute), /queue is full/);
  const manual = createManualRecord({
    id: 'manual-at-capacity', title: 'Task', durationMinutes: 0, tasksCompleted: 1,
    workRatio: 2, taskBonusMinutes: 5, now: 1_700_000_000_000,
  });
  assert.throws(() => appendWorkRecord(fullQueue, manual), /queue is full/);
});

test('manual records floor time credit, add explicit task bonuses, and keep tasks separate', () => {
  const manual = createManualRecord({
    id: 'manual-1', title: '  Review notes  ', durationMinutes: 47,
    tasksCompleted: 3, workRatio: 2.5, taskBonusMinutes: 7, now: 1_700_000_000_000,
  });
  assert.deepEqual(manual, {
    recordId: 'ios_manual_manual-1', title: 'Review notes', durationMinutes: 47,
    timestamp: 1_700_000_000_000, source: 'IOS_MANUAL', earnedMinutesCredited: 39,
    date: localDate(1_700_000_000_000), tasksCompleted: 3,
  });

  const taskOnly = createManualRecord({
    id: 'task-only', title: 'Finished tasks', durationMinutes: 0,
    tasksCompleted: 2, workRatio: 3, taskBonusMinutes: 5, now: 1_700_000_000_000,
  });
  assert.equal(taskOnly.durationMinutes, 0);
  assert.equal(taskOnly.earnedMinutesCredited, 10);
});

test('manual record validation bounds duration, tasks, bonuses, and rewards', () => {
  const valid = {
    id: 'manual-valid', title: 'Manual work', durationMinutes: 30,
    tasksCompleted: 1, workRatio: 2, taskBonusMinutes: 5, now: 1_700_000_000_000,
  };
  for (const patch of [
    { durationMinutes: -1 },
    { durationMinutes: 1441 },
    { durationMinutes: 1.5 },
    { durationMinutes: 0, tasksCompleted: 0 },
    { tasksCompleted: -1 },
    { tasksCompleted: 101 },
    { tasksCompleted: 1.5 },
    { taskBonusMinutes: -1 },
    { taskBonusMinutes: 61 },
    { workRatio: 0.5 },
    { workRatio: Number.NaN },
  ]) {
    assert.throws(() => createManualRecord({ ...valid, ...patch }));
  }
});

test('manual records validate on persistence and append idempotently by stable ID', () => {
  const manual = createManualRecord({
    id: 'manual-retry', title: 'Read', durationMinutes: 0,
    tasksCompleted: 1, workRatio: 2, taskBonusMinutes: 4, now: 1_700_000_000_000,
  });
  const empty = emptyEnvelope('user-1');
  const appended = appendWorkRecord(empty, manual);
  assert.deepEqual(appended.pending, [manual]);
  assert.deepEqual(appendWorkRecord(appended, manual), appended);
  assert.deepEqual(parseEnvelope(JSON.stringify(appended), 'user-1'), appended);

  const timer = completeSession({ ...empty, session: session({ id: 'timer-tasks', targetMinutes: 5 }) }, 1_700_000_060_000);
  assert.throws(() => parseEnvelope(JSON.stringify({
    ...timer,
    pending: [{ ...timer.pending[0], tasksCompleted: 1 }],
  }), 'user-1'), /timer records cannot complete tasks/);
});

test('acknowledgement removes only the requested pending record', () => {
  const active = session({ targetMinutes: 5, now: 1_700_000_000_000 });
  const withRecord = completeSession({
    ...emptyEnvelope('user-1'), session: active,
  }, active.startedAt + 3 * minute);
  const afterAck = acknowledgeRecord(withRecord, 'ios_timer_session-1');
  assert.deepEqual(afterAck.pending, []);
  assert.equal(afterAck.ownerId, 'user-1');
  assert.deepEqual(acknowledgeRecord(withRecord, 'another-record'), withRecord);
});

test('local date uses the device local calendar date', () => {
  const sample = new Date(2025, 0, 2, 23, 45, 0).getTime();
  assert.equal(localDate(sample), '2025-01-02');
});

test('duration formatting is MM:SS and rejects invalid values', () => {
  assert.equal(formatDuration(0), '00:00');
  assert.equal(formatDuration(61_999), '01:01');
  assert.equal(formatDuration(3_661_000), '61:01');
  assert.throws(() => formatDuration(-1), /non-negative/);
});
