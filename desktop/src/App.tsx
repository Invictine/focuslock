import { Authenticated, Unauthenticated, useConvexAuth, useMutation, useQuery } from "convex/react";
import { SignInButton, SignUpButton, UserButton } from "@clerk/clerk-react";
import { useEffect, useMemo, useState } from "react";
import { api as convexApi } from "../../convex/_generated/api";
import "./styles.css";

const api: any = convexApi;

/* ---------------- types ---------------- */
type AppItem = { packageName: string; appName: string; isBlocked: boolean; category: string; specificShortsOnly?: boolean };
type SiteItem = { domain: string; displayName: string; isBlocked: boolean; category: string; isCustom?: boolean };
type Limit = { targetKind: string; targetKey: string; label?: string; dailyLimitMinutes?: number; sessionLimitMinutes?: number; isBlockedNow?: boolean };
type Schedule = { scheduleId: string; label: string; targetKind: string; targetKey: string; days: number[]; startMinute: number; endMinute: number; isEnabled: boolean };
type UsageDay = { date: string; totalScreenMinutes: number; appCount: number; topApps: { packageName: string; appName: string; minutes: number }[] };
type WorkRec = { recordId?: string; title: string; durationMinutes: number; timestamp: number; earnedMinutesCredited?: number; earned?: number; source?: string };
type Prefs = { strictMode: boolean; weeklyReport: boolean; globalDailyCapMinutes?: number };

/* ---------------- helpers ---------------- */
function todayStr(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}
function fmtDur(mins: number): string {
  if (mins <= 0) return "0m";
  const h = Math.floor(mins / 60);
  const m = Math.round(mins % 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
function fmtClock(mins: number): string {
  const h = Math.floor(mins / 60).toString().padStart(2, "0");
  const m = (mins % 60).toString().padStart(2, "0");
  return `${h}:${m}`;
}
function parseClock(v: string): number {
  const [h, m] = v.split(":").map((x) => parseInt(x || "0", 10));
  return (h || 0) * 60 + (m || 0);
}
function parseRangeFromUrl(): { start: number; end: number } {
  const q = new URLSearchParams(window.location.search);
  const hash = window.location.hash || "";
  const hm = hash.match(/dateRange=([^&]+)/);
  const raw = q.get("dateRange") || (hm ? decodeURIComponent(hm[1]) : null);
  const now = new Date();
  const endOfToday = new Date(now);
  endOfToday.setHours(23, 59, 59, 999);
  if (raw) {
    const parts = raw.split(",").map((x) => parseInt(x.trim(), 10));
    if (parts.length === 2 && parts.every((n) => Number.isFinite(n))) {
      return { start: Math.min(parts[0], parts[1]), end: Math.max(parts[0], parts[1]) };
    }
  }
  const start = new Date(now);
  start.setDate(start.getDate() - 6);
  start.setHours(0, 0, 0, 0);
  return { start: start.getTime(), end: endOfToday.getTime() };
}
function writeRangeToHash(start: number, end: number) {
  const base = window.location.hash.split("?")[0] || "#/";
  const next = `${base}?dateRange=${start},${end}`;
  history.replaceState(null, "", window.location.pathname + window.location.search + next);
}
function downloadCsv(filename: string, rows: (string | number)[][]) {
  const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\n");
  const blob = new Blob([csv], { type: "text/csv" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}
function uid(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}
function loadLocal<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}
function saveLocal(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch { /* ignore */ }
}

/* ---------------- demo seed (StayFree-like) ---------------- */
const DEMO_APPS: AppItem[] = [
  { packageName: "com.instagram.android", appName: "Instagram", isBlocked: true, category: "Social", specificShortsOnly: false },
  { packageName: "com.google.android.youtube", appName: "YouTube", isBlocked: true, category: "Video", specificShortsOnly: true },
  { packageName: "com.zhiliaoapp.musically", appName: "TikTok", isBlocked: true, category: "Social" },
  { packageName: "com.reddit.frontpage", appName: "Reddit", isBlocked: false, category: "Social" },
  { packageName: "com.discord", appName: "Discord", isBlocked: false, category: "Social" },
  { packageName: "com.twitter.android", appName: "X (Twitter)", isBlocked: true, category: "Social" },
  { packageName: "com.netflix.mediaclient", appName: "Netflix", isBlocked: false, category: "Video" },
  { packageName: "com.supercell.clashofclans", appName: "Clash of Clans", isBlocked: false, category: "Games" },
];
const DEMO_SITES: SiteItem[] = [
  { domain: "youtube.com", displayName: "YouTube Web", isBlocked: true, category: "Video" },
  { domain: "x.com", displayName: "X (Twitter) Web", isBlocked: true, category: "Social" },
  { domain: "twitch.tv", displayName: "Twitch Web", isBlocked: false, category: "Video" },
  { domain: "reddit.com", displayName: "Reddit Web", isBlocked: false, category: "Social" },
  { domain: "netflix.com", displayName: "Netflix Web", isBlocked: false, category: "Video" },
];
function demoUsage(): UsageDay[] {
  const out: UsageDay[] = [];
  const mins = [212, 178, 245, 160, 190, 132, 96];
  for (let i = 6; i >= 0; i--) {
    const total = mins[6 - i] + (i === 0 ? 0 : 0);
    out.push({
      date: todayStr(-i),
      totalScreenMinutes: total,
      appCount: 14,
      topApps: [
        { packageName: "com.google.android.youtube", appName: "YouTube", minutes: Math.round(total * 0.3) },
        { packageName: "com.instagram.android", appName: "Instagram", minutes: Math.round(total * 0.22) },
        { packageName: "com.zhiliaoapp.musically", appName: "TikTok", minutes: Math.round(total * 0.16) },
        { packageName: "com.twitter.android", appName: "X", minutes: Math.round(total * 0.1) },
      ],
    });
  }
  return out;
}

/* ================= App root ================= */
export default function App() {
  const [theme, setTheme] = useState(() => localStorage.getItem("fl-theme") || "dark");
  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
    localStorage.setItem("fl-theme", theme);
  }, [theme]);
  const isDemo = new URLSearchParams(window.location.search).has("demo");

  return (
    <div className="fl-shell">
      <nav className="fl-nav">
        <a href="#top" className="fl-brand" style={{ textDecoration: "none" }}>
          <span className="fl-logo">🔒</span> FocusLock
        </a>
        <div className="fl-links">
          <a href="#blocking">Blocking</a>
          <a href="#analytics">Analytics</a>
          <a href="#filtering">Shorts filter</a>
          <a href="#faq">FAQ</a>
          <a href={isDemo ? window.location.pathname : `${window.location.pathname}?demo=1`}>Live demo</a>
        </div>
        <div className="fl-nav-cta">
          <button className="btn btn-sm btn-ghost" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>
            {theme === "dark" ? "☀️" : "🌙"}
          </button>
          <Authenticated><UserButton /></Authenticated>
          {isDemo && <a className="btn btn-sm btn-primary" href={window.location.pathname}>Get started</a>}
        </div>
      </nav>

      {isDemo ? (
        <DemoDashboard />
      ) : (
        <>
          <Unauthenticated>
            <LandingPage />
            <div className="card sign-card">
              <h2 style={{ margin: "0 0 8px" }}>Sign in to open your dashboard</h2>
              <p className="muted">Same Clerk account as your phone — block lists, limits, schedules and credits sync.</p>
              <div className="row-flex" style={{ justifyContent: "center", marginTop: 14 }}>
                <SignInButton mode="modal"><span className="btn btn-primary">Sign in</span></SignInButton>
                <SignUpButton mode="modal"><span className="btn">Sign up</span></SignUpButton>
                <a className="btn btn-ghost" href={`${window.location.pathname}?demo=1`}>Try demo</a>
              </div>
            </div>
          </Unauthenticated>
          <Authenticated><LiveDashboard /></Authenticated>
        </>
      )}
      <footer>
        <span>© 2026 FocusLock — earn your scroll.</span>
        <span>Android + Desktop sync via Convex ·ही StayFree-style dashboard with date ranges, limits & schedules.</span>
      </footer>
    </div>
  );
}

/* ================= Landing page ================= */
function LandingPage() {
  return (
    <div id="top">
      <div className="fl-hero">
        <div>
          <span className="pill"><span className="dot" /> Android + Desktop · cross-device screen-time control</span>
          <h1>Own your time, <span className="grad">everywhere.</span></h1>
          <p className="fl-sub">
            FocusLock pairs StayFree-style blocking — Block Now, schedules, daily &amp; session limits,
            per-app analytics with date ranges — with a twist StayFree doesn't have:
            <strong> focused work earns leisure time</strong>. Finish a Pomodoro or TickTick task, unlock guilt-free scrolling.
          </p>
          <div className="fl-hero-cta">
            <a className="btn btn-primary" href="?demo=1">⚡ Open live demo dashboard</a>
            <a className="btn" href="#blocking">See how blocking works</a>
          </div>
          <div className="fl-proof">
            <span><strong>Earn-by-work</strong> credits</span>
            <span><strong>Shorts-only</strong> filtering</span>
            <span><strong>Cross-device</strong> sync</span>
            <span><strong>CSV</strong> export</span>
          </div>
          <div className="quotes">
            <blockquote>“StayFree-grade stats, but the earn-to-unlock loop finally stuck.” — alpha tester</blockquote>
            <blockquote>“Schedules + session limits killed my 2am YouTube spiral.” — alpha tester</blockquote>
          </div>
        </div>
        <div className="mock" aria-hidden>
          <div className="mock-bar"><i /><i /><i /><span className="muted" style={{ fontSize: 12 }}>focuslock — dashboard #/?dateRange=…</span></div>
          <div className="mock-body">
            <div className="mock-kpis">
              <div className="mock-kpi"><small>Screen time</small><br /><b>2h 12m</b></div>
              <div className="mock-kpi"><small>Balance</small><br /><b style={{ color: "#5dffa4" }}>47m</b></div>
              <div className="mock-kpi"><small>Work</small><br /><b>90m</b></div>
            </div>
            <div className="bars">
              {[38, 55, 42, 70, 52, 88, 64].map((h, i) => (
                <span key={i} style={{ height: `${h}%`, opacity: i === 5 ? 1 : 0.65 }} />
              ))}
            </div>
            {[["Instagram", true], ["YouTube · Shorts only", true], ["Reddit", false]].map(([n, on]) => (
              <div className="mock-row" key={n as string}><span>{n}</span><span className={`toggle ${on ? "on" : ""}`} /></div>
            ))}
          </div>
        </div>
      </div>

      <div className="section" id="blocking">
        <h2>Block like StayFree. Earn like FocusLock.</h2>
        <p className="lead">Every StayFree blocking mode, plus work-to-scroll credits so limits feel rewarding instead of punishing.</p>
        <div className="grid4">
          {[
            ["⛔", "Block Now", "One tap instantly locks distracting apps & sites. Strict mode prevents disabling mid-block."],
            ["🗓️", "Schedules", "Mon–Fri 9–5, school nights, weekends — recurring auto-blocks per app, site, category or everything."],
            ["⏳", "Daily limits", "Cap each app/site/category per day (e.g. 30m TikTok). Over-limit rows glow red in reports."],
            ["⏱️", "Session limits", "Cap each sitting (e.g. 10m per Reels session) with a cooldown before you can reopen."],
          ].map(([icon, t, d]) => (
            <div className="card" key={t}><div className="icon">{icon}</div><h3>{t}</h3><p>{d}</p></div>
          ))}
        </div>
        <div className="grid2">
          <div className="card"><div className="icon">💰</div><h3>Earn-by-work credits (only in FocusLock)</h3><p>25 min of focus ≈ +12 min leisure at your ratio. Pomodoro timer, manual log, or TickTick tasks — all synced phone ↔ desktop.</p></div>
          <div className="card" id="filtering"><div className="icon">🎬</div><h3>Shorts / Reels-only filtering</h3><p>Keep YouTube for tutorials, kill Shorts. Per-app “Shorts only” switch mirrors StayFree's in-app filtering without nuking the whole app.</p></div>
        </div>
      </div>

      <div className="section" id="analytics">
        <h2>Analytics you'll actually open</h2>
        <p className="lead">StayFree-compatible <code>#/?dateRange=start,end</code> URLs, presets (today · 7d · 30d), per-app &amp; per-site bars, limit badges, focus history and one-click CSV export.</p>
        <div className="grid3">
          <div className="card"><div className="icon">📊</div><h3>Date-range reports</h3><p>Shareable ranges, daily totals, 7-day trend chart, weekly averages and top offenders.</p></div>
          <div className="card"><div className="icon">📱</div><h3>Per-app & per-site</h3><p>Search, category filter, usage vs daily-limit progress, session-limit and shorts-only flags inline.</p></div>
          <div className="card"><div className="icon">📤</div><h3>Export & focus log</h3><p>CSV export of usage, work and limits. Pomodoro sessions land in history and earn credit automatically.</p></div>
        </div>
      </div>

      <div className="section">
        <h2>How it works</h2>
        <div className="steps">
          {[["Pick your poisons", "Choose apps & sites. Set daily caps, session caps and nightly/weekday schedules."], ["Work to earn", "Run the focus timer or complete TickTick tasks. Each block mints leisure credit."], ["Scroll guilt-free", "Credit auto-unlocks your apps. Empty balance = gentle block screen, not shame."]].map(([t, d]) => (
            <div className="card step" key={t}><h3>{t}</h3><p>{d}</p></div>
          ))}
        </div>
        <table className="cmp">
          <thead><tr><th></th><th>StayFree</th><th>FocusLock</th></tr></thead>
          <tbody>
            {[["Block now / schedules / daily / session limits", "✓", "✓"], ["Date-range dashboard + export", "✓", "✓"], ["Shorts-only filtering", "✓", "✓"], ["Work-to-scroll credits + TickTick", "—", "✓"], ["Phone ↔ desktop auto-sync", "paid", "✓"]].map(([f, s, fl]) => (
              <tr key={f}><td>{f}</td><td>{s}</td><td><strong>{fl}</strong></td></tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="section" id="faq">
        <h2>FAQ</h2>
        {[
          ["Is this a StayFree replacement?", "It's StayFree-parity for the dashboard (ranges, limits, schedules, shorts filter, export) plus an earn-by-work loop. Import your block list mentally in 2 minutes — search, toggle, set caps."],
          ["How do credits work?", "Focused minutes × your work ratio + task bonus = leisure minutes. Example: 25 min focus at 2:1 earns ~12 min. The balance gates your blocked apps."],
          ["Does it sync phone ↔ desktop?", "Yes — same Clerk account. Block lists, limits, schedules, prefs and work history sync via Convex. The demo runs fully offline in localStorage."],
          ["What permissions does Android need?", "Accessibility (blocking), Usage Access (screen-time stats) and Notifications (TickTick task detection). The dashboard shows a setup pill until all three are granted."],
        ].map(([q, a]) => (
          <details className="faq" key={q}><summary>{q}</summary><p>{a}</p></details>
        ))}
        <div className="cta-big">
          <h2 style={{ marginTop: 0 }}>Stop renting your attention.</h2>
          <p className="muted">Open the demo, set one daily limit, run one Pomodoro.</p>
          <div className="row-flex" style={{ justifyContent: "center", marginTop: 14 }}>
            <a className="btn btn-primary" href="?demo=1">Open the dashboard</a>
            <a className="btn" href="#top">Back to top</a>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ================= Live (Convex) dashboard ================= */
function LiveDashboard() {
  const snap: any = useQuery(api.focus.getDashboard, {});
  const { isLoading } = useConvexAuth();
  if (snap === undefined || isLoading) return <p className="muted">Syncing…</p>;
  if (snap === null) return <DemoDashboard notice="Couldn't reach Convex — showing offline demo data." />;
  return <SharedDashboard live={snap} />;
}

/* ================= Demo dashboard (offline, localStorage) ================= */
function DemoDashboard({ notice }: { notice?: string }) {
  const [apps, setApps] = useState<AppItem[]>(() => loadLocal("fl-apps", DEMO_APPS));
  const [sites, setSites] = useState<SiteItem[]>(() => loadLocal("fl-sites", DEMO_SITES));
  const [limits, setLimits] = useState<Limit[]>(() => loadLocal("fl-limits", [
    { targetKind: "app", targetKey: "com.google.android.youtube", label: "YouTube", dailyLimitMinutes: 45, sessionLimitMinutes: 10 },
    { targetKind: "app", targetKey: "com.instagram.android", label: "Instagram", dailyLimitMinutes: 30, sessionLimitMinutes: 10 },
    { targetKind: "app", targetKey: "com.zhiliaoapp.musically", label: "TikTok", dailyLimitMinutes: 30 },
  ] as Limit[]));
  const [schedules, setSchedules] = useState<Schedule[]>(() => loadLocal("fl-schedules", [
    { scheduleId: "s1", label: "Deep work", targetKind: "all", targetKey: "*", days: [1, 2, 3, 4, 5], startMinute: 540, endMinute: 1020, isEnabled: true },
    { scheduleId: "s2", label: "No doomscroll nights", targetKind: "category", targetKey: "Social", days: [0, 1, 2, 3, 4, 5, 6], startMinute: 1320, endMinute: 1439, isEnabled: true },
  ] as Schedule[]));
  const [usage] = useState<UsageDay[]>(demoUsage);
  const [records, setRecords] = useState<WorkRec[]>(() => loadLocal("fl-records", [
    { recordId: "r1", title: "Ship FocusLock alpha", durationMinutes: 50, timestamp: Date.now() - 36e5, earnedMinutesCredited: 25, source: "TICKTICK" },
    { recordId: "r2", title: "Design review", durationMinutes: 25, timestamp: Date.now() - 72e5, earnedMinutesCredited: 12, source: "TIMER" },
  ] as WorkRec[]));
  const [balanceMin, setBalanceMin] = useState(() => loadLocal("fl-balance", 47));
  const [prefs, setPrefs] = useState<Prefs>(() => loadLocal("fl-prefs", { strictMode: false, weeklyReport: true, globalDailyCapMinutes: 180 }));

  useEffect(() => saveLocal("fl-apps", apps), [apps]);
  useEffect(() => saveLocal("fl-sites", sites), [sites]);
  useEffect(() => saveLocal("fl-limits", limits), [limits]);
  useEffect(() => saveLocal("fl-schedules", schedules), [schedules]);
  useEffect(() => saveLocal("fl-records", records), [records]);
  useEffect(() => saveLocal("fl-balance", balanceMin), [balanceMin]);
  useEffect(() => saveLocal("fl-prefs", prefs), [prefs]);

  return (
    <SharedDashboard
      demo
      notice={notice}
      apps={apps} sites={sites} limits={limits} schedules={schedules} usage={usage} records={records}
      balanceMin={balanceMin} prefs={prefs}
      onToggleApp={(pkg) => setApps((p) => p.map((a) => (a.packageName === pkg ? { ...a, isBlocked: !a.isBlocked } : a)))}
      onToggleSite={(d) => setSites((p) => p.map((s) => (s.domain === d ? { ...s, isBlocked: !s.isBlocked } : s)))}
      onToggleShorts={(pkg) => setApps((p) => p.map((a) => (a.packageName === pkg ? { ...a, specificShortsOnly: !a.specificShortsOnly } : a)))}
      onAddSite={(d) => setSites((p) => (p.some((s) => s.domain === d) ? p : [...p, { domain: d, displayName: d, isBlocked: true, category: "Custom", isCustom: true }]))}
      onSaveLimits={setLimits}
      onSaveSchedules={setSchedules}
      onSavePrefs={setPrefs}
      onFocusComplete={(mins, title) => {
        const earned = Math.max(1, Math.round(mins / 2));
        setBalanceMin((b) => b + earned);
        setRecords((r) => [{ recordId: uid("timer"), title, durationMinutes: mins, timestamp: Date.now(), earnedMinutesCredited: earned, source: "TIMER" }, ...r]);
      }}
      onEarnDemo={() => setBalanceMin((b) => b + 25)}
    />
  );
}

/* ================= Shared dashboard ================= */
function SharedDashboard(props: {
  live?: any; demo?: boolean; notice?: string;
  apps?: AppItem[]; sites?: SiteItem[]; limits?: Limit[]; schedules?: Schedule[];
  usage?: UsageDay[]; records?: WorkRec[]; balanceMin?: number; prefs?: Prefs;
  onToggleApp?: (k: string) => void; onToggleSite?: (k: string) => void; onToggleShorts?: (k: string) => void; onAddSite?: (d: string) => void;
  onSaveLimits?: (l: Limit[]) => void; onSaveSchedules?: (s: Schedule[]) => void; onSavePrefs?: (p: Prefs) => void;
  onFocusComplete?: (mins: number, title: string) => void; onEarnDemo?: () => void;
}) {
  const live = props.live;
  const saveApps = useMutation(api.focus.saveBlockedApps);
  const saveSites = useMutation(api.focus.saveBlockedWebsites);
  const saveState = useMutation(api.focus.saveState);
  const saveLimitsM = useMutation(api.focus.saveAppLimits);
  const saveSchedM = useMutation(api.focus.saveSchedules);
  const savePrefsM = useMutation(api.focus.savePrefs);
  const logSessionM = useMutation(api.focus.logFocusSession);

  const [tab, setTab] = useState("overview");
  const [range, setRange] = useState(parseRangeFromUrl);
  const [preset, setPreset] = useState("7d");
  const [query, setQuery] = useState("");
  const [catFilter, setCatFilter] = useState("all");
  const [busy, setBusy] = useState<string | null>(null);
  const [newDomain, setNewDomain] = useState("");

  useEffect(() => { writeRangeToHash(range.start, range.end); }, [range]);
  useEffect(() => {
    const onHash = () => setRange(parseRangeFromUrl());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // Live-backed editable copies (demo passes them directly as props)
  const [liveApps, setLiveApps] = useState<AppItem[] | null>(null);
  const [liveSites, setLiveSites] = useState<SiteItem[] | null>(null);
  const [liveLimits, setLiveLimits] = useState<Limit[]>([]);
  const [liveSched, setLiveSched] = useState<Schedule[]>([]);
  const [livePrefs, setLivePrefs] = useState<Prefs>({ strictMode: false, weeklyReport: true });
  const [liveBalance, setLiveBalance] = useState(0);
  useEffect(() => {
    if (!live) return;
    setLiveApps(live.apps ?? []);
    setLiveSites(live.sites ?? []);
    setLiveLimits(live.limits ?? []);
    setLiveSched(live.schedules ?? []);
    setLivePrefs(live.prefs ?? { strictMode: false, weeklyReport: true });
    setLiveBalance(Math.floor(((live.state?.creditBalanceSeconds ?? 0) as number) / 60));
  }, [live]);

  const apps: AppItem[] = props.apps ?? liveApps ?? [];
  const sites: SiteItem[] = props.sites ?? liveSites ?? [];
  const limits: Limit[] = props.limits ?? liveLimits;
  const schedules: Schedule[] = props.schedules ?? liveSched;
  const prefs: Prefs = props.prefs ?? livePrefs;
  const balanceMin: number = props.balanceMin ?? liveBalance;
  const usage: UsageDay[] = useMemo(() => {
    const u: UsageDay[] = props.usage ?? live?.usage ?? [];
    if (u.length > 0) return u;
    return demoUsage();
  }, [props.usage, live]);
  const records: WorkRec[] = props.records ?? live?.records ?? [];

  const inRange = (ts: number) => ts >= range.start && ts <= range.end;
  const recordsInRange = records.filter((r) => inRange(r.timestamp || Date.now()));
  const usageInRange = usage.filter((u) => {
    const t = new Date(u.date + "T12:00:00").getTime();
    return t >= new Date(new Date(range.start).toISOString().slice(0, 10)).getTime() - 864e5 &&
      t <= range.end;
  });
  const totalScreen = usageInRange.reduce((a, u) => a + u.totalScreenMinutes, 0);
  const workMin = recordsInRange.reduce((a, r) => a + (r.durationMinutes || 0), 0);
  const earnedMin = recordsInRange.reduce((a, r) => a + (r.earnedMinutesCredited ?? r.earned ?? 0), 0);
  const blockedApps = apps.filter((a) => a.isBlocked).length;
  const blockedSites = sites.filter((s) => s.isBlocked).length;

  const usageByApp = useMemo(() => {
    const m = new Map<string, { name: string; minutes: number }>();
    for (const d of usageInRange) for (const t of d.topApps || []) {
      const e = m.get(t.packageName) || { name: t.appName, minutes: 0 };
      e.minutes += t.minutes;
      m.set(t.packageName, e);
    }
    return [...m.entries()].map(([pkg, v]) => ({ pkg, ...v })).sort((a, b) => b.minutes - a.minutes);
  }, [usageInRange]);

  const limitOf = (kind: string, key: string) => limits.find((l) => l.targetKind === kind && l.targetKey === key);
  const categories = useMemo(() => ["all", ...Array.from(new Set([...apps.map((a) => a.category), ...sites.map((s) => s.category)].filter(Boolean)))], [apps, sites]);
  const filteredApps = apps.filter((a) => (catFilter === "all" || a.category === catFilter) && (a.appName.toLowerCase().includes(query.toLowerCase()) || a.packageName.includes(query)));
  const filteredSites = sites.filter((s) => (catFilter === "all" || s.category === catFilter) && ((s.displayName || s.domain).toLowerCase().includes(query.toLowerCase()) || s.domain.includes(query)));

  async function persistAll(nextLimits = limits, nextSched = schedules, nextPrefs = prefs) {
    if (props.demo || !live) {
      props.onSaveLimits?.(nextLimits);
      props.onSaveSchedules?.(nextSched);
      props.onSavePrefs?.(nextPrefs);
      return;
    }
    setBusy("Saving…");
    try {
      await saveLimitsM({ limits: nextLimits.map(({ targetKind, targetKey, label, dailyLimitMinutes, sessionLimitMinutes, isBlockedNow }) => ({ targetKind, targetKey, label, dailyLimitMinutes, sessionLimitMinutes, isBlockedNow })), updatedAt: Date.now() });
      await saveSchedM({ schedules: nextSched, updatedAt: Date.now() });
      await savePrefsM({ strictMode: !!nextPrefs.strictMode, weeklyReport: !!nextPrefs.weeklyReport, globalDailyCapMinutes: nextPrefs.globalDailyCapMinutes, updatedAt: Date.now() });
      setLiveLimits(nextLimits); setLiveSched(nextSched); setLivePrefs(nextPrefs);
    } finally { setBusy(null); }
  }

  async function toggleShorts(pkg: string) {
    if (props.onToggleShorts) return props.onToggleShorts(pkg);
    const next = (liveApps ?? []).map((a) => (a.packageName === pkg ? { ...a, specificShortsOnly: !a.specificShortsOnly } : a));
    setLiveApps(next); setBusy("Saving…");
    try { await saveApps({ apps: next.map((a) => ({ packageName: a.packageName, appName: a.appName, isBlocked: a.isBlocked, category: a.category || "Other", specificShortsOnly: !!a.specificShortsOnly })), updatedAt: Date.now() }); }
    finally { setBusy(null); }
  }
  async function toggleApp(pkg: string) {
    if (props.onToggleApp) return props.onToggleApp(pkg);
    const next = (liveApps ?? []).map((a) => (a.packageName === pkg ? { ...a, isBlocked: !a.isBlocked } : a));
    setLiveApps(next); setBusy("Saving…");
    try { await saveApps({ apps: next.map((a) => ({ packageName: a.packageName, appName: a.appName, isBlocked: a.isBlocked, category: a.category || "Other", specificShortsOnly: !!a.specificShortsOnly })), updatedAt: Date.now() }); }
    finally { setBusy(null); }
  }
  async function toggleSite(domain: string) {
    if (props.onToggleSite) return props.onToggleSite(domain);
    const next = (liveSites ?? []).map((s) => (s.domain === domain ? { ...s, isBlocked: !s.isBlocked } : s));
    setLiveSites(next); setBusy("Saving…");
    try { await saveSites({ sites: next.map((s) => ({ domain: s.domain, displayName: s.displayName || s.domain, isBlocked: s.isBlocked, category: s.category || "Other", isCustom: !!s.isCustom })), updatedAt: Date.now() }); }
    finally { setBusy(null); }
  }
  async function earnDemo() {
    if (props.onEarnDemo) return props.onEarnDemo();
    setBusy("Saving…");
    try {
      const s = live?.state;
      await saveState({
        creditBalanceSeconds: ((s?.creditBalanceSeconds ?? 0) as number) + 25 * 60,
        totalWorkSecondsToday: ((s?.totalWorkSecondsToday ?? 0) as number) + 25 * 60,
        totalScrollSecondsToday: (s?.totalScrollSecondsToday ?? 0) as number,
        tasksCompletedToday: ((s?.tasksCompletedToday ?? 0) as number) + 1,
        lastResetDate: (s?.lastResetDate as string) ?? todayStr(),
        updatedAt: Date.now(),
      });
      await logSessionM({ sessionId: uid("sess"), title: "Desktop Pomodoro", durationMinutes: 25, timestamp: Date.now(), source: "DESKTOP", earnedMinutesCredited: 12 });
      setLiveBalance((b) => b + 25);
    } finally { setBusy(null); }
  }
  function focusComplete(mins: number, title: string) {
    if (props.onFocusComplete) return props.onFocusComplete(mins, title);
    const earned = Math.max(1, Math.round(mins / 2));
    const s = live?.state;
    saveState({
      creditBalanceSeconds: ((s?.creditBalanceSeconds ?? 0) as number) + earned * 60,
      totalWorkSecondsToday: ((s?.totalWorkSecondsToday ?? 0) as number) + mins * 60,
      totalScrollSecondsToday: (s?.totalScrollSecondsToday ?? 0) as number,
      tasksCompletedToday: ((s?.tasksCompletedToday ?? 0) as number) + 1,
      lastResetDate: (s?.lastResetDate as string) ?? todayStr(),
      updatedAt: Date.now(),
    }).catch(() => {});
    logSessionM({ sessionId: uid("sess"), title, durationMinutes: mins, timestamp: Date.now(), source: "DESKTOP", earnedMinutesCredited: earned }).catch(() => {});
    setLiveBalance((b) => b + earned);
  }

  function applyPreset(p: string) {
    setPreset(p);
    const now = new Date();
    const eod = new Date(now); eod.setHours(23, 59, 59, 999);
    const sod = new Date(now); sod.setHours(0, 0, 0, 0);
    if (p === "today") setRange({ start: sod.getTime(), end: eod.getTime() });
    else if (p === "yesterday") { const s = new Date(sod); s.setDate(s.getDate() - 1); const e = new Date(eod); e.setDate(e.getDate() - 1); setRange({ start: s.getTime(), end: e.getTime() }); }
    else if (p === "7d") { const s = new Date(sod); s.setDate(s.getDate() - 6); setRange({ start: s.getTime(), end: eod.getTime() }); }
    else if (p === "30d") { const s = new Date(sod); s.setDate(s.getDate() - 29); setRange({ start: s.getTime(), end: eod.getTime() }); }
  }

  function exportAll() {
    downloadCsv(`focuslock-report-${todayStr()}.csv`, [
      ["section", "name", "minutes", "extra"],
      ...usageInRange.flatMap((u) => (u.topApps || []).map((t) => ["usage", `${u.date} ${t.appName}`, t.minutes, t.packageName] as (string | number)[])),
      ...recordsInRange.map((r) => ["work", r.title, r.durationMinutes, new Date(r.timestamp).toISOString()] as (string | number)[]),
      ...limits.map((l) => ["limit", `${l.targetKind}:${l.targetKey}`, l.dailyLimitMinutes ?? 0, `session ${l.sessionLimitMinutes ?? "-"} blockNow ${l.isBlockedNow ? "yes" : "no"}`] as (string | number)[]),
    ]);
  }

  const maxDay = Math.max(1, ...usageInRange.map((u) => u.totalScreenMinutes));

  return (
    <div style={{ paddingTop: 8 }}>
      {props.demo && <div className="demo-banner">Demo preview with sample data — sign in for live Convex sync. {props.notice ? ` ${props.notice}` : ""}</div>}
      <div className="dash-top">
        <div>
          <h1 style={{ margin: "6px 0 2px", letterSpacing: "-0.03em" }}>{props.demo ? "Dashboard demo" : "Dashboard"}</h1>
          <span className="muted" style={{ fontSize: 13 }}>
            {new Date(range.start).toLocaleDateString()} → {new Date(range.end).toLocaleDateString()} · shareable <code>#/?dateRange={range.start},{range.end}</code>
          </span>
        </div>
        <div className="range">
          {[["today", "Today"], ["yesterday", "Yesterday"], ["7d", "7D"], ["30d", "30D"]].map(([v, l]) => (
            <button key={v} className={`chip ${preset === v ? "active" : ""}`} onClick={() => applyPreset(v)}>{l}</button>
          ))}
          <input className="input" type="date" style={{ width: 140 }} value={new Date(range.start).toISOString().slice(0, 10)} onChange={(e) => { setPreset("custom"); const d = new Date(e.target.value + "T00:00:00"); if (!isNaN(d.getTime())) setRange((r) => ({ ...r, start: d.getTime() })); }} />
          <input className="input" type="date" style={{ width: 140 }} value={new Date(range.end).toISOString().slice(0, 10)} onChange={(e) => { setPreset("custom"); const d = new Date(e.target.value + "T23:59:59"); if (!isNaN(d.getTime())) setRange((r) => ({ ...r, end: d.getTime() })); }} />
          <button className="btn btn-sm" onClick={exportAll}>⬇ Export CSV</button>
        </div>
      </div>
      {busy && <p className="muted">{busy}</p>}

      <div className="kpis">
        {[
          ["Screen time", fmtDur(totalScreen), `${usageInRange.length} day(s) in range`],
          ["Balance", `${balanceMin}m`, "unlocks blocked apps"],
          ["Focused work", fmtDur(workMin), `+${earnedMin}m earned`],
          ["Blocked", `${blockedApps + blockedSites} on`, `${blockedApps} apps · ${blockedSites} sites`],
          ["Schedules", `${schedules.filter((s) => s.isEnabled).length} on`, `${schedules.length} total · strict ${prefs.strictMode ? "on" : "off"}`],
        ].map(([t, v, s]) => (
          <div className="kpi" key={t}><small>{t}</small><div className="v">{v}</div><div className="s">{s}</div></div>
        ))}
      </div>

      <div className="tabs">
        {[["overview", "📊 Overview"], ["apps", "📱 Apps & sites"], ["limits", "🗓️ Limits & schedules"], ["focus", "⏱️ Focus"], ["reports", "📤 Reports"]].map(([v, l]) => (
          <button key={v} className={`chip ${tab === v ? "active" : ""}`} onClick={() => setTab(v)}>{l}</button>
        ))}
        <span style={{ flex: 1 }} />
        <button className="btn btn-sm btn-primary" onClick={earnDemo}>+25 min focus</button>
      </div>

      {tab === "overview" && (
        <div className="grid2">
          <div className="chart">
            <h3 style={{ marginTop: 0 }}>Trend <span className="muted" style={{ fontWeight: 400 }}>· min/day</span></h3>
            <div className="bars" style={{ height: 150 }}>
              {usageInRange.slice(-14).map((u) => (
                <span key={u.date} title={`${u.date}: ${u.totalScreenMinutes}m`} style={{ height: `${Math.max(6, (u.totalScreenMinutes / maxDay) * 100)}%` }} />
              ))}
              {usageInRange.length === 0 && <span className="muted">No usage in this range.</span>}
            </div>
            <div className="row-flex muted" style={{ fontSize: 12 }}>{usageInRange.slice(-14).map((u) => <span key={u.date} style={{ flex: 1, textAlign: "center" }}>{u.date.slice(5)}</span>)}</div>
          </div>
          <div className="chart">
            <h3 style={{ marginTop: 0 }}>Top apps in range</h3>
            {usageByApp.slice(0, 6).map((a) => {
              const lim = limitOf("app", a.pkg);
              const over = lim?.dailyLimitMinutes ? a.minutes > lim.dailyLimitMinutes * Math.max(1, usageInRange.length) / Math.max(1, usageInRange.length) && a.minutes / Math.max(1, usageInRange.length) > (lim.dailyLimitMinutes || Infinity) : false;
              return (
                <div className="bar-row" key={a.pkg}>
                  <span>{a.name}</span>
                  <div className="bar-track"><div className={`bar-fill ${over ? "warn" : ""}`} style={{ width: `${Math.min(100, (a.minutes / Math.max(1, usageByApp[0]?.minutes || 1)) * 100)}%` }} /></div>
                  <span className="muted">{fmtDur(a.minutes)} {lim?.dailyLimitMinutes ? <span className={`limit-badge ${over ? "over" : ""}`}>≤{lim.dailyLimitMinutes}m/d</span> : null}</span>
                </div>
              );
            })}
            {usageByApp.length === 0 && <p className="muted">No per-app data in range.</p>}
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Recent work ({recordsInRange.length})</h3>
            {recordsInRange.slice(0, 8).map((r, i) => (
              <div className="mock-row" key={(r.recordId || i) + String(r.timestamp)} style={{ marginBottom: 8 }}>
                <span>{r.title} · {r.durationMinutes}m (+{r.earnedMinutesCredited ?? r.earned ?? 0}m)</span>
                <small className="muted">{new Date(r.timestamp).toLocaleString()}</small>
              </div>
            ))}
            {recordsInRange.length === 0 && <p className="muted">No work logged in this range — run the Focus timer.</p>}
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Quick actions</h3>
            <div className="row-flex">
              <button className="btn btn-sm" onClick={() => setTab("limits")}>⛔ Block now / schedule</button>
              <button className="btn btn-sm" onClick={() => setTab("focus")}>⏱️ Start Pomodoro</button>
              <button className="btn btn-sm" onClick={() => setTab("apps")}>🎬 Shorts-only filter</button>
              <button className="btn btn-sm" onClick={exportAll}>⬇ Export</button>
            </div>
            <p className="muted" style={{ fontSize: 13 }}>Tip: copy the URL — the <code>dateRange</code> hash works exactly like StayFree's dashboard link.</p>
          </div>
        </div>
      )}

      {tab === "apps" && (
        <div className="card">
          <div className="row-flex">
            <input className="input" placeholder="Search apps & sites…" value={query} onChange={(e) => setQuery(e.target.value)} style={{ maxWidth: 260 }} />
            <select className="input" style={{ maxWidth: 180 }} value={catFilter} onChange={(e) => setCatFilter(e.target.value)}>
              {categories.map((c) => <option key={c} value={c}>{c === "all" ? "All categories" : c}</option>)}
            </select>
            <span className="muted">{filteredApps.length} apps · {filteredSites.length} sites</span>
          </div>
          <h3>Apps</h3>
          {filteredApps.map((a) => {
            const lim = limitOf("app", a.packageName);
            return (
              <div className="mock-row" key={a.packageName} style={{ marginBottom: 8, flexWrap: "wrap", gap: 10 }}>
                <span style={{ minWidth: 200 }}><strong>{a.appName}</strong> <small className="muted">{a.category} · {a.packageName}</small><br />
                  <label className="muted" style={{ fontSize: 12 }}><input type="checkbox" checked={!!a.specificShortsOnly} onChange={() => toggleShorts(a.packageName)} /> Shorts-only block</label>
                </span>
                <span className="row-flex">
                  <label className="muted" style={{ fontSize: 12 }}>Daily <input className="input" type="number" min={0} max={1440} placeholder="—" value={lim?.dailyLimitMinutes ?? ""} style={{ width: 70 }} onChange={(e) => upsertLimit({ targetKind: "app", targetKey: a.packageName, label: a.appName, dailyLimitMinutes: numOrUndef(e.target.value), sessionLimitMinutes: lim?.sessionLimitMinutes, isBlockedNow: lim?.isBlockedNow }, persistAll, limits)} />m</label>
                  <label className="muted" style={{ fontSize: 12 }}>Session <input className="input" type="number" min={0} max={480} placeholder="—" value={lim?.sessionLimitMinutes ?? ""} style={{ width: 70 }} onChange={(e) => upsertLimit({ targetKind: "app", targetKey: a.packageName, label: a.appName, dailyLimitMinutes: lim?.dailyLimitMinutes, sessionLimitMinutes: numOrUndef(e.target.value), isBlockedNow: lim?.isBlockedNow }, persistAll, limits)} />m</label>
                  <button className={`toggle ${a.isBlocked ? "on" : ""}`} onClick={() => toggleApp(a.packageName)} aria-label={`block ${a.appName}`} />
                </span>
              </div>
            );
          })}
          <h3>Websites</h3>
          <div className="row-flex" style={{ marginBottom: 10 }}>
            <input className="input" placeholder="Add domain e.g. youtube.com" value={newDomain} onChange={(e) => setNewDomain(e.target.value)} style={{ maxWidth: 260 }} />
            <button className="btn btn-sm" onClick={() => addSite(newDomain, sites, live, props, setLiveSites, saveSites, setBusy, setNewDomain)}>+ Add site</button>
          </div>
          {filteredSites.map((s) => {
            const lim = limitOf("site", s.domain);
            return (
              <div className="mock-row" key={s.domain} style={{ marginBottom: 8, flexWrap: "wrap", gap: 10 }}>
                <span style={{ minWidth: 200 }}><strong>{s.displayName || s.domain}</strong> <small className="muted">{s.category} · {s.domain}</small></span>
                <span className="row-flex">
                  <label className="muted" style={{ fontSize: 12 }}>Daily <input className="input" type="number" min={0} max={1440} placeholder="—" value={lim?.dailyLimitMinutes ?? ""} style={{ width: 70 }} onChange={(e) => upsertLimit({ targetKind: "site", targetKey: s.domain, label: s.displayName || s.domain, dailyLimitMinutes: numOrUndef(e.target.value), sessionLimitMinutes: lim?.sessionLimitMinutes, isBlockedNow: lim?.isBlockedNow }, persistAll, limits)} />m</label>
                  <button className={`toggle ${s.isBlocked ? "on" : ""}`} onClick={() => toggleSite(s.domain)} aria-label={`block ${s.domain}`} />
                </span>
              </div>
            );
          })}
        </div>
      )}

      {tab === "limits" && (
        <LimitsTab limits={limits} schedules={schedules} prefs={prefs} apps={apps} sites={sites} persistAll={persistAll} setBusy={setBusy} />
      )}

      {tab === "focus" && <FocusTab onComplete={focusComplete} records={records} />}

      {tab === "reports" && (
        <div className="grid2">
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Range summary</h3>
            <p>Total screen time: <strong>{fmtDur(totalScreen)}</strong> ({usageInRange.length || 1} day(s), avg {fmtDur(Math.round(totalScreen / Math.max(1, usageInRange.length)))}/day)</p>
            <p>Focused work: <strong>{fmtDur(workMin)}</strong> · earned <strong>+{earnedMin}m</strong> · sessions <strong>{recordsInRange.length}</strong></p>
            <p className="muted">Global daily cap: {prefs.globalDailyCapMinutes ? `${prefs.globalDailyCapMinutes}m — ${totalScreen > (prefs.globalDailyCapMinutes * Math.max(1, usageInRange.length)) ? "OVER for the range" : "within cap"}` : "not set (Limits tab → set one)"}</p>
            <div className="row-flex"><button className="btn btn-sm btn-primary" onClick={exportAll}>⬇ Export full CSV</button></div>
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Over-limit offenders</h3>
            {usageByApp.map((a) => {
              const lim = limitOf("app", a.pkg);
              if (!lim?.dailyLimitMinutes) return null;
              const perDay = a.minutes / Math.max(1, usageInRange.length);
              const over = perDay > lim.dailyLimitMinutes;
              return <div className="bar-row" key={a.pkg}><span>{a.name}</span><div className="bar-track"><div className={`bar-fill ${over ? "warn" : ""}`} style={{ width: `${Math.min(100, (perDay / lim.dailyLimitMinutes) * 100)}%` }} /></div><span className="muted">{fmtDur(Math.round(perDay))}/{lim.dailyLimitMinutes}m</span></div>;
            })}
            <p className="muted">Only targets with a daily cap appear here.</p>
          </div>
        </div>
      )}
    </div>
  );
}

function numOrUndef(v: string): number | undefined {
  if (v === "" || v == null) return undefined;
  const n = parseInt(v, 10);
  return Number.isFinite(n) && n > 0 ? n : undefined;
}
function upsertLimit(next: Limit, persistAll: (l: Limit[], s?: Schedule[], p?: Prefs) => void, limits: Limit[]) {
  const rest = limits.filter((l) => !(l.targetKind === next.targetKind && l.targetKey === next.targetKey));
  const cleaned: Limit = { ...next };
  if (cleaned.dailyLimitMinutes == null && cleaned.sessionLimitMinutes == null && !cleaned.isBlockedNow) {
    persistAll(rest);
  } else {
    persistAll([...rest, cleaned]);
  }
}
async function addSite(domain: string, sites: SiteItem[], live: any, props: any, setLiveSites: any, saveSites: any, setBusy: any, setNewDomain: any) {
  const d = domain.trim().toLowerCase().replace(/^https?:\/\//, "").split("/")[0];
  if (!d || !d.includes(".")) return;
  if (props.demo || !live) {
    props.onAddSite?.(d);
    setNewDomain("");
    return;
  }
  const next = [...sites, { domain: d, displayName: d, isBlocked: true, category: "Custom", isCustom: true }];
  setLiveSites(next); setBusy("Saving…");
  try { await saveSites({ sites: next.map((s) => ({ domain: s.domain, displayName: s.displayName || s.domain, isBlocked: s.isBlocked, category: s.category || "Other", isCustom: !!s.isCustom })), updatedAt: Date.now() }); }
  finally { setBusy(null); setNewDomain(""); }
}

/* ---- Limits & schedules tab ---- */
function LimitsTab({ limits, schedules, prefs, apps, sites, persistAll, setBusy }: {
  limits: Limit[]; schedules: Schedule[]; prefs: Prefs; apps: AppItem[]; sites: SiteItem[];
  persistAll: (l: Limit[], s?: Schedule[], p?: Prefs) => void; setBusy: (s: string | null) => void;
}) {
  const [label, setLabel] = useState("Deep work");
  const [target, setTarget] = useState("all:*");
  const [days, setDays] = useState<number[]>([1, 2, 3, 4, 5]);
  const [start, setStart] = useState("09:00");
  const [end, setEnd] = useState("17:00");
  const dayNames = ["S", "M", "T", "W", "T", "F", "S"];

  function addSchedule() {
    const [targetKind, ...rest] = target.split(":");
    const targetKey = rest.join(":") || "*";
    persistAll(limits, [...schedules, { scheduleId: uid("sch"), label: label || "Block", targetKind, targetKey, days, startMinute: parseClock(start), endMinute: parseClock(end), isEnabled: true }], prefs);
  }
  function blockNowAll(on: boolean) {
    const keys = new Map(limits.map((l) => [`${l.targetKind}:${l.targetKey}`, l]));
    for (const a of apps) {
      const k = `app:${a.packageName}`;
      keys.set(k, { ...(keys.get(k) || { targetKind: "app", targetKey: a.packageName, label: a.appName }), isBlockedNow: on });
    }
    persistAll([...keys.values()], schedules, prefs);
  }
  return (
    <div className="grid2">
      <div className="card">
        <h3 style={{ marginTop: 0 }}>⛔ Block now</h3>
        <p className="muted">Instantly lock everything (like StayFree's Block Now). {prefs.strictMode ? "Strict mode is ON — toggles are locked." : ""}</p>
        <div className="row-flex">
          <button className="btn btn-sm btn-primary" onClick={() => blockNowAll(true)} disabled={prefs.strictMode}>Block everything now</button>
          <button className="btn btn-sm" onClick={() => blockNowAll(false)} disabled={prefs.strictMode}>Release all</button>
        </div>
        <h3>Global prefs</h3>
        <label className="row-flex muted" style={{ fontSize: 13.5 }}><input type="checkbox" checked={!!prefs.strictMode} onChange={(e) => persistAll(limits, schedules, { ...prefs, strictMode: e.target.checked })} /> Strict mode (no quick unblock)</label>
        <label className="row-flex muted" style={{ fontSize: 13.5 }}>Daily screen cap (min) <input className="input" type="number" style={{ width: 90 }} value={prefs.globalDailyCapMinutes ?? ""} onChange={(e) => persistAll(limits, schedules, { ...prefs, globalDailyCapMinutes: numOrUndef(e.target.value) })} /></label>
        <p className="muted" style={{ fontSize: 12.5 }}>Blocked-now targets: {limits.filter((l) => l.isBlockedNow).length}</p>
      </div>
      <div className="card">
        <h3 style={{ marginTop: 0 }}>🗓️ New schedule</h3>
        <label className="muted" style={{ fontSize: 12 }}>Label</label>
        <input className="input" value={label} onChange={(e) => setLabel(e.target.value)} />
        <div style={{ height: 8 }} />
        <label className="muted" style={{ fontSize: 12 }}>Target</label>
        <select className="input" value={target} onChange={(e) => setTarget(e.target.value)}>
          <option value="all:*">Everything</option>
          <option value="category:Social">Category · Social</option>
          <option value="category:Video">Category · Video</option>
          <option value="category:Games">Category · Games</option>
          {apps.slice(0, 20).map((a) => <option key={a.packageName} value={`app:${a.packageName}`}>App · {a.appName}</option>)}
          {sites.slice(0, 20).map((s) => <option key={s.domain} value={`site:${s.domain}`}>Site · {s.displayName || s.domain}</option>)}
        </select>
        <div style={{ height: 8 }} />
        <div className="sched-days">{dayNames.map((d, i) => <span key={i} className={`day ${days.includes(i) ? "on" : ""}`} onClick={() => setDays((p) => (p.includes(i) ? p.filter((x) => x !== i) : [...p, i]))}>{d}</span>)}</div>
        <div style={{ height: 8 }} />
        <div className="row-flex">
          <input className="input" type="time" value={start} onChange={(e) => setStart(e.target.value)} style={{ maxWidth: 130 }} />
          <span className="muted">→</span>
          <input className="input" type="time" value={end} onChange={(e) => setEnd(e.target.value)} style={{ maxWidth: 130 }} />
          <button className="btn btn-sm btn-primary" onClick={addSchedule}>+ Add</button>
        </div>
      </div>
      <div className="card" style={{ gridColumn: "1 / -1" }}>
        <h3 style={{ marginTop: 0 }}>Schedules ({schedules.length})</h3>
        {schedules.map((s) => (
          <div className="mock-row" key={s.scheduleId} style={{ marginBottom: 8, flexWrap: "wrap", gap: 8 }}>
            <span><strong>{s.label}</strong> <small className="muted">{s.targetKind}:{s.targetKey} · {s.days.map((d) => dayNames[d]).join("")} · {fmtClock(s.startMinute)}–{fmtClock(s.endMinute)}</small></span>
            <span className="row-flex">
              <button className={`toggle ${s.isEnabled ? "on" : ""}`} onClick={() => { setBusy("Saving…"); persistAll(limits, schedules.map((x) => (x.scheduleId === s.scheduleId ? { ...x, isEnabled: !x.isEnabled } : x)), prefs); setBusy(null); }} aria-label="toggle schedule" />
              <button className="btn btn-sm" onClick={() => persistAll(limits, schedules.filter((x) => x.scheduleId !== s.scheduleId), prefs)}>Delete</button>
            </span>
          </div>
        ))}
        {schedules.length === 0 && <p className="muted">No schedules yet — add your first deep-work block above.</p>}
      </div>
    </div>
  );
}

/* ---- Focus tab ---- */
function FocusTab({ onComplete, records }: { onComplete: (mins: number, title: string) => void; records: WorkRec[] }) {
  const [minutes, setMinutes] = useState(25);
  const [title, setTitle] = useState("Deep work");
  const [remaining, setRemaining] = useState(25 * 60);
  const [running, setRunning] = useState(false);
  useEffect(() => { if (!running) setRemaining(minutes * 60); }, [minutes, running]);
  useEffect(() => {
    if (!running) return;
    const id = setInterval(() => setRemaining((r) => {
      if (r <= 1) { clearInterval(id); setRunning(false); onComplete(minutes, title || "Focus session"); return minutes * 60; }
      return r - 1;
    }), 1000);
    return () => clearInterval(id);
  }, [running, minutes, title, onComplete]);
  const frac = 1 - remaining / Math.max(1, minutes * 60);
  const R = 80;
  const C = 2 * Math.PI * R;
  return (
    <div className="grid2">
      <div className="card" style={{ textAlign: "center" }}>
        <h3 style={{ marginTop: 0 }}>⏱️ Pomodoro</h3>
        <div className="row-flex" style={{ justifyContent: "center" }}>
          {[15, 25, 50].map((m) => <button key={m} className={`chip ${minutes === m ? "active" : ""}`} onClick={() => setMinutes(m)}>{m}m</button>)}
        </div>
        <div style={{ height: 10 }} />
        <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Session title" style={{ maxWidth: 280, margin: "0 auto" }} />
        <div style={{ height: 12 }} />
        <svg className="timer-ring" viewBox="0 0 190 190">
          <circle cx="95" cy="95" r={R} fill="none" stroke="rgba(127,140,165,.25)" strokeWidth="12" />
          <circle cx="95" cy="95" r={R} fill="none" stroke="#7c5cff" strokeWidth="12" strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C * (1 - frac)} transform="rotate(-90 95 95)" />
          <text x="95" y="102" textAnchor="middle" fill="currentColor" fontSize="30" fontWeight="800">
            {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}
          </text>
        </svg>
        <div className="row-flex" style={{ justifyContent: "center", marginTop: 10 }}>
          {!running ? <button className="btn btn-primary" onClick={() => setRunning(true)}>Start</button>
            : <><button className="btn" onClick={() => setRunning(false)}>Pause</button><button className="btn btn-sm" onClick={() => { setRunning(false); const done = minutes - Math.floor(remaining / 60); if (done >= 2) onComplete(done, title || "Focus session"); }}>Finish early</button></>}
          <button className="btn btn-sm btn-ghost" onClick={() => { setRunning(false); setRemaining(minutes * 60); }}>Reset</button>
        </div>
        <p className="muted" style={{ fontSize: 12.5 }}>Finishing earns ~½ the minutes as leisure credit.</p>
      </div>
      <div className="card">
        <h3 style={{ marginTop: 0 }}>Session history ({records.length})</h3>
        {records.slice(0, 12).map((r, i) => (
          <div className="mock-row" key={(r.recordId || i) + String(r.timestamp)} style={{ marginBottom: 8 }}>
            <span>{r.title} · {r.durationMinutes}m</span>
            <small className="muted">+{r.earnedMinutesCredited ?? r.earned ?? 0}m · {new Date(r.timestamp).toLocaleString()}</small>
          </div>
        ))}
        {records.length === 0 && <p className="muted">No sessions yet.</p>}
      </div>
    </div>
  );
}

function fmtDate(d = new Date()) {
  return d.toISOString().slice(0, 10);
}
export { fmtDate };
