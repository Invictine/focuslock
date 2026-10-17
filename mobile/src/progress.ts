import { localDate } from './domain';
import type { WorkRecord } from './domain';
import type { Dashboard } from './types';

export function focusProgress(dashboard: Dashboard | null, pending: WorkRecord[], today: string) {
  const records = new Map(dashboard?.workRecords.map((record) => [record.recordId, record]) ?? []);
  const syncedIds = new Set(records.keys());
  const pendingToday = pending.filter((record) => record.date === today && !syncedIds.has(record.recordId));
  for (const record of pending) if (!syncedIds.has(record.recordId)) records.set(record.recordId, record);
  const byTitle: Record<string, number> = Object.create(null);
  let recorded = 0;
  for (const record of records.values()) {
    if (!Number.isFinite(record.timestamp) || record.timestamp < 0 || localDate(record.timestamp) !== today || !Number.isFinite(record.durationMinutes) || record.durationMinutes <= 0) continue;
    recorded += record.durationMinutes;
    const title = record.title.trim();
    byTitle[title] = (byTitle[title] ?? 0) + record.durationMinutes;
  }
  const bank = dashboard?.state?.lastResetDate === today ? dashboard.state.totalWorkSecondsToday / 60 : 0;
  return { totalMinutes: Math.max(recorded, bank + pendingToday.reduce((sum, record) => sum + record.durationMinutes, 0)), byTitle };
}
