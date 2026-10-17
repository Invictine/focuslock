import assert from 'node:assert/strict';
import test from 'node:test';
import {
  HOME_LOCATION_TASK,
  HOME_REGION_ID,
  distanceMeters,
  isAtHome,
  parseSavedDeviceHome,
  validateHomePlace,
} from '../src/homeLocation';

const home = { label: 'Home', latitude: 12.9716, longitude: 77.5946, radiusMeters: 150 };

test('home region task has a stable app-owned identifier', () => {
  assert.equal(HOME_LOCATION_TASK, 'focuslock-home-boundary-v1');
  assert.equal(HOME_REGION_ID, 'focuslock-home-boundary');
});

test('home place validation trims labels and bounds coordinates and radius', () => {
  assert.deepEqual(validateHomePlace({ ...home, label: '  Home  ' }), home);
  for (const input of [
    { ...home, label: '   ' },
    { ...home, label: 'x'.repeat(121) },
    { ...home, latitude: 90.01 },
    { ...home, latitude: Number.NaN },
    { ...home, longitude: -180.01 },
    { ...home, longitude: Number.POSITIVE_INFINITY },
    { ...home, radiusMeters: 24.99 },
    { ...home, radiusMeters: 5000.1 },
  ]) assert.throws(() => validateHomePlace(input));
});

test('distance uses great-circle distance and home detection honors radius', () => {
  const nearby = { latitude: home.latitude + 0.0005, longitude: home.longitude };
  const far = { latitude: home.latitude + 0.01, longitude: home.longitude };
  assert.ok(distanceMeters(home, nearby) > 0 && distanceMeters(home, nearby) < home.radiusMeters);
  assert.equal(isAtHome(home, nearby), true);
  assert.equal(isAtHome(home, far), false);
  assert.equal(isAtHome(home, null), null);
  assert.equal(isAtHome(home, { latitude: 91, longitude: 0 }), null);
});

test('saved local home is account-scoped and corrupt data fails closed', () => {
  const raw = JSON.stringify({ ownerId: 'account-a', ...home });
  assert.deepEqual(parseSavedDeviceHome(raw, 'account-a'), { ownerId: 'account-a', ...home });
  assert.equal(parseSavedDeviceHome(null, 'account-a'), null);
  assert.throws(() => parseSavedDeviceHome(raw, 'account-b'), /another account/);
  assert.throws(() => parseSavedDeviceHome('{bad', 'account-a'), /corrupt/);
  assert.throws(() => parseSavedDeviceHome(JSON.stringify({ ownerId: 'account-a', ...home, radiusMeters: 0 }), 'account-a'));
});
