import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { normalizeFrogDomain } from "./frog";
import "./frog.css";

export type FrogSetupCatalog = {
  apps?: Array<{ appId?: string; app_id?: string; name?: string; appName?: string; app_name?: string }>;
  domains?: string[];
};

type Props = {
  catalog?: FrogSetupCatalog;
  initialApps?: string[];
  initialDomains?: string[];
  onSave: (appIds: string[], domains: string[]) => void | Promise<void>;
  onCancel?: () => void;
  pending?: boolean;
  error?: string | null;
  blockedAppIds?: string[];
  blockedDomains?: string[];
};

export default function FrogSetup({ catalog, initialApps = [], initialDomains = [], onSave, onCancel, pending = false, error, blockedAppIds = [], blockedDomains = [] }: Props) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const [apps, setApps] = useState<string[]>(initialApps);
  const [domains, setDomains] = useState<string[]>(initialDomains);
  const [appInput, setAppInput] = useState("");
  const [siteInput, setSiteInput] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);
  const [running, setRunning] = useState<FrogSetupCatalog["apps"]>(catalog?.apps || []);
  useEffect(() => { void invoke<Array<{ appId?: string; appName?: string; app_id?: string; app_name?: string }>>("get_running_apps").then(setRunning).catch(() => {}); }, []);
  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    const dialog = dialogRef.current;
    if (!dialog) return;
    const focusable = () => [...dialog.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), [tabindex="0"]')];
    focusable()[0]?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const nodes = focusable(); if (!nodes.length) return;
      const index = nodes.indexOf(document.activeElement as HTMLElement);
      if (event.shiftKey && (index <= 0 || index < 0)) { event.preventDefault(); nodes[nodes.length - 1].focus(); }
      else if (!event.shiftKey && index === nodes.length - 1) { event.preventDefault(); nodes[0].focus(); }
    };
    dialog.addEventListener("keydown", onKey);
    return () => { dialog.removeEventListener("keydown", onKey); previousFocus?.focus(); };
  }, []);
  const blockedApps = new Set(blockedAppIds.map(id => id.toLowerCase()));
  const unavailableBrowsers = new Set(["msedge.exe", "firefox.exe", "brave.exe", "vivaldi.exe", "opera.exe", "opera_gx.exe", "arc.exe"]);
  const protectedApps = new Set(["explorer.exe", "dwm.exe", "winlogon.exe", "csrss.exe", "sihost.exe", "runtimebroker.exe", "applicationframehost.exe", "textinputhost.exe", "searchhost.exe", "startmenuexperiencehost.exe"]);
  const appUnavailable = (id: string) => blockedApps.has(id.toLowerCase()) || unavailableBrowsers.has(id.toLowerCase()) || protectedApps.has(id.toLowerCase()) || id.toLowerCase().includes("focuslock");
  const siteUnavailable = (host: string) => blockedDomains.some(raw => { const blocked = raw.toLowerCase(); return host === blocked || host.endsWith(`.${blocked}`) || blocked.endsWith(`.${host}`); });
  const appRows = useMemo(() => [...(running || []), ...(catalog?.apps || [])].map((a) => ({ id: a.appId || a.app_id || "", name: a.name || a.appName || a.app_name || a.appId || a.app_id || "" })).filter((a, i, all) => a.id && all.findIndex((x) => x.id.toLowerCase() === a.id.toLowerCase()) === i).filter((a) => { const id = a.id.toLowerCase(); return !appUnavailable(id); }), [catalog?.apps, running, blockedAppIds]);
  function addApp(value: string) { const id = value.trim(); if (!/^([\w.-]+)\.exe$/i.test(id) || /[\\/\*\?]/.test(id) || appUnavailable(id)) { setLocalError("Enter an executable name such as study.exe; boundary apps cannot be essential."); return; } if (!apps.some((x) => x.toLowerCase() === id.toLowerCase())) setApps([...apps, id]); setAppInput(""); }
  function addSite(value: string) { const site = normalizeFrogDomain(value); if (!site || siteUnavailable(site)) { setLocalError("Enter a valid, non-boundary website domain."); return; } if (!domains.includes(site)) setDomains([...domains, site]); setSiteInput(""); setLocalError(null); }
  async function save() { const safeApps = apps.filter((x) => !appUnavailable(x)); const safeDomains = domains.filter((x) => !siteUnavailable(x)); if (!safeApps.length && !safeDomains.length) { setLocalError("Choose at least one essential app or website."); return; } setLocalError(null); try { await onSave(safeApps, safeDomains); } catch (e) { setLocalError(e instanceof Error ? e.message : String(e)); } }
  return <div className="frog-setup-backdrop" onKeyDown={(e) => { if (e.key === "Escape" && !pending) onCancel?.(); }}><div ref={dialogRef} tabIndex={-1} className="frog-setup-dialog frog-dialog" role="dialog" aria-modal="true" aria-labelledby="frog-setup-title">
    <h2 id="frog-setup-title">Set up Eat the Frog</h2>
    <p>Choose the apps and websites you need for focused work. Only these essentials stay available while Frog is active. Websites open in Chrome.</p>
    <p className="frog-hint">Open an app once to find it here, or add its executable name. Boundary apps cannot be essential.</p>
    <fieldset className="frog-picker-section"><legend className="section-label">Essential Windows apps</legend>
      <div className="frog-setup-chips">{appRows.map((app) => <label key={app.id}><input disabled={pending || blockedAppIds.includes(app.id)} type="checkbox" checked={apps.some((x) => x.toLowerCase() === app.id.toLowerCase())} onChange={() => setApps(apps.some((x) => x.toLowerCase() === app.id.toLowerCase()) ? apps.filter((x) => x.toLowerCase() !== app.id.toLowerCase()) : [...apps, app.id])} /> {app.name}</label>)}{apps.filter((x) => !appRows.some((a) => a.id.toLowerCase() === x.toLowerCase())).map((app) => <button disabled={pending} className="frog-setup-chip" type="button" key={app} onClick={() => setApps(apps.filter((x) => x !== app))}>{app} ×</button>)}</div>
      <div className="frog-setup-add"><input disabled={pending} aria-label="App executable" placeholder="example.exe" value={appInput} onChange={(e) => setAppInput(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addApp(appInput); } }} /><button disabled={pending} type="button" className="secondary-button" onClick={() => addApp(appInput)}>Add app</button></div>
    </fieldset>
    <fieldset className="frog-picker-section"><legend className="section-label">Essential websites</legend>
      <div className="frog-setup-chips">{domains.map((site) => <button disabled={pending} type="button" className="frog-setup-chip" key={site} onClick={() => setDomains(domains.filter((x) => x !== site))}>{site} ×</button>)}</div>
      <div className="frog-setup-add"><input disabled={pending} aria-label="Website domain" placeholder="docs.google.com" value={siteInput} onChange={(e) => setSiteInput(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addSite(siteInput); } }} /><button disabled={pending} type="button" className="secondary-button" onClick={() => addSite(siteInput)}>Add website</button></div>
    </fieldset>
    {(localError || error) && <p className="inline-error" role="alert">{localError || error}</p>}
    <div className="frog-setup-actions"><button type="button" className="secondary-button" onClick={onCancel} disabled={pending}>Cancel</button><button type="button" className="primary-button" onClick={save} disabled={pending}>{pending ? "Saving…" : "Enable Eat the Frog"}</button></div>
  </div></div>;
}
