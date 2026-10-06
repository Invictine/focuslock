import { beforeEach, expect, it } from "vitest";
import { accountUsage, acknowledgeSync, enqueueSync, peekSync, unacknowledgedUsage,
  enqueueMutation, pendingMutations, acknowledgeMutation } from "../desktop/src/offlineQueue";
import { replayMutations } from "../desktop/src/offlineQueue";

let disk: Map<string, string>;
beforeEach(() => {
  disk = new Map();
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { localStorage: {
    getItem: (key: string) => disk.get(key) ?? null,
    setItem: (key: string, value: string) => disk.set(key, value),
    removeItem: (key: string) => disk.delete(key),
  } } });
});
const bucket = (seconds: number, stamp = 100) => ({ date: '2026-09-19', targetKind: 'website' as const,
  targetKey: 'example.com', targetLabel: 'Example', trackedSeconds: seconds, updatedAt: stamp });
const heartbeat = { deviceId: 'pc', name: 'PC', platform: 'windows' as const, appVersion: '1',
  trackingStatus: 'active' as const, lastSeen: 100 };

it('keeps unacknowledged usage on disk and isolates account queues', () => {
  enqueueSync('alice', 'pc', heartbeat, [bucket(10)]);
  expect(peekSync('bob', 'pc').usage).toEqual([]);
  expect(peekSync('alice', 'pc').usage[0].trackedSeconds).toBe(10);
  const sent = peekSync('alice', 'pc');
  enqueueSync('alice', 'pc', heartbeat, [bucket(20)]);
  acknowledgeSync('alice', 'pc', sent);
  expect(peekSync('alice', 'pc').usage[0].trackedSeconds).toBe(20);
  acknowledgeSync('alice', 'pc', peekSync('alice', 'pc'));
  expect(peekSync('alice', 'pc').usage).toEqual([]);
  enqueueSync('alice', 'pc', heartbeat, [bucket(20, 900)]);
  expect(peekSync('alice', 'pc').usage).toEqual([]);
});

it('does not upload another account\'s global native usage on switch', () => {
  expect(accountUsage('alice', 'pc', [bucket(120)])[0].trackedSeconds).toBe(120);
  expect(accountUsage('bob', 'pc', [bucket(120)])).toEqual([]);
  expect(accountUsage('bob', 'pc', [bucket(150)])[0].trackedSeconds).toBe(30);
  expect(accountUsage('alice', 'pc', [bucket(170)])[0].trackedSeconds).toBe(120);
  expect(accountUsage('alice', 'pc', [bucket(200)])[0].trackedSeconds).toBe(150);
});

it('aggregates same-domain native rows before calculating account deltas', () => {
  expect(accountUsage('alice', 'pc', [bucket(50), bucket(70)])[0].trackedSeconds).toBe(120);
  expect(accountUsage('alice', 'pc', [bucket(60), bucket(80)])[0].trackedSeconds).toBe(140);
});

it('only queues counters above the last acknowledged value and keeps failed rows for retry', () => {
  const old = bucket(120);
  const fresh = { ...bucket(15), targetKey: 'fresh.example' };
  enqueueSync('alice', 'pc', heartbeat, [old]);
  acknowledgeSync('alice', 'pc', peekSync('alice', 'pc'));
  const cumulative = accountUsage('alice', 'pc', [old, fresh]);
  expect(unacknowledgedUsage(cumulative, peekSync('alice', 'pc').acknowledged)).toEqual([fresh]);
  enqueueSync('alice', 'pc', heartbeat, unacknowledgedUsage(cumulative, peekSync('alice', 'pc').acknowledged));
  expect(peekSync('alice', 'pc').usage).toEqual([fresh]);
  // A failed upload is still on disk, even if a later scan has no new delta.
  expect(unacknowledgedUsage(accountUsage('alice', 'pc', [old, fresh]), peekSync('alice', 'pc').acknowledged)).toEqual([fresh]);
  expect(peekSync('alice', 'pc').usage).toEqual([fresh]);
  acknowledgeSync('alice', 'pc', peekSync('alice', 'pc'));
  expect(unacknowledgedUsage(accountUsage('alice', 'pc', [old, fresh]), peekSync('alice', 'pc').acknowledged)).toEqual([]);
});

it('acknowledges expired cloud rows without removing device-local usage', () => {
  const local = accountUsage('alice', 'pc', [bucket(300)]);
  enqueueSync('alice', 'pc', heartbeat, local);
  // recordUsageBatch can accept the transaction while reporting that this old
  // row predates retention. The successful response still acknowledges it.
  acknowledgeSync('alice', 'pc', peekSync('alice', 'pc'));
  expect(accountUsage('alice', 'pc', [bucket(300)])[0].trackedSeconds).toBe(300);
  expect(unacknowledgedUsage(local, peekSync('alice', 'pc').acknowledged)).toEqual([]);
});

it('stores mutations until individually acknowledged without losing concurrent additions', () => {
  enqueueMutation('alice', { id: 'one', path: 'focus:addWorkRecord', args: { title: 'A' } });
  enqueueMutation('alice', { id: 'two', path: 'focus:setBlockedWebsite', args: { domain: 'example.com' } });
  acknowledgeMutation('alice', 'one');
  expect(pendingMutations('alice').map((item) => item.id)).toEqual(['two']);
  expect(pendingMutations('bob')).toEqual([]);
});

it('continues past a failed head, acknowledges later website edits, and attempts each item once', async () => {
  enqueueMutation('alice', { id: 'head', path: 'focus:setBlockedWebsite', args: { domain: 'failed.example' } });
  enqueueMutation('alice', { id: 'later', path: 'focus:setBlockedWebsite', args: { domain: 'later.example' } });
  enqueueMutation('bob', { id: 'bob-only', path: 'focus:setBlockedWebsite', args: { domain: 'bob.example' } });
  const attempts: string[] = [];

  const results = await replayMutations('alice', async item => {
    attempts.push(item.id);
    if (item.id === 'head') throw new Error('temporary failure');
    return { applied: true };
  });

  expect(attempts).toEqual(['head', 'later']);
  expect([...results.keys()]).toEqual(['head', 'later']);
  expect(results.get('head')?.ok).toBe(false);
  expect(results.get('later')).toEqual({ ok: true, value: { applied: true } });
  expect(pendingMutations('alice').map(item => item.id)).toEqual(['head']);
  expect(pendingMutations('bob').map(item => item.id)).toEqual(['bob-only']);
});

it('attempts every failed mutation once and retains all failures for a later retry', async () => {
  enqueueMutation('alice', { id: 'first', path: 'focus:setBlockedWebsite', args: { domain: 'first.example' } });
  enqueueMutation('alice', { id: 'second', path: 'focus:setBlockedWebsite', args: { domain: 'second.example' } });
  const attempts: string[] = [];

  const results = await replayMutations('alice', async item => {
    attempts.push(item.id);
    throw new Error(`failed ${item.id}`);
  });

  expect(attempts).toEqual(['first', 'second']);
  expect([...results.values()].every(result => !result.ok)).toBe(true);
  expect(pendingMutations('alice').map(item => item.id)).toEqual(['first', 'second']);
});

it('includes mutations appended while an earlier replay is in flight', async () => {
  enqueueMutation('alice', { id: 'first', path: 'focus:savePrefs', args: { strictMode: false } });
  const attempts: string[] = [];

  const results = await replayMutations('alice', async item => {
    attempts.push(item.id);
    if (item.id === 'first') enqueueMutation('alice', { id: 'concurrent', path: 'focus:setBlockedWebsite', args: { domain: 'concurrent.example' } });
    return { applied: true };
  });

  expect(attempts).toEqual(['first', 'concurrent']);
  expect([...results.keys()]).toEqual(['first', 'concurrent']);
  expect(pendingMutations('alice')).toEqual([]);
});

it('retries a retained mutation on a later replay and acknowledges it after success', async () => {
  enqueueMutation('alice', { id: 'retry', path: 'focus:setBlockedWebsite', args: { domain: 'retry.example' } });
  const first = await replayMutations('alice', async () => { throw new Error('offline'); });
  expect(first.get('retry')?.ok).toBe(false);
  expect(pendingMutations('alice').map(item => item.id)).toEqual(['retry']);

  const retryCalls: string[] = [];
  const second = await replayMutations('alice', async item => {
    retryCalls.push(item.id);
    return { applied: true };
  });
  expect(retryCalls).toEqual(['retry']);
  expect(second.get('retry')).toEqual({ ok: true, value: { applied: true } });
  expect(pendingMutations('alice')).toEqual([]);
});
