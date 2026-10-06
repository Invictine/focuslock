import { build } from 'esbuild';
import { readFile, writeFile, mkdir, cp, rm } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));

async function envFile(file) {
  try {
    const text = await readFile(file, 'utf8');
    return Object.fromEntries(text.split(/\r?\n/)
      .map((line) => line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/))
      .filter(Boolean)
      .map((match) => [match[1], match[2].replace(/^['"]|['"]$/g, '')]));
  } catch {
    return {};
  }
}

const local = await envFile(path.join(root, '.env'));
const desktop = await envFile(path.join(root, '..', 'desktop', '.env'));
const clerkKey = process.env.CLERK_PUBLISHABLE_KEY || local.CLERK_PUBLISHABLE_KEY || desktop.VITE_CLERK_PUBLISHABLE_KEY;
const convexUrl = process.env.CONVEX_URL || local.CONVEX_URL || desktop.VITE_CONVEX_URL;

function frontendApiFromKey(key) {
  try {
    const encoded = key.replace(/^pk_(?:test|live)_/, '');
    return Buffer.from(encoded, 'base64url').toString('utf8').replace(/\$$/, '');
  } catch {
    return '';
  }
}

const clerkFrontendApi = frontendApiFromKey(clerkKey || '');
if (!clerkKey || !convexUrl) {
  throw new Error('Missing CLERK_PUBLISHABLE_KEY or CONVEX_URL. Add extension/.env or configure desktop/.env first.');
}

// The Frontend API serves JSON, not the Account Portal's sign-in page.
let clerkSignInUrl = process.env.CLERK_SIGN_IN_URL || local.CLERK_SIGN_IN_URL;
if (!clerkSignInUrl) {
  const response = await fetch(`https://${clerkFrontendApi}/v1/environment`, {
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) throw new Error('Could not discover Clerk sign-in URL. Set CLERK_SIGN_IN_URL explicitly.');
  const display = (await response.json()).display_config;
  clerkSignInUrl = display?.sign_in_url;
  if (clerkSignInUrl && display?.user_profile_url) {
    const portal = new URL(clerkSignInUrl);
    // Do not inherit the web/desktop application's localhost redirect.
    portal.searchParams.set('redirect_url', display.user_profile_url);
    clerkSignInUrl = portal.href;
  }
}
if (!clerkSignInUrl || new URL(clerkSignInUrl).protocol !== 'https:' || new URL(clerkSignInUrl).username || new URL(clerkSignInUrl).password) {
  throw new Error('CLERK_SIGN_IN_URL must be an HTTPS sign-in page.');
}
// Development browser cookies live on the web app; production client cookies
// live on the Clerk Frontend API domain (per Clerk Sync Host configuration).
const clerkSyncHost = process.env.CLERK_SYNC_HOST || local.CLERK_SYNC_HOST
  || (clerkKey.startsWith('pk_test_') ? new URL(clerkSignInUrl).origin : `https://${clerkFrontendApi}`);
for (const [name, value] of Object.entries({ CONVEX_URL: convexUrl, CLERK_SYNC_HOST: clerkSyncHost })) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password) {
    throw new Error(`${name} must be an HTTPS URL without credentials.`);
  }
}

const common = {
  bundle: true,
  minify: true,
  sourcemap: false,
  metafile: true,
  target: ['chrome109'],
  define: {
    'process.env.CLERK_PUBLISHABLE_KEY': JSON.stringify(clerkKey),
    'process.env.CONVEX_URL': JSON.stringify(convexUrl),
    'process.env.CLERK_SYNC_HOST': JSON.stringify(clerkSyncHost),
    'process.env.CLERK_SIGN_IN_URL': JSON.stringify(clerkSignInUrl),
  },
};

const bundles = await Promise.all([
  build({ ...common, entryPoints: [path.join(root, 'popup', 'popup.js')], outfile: path.join(root, 'dist', 'popup.js'), format: 'iife' }),
  build({ ...common, entryPoints: [path.join(root, 'options', 'options-auth.js')], outfile: path.join(root, 'dist', 'options-auth.js'), format: 'iife' }),
  build({ ...common, entryPoints: [path.join(root, 'src', 'cloud-sync.js')], outfile: path.join(root, 'dist', 'cloud-sync.js'), format: 'iife' }),
]);

// Copy only runtime assets. Never distribute .env, private keys, tests, or dependencies.
const unpacked = path.resolve(root, '..', 'build', 'extension-unpacked');
await mkdir(unpacked, { recursive: true });
// Dashboard assets may remain from older builds in this generated directory.
// Recreate only the UI asset folders so those files cannot leak into the package.
for (const name of ['popup', 'options']) {
  const target = path.resolve(unpacked, name);
  if (path.dirname(target) !== unpacked) throw new Error(`Refusing to clean UI output outside ${unpacked}`);
  await rm(target, { recursive: true, force: true });
}
await writeFile(path.join(root, '..', 'build', 'extension-bundle-inputs.json'),
  JSON.stringify([...new Set(bundles.flatMap(result => Object.keys(result.metafile.inputs)))], null, 2));
for (const entry of ['manifest.json', 'background', 'content', 'blocked', 'shared', 'icons', 'dist']) {
  await cp(path.join(root, entry), path.join(unpacked, entry), { recursive: true });
}
await mkdir(path.join(unpacked, 'popup'), { recursive: true });
for (const entry of ['popup.html', 'popup.css']) {
  await cp(path.join(root, 'popup', entry), path.join(unpacked, 'popup', entry));
}
await mkdir(path.join(unpacked, 'options'), { recursive: true });
await cp(path.join(root, 'options', 'options.html'), path.join(unpacked, 'options', 'options.html'));
await mkdir(path.join(unpacked, 'src'), { recursive: true });
for (const entry of ['matcher.js', 'features.js', 'store.js', 'policy.js', 'desktop-bridge.js']) {
  await cp(path.join(root, 'src', entry), path.join(unpacked, 'src', entry));
}
console.log('Built scripts in extension/dist and loadable extension in build/extension-unpacked.');
