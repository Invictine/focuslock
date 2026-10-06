import { readFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const defaultRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

async function readConfig(root, relative) {
  try {
    const text = await readFile(path.join(root, relative), 'utf8');
    return Object.fromEntries(text.split(/\r?\n/)
      .map((line) => line.match(/^\s*([^#=\s]+)\s*=\s*(.*?)\s*$/))
      .filter(Boolean)
      .map((match) => [match[1], match[2].replace(/^['"]|['"]$/g, '').replace(/\\:/g, ':')]));
  } catch (error) {
    if (error.code === 'ENOENT') return {};
    throw error;
  }
}

async function readViteProductionEnv(root) {
  // Vite loads these in order; each later file overrides earlier files.
  const files = ['.env', '.env.local', '.env.production', '.env.production.local'];
  const configs = await Promise.all(files.map((name) => readConfig(root, `desktop/${name}`)));
  return Object.assign({}, ...configs);
}

function effectiveClientConfigs({ desktop, extensionDesktop, extension, android, env }) {
  return [
    { name: 'Android', key: android['clerk.publishableKey'] ?? env.CLERK_PUBLISHABLE_KEY,
      url: android['convex.url'] ?? env.CONVEX_URL },
    { name: 'Desktop', key: env.VITE_CLERK_PUBLISHABLE_KEY ?? desktop.VITE_CLERK_PUBLISHABLE_KEY,
      url: env.VITE_CONVEX_URL ?? desktop.VITE_CONVEX_URL },
    { name: 'Extension', key: env.CLERK_PUBLISHABLE_KEY || extension.CLERK_PUBLISHABLE_KEY || extensionDesktop.VITE_CLERK_PUBLISHABLE_KEY,
      url: env.CONVEX_URL || extension.CONVEX_URL || extensionDesktop.VITE_CONVEX_URL },
  ];
}

export async function checkSyncConfig({ root = defaultRoot, env = process.env, log = console.log, errorLog = console.error } = {}) {
  const [backend, desktop, extensionDesktop, extension, android] = await Promise.all([
    readConfig(root, '.env.local'), readViteProductionEnv(root),
    readConfig(root, 'desktop/.env'), readConfig(root, 'extension/.env'), readConfig(root, 'local.properties'),
  ]);
  // The extension build uses process env > extension/.env > desktop/.env.
  // It does not load desktop/.env.local or Vite's production files.
  const clients = effectiveClientConfigs({ desktop, extensionDesktop, extension, android, env });
  let failed = false;
  const issuers = new Set();
  for (const client of clients) {
    const configured = /^pk_(test|live)_/.test(client.key ?? '') && /^https:\/\//.test(client.url ?? '');
    const sameBackend = client.url === backend.CONVEX_URL;
    if (configured) {
      const host = Buffer.from(client.key.replace(/^pk_(test|live)_/, ''), 'base64').toString('utf8').replace(/\$$/, '');
      issuers.add(`https://${host}`);
    }
    log(`${client.name}: ${configured ? 'configured' : 'MISSING CONFIG'}, ${sameBackend ? 'same deployment' : 'DEPLOYMENT MISMATCH'}`);
    failed ||= !configured || !sameBackend;
  }
  if (issuers.size !== 1) {
    errorLog('Clerk issuer mismatch: all clients must use the same Clerk instance.');
    failed = true;
  } else {
    log(`Required Convex CLERK_JWT_ISSUER_DOMAIN: ${[...issuers][0]}`);
  }
  log('Clerk must also have a JWT template named convex with aud=convex. This check does not sign in or test network access.');
  return failed ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  process.exitCode = await checkSyncConfig();
}
