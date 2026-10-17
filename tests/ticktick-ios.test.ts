/// <reference types="vite/client" />
import { convexTest } from 'convex-test';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { makeFunctionReference } from 'convex/server';
import schema from '../convex/schema';
import { createOAuthState, dateInZone, parseFocusRows, parseTicktickCallback, verifyOAuthState } from '../convex/ticktickIosHelpers';

const modules = import.meta.glob('../convex/**/*.ts');
const status = makeFunctionReference<'action'>('ticktickIos:status');
const authorize = makeFunctionReference<'action'>('ticktickIos:authorize');
const connect = makeFunctionReference<'action'>('ticktickIos:connect');
const tasks = makeFunctionReference<'action'>('ticktickIos:tasks');
const syncFocus = makeFunctionReference<'action'>('ticktickIos:syncFocus');
const saveLink = makeFunctionReference<'mutation'>('ticktick:saveConnection');
const getDashboard = makeFunctionReference<'query'>('focus:getDashboard');

afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
describe('iOS TickTick server integration', () => {
  it('binds authorization state to account, signature, connection revision and expiry', async () => {
    const now = Date.now();
    const value = { sub: 'alice', revision: 2, expires: now + 600_000, nonce: 'unique_nonce_123456789' };
    const signed = await createOAuthState('server-secret', value);
    expect(await verifyOAuthState('server-secret', signed, 'alice', now)).toEqual(value);
    await expect(verifyOAuthState('server-secret', signed, 'bob', now)).rejects.toThrow('another account');
    await expect(verifyOAuthState('other-secret', signed, 'alice', now)).rejects.toThrow();
    await expect(verifyOAuthState('server-secret', signed, 'alice', now + 600_001)).rejects.toThrow();
    expect(parseTicktickCallback(`http://127.0.0.1:8080/?code=code&state=${signed}`)).toEqual({ code: 'code', state: signed });
    expect(() => parseTicktickCallback(`https://attacker.example/?code=code&state=${signed}`)).toThrow();
  });
  it('parses genuine completed focus duration in seconds and milliseconds and retains Android dedupe IDs', () => {
    const end = Date.parse('2026-10-17T10:00:00Z');
    const row = { id: 'session', type: 0, startTime: '2026-10-17T09:35:00Z', endTime: '2026-10-17T10:00:00Z', duration: 1500 };
    expect(parseFocusRows([row], 0, end - 86_400_000, end)[0]).toMatchObject({ recordId: 'ticktick_focus_0_session', durationMinutes: 25 });
    expect(parseFocusRows([{ ...row, duration: 1_500_000 }], 0, end - 86_400_000, end)[0].durationMinutes).toBe(25);
    expect(parseFocusRows([{ ...row, endTime: null }], 0, 0, end)).toEqual([]);
    expect(parseFocusRows([{ ...row, duration: 999_000_000 }], 0, 0, end)).toEqual([]);
    expect(() => parseFocusRows([{ ...row, type: 1 }], 0, 0, end)).toThrow();
    expect(dateInZone(Date.parse('2026-10-16T20:00:00Z'), 'Asia/Kolkata')).toBe('2026-10-17');
  });
  it('requires authentication and never returns provider tokens from status or tasks', async () => {
    const test = convexTest(schema, modules);
    await expect(test.action(status, {})).rejects.toThrow('Not authenticated');
    const alice = test.withIdentity({ subject: 'alice' });
    await alice.mutation(saveLink, { expectedRevision: 0, connection: { accessToken: 'private-provider-token' } });
    expect(await alice.action(status, {})).toEqual({ connected: true, revision: 1, serverConfigured: false });
    expect(await test.withIdentity({ subject: 'bob' }).action(status, {})).toMatchObject({ connected: false });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => Response.json(url.endsWith('/project') ? [{ id: 'p', name: 'Study' }] : { tasks: [{ id: 't', title: 'Chemistry', status: 0 }] })));
    expect(await alice.action(tasks, {})).toEqual([{ id: 't', projectId: 'p', title: 'Chemistry', projectName: 'Study' }]);
  });
  it('rejects a stale callback before contacting the token provider', async () => {
    vi.stubEnv('TICKTICK_CLIENT_ID', 'client');
    vi.stubEnv('TICKTICK_CLIENT_SECRET', 'secret');
    const alice = convexTest(schema, modules).withIdentity({ subject: 'alice' });
    const { url } = await alice.action(authorize, {}) as { url: string };
    const state = new URL(url).searchParams.get('state');
    await alice.mutation(saveLink, { expectedRevision: 0, connection: null });
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    await expect(alice.action(connect, { callback: `http://127.0.0.1:8080/?code=secret-code&state=${state}` })).rejects.toThrow('connection changed');
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('imports official focus once without completing tasks or awarding repeated credit', async () => {
    const now = Date.now();
    const alice = convexTest(schema, modules).withIdentity({ subject: 'alice' });
    await alice.mutation(saveLink, { expectedRevision: 0, connection: { accessToken: 'private-provider-token' } });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const type = Number(new URL(url).searchParams.get('type'));
      return Response.json(type === 0 ? [{ id: 'real-focus', type: 0, startTime: new Date(now - 25 * 60_000).toISOString(), endTime: new Date(now - 1000).toISOString(), duration: 1499 }] : []);
    }));
    expect(await alice.action(syncFocus, { timeZone: 'Asia/Kolkata' })).toEqual({ found: 1, imported: 1, earnedMinutes: 6 });
    expect(await alice.action(syncFocus, { timeZone: 'Asia/Kolkata' })).toEqual({ found: 1, imported: 0, earnedMinutes: 0 });
    const dash = await alice.query(getDashboard, {}) as any;
    expect(dash.state.creditBalanceSeconds).toBe(360);
    expect(dash.state.tasksCompletedToday).toBe(0);
    expect(dash.records).toHaveLength(1);
  });
});
