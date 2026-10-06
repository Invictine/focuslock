import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { checkSyncConfig } from '../scripts/check-sync-config.mjs';

const roots: string[] = [];
const baseUrl = 'https://stable.convex.cloud';
const overrideUrl = 'https://wrong.convex.cloud';
const publishableKey = `pk_test_${Buffer.from('auth.example.com$').toString('base64')}`;

async function fixture() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'focuslock-config-'));
  roots.push(root);
  await mkdir(path.join(root, 'desktop'));
  await mkdir(path.join(root, 'extension'));
  await writeFile(path.join(root, '.env.local'), `CONVEX_URL=${baseUrl}\n`);
  await writeFile(path.join(root, 'desktop', '.env'), `VITE_CLERK_PUBLISHABLE_KEY=${publishableKey}\nVITE_CONVEX_URL=${baseUrl}\n`);
  await writeFile(path.join(root, 'extension', '.env'), `CLERK_PUBLISHABLE_KEY=${publishableKey}\nCONVEX_URL=${baseUrl}\n`);
  await writeFile(path.join(root, 'local.properties'), `clerk.publishableKey=${publishableKey}\nconvex.url=${baseUrl}\n`);
  return root;
}

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe('sync config checker', () => {
  it('catches desktop .env.local while keeping extension fallback precedence faithful to its build', async () => {
    const root = await fixture();
    await writeFile(path.join(root, 'desktop', '.env.local'), `VITE_CONVEX_URL=${overrideUrl}\n`);
    const output: string[] = [];
    const status = await checkSyncConfig({ root, env: {}, log: (line) => output.push(line), errorLog: (line) => output.push(line) });
    expect(status).toBe(1);
    expect(output).toContain('Desktop: configured, DEPLOYMENT MISMATCH');
    // Extension build reads desktop/.env directly, so .env.local cannot override it.
    expect(output).toContain('Extension: configured, same deployment');
    expect(output.join('\n')).not.toContain(publishableKey);
  });

  it('gives process environment values precedence over production env files', async () => {
    const root = await fixture();
    await writeFile(path.join(root, 'desktop', '.env.production'), `VITE_CONVEX_URL=${overrideUrl}\n`);
    const output: string[] = [];
    const status = await checkSyncConfig({ root, env: { VITE_CONVEX_URL: baseUrl, CONVEX_URL: baseUrl }, log: (line) => output.push(line), errorLog: (line) => output.push(line) });
    expect(status).toBe(0);
    expect(output).toContain('Desktop: configured, same deployment');
    expect(output.join('\n')).not.toContain(publishableKey);
  });
});
