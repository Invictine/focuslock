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

if (!clerkKey || !convexUrl) {
  throw new Error('Missing CLERK_PUBLISHABLE_KEY or CONVEX_URL. Add extension/.env or configure desktop/.env first.');
}

const common = {
  bundle: true,
  minify: true,
  sourcemap: false,
  target: ['chrome109'],
  define: {
    'process.env.CLERK_PUBLISHABLE_KEY': JSON.stringify(clerkKey),
    'process.env.CONVEX_URL': JSON.stringify(convexUrl),
  },
};

await Promise.all([
  build({ ...common, entryPoints: [path.join(root, 'popup', 'popup.js')], outfile: path.join(root, 'dist', 'popup.js'), format: 'iife' }),
  build({ ...common, entryPoints: [path.join(root, 'options', 'options-auth.js')], outfile: path.join(root, 'dist', 'options-auth.js'), format: 'iife' }),
  build({ ...common, entryPoints: [path.join(root, 'src', 'cloud-sync.js')], outfile: path.join(root, 'dist', 'cloud-sync.js'), format: 'iife' }),
]);

console.log('Built FocusLock extension scripts in extension/dist.');
