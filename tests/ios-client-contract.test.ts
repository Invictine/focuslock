// @vitest-environment node
/// <reference types="vite/client" />
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { convexTest } from 'convex-test';
import { beforeAll, describe, expect, it } from 'vitest';
import ts from 'typescript';
import schema from '../convex/schema';

const modules = import.meta.glob('../convex/**/*.ts');
const minute = 60_000;
const require = createRequire(import.meta.url);
let mobileApi: Record<string, any>;
let mobileDomain: Record<string, any>;

async function loadMobileSource(relativePath: string): Promise<Record<string, any>> {
  const source = await readFile(new URL(relativePath, import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  });
  const loadedModule = { exports: {} as Record<string, any> };
  new Function('require', 'module', 'exports', outputText)(require, loadedModule, loadedModule.exports);
  return loadedModule.exports;
}

beforeAll(async () => {
  mobileApi = await loadMobileSource('../mobile/src/api.ts');
  mobileDomain = await loadMobileSource('../mobile/src/domain.ts');
});

function completedIosRecord(options: {
  id: string;
  title?: string;
  ownerId?: string;
  startedAt?: number;
  elapsedMinutes?: number;
  targetMinutes?: number;
  workRatio?: number;
}) {
  const startedAt = options.startedAt ?? Date.now();
  const session = mobileDomain.createSession({
    id: options.id,
    ownerId: options.ownerId ?? 'alice',
    title: options.title ?? 'Focused work',
    targetMinutes: options.targetMinutes ?? 30,
    workRatio: options.workRatio ?? 2,
    now: startedAt,
  });
  return mobileDomain.completeSession(
    { ...mobileDomain.emptyEnvelope(session.ownerId), session },
    startedAt + (options.elapsedMinutes ?? 10) * minute,
  ).pending[0];
}

function todayState(userId: string, date: string) {
  return {
    userId,
    creditBalanceSeconds: 600,
    totalWorkSecondsToday: 1200,
    totalScrollSecondsToday: 300,
    tasksCompletedToday: 4,
    lastResetDate: date,
    updatedAt: 100,
    externalEarnedSeconds: 0,
    externalSpentSeconds: 0,
    externalDate: date,
    externalWorkSecondsToday: 0,
    externalScrollSecondsToday: 0,
    externalTasksCompletedToday: 0,
  };
}

function localDateOffset(days: number): { date: string; timestamp: number } {
  const value = new Date();
  value.setDate(value.getDate() + days);
  value.setHours(12, 0, 0, 0);
  return { date: mobileDomain.localDate(value.getTime()), timestamp: value.getTime() };
}

describe('iOS API and Convex client contract', () => {
  it('retries a completed iOS session once, credits once, and leaves task totals unchanged', async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: 'alice' });
    const record = completedIosRecord({ id: 'ios-session-retry', elapsedMinutes: 17, workRatio: 3 });
    await t.run((ctx) => ctx.db.insert('focusState', todayState('alice', record.date)));

    await expect(alice.mutation(mobileApi.recordWork, record)).resolves.toEqual({ applied: true, duplicate: false });
    await expect(alice.mutation(mobileApi.recordWork, record)).resolves.toEqual({ applied: true, duplicate: true });

    const dashboard = await alice.query(mobileApi.accountDashboard, { fromDate: record.date, toDate: record.date });
    expect(dashboard.records.filter((item) => item.recordId === record.recordId)).toHaveLength(1);
    expect(dashboard.records[0]).toMatchObject({
      recordId: record.recordId,
      title: record.title,
      durationMinutes: 17,
      source: 'IOS_TIMER',
      earnedMinutesCredited: 5,
    });
    expect(dashboard.state).toMatchObject({
      creditBalanceSeconds: 900,
      totalWorkSecondsToday: 2220,
      tasksCompletedToday: 4,
      lastResetDate: record.date,
    });
  });

  it('does not expose one account’s iOS work records or credit to another account', async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: 'alice' });
    const bob = t.withIdentity({ subject: 'bob' });
    const record = completedIosRecord({ id: 'alice-only-session', ownerId: 'alice', elapsedMinutes: 8 });
    await alice.mutation(mobileApi.recordWork, record);

    const aliceSnapshot = await alice.query(mobileApi.accountDashboard, { fromDate: record.date, toDate: record.date });
    const bobSnapshot = await bob.query(mobileApi.accountDashboard, { fromDate: record.date, toDate: record.date });
    expect(aliceSnapshot.records.map((item) => item.recordId)).toContain(record.recordId);
    expect(aliceSnapshot.state?.creditBalanceSeconds).toBe(record.earnedMinutesCredited * 60);
    expect(bobSnapshot.records).toEqual([]);
    expect(bobSnapshot.state).toBeNull();
  });

  it('maps the real dashboard snapshot into the mobile dashboard shape', async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: 'alice' });
    const record = completedIosRecord({ id: 'dashboard-session', title: 'Read chemistry', elapsedMinutes: 12 });
    await t.run(async (ctx) => {
      await ctx.db.insert('focusState', todayState('alice', record.date));
      await ctx.db.insert('blockedApps', {
        userId: 'alice', packageName: 'com.example.social', appName: 'Social',
        isBlocked: true, category: 'Social', updatedAt: 10,
      });
      await ctx.db.insert('blockedWebsites', {
        userId: 'alice', domain: 'example.com', displayName: 'Example',
        isBlocked: false, category: 'Web', updatedAt: 11,
      });
      await ctx.db.insert('userPrefs', {
        userId: 'alice', strictMode: true, strictEndsAt: Date.now() + 60_000,
        weeklyReport: true, workRatio: 2, updatedAt: 12,
      });
    });
    await alice.mutation(mobileApi.recordWork, record);

    const snapshot = await alice.query(mobileApi.accountDashboard, { fromDate: record.date, toDate: record.date });
    const dashboard = mobileApi.dashboardView(snapshot);
    expect(dashboard.state).toEqual(snapshot.state);
    expect(dashboard.blockedApps).toEqual(snapshot.apps);
    expect(dashboard.blockedWebsites).toEqual(snapshot.sites);
    expect(dashboard.workRecords).toEqual(snapshot.records);
    expect(dashboard.prefs).toEqual(snapshot.prefs);
    expect(dashboard).toMatchObject({
      blockedApps: [{ packageName: 'com.example.social', appName: 'Social', isBlocked: true }],
      blockedWebsites: [{ domain: 'example.com', displayName: 'Example', isBlocked: false }],
      workRecords: [{ recordId: 'ios_timer_dashboard-session', title: 'Read chemistry', source: 'IOS_TIMER' }],
      prefs: { strictMode: true, workRatio: 2 },
      state: { creditBalanceSeconds: 960, totalWorkSecondsToday: 1920 },
    });
  });

  it('keeps today’s counters when an older-day record is retried later', async () => {
    const t = convexTest(schema, modules);
    const alice = t.withIdentity({ subject: 'alice' });
    const today = localDateOffset(0).date;
    const yesterday = localDateOffset(-1);
    const priorTodayState = todayState('alice', today);
    const record = completedIosRecord({
      id: 'yesterday-session', startedAt: yesterday.timestamp,
      elapsedMinutes: 9, workRatio: 3,
    });
    expect(record.date).toBe(yesterday.date);
    await t.run((ctx) => ctx.db.insert('focusState', priorTodayState));

    await alice.mutation(mobileApi.recordWork, record);
    await alice.mutation(mobileApi.recordWork, record);
    const dashboard = await alice.query(mobileApi.accountDashboard, { fromDate: yesterday.date, toDate: today });
    expect(dashboard.state).toMatchObject({
      creditBalanceSeconds: priorTodayState.creditBalanceSeconds + record.earnedMinutesCredited * 60,
      totalWorkSecondsToday: priorTodayState.totalWorkSecondsToday,
      totalScrollSecondsToday: priorTodayState.totalScrollSecondsToday,
      tasksCompletedToday: priorTodayState.tasksCompletedToday,
      lastResetDate: today,
    });
    expect(dashboard.records.filter((item) => item.recordId === record.recordId)).toHaveLength(1);
  });
});
