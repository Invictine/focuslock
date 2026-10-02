import {chromium} from 'file:///C:/Users/aniru/AppData/Local/hermes/hermes-agent/node_modules/playwright-core/index.mjs';
import {fileURLToPath,pathToFileURL} from 'node:url';
import path from 'node:path';
const root=path.dirname(fileURLToPath(import.meta.url));
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
try{const page=await browser.newPage({viewport:{width:2400,height:1200}});await page.goto(pathToFileURL(path.join(root,'gallery.html')).href);await page.evaluate(()=>document.fonts.ready);await page.locator('#compare').click();await page.locator('#stage').screenshot({path:path.join(root,'screenshots','concepts.png')});console.log('Five-concept comparison captured.');}finally{await browser.close();}
