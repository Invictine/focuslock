import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';

const KEY = 'focuslock.v1';
const data = { [KEY]: {
  lists: [null, { id: 7, name: null, mode: 'unexpected', sites: 'reddit.com', exceptions: null, lockedUntil: 'NaN', dailyLimitMin: -9 }],
  schedules: [null, { id: 'ok', listId: 'list_1', type: 'timer', startTs: 'bad', endTs: 500, days: 'all' }],
  snoozes: { 'reddit.com': 'later', 'youtube.com': 1234 }, stats: { 'not-a-day': {}, '2026-09-27': { 'reddit.com': 90, 'bad.com': 'bad' } },
  cloudSites: [null, { domain: 'REDDIT.COM', isBlocked: true }, { domain: {}, isBlocked: true }],
  permanentSites: ['WWW.Example.com', 'example.com', 'bad domain', {}, '*.Sub.Example.com.', 'x'.repeat(300)],
  strictMode: 'true', strictEndsAt: 'bad', blockedLog: [null, { url: 'https://reddit.com/' }],
} };
let persisted;
const context = {
  self: {},
  chrome: { storage: { local: {
    async get() { return structuredClone(data); },
    async set(value) { persisted = structuredClone(value); },
  } } },
};
vm.createContext(context);
vm.runInContext(await fs.readFile(new URL('../src/store.js', import.meta.url), 'utf8'), context);
const normalized = await context.self.FocusLockStore.load();
assert.equal(normalized.lists.length, 1);
assert.equal(normalized.lists[0].mode, 'blacklist');
assert.deepEqual(Array.from(normalized.lists[0].sites), []);
assert.equal(normalized.lists[0].lockedUntil, 0);
assert.equal(normalized.schedules.length, 1);
assert.equal(normalized.schedules[0].startTs, 0);
assert.deepEqual(Array.from(Object.keys(normalized.snoozes)), ['youtube.com']);
assert.deepEqual(Array.from(Object.keys(normalized.stats)), ['2026-09-27']);
assert.equal(normalized.cloudSites[0].domain, 'reddit.com');
assert.deepEqual(Array.from(normalized.permanentSites), ['example.com', '*.sub.example.com'],
  'Permanent domains are lowercased, stripped of www., deduplicated, and junk is dropped');
assert.deepEqual(Array.from(context.self.FocusLockStore.defaultState().permanentSites), [],
  'A fresh install starts with no permanent blocks');
assert.equal(normalized.strictMode, true);
assert.equal(normalized.strictEndsAt, 0); // malformed expiry keeps an active commitment indefinite
assert.deepEqual(Array.from(persisted[KEY].cloudSites, site => site.domain), ['reddit.com']);
assert.deepEqual(Array.from(persisted[KEY].permanentSites), ['example.com', '*.sub.example.com'],
  'Normalized permanent blocks are persisted');
console.log('extension storage normalization tests passed');
