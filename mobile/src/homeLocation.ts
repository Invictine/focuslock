/** Pure location helpers shared by the iOS home-boundary task and its tests. */
export const HOME_LOCATION_TASK = 'focuslock-home-boundary-v1';
export const HOME_REGION_ID = 'focuslock-home-boundary';

export type HomeCoordinates = {
  latitude: number;
  longitude: number;
};

export type HomePlace = HomeCoordinates & {
  label: string;
  radiusMeters: number;
};

export type SavedDeviceHome = HomePlace & {
  ownerId: string;
};

const EARTH_RADIUS_METERS = 6_371_000;

export function validateHomePlace(input: HomePlace): HomePlace {
  const label = typeof input.label === 'string' ? input.label.trim() : '';
  if (!label || label.length > 120) throw new TypeError('Enter a place label up to 120 characters.');
  if (!Number.isFinite(input.latitude) || input.latitude < -90 || input.latitude > 90) {
    throw new TypeError('Latitude must be between -90 and 90.');
  }
  if (!Number.isFinite(input.longitude) || input.longitude < -180 || input.longitude > 180) {
    throw new TypeError('Longitude must be between -180 and 180.');
  }
  if (!Number.isFinite(input.radiusMeters) || input.radiusMeters < 25 || input.radiusMeters > 5000) {
    throw new TypeError('Radius must be between 25 and 5000 metres.');
  }
  return { label, latitude: input.latitude, longitude: input.longitude, radiusMeters: input.radiusMeters };
}

export function parseSavedDeviceHome(raw: string | null, ownerId: string): SavedDeviceHome | null {
  if (raw === null) return null;
  let value: unknown;
  try { value = JSON.parse(raw); }
  catch { throw new TypeError('Saved on-device home location is corrupt.'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError('Saved on-device home location is invalid.');
  }
  const record = value as Record<string, unknown>;
  if (record.ownerId !== ownerId) throw new TypeError('Saved home location belongs to another account.');
  if (typeof record.label !== 'string' || typeof record.latitude !== 'number' ||
      typeof record.longitude !== 'number' || typeof record.radiusMeters !== 'number') {
    throw new TypeError('Saved on-device home location is invalid.');
  }
  return { ownerId, ...validateHomePlace({
    label: record.label,
    latitude: record.latitude,
    longitude: record.longitude,
    radiusMeters: record.radiusMeters,
  }) };
}

/** Great-circle distance; inputs are validated so malformed GPS cannot unlock rules. */
export function distanceMeters(from: HomeCoordinates, to: HomeCoordinates): number {
  validateCoordinates(from);
  validateCoordinates(to);
  const radians = (degrees: number) => degrees * Math.PI / 180;
  const lat1 = radians(from.latitude);
  const lat2 = radians(to.latitude);
  const dLat = lat2 - lat1;
  const dLon = radians(to.longitude - from.longitude);
  const haversine = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, haversine)));
}

/** null means location is unknown; native policy must preserve shields in that state. */
export function isAtHome(home: HomePlace, current: HomeCoordinates | null): boolean | null {
  if (!current || !Number.isFinite(current.latitude) || !Number.isFinite(current.longitude) ||
      current.latitude < -90 || current.latitude > 90 || current.longitude < -180 || current.longitude > 180) {
    return null;
  }
  const place = validateHomePlace(home);
  return distanceMeters(place, current) <= place.radiusMeters;
}

function validateCoordinates(value: HomeCoordinates): void {
  if (!Number.isFinite(value.latitude) || value.latitude < -90 || value.latitude > 90 ||
      !Number.isFinite(value.longitude) || value.longitude < -180 || value.longitude > 180) {
    throw new TypeError('Coordinates are invalid.');
  }
}
