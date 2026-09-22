import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
async function readConfig(relative) {
  try {
    const text = await readFile(path.join(root, relative), 'utf8');
    return Object.fromEntries(text.split(/\r?\n/).map((line) => line.match(/^\s*([^#=\s]+)\s*=\s*(.*?)\s*$/))
      .filter(Boolean).map((match) => [match[1], match[2].replace(/^['"]|['"]$/g, '').replace(/\\:/g, ':')]));
  } catch (error) {
    if (error.code === 'ENOENT') return {};
    throw error;
  }
}
const [backend, desktop, extension, android] = await Promise.all(
  ['.env.local', 'desktop/.env', 'extension/.env', 'local.properties'].map(readConfig),
);
const clients = [
  { name: 'Android', key: android['clerk.publishableKey'], url: android['convex.url'] },
  { name: 'Desktop', key: desktop.VITE_CLERK_PUBLISHABLE_KEY, url: desktop.VITE_CONVEX_URL },
  { name: 'Extension', key: process.env.CLERK_PUBLISHABLE_KEY || extension.CLERK_PUBLISHABLE_KEY || desktop.VITE_CLERK_PUBLISHABLE_KEY,
    url: process.env.CONVEX_URL || extension.CONVEX_URL || desktop.VITE_CONVEX_URL },
];
let failed = false;
const issuers = new Set();
for (const client of clients) {
  const configured = /^pk_(test|live)_/.test(client.key ?? '') && /^https:\/\//.test(client.url ?? '');
  const sameBackend = client.url === backend.CONVEX_URL;
  if (configured) {
    const host = Buffer.from(client.key.replace(/^pk_(test|live)_/, ''), 'base64').toString('utf8').replace(/\$$/, '');
    issuers.add(`https://${host}`);
  }
  console.log(`${client.name}: ${configured ? 'configured' : 'MISSING CONFIG'}, ${sameBackend ? 'same deployment' : 'DEPLOYMENT MISMATCH'}`);
  failed ||= !configured || !sameBackend;
}
if (issuers.size !== 1) {
  console.error('Clerk issuer mismatch: all clients must use the same Clerk instance.');
  failed = true;
} else {
  console.log(`Required Convex CLERK_JWT_ISSUER_DOMAIN: ${[...issuers][0]}`);
}
console.log('Clerk must also have a JWT template named convex with aud=convex. This check does not sign in or test network access.');
process.exitCode = failed ? 1 : 0;
