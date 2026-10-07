// Fixture-only visual and behavior checks for the real Focus page time panel.
import { chromium } from '../extension/node_modules/playwright-core/index.mjs';
import { build } from '../extension/node_modules/esbuild/lib/main.js';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'build/ui-verification/time-dashboard');
await mkdir(out, { recursive: true });
const authStub = `export function useFocusAuth(){return {user:{id:'fixture-account',name:'Time Dashboard Test',email:'test@example.test'},loading:false,error:null,getSyncToken:async()=> 'fixture-token',signInInBrowser:async()=>{},signOut:async()=>{}}}`;
const durableSyncStub = `export const accountClient=()=>({mutation:async()=>({}),query:async()=>({})}); export async function flushMutations(){if(window.__syncMode==='fail')throw new Error('fixture sync failure'); if(window.__syncMode==='pending')await new Promise(resolve=>window.__releaseSync=resolve); return new Map()}; export function useDurableMutation(){return async()=>({applied:true})}; export function useMutationReplay(){}`;
const clerkStub = `export function SignIn(){return null}`;
const convexStub = `import {useSyncExternalStore} from 'react'; const subscribe=(fn)=>{window.addEventListener('fixture-change',fn);return()=>window.removeEventListener('fixture-change',fn)}; export const Authenticated=({children})=>children; export const Unauthenticated=()=>null; export const AuthLoading=()=>null; export function useQuery(ref,args){useSyncExternalStore(subscribe,()=>window.__fixtureVersion,()=>window.__fixtureVersion); const key=String(ref); if(key.includes('getDashboard')||key.includes('getConfiguration'))return window.__fixture.dashboard; if(key.includes('getState'))return {state:window.__fixture.dashboard.state}; if(key.includes('getHistory'))return {records:[],sessions:[]}; if(key.includes('getUsageSummary')){window.__queryCalls.push({args}); if(window.__fixture.summaryLoading)return undefined; return args?.fromDate===args?.toDate?window.__fixture.todaySummary:window.__fixture.rangeSummary;} if(key.includes('listDevices')||key.includes('listGroups')||key.includes('listKnownTargets'))return []; if(key.includes('getNuke'))return {isActive:false}; if(key.includes('getGuardian')||key.includes('getApprovalState'))return null; return undefined;} export function useMutation(){return async()=>({applied:true})} export function useAction(){return async()=>({reply:'Fixture'})}`;
const apiStub = `export const api={focus:{getDashboard:'focus.getDashboard',getConfiguration:'focus.getConfiguration',getState:'focus.getState',getHistory:'focus.getHistory',getAccount:'focus.getAccount',savePrefs:'focus.savePrefs',recordWork:'focus.recordWork',saveBlockedApps:'focus.saveBlockedApps',saveBlockedWebsites:'focus.saveBlockedWebsites',setBlockedWebsite:'focus.setBlockedWebsite',addPermanentBlocks:'focus.addPermanentBlocks'},devices:{heartbeat:'devices.heartbeat',listDevices:'devices.listDevices'},usage:{recordUsageBatch:'usage.recordUsageBatch',getUsageSummary:'usage.getUsageSummary',listKnownTargets:'usage.listKnownTargets'},groups:{listGroups:'groups.listGroups',saveGroups:'groups.saveGroups'},strictApproval:{getGuardian:'strictApproval.getGuardian',getApprovalState:'strictApproval.getApprovalState',configureGuardian:'strictApproval.configureGuardian',requestApprovalEmail:'strictApproval.requestApprovalEmail'},nuke:{getNuke:'nuke.getNuke',activate:'nuke.activate',completeMeditation:'nuke.completeMeditation',checkin:'nuke.checkin'}};`;
const tauriStub = `export async function invoke(command,args){window.__calls.push({command,args}); if(command==='get_tracking_snapshot'){const snapshot={device:{id:'local-pc',name:'Fixture PC',platform:'windows',createdAtMs:Date.now()},current:{capturedAtMs:Date.now(),appId:'com.boundary.game',appName:'Boundary Game',windowTitle:'Fixture',deviceId:'local-pc',deviceName:'Fixture PC',idle:false,blocked:true},usage:[{date:window.__fixture.today,targetKind:'app',appId:'com.boundary.game',appName:'Boundary Game',activeSeconds:1200}],running:true,config:{sampleIntervalMs:5000,idleThresholdSeconds:120,captureBrowserDomains:true}}; if(window.__holdFirstSnapshot){window.__holdFirstSnapshot=false; return new Promise(resolve=>window.__resolveSnapshot=()=>resolve(snapshot));} return snapshot;} if(command==='get_tracker_status')return {running:true,ready:true}; if(command==='get_permanent_targets')return []; return undefined}`;

const entry = path.join(out, 'time-dashboard-entry.tsx');
await writeFile(entry, `import React from 'react'; import {createRoot} from 'react-dom/client'; import App from ${JSON.stringify(path.join(root, 'desktop/src/App.tsx'))}; createRoot(document.getElementById('root')).render(<App/>);`);
const result = await build({
  absWorkingDir: path.join(root, 'desktop'), nodePaths: [path.join(root, 'desktop', 'node_modules')], entryPoints: [entry], bundle: true, write: false, format: 'iife', platform: 'browser', target: 'es2020', jsx: 'automatic',
  plugins: [{ name: 'time-dashboard-fixtures', setup(b) {
    const importer = args => String(args.importer).replace(/\\/g, '/');
    const fromApp = args => importer(args).endsWith('/desktop/src/App.tsx');
    const fromDesktop = args => importer(args).includes('/desktop/src/');
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
const now = new Date();
const today = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-');
const todaySummary = { totalTrackedSeconds: 900, targets: [{ targetKind: 'app', targetKey: 'com.boundary.game', targetLabel: 'Boundary Game', trackedSeconds: 900, deviceIds: ['local-pc'] }], deviceTargets: [{ deviceId: 'local-pc', targetKind: 'app', targetKey: 'com.boundary.game', trackedSeconds: 900 }] };
const fixture = {
  today,
  summaryLoading: true,
  dashboard: { state: { lastResetDate: today, totalWorkSecondsToday: 7200, creditBalanceSeconds: 1800 }, apps: [{ packageName: 'com.boundary.game', appName: 'Boundary Game', isBlocked: true, category: 'Games' }], sites: [], records: [], prefs: { workRatio: 4 } },
  todaySummary,
  rangeSummary: { totalTrackedSeconds: 50000, targets: [{ targetKind: 'app', targetKey: 'com.boundary.game', targetLabel: 'Boundary Game', trackedSeconds: 50000, deviceIds: ['local-pc'] }], deviceTargets: [{ deviceId: 'local-pc', targetKind: 'app', targetKey: 'com.boundary.game', trackedSeconds: 50000 }] },
};

try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 950 }, timezoneId: 'Asia/Kolkata' });
  await context.addInitScript(value => {
    window.__fixture = value; window.__fixtureVersion = 0; window.__calls = []; window.__queryCalls = []; window.__syncMode = 'ok'; window.__holdFirstSnapshot = true; window.__TAURI_INTERNALS__ = {};
  }, fixture);
  await context.route('**/*', async route => route.fulfill({ contentType: 'text/html', body: html }));
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://focuslock.test/time-dashboard.html');
  await page.locator('.app-frame').waitFor();
  await page.getByRole('heading', { name: "Today's time" }).waitFor();

  // Without a native snapshot or synced summary, leisure remains explicitly unknown.
  const leisure = page.locator('.time-pair article').filter({ has: page.getByText('Leisure', { exact: true }) }).locator('strong');
  await assert.doesNotReject(() => page.getByText('—', { exact: true }).waitFor());
  assert.equal(await leisure.innerText(), '—', 'Loading synced leisure shows a dash while local/native data is unavailable');
  assert.equal(await page.locator('.time-pair article').first().locator('strong').innerText(), '2h 0m', 'Fixture shows 120 minutes of focus');
  await writeFile(path.join(out, 'time-dashboard-loading.png'), await page.screenshot({ fullPage: true }));

  // Load synced 15-minute Boundary total, then add only this PC's fresh 5 minutes.
  await page.evaluate(() => { window.__fixture.summaryLoading = false; window.__fixtureVersion++; window.dispatchEvent(new Event('fixture-change')); });
  await page.evaluate(() => window.__resolveSnapshot());
  await page.waitForFunction(() => document.querySelector('.time-pair article:nth-child(2) strong')?.textContent === '20m');
  assert.equal(await leisure.innerText(), '20m', '15 synced minutes plus the 5-minute local cumulative increase are shown once');
  assert.equal(await page.locator('.device-summary h2').innerText(), '20m', 'Today screen time includes the same local increase as leisure');
  assert.equal(await page.getByRole('button', { name: 'Sync now', exact: true }).count(), 1, 'Sync now is accessible by its button name');

  const viewportChecks = [];
  for (const width of [1280, 600, 360]) {
    await page.setViewportSize({ width, height: 950 });
    await page.waitForTimeout(100);
    const dimensions = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth, body: document.body.scrollWidth }));
    assert.ok(dimensions.document <= width && dimensions.body <= width, `No horizontal overflow at ${width}px: ${JSON.stringify(dimensions)}`);
    assert.equal(await page.getByRole('button', { name: 'Sync now', exact: true }).count(), 1, `Sync now remains accessible at ${width}px`);
    await page.screenshot({ path: path.join(out, `time-dashboard-${width}.png`), fullPage: true });
    viewportChecks.push({ width, ...dimensions });
  }

  // Selecting seven days changes the history query, while the today-scoped query and leisure stay fixed.
  await page.getByRole('tab', { name: '7 days' }).click();
  await page.waitForFunction(() => window.__queryCalls.some(call => call.args?.fromDate && call.args.fromDate !== call.args.toDate));
  assert.equal(await leisure.innerText(), '20m', 'Today leisure stays today-scoped after selecting the seven-day range');
  const sevenDayArgs = await page.evaluate(() => window.__queryCalls.find(call => call.args?.fromDate && call.args.fromDate !== call.args.toDate)?.args);
  assert.ok(sevenDayArgs?.fromDate && sevenDayArgs?.toDate, 'Seven-day range query was issued');
  assert.equal(await page.locator('.device-summary h2').innerText(), '13h 53m', 'Range-specific total comes from the seven-day fixture');

  // Exercise visible syncing and failed states through the real Sync now handler.
  await page.evaluate(() => { window.__syncMode = 'pending'; });
  await page.getByRole('button', { name: 'Sync now', exact: true }).click();
  await page.getByRole('button', { name: 'Syncing…', exact: true }).waitFor();
  await page.getByText('Syncing your time across devices…', { exact: true }).waitFor();
  await page.evaluate(() => { window.__releaseSync?.(); window.__syncMode = 'fail'; });
  await page.getByRole('button', { name: 'Sync now', exact: true }).waitFor();
  await page.getByRole('button', { name: 'Sync now', exact: true }).click();
  await page.getByText('Sync failed · Error: fixture sync failure', { exact: true }).waitFor();

  assert.deepEqual(errors, [], 'No browser runtime errors');
  const report = {
    checks: ['unknown leisure is a dash without native or synced data', '120 minutes focused work renders', '15 synced Boundary minutes plus only the local increase totals 20 minutes', 'today leisure is independent of the 7-day range', 'syncing and failed states render through Sync now', 'Sync now has an accessible button name', 'no horizontal overflow at 1280, 600, or 360 pixels'],
    viewportChecks,
    sevenDayArgs,
    runtimeErrors: errors,
    screenshots: ['time-dashboard-loading.png', 'time-dashboard-1280.png', 'time-dashboard-600.png', 'time-dashboard-360.png'],
  };
  await writeFile(path.join(out, 'time-dashboard-results.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
  await context.close();
} finally {
  await browser.close();
}
