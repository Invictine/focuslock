const encoder = new TextEncoder();
const REDIRECT = 'http://127.0.0.1:8080/';
export const ticktickRedirect = REDIRECT;
export type OAuthState = { sub: string; revision: number; expires: number; nonce: string };

function encode(bytes: Uint8Array) {
  return btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join('')).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function decode(value: string) {
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), (character) => character.charCodeAt(0));
}
async function stateKey(secret: string) {
  return crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}
export async function createOAuthState(secret: string, state: OAuthState) {
  const payload = encode(encoder.encode(JSON.stringify(state)));
  const signature = await crypto.subtle.sign('HMAC', await stateKey(secret), encoder.encode(payload));
  return `${payload}.${encode(new Uint8Array(signature))}`;
}
export async function verifyOAuthState(secret: string, state: string, subject: string, now: number): Promise<OAuthState> {
  try {
    const parts = state.split('.');
    if (parts.length !== 2 || state.length > 2048) throw new Error();
    if (!await crypto.subtle.verify('HMAC', await stateKey(secret), decode(parts[1]), encoder.encode(parts[0]))) throw new Error();
    const value = JSON.parse(new TextDecoder().decode(decode(parts[0]))) as OAuthState;
    if (value.sub !== subject || !Number.isSafeInteger(value.revision) || value.revision < 0 ||
      !Number.isFinite(value.expires) || value.expires <= now || value.expires > now + 600_000 ||
      typeof value.nonce !== 'string' || value.nonce.length < 16) throw new Error();
    return value;
  } catch { throw new Error('This TickTick connection request expired or belongs to another account. Start again.'); }
}
export function parseTicktickCallback(callback: string) {
  let url: URL;
  try { url = new URL(callback.trim()); } catch { throw new Error('Paste the complete TickTick redirect address.'); }
  if (`${url.origin}${url.pathname}` !== REDIRECT || url.searchParams.has('error')) throw new Error('TickTick did not authorize this connection. Start again.');
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  if (!code || !state || code.length > 2048) throw new Error('The redirect needs both its code and state.');
  return { code, state };
}
export function dateInZone(timestamp: number, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(timestamp));
  const value = (type: string) => parts.find((part) => part.type === type)?.value;
  return `${value('year')}-${value('month')}-${value('day')}`;
}
export function parseFocusRows(rows: unknown, type: number, since: number, now: number) {
  if (!Array.isArray(rows)) throw new Error('TickTick returned an invalid focus response.');
  return rows.flatMap((row: any) => {
    if (!row || typeof row !== 'object') throw new Error('TickTick returned an invalid focus entry.');
    if (!row.endTime) return [];
    if (typeof row.id !== 'string' || !row.id.trim() || Number(row.type) !== type) throw new Error('TickTick returned an invalid focus identity.');
    const start = Date.parse(row.startTime);
    const end = Date.parse(row.endTime);
    const duration = Number(row.duration);
    if (![start, end, duration].every(Number.isFinite)) throw new Error('TickTick returned an invalid focus duration.');
    if (end <= start || end < since || end > now || duration <= 0) return [];
    const elapsed = Math.floor((end - start) / 1000) + 1;
    const seconds = duration <= elapsed ? duration : Math.floor(duration / 1000);
    const minutes = Math.floor(seconds / 60);
    if (seconds > elapsed || minutes < 1 || minutes > 480) return [];
    return [{ recordId: `ticktick_focus_${type}_${row.id}`, title: 'TickTick focus session', durationMinutes: minutes, timestamp: end }];
  });
}
