import { action } from './_generated/server';
import { api } from './_generated/api';
import { v } from 'convex/values';
import { createOAuthState, dateInZone, parseFocusRows, parseTicktickCallback, ticktickRedirect, verifyOAuthState } from './ticktickIosHelpers';

const provider = 'https://api.ticktick.com/open/v1';
type Connection = { accessToken: string; refreshToken?: string; expiresAt?: number; userName?: string };
type Link = { revision: number; connection: Connection | null };
function credentials() {
  const id = process.env.TICKTICK_CLIENT_ID;
  const secret = process.env.TICKTICK_CLIENT_SECRET;
  if (!id || !secret) throw new Error('TickTick server credentials have not been configured.');
  return { id, secret };
}
async function subject(ctx: any) {
  const identity = await ctx.auth.getUserIdentity();
  if (!identity) throw new Error('Not authenticated');
  return identity.subject as string;
}
async function exchange(form: URLSearchParams): Promise<Connection> {
  const { id, secret } = credentials();
  const response = await fetch('https://ticktick.com/oauth/token', {
    method: 'POST', headers: { Authorization: `Basic ${btoa(`${id}:${secret}`)}`, 'Content-Type': 'application/x-www-form-urlencoded' }, body: form.toString(),
  });
  if (!response.ok) throw new Error(`TickTick authorization failed (${response.status}). Reconnect and try again.`);
  const token = await response.json() as any;
  if (typeof token.access_token !== 'string' || !token.access_token.trim()) throw new Error('TickTick returned no access token.');
  return { accessToken: token.access_token, ...(typeof token.refresh_token === 'string' && token.refresh_token ? { refreshToken: token.refresh_token } : {}),
    ...(Number.isFinite(Number(token.expires_in)) && Number(token.expires_in) > 0 ? { expiresAt: Date.now() + Number(token.expires_in) * 1000 } : {}) };
}
async function validLink(ctx: any): Promise<Link & { connection: Connection }> {
  await subject(ctx);
  let link: Link = await ctx.runQuery(api.ticktick.getConnection, {});
  if (!link.connection) throw new Error('Connect TickTick first.');
  if (link.connection.expiresAt && link.connection.expiresAt <= Date.now() + 60_000) {
    if (!link.connection.refreshToken) throw new Error('TickTick sign-in expired. Reconnect your account.');
    const refreshed = await exchange(new URLSearchParams({ grant_type: 'refresh_token', refresh_token: link.connection.refreshToken }));
    const next = { ...link.connection, ...refreshed };
    const saved = await ctx.runMutation(api.ticktick.saveConnection, { expectedRevision: link.revision, connection: next });
    if (!saved.applied) throw new Error('The TickTick connection changed. Refresh and try again.');
    link = { revision: saved.revision, connection: next };
  }
  return link as Link & { connection: Connection };
}
async function assertCurrent(ctx: any, link: Link) {
  const current: Link = await ctx.runQuery(api.ticktick.getConnection, {});
  if (current.revision !== link.revision || !current.connection) throw new Error('The TickTick connection changed during this request. Refresh and try again.');
}
async function request(link: Link & { connection: Connection }, path: string, method = 'GET') {
  const response = await fetch(`${provider}${path}`, { method, headers: { Authorization: `Bearer ${link.connection.accessToken}` } });
  if (!response.ok) throw new Error(`TickTick request failed (${response.status}). Refresh or reconnect your account.`);
  return response.status === 204 ? null : response.json();
}

export const status = action({ args: {}, handler: async (ctx): Promise<{ connected: boolean; revision: number; serverConfigured: boolean; userName?: string }> => {
  await subject(ctx);
  const link: Link = await ctx.runQuery(api.ticktick.getConnection, {});
  return { connected: Boolean(link.connection), revision: link.revision, serverConfigured: Boolean(process.env.TICKTICK_CLIENT_ID && process.env.TICKTICK_CLIENT_SECRET), ...(link.connection?.userName ? { userName: link.connection.userName } : {}) };
} });

export const authorize = action({ args: {}, handler: async (ctx): Promise<{ url: string }> => {
  const sub = await subject(ctx);
  const { id, secret } = credentials();
  const link: Link = await ctx.runQuery(api.ticktick.getConnection, {});
  const state = await createOAuthState(secret, { sub, revision: link.revision, expires: Date.now() + 600_000, nonce: crypto.randomUUID() });
  return { url: `https://ticktick.com/oauth/authorize?${new URLSearchParams({ client_id: id, response_type: 'code', redirect_uri: ticktickRedirect, scope: 'tasks:read tasks:write', state })}` };
} });

export const connect = action({ args: { callback: v.string() }, handler: async (ctx, args): Promise<{ connected: true }> => {
  const sub = await subject(ctx);
  const { secret } = credentials();
  const { code, state } = parseTicktickCallback(args.callback);
  const verified = await verifyOAuthState(secret, state, sub, Date.now());
  const current: Link = await ctx.runQuery(api.ticktick.getConnection, {});
  if (current.revision !== verified.revision) throw new Error('The TickTick connection changed. Start again.');
  const connection = await exchange(new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: ticktickRedirect, scope: 'tasks:read tasks:write' }));
  const saved = await ctx.runMutation(api.ticktick.saveConnection, { expectedRevision: verified.revision, connection });
  if (!saved.applied) throw new Error('The TickTick connection changed. Start again.');
  return { connected: true };
} });

export const tasks = action({ args: {}, handler: async (ctx): Promise<{ id: string; projectId: string; title: string; projectName: string }[]> => {
  const link = await validLink(ctx);
  const projects = await request(link, '/project') as any;
  if (!Array.isArray(projects)) throw new Error('TickTick returned an invalid project list.');
  if (projects.length > 100) throw new Error('This TickTick account has too many projects for one refresh.');
  const rows: { id: string; projectId: string; title: string; projectName: string }[] = [];
  for (let index = 0; index < projects.length; index += 5) {
    const chunk = await Promise.all(projects.slice(index, index + 5).map(async (project: any) => {
      if (typeof project.id !== 'string') throw new Error('TickTick returned an invalid project.');
      const data = await request(link, `/project/${encodeURIComponent(project.id)}/data`) as any;
      if (!Array.isArray(data?.tasks)) throw new Error('TickTick returned an invalid task list.');
      return data.tasks.filter((task: any) => task.status !== 2 && typeof task.id === 'string' && typeof task.title === 'string').map((task: any) => ({ id: task.id, projectId: project.id, title: task.title, projectName: String(project.name ?? '') }));
    }));
    rows.push(...chunk.flat());
  }
  await assertCurrent(ctx, link);
  return rows.slice(0, 2000);
} });

export const completeTask = action({ args: { taskId: v.string(), projectId: v.string() }, handler: async (ctx, args): Promise<{ completed: true }> => {
  if (![args.taskId, args.projectId].every((value) => /^[a-zA-Z0-9_-]{1,160}$/.test(value))) throw new Error('Invalid TickTick task.');
  const link = await validLink(ctx);
  await assertCurrent(ctx, link);
  const response = await fetch(`${provider}/project/${encodeURIComponent(args.projectId)}/task/${encodeURIComponent(args.taskId)}/complete`, { method: 'POST', headers: { Authorization: `Bearer ${link.connection.accessToken}` } });
  if (!response.ok) throw new Error(`TickTick could not complete this task (${response.status}).`);
  // Completing a task is not a measured focus session and earns no focus time.
  return { completed: true };
} });

export const syncFocus = action({ args: { timeZone: v.string() }, handler: async (ctx, args): Promise<{ found: number; imported: number; earnedMinutes: number }> => {
  const now = Date.now();
  dateInZone(now, args.timeZone); // Validate IANA zone before making any request.
  const since = now - 7 * 86_400_000;
  const link = await validLink(ctx);
  const snapshot = await ctx.runQuery(api.focus.getDashboard, {});
  const ratio = Math.max(1, Math.min(10, snapshot.prefs?.workRatio ?? 4));
  const rows = (await Promise.all([0, 1].map(async (type) => {
    const query = new URLSearchParams({ from: new Date(since - 86_400_000).toISOString(), to: new Date(now).toISOString(), type: String(type) });
    return parseFocusRows(await request(link, `/focus?${query}`), type, since, now);
  }))).flat();
  await assertCurrent(ctx, link);
  let imported = 0, earnedMinutes = 0;
  for (const row of rows) {
    await assertCurrent(ctx, link);
    const earned = Math.floor(row.durationMinutes / ratio);
    const result = await ctx.runMutation(api.focus.recordWork, { ...row, source: 'TICKTICK_FOCUS_API', earnedMinutesCredited: earned, date: dateInZone(row.timestamp, args.timeZone), tasksCompleted: 0 });
    if (!result.duplicate) { imported += 1; earnedMinutes += earned; }
  }
  return { found: rows.length, imported, earnedMinutes };
} });
