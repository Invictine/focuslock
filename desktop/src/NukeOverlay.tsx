import { useAction, useMutation, useQuery } from "convex/react";
import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../convex/_generated/api";
import "./nuke.css";

const MEDITATION_MS = 10 * 60 * 1000;

function fmt(ms: number) {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

export default function NukeOverlay() {
  const nuke: any = useQuery((api as any).nuke?.getNuke);
  const completeMeditation = useMutation((api as any).nuke?.completeMeditation);
  const checkin = useAction((api as any).nuke?.checkin);
  const [now, setNow] = useState(Date.now());
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [messages, setMessages] = useState<{ role: string; text: string }[]>([
    { role: "coach", text: "Reset complete. What will you do next, for how long, and what will you avoid?" },
  ]);

  useEffect(() => {
    if (!nuke?.isActive) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [nuke?.isActive]);

  const startedAt = nuke?.startedAt ?? now;
  const remaining = Math.max(0, MEDITATION_MS - (now - startedAt));
  const meditationDone = useMemo(
    () => Boolean(nuke?.meditationCompletedAt) || Boolean(nuke?.isActive && remaining <= 0),
    [nuke, remaining],
  );

  useEffect(() => {
    if (nuke?.isActive && remaining <= 0 && !nuke?.meditationCompletedAt) {
      completeMeditation?.().catch(() => undefined);
    }
  }, [completeMeditation, nuke?.isActive, nuke?.meditationCompletedAt, remaining]);

  useEffect(() => {
    if (!nuke?.isActive) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = previous; };
  }, [nuke?.isActive]);

  if (!nuke?.isActive) return null;

  async function send() {
    const text = input.trim();
    if (text.length < 3 || sending) return;
    setSending(true);
    setInput("");
    const next = [...messages, { role: "user", text }];
    setMessages(next);
    try {
      const response: any = await checkin?.({ message: text, history: next.slice(-6) });
      setMessages([...next, { role: "coach", text: response?.reply ?? "Make the plan more concrete." }]);
    } catch {
      setMessages([...next, { role: "coach", text: "The coach is unavailable. Restate one action, its duration, and what you will avoid." }]);
    } finally {
      setSending(false);
    }
  }

  const cycle = Math.floor((now - startedAt) / 1000) % 19;
  const breath = cycle <= 3 ? "Breathe in" : cycle <= 10 ? "Hold" : "Breathe out";
  const progress = Math.min(100, ((MEDITATION_MS - remaining) / MEDITATION_MS) * 100);

  return (
    <div className="nuke-overlay" role="dialog" aria-modal="true" aria-labelledby="nuke-title">
      <section className="nuke-card">
        {!meditationDone ? (
          <>
            <p className="nuke-label">Nuke active · Android and Windows locked</p>
            <h1 id="nuke-title" className="nuke-timer">{fmt(remaining)}</h1>
            <p className="nuke-copy">Complete the ten-minute reset to continue. Leaving does not pause it.</p>
            <div className="breath-circle" aria-live="polite">{breath}</div>
            <p className="nuke-hint">4 in · 7 hold · 8 out</p>
            <div className="nuke-progress" aria-label={`${Math.round(progress)}% complete`}><span style={{ width: `${progress}%` }} /></div>
          </>
        ) : (
          <>
            <p className="eyebrow">Final step</p>
            <h1 id="nuke-title">Check in to unlock</h1>
            <p className="nuke-copy">The plan needs one action, a duration, and a clear boundary.</p>
            <div className="nuke-chat" aria-live="polite">
              {messages.map((message, index) => <p key={index} className={`nuke-message ${message.role}`}>{message.text}</p>)}
              {sending && <p className="nuke-reading">Coach is reading…</p>}
            </div>
            <div className="nuke-compose">
              <input value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") void send(); }} placeholder="Write the report intro for 25 minutes; phone in drawer" aria-label="Your next-action plan" />
              <button onClick={() => void send()} disabled={sending || input.trim().length < 3}>Send</button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}

export function NukeButton() {
  const nuke: any = useQuery((api as any).nuke?.getNuke);
  const activate = useMutation((api as any).nuke?.activate);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  if (nuke?.isActive) return null;

  async function arm() {
    setBusy(true);
    try { await activate?.({}); }
    finally { setBusy(false); setConfirming(false); }
  }

  return (
    <div className="nuke-action">
      <div><h3>Nuke everything</h3><p>Lock Android and Windows until the reset and check-in are complete.</p></div>
      {!confirming ? <button className="danger-button" onClick={() => setConfirming(true)}>Start reset</button> : (
        <div className="confirm-actions">
          <button className="danger-button" disabled={busy} onClick={() => void arm()}>{busy ? "Arming…" : "Lock both devices"}</button>
          <button className="secondary-button" onClick={() => setConfirming(false)}>Cancel</button>
        </div>
      )}
    </div>
  );
}
