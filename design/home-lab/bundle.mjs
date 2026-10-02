// Produces a portable offline gallery without adding dependencies to the app.
import {build} from '../../extension/node_modules/esbuild/lib/main.js';
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.dirname(fileURLToPath(import.meta.url));
const {outputFiles}=await build({entryPoints:[path.join(root,'lab.js')],bundle:true,write:false,format:'iife',target:'es2020',minify:true});
const js=outputFiles[0].text.replaceAll('icons.svg#','#').replaceAll('</script','<\\/script');
let css=(await Promise.all(['lab.css','variants-b.css','variants-c.css'].map(f=>readFile(path.join(root,f),'utf8')))).join('\n');
for(const font of ['dm-sans','space-grotesk']){const data=(await readFile(path.join(root,'assets',font+'.woff2'))).toString('base64');css=css.replace(`assets/${font}.woff2`,`data:font/woff2;base64,${data}`);}
const sprite=(await readFile(path.join(root,'icons.svg'),'utf8')).replace('<svg ','<svg style="display:none" aria-hidden="true" ');
const licenses=(await Promise.all(['lucide-LICENSE','dm-sans-OFL.txt','space-grotesk-OFL.txt'].map(f=>readFile(path.join(root,'assets',f),'utf8')))).join('\n\n').replaceAll('--','- -');
const html=(await readFile(path.join(root,'index.html'),'utf8')).replace(/<link rel="stylesheet"[^>]+>/g,'').replace('</head>',`<!-- Embedded asset licenses\n${licenses}\n--><style>${css}</style></head>`).replace('<body>',`<body>${sprite}`).replace('<script type="module" src="lab.js"></script>',`<script>${js}</script>`);
await writeFile(path.join(root,'gallery.html'),html);
console.log('Offline gallery written: design/home-lab/gallery.html');
