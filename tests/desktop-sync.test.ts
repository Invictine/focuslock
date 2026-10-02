import { beforeEach, expect, it } from "vitest";
import { accountUsage, acknowledgeSync, enqueueSync, peekSync, unacknowledgedUsage,
  enqueueMutation, pendingMutations, acknowledgeMutation } from "../desktop/src/offlineQueue";

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
