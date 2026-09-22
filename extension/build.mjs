import { build } from 'esbuild';
import { readFile } from 'node:fs/promises';
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
if (!clerkSignInUrl || new URL(clerkSignInUrl).protocol !== 'https:') {
  throw new Error('CLERK_SIGN_IN_URL must be an HTTPS sign-in page.');
}
// Development browser cookies live on the web app; production client cookies
// live on the Clerk Frontend API domain (per Clerk Sync Host configuration).
const clerkSyncHost = process.env.CLERK_SYNC_HOST || local.CLERK_SYNC_HOST
  || (clerkKey.startsWith('pk_test_') ? new URL(clerkSignInUrl).origin : `https://${clerkFrontendApi}`);

const common = {
  bundle: true,
  minify: true,
  sourcemap: false,
  target: ['chrome109'],
  define: {
    'process.env.CLERK_PUBLISHABLE_KEY': JSON.stringify(clerkKey),
    'process.env.CONVEX_URL': JSON.stringify(convexUrl),
    'process.env.CLERK_SYNC_HOST': JSON.stringify(clerkSyncHost),
    'process.env.CLERK_SIGN_IN_URL': JSON.stringify(clerkSignInUrl),
  },
};

await Promise.all([
  build({ ...common, entryPoints: [path.join(root, 'popup', 'popup.js')], outfile: path.join(root, 'dist', 'popup.js'), format: 'iife' }),
  build({ ...common, entryPoints: [path.join(root, 'options', 'options-auth.js')], outfile: path.join(root, 'dist', 'options-auth.js'), format: 'iife' }),
  build({ ...common, entryPoints: [path.join(root, 'src', 'cloud-sync.js')], outfile: path.join(root, 'dist', 'cloud-sync.js'), format: 'iife' }),
]);

console.log('Built FocusLock extension scripts in extension/dist.');
