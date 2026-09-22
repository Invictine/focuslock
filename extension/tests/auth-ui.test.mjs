import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const [html, auth, manifest, build] = await Promise.all([
  fs.readFile(new URL('../options/options.html', import.meta.url), 'utf8'),
  fs.readFile(new URL('../options/options-auth.js', import.meta.url), 'utf8'),
  fs.readFile(new URL('../manifest.json', import.meta.url), 'utf8'),
  fs.readFile(new URL('../build.mjs', import.meta.url), 'utf8'),
]);

for (const id of ['accountSignIn', 'accountEmailSignIn', 'accountAuth', 'accountError']) {
  assert.equal((html.match(new RegExp(`id=["']${id}["']`, 'g')) || []).length, 1, `${id} must be unique`);
}
assert.match(html, /id="accountPitch"[^>]*hidden/);
assert.match(auth, /await createClerkClient\(\{ publishableKey, syncHost, background: true \}\)/);
assert.match(auth, /chrome\.tabs\.create\(\{ url: browserSignInUrl, active: true \}\)/);
assert.match(auth, /accountEmailSignIn/);
assert.ok(JSON.parse(manifest).permissions.includes('cookies'), 'Sync Host requires cookie access');
assert.match(build, /process\.env\.CLERK_SYNC_HOST/);
assert.match(build, /process\.env\.CLERK_SIGN_IN_URL/);

console.log('auth UI and browser handoff tests passed');
