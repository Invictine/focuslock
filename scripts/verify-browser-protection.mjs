import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { createServer as createTcpServer } from 'node:net';
import { once } from 'node:events';
import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { chromium } from '../extension/node_modules/playwright-core/index.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const HOST_NAME = 'com.focuslock.browser';
const EXTENSION_ID = 'fkkpmoiageeieaoplphafmhjkkdadcnf';
const REGISTRY_BASES = [
  'Software\\Google\\Chrome\\NativeMessagingHosts',
  'Software\\Microsoft\\Edge\\NativeMessagingHosts',
  'Software\\BraveSoftware\\Brave-Browser\\NativeMessagingHosts',
  'Software\\Vivaldi\\NativeMessagingHosts',
  'Software\\Opera Software\\Opera Stable\\NativeMessagingHosts',
  'Software\\Opera Software\\Opera GX Stable\\NativeMessagingHosts',
];
const REGISTRY_VIEWS = ['/reg:32', '/reg:64'];
const RUN_ID = `${new Date().toISOString().replace(/[:.]/g, '-')}-${Math.random().toString(16).slice(2, 10)}`;

function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) throw new Error(`Unexpected argument: ${key}`);
    if (key === '--help') values.help = true;
    else if (key === '--restore') values.restore = argv[++index];
    else if (['--exe', '--identifier', '--out'].includes(key)) values[key.slice(2)] = argv[++index];
    else throw new Error(`Unknown argument: ${key}`);
  }
  return values;
}

function usage() {
  return [
    'Usage:',
    '  node scripts/verify-browser-protection.mjs --exe <absolute FocusLockQA.exe> --identifier <com.focuslock.browserqa.random> [--out <directory>]',
    '  node scripts/verify-browser-protection.mjs --restore <registry-backup.json>',
  ].join('\n');
}

function ensureWindows() {
  if (process.platform !== 'win32') throw new Error('This live acceptance runner requires Windows.');
}

function validateArgs(args) {
  if (args.restore) return;
  if (!args.exe || !path.isAbsolute(args.exe)) throw new Error('--exe must be an absolute QA executable path.');
  if (!args.identifier || !/^com\.focuslock\.browserqa\.[a-z0-9.-]+$/i.test(args.identifier)) {
    throw new Error('--identifier must start with com.focuslock.browserqa. and contain only identifier characters.');
  }
}

function registryKey(base) {
  return `HKCU\\${base}\\${HOST_NAME}`;
}

function reg(args) {
  const result = spawnSync('reg.exe', args, { encoding: 'utf8', windowsHide: true });
  if (result.error) throw new Error(`Could not run reg.exe ${args[0]}: ${result.error.message}`);
  return { status: result.status ?? 1, stdout: result.stdout || '', stderr: result.stderr || '' };
}

function parseDefaultValue(output) {
  const line = String(output).split(/\r?\n/).find(value => /^\s*\(Default\)\s+REG_\w+\s+/i.test(value));
  if (!line) return { present: false, value: null };
  const match = line.match(/^\s*\(Default\)\s+(REG_\w+)\s+(.*)$/i);
  if (!match) return { present: false, value: null };
  if (match[1].toUpperCase() !== 'REG_SZ') {
    throw new Error(`The existing native-host default at this exact key has type ${match[1]}, not REG_SZ; refusing to alter it.`);
  }
  return { present: true, value: match[2].trim() };
}

function captureRegistryEntry(base, view) {
  const key = registryKey(base);
  const keyQuery = reg(['query', key, view]);
  const defaultQuery = reg(['query', key, '/ve', view]);
  const defaultValue = parseDefaultValue(defaultQuery.stdout);
  return {
    key,
    base,
    view,
    keyPresent: keyQuery.status === 0,
    defaultValuePresent: defaultValue.present,
    defaultValue: defaultValue.value,
  };
}

function allRegistryEntries() {
  return REGISTRY_BASES.flatMap(base => REGISTRY_VIEWS.map(view => captureRegistryEntry(base, view)));
}

async function saveRegistryBackup(file, backup) {
  await fs.writeFile(file, `${JSON.stringify(backup, null, 2)}\n`, { mode: 0o600 });
}

function deleteDefaultValue(entry) {
  const result = reg(['delete', entry.key, '/ve', '/f', entry.view]);
  return result.status === 0;
}

function addDefaultValue(entry, value) {
  const result = reg(['add', entry.key, '/ve', '/t', 'REG_SZ', '/d', value, '/f', entry.view]);
  if (result.status !== 0) throw new Error(`Could not restore ${entry.key} (${entry.view}): ${result.stderr || result.stdout}`);
}

function removeEmptyCreatedChild(entry) {
  const result = reg(['query', entry.key, entry.view]);
  if (result.status !== 0) return;
  const lines = result.stdout.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  const hasValuesOrSubkeys = lines.slice(1).some(line => /^\(Default\)|^[^\s].*REG_\w+\s|^HKEY_/i.test(line));
  if (!hasValuesOrSubkeys) {
    const deleted = reg(['delete', entry.key, '/f', entry.view]);
    if (deleted.status !== 0) throw new Error(`Could not remove the QA-created empty key ${entry.key} (${entry.view}).`);
  }
}

function validateBackupEntry(entry) {
  return REGISTRY_BASES.includes(entry.base) && REGISTRY_VIEWS.includes(entry.view) &&
    entry.key === registryKey(entry.base);
}

function restoreRegistryBackup(backup) {
  if (!backup || !Array.isArray(backup.entries) || backup.entries.length !== REGISTRY_BASES.length * REGISTRY_VIEWS.length) {
    throw new Error('Registry backup is incomplete or malformed; refusing to restore it.');
  }
  const seen = new Set();
  for (const saved of backup.entries) {
    if (!validateBackupEntry(saved)) throw new Error('Registry backup contains a key outside the FocusLock native-host allowlist.');
    const identity = `${saved.key}|${saved.view}`;
    if (seen.has(identity)) throw new Error(`Duplicate registry backup entry: ${identity}`);
    seen.add(identity);
  }

  const errors = [];
  for (const saved of backup.entries) {
    try {
      deleteDefaultValue(saved);
      if (saved.defaultValuePresent === true) {
        addDefaultValue(saved, saved.defaultValue);
      } else if (!saved.keyPresent) {
        removeEmptyCreatedChild(saved);
      }
    } catch (error) {
      errors.push(error.message);
    }
  }
  if (errors.length) throw new Error(`Some native-host registry values could not be restored:\n${errors.join('\n')}`);
}

async function unusedPort() {
  const server = createTcpServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return port;
}

async function poll(label, check, { timeoutMs, intervalMs = 500, progressMs = 10_000, log } = {}) {
  const startedAt = Date.now();
  let lastProgressAt = startedAt;
  let lastValue;
  while (Date.now() - startedAt < timeoutMs) {
    if (interrupted) throw new Error(`Interrupted during ${label}.`);
    try {
      lastValue = await check();
      if (lastValue && lastValue.done) return lastValue.value;
    } catch (error) {
      lastValue = { error: error.message };
    }
    if (Date.now() - lastProgressAt >= progressMs) {
      log(`${label} still pending`, { elapsedSeconds: Math.floor((Date.now() - startedAt) / 1000), latest: lastValue });
      lastProgressAt = Date.now();
    }
    await new Promise(resolve => setTimeout(resolve, intervalMs));
  }
  throw new Error(`Timed out after ${Math.ceil(timeoutMs / 1000)}s waiting for ${label}. Last observation: ${JSON.stringify(lastValue)}`);
}

let interrupted = false;
process.once('SIGINT', () => { interrupted = true; });
process.once('SIGTERM', () => { interrupted = true; });

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  console.log(usage());
  process.exit(0);
}
ensureWindows();
validateArgs(args);

if (args.restore) {
  const backup = JSON.parse(await fs.readFile(path.resolve(args.restore), 'utf8'));
  restoreRegistryBackup(backup);
  console.log(`Restored only the FocusLock native-host default entries recorded in ${path.resolve(args.restore)}`);
  process.exit(0);
}

const executable = path.resolve(args.exe);
const outputDir = path.resolve(args.out || path.join(ROOT, 'build', 'browser-protection'));
const extensionRootCandidates = [
  path.join(ROOT, 'build', 'extension-unpacked'),
  path.join(ROOT, 'extension'),
];
let extensionRoot = null;
for (const candidate of extensionRootCandidates) {
  try {
    await fs.access(path.join(candidate, 'manifest.json'));
    await fs.access(path.join(candidate, 'dist', 'cloud-sync.js'));
    extensionRoot = candidate;
    break;
  } catch { /* use next supported unpacked location */ }
}
await fs.access(executable);
if (!extensionRoot) throw new Error('Build the extension first; neither build/extension-unpacked nor extension contains a runnable manifest and dist/cloud-sync.js.');
await fs.mkdir(outputDir, { recursive: true });

const backupFile = path.join(outputDir, `registry-backup-${RUN_ID}.json`);
const reportFile = path.join(outputDir, `browser-protection-report-${RUN_ID}.json`);
const progressFile = path.join(outputDir, `browser-protection-progress-${RUN_ID}.log`);
const report = {
  runId: RUN_ID,
  startedAt: new Date().toISOString(),
  executable,
  identifier: args.identifier,
  outputDir,
  extensionRoot,
  registryBackup: backupFile,
  phases: [],
  checks: {},
  status: 'running',
};
let registryBackup = null;
let qaChild = null;
let cdpBrowser = null;
let chromiumContext = null;
let secondaryContext = null;
let localServer = null;
let appMainPage = null;
let testPage = null;
let testUrl = null;
let qaPid = null;
let debugPort = null;
let qaIdentityVerified = false;

async function log(message, details = {}) {
  const entry = { at: new Date().toISOString(), message, ...details };
  console.log(`[browser-protection] ${message}${Object.keys(details).length ? ` ${JSON.stringify(details)}` : ''}`);
  await fs.appendFile(progressFile, `${JSON.stringify(entry)}\n`);
}

async function phase(name, details = {}) {
  const entry = { name, at: new Date().toISOString(), ...details };
  report.phases.push(entry);
  await log(`PHASE ${name}`, details);
}

function childIsRunning(child) {
  return Boolean(child && child.exitCode === null && child.signalCode === null);
}

async function fetchJson(url, timeoutMs = 1200) {
  const response = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response.json();
}

async function findAppPages(browser) {
  const pages = browser.contexts().flatMap(context => context.pages());
  const appPages = [];
  for (const page of pages) {
    try {
      const hasInternals = await page.evaluate(() => Boolean(window.__TAURI_INTERNALS__?.invoke));
      if (hasInternals) appPages.push(page);
    } catch { /* non-app CDP targets are expected */ }
  }
  return appPages;
}

async function tauriInvoke(page, command, commandArgs = {}) {
  return page.evaluate(async ({ name, payload }) => {
    const invoke = window.__TAURI_INTERNALS__?.invoke;
    if (typeof invoke !== 'function') throw new Error('Tauri invoke API is unavailable in this WebView2 page.');
    return invoke(name, payload);
  }, { name: command, payload: commandArgs });
}

async function trackerStatus() {
  return tauriInvoke(appMainPage, 'get_tracker_status');
}

async function repairState() {
  return tauriInvoke(appMainPage, 'get_browser_repair_state');
}

async function blockerState() {
  return tauriInvoke(appMainPage, 'get_blocker_state');
}

async function foregroundPid(pid) {
  const script = `
    Add-Type -TypeDefinition @'
    using System;
    using System.Runtime.InteropServices;
    public static class FocusLockQaForeground {
      [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
      [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
      [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
      [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
      [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr processId);
      [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
      [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint from, uint to, bool attach);
      [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    }
'@
    $target = Get-Process -Id ${Number(pid)} -ErrorAction SilentlyContinue
    if ($null -eq $target -or $target.MainWindowHandle -eq 0) { exit 3 }
    [void][FocusLockQaForeground]::ShowWindowAsync($target.MainWindowHandle, 9)
    [FocusLockQaForeground]::keybd_event(18, 0, 0, [UIntPtr]::Zero)
    [FocusLockQaForeground]::keybd_event(18, 0, 2, [UIntPtr]::Zero)
    $foregroundThread = [FocusLockQaForeground]::GetWindowThreadProcessId([FocusLockQaForeground]::GetForegroundWindow(), [IntPtr]::Zero)
    $currentThread = [FocusLockQaForeground]::GetCurrentThreadId()
    $attached = [FocusLockQaForeground]::AttachThreadInput($currentThread, $foregroundThread, $true)
    try {
      [void][FocusLockQaForeground]::BringWindowToTop($target.MainWindowHandle)
      [void][FocusLockQaForeground]::SetForegroundWindow($target.MainWindowHandle)
      if ([FocusLockQaForeground]::GetForegroundWindow() -ne $target.MainWindowHandle) { exit 4 }
    } finally { if ($attached) { [void][FocusLockQaForeground]::AttachThreadInput($currentThread, $foregroundThread, $false) } }
    exit 0
  `;
  const result = spawnSync('powershell.exe', [
    '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64'),
  ], { encoding: 'utf8', windowsHide: true, timeout: 5000 });
  return result.status === 0;
}

async function foregroundChrome(profileDir) {
  const encodedProfile = `'${profileDir.replace(/'/g, "''")}'`;
  const script = `
    Add-Type -TypeDefinition @'
    using System;
    using System.Runtime.InteropServices;
    public static class FocusLockQaForeground {
      [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
      [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
      [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
      [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
      [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr processId);
      [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
      [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint from, uint to, bool attach);
      [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    }
'@
    $profile = ${encodedProfile}
    $target = Get-CimInstance Win32_Process | Where-Object { $_.Name -ieq 'chrome.exe' -and $_.CommandLine -and $_.CommandLine.Contains($profile) } | ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue } | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    if ($null -eq $target -or $target.MainWindowHandle -eq 0) { exit 4 }
    [void][FocusLockQaForeground]::ShowWindowAsync($target.MainWindowHandle, 9)
    [FocusLockQaForeground]::keybd_event(18, 0, 0, [UIntPtr]::Zero)
    [FocusLockQaForeground]::keybd_event(18, 0, 2, [UIntPtr]::Zero)
    $foregroundThread = [FocusLockQaForeground]::GetWindowThreadProcessId([FocusLockQaForeground]::GetForegroundWindow(), [IntPtr]::Zero)
    $currentThread = [FocusLockQaForeground]::GetCurrentThreadId()
    $attached = [FocusLockQaForeground]::AttachThreadInput($currentThread, $foregroundThread, $true)
    try {
      [void][FocusLockQaForeground]::BringWindowToTop($target.MainWindowHandle)
      [void][FocusLockQaForeground]::SetForegroundWindow($target.MainWindowHandle)
      if ([FocusLockQaForeground]::GetForegroundWindow() -ne $target.MainWindowHandle) { exit 5 }
      Start-Sleep -Milliseconds 1500
    } finally { if ($attached) { [void][FocusLockQaForeground]::AttachThreadInput($currentThread, $foregroundThread, $false) } }
    exit 0
  `;
  const result = spawnSync('powershell.exe', [
    '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64'),
  ], { encoding: 'utf8', windowsHide: true, timeout: 7000 });
  return result.status === 0;
}

async function bringChromeToFront(page, profileDir) {
  await page.bringToFront();
  if (!await foregroundChrome(profileDir)) throw new Error('The disposable Chromium window could not take native foreground focus.');
}

async function waitForTracker(label, predicate, timeoutMs = 15_000) {
  return poll(label, async () => {
    const status = await trackerStatus();
    return predicate(status) ? { done: true, value: status } : { done: false, status };
  }, { timeoutMs, log });
}

async function waitForRepairState(label, predicate, timeoutMs = 15_000) {
  return poll(label, async () => {
    const state = await repairState();
    return predicate(state) ? { done: true, value: state } : { done: false, state };
  }, { timeoutMs, intervalMs: 500, log });
}

async function findRepairPage(browser) {
  const pages = await findAppPages(browser);
  return pages.find(page => page.url().includes('#/browser-repair') || page.url().includes('/browser-repair')) || null;
}

async function waitForRepairPage(timeoutMs = 15_000) {
  return poll('visible browser repair window', async () => {
    const page = await findRepairPage(cdpBrowser);
    if (!page) return { done: false, found: false };
    let inspection;
    try {
      inspection = await page.evaluate(() => ({
        visible: document.visibilityState === 'visible',
        title: document.title,
        width: window.innerWidth,
        height: window.innerHeight,
        text: String(document.body?.innerText || '').replace(/\s+/g, ' ').slice(0, 500),
        buttonBottom: document.querySelector('button')?.getBoundingClientRect().bottom ?? 0,
      }));
    } catch (error) {
      return { done: false, error: error.message };
    }
    return inspection.visible && inspection.width <= 720 && inspection.height <= 560 &&
      /extension/i.test(inspection.text) && /(enable|install)/i.test(inspection.text) && /\d+\s*(s|sec|second)/i.test(inspection.text)
      ? { done: true, value: { page, inspection } }
      : { done: false, inspection };
  }, { timeoutMs, intervalMs: 400, log });
}

function assertRepairState(state, label) {
  if (!state || typeof state !== 'object') throw new Error(`${label}: browser repair state is missing.`);
  for (const field of ['browser', 'appId', 'graceRemainingSeconds', 'reason']) {
    if (!(field in state)) throw new Error(`${label}: repair state is missing ${field}.`);
  }
  if (!/chrome/i.test(String(state.browser)) || state.reason !== 'extension_missing') {
    throw new Error(`${label}: unexpected repair target or reason: ${JSON.stringify(state)}`);
  }
  if (typeof state.appId !== 'string' || state.appId.length === 0) {
    throw new Error(`${label}: repair state appId is empty or not a string.`);
  }
  if (!Number.isFinite(state.graceRemainingSeconds)) {
    throw new Error(`${label}: repair countdown is not numeric: ${JSON.stringify(state)}`);
  }
  return state;
}

async function inspectChromeControls(page) {
  return page.evaluate(() => {
    const found = [];
    const visited = new Set();
    const visit = root => {
      if (!root || visited.has(root)) return;
      visited.add(root);
      for (const element of root.querySelectorAll('*')) {
        const id = element.id || '';
        const tag = element.localName || '';
        const role = element.getAttribute('role') || '';
        if (tag === 'cr-toggle' || tag === 'input' || role === 'switch' || role === 'checkbox') {
          const rect = element.getBoundingClientRect();
          if (rect.width === 0 || rect.height === 0 || getComputedStyle(element).visibility === 'hidden') continue;
          const parent = element.parentElement;
          const host = element.getRootNode().host;
          let selector = id ? `#${CSS.escape(id)}` : tag;
          let shadowHost = host;
          while (shadowHost) {
            selector = `${shadowHost.localName}${shadowHost.id ? `#${CSS.escape(shadowHost.id)}` : ''} ${selector}`;
            shadowHost = shadowHost.getRootNode().host;
          }
          found.push({
            id,
            tag,
            selector,
            hostId: host?.id || '',
            hostText: host?.localName === 'extensions-toggle-row' ? String(host.innerText || host.textContent || '').trim() : '',
            role,
            ariaLabel: element.getAttribute('aria-label') || '',
            ariaLabelledBy: element.getAttribute('aria-labelledby') || '',
            ariaChecked: element.getAttribute('aria-checked'),
            checked: typeof element.checked === 'boolean' ? element.checked : null,
            text: String(parent?.innerText || parent?.textContent || '').trim().replace(/\\s+/g, ' ').slice(0, 160),
          });
        }
        if (element.shadowRoot) visit(element.shadowRoot);
      }
    };
    visit(document);
    return { title: document.title, url: location.href, controls: found.slice(0, 250) };
  });
}

function semanticControl(inspection, expression, label) {
  const found = inspection.controls.filter(control => {
    const description = `${control.id} ${control.hostId} ${control.hostText} ${control.ariaLabel} ${control.ariaLabelledBy} ${control.text}`;
    return expression.test(description);
  });
  if (found.length !== 1) {
    throw new Error(`Could not identify one ${label} control from the inspected Chrome extension settings DOM; found ${found.length}. See the progress log.`);
  }
  return found[0];
}

function selectorFor(control) {
  if (control.selector) return control.selector;
  if (control.id) return `#${control.id.replace(/[^a-zA-Z0-9_-]/g, '\\$&')}`;
  throw new Error('Inspected Chrome settings control has no stable DOM id.');
}

async function readControlState(page, control) {
  const locator = page.locator(selectorFor(control)).filter({ visible: true });
  if (await locator.count() !== 1) throw new Error(`Inspected Chrome control #${control.id} is not uniquely addressable through the live page.`);
  return locator.evaluate(element => {
    const aria = element.getAttribute('aria-checked');
    if (aria !== null) return aria === 'true';
    if (typeof element.checked === 'boolean') return element.checked;
    return element.hasAttribute('checked');
  });
}

async function setExtensionToggle(page, desired, semantic) {
  await page.goto(semantic.source === 'developer mode' ? 'chrome://extensions/' : `chrome://extensions/?id=${EXTENSION_ID}`);
  await page.waitForTimeout(1500);
  const inspection = await inspectChromeControls(page);
  await log('Inspected Chrome extension settings controls', {
    title: inspection.title,
    url: inspection.url,
    controls: inspection.controls,
  });
  const control = semanticControl(inspection, semantic.test, semantic.source);
  const before = await readControlState(page, control);
  if (before !== desired) {
    const locator = page.locator(selectorFor(control)).filter({ visible: true });
    await locator.click({ timeout: 5000 });
    await page.waitForTimeout(1500);
  }
  const after = await readControlState(page, control);
  if (after !== desired) throw new Error(`Chrome ${semantic.source} toggle did not reach the requested state.`);
  return { id: control.id, before, after };
}

async function startLocalTestServer() {
  const nonce = RUN_ID.replace(/[^a-z0-9]/gi, '').slice(-12);
  localServer = createServer((request, response) => {
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    response.end(`<!doctype html><meta charset="utf-8"><title>FocusLock Browser Protection QA ${nonce}</title><main><h1>Browser protection acceptance</h1><p>Disposable local page for native extension health verification.</p></main>`);
  });
  localServer.listen(0, '127.0.0.1');
  await once(localServer, 'listening');
  testUrl = `http://127.0.0.1:${localServer.address().port}/qa-${nonce}`;
  return testUrl;
}

async function assertQaHostRegistered() {
  const entries = allRegistryEntries();
  const registered = entries.filter(entry => entry.defaultValue);
  if (registered.length !== entries.length) {
    throw new Error(`QA native host was not registered in all 12 expected registry views (${registered.length}/12).`);
  }
  const manifestPaths = [...new Set(registered.map(entry => entry.defaultValue))];
  const manifests = [];
  for (const manifestPath of manifestPaths) {
    const parsed = JSON.parse(await fs.readFile(manifestPath, 'utf8'));
    if (parsed.name !== HOST_NAME || path.resolve(parsed.path).toLowerCase() !== executable.toLowerCase()) {
      throw new Error(`Registry manifest ${manifestPath} does not point to the QA executable.`);
    }
    if (path.resolve(path.dirname(manifestPath)).toLowerCase() !== path.resolve(process.env.APPDATA, args.identifier).toLowerCase()) {
      throw new Error('The executable was not built with the requested disposable QA app identifier. No policy commands will be sent.');
    }
    manifests.push(manifestPath);
  }
  report.checks.nativeHost = { registryViews: registered.length, manifestPaths: manifests };
  qaIdentityVerified = true;
  return entries;
}

async function cleanup() {
  if (appMainPage && qaIdentityVerified) {
    try { await tauriInvoke(appMainPage, 'set_browser_protection_policy', { required: false, lockedUntilMs: 0 }); }
    catch (error) { await log('Could not clear the QA protection policy during cleanup', { error: error.message }); }
    try { await tauriInvoke(appMainPage, 'set_blocked_targets', { targets: { appIds: [], domains: [] }, reasons: {} }); }
    catch (error) { await log('Could not clear QA blocked targets during cleanup', { error: error.message }); }
  }
  if (secondaryContext) {
    try { await secondaryContext.close(); } catch (error) { await log('Secondary test profile close reported an error', { error: error.message }); }
    secondaryContext = null;
  }
  if (chromiumContext) {
    try { await chromiumContext.close(); } catch (error) { await log('Disposable Chromium profile close reported an error', { error: error.message }); }
    chromiumContext = null;
  }
  if (cdpBrowser) {
    try { await cdpBrowser.close(); } catch (error) { await log('WebView2 CDP close reported an error', { error: error.message }); }
    cdpBrowser = null;
  }
  if (qaChild && childIsRunning(qaChild)) {
    qaChild.kill();
    await Promise.race([once(qaChild, 'exit'), new Promise(resolve => setTimeout(resolve, 5000))]);
  }
  if (localServer) {
    await new Promise(resolve => localServer.close(() => resolve()));
    localServer = null;
  }
  if (registryBackup) {
    restoreRegistryBackup(registryBackup);
    report.checks.registryRestored = true;
    registryBackup.restoredAt = new Date().toISOString();
    await saveRegistryBackup(backupFile, registryBackup);
    await log('Restored only the 12 FocusLock native-host default registry entries.');
  }
}

let fatalError = null;
try {
  await phase('validate isolated QA inputs', { exe: executable, identifier: args.identifier, extensionRoot, out: outputDir });
  const exeStat = await fs.stat(executable);
  if (!exeStat.isFile()) throw new Error('--exe does not point to a file.');
  const identifierPath = path.join(process.env.APPDATA || '', args.identifier);
  report.checks.isolatedAppDataIdentifier = identifierPath;
  await fs.access(path.join(extensionRoot, 'src', 'desktop-bridge.js'));

  await phase('backup exact native host registry entries');
  registryBackup = { runId: RUN_ID, hostName: HOST_NAME, entries: allRegistryEntries() };
  await saveRegistryBackup(backupFile, registryBackup);
  await log('Saved recovery backup before starting the QA app', {
    file: backupFile,
    registryEntries: registryBackup.entries.length,
    existingDefaultValues: registryBackup.entries.filter(entry => entry.defaultValue !== null).length,
  });

  await phase('launch QA desktop and connect to WebView2');
  debugPort = await unusedPort();
  qaChild = spawn(executable, [], {
    env: { ...process.env, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${debugPort}` },
    stdio: 'ignore',
    windowsHide: true,
  });
  qaPid = qaChild.pid;
  qaChild.on('error', error => { void log('QA child process error', { error: error.message }); });
  const debugUrl = `http://127.0.0.1:${debugPort}`;
  await poll('QA WebView2 remote debugging endpoint', async () => {
    if (!childIsRunning(qaChild)) throw new Error(`QA executable exited with code ${qaChild.exitCode}.`);
    const version = await fetchJson(`${debugUrl}/json/version`);
    return version.Browser ? { done: true, value: version } : null;
  }, { timeoutMs: 15_000, intervalMs: 300, log });
  cdpBrowser = await chromium.connectOverCDP(debugUrl, { timeout: 15_000 });
  appMainPage = await poll('Tauri main WebView2 page', async () => {
    const pages = await findAppPages(cdpBrowser);
    const page = pages.find(candidate => !candidate.url().includes('#/blocked') && !candidate.url().includes('/blocked'));
    return page ? { done: true, value: page } : null;
  }, { timeoutMs: 15_000, intervalMs: 400, log });
  report.checks.webview2 = { connected: true, debugPort, qaPid };
  const registeredEntries = await assertQaHostRegistered();
  report.checks.nativeHost.registryViewCount = registeredEntries.length;

  await phase('initialize disposable QA policy');
  await tauriInvoke(appMainPage, 'set_browser_protection_policy', { required: false, lockedUntilMs: 0 });
  await tauriInvoke(appMainPage, 'set_blocked_targets', { targets: { appIds: [], domains: [] }, reasons: {} });
  await tauriInvoke(appMainPage, 'start_tracking');
  const status = await trackerStatus();
  if (!status.running) throw new Error('The native tracker did not start in the QA app.');

  await phase('launch isolated Chromium profile with the unpacked extension');
  const profileDir = path.join(outputDir, `qa-profile-primary-${RUN_ID}`);
  await fs.mkdir(profileDir, { recursive: true });
  const chromeExecutable = process.env.FOCUSLOCK_QA_CHROME;
  chromiumContext = await chromium.launchPersistentContext(profileDir, {
    ...(chromeExecutable ? { executablePath: path.resolve(chromeExecutable) } : { channel: 'chromium' }),
    headless: false,
    args: [
      `--disable-extensions-except=${extensionRoot}`,
      `--load-extension=${extensionRoot}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-sync',
      '--disable-background-networking',
    ],
    viewport: { width: 1280, height: 800 },
  });
  testPage = chromiumContext.pages()[0] || await chromiumContext.newPage();
  await testPage.goto('chrome://extensions/');
  await phase('enable extension and incognito access');
  report.checks.developerMode = await setExtensionToggle(testPage, true, { source: 'developer mode', test: /devMode/i });
  const enableState = await setExtensionToggle(testPage, true, { source: 'enable', test: /enable/i });
  const incognitoState = await setExtensionToggle(testPage, true, { source: 'incognito', test: /incognito/i });
  report.checks.extensionEnabled = enableState;
  report.checks.incognitoAllowed = incognitoState;

  await phase('verify healthy native heartbeat');
  // Changing incognito access reloads the extension. Its popup wakes the
  // background worker before testing the newly granted configuration.
  const extensionProbePage = await chromiumContext.newPage();
  await extensionProbePage.goto(`chrome-extension://${EXTENSION_ID}/popup/popup.html`);
  report.checks.nativeProbe = await extensionProbePage.evaluate(async () => {
    try {
      return { ack: await Promise.race([chrome.runtime.sendNativeMessage('com.focuslock.browser', {
        type: 'heartbeat', version: 1, allUrls: true, incognitoAllowed: true, windows: [],
      }), new Promise((_, reject) => setTimeout(() => reject(new Error('Native host response timed out')), 10000))]) };
    } catch(error) { return { error: error.message }; }
  });
  await log('Direct native messaging probe', report.checks.nativeProbe);
  await extensionProbePage.close();
  if (report.checks.nativeProbe.ack?.ok !== true) throw new Error(`Native host handshake failed: ${JSON.stringify(report.checks.nativeProbe)}`);
  await startLocalTestServer();
  const paused = await tauriInvoke(appMainPage, 'stop_tracking');
  if (paused.running) throw new Error('Neutral QA tracker did not pause before activation testing.');
  await tauriInvoke(appMainPage, 'set_browser_protection_policy', { required: true, lockedUntilMs: 0 });
  if (!(await trackerStatus()).running) throw new Error('Browser protection did not resume the previously paused tracker.');
  report.checks.activationResumesPausedTracker = true;
  await testPage.goto(testUrl);
  await bringChromeToFront(testPage, profileDir);
  let healthyStatus = await waitForTracker('healthy Chrome browser lease', value =>
    value.browserProtectionRequired && value.browserProtection?.healthy === true && /chrome/i.test(value.browserProtection?.browser || ''), 30_000);
  report.checks.healthyNativeHeartbeat = healthyStatus.browserProtection;
  if (!healthyStatus.browserProtection.healthy) throw new Error('The extension did not establish a healthy native heartbeat.');

  await phase('verify policy never blocks protected-domain pages or extension settings');
  await tauriInvoke(appMainPage, 'set_blocked_targets', {
    targets: { appIds: [], domains: ['localhost', '127.0.0.1'] },
    reasons: { domains: 'qa-domain-policy' },
  });
  await testPage.goto(testUrl);
  await bringChromeToFront(testPage, profileDir);
  await waitForTracker('extension owns configured local-domain blocking', value => value.current?.windowTitle?.includes('FocusLock Browser Protection QA'), 15_000);
  let domainBlocker = await blockerState();
  let domainStatus = await trackerStatus();
  if (domainBlocker.visible || domainStatus.current?.blocked === true) {
    throw new Error(`The desktop blocked a domain page that belongs to the extension: ${JSON.stringify({ domainBlocker, current: domainStatus.current })}`);
  }
  report.checks.extensionOwnsDomainBlocking = { domains: ['localhost', '127.0.0.1'], blocker: domainBlocker, current: domainStatus.current };
  await tauriInvoke(appMainPage, 'set_blocked_targets', { targets: { appIds: [], domains: [] }, reasons: {} });

  await testPage.goto(`chrome://extensions/?id=${EXTENSION_ID}`);
  await bringChromeToFront(testPage, profileDir);
  const healthySettingsBlocker = await blockerState();
  if (healthySettingsBlocker.visible) throw new Error(`Extension settings were blocked while the extension was healthy: ${JSON.stringify(healthySettingsBlocker)}`);
  report.checks.healthySettingsAccessible = { blocker: healthySettingsBlocker, url: testPage.url() };

  await phase('disable extension under active policy and verify 60-second repair warning is non-modal');
  await tauriInvoke(appMainPage, 'set_browser_protection_policy', { required: true, lockedUntilMs: 0 });
  const primaryDisabled = await setExtensionToggle(testPage, false, { source: 'enable', test: /enable/i });
  report.checks.extensionDisabledInUi = primaryDisabled;
  await testPage.goto(testUrl);
  await bringChromeToFront(testPage, profileDir);
  const firstRepair = assertRepairState(await waitForRepairState('missing extension warning for primary profile', state => state !== null, 20_000), 'primary profile');
  if (firstRepair.graceRemainingSeconds > 60 || firstRepair.graceRemainingSeconds < 35) {
    throw new Error(`The extension recovery timer did not begin near 60 seconds: ${JSON.stringify(firstRepair)}`);
  }
  const repairWindow = await waitForRepairPage();
  if (repairWindow.inspection.buttonBottom > repairWindow.inspection.height) {
    throw new Error('The repair button is outside the compact warning window.');
  }
  const warningScreenshot = path.join(outputDir, `browser-repair-countdown-${RUN_ID}.png`);
  await repairWindow.page.screenshot({ path: warningScreenshot, timeout: 5000 });
  const blockerDuringRepair = await blockerState();
  if (blockerDuringRepair.visible) throw new Error(`A native blocker appeared during extension repair: ${JSON.stringify(blockerDuringRepair)}`);
  const browserRetainedFocus = await testPage.evaluate(() => document.hasFocus());
  if (!browserRetainedFocus) throw new Error('The repair warning took focus away from the browser.');
  report.checks.nonModalRepairWarning = {
    state: firstRepair,
    page: repairWindow.inspection,
    screenshot: warningScreenshot,
    browserRetainedFocus,
    blocker: blockerDuringRepair,
  };

  await phase('confirm extension settings stay accessible and re-enable cancels repair');
  const repairSettings = await setExtensionToggle(testPage, true, { source: 'enable', test: /enable/i });
  report.checks.settingsAccessibleDuringRepair = { url: testPage.url(), reenabled: repairSettings };
  await testPage.goto(testUrl);
  await bringChromeToFront(testPage, profileDir);
  await waitForTracker('healthy lease after re-enabling primary profile', value => value.browserProtection?.healthy === true, 30_000);
  const repairCleared = await poll('repair warning clears after extension recovery', async () => {
    const state = await repairState();
    return state === null ? { done: true, value: state } : { done: false, state };
  }, { timeoutMs: 15_000, log });
  const recoveredBlocker = await blockerState();
  if (recoveredBlocker.visible) throw new Error(`A native blocker appeared after extension recovery: ${JSON.stringify(recoveredBlocker)}`);
  if (testPage.isClosed()) throw new Error('The browser closed after the extension was re-enabled before the deadline.');
  report.checks.reenabledBeforeDeadline = { repairState: repairCleared, blocker: recoveredBlocker, browserStillOpen: true };

  await phase('verify an unextended second profile has an independent, non-renewing deadline');
  const secondProfileDir = path.join(outputDir, `qa-profile-secondary-${RUN_ID}`);
  await fs.mkdir(secondProfileDir, { recursive: true });
  secondaryContext = await chromium.launchPersistentContext(secondProfileDir, {
    ...(chromeExecutable ? { executablePath: path.resolve(chromeExecutable) } : { channel: 'chromium' }),
    headless: false,
    args: ['--no-first-run', '--no-default-browser-check', '--disable-sync', '--disable-background-networking'],
    viewport: { width: 1280, height: 800 },
  });
  const secondaryPage = secondaryContext.pages()[0] || await secondaryContext.newPage();
  await secondaryPage.goto(testUrl);
  await secondaryPage.evaluate(() => { document.title += ' Secondary profile'; });
  await bringChromeToFront(secondaryPage, secondProfileDir);
  const secondRepair = assertRepairState(await waitForRepairState('repair warning for unextended secondary profile', state => state !== null, 20_000), 'secondary profile');
  if (secondRepair.graceRemainingSeconds > 60 || secondRepair.graceRemainingSeconds <= 0) {
    throw new Error(`The secondary profile did not receive a fresh 60-second lease: ${JSON.stringify(secondRepair)}`);
  }
  const secondRepairObservedAt = Date.now();
  const repairIdentity = secondRepair.appId;

  // A fresh heartbeat from the healthy profile must not clear or renew the
  // separate unextended profile's countdown.
  const healthyProbe = await chromiumContext.newPage();
  await healthyProbe.goto(`chrome-extension://${EXTENSION_ID}/popup/popup.html`);
  const healthyHeartbeat = await healthyProbe.evaluate(async () => chrome.runtime.sendNativeMessage('com.focuslock.browser', {
    type: 'heartbeat', version: 1, allUrls: true, incognitoAllowed: true, windows: [],
  }));
  await healthyProbe.close();
  if (healthyHeartbeat?.ok !== true) throw new Error(`The healthy profile heartbeat failed during the independent repair timer: ${JSON.stringify(healthyHeartbeat)}`);
  const afterHealthyHeartbeat = assertRepairState(await repairState(), 'secondary profile after healthy profile heartbeat');
  if (afterHealthyHeartbeat.appId !== repairIdentity || afterHealthyHeartbeat.graceRemainingSeconds > secondRepair.graceRemainingSeconds) {
    throw new Error(`The healthy profile renewed or replaced the other profile's repair timer: ${JSON.stringify({ secondRepair, afterHealthyHeartbeat })}`);
  }

  await appMainPage.bringToFront();
  if (!await foregroundPid(qaPid)) throw new Error('Could not bring the QA app foreground before the browser repair deadline.');
  await new Promise(resolve => setTimeout(resolve, 7000));
  const afterFocusSwitch = assertRepairState(await repairState(), 'secondary profile after focus switch');
  if (afterFocusSwitch.appId !== repairIdentity || afterFocusSwitch.graceRemainingSeconds >= afterHealthyHeartbeat.graceRemainingSeconds) {
    throw new Error(`Switching to the QA app renewed the secondary profile timer: ${JSON.stringify({ afterHealthyHeartbeat, afterFocusSwitch })}`);
  }
  if (secondaryPage.isClosed() || testPage.isClosed()) {
    throw new Error('A browser profile closed before the secondary profile repair deadline expired.');
  }
  report.checks.independentProfileCountdown = {
    initial: secondRepair,
    afterHealthyProfileHeartbeat: afterHealthyHeartbeat,
    afterQaForeground: afterFocusSwitch,
    sameAffectedAppId: true,
    countdownDidNotRenew: true,
  };

  await phase('close only the unextended browser after its deadline without requiring browser foreground');
  await poll('secondary browser window closes at repair deadline', async () => {
    const closed = secondaryPage.isClosed() || !secondaryContext.pages().includes(secondaryPage);
    return closed ? { done: true, value: { closed, elapsedMs: Date.now() - secondRepairObservedAt } } : { done: false, closed };
  }, { timeoutMs: 75_000, intervalMs: 500, progressMs: 10_000, log });
  const closureElapsedMs = Date.now() - secondRepairObservedAt;
  const expectedRemainingMs = secondRepair.graceRemainingSeconds * 1000;
  if (closureElapsedMs < expectedRemainingMs - 2000 || closureElapsedMs > expectedRemainingMs + 15_000) {
    throw new Error(`The browser did not close near the observed countdown deadline (${closureElapsedMs}ms elapsed, ${expectedRemainingMs}ms remaining when observed).`);
  }
  if (!childIsRunning(qaChild)) throw new Error('FocusLock exited while closing the affected browser window.');
  if (testPage.isClosed()) throw new Error('The healthy extension profile was closed with the unextended profile.');
  const stateAfterClosure = await poll('repair notice clears on the next tracker sample after closure', async () => {
    const state = await repairState();
    return state === null ? { done: true, value: state } : { done: false, state };
  }, { timeoutMs: 10_000, intervalMs: 300, log });
  const blockerAfterClosure = await blockerState();
  if (blockerAfterClosure.visible) throw new Error(`A native website blocker appeared after browser recovery enforcement: ${JSON.stringify(blockerAfterClosure)}`);
  report.checks.browserClosedAtDeadline = {
    elapsedMs: closureElapsedMs,
    qaAppStillRunning: childIsRunning(qaChild),
    healthyProfileStillOpen: !testPage.isClosed(),
    repairState: stateAfterClosure,
    blocker: blockerAfterClosure,
  };

  await phase('restore neutral QA policy');
  await tauriInvoke(appMainPage, 'set_browser_protection_policy', { required: false, lockedUntilMs: 0 });
  report.status = 'passed';
} catch (error) {
  fatalError = error;
  report.status = interrupted ? 'interrupted' : 'failed';
  report.error = error.stack || error.message;
  try { await log('ACCEPTANCE FAILED', { error: error.message }); } catch { /* preserve primary failure */ }
} finally {
  try {
    await phase('cleanup and exact registry restoration');
    await cleanup();
  } catch (error) {
    report.status = 'cleanup_failed';
    report.cleanupError = error.stack || error.message;
    try { await log('CLEANUP FAILED', { error: error.message }); } catch { /* no further recovery output */ }
  }
  report.finishedAt = new Date().toISOString();
  await fs.writeFile(reportFile, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`Browser protection QA report: ${reportFile}`);
  console.log(`Registry recovery backup: ${backupFile}`);
}

if (fatalError || report.status !== 'passed') {
  if (fatalError) console.error(fatalError.stack || fatalError.message);
  process.exitCode = 1;
} else {
  console.log('Browser protection live acceptance passed.');
}
