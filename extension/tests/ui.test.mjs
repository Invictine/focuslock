// Isolated UI verification: fixture auth/cloud data, real UI and local storage logic.
import { chromium } from 'playwright-core';
import { build } from 'esbuild';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
const root = path.resolve(import.meta.dirname, '../..');
const out = path.join(root, 'build/ui-verification');
await mkdir(out, { recursive: true });
const clerkStub = `export function createClerkClient(){return {loaded:true, user:{firstName:'UI',fullName:'UI review',primaryEmailAddress:{emailAddress:'review@example.test'}},session:{id:'fixture'},load:async()=>{},addListener:()=>()=>{},mountSignIn:()=>{},unmountSignIn:()=>{},signOut:async()=>{}}}`;
const bundled = {};
for (const entry of ['popup/popup.js', 'options/options-auth.js']) {
  const result = await build({entryPoints:[path.join(root,'extension',entry)],bundle:true,write:false,format:'iife',define:{'process.env.CLERK_PUBLISHABLE_KEY':'"fixture"','process.env.CLERK_SYNC_HOST':'"https://accounts.example.test"','process.env.CLERK_SIGN_IN_URL':'"https://accounts.example.test/sign-in"'},plugins:[{name:'isolated-auth',setup(b){b.onResolve({filter:/^@clerk/},()=>({path:'clerk',namespace:'fixture'}));b.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:clerkStub,loader:'js'}));}}]});
  bundled['/extension/dist/'+path.basename(entry)] = result.outputFiles[0].text;
}
const browser = await chromium.launch({headless:true});
const context = await browser.newContext({viewport:{width:1280,height:900},colorScheme:'light'});
await context.addInitScript(() => {
  window.__createdTabs = [];
  window.__messages = [];
  window.__groupVersion = 1;
  window.__groupsSaveError = '';
  window.__groups = [{_id:'backend-id',groupId:'media',name:'Media',category:'Entertainment',members:[{targetKind:'app',targetKey:'com.google.youtube',targetLabel:'YouTube'},{targetKind:'website',targetKey:'youtube.com',targetLabel:'youtube.com'}],dailyLimitMinutes:60,limitEnabled:true,updatedAt:1}];
  window.__storageListeners = [];
  window.chrome = {
    runtime: {
      getURL: p => new URL('/extension/' + p, location.origin).href,
      sendMessage: async message => {
        window.__messages.push(message);
        if (message?.type === 'getDashboard') return { signedIn: true, dashboard: {
          state: { totalWorkSecondsToday: 3600, creditBalanceSeconds: 900, totalScrollSecondsToday: 300 }, sessions: [], records: [],
          apps: [
            { appName: 'YouTube', packageName: 'com.google.youtube', isBlocked: true },
            { appName: 'Reddit', packageName: 'com.reddit.frontpage', isBlocked: true },
          ],
          sites: [{ domain: 'youtube.com', isBlocked: true }, { domain: 'reddit.com', isBlocked: true }],
          groups: window.__groups, groupsUpdatedAt: window.__groupVersion,
        } };
        if (message?.type === 'focusGroupsSave') {
          if (window.__groupsSaveError) return { ok: false, error: window.__groupsSaveError };
          window.__groups = message.groups;
          window.__groupVersion = message.updatedAt;
          return { ok: true };
        }
        return { ok: true, summary: { totalTrackedSeconds: 5120 }, devices: [
          { platform: 'browser', name: 'Chrome extension', trackingStatus: 'active', lastSeen: Date.now() },
          { platform: 'android', name: 'Android app', trackingStatus: 'active', lastSeen: Date.now() },
        ], lastSyncAt: Date.now() };
      },
      openOptionsPage: async () => {},
    },
    tabs: {
      query: async () => [{ url: sessionStorage.getItem('activeUrl') || 'https://example.org/article' }],
      create: async options => window.__createdTabs.push(options?.url || ''),
      update: async options => window.__createdTabs.push(options?.url || ''),
    },
    storage: { onChanged: { addListener: listener => window.__storageListeners.push(listener) }, local: {
      get: async key => ({ [key]: JSON.parse(localStorage.getItem(key) || 'null') }),
      set: async values => { const changes = {}; for (const [key, value] of Object.entries(values)) { const oldValue = JSON.parse(localStorage.getItem(key) || 'null'); localStorage.setItem(key, JSON.stringify(value)); changes[key] = { oldValue, newValue: value }; } for (const listener of window.__storageListeners) listener(changes, 'local'); },
    } },
  };
});
await context.route('**/*', async route => {
  const url = new URL(route.request().url());
  if(url.hostname !== 'focuslock.test') return route.abort();
  const source = bundled[url.pathname];
  if(source) return route.fulfill({contentType:'text/javascript',body:source});
  const file = path.join(root,...url.pathname.split('/').filter(Boolean));
  const types = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'};
  try { return route.fulfill({contentType:types[path.extname(file)]||'text/plain',body:await readFile(file)}); }
  catch { return route.fulfill({status:404,body:'Not found'}); }
});
const page = await context.newPage();
const errors = [];
page.on('pageerror', error=>errors.push(error.message));
await page.goto('http://focuslock.test/extension/options/options.html');
await page.locator('#lists .card').first().waitFor({state:'attached'});
const nav = page.locator('nav button[data-tab]');
const tabs = await nav.evaluateAll(items=>items.map(item=>item.dataset.tab));
assert.deepEqual(tabs,['stats','blocks','permalock','settings','account'],'Five primary destinations');
assert.equal(await page.locator('#tab-stats').isVisible(),true,'Default focus view');
assert.equal(await page.locator('nav button[data-tab="stats"]').getAttribute('aria-current'),'page','Focus is active by default');
for(const tab of tabs){
  await page.locator(`nav button[data-tab="${tab}"]`).click();
  assert.equal(await page.locator(`#tab-${tab}`).isVisible(),true,`Navigation ${tab}`);
  assert.equal(await page.locator(`nav button[data-tab="${tab}"]`).getAttribute('aria-current'),'page',`Active nav ${tab}`);
}
await page.locator('nav button[data-tab="stats"]').click();
assert.equal(await page.locator('#focusViews').isVisible(),true,'Focus sub-navigation visible');
assert.equal(await page.locator('#focusViews button[data-tab="stats"]').evaluate(el=>el.classList.contains('on')),true,'Overview selected by default');
await page.locator('#focusViews button[data-tab="sched"]').click();
assert.equal(await page.locator('#tab-sched').isVisible(),true,'Schedule view');
assert.equal(await page.locator('nav button[data-tab="stats"]').getAttribute('aria-current'),'page','Focus remains active under schedule');
assert.equal(await page.locator('#focusViews button[data-tab="sched"]').evaluate(el=>el.classList.contains('on')),true,'Schedule selected');
await page.locator('nav button[data-tab="blocks"]').click();
assert.equal(await page.locator('#groupCount').textContent(), '1', 'Synced target group count');
await page.locator('#boundaryTabs button[data-surface="groups"]').click();
assert.equal(await page.locator('#surface-groups').isVisible(), true, 'Target groups surface is reachable');
assert.match(await page.locator('#groupList').textContent(), /YouTube/);
assert.match(await page.locator('#groupList').textContent(), /youtube\.com/);
const strictGroupMinutes = page.locator('#groupList [data-group-id="media"] [data-group-limit-min]');
await strictGroupMinutes.fill('77');
await page.evaluate(async () => {
  const state = JSON.parse(localStorage.getItem('focuslock.v1'));
  state.strictMode = true;
  state.strictEndsAt = Date.now() + 2000;
  await chrome.storage.local.set({ 'focuslock.v1': state });
});
await page.waitForFunction(() => document.querySelector('#groupList [data-group-id="media"] [data-group-save]')?.disabled === true);
assert.equal(await page.locator('#groupList [data-group-id="media"] [data-group-remove]').isDisabled(), true, 'Strict mode disables group removal');
assert.equal(await page.locator('#groupList [data-group-id="media"] [data-group-limit-enabled]').isDisabled(), true, 'Strict mode disables group limit checkbox');
assert.equal(await strictGroupMinutes.isDisabled(), true, 'Strict mode disables group minutes');
await page.locator('#boundaryTabs button[data-surface="sites"]').click();
assert.equal(await page.locator('#siteRows [data-del]').first().isDisabled(), true, 'Strict mode disables shared site editing');
for (const id of ['presetSocial', 'presetVideo', 'presetUnblock', 'newList']) assert.equal(await page.locator('#' + id).isDisabled(), true, `Strict mode disables ${id}`);
await page.evaluate(async () => {
  const prefs = JSON.parse(localStorage.getItem('focuslock.focus.prefs.v1') || '{}');
  await chrome.storage.local.set({ 'focuslock.focus.prefs.v1': prefs });
});
assert.equal(await page.locator('#groupList [data-group-id="media"] [data-group-limit-min]').inputValue(), '77', 'Stats-only storage update preserves unsaved group input');
await page.waitForFunction(() => document.querySelector('#groupList [data-group-id="media"] [data-group-save]')?.disabled === false, null, { timeout: 6000 });
assert.equal(await page.locator('#groupList [data-group-id="media"] [data-group-save]').isDisabled(), false, 'Strict expiry unlocks group save');
assert.equal(await page.locator('#groupList [data-group-id="media"] [data-group-limit-min]').isDisabled(), false, 'Strict expiry unlocks group minutes');
assert.equal(await page.locator('#groupList [data-group-id="media"] [data-group-limit-min]').inputValue(), '77', 'Strict expiry preserves unsaved group input');
assert.equal(await page.locator('#siteRows [data-del]').first().isDisabled(), false, 'Strict expiry unlocks shared site editing');
await page.locator('#boundaryTabs button[data-surface="groups"]').click();
await page.locator('#createGroup').click();
await page.locator('#createGroupModal').waitFor({state:'visible'});
assert.equal(await page.locator('#groupTargetOptions [data-group-member][data-key="com.google.youtube"]').isDisabled(), true,
  'Already grouped app targets cannot be selected into a second group');
assert.equal(await page.locator('#groupTargetOptions [data-group-member][data-key="youtube.com"]').isDisabled(), true,
  'Already grouped website targets cannot be selected into a second group');
await page.locator('#groupTargetOptions [data-group-member][data-key="com.reddit.frontpage"]').check();
await page.locator('#groupTargetOptions [data-group-member][data-key="reddit.com"]').check();
await page.locator('#groupName').fill('Reddit set');
await page.locator('#groupLimitEnabled').check();
await page.locator('#groupLimitMinutes').fill('45');
await page.evaluate(() => { window.__groupVersion += 1; });
await page.locator('#createGroupSave').click();
await page.waitForFunction(() => /Groups changed while this form was open/i.test(document.getElementById('createGroupError').textContent));
assert.equal(await page.evaluate(() => window.__messages.filter(message => message.type === 'focusGroupsSave').length), 0,
  'A stale group editor cannot overwrite a newer collection');
await page.locator('#createGroupCancel').click();
await page.locator('#createGroup').click();
await page.locator('#createGroupModal').waitFor({state:'visible'});
await page.locator('#groupTargetOptions [data-group-member][data-key="com.reddit.frontpage"]').check();
await page.locator('#groupTargetOptions [data-group-member][data-key="reddit.com"]').check();
await page.locator('#groupName').fill('Reddit set');
await page.locator('#groupLimitEnabled').check();
await page.locator('#groupLimitMinutes').fill('45');
await page.evaluate(() => { window.__groupsSaveError = 'The server rejected this group update.'; });
await page.locator('#createGroupSave').click();
await page.waitForFunction(() => /server rejected this group update/i.test(document.getElementById('createGroupError').textContent));
assert.equal(await page.evaluate(() => window.__groups.length), 1, 'Rejected group saves leave the shared collection unchanged');
await page.evaluate(() => { window.__groupsSaveError = ''; });
await page.locator('#createGroupSave').click();
await page.locator('#createGroupModal').waitFor({state:'hidden'});
const createdGroups = await page.evaluate(() => window.__messages.filter(message => message.type === 'focusGroupsSave').at(-1));
assert.equal(createdGroups.groups.length, 2, 'Create appends a group while keeping existing groups');
assert.deepEqual(createdGroups.groups[0].members.map(member => member.targetKey), ['com.google.youtube', 'youtube.com'],
  'Creation preserves the existing group and its target ownership');
assert.deepEqual(createdGroups.groups[1].members.map(member => member.targetKey), ['com.reddit.frontpage', 'reddit.com'],
  'New group combines selected app and website targets');
assert.equal(createdGroups.groups[1].dailyLimitMinutes, 45, 'New group stores its optional daily cap');
assert.equal(createdGroups.groups[1].limitEnabled, true);
assert.ok(createdGroups.updatedAt > 1, 'Group save advances the collection version');

const mediaCard = page.locator('#groupList [data-group-id="media"]');
await mediaCard.locator('[data-group-limit-min]').fill('90');
const savesBeforeEdit = await page.evaluate(() => window.__messages.filter(message => message.type === 'focusGroupsSave').length);
await mediaCard.locator('[data-group-save]').click();
await page.waitForFunction(before => window.__messages.filter(message => message.type === 'focusGroupsSave').length > before, savesBeforeEdit);
assert.equal(await page.evaluate(() => window.__messages.filter(message => message.type === 'focusGroupsSave').at(-1).groups[0].dailyLimitMinutes), 90,
  'Group limit edit submits app and website group settings');
assert.equal(await page.evaluate(() => '_id' in window.__messages.filter(message => message.type === 'focusGroupsSave').at(-1).groups[0]), false,
  'Database identity fields are not sent back when saving groups');
assert.equal(await page.evaluate(() => 'updatedAt' in window.__messages.filter(message => message.type === 'focusGroupsSave').at(-1).groups[0]), false,
  'Collection timestamp is sent separately from group values');
page.once('dialog', dialog => dialog.accept());
const savesBeforeRemove = await page.evaluate(() => window.__messages.filter(message => message.type === 'focusGroupsSave').length);
await page.locator('#groupList [data-group-id="media"] [data-group-remove]').click();
await page.waitForFunction(before => window.__messages.filter(message => message.type === 'focusGroupsSave').length > before, savesBeforeRemove);
const remainingGroups = await page.evaluate(() => window.__messages.filter(message => message.type === 'focusGroupsSave').at(-1).groups);
assert.equal(remainingGroups.length, 1, 'Removing a group preserves unrelated groups');
assert.equal(remainingGroups[0].name, 'Reddit set');
await page.locator('#boundaryTabs button[data-surface="sites"]').click();
const before = await page.locator('#lists .card').count();
await page.locator('details.advanced').evaluate(el=>el.open=true);
await page.locator('#newList').click();
await page.waitForFunction(count=>document.querySelectorAll('#lists .card').length===count, before+1);
const added = page.locator('#lists .card').last();
await added.locator('details.list-editor').evaluate(el => { el.open = true; });
await added.locator('[data-f="name"]').fill('UI verification list');
await added.locator('[data-f="sites"]').fill('example.org');
await added.locator('[data-a="save"]').click();
await page.reload();
await page.locator('nav button[data-tab="blocks"]').click();
await page.locator('details.advanced').evaluate(el=>el.open=true);
await page.locator('#lists .card').first().waitFor();
await page.locator('#lists .card').last().locator('details.list-editor').evaluate(el => { el.open = true; });
assert.equal(await page.locator('#lists .card').last().locator('[data-f="name"]').inputValue(),'UI verification list');
await page.screenshot({path:path.join(out,'extension-boundaries.png'),fullPage:true});
for (const width of [1280,840,640,390]) {
  await page.setViewportSize({width,height:900});
  for (const tab of [...tabs, 'sched']) {
    if (tab === 'sched') await page.locator('nav button[data-tab="stats"]').click();
    await page.locator(`${tab === 'sched' ? '#focusViews' : 'nav'} button[data-tab="${tab}"]`).click();
    const overflow = await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth+1);
    assert.equal(overflow,false,`No horizontal overflow at ${width}px on ${tab}`);
  }
}
await page.setViewportSize({width:1280,height:900});
await page.goto('http://focuslock.test/extension/options/options.html?account=profile');
assert.equal(await page.locator('#tab-account').isVisible(),true,'Account deep link');
assert.equal(await page.locator('nav button[data-tab="account"]').getAttribute('aria-current'),'page','Account active nav');
await page.screenshot({path:path.join(out,'extension-account.png'),fullPage:true});
await page.goto('http://focuslock.test/extension/options/options.html?tab=settings');
assert.equal(await page.locator('#tab-settings').isVisible(),true,'Settings deep link');
assert.equal(await page.locator('nav button[data-tab="settings"]').getAttribute('aria-current'),'page','Settings active nav');
await page.locator('nav button[data-tab="stats"]').click();
await page.screenshot({path:path.join(out,'extension-focus.png'),fullPage:true});
await page.locator('#workTitle').fill('UI work credit check');
await page.locator('#workMinutes').fill('25');
assert.equal(await page.locator('#workPreview').textContent(), '≈ +11 min leisure', 'Work preview matches Android ratio-plus-bonus credit');
await page.locator('#workLog').click();
await page.waitForFunction(() => window.__messages.some(message => message.type === 'addWorkRecord'));
assert.equal(await page.evaluate(() => window.__messages.find(message => message.type === 'addWorkRecord').record.earnedMinutesCredited), 11,
  'Logged work uses the same credit calculation as Android');
assert.equal(await page.evaluate(() => window.__messages.find(message => message.type === 'addWorkRecord').record.source), 'manual-task',
  'Titled work entries are marked as manual tasks for the shared bank');
await page.goto('http://focuslock.test/extension/popup/popup.html');
await page.locator('#block-site').waitFor();
assert.equal(await page.locator('#block-site').isEnabled(),true);
await page.locator('#settings').click();
assert.match((await page.evaluate(()=>window.__createdTabs.at(-1)))||'',/options\.html\?tab=settings$/,'Popup settings link');
await page.locator('#account').click();
assert.match((await page.evaluate(()=>window.__createdTabs.at(-1)))||'',/options\.html\?account=profile$/,'Popup account link');
await page.setViewportSize({width:396,height:670});
await page.screenshot({path:path.join(out,'extension-popup.png'),fullPage:true});
const colors = await page.evaluate(()=>({background:getComputedStyle(document.body).backgroundColor,text:getComputedStyle(document.body).color}));
assert.equal(colors.background,'rgb(13, 13, 15)','Dark theme even with light OS preference');
await page.evaluate(()=>sessionStorage.setItem('activeUrl','chrome://extensions'));
await page.reload();
await page.locator('#block-site').waitFor();
assert.equal(await page.locator('#block-site').isDisabled(),true,'Internal pages cannot be blocked');
assert.deepEqual(errors,[],'No browser runtime errors');
const report = {fixtureData:true,tabs,widths:[1280,840,640,390],listSavePersists:true,accountDeepLink:true,internalPageGuard:true,colors,runtimeErrors:errors};
await writeFile(path.join(out,'extension-results.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));
await browser.close();
