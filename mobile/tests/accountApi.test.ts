import assert from 'node:assert/strict';
import test from 'node:test';
import { buildPreferencesPatch, buildStrictConfiguration, normalizeWebsiteDomain, type StrictConfig } from '../src/accountApi';

test('website entry accepts URL-shaped input but sends a normalized hostname', () => {
  assert.equal(normalizeWebsiteDomain('  HTTPS://WWW.Example.COM/path?q=1  '), 'example.com');
  assert.equal(normalizeWebsiteDomain('sub.example.co.in.'), 'sub.example.co.in');
});

test('website entry rejects values that are not public-style domain names', () => {
  for (const value of ['', 'localhost', 'example', 'example.com:8080', '-bad.example', 'bad..example.com']) {
    assert.throws(() => normalizeWebsiteDomain(value), /website domain/);
  }
});

test('partial preference writes preserve independent clocks and omit untouched fields', () => {
  assert.deepEqual(buildPreferencesPatch({ updatedAt: 1234, workRatio: 3 }), {
    updatedAt: 1234,
    workRatio: 3,
    workRatioUpdatedAt: 1234,
  });
  assert.deepEqual(buildPreferencesPatch({ updatedAt: 5678, weeklyReport: false }), {
    updatedAt: 5678,
    weeklyReport: false,
  });
  assert.deepEqual(buildPreferencesPatch({ updatedAt: 9, taskBonusMinutes: 8 }), {
    updatedAt: 9,
    taskBonusMinutes: 8,
    taskBonusMinutesUpdatedAt: 9,
  });
});

test('strict configuration preserves existing Android protections and defaults new accounts to false', () => {
  const existing: StrictConfig = {
    method: 'timer', methods: ['timer'], cooldownMinutes: 5,
    preventUninstall: true, blockDeviceSettings: false, blockRecents: true,
    blockSplitScreen: false, blockReinstalledApps: true, hasPin: false, revision: 7,
  };
  assert.deepEqual(buildStrictConfiguration({ methods: ['charger', 'cooldown'], primary: 'charger', cooldownMinutes: 4, previous: existing }), {
    method: 'charger', methods: ['charger', 'cooldown'], cooldownMinutes: 4,
    preventUninstall: true, blockDeviceSettings: false, blockRecents: true,
    blockSplitScreen: false, blockReinstalledApps: true,
  });
  assert.deepEqual(buildStrictConfiguration({ methods: ['timer'], primary: 'timer', cooldownMinutes: 5, previous: null }), {
    method: 'timer', methods: ['timer'], cooldownMinutes: 5,
    preventUninstall: false, blockDeviceSettings: false, blockRecents: false,
    blockSplitScreen: false, blockReinstalledApps: false,
  });
});

test('PIN route needs a 4 to 12 digit PIN unless one is already configured', () => {
  assert.throws(() => buildStrictConfiguration({ methods: ['pin'], primary: 'pin', cooldownMinutes: 5, previous: null }), /Set a PIN/);
  assert.throws(() => buildStrictConfiguration({ methods: ['pin'], primary: 'pin', cooldownMinutes: 5, previous: null, pin: '123' }), /4 to 12 digits/);
  assert.equal(buildStrictConfiguration({ methods: ['pin'], primary: 'pin', cooldownMinutes: 5, previous: null, pin: '938274' }).pin, '938274');
});
