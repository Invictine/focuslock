import { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import {
  formatFrogClock,
  normalizeFrogDomain,
  useFrogState,
  type FrogTask,
} from "./frog";
import { useVoidLauncherUi, voidLauncherAvailable } from "./voidLauncher";
import "./frog.css";

/** Apps and websites the picker offers, assembled by the Focus page from the
 * synced dashboard + known targets (the same sources the Boundaries rows use). */
export type FrogCatalog = {
  apps: { key: string; label: string }[];
  sites: { key: string; label: string }[];
};

function tauriAvailable(): boolean {
  return Boolean((window as any).__TAURI_INTERNALS__);
}

function wakeLabel(hour: number): string {
  return `${String(Math.min(23, Math.max(0, Math.trunc(hour)))).padStart(2, "0")}:00`;
}

// Small picker glyphs stay local to avoid importing App.tsx and creating a cycle.

function AppGlyph({ size = 14 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="3" y="4" width="18" height="13" rx="2" />
      <path d="M8 21h8m-4-4v4" />
    </svg>
  );
}

function SiteGlyph({ size = 14 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
    </svg>
  );
}

function CheckGlyph({ size = 14 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="m5 12 4 4L19 6" />
    </svg>
  );
}

function SearchGlyph({ size = 16 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m16 16 5 5" />
    </svg>
  );
}

export default function FrogCard({ catalog }: { catalog: FrogCatalog }) {
  const { state, actions } = useFrogState();
  const voidUi = useVoidLauncherUi();
  const [pickerOpen, setPickerOpen] = useState(false);
  const desktopLauncherAvailable = voidLauncherAvailable();

  useEffect(() => {
    const openFallbackPicker = () => setPickerOpen(true);
    window.addEventListener("focuslock:pick-frog", openFallbackPicker);
    return () => window.removeEventListener("focuslock:pick-frog", openFallbackPicker);
  }, []);

  // Feature off: no card at all (mirrors Android's FrogCard).
  if (!state.enabled) return null;

  const frog = state.frog;
  const trackedMinutes = Math.floor(state.trackedSeconds / 60);
  const wake = wakeLabel(state.wakeHour);

  const subtitle =
    state.phase === "not_armed"
      ? `Arms at ${wake}`
      : state.phase === "grace"
        ? `Grace · ${formatFrogClock(state.graceRemainingSeconds)}`
      : state.phase === "pick_frog"
        ? "Choose today's task"
        : state.phase === "complete"
          ? "Complete for today"
          : "In progress";

  return (
    <>
      <section className="frog-card" aria-label="Eat the frog">
        <div className="frog-card-head">
          <div className="frog-card-title">
            <h2>Eat the frog</h2>
            <p>{subtitle}</p>
          </div>
        </div>
        {frog && <p className="frog-task">{frog.title}{frog.projectName ? ` · ${frog.projectName}` : ""}</p>}
        {state.phase === "working" && (
          <p className="frog-task-meta">
            {trackedMinutes}m tracked{state.tickedOff ? " · ticked off" : ""}
          </p>
        )}
        {state.phase !== "complete" && <div className="frog-actions">
          <button
            type="button"
            className="frog-open-button"
            disabled={state.phase !== "not_armed" && desktopLauncherAvailable && (!voidUi.ready || voidUi.busy)}
            onClick={() => {
              if (desktopLauncherAvailable && state.phase !== "not_armed") window.dispatchEvent(new Event("focuslock:void-open"));
              else setPickerOpen(true);
            }}
            title={desktopLauncherAvailable && !voidUi.ready ? "Connecting to the Frog launcher…" : undefined}
          >
            {state.phase === "not_armed" ? "Prepare task" : desktopLauncherAvailable
              ? voidUi.busy ? "Opening Frog…" : state.phase === "pick_frog" ? "Pick task" : "Open Frog"
              : state.phase === "pick_frog" || !frog ? "Pick task" : "Open Frog"}
          </button>
        </div>}
        {desktopLauncherAvailable && voidUi.error && (
          <p className="frog-launcher-error" role="alert">{voidUi.error}</p>
        )}
      </section>

      {pickerOpen && (
        <FrogPickerDialog
          initial={frog}
          catalog={catalog}
          onClose={() => setPickerOpen(false)}
          onSave={(task) => {
            actions.selectFrog(task);
            setPickerOpen(false);
          }}
        />
      )}
    </>
  );
}

/**
 * Two-step picker: 1) the task title + optional project, 2) the allowlist
 * ("What do you need to get this done?"). Step 2 apps come from the synced
 * catalog plus a live `get_running_apps` read under Tauri; websites can be
 * picked from the Boundaries suggestions or typed in (normalized like the
 * Boundaries add-website dialog).
 */
function FrogPickerDialog({
  initial,
  catalog,
  onClose,
  onSave,
}: {
  initial: FrogTask | null;
  catalog: FrogCatalog;
  onClose: () => void;
  onSave: (task: FrogTask) => void;
}) {
  const [step, setStep] = useState<1 | 2>(1);
  const [title, setTitle] = useState(initial?.title || "");
  const [projectName, setProjectName] = useState(initial?.projectName || "");
  const [appKeys, setAppKeys] = useState<string[]>(initial?.neededAppIds || []);
  const [domainKeys, setDomainKeys] = useState<string[]>(initial?.neededDomains || []);
  const [appQuery, setAppQuery] = useState("");
  const [siteInput, setSiteInput] = useState("");
  const [siteError, setSiteError] = useState<string | null>(null);
  const [runningApps, setRunningApps] = useState<{ key: string; label: string }[]>([]);

  // Live "what is open right now" from the Windows tracker. Web preview simply
  // skips it and relies on the synced catalog.
  useEffect(() => {
    if (!tauriAvailable()) return;
    let cancelled = false;
    invoke<{ app_id?: string; app_name?: string }[]>("get_running_apps")
      .then((rows) => {
        if (cancelled) return;
        setRunningApps(
          (rows || [])
            .map((row) => ({
              key: String(row.app_id || "").toLowerCase(),
              label: String(row.app_name || row.app_id || ""),
            }))
            .filter((row) => row.key),
        );
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const appOptions = useMemo(() => {
    const map = new Map<string, string>();
    catalog.apps.forEach((app) => map.set(app.key, app.label));
    runningApps.forEach((app) => {
      if (!map.has(app.key)) map.set(app.key, app.label);
    });
    return [...map.entries()]
      .map(([key, label]) => ({ key, label }))
      .sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()));
  }, [catalog.apps, runningApps]);

  const filteredApps = useMemo(() => {
    const needle = appQuery.trim().toLowerCase();
    if (!needle) return appOptions;
    return appOptions.filter(
      (app) => app.label.toLowerCase().includes(needle) || app.key.includes(needle),
    );
  }, [appOptions, appQuery]);

  const siteSuggestions = useMemo(() => {
    const needle = siteInput.trim().toLowerCase();
    return catalog.sites
      .filter(
        (site) =>
          !domainKeys.includes(site.key) &&
          (!needle || site.key.includes(needle) || site.label.toLowerCase().includes(needle)),
      )
      .slice(0, 8);
  }, [catalog.sites, domainKeys, siteInput]);

  function toggleApp(key: string) {
    setAppKeys((current) =>
      current.includes(key) ? current.filter((entry) => entry !== key) : [...current, key],
    );
  }

  function addDomain() {
    const normalized = normalizeFrogDomain(siteInput);
    if (!normalized) {
      setSiteError("Enter a domain, like docs.google.com or a full URL.");
      return;
    }
    setDomainKeys((current) =>
      current.includes(normalized) ? current : [...current, normalized],
    );
    setSiteInput("");
    setSiteError(null);
  }

  function save() {
    const trimmed = title.trim();
    if (!trimmed) return;
    onSave({
      title: trimmed,
      ...(projectName.trim() ? { projectName: projectName.trim() } : {}),
      neededAppIds: appKeys,
      neededDomains: domainKeys,
    });
  }

  return (
    <div className="boundary-dialog-backdrop" role="presentation" onClick={onClose}>
      <div
        className="boundary-dialog group-dialog frog-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={initial ? "Change today's frog" : "Pick today's frog"}
        onClick={(event) => event.stopPropagation()}
      >
        <h2>{initial ? "Change today's frog" : "Pick today's frog"}</h2>

        {step === 1 ? (
          <>
            <p>What is today's frog? The one task that makes the rest of the day easier.</p>
            <label className="group-field">
              <span>Task</span>
              <input
                autoFocus
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && title.trim()) setStep(2);
                }}
                placeholder="e.g. Write the report intro"
              />
            </label>
            <label className="group-field">
              <span>Project (optional)</span>
              <input
                value={projectName}
                onChange={(event) => setProjectName(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && title.trim()) setStep(2);
                }}
                placeholder="e.g. Q3 report"
              />
            </label>
            <div className="dialog-actions">
              <button type="button" className="secondary-button" onClick={onClose}>
                Cancel
              </button>
              <button
                type="button"
                className="primary-button"
                disabled={!title.trim()}
                onClick={() => setStep(2)}
              >
                Next — what do you need?
              </button>
            </div>
          </>
        ) : (
          <>
            <p>
              What do you need to get this done? These apps and websites stay usable while every
              other boundary target is locked.
            </p>

            <div className="chip-row">
              {appKeys.map((key) => (
                <button
                  type="button"
                  className="member-chip"
                  key={`app:${key}`}
                  title="Remove from the allowlist"
                  onClick={() => toggleApp(key)}
                >
                  <AppGlyph />
                  {key} ×
                </button>
              ))}
              {domainKeys.map((key) => (
                <button
                  type="button"
                  className="member-chip website"
                  key={`site:${key}`}
                  title="Remove from the allowlist"
                  onClick={() =>
                    setDomainKeys((current) => current.filter((entry) => entry !== key))
                  }
                >
                  <SiteGlyph />
                  {key} ×
                </button>
              ))}
              {!appKeys.length && !domainKeys.length && (
                <span className="selection-hint">
                  Nothing picked yet — the frog lock blocks everything in Boundaries until you add
                  what the task needs.
                </span>
              )}
            </div>

            <div className="frog-picker-section">
              <p className="section-label">Apps</p>
              <label className="search">
                <SearchGlyph />
                <input
                  value={appQuery}
                  onChange={(event) => setAppQuery(event.target.value)}
                  placeholder="Search apps"
                  aria-label="Search apps"
                />
              </label>
              <div className="picker-list">
                {filteredApps.map((app) => {
                  const picked = appKeys.includes(app.key);
                  return (
                    <button
                      type="button"
                      className={`picker-row ${picked ? "picked" : ""}`}
                      key={app.key}
                      aria-pressed={picked}
                      onClick={() => toggleApp(app.key)}
                    >
                      <span className="picker-icon">
                        <AppGlyph />
                      </span>
                      <span className="picker-copy">
                        <strong>{app.label}</strong>
                        <small>{app.key}</small>
                      </span>
                      <span className={`pick-box ${picked ? "selected" : ""}`}>
                        <CheckGlyph />
                      </span>
                    </button>
                  );
                })}
                {!filteredApps.length && (
                  <p className="picker-empty">
                    No apps match. Turn on Windows tracking and open the app, or add it by hand
                    below.
                  </p>
                )}
              </div>
            </div>

            <div className="frog-picker-section">
              <p className="section-label">Websites</p>
              <div className="key-entry">
                <input
                  value={siteInput}
                  onChange={(event) => {
                    setSiteInput(event.target.value);
                    setSiteError(null);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") addDomain();
                  }}
                  placeholder="e.g. docs.google.com or a full URL"
                  aria-label="Add a website to the allowlist"
                  aria-invalid={Boolean(siteError)}
                />
                <button
                  type="button"
                  className="secondary-button"
                  disabled={!siteInput.trim()}
                  onClick={addDomain}
                >
                  Add
                </button>
              </div>
              {siteError && <p className="inline-error">{siteError}</p>}
              <p className="frog-hint">
                Websites added here open in any browser (Chrome, Edge, Firefox, …) while the frog
                lock is on — browsers stay reachable so domain rules alone do the blocking.
              </p>
              {siteSuggestions.length > 0 && (
                <div className="chip-row">
                  {siteSuggestions.map((site) => (
                    <button
                      type="button"
                      className="member-chip website"
                      key={site.key}
                      title={`Add ${site.key}`}
                      onClick={() =>
                        setDomainKeys((current) =>
                          current.includes(site.key) ? current : [...current, site.key],
                        )
                      }
                    >
                      + {site.label}
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div className="dialog-actions">
              <button type="button" className="secondary-button" onClick={() => setStep(1)}>
                Back
              </button>
              <button type="button" className="primary-button" onClick={save}>
                {initial ? "Save frog" : "Use this frog"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
