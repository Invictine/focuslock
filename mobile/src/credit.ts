import { makeFunctionReference } from 'convex/server';
import type { CreditGateStatus, LeisureUsageDay } from '../modules/focuslock-screen-time';

export type CreditBucket = {
  date: string; targetKind: 'app'; targetKey: string; targetLabel: string;
  category: string; trackedSeconds: number; leisureSeconds: number; updatedAt: number;
};
export const recordLeisureUsage = makeFunctionReference<'mutation', {
  deviceId: string; buckets: CreditBucket[];
}, { written: number; expired?: number; oldestAcceptedDate?: string }>('usage:recordUsageBatch');
export const freshCreditState = makeFunctionReference<'query', Record<string, never>, {
  state: { creditBalanceSeconds: number } | null;
}>('focus:getState');

export function creditBuckets(rows: LeisureUsageDay[]): CreditBucket[] {
  if (rows.length > 500) throw new Error('Too many saved usage days. No new credit allowance was granted.');
  const seen = new Set<string>();
  return rows.map((row) => {
    const parsed = new Date(`${row.date}T00:00:00Z`);
    const key = `${row.counterId}:${row.date}`;
    if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(row.counterId)
      || !/^\d{4}-\d{2}-\d{2}$/.test(row.date) || !Number.isFinite(parsed.getTime())
      || parsed.toISOString().slice(0, 10) !== row.date || seen.has(key)
      || !Number.isSafeInteger(row.measuredSeconds) || row.measuredSeconds < 0
      || !Number.isFinite(row.updatedAt) || row.updatedAt < 0) {
      throw new Error('The saved device usage counter is invalid. It has been kept for recovery; no allowance was granted.');
    }
    seen.add(key);
    return {
      date: row.date, targetKind: 'app', targetKey: `ios-credit:${row.counterId.toLowerCase()}`,
      targetLabel: 'iPhone leisure selection', category: 'LEISURE',
      trackedSeconds: row.measuredSeconds, leisureSeconds: row.measuredSeconds, updatedAt: row.updatedAt,
    };
  });
}

type CreditDependencies = {
  ownerId: string;
  isCurrent: () => boolean;
  installationId: () => Promise<string>;
  native: {
    getCreditGate: () => Promise<CreditGateStatus>;
    getLeisureUsage: (owner: string) => Promise<LeisureUsageDay[]>;
    configureCreditAllowance: (owner: string, minutes: number) => Promise<CreditGateStatus>;
  };
  acknowledge: (deviceId: string, buckets: CreditBucket[]) => Promise<unknown>;
  balance: () => Promise<number | null>;
};

/** One account-bound flight: durable measurements, server acknowledgement,
 * fresh bank, then bounded native allowance. Failure never grants more time. */
export class CreditSync {
  private flight: Promise<void> | null = null;
  constructor(private readonly dependencies: CreditDependencies) {}
  sync(): Promise<void> {
    if (this.flight) return this.flight;
    this.flight = this.run().finally(() => { this.flight = null; });
    return this.flight;
  }
  private current() {
    if (!this.dependencies.isCurrent()) throw new Error('Credit sync stopped because the account or connection changed.');
  }
  private async run() {
    const d = this.dependencies;
    if (!d.ownerId || d.ownerId === 'local') throw new Error('Sign in before syncing leisure credit.');
    this.current();
    const rows = creditBuckets(await d.native.getLeisureUsage(d.ownerId));
    this.current();
    const deviceId = await d.installationId();
    this.current();
    if (rows.length) await d.acknowledge(deviceId, rows);
    this.current();
    const gate = await d.native.getCreditGate();
    this.current();
    // Disabled selections and another account's policy never receive a silent
    // grant. Enabling explicitly first installs an owner-bound zero allowance.
    if (!gate.enabled || gate.ownerId !== d.ownerId) return;
    const balance = await d.balance();
    this.current();
    if (balance === null || !Number.isFinite(balance) || balance < 0) throw new Error('A fresh account balance is unavailable. The existing device allowance has been kept.');
    // Recheck after the network request: an emergency suspend/disable or account
    // transition must not be undone by a response that arrived later.
    const latestGate = await d.native.getCreditGate();
    this.current();
    if (!latestGate.enabled || latestGate.ownerId !== d.ownerId) return;
    await d.native.configureCreditAllowance(d.ownerId, Math.min(60, Math.floor(balance / 60)));
  }
}
