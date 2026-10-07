import { spawn, spawnSync } from 'node:child_process';
import { createServer as createHttpServer } from 'node:http';
import { once } from 'node:events';
import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { chromium } from '../extension/node_modules/playwright-core/index.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const HOST_NAME = 'com.focuslock.browser';
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
const BLOCKED_APP_ID = 'focuslock.qa.lifecycle.boundary';

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (key === '--help') args.help = true;
    else if (['--exe', '--identifier', '--out'].includes(key)) args[key.slice(2)] = argv[++i];
    else throw new Error(`Unexpected argument: ${key}`);
  }
  return args;
}

function usage() {
  return 'Usage: node scripts/verify-pc-background.mjs --exe <absolute QA.exe> --identifier <com.focuslock.browserqa.random> [--out <directory>]';
}

function registryKey(base) { return `HKCU\\${base}\\${HOST_NAME}`; }
function reg(args) {
  const result = spawnSync('reg.exe', args, { encoding: 'utf8', windowsHide: true });
  if (result.error) throw new Error(`Could not run reg.exe ${args[0]}: ${result.error.message}`);
  return { status: result.status ?? 1, stdout: result.stdout || '', stderr: result.stderr || '' };
}
function parseDefault(output) {
  const line = String(output).split(/\r?\n/).find(value => /^\s*\(Default\)\s+REG_\w+\s+/i.test(value));
  if (!line) return { present: false, value: null };
  const match = line.match(/^\s*\(Default\)\s+(REG_\w+)\s+(.*)$/i);
  if (match[1].toUpperCase() !== 'REG_SZ') throw new Error(`Unexpected native host registry value type ${match[1]}; refusing to alter it.`);
  return { present: true, value: match[2].trim() };
}
function captureRegistryEntry(base, view) {
  const key = registryKey(base);
  const keyQuery = reg(['query', key, view]);
  const value = parseDefault(reg(['query', key, '/ve', view]).stdout);
  return { key, base, view, keyPresent: keyQuery.status === 0, defaultValuePresent: value.present, defaultValue: value.value };
}
function captureRegistry() { return REGISTRY_BASES.flatMap(base => REGISTRY_VIEWS.map(view => captureRegistryEntry(base, view))); }
function deleteDefault(entry) { reg(['delete', entry.key, '/ve', '/f', entry.view]); }
function addDefault(entry, value) {
  const result = reg(['add', entry.key, '/ve', '/t', 'REG_SZ', '/d', value, '/f', entry.view]);
  if (result.status !== 0) throw new Error(`Could not restore ${entry.key} (${entry.view}): ${result.stderr || result.stdout}`);
}
function removeEmptyCreatedKey(entry) {
  const result = reg(['query', entry.key, entry.view]);
  if (result.status !== 0) return;
  const lines = result.stdout.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  if (!lines.slice(1).some(line => /^\(Default\)|^[^\s].*REG_\w+\s|^HKEY_/i.test(line))) {
    const deleted = reg(['delete', entry.key, '/f', entry.view]);
    if (deleted.status !== 0) throw new Error(`Could not remove QA-created empty key ${entry.key} (${entry.view}).`);
  }
}
function restoreRegistry(entries) {
  const allowed = new Set(REGISTRY_BASES.flatMap(base => REGISTRY_VIEWS.map(view => `${registryKey(base)}|${view}`)));
  if (!Array.isArray(entries) || entries.length !== allowed.size) throw new Error('Registry backup is incomplete; refusing to restore it.');
  const seen = new Set();
  for (const entry of entries) {
    const identity = `${entry.key}|${entry.view}`;
    if (!allowed.has(identity) || seen.has(identity)) throw new Error('Registry backup contains an invalid or duplicate key.');
    seen.add(identity);
  }
  const errors = [];
  for (const entry of entries) {
    try {
      deleteDefault(entry);
      if (entry.defaultValuePresent) addDefault(entry, entry.defaultValue);
      else if (!entry.keyPresent) removeEmptyCreatedKey(entry);
    } catch (error) { errors.push(error.message); }
  }
  if (errors.length) throw new Error(`Native host registry restoration failed:\n${errors.join('\n')}`);
}
function assertRegistryMatches(entries) {
  const current = captureRegistry();
  for (const saved of entries) {
    const actual = current.find(entry => entry.key === saved.key && entry.view === saved.view);
    if (!actual || actual.keyPresent !== saved.keyPresent || actual.defaultValuePresent !== saved.defaultValuePresent ||
        actual.defaultValue !== saved.defaultValue) {
      throw new Error(`Registry entry did not return to its saved state: ${saved.key} (${saved.view}).`);
    }
  }
}

function assertInputs(args) {
  if (process.platform !== 'win32') throw new Error('This acceptance runner requires Windows.');
  if (!args.exe || !path.isAbsolute(args.exe)) throw new Error('--exe must be an absolute QA executable path.');
  if (!args.identifier || !/^com\.focuslock\.browserqa\.[a-z0-9.-]+$/i.test(args.identifier)) {
    throw new Error('--identifier must begin with com.focuslock.browserqa.');
  }
}
async function writeJson(file, value) { await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 }); }
async function fetchJson(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response.json();
}
async function poll(label, check, timeoutMs = 15_000, intervalMs = 350) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try { last = await check(); if (last?.done) return last.value; } catch (error) { last = error.message; }
    await new Promise(resolve => setTimeout(resolve, intervalMs));
  }
  throw new Error(`Timed out waiting for ${label}; last observation: ${JSON.stringify(last)}`);
}
async function pagesWithTauri(browser) {
  const result = [];
  for (const page of browser.contexts().flatMap(context => context.pages())) {
    try { if (await page.evaluate(() => Boolean(window.__TAURI_INTERNALS__?.invoke))) result.push(page); } catch { /* ignore unrelated targets */ }
  }
  return result;
}
async function invoke(page, command, payload = {}) {
  return page.evaluate(async ({ command, payload }) => {
    const fn = window.__TAURI_INTERNALS__?.invoke;
    if (typeof fn !== 'function') throw new Error('Tauri invoke is unavailable.');
    return fn(command, payload);
  }, { command, payload });
}
async function unusedPort() {
  const { createServer } = await import('node:net');
  const server = createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const port = server.address().port;
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return port;
}
function ps(script) {
  const encoded = Buffer.from(script, 'utf16le').toString('base64');
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], { encoding: 'utf8', windowsHide: true });
  if (result.status !== 0) throw new Error(result.stderr || result.stdout || 'PowerShell operation failed.');
  return result.stdout.trim();
}
function sendClose(pid) {
  const script = `Add-Type -TypeDefinition @'
using System; using System.Text; using System.Runtime.InteropServices;
public static class FocusLockPcQaWindow {
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc callback, IntPtr param);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hwnd, StringBuilder text, int max);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int command);
 [DllImport("user32.dll")] public static extern IntPtr SendMessageTimeout(IntPtr hwnd, uint msg, IntPtr w, IntPtr l, uint flags, uint timeout, out IntPtr result);
 public delegate bool EnumProc(IntPtr hwnd, IntPtr param);
}
'@; $target=${pid}; [FocusLockPcQaWindow]::EnumWindows({ param($hwnd,$param) $owner=0; [void][FocusLockPcQaWindow]::GetWindowThreadProcessId($hwnd,[ref]$owner); if($owner -eq $target){ $title=New-Object System.Text.StringBuilder 256; [void][FocusLockPcQaWindow]::GetWindowTextW($hwnd,$title,256); if($title.ToString() -eq 'FocusLock'){ $result=[IntPtr]::Zero; [void][FocusLockPcQaWindow]::SendMessageTimeout($hwnd,0x0010,[IntPtr]::Zero,[IntPtr]::Zero,0x0002,3000,[ref]$result) } }; return $true },[IntPtr]::Zero) | Out-Null`;
  ps(script);
}
function visibleWindowCount(pid) {
  return nativeWindowsForPid(pid).filter(window => window.title === 'FocusLock').length;
}
function processAlive(pid) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `if(Get-Process -Id ${pid} -ErrorAction SilentlyContinue){exit 0}else{exit 1}`], { encoding: 'utf8', windowsHide: true });
  return result.status === 0;
}
function processCommandLine(pid) {
  return ps(`(Get-CimInstance Win32_Process -Filter 'ProcessId = ${pid}').CommandLine`);
}
function findProcessByExecutable(executable, exclude = new Set()) {
  const raw = ps(`Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath -ieq '${executable.replaceAll("'", "''")}' } | Select-Object -ExpandProperty ProcessId`);
  return raw.split(/\s+/).map(Number).filter(pid => Number.isInteger(pid) && pid > 0 && !exclude.has(pid));
}
function findWatchdogs(parentPid) {
  const raw = ps(`Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match '--focuslock-watchdog\\s+${parentPid}(\\s|$)' } | Select-Object -ExpandProperty ProcessId`);
  return raw.split(/\s+/).map(Number).filter(pid => Number.isInteger(pid) && pid > 0);
}
function profileProcessIds(profileDir, executableLeaf = 'chrome.exe') {
  const escaped = profileDir.replaceAll("'", "''");
  const escapedLeaf = executableLeaf.replaceAll("'", "''");
  const raw = ps(`Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and [System.IO.Path]::GetFileName($_.ExecutablePath) -ieq '${escapedLeaf}' -and $_.CommandLine -and $_.CommandLine.Contains('${escaped}') } | Select-Object -ExpandProperty ProcessId`);
  return raw.split(/\s+/).map(Number).filter(pid => Number.isInteger(pid) && pid > 0);
}
function nativeWindowsForPid(pid) {
  const output = ps(`Add-Type -TypeDefinition @'
using System; using System.Text; using System.Runtime.InteropServices;
public static class FocusLockPcQaBrowserWindows {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc callback, IntPtr param);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hwnd, StringBuilder text, int max);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
 public delegate bool EnumProc(IntPtr hwnd, IntPtr param);
}
'@; $target=${pid}; $script:items=New-Object System.Collections.Generic.List[object]; [FocusLockPcQaBrowserWindows]::EnumWindows({ param($hwnd,$param) $owner=0; [void][FocusLockPcQaBrowserWindows]::GetWindowThreadProcessId($hwnd,[ref]$owner); if($owner -eq $target -and [FocusLockPcQaBrowserWindows]::IsWindowVisible($hwnd)){ $title=New-Object System.Text.StringBuilder 1024; [void][FocusLockPcQaBrowserWindows]::GetWindowTextW($hwnd,$title,1024); $rect=New-Object FocusLockPcQaBrowserWindows+RECT; [void][FocusLockPcQaBrowserWindows]::GetWindowRect($hwnd,[ref]$rect); $script:items.Add([pscustomobject]@{hwnd=$hwnd.ToInt64(); pid=$owner; title=$title.ToString(); left=$rect.Left; top=$rect.Top; width=($rect.Right-$rect.Left); height=($rect.Bottom-$rect.Top)}) }; return $true },[IntPtr]::Zero) | Out-Null; ConvertTo-Json -InputObject @($script:items.ToArray()) -Compress`);
  const parsed = JSON.parse(output || '[]');
  return Array.isArray(parsed) ? parsed : [parsed];
}
async function chromeWorker(context, extensionId) {
  return poll('FocusLock extension service worker', async () => {
    const worker = context.serviceWorkers().find(candidate => candidate.url().startsWith(`chrome-extension://${extensionId}/`));
    return worker ? { done: true, value: worker } : null;
  }, 20_000, 300);
}
async function chromeWindows(worker) {
  return worker.evaluate(async () => new Promise(resolve => chrome.windows.getAll({ populate: true }, resolve)));
}
async function chromeTabsCreate(worker, url) {
  return worker.evaluate(async target => new Promise(resolve => chrome.tabs.create({ url: target, active: true }, resolve)), url);
}
async function chromeTabUpdate(worker, tabId, url) {
  return worker.evaluate(async ({ tabId, url }) => new Promise(resolve => chrome.tabs.update(tabId, { url }, resolve)), { tabId, url });
}
async function chromeWindowUpdate(worker, windowId, update) {
  return worker.evaluate(async ({ windowId, update }) => new Promise(resolve => chrome.windows.update(windowId, update, resolve)), { windowId, update });
}
async function readLeaseFiles(appDataDir) {
  const directory = path.join(appDataDir, 'browser-leases');
  try {
    const names = await fs.readdir(directory);
    const leases = await Promise.all(names.filter(name => /^host-\d+\.json$/.test(name)).map(async name => {
      try { return { file: path.join(directory, name), value: JSON.parse(await fs.readFile(path.join(directory, name), 'utf8')) }; }
      catch { return null; }
    }));
    return leases.filter(Boolean);
  } catch { return []; }
}
async function createTitleServer() {
  const server = createHttpServer((request, response) => {
    const url = new URL(request.url || '/', 'http://127.0.0.1');
    const title = (url.searchParams.get('title') || 'PC Bridge QA').slice(0, 100).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    response.end(`<!doctype html><html><head><title>${title}</title></head><body>${title}</body></html>`);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return { server, baseUrl: `http://127.0.0.1:${server.address().port}` };
}
async function runChromeBridgeAcceptance() {
  const extensionRoot = path.join(ROOT, 'build', 'extension-unpacked');
  await fs.access(path.join(extensionRoot, 'manifest.json'));
  const extensionManifest = JSON.parse(await fs.readFile(path.join(extensionRoot, 'manifest.json'), 'utf8'));
  const extensionId = 'fkkpmoiageeieaoplphafmhjkkdadcnf';
  const chromeProfile = path.join(outputDir, `chrome-profile-${RUN_ID}`);
  const titleServerState = await createTitleServer();
  titleServer = titleServerState.server;
  chromeProfileDir = chromeProfile;
  const customChromium = process.env.FOCUSLOCK_QA_CHROME ? path.resolve(process.env.FOCUSLOCK_QA_CHROME) : null;
  chromeExecutableLeaf = path.basename(customChromium || chromium.executablePath());
  chromeContextQA = await chromium.launchPersistentContext(chromeProfile, {
    ...(customChromium ? { executablePath: customChromium } : {}),
    headless: false,
    viewport: { width: 1280, height: 800 },
    args: [
    `--disable-extensions-except=${extensionRoot}`,
    `--load-extension=${extensionRoot}`,
      '--no-first-run', '--no-default-browser-check', '--disable-sync', '--disable-background-networking',
    ],
  });
  const chromiumPage = chromeContextQA.pages()[0] || await chromeContextQA.newPage();
  await chromiumPage.goto(`${titleServerState.baseUrl}/?title=PC%20Bridge%20QA%20A`);
  let worker = await chromeWorker(chromeContextQA, extensionId);
  chromeWorkerRef = worker;
  const firstTab = await chromeTabsCreate(worker, `${titleServerState.baseUrl}/?title=PC%20Bridge%20QA%20A`);
  const tabId = firstTab.id;
  const windowId = firstTab.windowId;
  if (!Number.isInteger(tabId) || !Number.isInteger(windowId)) throw new Error('Could not create the isolated Chrome bridge tab.');
  await chromeWindowUpdate(worker, windowId, { focused: true });
  const initialBrowserIdentity = await poll('lease bound to the disposable Chromium profile and native QA HWND', async () => {
    const leases = await readLeaseFiles(appDataDir);
    for (const entry of leases) {
      const lease = entry.value;
      if (String(lease.appId).toLowerCase() !== 'chrome.exe' || lease.allUrls !== true || !lease.windowHandles?.length) continue;
      const candidatePid = Number(lease.browserPid);
      if (!Number.isInteger(candidatePid) || candidatePid <= 0 || !processAlive(candidatePid)) continue;
      const commandLine = processCommandLine(candidatePid);
      if (!commandLine.toLowerCase().includes(chromeProfile.toLowerCase())) continue;
      const windows = nativeWindowsForPid(candidatePid);
      const exactQaWindow = windows.find(window => lease.windowHandles.includes(window.hwnd) && window.title.startsWith('PC Bridge QA A'));
      if (!exactQaWindow) continue;
      const apps = await invoke(page, 'get_running_apps');
      const nativeHelperObservation = apps.find(app => String(app.appId).toLowerCase() === 'chrome.exe' &&
        String(app.windowTitle).startsWith('PC Bridge QA A')) || null;
      return { done: true, value: { pid: candidatePid, commandLine, leaseEntry: entry, nativeWindow: exactQaWindow, nativeHelperObservation } };
    }
    return { candidates: leases.map(entry => ({ browserPid: entry.value.browserPid, appId: entry.value.appId, allUrls: entry.value.allUrls, windowHandles: entry.value.windowHandles })) };
  }, 20_000, 300);
  chromePid = initialBrowserIdentity.pid;
  report.checks.chromeBridgeProcessIdentity = {
    browserPid: chromePid, profile: chromeProfile, commandLine: initialBrowserIdentity.commandLine,
    leaseFile: initialBrowserIdentity.leaseEntry.file, hwnd: initialBrowserIdentity.nativeWindow.hwnd,
  };

  async function attestation(title, previousTimestamp = 0) {
    return poll(`native lease for ${title}`, async () => {
      const leases = await readLeaseFiles(appDataDir);
      const leaseEntry = leases.filter(entry => entry.value.browserPid === chromePid &&
        String(entry.value.appId).toLowerCase() === 'chrome.exe' && entry.value.allUrls === true &&
        Number(entry.value.updatedAtMs) > previousTimestamp)
        .sort((a, b) => b.value.updatedAtMs - a.value.updatedAtMs)[0];
      if (!leaseEntry || !Array.isArray(leaseEntry.value.windowHandles)) return null;
      const nativeWindows = nativeWindowsForPid(chromePid);
      const matchingWindows = nativeWindows.filter(window => leaseEntry.value.windowHandles.includes(window.hwnd) &&
        window.title.startsWith(title));
      const apps = await invoke(page, 'get_running_apps');
      const nativeHelperObservation = apps.find(app => String(app.appId).toLowerCase() === 'chrome.exe' &&
        String(app.windowTitle).startsWith(title)) || null;
      if (!matchingWindows.length) return null;
      return { done: true, value: { leaseEntry, nativeWindow: matchingWindows[0], nativeHelperObservation } };
    }, 20_000, 300);
  }

  const initial = await attestation('PC Bridge QA A');
  const afterTitleTab = await chromeTabUpdate(worker, tabId, `${titleServerState.baseUrl}/?title=PC%20Bridge%20QA%20B`);
  if (!afterTitleTab) throw new Error('Chrome did not return the updated bridge tab.');
  const titleCheck = await attestation('PC Bridge QA B', initial.leaseEntry.value.updatedAtMs);

  worker = await chromeWorker(chromeContextQA, extensionId);
  chromeWorkerRef = worker;
  let currentWindow = (await chromeWindows(worker)).find(candidate => candidate.id === windowId);
  if (!currentWindow) throw new Error('FocusLock bridge no longer reports its Chrome window.');
  const moveBefore = nativeWindowsForPid(chromePid).find(item => titleCheck.leaseEntry.value.windowHandles.includes(item.hwnd));
  const moveLeft = Number(currentWindow.left) > 70 ? 35 : 115;
  const moveTop = Number(currentWindow.top) > 70 ? 35 : 115;
  const moved = await chromeWindowUpdate(worker, windowId, { left: moveLeft, top: moveTop, focused: true });
  const moveCheck = await attestation('PC Bridge QA B', titleCheck.leaseEntry.value.updatedAtMs);
  if (Number(moved.left) !== moveLeft || Number(moved.top) !== moveTop || !moveBefore ||
      (moveCheck.nativeWindow.left === moveBefore.left && moveCheck.nativeWindow.top === moveBefore.top)) {
    throw new Error('Chrome move did not change the native window bounds while retaining its lease.');
  }

  worker = await chromeWorker(chromeContextQA, extensionId);
  chromeWorkerRef = worker;
  currentWindow = (await chromeWindows(worker)).find(candidate => candidate.id === windowId);
  const resizeBefore = nativeWindowsForPid(chromePid).find(item => moveCheck.leaseEntry.value.windowHandles.includes(item.hwnd));
  const resizeWidth = Number(currentWindow.width) >= 1100 ? Number(currentWindow.width) - 100 : Number(currentWindow.width) + 100;
  const resizeHeight = Number(currentWindow.height) >= 850 ? Number(currentWindow.height) - 80 : Number(currentWindow.height) + 80;
  const resized = await chromeWindowUpdate(worker, windowId, { width: resizeWidth, height: resizeHeight, focused: true });
  const resizeCheck = await attestation('PC Bridge QA B', moveCheck.leaseEntry.value.updatedAtMs);
  if (Number(resized.width) !== resizeWidth || Number(resized.height) !== resizeHeight || !resizeBefore ||
      (resizeCheck.nativeWindow.width === resizeBefore.width && resizeCheck.nativeWindow.height === resizeBefore.height)) {
    throw new Error('Chrome resize did not change the native window bounds while retaining its lease.');
  }

  worker = await chromeWorker(chromeContextQA, extensionId);
  chromeWorkerRef = worker;
  await worker.evaluate(() => { self.FocusLockDesktopBridge.stop(); return true; });
  await poll('native lease removal after stopping extension bridge', async () => {
    const leases = await readLeaseFiles(appDataDir);
    return leases.some(entry => entry.value.browserPid === chromePid)
      ? null : { done: true, value: true };
  }, 12_000, 300);
  report.checks.chromeBridge = {
    executablePath: customChromium || chromium.executablePath(), profile: chromeProfile, extensionId: extensionManifest.key ? extensionId : null,
    browserPid: chromePid,
    initial: { title: 'PC Bridge QA A', hwnd: initial.nativeWindow.hwnd, lease: initial.leaseEntry.value, nativeHelperObservation: initial.nativeHelperObservation },
    titleAndTabChange: { title: 'PC Bridge QA B', hwnd: titleCheck.nativeWindow.hwnd, lease: titleCheck.leaseEntry.value, nativeHelperObservation: titleCheck.nativeHelperObservation },
    move: { left: moved.left, top: moved.top, nativeRect: moveCheck.nativeWindow, lease: moveCheck.leaseEntry.value },
    resize: { width: resized.width, height: resized.height, nativeRect: resizeCheck.nativeWindow, lease: resizeCheck.leaseEntry.value },
    bridgeStopRemovedLease: true,
  };
}
function stopQaPid(pid) {
  if (!pid || !processAlive(pid)) return;
  const result = spawnSync('taskkill.exe', ['/PID', String(pid), '/F'], { encoding: 'utf8', windowsHide: true });
  if (result.status !== 0 && processAlive(pid)) throw new Error(`Could not stop QA PID ${pid}: ${result.stderr || result.stdout}`);
}

const args = parseArgs(process.argv.slice(2));
if (args.help) { console.log(usage()); process.exit(0); }
assertInputs(args);
const executable = path.resolve(args.exe);
await fs.access(executable);
const outputDir = path.resolve(args.out || path.join(ROOT, 'build', 'pc-background'));
await fs.mkdir(outputDir, { recursive: true });
const backupFile = path.join(outputDir, `registry-backup-${RUN_ID}.json`);
const reportFile = path.join(outputDir, `pc-background-report-${RUN_ID}.json`);
const report = { runId: RUN_ID, startedAt: new Date().toISOString(), executable, identifier: args.identifier, outputDir, registryBackup: backupFile, checks: {}, status: 'running' };
let registryBackup = null;
let child = null;
let browser = null;
let page = null;
let debugPort = null;
let originalPid = null;
let recoveredPid = null;
let identifierVerified = false;
let appDataDir = null;
let preExistingPids = [];
let chromeContextQA = null;
let chromeProfileDir = null;
let chromeExecutableLeaf = 'chrome.exe';
let titleServer = null;
let chromeWorkerRef = null;
let chromePid = null;

async function connectForPid(pid, port) {
  const url = `http://127.0.0.1:${port}`;
  const version = await fetchJson(`${url}/json/version`);
  if (!version.Browser) throw new Error('WebView2 CDP endpoint did not report a browser.');
  const connected = await chromium.connectOverCDP(url, { timeout: 10_000 });
  const appPage = await poll(`Tauri page for PID ${pid}`, async () => {
    const pages = await pagesWithTauri(connected);
    return pages.length ? { done: true, value: pages.find(candidate => !candidate.url().includes('/blocked')) || pages[0] } : null;
  });
  return { browser: connected, page: appPage };
}

try {
  if (!path.resolve(path.dirname(executable)).toLowerCase().includes(`${path.sep}target${path.sep}`.toLowerCase())) {
    throw new Error('QA executable must be under a target directory so startup cannot register its Run autorun entry.');
  }
  appDataDir = path.join(process.env.APPDATA || '', args.identifier);
  report.checks.isolatedAppDataIdentifier = appDataDir;
  registryBackup = { runId: RUN_ID, hostName: HOST_NAME, entries: captureRegistry() };
  await writeJson(backupFile, registryBackup);
  preExistingPids = findProcessByExecutable(executable);
  if (preExistingPids.length) throw new Error(`QA executable is already running as PID(s) ${preExistingPids.join(', ')}; refusing to interact with an existing process.`);

  debugPort = await unusedPort();
  child = spawn(executable, [], {
    env: { ...process.env, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${debugPort}` },
    stdio: 'ignore', windowsHide: true,
  });
  originalPid = child.pid;
  report.checks.originalPid = originalPid;
  child.on('error', error => { report.childError = error.message; });
  await poll('initial QA CDP endpoint', async () => {
    if (!processAlive(originalPid)) throw new Error(`Initial QA process ${originalPid} exited.`);
    const endpoint = await fetchJson(`http://127.0.0.1:${debugPort}/json/version`);
    return endpoint.Browser ? { done: true, value: endpoint } : null;
  });
  ({ browser, page } = await connectForPid(originalPid, debugPort));
  const initialWatchdogs = await poll('release watchdog for initial QA process', async () => {
    const pids = findWatchdogs(originalPid);
    return pids.length ? { done: true, value: pids } : null;
  }, 10_000, 250);
  report.checks.initialWatchdog = { pids: initialWatchdogs };

  // Verify the disposable native host points to this executable and this QA app-data root
  // before invoking any policy or tracker mutation.
  const registered = captureRegistry();
  const values = registered.filter(entry => entry.defaultValuePresent);
  const manifests = [...new Set(values.map(entry => entry.defaultValue))];
  if (manifests.length === 0) throw new Error('The QA process did not register the FocusLock native host.');
  for (const manifestPath of manifests) {
    const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'));
    if (manifest.name !== HOST_NAME || path.resolve(manifest.path).toLowerCase() !== executable.toLowerCase()) {
      throw new Error(`Native host manifest ${manifestPath} is not bound to the QA executable.`);
    }
    if (path.resolve(path.dirname(manifestPath)).toLowerCase() !== path.resolve(appDataDir).toLowerCase()) {
      throw new Error(`Native host manifest is outside disposable QA app data (${args.identifier}); no policy commands sent.`);
    }
  }
  identifierVerified = true;
  report.checks.nativeHost = { registryViews: values.length, manifests, identifierVerified };

  const emptyTargets = { targets: { appIds: [], domains: [] }, reasons: {} };
  await invoke(page, 'set_blocked_targets', emptyTargets);
  await invoke(page, 'start_tracking');
  let snapshot = await invoke(page, 'get_tracking_snapshot');
  if (!snapshot.running) throw new Error('Tracker did not start in the isolated QA app.');
  report.checks.trackerStarted = true;

  await sendClose(originalPid);
  await poll('main window close-to-hide', async () => {
    const count = visibleWindowCount(originalPid);
    const status = await invoke(page, 'get_tracker_status');
    return count === 0 && status.running ? { done: true, value: { visibleWindows: count, trackerRunning: status.running } } : null;
  }, 10_000, 300);
  report.checks.closeHidesMainAndKeepsTracking = { visibleWindows: 0, trackerRunning: true };

  const duplicate = spawn(executable, [], { env: { ...process.env, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${debugPort}` }, stdio: 'ignore', windowsHide: true });
  const duplicatePid = duplicate.pid;
  await Promise.race([once(duplicate, 'exit'), new Promise(resolve => setTimeout(resolve, 5000))]);
  const duplicateExited = !processAlive(duplicatePid);
  report.checks.duplicateLaunchRejected = { duplicatePid, exited: duplicateExited };
  if (!duplicateExited) throw new Error('Second launch remained running; singleton rejection failed.');
  report.checks.duplicateLaunchLeavesTrackerInstanceRunning = await invoke(page, 'get_tracker_status').then(status => status.running);
  if (!report.checks.duplicateLaunchLeavesTrackerInstanceRunning) throw new Error('Tracker stopped after duplicate launch handling.');
  sendClose(originalPid);
  await poll('WM_CLOSE hides the main window after duplicate launch', async () => {
    const visibleWindows = nativeWindowsForPid(originalPid);
    report.checks.visibleWindowsAfterDuplicate = visibleWindows;
    return visibleWindows.some(window => window.title === 'FocusLock')
      ? { visibleMainWindows: visibleWindows.filter(window => window.title === 'FocusLock') }
      : { done: true, value: true };
  }, 10_000, 300);
  report.checks.duplicateWindowCloseRegression = { passed: true };

  const neutralPause = await invoke(page, 'stop_tracking');
  if (neutralPause.running) throw new Error('Neutral tracker pause did not pause tracking.');
  const pausedSnapshot = await invoke(page, 'get_tracking_snapshot');
  if (pausedSnapshot.running) throw new Error('Tracker remained active after a neutral pause.');
  report.checks.neutralPause = { running: pausedSnapshot.running };
  await invoke(page, 'set_blocked_targets', { targets: { appIds: [BLOCKED_APP_ID], domains: [] }, reasons: { [BLOCKED_APP_ID]: 'qa-persisted-boundary' } });
  const beforeRestart = await invoke(page, 'get_tracking_snapshot');
  if (!beforeRestart.running) throw new Error('Adding an active boundary did not resume tracking after a neutral pause.');
  if (!beforeRestart.blockedTargets?.appIds?.includes(BLOCKED_APP_ID)) throw new Error('QA app boundary did not persist before restart.');
  report.checks.activeBoundaryBeforeRestart = beforeRestart.blockedTargets;
  let blockedPauseError = '';
  try { await invoke(page, 'stop_tracking'); }
  catch (error) { blockedPauseError = error.message || String(error); }
  if (!/active boundaries/i.test(blockedPauseError)) throw new Error(`Pausing with an active boundary was not rejected as expected: ${blockedPauseError || 'no error'}`);
  report.checks.activeBoundaryPreventsPause = { rejected: true, message: blockedPauseError };

  await browser.close(); browser = null; page = null;
  child.kill();
  await Promise.race([once(child, 'exit'), new Promise(resolve => setTimeout(resolve, 5000))]);
  if (processAlive(originalPid)) throw new Error(`Could not terminate original QA process ${originalPid}.`);
  report.checks.originalProcessKilled = true;

  const recovered = await poll('watchdog relaunch and CDP recovery', async () => {
    const pids = findProcessByExecutable(executable, new Set([originalPid, ...preExistingPids]));
    for (const pid of pids) {
      if (!processAlive(pid)) continue;
      try {
        const attached = await connectForPid(pid, debugPort);
        return { done: true, value: { pid, ...attached } };
      } catch { /* startup may still be initializing */ }
    }
    return null;
  }, 45_000, 500);
  recoveredPid = recovered.pid;
  browser = recovered.browser;
  page = recovered.page;
  const recoveredWatchdogs = await poll('release watchdog for recovered QA process', async () => {
    const pids = findWatchdogs(recoveredPid);
    return pids.length ? { done: true, value: pids } : null;
  }, 10_000, 250);
  report.checks.recoveredWatchdog = { pids: recoveredWatchdogs };
  const recoveredVisibleWindows = nativeWindowsForPid(recoveredPid);
  const recoveredMainWindows = recoveredVisibleWindows.filter(window => window.title === 'FocusLock');
  report.checks.watchdogRecovery = { recoveredPid, originalPid, cdpReconnected: true,
    hidden: recoveredMainWindows.length === 0, visibleMainWindows: recoveredMainWindows,
    otherVisibleWindows: recoveredVisibleWindows.filter(window => window.title !== 'FocusLock'),
    processCommandLine: processCommandLine(recoveredPid) };
  if (!report.checks.watchdogRecovery.hidden) throw new Error('Watchdog recovery relaunched with a visible main window.');

  const recoveredSnapshot = await invoke(page, 'get_tracking_snapshot');
  const recoveredStatus = await invoke(page, 'get_tracker_status');
  if (!recoveredSnapshot.running || !recoveredStatus.running) throw new Error('Recovered process did not preserve active tracker state.');
  if (!recoveredSnapshot.blockedTargets?.appIds?.includes(BLOCKED_APP_ID)) throw new Error('Active QA app boundary was lost after process recovery.');
  report.checks.recoveredState = { trackerRunning: recoveredSnapshot.running, blockedTargets: recoveredSnapshot.blockedTargets, appBoundaryPreserved: true };
  await runChromeBridgeAcceptance();
  report.checks.passedLifecycle = true;
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.stack || error.message;
} finally {
  try {
    if (chromeWorkerRef) {
      try { await chromeWorkerRef.evaluate(() => { self.FocusLockDesktopBridge.stop(); return true; }); }
      catch { /* worker may already be stopped or Chrome may be exiting */ }
    }
    if (chromeContextQA) {
      try { await chromeContextQA.close(); } catch { /* Chromium may already be gone */ }
      chromeContextQA = null;
    }
    if (chromeProfileDir) {
      const safeProfile = path.resolve(chromeProfileDir);
      if (path.dirname(safeProfile).toLowerCase() !== path.resolve(outputDir).toLowerCase() ||
          !path.basename(safeProfile).startsWith(`chrome-profile-${RUN_ID}`)) {
        throw new Error('Refusing to clean a Chrome profile outside this QA run output directory.');
      }
      const pids = profileProcessIds(safeProfile, chromeExecutableLeaf);
      let rootPid = null;
      for (const pid of pids) {
        if (processCommandLine(pid).toLowerCase().includes(safeProfile.toLowerCase())) { rootPid = pid; break; }
      }
      if (chromePid && processAlive(chromePid) && processCommandLine(chromePid).toLowerCase().includes(safeProfile.toLowerCase())) {
        rootPid = chromePid;
      }
      if (!rootPid && pids.length) throw new Error('Could not prove a remaining Chromium process belongs to the disposable profile; refusing to terminate it.');
      if (rootPid) {
        const result = spawnSync('taskkill.exe', ['/PID', String(rootPid), '/T', '/F'], { encoding: 'utf8', windowsHide: true });
        if (result.status !== 0 && profileProcessIds(safeProfile).length) {
          throw new Error(`Could not stop the isolated Chrome process tree: ${result.stderr || result.stdout}`);
        }
        await poll('isolated Chrome process cleanup', async () => {
          const browserStillRunning = chromePid ? processAlive(chromePid) : false;
          return profileProcessIds(safeProfile, chromeExecutableLeaf).length || browserStillRunning ? null : { done: true, value: true };
        }, 10_000, 250);
      }
      if (profileProcessIds(safeProfile, chromeExecutableLeaf).length === 0) {
        await fs.rm(safeProfile, { recursive: true, force: true });
        report.checks.chromeProfileRemoved = true;
      }
      if (chromePid) await poll('Chrome native-host lease cleanup', async () => {
        const leases = await readLeaseFiles(appDataDir);
        return leases.some(entry => entry.value.browserPid === chromePid) ? null : { done: true, value: true };
      }, 8000, 250);
    }
    if (titleServer) {
      await new Promise(resolve => titleServer.close(() => resolve()));
      titleServer = null;
    }
    if (page && identifierVerified) {
      try { await invoke(page, 'set_blocked_targets', { targets: { appIds: [], domains: [] }, reasons: {} }); }
      catch (error) { report.checks.policyCleanupError = error.message; }
    }
    if (browser) { try { await browser.close(); } catch { /* process may already be gone */ } }
    // Arm the watchdog's per-parent stop marker before terminating each QA process.
    // This prevents cleanup from launching a replacement QA process.
    const pidToStop = recoveredPid && processAlive(recoveredPid) ? recoveredPid :
      (originalPid && processAlive(originalPid) ? originalPid : null);
    if (pidToStop && appDataDir) {
      const marker = path.join(appDataDir, `recovery-${pidToStop}.stop`);
      await fs.mkdir(appDataDir, { recursive: true });
      await fs.writeFile(marker, 'QA cleanup');
      stopQaPid(pidToStop);
      await poll(`watchdog stop marker consumption for PID ${pidToStop}`, async () =>
        await fs.access(marker).then(() => null, () => ({ done: true, value: true })), 5000, 250);
      report.checks.cleanupWatchdogDisarmed = { pid: pidToStop, markerConsumed: true };
    }
    if (registryBackup) {
      restoreRegistry(registryBackup.entries);
      assertRegistryMatches(registryBackup.entries);
      report.checks.registryRestored = true;
      registryBackup.restoredAt = new Date().toISOString();
      await writeJson(backupFile, registryBackup);
    }
  } catch (error) {
    report.status = 'cleanup_failed';
    report.cleanupError = error.stack || error.message;
  }
  report.finishedAt = new Date().toISOString();
  await writeJson(reportFile, report);
  console.log(`PC background QA report: ${reportFile}`);
  console.log(`Registry recovery backup: ${backupFile}`);
}

if (report.status !== 'passed') {
  console.error(report.error || report.cleanupError || 'PC background acceptance failed.');
  process.exitCode = 1;
} else console.log('PC background live acceptance passed.');
