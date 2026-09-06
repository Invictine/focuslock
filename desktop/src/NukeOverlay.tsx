import { useAction, useMutation, useQuery } from "convex/react";
import { api } from "../../convex/_generated/api";
import { useEffect, useMemo, useState } from "react";

const MEDITATION_MS = 10 * 60 * 1000;

function fmt(ms: number) {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export default function NukeOverlay() {
  const nuke: any = useQuery((api as any).nuke?.getNuke);
  const activate = useMutation((api as any).nuke?.activate);
  const completeMeditation = useMutation((api as any).nuke?.completeMeditation);
  const checkin = useAction((api as any).nuke?.checkin);

  const [now, setNow] = useState(Date.now());
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [msgs, setMsgs] = useState<{ role: string; text: string }[]>([
    { role: "coach", text: "Reset complete. Well done staying with it. Now tell me — what are you going to do from here onwards? One concrete next action, for how long, and what will you NOT touch?" },
  ]);

  useEffect(() => {
    if (!nuke?.isActive) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [nuke?.isActive]);

  const startedAt = nuke?.startedAt ?? now;
  const remaining = Math.max(0, MEDITATION_MS - (now - startedAt));
  const medDone = useMemo(
    () => !!nuke?.meditationCompletedAt || (nuke?.isActive && remaining <= 0),
    [nuke, remaining]
  );

  useEffect(() => {
    if (nuke?.isActive && remaining <= 0 && !nuke?.meditationCompletedAt) {
      completeMeditation?.().catch(() => {});
    }
  }, [nuke?.isActive, remaining]);

  // Lock scroll while nuked.
  useEffect(() => {
    if (nuke?.isActive) {
      const prev = document.body.style.overflow;
      document.body.style.overflow = "hidden";
      return () => { document.body.style.overflow = prev; };
    }
  }, [nuke?.isActive]);

  if (!nuke?.isActive) return null;

  async function send() {
    const text = input.trim();
    if (text.length < 3 || sending) return;
    setSending(true);
    setInput("");
    const next = [...msgs, { role: "user", text }];
    setMsgs(next);
    try {
      const res: any = await checkin?.({ message: text, history: next.slice(-6) });
      setMsgs([...next, { role: "coach", text: res?.reply ?? "Stay with it — restate your plan." }]);
    } catch {
      setMsgs([...next, { role: "coach", text: "Coach unreachable — stay with the breath and restate: one action, minutes, what you avoid." }]);
    } finally {
      setSending(false);
    }
  }

  // Breathing phase label (4-7-8, 19s loop)
  const cycle = Math.floor((now - startedAt) / 1000) % 19;
  const breath = cycle <= 3 ? "Breathe in…" : cycle <= 10 ? "Hold…" : "Breathe out…";

  return (
    <div style={overlay}>
      <div style={card}>
        {!medDone ? (
          <>
            <div style={nukePill}>☢ NUKE ACTIVE — phone + PC locked</div>
            <div style={timer}>{fmt(remaining)}</div>
            <div style={{ opacity: 0.7 }}>Finish the 10-minute reset to continue. Leaving does not pause it.</div>
            <div style={breathCircle}>{breath}</div>
            <div style={{ fontSize: 13, opacity: 0.65 }}>4 in · 7 hold · 8 out — eyes soft, shoulders down.</div>
            <div style={progressOuter}>
              <div style={{ ...progressInner, width: `${Math.min(100, ((MEDITATION_MS - remaining) / MEDITATION_MS) * 100)}%` }} />
            </div>
          </>
        ) : (
          <>
            <h2 style={{ margin: "0 0 4px" }}>Reset complete — check in to unlock</h2>
            <p style={{ margin: "0 0 12px", opacity: 0.7, fontSize: 14 }}>
              Talk it through. The coach unlocks both devices only when the plan is concrete.
            </p>
            <div style={chat}>
              {msgs.map((m, i) => (
                <div key={i} style={{ ...bubble, alignSelf: m.role === "user" ? "flex-end" : "flex-start", background: m.role === "user" ? "#111" : "#f1f1f1", color: m.role === "user" ? "#fff" : "#111" }}>
                  {m.text}
                </div>
              ))}
              {sending && <div style={{ fontSize: 13, opacity: 0.6 }}>Coach is reading…</div>}
            </div>
            <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") send(); }}
                placeholder="e.g. I will write the report intro for 25 min, phone in drawer…"
                style={chatInput}
              />
              <button onClick={send} disabled={sending || input.trim().length < 3} style={sendBtn}>Send</button>
            </div>
            <div style={{ fontSize: 12, opacity: 0.55, marginTop: 8, textAlign: "center" }}>No bypass. Say the plan.</div>
          </>
        )}
      </div>
    </div>
  );
}

export function NukeButton() {
  const nuke: any = useQuery((api as any).nuke?.getNuke);
  const activate = useMutation((api as any).nuke?.activate);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  if (nuke?.isActive) return null;
  return (
    <div style={{ border: "1px solid #f3c1c1", background: "#fff5f5", borderRadius: 12, padding: 16 }}>
      <h3 style={{ margin: "0 0 4px" }}>☢ Nuke it</h3>
      <p style={{ margin: "0 0 12px", opacity: 0.75, fontSize: 14 }}>
        Completely blocks phone + PC until a 10-minute reset + coach check-in.
      </p>
      {!confirm ? (
        <button onClick={() => setConfirm(true)} style={dangerBtn}>NUKE everything</button>
      ) : (
        <div style={{ display: "flex", gap: 8 }}>
          <button
            disabled={busy}
            onClick={async () => { setBusy(true); try { await activate?.({}); } finally { setBusy(false); setConfirm(false); } }}
            style={dangerBtn}
          >
            {busy ? "Arming…" : "Yes, lock everything"}
          </button>
          <button onClick={() => setConfirm(false)} style={ghostBtn}>Cancel</button>
        </div>
      )}
    </div>
  );
}

const overlay: React.CSSProperties = { position: "fixed", inset: 0, zIndex: 9999, background: "#0d0d10", color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", padding: 20 };
const card: React.CSSProperties = { maxWidth: 560, width: "100%", background: "#17171c", border: "1px solid #333", borderRadius: 16, padding: 28, display: "flex", flexDirection: "column", alignItems: "center", gap: 12, textAlign: "center" };
const nukePill: React.CSSProperties = { background: "#3d1113", color: "#ff9d9d", borderRadius: 999, padding: "6px 14px", fontSize: 13, fontWeight: 700 };
const timer: React.CSSProperties = { fontSize: 64, fontWeight: 800, fontVariantNumeric: "tabular-nums" };
const breathCircle: React.CSSProperties = { width: 140, height: 140, borderRadius: "50%", background: "#26262e", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 18, fontWeight: 600 };
const progressOuter: React.CSSProperties = { width: "100%", height: 8, background: "#2a2a32", borderRadius: 999, overflow: "hidden" };
const progressInner: React.CSSProperties = { height: "100%", background: "#ff5d5d" };
const chat: React.CSSProperties = { display: "flex", flexDirection: "column", gap: 8, width: "100%", maxHeight: 320, overflowY: "auto", textAlign: "left" };
const bubble: React.CSSProperties = { maxWidth: "85%", padding: "10px 14px", borderRadius: 14, fontSize: 14, whiteSpace: "pre-wrap" };
const chatInput: React.CSSProperties = { flex: 1, padding: "10px 14px", borderRadius: 10, border: "1px solid #444", background: "#0f0f13", color: "#fff" };
const sendBtn: React.CSSProperties = { padding: "10px 18px", borderRadius: 10, border: "none", background: "#fff", color: "#111", cursor: "pointer", fontWeight: 700 };
const dangerBtn: React.CSSProperties = { padding: "10px 18px", borderRadius: 10, border: "none", background: "#c81e1e", color: "#fff", cursor: "pointer", fontWeight: 700 };
const ghostBtn: React.CSSProperties = { padding: "10px 18px", borderRadius: 10, border: "1px solid #ccc", background: "#fff", cursor: "pointer" };
