// Deterministic UI walkthroughs for the real desktop React components.
// All persistence, auth, Convex, and Tauri calls are local fixtures.
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const out = path.join(root, "build/ui-verification");
await mkdir(out, { recursive: true });

function resolvePackage(name) {
  const candidates = [
    process.env[`${name.replaceAll("-", "_").toUpperCase()}_PATH`],
    path.join(root, "desktop"),
    path.join(root, "extension"),
    root,
  ].filter(Boolean);
  let lastError;
  for (const base of candidates) {
    try { return createRequire(path.join(base, "package.json")).resolve(name); }
    catch (error) { lastError = error; }
  }
  throw new Error(`Could not resolve ${name}; install it in desktop/ or extension/. ${lastError}`);
}

const playwright = await import(pathToFileURL(resolvePackage("playwright-core")));
const chromium = playwright.chromium || playwright.default?.chromium;
const { build } = await import(pathToFileURL(resolvePackage("esbuild")));

const fixture = {
  signedIn: true,
  nuke: { isActive: false },
  blocker: { visible: true, reason: "boundary", target: "youtube.com" },
  repair: { browser: "chrome.exe", appId: "chrome.exe", graceRemainingSeconds: 35, reason: "extension_missing" },
  dashboard: {
    state: { creditBalanceSeconds: 7380 },
    apps: [
      { packageName: "com.code.editor", appName: "Code Editor", isBlocked: false, category: "Windows" },
      { packageName: "com.spotify", appName: "Spotify", isBlocked: false, category: "Music" },
      { packageName: "com.youtube.desktop", appName: "YouTube", isBlocked: true, category: "Video" },
    ],
    sites: [
      { domain: "youtube.com", displayName: "YouTube", isBlocked: true, category: "Video" },
      { domain: "reddit.com", displayName: "Reddit", isBlocked: true, category: "Social" },
      { domain: "docs.google.com", displayName: "Google Docs", isBlocked: false, category: "Work" },
    ],
    permanentBlocks: [], limits: [], schedules: [], records: [], sessions: [],
    prefs: { strictMode: false, strictEndsAt: 0, strictPreset: "custom", workRatio: 4, taskBonusMinutes: 5 },
  },
  groups: [{ groupId: "study", name: "Study group", dailyLimitMinutes: 60, members: [
    { targetKind: "app", targetKey: "com.code.editor", targetLabel: "Code Editor" },
    { targetKind: "website", targetKey: "docs.google.com", targetLabel: "Google Docs" },
  ] }],
  devices: [{ deviceId: "windows-ui", name: "Review PC", platform: "windows", trackingStatus: "active", lastSeen: Date.now() }],
  usage: {
    totalTrackedSeconds: 6840,
    devices: [{ deviceId: "windows-ui", trackedSeconds: 3240, name: "Review PC", platform: "windows" }],
    targets: [{ targetKind: "app", targetKey: "com.code.editor", targetLabel: "Code Editor", trackedSeconds: 900, deviceIds: ["windows-ui"] }],
  },
};

const authStub = `import {useSyncExternalStore} from 'react';const subscribe=f=>{window.addEventListener('fixture-change',f);return()=>window.removeEventListener('fixture-change',f)};export function useFocusAuth(){useSyncExternalStore(subscribe,()=>window.__fixtureVersion,()=>window.__fixtureVersion);return {user:window.__fixture.signedIn?{id:'ui-review',name:'UI Review',email:'review@example.test'}:null,loading:Boolean(window.__fixture.loading),error:null,signInInBrowser:async()=>{window.__calls.push({kind:'auth',action:'signInInBrowser'})},signOut:async()=>{window.__calls.push({kind:'auth',action:'signOut'})},getSyncToken:async()=> 'fixture-token'}};export function ClerkWebAuthProvider({children}){return children};export function DesktopBrowserAuthProvider({children}){return children}`;
const durableStub = `export const accountClient=()=>({mutation:async()=>({}),query:async()=>({})});export async function flushMutations(){return new Map()};export function useMutationReplay(){};export function useDurableMutation(ref){return async(args)=>{window.__calls.push({kind:'mutation',ref:String(ref),args});if(String(ref).includes('saveGroups'))window.__fixture.groups=args.groups||[];if(String(ref).includes('setBlockedWebsite')){window.__fixture.dashboard.sites=window.__fixture.dashboard.sites.filter(x=>x.domain!==args.domain);window.__fixture.dashboard.sites.push({...args,displayName:args.displayName||args.domain,category:'Custom'})}window.__fixtureVersion++;window.dispatchEvent(new Event('fixture-change'));return {applied:true}}}`;
const clerkStub = `import React from 'react';export function SignIn(){return React.createElement('div',{className:'fixture-sign-in'},'Clerk sign-in fixture')};export function ClerkProvider({children}){return children};export function useAuth(){return {isSignedIn:true}}`;
const convexStub = `import React,{useSyncExternalStore} from 'react';const subscribe=f=>{window.addEventListener('fixture-change',f);return()=>window.removeEventListener('fixture-change',f)};const version=()=>window.__fixtureVersion;export const Authenticated=({children})=>children;export const Unauthenticated=()=>null;export const AuthLoading=()=>null;export function ConvexProvider({children}){return children};export function ConvexProviderWithClerk({children}){return children};export function ConvexReactClient(){return {}};export function useQuery(ref){useSyncExternalStore(subscribe,version,version);const k=String(ref),f=window.__fixture;if(f.crash)throw new Error('Fixture render failure');if(k.includes('getDashboard')||k.includes('getConfiguration'))return {...f.dashboard,groups:f.groups};if(k.includes('getState'))return {state:f.dashboard.state};if(k.includes('getHistory'))return {records:f.dashboard.records,sessions:[]};if(k.includes('getUsageSummary'))return f.usage;if(k.includes('listDevices'))return f.devices;if(k.includes('listGroups'))return f.groups;if(k.includes('getNuke'))return f.nuke;if(k.includes('getGuardian'))return null;if(k.includes('getApprovalState'))return null;return undefined};export function useMutation(ref){return async args=>{window.__calls.push({kind:'mutation',ref:String(ref),args});if(String(ref).includes('completeMeditation')){window.__fixture.nuke={...window.__fixture.nuke,meditationCompletedAt:Date.now()};window.__fixtureVersion++;window.dispatchEvent(new Event('fixture-change'))}return {applied:true}}};export function useAction(ref){return async args=>{window.__calls.push({kind:'action',ref:String(ref),args});return {reply:'Fixture check-in response'}}}`;
const apiStub = `export const api={focus:{getDashboard:'focus.getDashboard',getConfiguration:'focus.getConfiguration',getState:'focus.getState',getHistory:'focus.getHistory',getAccount:'focus.getAccount',savePrefs:'focus.savePrefs',recordWork:'focus.recordWork',saveBlockedApps:'focus.saveBlockedApps',saveBlockedWebsites:'focus.saveBlockedWebsites',setBlockedWebsite:'focus.setBlockedWebsite'},devices:{heartbeat:'devices.heartbeat',listDevices:'devices.listDevices'},usage:{recordUsageBatch:'usage.recordUsageBatch',getUsageSummary:'usage.getUsageSummary',listKnownTargets:'usage.listKnownTargets'},groups:{listGroups:'groups.listGroups',saveGroups:'groups.saveGroups'},strictApproval:{getGuardian:'strictApproval.getGuardian',getApprovalState:'strictApproval.getApprovalState',configureGuardian:'strictApproval.configureGuardian',requestApprovalEmail:'strictApproval.requestApprovalEmail'},nuke:{getNuke:'nuke.getNuke',activate:'nuke.activate',completeMeditation:'nuke.completeMeditation',checkin:'nuke.checkin'}};`;
const tauriStub = `export async function invoke(command,args){window.__calls.push({kind:'invoke',command,args});const f=window.__fixture;if(command==='get_tracking_snapshot')return {device:{id:'windows-ui',name:'Review PC',platform:'windows',createdAtMs:Date.now()},current:{capturedAtMs:Date.now(),appId:'com.code.editor',appName:'Code Editor',windowTitle:'UI fixture',deviceId:'windows-ui',deviceName:'Review PC',idle:false,blocked:false},usage:[],running:true,blockedTargets:{appIds:[],domains:[]},config:{sampleIntervalMs:5000,idleThresholdSeconds:120,captureBrowserDomains:true}};if(command==='get_tracker_status')return {running:true,enforcementActive:true};if(command==='get_device_identity')return {id:'windows-ui',name:'Review PC',platform:'windows',createdAtMs:Date.now()};if(command==='get_permanent_targets')return f.permanentTargets||[];if(command==='get_blocker_state')return f.blocker;if(command==='get_browser_repair_state')return f.repair;if(command==='add_permanent_targets')return {added:args?.appIds||[],rejected:[]};if(command==='get_void_launcher_status')return {running:false};return undefined};`;
const windowStub = `export function getCurrentWindow(){return {startDragging:async()=>{window.__calls.push({kind:'window',action:'startDragging'})}}}`;

const entry = path.join(out, "desktop-ui-entry.tsx");
await writeFile(entry, `import ${JSON.stringify(path.join(root, "desktop/src/main.tsx"))};`);
const requireFrom = createRequire(path.join(root, "desktop", "package.json"));
const result = await build({
  absWorkingDir: path.join(root, "desktop"), nodePaths: [path.join(root, "desktop/node_modules")],
  entryPoints: [entry], bundle: true, write: false, format: "iife", platform: "browser", target: "es2020", jsx: "automatic",
  define: { "import.meta.env.VITE_CONVEX_URL": "window.__FIXTURE_ENV__.VITE_CONVEX_URL", "import.meta.env.VITE_CLERK_PUBLISHABLE_KEY": "window.__FIXTURE_ENV__.VITE_CLERK_PUBLISHABLE_KEY" },
  plugins: [{ name: "desktop-fixtures", setup(b) {
    const normalized = a => String(a.importer).replace(/\\/g, "/");
    const fromDesktop = a => normalized(a).includes("/desktop/src/");
    const fromApp = a => normalized(a).endsWith("/desktop/src/App.tsx");
    b.onResolve({ filter: /^react$/ }, () => ({ path: requireFrom.resolve("react") }));
    b.onResolve({ filter: /^react-dom\/client$/ }, () => ({ path: requireFrom.resolve("react-dom/client") }));
    b.onResolve({ filter: /^\.\/auth$/ }, a => fromDesktop(a) ? ({ path: "auth", namespace: "fixture" }) : undefined);
    b.onResolve({ filter: /^\.\/durableSync$/ }, a => fromDesktop(a) ? ({ path: "durable", namespace: "fixture" }) : undefined);
    b.onResolve({ filter: /convex[\\/]react$/ }, () => ({ path: "convex", namespace: "fixture" }));
    b.onResolve({ filter: /convex[\\/]react-clerk$/ }, () => ({ path: "convex-clerk", namespace: "fixture" }));
    b.onResolve({ filter: /clerk-react$/ }, () => ({ path: "clerk", namespace: "fixture" }));
    b.onResolve({ filter: /@tauri-apps[\\/]api[\\/]core$/ }, () => ({ path: "tauri", namespace: "fixture" }));
    b.onResolve({ filter: /@tauri-apps[\\/]api[\\/]window$/ }, () => ({ path: "window", namespace: "fixture" }));
    b.onResolve({ filter: /_generated/ }, () => ({ path: "api", namespace: "fixture" }));
    b.onResolve({ filter: /\.css$/ }, a => ({ path: path.resolve(a.resolveDir, a.path), namespace: "fixture-css" }));
    b.onLoad({ filter: /.*/, namespace: "fixture" }, a => ({ contents: ({ auth: authStub, durable: durableStub, convex: convexStub, "convex-clerk": `export const ConvexProviderWithClerk=({children})=>children`, clerk: clerkStub, tauri: tauriStub, window: windowStub, api: apiStub })[a.path], loader: "js" }));
    b.onLoad({ filter: /.*/, namespace: "fixture-css" }, async a => {
      const tokens = await readFile(path.join(root, "desktop/src/theme-tokens.css"), "utf8");
      const css = tokens + "\n" + (await readFile(a.path, "utf8")).replace(/@import\\s+[\"'][^\"']+[\"'];/g, "");
      return { contents: `const s=document.createElement('style');s.textContent=${JSON.stringify(css)};document.head.appendChild(s)`, loader: "js" };
    });
  }}],
});
const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script>${result.outputFiles[0].text}</script></body></html>`;
const browser = await chromium.launch({ headless: true });
const errors = [];

async function openPage(state = {}, hash = "") {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: "dark" });
  await context.addInitScript((data) => {
    window.__fixture = structuredClone(data.fixture);
    Object.assign(window.__fixture, data.state);
    window.__FIXTURE_ENV__ = data.state.environment || { VITE_CONVEX_URL: "https://ui-review.convex.cloud", VITE_CLERK_PUBLISHABLE_KEY: "pk_test_fixture" };
    window.__fixtureVersion = 0;
    window.__calls = [];
    window.__TAURI_INTERNALS__ = {
      transformCallback(callback, once = false) {
        const callbacks = window.__tauriCallbacks ||= {};
        const id = String(Object.keys(callbacks).length + 1);
        callbacks[id] = callback;
        if (once) callbacks[id].once = true;
        return id;
      },
      unregisterCallback(id) { delete window.__tauriCallbacks?.[id]; },
      invoke(command, args) {
        if (command === "plugin:event|listen") return Promise.resolve(1);
        if (command === "plugin:event|unlisten") return Promise.resolve();
        return Promise.resolve(undefined);
      },
    };
    window.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener() {} };
  }, { fixture, state });
  await context.route("**/*", route => route.fulfill({ contentType: "text/html", body: html }));
  const page = await context.newPage();
  page.setDefaultTimeout(5000);
  page.on("pageerror", e => { if (!state.crash) errors.push(`${hash || "app"}: ${e.message}`); });
  await page.goto(`http://focuslock.test/${hash}`);
  return { context, page };
}

async function assertInsideViewport(page, locator, label) {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  const viewport = page.viewportSize();
  assert.ok(box, `${label} has a visible bounding box`);
  assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width + 1 && box.y + box.height <= viewport.height + 1,
    `${label} fits the ${viewport.width}x${viewport.height} viewport after scrolling (${JSON.stringify(box)})`);
}

try {
  // Signed-in main app: all five tabs and basic Focus interactions.
  const { context, page } = await openPage();
  await page.locator(".app-frame").waitFor();
  const tabs = ["Focus", "Boundaries", "Permalock", "Settings", "Account"];
  for (const viewport of [{ width: 1280, height: 900, name: "wide" }, { width: 1000, height: 720, name: "1000x720" }, { width: 390, height: 720, name: "390x720" }]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    for (const tab of tabs) {
      await page.getByRole("button", { name: tab, exact: true }).click();
      assert.match(await page.locator(".nav-button.active").innerText(), new RegExp(tab, "i"));
      const buttons = page.locator(".rail nav .nav-button");
      assert.equal(await buttons.count(), 5, "All five navigation buttons are present");
      if (viewport.width < 900) {
        const rects = await buttons.evaluateAll(nodes => nodes.map(node => {
          const r = node.getBoundingClientRect();
          return { top: r.top, left: r.left, right: r.right, bottom: r.bottom };
        }));
        const rail = await page.locator(".rail nav").boundingBox();
        assert.ok(rects.every(r => Math.abs(r.top - rects[0].top) < 1), `Mobile navigation stays in one row at ${viewport.width}px`);
        assert.ok(rects.every(r => r.left >= rail.x && r.right <= rail.x + rail.width && r.top >= rail.y && r.bottom <= rail.y + rail.height), `Navigation buttons fit inside rail at ${viewport.width}px`);
        assert.equal(await page.locator(".rail-avatar").evaluate(node => getComputedStyle(node).display), "none", "Rail avatar is hidden in compact navigation");
      }
      await page.screenshot({ path: path.join(out, `desktop-ui-${tab.toLowerCase()}-${viewport.name}.png`), fullPage: true, animations: "disabled" });
    }
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole("button", { name: "Focus", exact: true }).click();
  await page.getByRole("button", { name: "Start focus" }).click();
  assert.equal(await page.getByRole("button", { name: "Pause" }).count(), 1, "Focus timer starts");
  await page.getByRole("button", { name: "Pause" }).click();
  await page.getByLabel("Work minutes").fill("35");
  await page.getByLabel("Tasks finished").fill("2");
  await page.getByLabel("Work title").fill("Fixture work log");
  await page.locator("#manual-log").getByRole("button", { name: "Log work" }).click();
  await page.getByText(/Logged 35m \+ 2 tasks/).waitFor();
  assert.ok(await page.evaluate(() => window.__calls.some(x => x.kind === "mutation" && x.ref.includes("recordWork"))), "Manual log uses fixture mutation");

  // Boundaries app/site and add-site dialog, with no persistent policy changes.
  await page.getByRole("button", { name: "Boundaries", exact: true }).click();
  assert.ok(await page.getByRole("button", { name: /Applications/ }).count());
  await page.getByRole("button", { name: "Websites" }).click();
  await page.getByRole("button", { name: "Add Website" }).click();
  const siteDialog = page.getByRole("dialog", { name: "Add website" });
  await siteDialog.getByLabel("Website domain").fill("https://sample.example/path");
  await siteDialog.getByRole("button", { name: "Add Website" }).click();
  await page.getByText("sample.example", { exact: true }).waitFor();
  await page.getByRole("button", { name: "Applications" }).click();

  // Group create/edit forms. Only the in-memory Convex fixture is updated.
  await page.getByRole("button", { name: /New group/ }).click();
  const createGroup = page.getByRole("dialog", { name: "Create group" });
  await createGroup.getByLabel("Name").fill("Fixture group");
  const candidate = createGroup.locator(".picker-row:not(:disabled)");
  for (let i = 0; i < Math.min(2, await candidate.count()); i++) await candidate.nth(i).click();
  await createGroup.getByRole("button", { name: "Create group", exact: true }).click();
  await page.getByText("Created Fixture group.").waitFor();
  await page.getByRole("button", { name: "Edit", exact: true }).first().click();
  const editGroup = page.getByRole("dialog", { name: "Edit group" });
  await editGroup.getByLabel("Name").fill("Fixture group edited");
  await editGroup.getByRole("button", { name: "Cancel" }).click();
  assert.equal(await editGroup.count(), 0, "Edit dialog cancels cleanly");

  // Permalock confirmation cancellation avoids even fixture persistence.
  await page.getByRole("button", { name: "Permalock", exact: true }).click();
  const select = page.getByRole("button", { name: /Select .* for permanent blocking/ }).first();
  await select.click();
  await page.getByRole("button", { name: "Block permanently" }).click();
  const permanentDialog = page.getByRole("dialog", { name: "Confirm permanent block" });
  await permanentDialog.getByRole("button", { name: "Cancel" }).click();
  assert.equal(await permanentDialog.count(), 0, "Permalock confirmation cancels");
  assert.equal(await page.evaluate(() => window.__calls.some(x => x.command === "add_permanent_targets")), false, "Cancel makes no native policy call");

  // Strict until-time fields are exercised in Settings with an in-memory save.
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await page.getByLabel("Strict mode timing").selectOption("until");
  const untilInTwoDays = await page.evaluate(() => {
    const date = new Date(Date.now() + 2 * 24 * 60 * 60_000);
    const pad = value => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  });
  await page.getByLabel("Strict mode end time").fill(untilInTwoDays);
  assert.equal(await page.locator(".strict-plan-fields .settings-field").count(), 3, "Until-time form exposes preset, timing, and end time");
  const tooFarAhead = await page.evaluate(() => {
    const date = new Date(Date.now() + 31 * 24 * 60 * 60_000);
    const pad = value => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  });
  await page.getByLabel("Strict mode end time").fill(tooFarAhead);
  await page.getByRole("button", { name: "Start commitment" }).click();
  await page.locator(".strict-plan-actions [role=alert]").filter({ hasText: "up to 30 days" }).waitFor();
  assert.equal(await page.evaluate(() => window.__calls.some(x => x.kind === "mutation" && x.args?.strictMode === true)), false, "Invalid Strict end time makes no fixture mutation");
  await page.getByLabel("Strict mode end time").fill(untilInTwoDays);
  await page.getByRole("button", { name: "Start commitment" }).click();
  await page.waitForFunction(() => window.__calls.some(x => x.kind === "mutation" && x.args?.strictMode === true));
  const strictPlan = await page.evaluate(() => window.__calls.find(x => x.kind === "mutation" && x.args?.strictMode === true)?.args);
  assert.ok(strictPlan.strictEndsAt > Date.now() && strictPlan.strictEndsAt <= Date.now() + 30 * 24 * 60 * 60_000, "Valid Strict end time saves within 30-day limit");

  // Nuke confirmation can be canceled; separate fixtures cover active timer and check-in.
  const nukeAction = page.locator(".nuke-action");
  await nukeAction.getByRole("button", { name: "Start reset" }).click();
  await nukeAction.getByRole("button", { name: "Cancel" }).click();
  assert.equal(await page.getByRole("dialog", { name: "Reset active" }).count(), 0, "Nuke confirmation cancels without activation");
  await context.close();

  for (const [name, state, expected] of [
    ["nuke-active", { nuke: { isActive: true, startedAt: Date.now() } }, /Nuke active/],
    ["nuke-checkin", { nuke: { isActive: true, startedAt: Date.now() - 11 * 60_000, meditationCompletedAt: Date.now() } }, /Check in to unlock/],
  ]) {
    const scenario = await openPage(state);
    await scenario.page.locator(".app-frame").waitFor();
    const dialog = scenario.page.getByRole("dialog");
    await dialog.waitFor();
    assert.match(await dialog.innerText(), expected);
    for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 480 }]) {
      await scenario.page.setViewportSize(viewport);
      await assertInsideViewport(scenario.page, scenario.page.locator(".nuke-card"), `${name} card`);
      if (name === "nuke-checkin") {
        await assertInsideViewport(scenario.page, scenario.page.getByLabel("Your next-action plan"), "Nuke check-in field");
        await assertInsideViewport(scenario.page, scenario.page.getByRole("button", { name: "Send" }), "Nuke check-in action");
      }
      await scenario.page.screenshot({ path: path.join(out, `desktop-ui-${name}-${viewport.width}x${viewport.height}.png`), fullPage: false });
    }
    if (name === "nuke-checkin") {
      await scenario.page.getByLabel("Your next-action plan").fill("Read chapter 5 for 25 minutes; phone away");
      await scenario.page.getByRole("button", { name: "Send" }).click();
      await scenario.page.getByText("Fixture check-in response").waitFor();
    }
    await scenario.context.close();
  }

  // Real main.tsx hash routes for native blocker and draggable browser repair.
  for (const [reason, state, expected] of [
    ["boundary", { blocker: { visible: true, reason: "boundary", target: "youtube.com" } }, /Boundary/],
    ["limit", { blocker: { visible: true, reason: "limit", target: "reddit.com" } }, /Daily limit reached/],
    ["permanent", { blocker: { visible: true, reason: "permanent", target: "com.example.app" } }, /Permanently blocked/],
    ["frog", { blocker: { visible: true, reason: "frog", target: "youtube.com" } }, /Eat the frog first/],
  ]) {
    const scenario = await openPage(state, "#/blocked");
    await scenario.page.getByRole("dialog").waitFor();
    assert.match(await scenario.page.locator(".blocker-card").innerText(), expected);
    for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 480 }]) {
      await scenario.page.setViewportSize(viewport);
      await assertInsideViewport(scenario.page, scenario.page.locator(".blocker-card"), `${reason} blocker card`);
      const actions = scenario.page.locator(".blocker-actions button");
      for (let i = 0; i < await actions.count(); i++) await assertInsideViewport(scenario.page, actions.nth(i), `${reason} blocker action ${i + 1}`);
      await scenario.page.screenshot({ path: path.join(out, `desktop-ui-blocker-${reason}-${viewport.width}x${viewport.height}.png`), fullPage: false });
    }
    await scenario.context.close();
  }
  {
    const scenario = await openPage({}, "#/browser-repair");
    await scenario.page.getByRole("heading", { name: "Reconnect FocusLock" }).waitFor();
    await scenario.page.setViewportSize({ width: 360, height: 220 });
    for (const locator of [
      scenario.page.getByRole("heading", { name: "Reconnect FocusLock" }),
      scenario.page.locator(".browser-repair-copy"),
      scenario.page.locator(".browser-repair-countdown"),
      scenario.page.getByRole("button", { name: "Open extension settings" }),
    ]) await assertInsideViewport(scenario.page, locator, "Browser repair field");
    await scenario.page.screenshot({ path: path.join(out, "desktop-ui-browser-repair-360x220.png"), fullPage: false });
    await scenario.page.setViewportSize({ width: 1280, height: 900 });
    await scenario.page.getByRole("button", { name: "Open extension settings" }).click();
    assert.ok(await scenario.page.evaluate(() => window.__calls.some(x => x.command === "open_browser_extension_settings")), "Browser repair action reaches native fixture");
    await scenario.page.screenshot({ path: path.join(out, "desktop-ui-browser-repair.png"), fullPage: true });
    await scenario.context.close();
  }
  {
    const scenario = await openPage({ repair: { browser: "firefox.exe", appId: "firefox.exe", graceRemainingSeconds: 20, reason: "browser_unsupported" } }, "#/browser-repair");
    await scenario.page.getByRole("heading", { name: "Use a supported browser" }).waitFor();
    await scenario.page.setViewportSize({ width: 360, height: 220 });
    await assertInsideViewport(scenario.page, scenario.page.getByRole("heading", { name: "Use a supported browser" }), "Unsupported-browser heading");
    assert.equal(await scenario.page.getByRole("button", { name: "Open extension settings" }).count(), 0, "Unsupported browsers do not expose extension settings");
    await scenario.page.screenshot({ path: path.join(out, "desktop-ui-browser-unsupported-360x220.png"), fullPage: false });
    await scenario.context.close();
  }

  // Signed-out and signed-in auth route states through the real App component.
  {
    const scenario = await openPage({ signedIn: false });
    await scenario.page.getByRole("heading", { name: "Your time, on every device." }).waitFor();
    await scenario.page.getByRole("button", { name: "Sign in in browser" }).click();
    assert.ok(await scenario.page.evaluate(() => window.__calls.some(x => x.kind === "auth" && x.action === "signInInBrowser")));
    await scenario.page.screenshot({ path: path.join(out, "desktop-ui-signed-out.png"), fullPage: true });
    await scenario.context.close();
  }
  {
    const scenario = await openPage({ loading: true });
    await scenario.page.getByText("Connecting FocusLock…").waitFor();
    await scenario.page.screenshot({ path: path.join(out, "desktop-ui-auth-loading.png"), fullPage: false });
    await scenario.context.close();
  }
  {
    const scenario = await openPage({ environment: { VITE_CONVEX_URL: "", VITE_CLERK_PUBLISHABLE_KEY: "" } });
    await scenario.page.getByRole("heading", { name: "FocusLock can’t connect yet" }).waitFor();
    await scenario.page.screenshot({ path: path.join(out, "desktop-ui-configuration-error.png"), fullPage: false });
    await scenario.context.close();
  }
  {
    const scenario = await openPage({ crash: true });
    await scenario.page.getByRole("heading", { name: "FocusLock hit an unexpected error" }).waitFor();
    assert.equal(await scenario.page.locator(".bootstrap-error-detail").innerText(), "Fixture render failure", "ErrorBoundary shows the fixture error detail");
    assert.equal(await scenario.page.getByRole("button", { name: "Retry" }).getAttribute("class"), "bootstrap-retry", "ErrorBoundary uses the styled retry control");
    await scenario.page.screenshot({ path: path.join(out, "desktop-ui-error-boundary.png"), fullPage: false });
    await scenario.context.close();
  }

  const widths = [1000, 1280, 840, 600, 390];
  const layout = await openPage();
  await layout.page.locator(".app-frame").waitFor();
  const overflowChecks = [];
  for (const width of widths) {
    await layout.page.setViewportSize({ width, height: 900 });
    for (const tab of tabs) {
      await layout.page.getByRole("button", { name: tab, exact: true }).click();
      const overflow = await layout.page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
      assert.equal(overflow, false, `No horizontal overflow at ${width}px on ${tab}`);
      overflowChecks.push({ width, tab, horizontalOverflow: overflow });
    }
  }
  await layout.context.close();

  // Dialogs remain scrollable and their actions can be reached at short heights.
  const short = await openPage();
  await short.page.locator(".app-frame").waitFor();
  await short.page.getByRole("button", { name: "Boundaries", exact: true }).click();
  await short.page.getByRole("button", { name: "Websites" }).click();
  await short.page.setViewportSize({ width: 600, height: 480 });
  await short.page.getByRole("button", { name: "Add Website" }).click();
  const shortDialog = short.page.getByRole("dialog", { name: "Add website" });
  for (const height of [720, 480]) {
    await short.page.setViewportSize({ width: 600, height });
    await shortDialog.scrollIntoViewIfNeeded();
    await assertInsideViewport(short.page, shortDialog.getByRole("button", { name: "Cancel" }), `Add-site cancel action at ${height}px`);
    await short.page.screenshot({ path: path.join(out, `desktop-ui-add-site-${height}.png`), fullPage: true });
  }
  await shortDialog.getByRole("button", { name: "Cancel" }).click();
  await short.page.getByRole("button", { name: "Applications" }).click();
  await short.page.getByRole("button", { name: /New group/ }).click();
  const groupDialog = short.page.getByRole("dialog", { name: "Create group" });
  for (const height of [720, 480]) {
    await short.page.setViewportSize({ width: 600, height });
    await assertInsideViewport(short.page, groupDialog.getByRole("button", { name: "Cancel" }), `Group cancel action at ${height}px`);
    await short.page.screenshot({ path: path.join(out, `desktop-ui-group-${height}.png`), fullPage: true });
  }
  await groupDialog.getByRole("button", { name: "Cancel" }).click();
  await short.page.getByRole("button", { name: "Permalock", exact: true }).click();
  await short.page.getByRole("button", { name: /Select .* for permanent blocking/ }).first().click();
  await short.page.getByRole("button", { name: "Block permanently" }).click();
  const permalockDialog = short.page.getByRole("dialog", { name: "Confirm permanent block" });
  for (const height of [720, 480]) {
    await short.page.setViewportSize({ width: 600, height });
    await assertInsideViewport(short.page, permalockDialog.getByRole("button", { name: "Cancel" }), `Permalock cancel action at ${height}px`);
    await short.page.screenshot({ path: path.join(out, `desktop-ui-permalock-${height}.png`), fullPage: true });
  }
  await permalockDialog.getByRole("button", { name: "Cancel" }).click();
  await short.context.close();
  assert.deepEqual(errors, [], "No browser runtime errors");
  const report = {
    fixtureData: true,
    routes: ["five main tabs", "auth loading/signed-out", "configuration error", "ErrorBoundary", "Nuke confirm/active/check-in", "blocker boundary/limit/permanent/Frog", "browser repair supported/unsupported"],
    interactions: ["timer start/pause", "manual work log", "app/site switch", "add website", "create/edit group", "Permalock confirmation cancel", "Strict until-time form", "Nuke confirmation cancel", "check-in send", "browser repair extension action"],
    widths, overflowChecks, shortDialogHeights: [720, 480], screenshots: true, runtimeErrors: errors,
  };
  await writeFile(path.join(out, "desktop-ui-results.json"), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await browser.close();
}
