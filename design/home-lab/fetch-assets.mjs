// One-time asset preparation; the completed lab has no runtime CDN dependencies.
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.dirname(fileURLToPath(import.meta.url));
await mkdir(path.join(root,'assets'),{recursive:true});
const icons={timer:'timer',plus:'plus',arrow:'arrow-right',check:'check',settings:'settings',shield:'shield',lock:'lock',home:'house',chart:'chart-no-axes-column-increasing',list:'list-todo',user:'user',bolt:'zap',refresh:'refresh-cw',close:'x',chevron:'chevron-right',info:'info',phone:'smartphone',target:'target',leaf:'leaf'};
async function fetchText(url){const r=await fetch(url);if(!r.ok)throw new Error(`${r.status}: ${url}`);return r.text();}
const symbols=await Promise.all(Object.entries(icons).map(async([id,name])=>{const svg=await fetchText(`https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/${name}.svg`);return `<symbol id="${id}" viewBox="0 0 24 24">${svg.replace(/^[\s\S]*?<svg[^>]*>/,'').replace(/<\/svg>[\s\S]*$/,'')}</symbol>`;}));
await writeFile(path.join(root,'icons.svg'),`<svg xmlns="http://www.w3.org/2000/svg">${symbols.join('')}</svg>`);
await writeFile(path.join(root,'assets','lucide-LICENSE'),await fetchText('https://raw.githubusercontent.com/lucide-icons/lucide/main/LICENSE'));
for(const [family,file,weight,ofl] of [['DM Sans','dm-sans','100..900','dmsans'],['Space Grotesk','space-grotesk','300..700','spacegrotesk']]){
  const response=await fetch(`https://fonts.googleapis.com/css2?family=${encodeURIComponent(family)}:wght@${weight}&display=swap`,{headers:{'User-Agent':'Mozilla/5.0 Chrome/128.0.0.0 Safari/537.36'}});if(!response.ok)throw new Error('Font CSS unavailable');
  const css=await response.text();const urls=[...css.matchAll(/url\((https:[^)]+)\)/g)].map(x=>x[1]);const r=await fetch(urls.at(-1));if(!r.ok)throw new Error('Font unavailable');await writeFile(path.join(root,'assets',file+'.woff2'),Buffer.from(await r.arrayBuffer()));await writeFile(path.join(root,'assets',file+'-OFL.txt'),await fetchText(`https://raw.githubusercontent.com/google/fonts/main/ofl/${ofl}/OFL.txt`));
}
console.log('Local Lucide icons and open-source fonts prepared.');
