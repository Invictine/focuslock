import test from 'node:test';
import assert from 'node:assert/strict';
import { CreditSync, creditBuckets } from '../src/credit';
import type { CreditGateStatus, LeisureUsageDay } from '../modules/focuslock-screen-time';

const row: LeisureUsageDay = { counterId: 'c36ddc7b-59b7-4972-ae1f-434c3514a8bd', date: '2026-10-17', measuredSeconds: 120, updatedAt: 1000 };
function fixture() {
  let current = true;
  let gate: CreditGateStatus = { enabled: true, ownerId: 'alice', selectionCount: 1, allowanceMinutes: 0, exhausted: true };
  const calls: string[] = [];
  let failAck = false;
  let onBalance = () => {};
  let snapshotBalance: number | null = 600;
  const sync = new CreditSync({
    ownerId: 'alice', isCurrent: () => current,
    installationId: async () => 'ios-stable',
    native: {
      getLeisureUsage: async (owner) => { assert.equal(owner, 'alice'); calls.push('measure'); return [row]; },
      getCreditGate: async () => ({ ...gate }),
      configureCreditAllowance: async (owner, minutes) => { calls.push(`grant:${owner}:${minutes}`); return gate; },
    },
    acknowledge: async (deviceId, buckets) => {
      calls.push('ack'); assert.equal(deviceId, 'ios-stable');
      assert.equal(buckets[0]?.leisureSeconds, row.measuredSeconds);
      if (failAck) throw new Error('offline');
    },
    balance: async () => { calls.push('balance'); onBalance(); return snapshotBalance; },
  });
  return { sync, calls, setCurrent: (value: boolean) => { current = value; }, setGate: (value: CreditGateStatus) => { gate = value; }, failAck: () => { failAck = true; }, onBalance: (fn: () => void) => { onBalance = fn; }, setBalance: (value: number | null) => { snapshotBalance = value; } };
}

test('only measured counters enter the stable native ledger namespace', () => {
  assert.deepEqual(creditBuckets([row]), [{
    date: row.date, targetKind: 'app', targetKey: `ios-credit:${row.counterId}`,
    targetLabel: 'iPhone leisure selection', category: 'LEISURE', trackedSeconds: 120, leisureSeconds: 120, updatedAt: 1000,
  }]);
  for (const invalid of [{ measuredSeconds: -1 }, { measuredSeconds: NaN }, { date: '2026-02-31' }, { counterId: 'reset' }]) {
    assert.throws(() => creditBuckets([{ ...row, ...invalid }]));
  }
  assert.throws(() => creditBuckets([row, row]));
});
test('overlapping syncs share one flight and acknowledge measurements before granting a fresh bounded balance', async () => {
  const f = fixture();
  f.setBalance(99_999);
  const first = f.sync.sync();
  assert.equal(f.sync.sync(), first);
  await first;
  assert.deepEqual(f.calls, ['measure', 'ack', 'balance', 'grant:alice:60']);
});
test('upload failure keeps the prior native allowance and durable usage for retry', async () => {
  const f = fixture(); f.failAck();
  await assert.rejects(f.sync.sync(), /offline/);
  assert.deepEqual(f.calls, ['measure', 'ack']);
});
test('account change during balance refresh prevents a late allowance grant', async () => {
  const f = fixture(); f.onBalance(() => f.setCurrent(false));
  await assert.rejects(f.sync.sync(), /account or connection changed/);
  assert.deepEqual(f.calls, ['measure', 'ack', 'balance']);
});
test('disable during balance refresh prevents a late allowance grant', async () => {
  const f = fixture();
  f.onBalance(() => f.setGate({ enabled: false, ownerId: 'alice', selectionCount: 1, allowanceMinutes: 0, exhausted: true }));
  await f.sync.sync();
  assert.deepEqual(f.calls, ['measure', 'ack', 'balance']);
});
test('another account policy is never granted new time and unknown balances fail closed', async () => {
  const f = fixture();
  f.setGate({ enabled: true, ownerId: 'bob', selectionCount: 1, allowanceMinutes: 10, exhausted: false });
  await f.sync.sync();
  assert.deepEqual(f.calls, ['measure', 'ack']);
  const unknown = fixture(); unknown.setBalance(null);
  await assert.rejects(unknown.sync.sync(), /balance is unavailable/);
  assert.deepEqual(unknown.calls, ['measure', 'ack', 'balance']);
});
