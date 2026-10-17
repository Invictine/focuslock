import assert from 'node:assert/strict';
import test from 'node:test';
import {
  acknowledgeRecord,
  completeSession,
  createSession,
  emptyEnvelope,
  type Envelope,
} from '../src/domain';
import { SessionStore, type KeyValueStorage } from '../src/storage';

class MemoryStorage implements KeyValueStorage {
  readonly values = new Map<string, string>();
  reads = 0;
  writes = 0;
  failNextWrite = false;

  async getItem(key: string): Promise<string | null> {
    this.reads += 1;
    return this.values.get(key) ?? null;
  }

  async setItem(key: string, value: string): Promise<void> {
    this.writes += 1;
    if (this.failNextWrite) {
      this.failNextWrite = false;
      throw new Error('simulated storage failure');
    }
    this.values.set(key, value);
  }
}

const minute = 60_000;
const startTime = 1_700_000_000_000;

function session(id: string, ownerId = 'user-1') {
  return createSession({ id, ownerId, title: id, targetMinutes: 30, workRatio: 2, now: startTime });
}

function serialized(envelope: Envelope): string {
  return JSON.stringify(envelope);
}

test('interleaved completion and acknowledgement updates preserve both changes', async () => {
  const storage = new MemoryStorage();
  const prior = completeSession(
    { ...emptyEnvelope('user-1'), session: session('prior') },
    startTime + 2 * minute,
  ).pending[0];
  const active = session('active');
  storage.values.set('focuslock.sessions.v1.user-1', serialized({
    ...emptyEnvelope('user-1'), session: active, pending: [prior],
  }));
  const store = new SessionStore(storage, 'user-1');

  const loadPromise = store.load();
  const completionPromise = store.update((current) => completeSession(current, startTime + 4 * minute));
  const acknowledgementPromise = store.update((current) => acknowledgeRecord(current, prior.recordId));
  await Promise.all([loadPromise, completionPromise, acknowledgementPromise]);

  const final = await store.load();
  assert.equal(final.session, null);
  assert.deepEqual(final.pending.map((record) => record.recordId), ['ios_timer_active']);
  assert.equal(storage.reads, 1, 'the store reads its persisted envelope once');
});

test('failed writes do not publish or replace current state and the queue accepts a retry', async () => {
  const storage = new MemoryStorage();
  const store = new SessionStore(storage, 'user-1');
  const before = await store.load();
  const originalRaw = storage.values.get(store.key);
  const notifications: Envelope[] = [];
  store.subscribe((envelope) => notifications.push(envelope));

  storage.failNextWrite = true;
  await assert.rejects(
    store.update((current) => ({ ...current, session: session('first') })),
    /simulated storage failure/,
  );
  assert.deepEqual(await store.load(), before);
  assert.equal(storage.values.get(store.key), originalRaw);
  assert.deepEqual(notifications, []);

  const saved = await store.update((current) => ({ ...current, session: session('retry') }));
  assert.equal(saved.session?.id, 'retry');
  assert.equal(notifications.length, 1);
  assert.equal((await store.load()).session?.id, 'retry');
});

test('corrupt persisted data rejects load and update without overwriting it', async () => {
  const storage = new MemoryStorage();
  storage.values.set('focuslock.sessions.v1.user-1', '{corrupt');
  const store = new SessionStore(storage, 'user-1');

  await assert.rejects(store.load(), /valid JSON/);
  await assert.rejects(store.update((current) => current), /valid JSON/);
  assert.equal(storage.values.get(store.key), '{corrupt');
  assert.equal(storage.writes, 0);
  assert.equal(storage.reads, 2, 'failed loads do not cache corrupt data');
});

test('updates reject a transform result owned by another account', async () => {
  const storage = new MemoryStorage();
  const store = new SessionStore(storage, 'user-1');
  await store.load();

  await assert.rejects(
    store.update(() => emptyEnvelope('user-2')),
    /different account/,
  );
  assert.equal(storage.writes, 0);
  assert.equal((await store.load()).ownerId, 'user-1');
});

test('account namespaces are isolated in a shared key-value adapter', async () => {
  const storage = new MemoryStorage();
  const first = new SessionStore(storage, 'user/one');
  const second = new SessionStore(storage, 'user two');
  assert.equal(first.key, 'focuslock.sessions.v1.user%2Fone');
  assert.equal(second.key, 'focuslock.sessions.v1.user%20two');

  await Promise.all([
    first.update((current) => ({ ...current, session: session('one', 'user/one') })),
    second.update((current) => ({ ...current, session: session('two', 'user two') })),
  ]);
  assert.equal((await first.load()).session?.id, 'one');
  assert.equal((await second.load()).session?.id, 'two');
  assert.equal(storage.values.size, 2);
});

test('returned and subscriber envelopes are copies, and unsubscribe stops notifications', async () => {
  const storage = new MemoryStorage();
  const store = new SessionStore(storage, 'user-1');
  await store.load();
  let notificationCount = 0;
  const unsubscribe = store.subscribe((envelope) => {
    notificationCount += 1;
    if (envelope.session) envelope.session.title = 'listener mutation';
    envelope.pending.push({
      recordId: 'listener-only', title: 'Mutation', durationMinutes: 1,
      timestamp: startTime, source: 'IOS_TIMER', earnedMinutesCredited: 0,
      date: '2023-11-14', tasksCompleted: 0,
    });
  });

  const returned = await store.update((current) => ({ ...current, session: session('saved') }));
  if (returned.session) returned.session.title = 'caller mutation';
  returned.pending.push({
    recordId: 'caller-only', title: 'Mutation', durationMinutes: 1,
    timestamp: startTime, source: 'IOS_TIMER', earnedMinutesCredited: 0,
    date: '2023-11-14', tasksCompleted: 0,
  });
  assert.equal((await store.load()).session?.title, 'saved');
  assert.deepEqual((await store.load()).pending, []);

  unsubscribe();
  await store.update((current) => ({ ...current, session: null }));
  assert.equal(notificationCount, 1);
});
