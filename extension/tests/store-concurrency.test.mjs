import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

const source = await fs.readFile(new URL('../src/store.js', import.meta.url), 'utf8');
let data = {};
let queue = Promise.resolve();
let writes = 0;
let failNext = false;
const chrome = { storage: { local: {
  async get(key) { return { [key]: data[key] === undefined ? undefined : JSON.parse(JSON.stringify(data[key], (_key, item) =>
    item && typeof item === 'object' && !Array.isArray(item)
      ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item)) }; },
  async set(values) {
    if (failNext) { failNext = false; throw new Error('quota fixture'); }
    data = { ...data, ...structuredClone(values) }; writes++;
  },
} } };
function context() {
  const sandbox = { chrome, self: {}, navigator: { locks: {
    request(_key, task) { const next = queue.then(task, task); queue = next.catch(() => {}); return next; },
  } } };
  vm.runInNewContext(source, sandbox);
  return sandbox.self.FocusLockStore;
}
const worker = context();
const dashboard = context();
const workerState = await worker.load();
const dashboardState = await dashboard.load();
const initialWrites = writes;
await dashboard.load();
assert.equal(writes, initialWrites, 'Reading a valid state does not rewrite it');
workerState.stats['2026-09-27'] = { 'example.com': 42 };
dashboardState.lists[0].name = 'Updated from dashboard';
await Promise.all([worker.save(workerState), dashboard.save(dashboardState)]);
assert.equal(data['focuslock.v1'].stats['2026-09-27']['example.com'], 42);
assert.equal(data['focuslock.v1'].lists[0].name, 'Updated from dashboard');

const a = await worker.load();
const b = await dashboard.load();
a.lists[0].name = 'First edit'; b.lists[0].name = 'Stale edit';
await worker.save(a);
await assert.rejects(() => dashboard.save(b), /Settings changed/);
assert.equal(data['focuslock.v1'].lists[0].name, 'First edit');

// A refresh in the same worker must not change the baseline of an older
// asynchronous operation that is still holding its previous state object.
const delayed = await worker.load();
await dashboard.update(state => { state.lists[0].name = 'New boundary rules'; });
await worker.load();
delayed.stats['2026-09-27']['example.com'] = 50;
await worker.save(delayed);
assert.equal(data['focuslock.v1'].lists[0].name, 'New boundary rules');
assert.equal(data['focuslock.v1'].stats['2026-09-27']['example.com'], 50);

const retry = await worker.load();
retry.settings.quotes = false;
failNext = true;
await assert.rejects(() => worker.save(retry), /quota fixture/);
await worker.save(retry);
assert.equal(data['focuslock.v1'].settings.quotes, false, 'Failed writes can retry the same payload');

// Permalock is append-only at the storage layer: a save that forgets the key
// may add a permanent block but can never drop one.
const seeded = await worker.load();
seeded.permanentSites = ['example.com'];
await worker.save(seeded);
const forgetful = await worker.load();
delete forgetful.permanentSites;
await worker.save(forgetful);
assert.deepEqual(Array.from(data['focuslock.v1'].permanentSites), ['example.com'],
  'A partial save cannot erase a permanent block');
assert.deepEqual(Array.from(forgetful.permanentSites), ['example.com'],
  'The caller state is rehydrated with the durable permanent list');
// Mobile can enable Strict Mode while a local edit is waiting to be saved.
const staleBoundaryEdit = await dashboard.load();
staleBoundaryEdit.lists[0].enabled = false;
const strict = await worker.load();
strict.strictMode = true;
strict.strictEndsAt = Date.now() + 60_000;
await worker.save(strict);
await assert.rejects(() => dashboard.save(staleBoundaryEdit), /Strict Mode/);
assert.equal(data['focuslock.v1'].lists[0].enabled, true);
const setupWhitelist = await worker.load();
setupWhitelist.strictEndsAt = Date.now() - 1;
await worker.save(setupWhitelist);
const addWhitelist = await worker.load();
addWhitelist.lists.push({ id: 'whitelist-test', name: 'Allowed sites', mode: 'whitelist', enabled: false,
  alwaysOn: true, sites: ['school.example'], exceptions: [], lockedUntil: 0, dailyLimitMin: 0 });
await worker.save(addWhitelist);
const reactivateStrict = await worker.load();
reactivateStrict.strictMode = true;
reactivateStrict.strictEndsAt = Date.now() + 60_000;
await worker.save(reactivateStrict);
const whitelistEnable = await dashboard.load();
whitelistEnable.lists.find(list => list.id === 'whitelist-test').enabled = true;
await assert.rejects(() => dashboard.save(whitelistEnable), /Strict Mode/,
  'Strict Mode does not permit enabling a whitelist that could weaken another active boundary');
const whitelistAdd = await dashboard.load();
whitelistAdd.lists.find(list => list.id === 'whitelist-test').sites.push('new-allowed.example');
await assert.rejects(() => dashboard.save(whitelistAdd), /Strict Mode/,
  'Strict Mode does not permit extending a whitelist');
const unrelatedSetting = await dashboard.load();
unrelatedSetting.settings.idleTimeoutSec = 90;
await dashboard.save(unrelatedSetting);
assert.equal(data['focuslock.v1'].settings.idleTimeoutSec, 90,
  'Strict Mode permits unrelated settings while boundary rules remain locked');
const permanentEdit = await dashboard.load();
permanentEdit.permanentSites.push('locked.example');
await dashboard.save(permanentEdit);
assert.ok(data['focuslock.v1'].permanentSites.includes('locked.example'),
  'Strict Mode permits append-only permanent blocks');
const additiveBoundary = await dashboard.load();
additiveBoundary.lists[0].sites.push('new-boundary.example');
await dashboard.save(additiveBoundary);
assert.ok(data['focuslock.v1'].lists[0].sites.includes('new-boundary.example'),
  'Strict Mode permits adding a blocked site');
const weakenedBoundary = await dashboard.load();
weakenedBoundary.lists[0].sites = weakenedBoundary.lists[0].sites.filter(site => site !== 'new-boundary.example');
await assert.rejects(() => dashboard.save(weakenedBoundary), /Strict Mode/,
  'Strict Mode continues to prevent removing a boundary');
await worker.update(state => { state.stats['2026-09-27']['example.com'] = 60; });
assert.equal(data['focuslock.v1'].stats['2026-09-27']['example.com'], 60,
  'Usage persists while strict boundary writes are locked');
const expired = await worker.load();
expired.strictEndsAt = Date.now() - 1;
await worker.save(expired);
await dashboard.update(state => { state.lists[0].enabled = false; });
assert.equal(data['focuslock.v1'].lists[0].enabled, false, 'Boundary edits resume at strict expiry');
console.log('extension storage concurrency tests passed');
