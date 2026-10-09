// Exercise the real Account page with deterministic Convex/native fixtures.
// Native session deletion is covered separately by auth.rs regression tests.
import { chromium } from "../extension/node_modules/playwright-core/index.mjs";
import { build } from "../extension/node_modules/esbuild/lib/main.js";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const out = path.join(root, "build/ui-verification");
await mkdir(out, { recursive: true });
const base = {
  apps: [],
  sites: [],
  permanentBlocks: [],
  limits: [],
  schedules: [],
  prefs: {},
  state: { creditBalanceSeconds: 0 },
};
const authStub = `export function useFocusAuth(){return {user:{id:'user-signout-fixture',name:'Signout Test',email:'signout@example.test'},loading:false,error:null,getSyncToken:async()=>null,signInInBrowser:async()=>{},signOut:async()=>{window.__signOuts++;window.__fixture={...window.__fixture, signedOut:true};window.dispatchEvent(new Event('fixture-change'));}}}`;
const durableStub = `export const accountClient=()=>({mutation:async()=>({}),query:async()=>({})});export async function flushMutations(){return new Map()};export function useDurableMutation(ref){return async(args)=>{window.__calls.push({kind:'mutation',ref:String(ref),args});return {applied:true}}};export function useMutationReplay(){}`;
const convexStub = `import {useSyncExternalStore} from 'react';const sub=fn=>{window.addEventListener('fixture-change',fn);return()=>window.removeEventListener('fixture-change',fn)};const snap=()=>window.__fixtureVersion;export const Authenticated=({children})=>children;export const Unauthenticated=()=>null;export const AuthLoading=()=>null;export function useQuery(ref){useSyncExternalStore(sub,snap,snap);const k=String(ref);if(k.includes('getDashboard')||k.includes('getConfiguration'))return window.__fixture.configMissing?undefined:window.__fixture;if(k.includes('getState'))return {state:window.__fixture.state};if(k.includes('getHistory'))return {records:[],sessions:[]};if(k.includes('getUsageSummary'))return {totalTrackedSeconds:0,devices:[],targets:[]};if(k.includes('listDevices'))return [];if(k.includes('listGroups'))return window.__fixture.groupsMissing?undefined:[];if(k.includes('getNuke'))return {isActive:Boolean(window.__fixture.nukeActive)};return undefined}export function useMutation(){return async()=>({applied:true})}export function useAction(){return async()=>({})}`;
const clerkStub = `export function SignIn(){return null}`;
const apiStub = `export const api={focus:{getDashboard:'focus.getDashboard',getConfiguration:'focus.getConfiguration',getState:'focus.getState',getHistory:'focus.getHistory',getAccount:'focus.getAccount',savePrefs:'focus.savePrefs',recordWork:'focus.recordWork',saveBlockedApps:'focus.saveBlockedApps',saveBlockedWebsites:'focus.saveBlockedWebsites'},devices:{heartbeat:'devices.heartbeat',listDevices:'devices.listDevices'},usage:{recordUsageBatch:'usage.recordUsageBatch',getUsageSummary:'usage.getUsageSummary',listKnownTargets:'usage.listKnownTargets'},groups:{listGroups:'groups.listGroups',saveGroups:'groups.saveGroups'},strictApproval:{getGuardian:'strictApproval.getGuardian',getApprovalState:'strictApproval.getApprovalState',configureGuardian:'strictApproval.configureGuardian',requestApprovalEmail:'strictApproval.requestApprovalEmail'},nuke:{getNuke:'nuke.getNuke',activate:'nuke.activate',completeMeditation:'nuke.completeMeditation',checkin:'nuke.checkin'}};`;
const tauriStub = `export async function invoke(command,args){window.__calls.push({kind:'invoke',command,args});if(command==='get_tracking_snapshot')return {device:{id:'signout-test',name:'Test PC',platform:'windows',createdAtMs:Date.now()},usage:[],running:true,blockedTargets:{appIds:[],domains:[]},config:{sampleIntervalMs:5000,idleThresholdSeconds:120,captureBrowserDomains:true}};if(command==='get_tracker_status')return {running:true,enforcementActive:false};if(command==='get_device_identity')return {id:'signout-test',name:'Test PC',platform:'windows',createdAtMs:Date.now()};if(command==='get_permanent_targets')return window.__fixture.permanentLocal||[];if(command==='sync_account_protection'){if(window.__persistMode==='reject')throw new Error('fixture persistence rejected');if(window.__persistMode==='pending')return new Promise(resolve=>{window.__resolveProtection=resolve});return {applied:true}}return undefined}`;
const entry = path.join(out, "desktop-signout-entry.tsx");
await writeFile(
  entry,
  `import React from 'react';import{createRoot}from'react-dom/client';import App from ${JSON.stringify(path.join(root, "desktop/src/App.tsx"))};createRoot(document.getElementById('root')).render(<App/>);`,
);
const result = await build({
  absWorkingDir: path.join(root, "desktop"),
  nodePaths: [path.join(root, "desktop/node_modules")],
  entryPoints: [entry],
  bundle: true,
  write: false,
  format: "iife",
  platform: "browser",
  target: "es2020",
  jsx: "automatic",
  plugins: [
    {
      name: "fixtures",
      setup(b) {
        const norm = (a) => String(a.importer).replace(/\\/g, "/");
        const desktop = (a) => norm(a).includes("/desktop/src/");
        const app = (a) => norm(a).endsWith("/desktop/src/App.tsx");
        b.onResolve({ filter: /^react$/ }, () => ({
          path: path.join(root, "desktop/node_modules/react/index.js"),
        }));
        b.onResolve({ filter: /^\.\/auth$/ }, (a) =>
          app(a) ? { path: "auth", namespace: "f" } : undefined,
        );
        b.onResolve({ filter: /^\.\/durableSync$/ }, (a) =>
          desktop(a) ? { path: "durable", namespace: "f" } : undefined,
        );
        b.onResolve({ filter: /convex[\\/]react$/ }, () => ({
          path: "convex",
          namespace: "f",
        }));
        b.onResolve({ filter: /clerk-react/ }, () => ({
          path: "clerk",
          namespace: "f",
        }));
        b.onResolve({ filter: /core$/ }, () => ({
          path: "tauri",
          namespace: "f",
        }));
        b.onResolve({ filter: /_generated/ }, () => ({
          path: "api",
          namespace: "f",
        }));
        b.onResolve({ filter: /\.css$/ }, (a) => ({
          path: path.resolve(a.resolveDir, a.path),
          namespace: "css",
        }));
        b.onLoad({ filter: /.*/, namespace: "f" }, (a) => ({
          contents: {
            auth: authStub,
            durable: durableStub,
            convex: convexStub,
            clerk: clerkStub,
            tauri: tauriStub,
            api: apiStub,
          }[a.path],
          loader: "js",
        }));
        b.onLoad({ filter: /.*/, namespace: "css" }, async (a) => ({
          contents: `const s=document.createElement('style');s.textContent=${JSON.stringify(await readFile(path.join(root, "desktop/src/theme-tokens.css"), "utf8"))}+${JSON.stringify(await readFile(a.path, "utf8"))};document.head.appendChild(s)`,
          loader: "js",
        }));
      },
    },
  ],
});
const html = `<!doctype html><html><body><div id="root"></div><script>${result.outputFiles[0].text}</script></body></html>`;
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 900 },
  });
  await context.addInitScript((f) => {
    window.__fixture = f;
    window.__fixtureVersion = 0;
    window.__calls = [];
    window.__signOuts = 0;
    window.__persistMode = "ok";
    window.__TAURI_INTERNALS__ = {};
  }, base);
  await context.route("**/*", (r) =>
    r.fulfill({ contentType: "text/html", body: html }),
  );
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("http://focuslock.test/desktop.html");
  await page.locator(".app-frame").waitFor();
  await page.getByRole("button", { name: "Account" }).click();
  const button = page.getByRole("button", { name: "Sign Out" });
  const checks = [];
  const set = async (patch) => {
    await page.evaluate((p) => {
      window.__fixture = { ...window.__fixture, ...p };
      window.__fixtureVersion++;
      window.dispatchEvent(new Event("fixture-change"));
    }, patch);
    await page.waitForTimeout(80);
  };
  const expectDisabled = async (patch) => {
    await set(patch);
    assert.equal(await button.isDisabled(), true, JSON.stringify(patch));
    checks.push("disabled:" + Object.keys(patch).join(","));
  };
  await expectDisabled({ apps: [{ category: "Windows", isBlocked: true }] });
  await expectDisabled({ apps: [], sites: [{ isBlocked: true }] });
  await expectDisabled({
    sites: [],
    limits: [{ targetKind: "app", dailyLimitMinutes: 30 }],
  });
  await expectDisabled({
    limits: [],
    schedules: [{ targetKind: "all", isEnabled: true }],
  });
  await expectDisabled({
    schedules: [],
    prefs: { strictMode: true, strictEndsAt: Date.now() + 3600000 },
  });
  await set({
    apps: [],
    sites: [],
    limits: [],
    schedules: [],
    permanentBlocks: [],
    prefs: { strictMode: true, strictEndsAt: Date.now() - 1 },
  });
  assert.equal(await button.isDisabled(), false);
  checks.push("expired-strict-allows");
  await set({
    apps: [],
    sites: [],
    limits: [],
    schedules: [],
    prefs: {},
    permanentBlocks: [],
  });
  await expectDisabled({ permanentBlocks: [{ targetKind: "website" }] });
  await set({
    permanentBlocks: [],
    sites: [{ isBlocked: true, domain: "example.invalid" }],
  });
  await page.waitForFunction(() =>
    window.__calls.some(
      (c) =>
        c.command === "set_browser_protection_policy" &&
        c.args?.required === true,
    ),
  );
  const missingCalls = await page.evaluate(() => window.__calls.length);
  await set({
    permanentBlocks: [],
    apps: [],
    sites: [],
    limits: [],
    schedules: [],
    configMissing: true,
  });
  assert.equal(await button.isDisabled(), true);
  checks.push("missing-config-disabled");
  await page.waitForTimeout(100);
  assert.equal(
    await page.evaluate(
      (n) =>
        window.__calls
          .slice(n)
          .some(
            (c) =>
              c.command === "set_browser_protection_policy" &&
              c.args?.required === false,
          ),
      missingCalls,
    ),
    false,
  );
  checks.push("missing-config-no-policy-clear");
  await set({ configMissing: false, groupsMissing: true });
  assert.equal(await button.isDisabled(), true);
  checks.push("missing-groups-disabled");
  await set({
    groupsMissing: false,
    permanentBlocks: [],
    apps: [],
    sites: [],
    limits: [],
    schedules: [],
  });
  await set({ nukeActive: true });
  assert.equal(await button.isDisabled(), true);
  checks.push("active-nuke-disabled");
  await set({ nukeActive: false });
  await page.evaluate(() => {
    window.__persistMode = "pending";
  });
  await set({ apps: [{ category: "Windows", isBlocked: true }] });
  await set({
    apps: [],
    sites: [],
    limits: [],
    schedules: [],
    permanentBlocks: [],
  });
  assert.equal(await button.isDisabled(), true);
  assert.match(
    await page.locator("#sign-out-protection").innerText(),
    /Saving sign-out protection/,
  );
  assert.equal(await page.evaluate(() => window.__signOuts), 0);
  await page.evaluate(() => window.__resolveProtection());
  await page.waitForFunction(
    () =>
      !document.querySelector("button[aria-describedby='sign-out-protection']"),
  );
  assert.equal(await button.isDisabled(), false);
  checks.push("pending-native-persistence-blocks-until-acknowledged");
  await page.evaluate(() => {
    window.__persistMode = "reject";
  });
  await set({ configMissing: true });
  await set({ configMissing: false });
  await page.waitForFunction(() =>
    document
      .querySelector("#sign-out-protection")
      ?.textContent.includes("fixture persistence rejected"),
  );
  assert.equal(await button.isDisabled(), true);
  assert.equal(await page.evaluate(() => window.__signOuts), 0);
  checks.push("rejected-native-persistence-blocks-signout");
  await page.evaluate(() => {
    window.__persistMode = "ok";
  });
  await set({ configMissing: true });
  await set({ configMissing: false });
  await page.waitForFunction(
    () =>
      !document.querySelector("button[aria-describedby='sign-out-protection']"),
  );
  assert.equal(await button.isDisabled(), false);
  await button.click();
  await page.waitForTimeout(100);
  assert.equal(await page.evaluate(() => window.__signOuts), 1);
  checks.push("signout-invoked-after-persisted-clear");

  const localContext = await browser.newContext();
  await localContext.addInitScript(
    (fixture) => {
      window.__fixture = fixture;
      window.__fixtureVersion = 0;
      window.__calls = [];
      window.__signOuts = 0;
      window.__persistMode = "ok";
      window.__TAURI_INTERNALS__ = {};
    },
    { ...base, permanentLocal: ["steam.exe"] },
  );
  await localContext.route("**/*", (route) =>
    route.fulfill({ contentType: "text/html", body: html }),
  );
  const localPage = await localContext.newPage();
  localPage.on("pageerror", (error) => errors.push(error.message));
  await localPage.goto("http://focuslock.test/local-permanent.html");
  await localPage.getByRole("button", { name: "Account" }).click();
  await localPage.locator("#sign-out-protection").waitFor();
  assert.equal(
    await localPage.getByRole("button", { name: "Sign Out" }).isDisabled(),
    true,
  );
  assert.match(
    await localPage.locator("#sign-out-protection").innerText(),
    /Sign-out is unavailable/,
  );
  assert.equal(await localPage.evaluate(() => window.__signOuts), 0);
  checks.push("native-device-permanent-block-disables-signout");
  await localContext.close();
  assert.deepEqual(errors, []);
  const report = {
    checks,
    runtimeErrors: errors,
    signOuts: await page.evaluate(() => window.__signOuts),
  };
  await writeFile(
    path.join(out, "desktop-signout-results.json"),
    JSON.stringify(report, null, 2),
  );
  console.log(JSON.stringify(report, null, 2));
} finally {
  await browser.close();
}
