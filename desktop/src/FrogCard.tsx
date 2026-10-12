import { useEffect, useRef, useState } from "react";
import {
  formatFrogClock,
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

function wakeLabel(hour: number): string {
  return `${String(Math.min(23, Math.max(0, Math.trunc(hour)))).padStart(2, "0")}:00`;
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
  if (!state.enabled) return <section className="frog-card" aria-label="Eat the Frog setup">
    <div className="frog-card-head"><div className="frog-card-title"><h2>Eat the Frog</h2><p>{state.emergencyDisabled ? "Emergency exit used · disabled on this PC" : "Choose your essentials before enabling this PC"}</p></div></div>
    <button type="button" className="frog-open-button" onClick={() => window.dispatchEvent(new Event("focuslock:frog-setup"))}>{state.setupComplete ? "Enable Eat the Frog" : "Set up Eat the Frog"}</button>
    <p className="frog-hint">Ctrl+Shift+Alt+F12 exits Frog and keeps it disabled until you manually enable it again.</p>
  </section>;

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
          catalog={{ apps: state.essentialAppIds.map((key) => ({ key, label: catalog.apps.find((app) => app.key.toLowerCase() === key)?.label || key })), sites: state.essentialDomains.map((key) => ({ key, label: key })) }}
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

/** The daily task uses the persistent essentials chosen during setup. */
function FrogPickerDialog({ initial, catalog, onClose, onSave }: {
  initial: FrogTask | null;
  catalog: FrogCatalog;
  onClose: () => void;
  onSave: (task: FrogTask) => void;
}) {
  const [title, setTitle] = useState(initial?.title || "");
  const [projectName, setProjectName] = useState(initial?.projectName || "");
  const dialogRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialogRef.current?.querySelector<HTMLInputElement>("input")?.focus();
    return () => previous?.focus();
  }, []);
  return <div className="frog-setup-backdrop" onKeyDown={(event) => {
    if (event.key === "Escape") { event.preventDefault(); onClose(); }
    if (event.key === "Tab") {
      const nodes = [...(dialogRef.current?.querySelectorAll<HTMLElement>('input, button:not([disabled])') || [])];
      if (event.shiftKey && document.activeElement === nodes[0]) { event.preventDefault(); nodes[nodes.length - 1]?.focus(); }
      else if (!event.shiftKey && document.activeElement === nodes[nodes.length - 1]) { event.preventDefault(); nodes[0]?.focus(); }
    }
  }}><div className="frog-dialog" ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="frog-task-title">
    <h2 id="frog-task-title">Pick one task</h2>
    <label className="frog-picker-section">Today's priority<input value={title} maxLength={200} onChange={(event) => setTitle(event.target.value)} /></label>
    <label className="frog-picker-section">Project (optional)<input value={projectName} maxLength={160} onChange={(event) => setProjectName(event.target.value)} /></label>
    <p className="frog-hint">Your essential apps and Chrome websites stay available while you work. Review them in Settings.</p>
    <div className="frog-setup-chips">{catalog.apps.map((app) => <span key={app.key} className="frog-setup-chip">{app.label}</span>)}{catalog.sites.map((site) => <span key={site.key} className="frog-setup-chip">{site.label}</span>)}</div>
    <div className="dialog-actions"><button type="button" className="secondary-button" onClick={onClose}>Cancel</button><button type="button" className="primary-button" disabled={!title.trim()} onClick={() => onSave({ title: title.trim(), ...(projectName.trim() ? { projectName: projectName.trim() } : {}), neededAppIds: catalog.apps.map((app) => app.key), neededDomains: catalog.sites.map((site) => site.key) })}>Use this task</button></div>
  </div></div>;
}
