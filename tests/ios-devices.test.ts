/// <reference types="vite/client" />
import { convexTest } from 'convex-test';
import { expect, it } from 'vitest';
import schema from '../convex/schema';
import { api } from '../convex/_generated/api';

const modules = import.meta.glob('../convex/**/*.ts');
it('keeps iPhone presence account-scoped without inventing measured usage', async () => {
  const test = convexTest(schema, modules);
  const alice = test.withIdentity({ subject: 'alice' });
  const bob = test.withIdentity({ subject: 'bob' });
  await alice.mutation(api.devices.heartbeat, {
    deviceId: 'ios-installation', name: '', platform: 'ios', appVersion: '0.7.0',
    trackingStatus: 'paused', statusDetail: 'Usage stays private on this iPhone.', lastSeen: Date.now(),
  });
  expect(await alice.query(api.devices.listDevices, {})).toEqual([expect.objectContaining({ platform: 'ios', name: 'iPhone', trackingStatus: 'paused' })]);
  expect(await bob.query(api.devices.listDevices, {})).toEqual([]);
  expect(await test.run((ctx) => ctx.db.query('deviceUsage').collect())).toEqual([]);
});
