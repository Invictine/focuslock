// Behavioral regression for website boundary creation in the real desktop App.tsx.
// Convex and Tauri are deterministic fixtures; the rendered UI and its handlers are real.
import { chromium } from '../extension/node_modules/playwright-core/index.mjs';
import { build } from '../extension/node_modules/esbuild/lib/main.js';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'build/ui-verification');
await mkdir(out, { recursive: true });
const dashboard = {
  state: { creditBalanceSeconds: 7380 },
  apps: [{ packageName: 'com.code.editor', appName: 'Code Editor', isBlocked: false, category: 'Windows' }],
  sites: [{ domain: 'reddit.com', displayName: 'Reddit', isBlocked: true, category: 'Web' }],
  records: [], prefs: { strictMode: true, strictEndsAt: Date.now() + 3600000 },
};
const authStub = `export function useFocusAuth(){return {user:{name:'Boundary Test',email:'test@example.test'},loading:false,error:null,signInInBrowser:async()=>{},signOut:async()=>{}}}`;
const durableSyncStub = `export const accountClient=()=>({mutation:async()=>({}),query:async()=>({})}); export async function flushMutations(){return new Map()}; export function useDurableMutation(ref){return async(args)=>{window.__calls.push({kind:'mutation',ref:String(ref),args}); if(String(ref).includes('setBlockedWebsite')){if(window.__behavior==='throw'){throw new Error('fixture mutation failure')} if(window.__behavior==='not-applied')return {applied:false}; if(window.__behavior==='missing-applied')return {}; const sites=window.__fixture.sites.filter(s=>s.domain!==args.domain); window.__fixture={...window.__fixture,sites:[...sites,{...args}]}; window.__fixtureVersion++; window.dispatchEvent(new Event('fixture-change')); return {applied:true};} return {applied:true};}}; export function useMutationReplay(){}`;
const clerkStub = `export function SignIn(){return null}`;
const convexStub = `import {useSyncExternalStore} from 'react'; const config=()=>window.__fixture; const subscribe=(fn)=>{window.addEventListener('fixture-change',fn);return()=>window.removeEventListener('fixture-change',fn)}; export const Authenticated=({children})=>children; export const Unauthenticated=()=>null; export const AuthLoading=()=>null; export function useQuery(ref){useSyncExternalStore(subscribe,()=>window.__fixtureVersion,()=>window.__fixtureVersion); const key=String(ref); if(key.includes('getDashboard')||key.includes('getConfiguration'))return config(); if(key.includes('getState'))return {state:window.__fixture.state}; if(key.includes('getHistory'))return {records:[],sessions:[]}; if(key.includes('getUsageSummary'))return {totalTrackedSeconds:0,devices:[],targets:[]}; if(key.includes('listDevices'))return []; if(key.includes('listGroups'))return []; if(key.includes('getNuke'))return {isActive:false}; return undefined;} export function useMutation(){return async()=>({applied:true})} export function useAction(){return async()=>({reply:'Fixture'})}`;
const apiStub = `export const api={focus:{getDashboard:'focus.getDashboard',getConfiguration:'focus.getConfiguration',getState:'focus.getState',getHistory:'focus.getHistory',getAccount:'focus.getAccount',savePrefs:'focus.savePrefs',recordWork:'focus.recordWork',saveBlockedApps:'focus.saveBlockedApps',saveBlockedWebsites:'focus.saveBlockedWebsites',setBlockedWebsite:'focus.setBlockedWebsite'},devices:{heartbeat:'devices.heartbeat',listDevices:'devices.listDevices'},usage:{recordUsageBatch:'usage.recordUsageBatch',getUsageSummary:'usage.getUsageSummary',listKnownTargets:'usage.listKnownTargets'},groups:{listGroups:'groups.listGroups',saveGroups:'groups.saveGroups'},strictApproval:{getGuardian:'strictApproval.getGuardian',getApprovalState:'strictApproval.getApprovalState',configureGuardian:'strictApproval.configureGuardian',requestApprovalEmail:'strictApproval.requestApprovalEmail'},nuke:{getNuke:'nuke.getNuke',activate:'nuke.activate',completeMeditation:'nuke.completeMeditation',checkin:'nuke.checkin'}};`;
const tauriStub = `export async function invoke(command,args){window.__calls.push({kind:'invoke',command,args}); if(command==='get_tracking_snapshot')return {device:{id:'boundary-test',name:'Test PC',platform:'windows',createdAtMs:Date.now()},current:{capturedAtMs:Date.now(),appId:'com.code.editor',appName:'Code Editor',windowTitle:'Test',deviceId:'boundary-test',deviceName:'Test PC',idle:false,blocked:false},usage:[],running:true,config:{sampleIntervalMs:5000,idleThresholdSeconds:120,captureBrowserDomains:true}}; return undefined}`;

const entry = path.join(out, 'desktop-boundaries-entry.tsx');
await writeFile(entry, `import React from 'react'; import {createRoot} from 'react-dom/client'; import App from ${JSON.stringify(path.join(root, 'desktop/src/App.tsx'))}; createRoot(document.getElementById('root')).render(<App/>);`);
const result = await build({
  absWorkingDir: path.join(root, 'desktop'), nodePaths: [path.join(root, 'desktop', 'node_modules')], entryPoints: [entry], bundle: true, write: false, format: 'iife', platform: 'browser', target: 'es2020', jsx: 'automatic',
  plugins: [{ name: 'desktop-boundary-fixtures', setup(b) {
    const normalized = args => String(args.importer).replace(/\\/g, '/');
    const fromApp = args => normalized(args).endsWith('/desktop/src/App.tsx');
    const fromDesktop = args => normalized(args).includes('/desktop/src/');
    b.onResolve({ filter: /^react$/ }, () => ({ path: path.join(root, 'desktop/node_modules/react/index.js') }));
    b.onResolve({ filter: /^\.\/auth$/ }, args => fromApp(args) ? ({ path: 'auth', namespace: 'fixture' }) : undefined);
    b.onResolve({ filter: /^\.\/durableSync$/ }, args => fromDesktop(args) ? ({ path: 'durable', namespace: 'fixture' }) : undefined);
    b.onResolve({ filter: /convex[\\/]react$/ }, () => ({ path: 'convex', namespace: 'fixture' }));
    b.onResolve({ filter: /clerk-react$/ }, () => ({ path: 'clerk', namespace: 'fixture' }));
    b.onResolve({ filter: /core$/ }, () => ({ path: 'tauri', namespace: 'fixture' }));
    b.onResolve({ filter: /_generated/ }, () => ({ path: 'api', namespace: 'fixture' }));
    b.onResolve({ filter: /\.css$/ }, args => ({ path: path.resolve(args.resolveDir, args.path), namespace: 'fixture-css' }));
    b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: ({ auth: authStub, durable: durableSyncStub, convex: convexStub, clerk: clerkStub, tauri: tauriStub, api: apiStub })[args.path], loader: 'js' }));
    b.onLoad({ filter: /.*/, namespace: 'fixture-css' }, async args => {
      const tokens = await readFile(path.join(root, 'desktop/src/theme-tokens.css'), 'utf8');
      const css = tokens + '\n' + (await readFile(args.path, 'utf8')).replace(/@import\s+["'][^"']+["'];/g, '');
      return { contents: `const style=document.createElement('style');style.textContent=${JSON.stringify(css)};document.head.appendChild(style);`, loader: 'js' };
    });
  }}],
});
const bundle = result.outputFiles[0].text;
const html = `<!doctype html><html><head><meta charset="utf-8"></head><body><div id="root"></div><script>${bundle}</script></body></html>`;
const browser = await chromium.launch({ headless: true });
try {
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
await context.addInitScript(fixture => { window.__fixture = fixture; window.__fixtureVersion = 0; window.__calls = []; window.__behavior = 'ok'; window.__TAURI_INTERNALS__ = {}; }, dashboard);
await context.route('**/*', async route => route.fulfill({ contentType: 'text/html', body: html }));
const page = await context.newPage();
page.setDefaultTimeout(5000);
const errors = [];
page.on('pageerror', e => errors.push(e.message));
await page.goto('http://focuslock.test/desktop.html');
await page.locator('.app-frame').waitFor();
await page.getByRole('button', { name: 'Boundaries' }).click();
await page.getByRole('button', { name: 'Websites' }).click();
const openAdd = page.locator('.add-site-button');
assert.equal(await openAdd.isDisabled(), true, 'Adding a website is unavailable during active Strict Mode');

// Unlock fixture and let the actual App query observe the reactive site list.
await page.evaluate(() => { window.__fixture.prefs.strictMode = false; window.dispatchEvent(new Event('fixture-change')); });
await page.waitForFunction(() => !document.querySelector('.add-site-button')?.disabled);
await page.getByLabel('Search websites').fill('no current match');
const socialChip = page.locator('.category-row').getByRole('button', { name: 'Social', exact: true });
if (await socialChip.count()) await socialChip.click();
await openAdd.click();
const dialog = page.getByRole('dialog', { name: 'Add website' });
const input = page.getByLabel('Website domain');
await input.fill('https://www.example.org/path?q=1');
await dialog.getByRole('button', { name: 'Add Website' }).click();
await page.getByText('example.org', { exact: true }).waitFor();
assert.equal(await dialog.count(), 0, 'Successful add closes the dialog');
assert.equal(await page.getByLabel('Search websites').inputValue(), '', 'Successful add resets website search');
assert.ok(await page.locator('.category-row .filter-chip.active').filter({ hasText: 'All' }).count(), 'Successful add resets website category');
const siteMutation = await page.evaluate(() => window.__calls.find(c => c.kind === 'mutation' && c.ref.includes('setBlockedWebsite')));
assert.ok(siteMutation, 'Uses the per-site mutation');
assert.equal(siteMutation.args.domain, 'example.org', 'Normalizes www and full URL to hostname');
assert.equal(siteMutation.args.isBlocked, true, 'New website is blocked');
assert.equal(await page.evaluate(() => window.__calls.some(c => c.kind === 'mutation' && c.ref.includes('saveBlockedWebsites'))), false, 'Does not replace the full website collection');
assert.ok(await page.evaluate(() => window.__calls.some(c => c.kind === 'invoke' && c.command === 'set_blocked_targets' && JSON.stringify(c.args).includes('example.org'))), 'Sends the successful website boundary to native enforcement');

for (const behavior of ['not-applied', 'missing-applied', 'throw']) {
  await page.evaluate(mode => { window.__behavior = mode; }, behavior);
  if (behavior === 'not-applied') await openAdd.click();
  const domain = `${behavior}.example.net`;
  await input.fill(domain);
  await dialog.getByRole('button', { name: 'Add Website' }).click();
  await page.locator('.inline-error').waitFor();
  assert.ok(await dialog.isVisible(), `${behavior} keeps dialog open`);
  assert.equal(await input.inputValue(), domain, `${behavior} preserves input for retry`);
  assert.ok((await page.locator('.inline-error').innerText()).length > 0, `${behavior} shows an error inside dialog`);
}

await page.evaluate(() => { window.__behavior = 'ok'; });
await dialog.getByRole('button', { name: 'Add Website' }).click();
await page.getByText('throw.example.net', { exact: true }).waitFor();
assert.equal(await dialog.count(), 0, 'Retry after a failed mutation closes on success');
assert.deepEqual(errors, [], 'No browser runtime errors');
const report = { checks: ['Strict Mode disables website add', 'full URL normalizes to www-free hostname', 'per-site mutation only', 'successful add resets search and category', 'applied:false and missing applied confirmation preserve dialog and input', 'thrown mutation preserves dialog and input', 'successful retry closes and shows row', 'native boundary receives new domain'], runtimeErrors: errors };
await writeFile(path.join(out, 'desktop-boundaries-results.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
} finally {
  await browser.close();
}
