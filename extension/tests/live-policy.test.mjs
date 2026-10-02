import test from 'node:test';
import assert from 'node:assert/strict';
import { createLivePolicyController } from '../src/live-policy.js';
const settle = () => new Promise((resolve) => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

function harness() {
  let ident = { userId: 'u1', session: 's1' }, client;
  const updates = [], states = [], policies = [], errors = [];
  const controller = createLivePolicyController({
    url: 'https://convex.test', query: 'syncPulse',
    readIdentity: async () => ident,
    fetchToken: async (identity, refresh) => `${identity.session}:${refresh}`,
    createClient: () => (client = {
      setAuth: (fn) => { client.auth = fn; },
      onUpdate: (_q, args, cb, err) => { updates.push({ args, cb, err }); return () => { client.unsubscribed = true; }; },
      subscribeToConnectionState: (cb) => { states.push(cb); return () => { client.stateUnsubscribed = true; }; },
      close: () => { client.closed = true; },
    }),
    onPolicy: async (pulse, ctx) => { if (await ctx.isCurrent()) policies.push([pulse, ctx.userId]); },
    onError: (e) => errors.push(e),
  });
  return { controller, updates, states, policies, errors, setIdentity: (v) => { ident = v; }, get client() { return client; } };
}

test('subscribes once, refreshes tokens, receives pushes, and tracks connection', async () => {
  const h = harness(); await h.controller.ensure({ day: '2026-10-01' }); await h.controller.ensure({ day: '2026-10-01' });
  assert.equal(h.updates.length, 1);
  assert.equal(await h.client.auth({ forceRefreshToken: true }), 's1:true');
  h.states[0]({ isWebSocketConnected: true }); h.updates[0].cb({ strict: true }); await settle();
  assert.deepEqual(h.policies, [[{ strict: true }, 'u1']]); assert.deepEqual(h.controller.status(), { active: true, connected: true, received: true });
});

test('day changes replace subscription and signout tears it down', async () => {
  const h = harness(); await h.controller.ensure({ day: 'a' }); const first = h.client;
  await h.controller.ensure({ day: 'b' }); assert.equal(first.closed, true); assert.equal(h.updates.length, 2);
  h.setIdentity(null); await h.controller.ensure({ day: 'b' }); assert.equal(h.controller.status().active, false); assert.equal(h.client.closed, true);
});

test('stale account pushes and queued callbacks cannot apply', async () => {
  const h = harness(); await h.controller.ensure({ day: 'a' }); const old = h.updates[0].cb;
  old({ n: 1 }); await settle(); h.setIdentity({ userId: 'u2', session: 's2' }); await h.controller.ensure({ day: 'a' });
  old({ n: 2 }); await Promise.resolve(); await Promise.resolve();
  assert.deepEqual(h.policies, [[{ n: 1 }, 'u1']]);
});

test('query errors preserve cached policy and report', async () => {
  const h = harness(); await h.controller.ensure({ day: 'a' }); h.updates[0].err(new Error('offline'));
  assert.equal(h.errors[0].message, 'offline'); assert.equal(h.controller.status().active, true);
});

test('a delayed identity read cannot resurrect after stop', async () => {
  const gate = deferred(); let clients = 0;
  const c = createLivePolicyController({ url: 'x', query: 'q', readIdentity: () => gate.promise,
    createClient: () => { clients += 1; return {}; }, onPolicy: () => {} });
  const pending = c.ensure({ day: 'a' }); await c.stop(); gate.resolve({ userId: 'u', session: 's' }); await pending;
  assert.equal(clients, 0);
});

test('identity changed before a push is detected and old callback is ignored', async () => {
  const h = harness(); await h.controller.ensure({ day: 'a' }); h.setIdentity({ userId: 'u2', session: 's2' });
  h.updates[0].cb({ stale: true }); await settle(); assert.deepEqual(h.policies, []);
});

test('token completing after an account switch returns null', async () => {
  const token = deferred(); let ident = { userId: 'u1', session: 's1' }; let client;
  const c = createLivePolicyController({ url: 'x', query: 'q', readIdentity: async () => ident,
    fetchToken: async () => token.promise, createClient: () => (client = { setAuth(fn) { client.auth = fn; }, onUpdate() { return () => {}; } }), onPolicy: () => {} });
  await c.ensure({ day: 'a' }); const pending = client.auth({ forceRefreshToken: true }); ident = { userId: 'u2', session: 's2' }; await c.ensure({ day: 'a' }); token.resolve('old-token'); assert.equal(await pending, null);
});

test('async close rejection is reported without an unhandled rejection', async () => {
  const h = harness(); await h.controller.ensure({ day: 'a' }); h.client.close = () => Promise.reject(new Error('close failed'));
  await h.controller.stop(); await settle(); assert.equal(h.errors.at(-1).message, 'close failed');
});

test('a queued policy callback sees false after account switch', async () => {
  let ident = { userId: 'u1', session: 's1' }; const gate = deferred(); let committed = false; let update;
  const c = createLivePolicyController({ url: 'x', query: 'q', readIdentity: async () => ident,
    createClient: () => ({ setAuth() {}, onUpdate(_q, _a, cb) { update = cb; return () => {}; } }),
    onPolicy: async (_p, ctx) => { await gate.promise; if (await ctx.isCurrent()) committed = true; } });
  await c.ensure({ day: 'a' }); update({ x: 1 }); ident = { userId: 'u2', session: 's2' }; await c.ensure({ day: 'a' }); gate.resolve(); await settle(); assert.equal(committed, false);
});

test('identity preparation failure permits a successful retry', async () => {
  let attempts = 0, clients = 0; const c = createLivePolicyController({ url: 'x', query: 'q',
    readIdentity: async () => ({ userId: 'u', session: 's' }), createClient: () => { clients += 1; return { setAuth() {}, onUpdate() { return () => {}; } }; },
    onIdentityChange: async () => { if (++attempts === 1) throw new Error('prepare'); }, onError: () => {}, onPolicy: () => {} });
  await c.ensure({ day: 'a' }); assert.equal(clients, 0); await c.ensure({ day: 'a' }); assert.equal(clients, 1);
});
