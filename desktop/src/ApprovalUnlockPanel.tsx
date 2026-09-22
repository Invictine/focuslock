import { useAction, useMutation, useQuery } from "convex/react";
import { useEffect, useState } from "react";
import { api } from "../../convex/_generated/api";
import { useStrictActive } from "./useStrictActive";

function formatEnd(timestamp?: number) {
  return timestamp ? new Date(timestamp).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "the active commitment";
}

/** Settings surface for the guardian approval flow. Guardian setup belongs before strict mode starts. */
export default function ApprovalUnlockPanel() {
  const guardian = useQuery(api.strictApproval.getGuardian, {});
  const state = useQuery(api.strictApproval.getApprovalState, {});
  const configure = useMutation(api.strictApproval.configureGuardian);
  const requestApproval = useAction(api.strictApproval.requestApprovalEmail);
  const [email, setEmail] = useState(guardian?.email ?? "");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { if (guardian?.email) setEmail(guardian.email); }, [guardian?.email]);

  const active = useStrictActive(Boolean(state?.strictMode), state?.strictEndsAt);
  const canRequest = active && Boolean(guardian?.email && state?.strictSessionId && state.strictEndsAt);

  async function saveGuardian() {
    setBusy(true); setError(null); setMessage(null);
    try {
      await configure({ email });
      setMessage("Guardian saved. They can approve future Strict Mode unlocks by email.");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not save guardian");
    } finally { setBusy(false); }
  }

  async function requestUnlock() {
    if (!state?.strictSessionId || !state.strictEndsAt) return;
    setBusy(true); setError(null); setMessage(null);
    try {
      const result = await requestApproval({ strictSessionId: state.strictSessionId, strictEndsAt: state.strictEndsAt });
      if (result.sent) setMessage("Approval email sent. The link expires in 30 minutes and applies only to this session.");
      else setError(result.reason ?? "The approval email could not be sent.");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The approval request could not be created");
    } finally { setBusy(false); }
  }

  if (state === undefined || guardian === undefined) return <section className="approval-unlock-panel" aria-busy="true"><p role="status">Loading email approval settings…</p></section>;

  return <section className="settings-card approval-unlock-panel" aria-labelledby="approval-unlock-title">
    <div className="settings-card-header">
      <div><p className="eyebrow">Guardian approval</p><h3 id="approval-unlock-title">Unlock by email</h3></div>
      <span className={`status-pill ${active ? "status-pill-warning" : "status-pill-muted"}`}>{active ? "Strict Mode active" : "Ready"}</span>
    </div>
    <p className="settings-card-copy">A trusted person can approve a one-time unlock for the current Strict Mode session. Permanent blocks are unchanged.</p>
    <label className="field-label" htmlFor="strict-guardian-email">Guardian email</label>
    <div className="inline-form">
      <input id="strict-guardian-email" className="text-input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="guardian@example.com" disabled={busy || active} />
      <button className="secondary-button" type="button" onClick={saveGuardian} disabled={busy || active || !email.trim()}>{busy ? "Saving…" : guardian ? "Update" : "Save"}</button>
    </div>
    {active && <p className="field-help">{guardian ? "Guardian settings are frozen until this commitment ends." : "No approval contact was configured. Add one after this commitment ends."}</p>}
    {active && <div className="approval-request-row"><div><strong>Need an unlock?</strong><p className="field-help">Current commitment ends {formatEnd(state?.strictEndsAt)}.</p></div><button className="primary-button" type="button" onClick={requestUnlock} disabled={busy || !canRequest}>{busy ? "Sending…" : "Request approval"}</button></div>}
    {!active && guardian && <p className="field-help">Guardian is configured and will receive requests while Strict Mode is active.</p>}
    {message && <p className="success-text" role="status">{message}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
  </section>;
}
