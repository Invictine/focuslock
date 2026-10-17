import { makeFunctionReference } from 'convex/server';
import type { Dashboard } from './types';
import type { WorkRecord } from './domain';

// Explicit public contracts keep this app installable without generated backend
// files or dependencies on either of the other React clients.
export type AccountSnapshot = {
  state: Dashboard['state'];
  apps: Dashboard['blockedApps'];
  sites: Dashboard['blockedWebsites'];
  records: Dashboard['workRecords'];
  prefs: Dashboard['prefs'];
};

export const accountDashboard = makeFunctionReference<
  'query', { fromDate: string; toDate: string }, AccountSnapshot
>('focus:getDashboard');
export const accountNuke = makeFunctionReference<'query', Record<string, never>, { isActive: boolean } | null>('nuke:getNuke');
export const recordWork = makeFunctionReference<
  'mutation', WorkRecord, { applied: boolean; duplicate: boolean }
>('focus:recordWork');
export const deviceHeartbeat = makeFunctionReference<'mutation', {
  deviceId: string; name: string; platform: 'ios'; appVersion: string;
  trackingStatus: 'paused' | 'permission_required'; statusDetail: string; lastSeen: number;
}, string>('devices:heartbeat');

export function dashboardView(snapshot: AccountSnapshot): Dashboard {
  return {
    state: snapshot.state,
    blockedApps: snapshot.apps,
    blockedWebsites: snapshot.sites,
    workRecords: snapshot.records,
    prefs: snapshot.prefs,
  };
}
