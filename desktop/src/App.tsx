import { Authenticated, Unauthenticated, useConvexAuth, useMutation, useQuery } from "convex/react";
import { SignInButton, SignUpButton, UserButton } from "@clerk/clerk-react";
import { api } from "../../convex/_generated/api";
import { useState } from "react";

function fmtDate(d = new Date()) {
  return d.toISOString().slice(0, 10);
}

export default function App() {
  return (
    <div style={styles.shell}>
      <header style={styles.header}>
        <div>
          <h1 style={{ margin: 0 }}>FocusLock Desktop</h1>
          <p style={{ margin: "4px 0 0", opacity: 0.7 }}>
            Auto-syncs with the Android app via Convex · same Clerk account = same data
          </p>
        </div>
        <Authenticated>
          <UserButton />
        </Authenticated>
      </header>

      {new URLSearchParams(window.location.search).has("demo") ? (
        <DashboardDemo />
      ) : (
        <>
          <Unauthenticated>
        <div style={styles.card}>
          <h2>Sign in to sync</h2>
          <p>Use the same Clerk account as on your phone.</p>
          <div style={{ display: "flex", gap: 12 }}>
            <SignInButton mode="modal">
              <button style={styles.primary}>Sign in</button>
            </SignInButton>
            <SignUpButton mode="modal">
              <button style={styles.secondary}>Sign up</button>
            </SignUpButton>
          </div>
          <p style={{ opacity: 0.65, fontSize: 13 }}>
            Note (Tauri): email/password works in-app. OAuth/social opens best in the browser
            dev build (`npm run dev`) — native OAuth in the Tauri window is a known Clerk
            limitation, see desktop/README.
          </p>
        </div>
      </Unauthenticated>

          <Authenticated>
            <Dashboard />
          </Authenticated>
        </>
      )}
    </div>
  );
}

function Dashboard() {
  const snap = useQuery(api.focus.getSnapshot);
  const saveState = useMutation(api.focus.saveState);
  const saveApps = useMutation(api.focus.saveBlockedApps);
  const saveSites = useMutation(api.focus.saveBlockedWebsites);
  const { isLoading } = useConvexAuth();
  const [busy, setBusy] = useState<string | null>(null);

  if (snap === undefined || isLoading) return <p>Syncing…</p>;
  if (snap === null) return <p>Couldn't load — check Convex + Clerk env.</p>;

  const s = snap.state;
  const balanceMin = Math.floor((s?.creditBalanceSeconds ?? 0) / 60);

  async function toggleApp(pkg: string) {
    setBusy("Saving…");
    try {
      const apps = snap!.apps.map((a: any) => ({
        packageName: a.packageName,
        appName: a.appName,
        isBlocked: a.packageName === pkg ? !a.isBlocked : a.isBlocked,
        category: a.category,
        specificShortsOnly: a.specificShortsOnly ?? false,
      }));
      await saveApps({ apps, updatedAt: Date.now() });
    } finally {
      setBusy(null);
    }
  }

  async function toggleSite(domain: string) {
    setBusy("Saving…");
    try {
      const sites = snap!.sites.map((w: any) => ({
        domain: w.domain,
        displayName: w.displayName,
        isBlocked: w.domain === domain ? !w.isBlocked : w.isBlocked,
        category: w.category,
        isCustom: w.isCustom ?? false,
      }));
      await saveSites({ sites, updatedAt: Date.now() });
    } finally {
      setBusy(null);
    }
  }

  async function earnDemo() {
    // Desktop can grant focus credit too (e.g. Pomodoro on PC) — merges via saveState LWW.
    setBusy("Saving…");
    try {
      await saveState({
        creditBalanceSeconds: (s?.creditBalanceSeconds ?? 0) + 25 * 60,
        totalWorkSecondsToday: (s?.totalWorkSecondsToday ?? 0) + 25 * 60,
        totalScrollSecondsToday: s?.totalScrollSecondsToday ?? 0,
        tasksCompletedToday: (s?.tasksCompletedToday ?? 0) + 1,
        lastResetDate: s?.lastResetDate ?? fmtDate(),
        updatedAt: Date.now(),
      });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div style={{ display: "grid", gap: 16 }}>
      {busy && <p style={{ opacity: 0.7 }}>{busy}</p>}
      <div style={styles.grid}>
        <div style={styles.card}>
          <h3>Credit balance</h3>
          <p style={styles.big}>{balanceMin} min</p>
          <button style={styles.primary} onClick={earnDemo}>
            +25 min focus (desktop Pomodoro)
          </button>
        </div>
        <div style={styles.card}>
          <h3>Today</h3>
          <p>Work: {Math.floor((s?.totalWorkSecondsToday ?? 0) / 60)} min</p>
          <p>Doomscroll: {Math.floor((s?.totalScrollSecondsToday ?? 0) / 60)} min</p>
          <p>Tasks: {s?.tasksCompletedToday ?? 0}</p>
        </div>
      </div>

      <div style={styles.card}>
        <h3>Blocked apps ({snap.apps.filter((a: any) => a.isBlocked).length} on)</h3>
        {snap.apps.length === 0 && <p>No data yet — open the Android app once while signed in.</p>}
        {snap.apps.map((a: any) => (
          <label key={a.packageName} style={styles.row}>
            <input type="checkbox" checked={a.isBlocked} onChange={() => toggleApp(a.packageName)} />
            <span>
              {a.appName} <small style={{ opacity: 0.6 }}>{a.packageName}</small>
            </span>
          </label>
        ))}
      </div>

      <div style={styles.card}>
        <h3>Blocked websites ({snap.sites.filter((w: any) => w.isBlocked).length} on)</h3>
        {snap.sites.map((w: any) => (
          <label key={w.domain} style={styles.row}>
            <input type="checkbox" checked={w.isBlocked} onChange={() => toggleSite(w.domain)} />
            <span>{w.displayName || w.domain}</span>
          </label>
        ))}
      </div>

      <div style={styles.card}>
        <h3>Work history ({snap.records.length})</h3>
        {snap.records.slice(0, 30).map((r: any) => (
          <div key={`${r.recordId}-${r.timestamp}`} style={styles.row}>
            <span>
              {r.title} · {r.durationMinutes}m (+{r.earnedMinutesCredited}m)
            </span>
            <small style={{ opacity: 0.6 }}>{new Date(r.timestamp).toLocaleString()}</small>
          </div>
        ))}
      </div>
    </div>
  );
}

/** Offline alpha showcase (?demo=1): same UI with sample data, no backend needed. */
function DashboardDemo() {
  const [apps, setApps] = useState([
    { packageName: "com.instagram.android", appName: "Instagram", isBlocked: true },
    { packageName: "com.google.android.youtube", appName: "YouTube", isBlocked: true },
    { packageName: "com.zhiliaoapp.musically", appName: "TikTok", isBlocked: true },
    { packageName: "com.reddit.frontpage", appName: "Reddit", isBlocked: false },
    { packageName: "com.discord", appName: "Discord", isBlocked: false },
  ]);
  const [sites, setSites] = useState([
    { domain: "youtube.com", displayName: "YouTube Web", isBlocked: true },
    { domain: "x.com", displayName: "X (Twitter) Web", isBlocked: true },
    { domain: "twitch.tv", displayName: "Twitch Web", isBlocked: false },
  ]);
  const [balanceMin, setBalanceMin] = useState(47);
  const records = [
    { title: "Ship FocusLock alpha", durationMinutes: 50, earned: 25, when: "Today, 09:12" },
    { title: "Design review", durationMinutes: 25, earned: 12, when: "Today, 08:20" },
    { title: "Inbox zero", durationMinutes: 15, earned: 7, when: "Yesterday, 17:40" },
  ];
  return (
    <div style={{ display: "grid", gap: 16 }}>
      <p style={{ background: "#fff8e1", border: "1px solid #ffe082", borderRadius: 8, padding: "8px 12px", margin: 0 }}>
        Demo preview with sample data — connect Convex + Clerk for live sync.
      </p>
      <div style={styles.grid}>
        <div style={styles.card}>
          <h3>Credit balance</h3>
          <p style={styles.big}>{balanceMin} min</p>
          <button style={styles.primary} onClick={() => setBalanceMin((m) => m + 25)}>
            +25 min focus (desktop Pomodoro)
          </button>
        </div>
        <div style={styles.card}>
          <h3>Today</h3>
          <p>Work: 90 min</p>
          <p>Doomscroll: 18 min</p>
          <p>Tasks: 3</p>
        </div>
      </div>
      <div style={styles.card}>
        <h3>Blocked apps ({apps.filter((a) => a.isBlocked).length} on)</h3>
        {apps.map((a) => (
          <label key={a.packageName} style={styles.row}>
            <input
              type="checkbox"
              checked={a.isBlocked}
              onChange={() => setApps((prev) => prev.map((x) => (x.packageName === a.packageName ? { ...x, isBlocked: !x.isBlocked } : x)))}
            />
            <span>{a.appName} <small style={{ opacity: 0.6 }}>{a.packageName}</small></span>
          </label>
        ))}
      </div>
      <div style={styles.card}>
        <h3>Blocked websites ({sites.filter((w) => w.isBlocked).length} on)</h3>
        {sites.map((w) => (
          <label key={w.domain} style={styles.row}>
            <input
              type="checkbox"
              checked={w.isBlocked}
              onChange={() => setSites((prev) => prev.map((x) => (x.domain === w.domain ? { ...x, isBlocked: !x.isBlocked } : x)))}
            />
            <span>{w.displayName}</span>
          </label>
        ))}
      </div>
      <div style={styles.card}>
        <h3>Work history ({records.length})</h3>
        {records.map((r) => (
          <div key={r.title} style={styles.row}>
            <span>{r.title} · {r.durationMinutes}m (+{r.earned}m)</span>
            <small style={{ opacity: 0.6 }}>{r.when}</small>
          </div>
        ))}
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  shell: { maxWidth: 860, margin: "0 auto", padding: 24, fontFamily: "system-ui, sans-serif" },
  header: { display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 20 },
  grid: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 },
  card: { border: "1px solid #e2e2e2", borderRadius: 12, padding: 16, background: "#fff" },
  big: { fontSize: 40, fontWeight: 700, margin: "8px 0" },
  row: { display: "flex", gap: 10, alignItems: "center", padding: "6px 0", borderTop: "1px solid #f0f0f0" },
  primary: { padding: "8px 16px", borderRadius: 8, border: "none", background: "#111", color: "#fff", cursor: "pointer" },
  secondary: { padding: "8px 16px", borderRadius: 8, border: "1px solid #ccc", background: "#fff", cursor: "pointer" },
};
