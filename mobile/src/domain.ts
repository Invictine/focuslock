export type Session = {
  id: string;
  ownerId: string;
  title: string;
  targetMinutes: number;
  workRatio: number;
  startedAt: number;
  runningSince: number | null;
  accumulatedMs: number;
};

export type WorkRecord = {
  recordId: string;
  title: string;
  durationMinutes: number;
  timestamp: number;
  source: 'IOS_TIMER' | 'IOS_MANUAL';
  earnedMinutesCredited: number;
  date: string;
  tasksCompleted: number;
};

export type Envelope = {
  version: 1;
  ownerId: string;
  session: Session | null;
  pending: WorkRecord[];
};

const MAX_TARGET_MINUTES = 240;
const MAX_WORK_RATIO = 1000;
const MAX_PENDING_RECORDS = 1000;
const MAX_TIMESTAMP = 8.64e15;

function requireOwnerId(ownerId: string): string {
  if (typeof ownerId !== 'string' || ownerId.trim().length === 0 || ownerId.length > 200) {
    throw new TypeError('ownerId must be a non-empty string of at most 200 characters');
  }
  return ownerId;
}

function requireId(id: string, name = 'id', maxLength = 240): string {
  if (typeof id !== 'string' || id.trim().length === 0 || id.length > maxLength) {
    throw new TypeError(`${name} must be a non-empty string of at most ${maxLength} characters`);
  }
  return id;
}

function requireTimestamp(value: number, name: string): number {
  if (!Number.isFinite(value) || value < 0 || value > MAX_TIMESTAMP) {
    throw new TypeError(`${name} must be a finite timestamp`);
  }
  return value;
}

function validateTitle(value: string): string {
  if (typeof value !== 'string') throw new TypeError('title must be a string');
  const title = value.trim();
  if (title.length === 0 || title.length > 160) {
    throw new RangeError('title must contain 1 to 160 characters after trimming');
  }
  return title;
}

function validateSession(value: unknown, envelopeOwnerId: string): Session {
  if (!isObject(value)) throw new TypeError('session must be an object');
  const session = value as Record<string, unknown>;
  const id = requireId(session.id as string, 'session.id', 180);
  const ownerId = requireOwnerId(session.ownerId as string);
  if (ownerId !== envelopeOwnerId) throw new TypeError('session owner does not match envelope owner');
  const title = validateTitle(session.title as string);
  const targetMinutes = session.targetMinutes;
  if (typeof targetMinutes !== 'number' || !Number.isInteger(targetMinutes) || targetMinutes < 1 || targetMinutes > MAX_TARGET_MINUTES) {
    throw new RangeError('session.targetMinutes must be an integer from 1 to 240');
  }
  const workRatio = session.workRatio;
  if (typeof workRatio !== 'number' || !Number.isFinite(workRatio) || workRatio < 1 || workRatio > MAX_WORK_RATIO) {
    throw new RangeError('session.workRatio must be finite and from 1 to 1000');
  }
  const startedAt = requireTimestamp(session.startedAt as number, 'session.startedAt');
  const runningSince = session.runningSince === null
    ? null
    : requireTimestamp(session.runningSince as number, 'session.runningSince');
  const accumulatedMs = session.accumulatedMs;
  if (typeof accumulatedMs !== 'number' || !Number.isFinite(accumulatedMs) || accumulatedMs < 0 || accumulatedMs > (targetMinutes as number) * 60_000) {
    throw new RangeError('session.accumulatedMs is outside the session duration');
  }
  return { id, ownerId, title, targetMinutes: targetMinutes as number, workRatio, startedAt, runningSince, accumulatedMs };
}

function validateRecord(value: unknown): WorkRecord {
  if (!isObject(value)) throw new TypeError('pending record must be an object');
  const record = value as Record<string, unknown>;
  const recordId = requireId(record.recordId as string, 'record.recordId');
  const title = validateTitle(record.title as string);
  if (record.source !== 'IOS_TIMER' && record.source !== 'IOS_MANUAL') {
    throw new TypeError('record.source is invalid');
  }
  const source = record.source;
  const durationMinutes = record.durationMinutes;
  const maximumDuration = source === 'IOS_TIMER' ? MAX_TARGET_MINUTES : 1440;
  const minimumDuration = source === 'IOS_TIMER' ? 1 : 0;
  if (typeof durationMinutes !== 'number' || !Number.isInteger(durationMinutes) || durationMinutes < minimumDuration || durationMinutes > maximumDuration) {
    throw new RangeError(`record.durationMinutes must be an integer from ${minimumDuration} to ${maximumDuration}`);
  }
  const timestamp = requireTimestamp(record.timestamp as number, 'record.timestamp');
  const tasksCompleted = record.tasksCompleted;
  if (typeof tasksCompleted !== 'number' || !Number.isInteger(tasksCompleted) || tasksCompleted < 0 || tasksCompleted > 100) {
    throw new RangeError('record.tasksCompleted must be an integer from 0 to 100');
  }
  if (source === 'IOS_TIMER' && tasksCompleted !== 0) {
    throw new TypeError('timer records cannot complete tasks');
  }
  if (source === 'IOS_MANUAL' && durationMinutes === 0 && tasksCompleted === 0) {
    throw new RangeError('a zero-minute manual record must include a completed task');
  }
  const earnedMinutesCredited = record.earnedMinutesCredited;
  const maximumEarned = source === 'IOS_TIMER' ? MAX_TARGET_MINUTES : 1440 + 100 * 60;
  if (typeof earnedMinutesCredited !== 'number' || !Number.isInteger(earnedMinutesCredited) || earnedMinutesCredited < 0 || earnedMinutesCredited > maximumEarned) {
    throw new RangeError('record.earnedMinutesCredited is outside the supported range');
  }
  const date = record.date;
  if (typeof date !== 'string' || !isCalendarDate(date)) {
    throw new TypeError('record.date must be a valid YYYY-MM-DD calendar date');
  }
  return {
    recordId, title, durationMinutes: durationMinutes as number, timestamp, source,
    earnedMinutesCredited: earnedMinutesCredited as number, date, tasksCompleted: tasksCompleted as number,
  };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isCalendarDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1) return false;
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day <= daysInMonth[month - 1];
}

function validateEnvelope(envelope: Envelope, expectedOwnerId?: string): Envelope {
  if (!isObject(envelope)) throw new TypeError('envelope must be an object');
  const value = envelope as unknown as Record<string, unknown>;
  if (value.version !== 1) throw new TypeError('unsupported envelope version');
  const ownerId = requireOwnerId(value.ownerId as string);
  if (expectedOwnerId !== undefined && ownerId !== expectedOwnerId) throw new TypeError('envelope belongs to a different account');
  const session = value.session === null ? null : validateSession(value.session, ownerId);
  if (!Array.isArray(value.pending) || value.pending.length > MAX_PENDING_RECORDS) {
    throw new TypeError('envelope.pending must be an array of at most 1000 records');
  }
  const pending = value.pending.map(validateRecord);
  const ids = new Set<string>();
  for (const record of pending) {
    if (ids.has(record.recordId)) throw new TypeError('envelope contains duplicate pending record IDs');
    ids.add(record.recordId);
  }
  return { version: 1, ownerId, session, pending };
}

export function emptyEnvelope(ownerId: string): Envelope {
  return { version: 1, ownerId: requireOwnerId(ownerId), session: null, pending: [] };
}

export function localDate(timestamp: number): string {
  requireTimestamp(timestamp, 'timestamp');
  const date = new Date(timestamp);
  const year = String(date.getFullYear()).padStart(4, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function createSession(input: {
  id: string;
  ownerId: string;
  title: string;
  targetMinutes: number;
  workRatio: number;
  now: number;
}): Session {
  const ownerId = requireOwnerId(input.ownerId);
  const targetMinutes = input.targetMinutes;
  const workRatio = input.workRatio;
  if (!Number.isInteger(targetMinutes) || targetMinutes < 1 || targetMinutes > MAX_TARGET_MINUTES) {
    throw new RangeError('targetMinutes must be an integer from 1 to 240');
  }
  if (!Number.isFinite(workRatio) || workRatio < 1 || workRatio > MAX_WORK_RATIO) {
    throw new RangeError('workRatio must be finite and from 1 to 1000');
  }
  const now = requireTimestamp(input.now, 'now');
  return {
    id: requireId(input.id, 'id', 180), ownerId, title: validateTitle(input.title), targetMinutes, workRatio,
    startedAt: now, runningSince: now, accumulatedMs: 0,
  };
}

export function createManualRecord(input: {
  id: string;
  title: string;
  durationMinutes: number;
  tasksCompleted: number;
  workRatio: number;
  taskBonusMinutes: number;
  now: number;
}): WorkRecord {
  const durationMinutes = input.durationMinutes;
  if (!Number.isInteger(durationMinutes) || durationMinutes < 0 || durationMinutes > 1440) {
    throw new RangeError('durationMinutes must be an integer from 0 to 1440');
  }
  const tasksCompleted = input.tasksCompleted;
  if (!Number.isInteger(tasksCompleted) || tasksCompleted < 0 || tasksCompleted > 100) {
    throw new RangeError('tasksCompleted must be an integer from 0 to 100');
  }
  if (durationMinutes === 0 && tasksCompleted === 0) {
    throw new RangeError('a zero-minute manual record must include a completed task');
  }
  if (!Number.isFinite(input.workRatio) || input.workRatio < 1 || input.workRatio > MAX_WORK_RATIO) {
    throw new RangeError('workRatio must be finite and from 1 to 1000');
  }
  const taskBonusMinutes = input.taskBonusMinutes;
  if (!Number.isInteger(taskBonusMinutes) || taskBonusMinutes < 0 || taskBonusMinutes > 60) {
    throw new RangeError('taskBonusMinutes must be an integer from 0 to 60');
  }
  const timestamp = requireTimestamp(input.now, 'now');
  return {
    recordId: `ios_manual_${requireId(input.id, 'id', 229)}`,
    title: validateTitle(input.title),
    durationMinutes,
    timestamp,
    source: 'IOS_MANUAL',
    earnedMinutesCredited: Math.floor(durationMinutes / input.workRatio) + tasksCompleted * taskBonusMinutes,
    date: localDate(timestamp),
    tasksCompleted,
  };
}

export function elapsedMilliseconds(session: Session, now: number): number {
  const validSession = validateSession(session, session.ownerId);
  requireTimestamp(now, 'now');
  const currentRun = validSession.runningSince === null ? 0 : Math.max(0, now - validSession.runningSince);
  return Math.min(validSession.targetMinutes * 60_000, validSession.accumulatedMs + currentRun);
}

export function pauseSession(session: Session, now: number): Session {
  const validSession = validateSession(session, session.ownerId);
  requireTimestamp(now, 'now');
  if (validSession.runningSince === null) return validSession;
  return { ...validSession, runningSince: null, accumulatedMs: elapsedMilliseconds(validSession, now) };
}

export function resumeSession(session: Session, now: number): Session {
  const validSession = validateSession(session, session.ownerId);
  requireTimestamp(now, 'now');
  if (validSession.runningSince !== null) return validSession;
  return { ...validSession, runningSince: now };
}

export function completeSession(envelope: Envelope, now: number): Envelope {
  const validEnvelope = validateEnvelope(envelope);
  requireTimestamp(now, 'now');
  const session = validEnvelope.session;
  if (session === null) throw new TypeError('there is no active session to complete');
  const durationMinutes = Math.floor(elapsedMilliseconds(session, now) / 60_000);
  if (durationMinutes < 1) throw new RangeError('a session must include at least one elapsed minute');
  const recordId = `ios_timer_${session.id}`;
  const alreadyPending = validEnvelope.pending.some((record) => record.recordId === recordId);
  if (!alreadyPending && validEnvelope.pending.length >= MAX_PENDING_RECORDS) {
    throw new RangeError('cannot add a record because the pending queue is full');
  }
  const pending = alreadyPending ? validEnvelope.pending : [
    ...validEnvelope.pending,
    {
      recordId,
      title: session.title,
      durationMinutes,
      timestamp: now,
      source: 'IOS_TIMER' as const,
      earnedMinutesCredited: Math.floor(durationMinutes / session.workRatio),
      date: localDate(now),
      tasksCompleted: 0 as const,
    },
  ];
  return { ...validEnvelope, session: null, pending };
}

export function appendWorkRecord(envelope: Envelope, record: WorkRecord): Envelope {
  const validEnvelope = validateEnvelope(envelope);
  const validRecord = validateRecord(record);
  if (validEnvelope.pending.some((pending) => pending.recordId === validRecord.recordId)) return validEnvelope;
  if (validEnvelope.pending.length >= MAX_PENDING_RECORDS) {
    throw new RangeError('cannot add a record because the pending queue is full');
  }
  return { ...validEnvelope, pending: [...validEnvelope.pending, validRecord] };
}

export function acknowledgeRecord(envelope: Envelope, recordId: string): Envelope {
  const validEnvelope = validateEnvelope(envelope);
  const id = requireId(recordId, 'recordId');
  return { ...validEnvelope, pending: validEnvelope.pending.filter((record) => record.recordId !== id) };
}

export function parseEnvelope(raw: string | null, ownerId: string): Envelope {
  const expectedOwnerId = requireOwnerId(ownerId);
  if (raw === null) return emptyEnvelope(expectedOwnerId);
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new TypeError('stored envelope is not valid JSON');
  }
  if (!isObject(parsed)) throw new TypeError('stored envelope must be an object');
  return validateEnvelope(parsed as unknown as Envelope, expectedOwnerId);
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) throw new RangeError('duration must be finite and non-negative');
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}
