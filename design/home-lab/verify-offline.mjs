import {chromium} from 'file:///C:/Users/aniru/AppData/Local/hermes/hermes-agent/node_modules/playwright-core/index.mjs';
import {fileURLToPath,pathToFileURL} from 'node:url';
import path from 'node:path';
import {writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const root=path.dirname(fileURLToPath(import.meta.url));
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const results={offline:true,checks:[],networkRequests:[],errors:[]};
try{
  const context=await browser.newContext({viewport:{width:1440,height:1100},offline:true});const page=await context.newPage();
  page.on('pageerror',e=>results.errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))results.networkRequests.push(r.url());});
  await page.goto(pathToFileURL(path.join(root,'gallery.html')).href);await page.evaluate(()=>document.fonts.ready);
  for(const id of ['halo','daybook','signal','mosaic','flow']){
    await page.locator(`[data-select="${id}"]`).click();const phone=page.locator(`[data-concept="${id}"] .phone`);assert.equal(await phone.count(),1);
    const icon=await phone.locator('.head-actions svg').first().evaluate(el=>({width:el.getBBox().width,height:el.getBBox().height}));assert(icon.width>0,'Embedded offline icon renders');
    results.checks.push(`${id}: offline homepage and icons render`);
  }
  await page.locator('[data-action="log"]').first().click();await page.locator('#work-title').fill('Offline sample');await page.locator('#work-minutes').fill('25');await page.locator('#log-form button[type="submit"]').click();
  assert((await page.locator('[data-view="history"]').innerText()).includes('Offline sample'));results.checks.push('Offline work log updates local demo history');
  await page.locator('#compare').click();assert.equal(await page.locator('#stage .phone').count(),5);results.checks.push('Offline compare renders all five');
  await page.locator('#compare').click();
  for(const width of [390,320]){
    await page.setViewportSize({width,height:1000});
    for(const id of ['halo','daybook','signal','mosaic','flow']){await page.locator(`[data-select="${id}"]`).click();const m=await page.evaluate(()=>({w:innerWidth,sw:document.documentElement.scrollWidth}));assert(m.sw<=m.w+1,`${id} gallery overflows at ${width}: ${JSON.stringify(m)}`);}
    results.checks.push(`Gallery adapts to ${width}px browser`);
  }
  assert.equal(results.errors.length,0);assert.equal(results.networkRequests.length,0);results.checks.push('No JavaScript errors or network dependencies');
}finally{await browser.close();await writeFile(path.join(root,'offline-results.json'),JSON.stringify(results,null,2)+'\n');}
console.log(`Offline gallery: ${results.checks.length} checks passed`);
