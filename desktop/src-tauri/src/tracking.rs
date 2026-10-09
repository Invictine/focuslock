use crate::{
    blocker::BlockerRuntime,
    browser_guard::{BrowserGuard, BrowserWindowSample},
    browser_warning::BrowserRepairRuntime,
    windows_capture::{
        browser_window_identity, capture_foreground, get_browser_windows, get_running_windows,
        idle_millis, minimize_foreground, request_browser_window_close,
        CapturedWindow,
    },
};
use chrono::Local;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fs,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock,
    },
    thread::{self, JoinHandle},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, Manager, State};
use uuid::Uuid;

const DEFAULT_REASON: &str = "blocked";
// Matches desktop/src/features.ts.
const DESKTOP_FROG_ENABLED: bool = true;

fn remove_disabled_frog_targets(targets: &mut BlockedTargets, reasons: &mut HashMap<String, String>) {
    if DESKTOP_FROG_ENABLED { return; }
    targets.app_ids.retain(|id| reasons.get(id).is_none_or(|reason| reason != "frog"));
    targets.domains.retain(|id| reasons.get(id).is_none_or(|reason| reason != "frog"));
    reasons.retain(|_, reason| reason != "frog");
}

/// Reason carried by a device-local permanent block. Permanence lives in
/// `TrackingStore::permanent_targets` (never in Convex), so this reason also
/// acts as the precedence marker: it always wins over `frog`/`limit`/`blocked`.
/// Shared with `blocker` (overlay actions, close handling).
pub(crate) const PERMANENT_REASON: &str = "permanent";

/// Executables that can never be permanently blocked. Blocking the Windows
/// shell makes the machine unrecoverable, so this is the desktop analog of
/// Android's `PermanentBlocksRepository.isProtectedPackage` (own package,
/// launcher, system UI, ...). `own_app_id()` is protected separately.
const PROTECTED_APP_IDS: &[&str] = &[
    "explorer.exe",
    "dwm.exe",
    "winlogon.exe",
    "csrss.exe",
    "smss.exe",
    "wininit.exe",
    "services.exe",
    "lsass.exe",
    "taskhostw.exe",
    "sihost.exe",
    "ctfmon.exe",
    "startmenuexperiencehost.exe",
    "searchhost.exe",
    "shellexperiencehost.exe",
    // OS/UWP hosts: blocking one of these takes whole classes of windows with
    // it and cannot be undone from the app.
    "applicationframehost.exe",
    "runtimebroker.exe",
    "textinputhost.exe",
    "lockapp.exe",
    "logonui.exe",
    "shellhost.exe",
    "searchapp.exe",
    // The bundled focus surface must remain reachable during Frog enforcement.
    "focuslock.void.exe",
];

/// Consecutive `capture_foreground` failures after which the blocker is hidden:
/// with no idea what the foreground is, a stuck always-on-top window must not
/// trap the user. A single transient error keeps enforcement up.
const CAPTURE_ERROR_HIDE_THRESHOLD: u32 = 5;

const STORE_VERSION: u8 = 1;

/// Usage days older than this are pruned from the store. The UI only renders
/// today's entries and the cloud sync uploads buckets by date, so 30 days is a
/// safe retention window.
const USAGE_RETENTION_DAYS: u64 = 30;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DeviceIdentity {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub created_at_ms: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TrackerConfig {
    pub sample_interval_ms: u64,
    pub idle_threshold_seconds: u64,
    pub capture_browser_domains: bool,
}

impl Default for TrackerConfig {
    fn default() -> Self {
        Self {
            sample_interval_ms: 1_000,
            idle_threshold_seconds: 60,
            capture_browser_domains: true,
        }
    }
}

impl TrackerConfig {
    fn normalized(mut self) -> Self {
        self.sample_interval_ms = self.sample_interval_ms.clamp(1_000, 10_000);
        self.idle_threshold_seconds = self.idle_threshold_seconds.clamp(15, 3_600);
        self
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BlockedTargets {
    pub app_ids: Vec<String>,
    pub domains: Vec<String>,
}

impl BlockedTargets {
    fn normalized(mut self) -> Self {
        self.app_ids = clean_values(self.app_ids);
        // FocusLock can never be its own blocked target, otherwise the blocker
        // window (or the main window) would keep triggering enforcement.
        self.app_ids.retain(|id| id != own_app_id());
        self.domains = clean_values(self.domains);
        self
    }
}

fn clean_values(values: Vec<String>) -> Vec<String> {
    let mut values: Vec<_> = values
        .into_iter()
        .map(|v| normalize_key(&v))
        .filter(|v| !v.is_empty())
        .collect();
    values.sort();
    values.dedup();
    values
}

fn normalize_key(value: &str) -> String {
    value.trim().trim_start_matches("www.").to_ascii_lowercase()
}

fn clean_reasons(reasons: HashMap<String, String>) -> HashMap<String, String> {
    reasons
        .into_iter()
        .filter_map(|(key, value)| {
            let key = normalize_key(&key);
            let value = value.trim().to_ascii_lowercase();
            (!key.is_empty() && !value.is_empty()).then_some((key, value))
        })
        .collect()
}

/// Union device-local permanent targets into an enforcement set and force their
/// reason. Called from the add command, `set_blocked_targets` and the load path,
/// so permanence holds in every direction: a caller payload (UI flush, Convex
/// refresh) can never remove a permanent target and can never downgrade its
/// reason to `frog`/`limit`/`blocked`.
fn merge_permanent_targets(
    targets: &mut BlockedTargets,
    reasons: &mut HashMap<String, String>,
    permanent: &[String],
) {
    for app_id in permanent {
        if !targets.app_ids.iter().any(|existing| existing == app_id) {
            targets.app_ids.push(app_id.clone());
        }
        reasons.insert(app_id.clone(), PERMANENT_REASON.to_string());
    }
    targets.app_ids = clean_values(std::mem::take(&mut targets.app_ids));
    targets.app_ids.retain(|id| id != own_app_id());
}

/// One rejected entry from `add_permanent_targets`, reported back to the UI so
/// it can explain why an id was not accepted.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PermanentRejection {
    pub id: String,
    pub reason: String,
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PermanentAddResult {
    pub added: Vec<String>,
    pub rejected: Vec<PermanentRejection>,
}

/// Normalizes a caller-supplied batch and decides which ids may become
/// permanent: protected ids are rejected, ids already permanent (or repeated
/// within the batch) are reported as duplicates, and the rest are returned
/// sorted/deduped. Empty ids are dropped without a report — the UI never sends
/// them, and there is no meaningful name to surface.
fn plan_permanent_additions(
    existing: &[String],
    app_ids: Vec<String>,
) -> (Vec<String>, Vec<PermanentRejection>) {
    let mut added: Vec<String> = Vec::new();
    let mut rejected: Vec<PermanentRejection> = Vec::new();
    for raw in app_ids {
        let key = normalize_key(&raw);
        if key.is_empty() {
            continue;
        }
        if is_protected_app_id(&key) {
            rejected.push(PermanentRejection {
                id: key,
                reason: "protected".into(),
            });
            continue;
        }
        if existing.iter().any(|id| id == &key) || added.iter().any(|id| id == &key) {
            rejected.push(PermanentRejection {
                id: key,
                reason: "duplicate".into(),
            });
            continue;
        }
        added.push(key);
    }
    added.sort();
    added.dedup();
    (added, rejected)
}

/// Lowercased executable name of this process; used to keep FocusLock's own
/// windows out of matching.
fn own_app_id() -> &'static str {
    static OWN_APP_ID: OnceLock<String> = OnceLock::new();
    OWN_APP_ID
        .get_or_init(|| {
            std::env::current_exe()
                .ok()
                .and_then(|path| path.file_name().map(|name| name.to_string_lossy().into_owned()))
                .map(|name| name.to_ascii_lowercase())
                .unwrap_or_else(|| "focuslock-desktop.exe".into())
        })
        .as_str()
}

/// Shell-critical executables (and FocusLock itself) can never become a
/// permanent block. Mirrors the `FROG_NEVER_BLOCK_APP_IDS` list the Windows UI
/// already treats as untouchable.
fn is_protected_app_id(app_id: &str) -> bool {
    let key = normalize_key(app_id);
    key == own_app_id() || key.contains("focuslock") || PROTECTED_APP_IDS.contains(&key.as_str())
}

/// A blocked foreground target with the reason its rule carries.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BlockedMatch {
    /// The blocked entry that matched (app id or domain as configured).
    pub target: String,
    pub kind: &'static str,
    pub reason: String,
}

/// Immutable, `HashSet`-backed view of the blocked targets. Rebuilt whenever
/// `set_blocked_targets` changes them so the tracker loop only does lookups,
/// which keeps very large frog-lock sets cheap to enforce.
#[derive(Clone, Debug)]
pub struct BlockedMatcher {
    app_ids: HashSet<String>,
    reasons: HashMap<String, String>,
    own_app_id: String,
}

impl BlockedMatcher {
    pub fn new(targets: &BlockedTargets, reasons: &HashMap<String, String>) -> Self {
        Self {
            app_ids: targets.app_ids.iter().cloned().collect(),
            reasons: reasons.clone(),
            own_app_id: own_app_id().to_string(),
        }
    }

    pub fn is_own_process(&self, app_id: &str) -> bool {
        app_id.eq_ignore_ascii_case(&self.own_app_id)
    }

    pub fn match_target(&self, captured: &CapturedWindow) -> Option<BlockedMatch> {
        if self.is_own_process(&captured.app_id) {
            return None;
        }
        if self.app_ids.contains(&captured.app_id) {
            return Some(BlockedMatch {
                target: captured.app_id.clone(),
                kind: "app",
                reason: self
                    .reasons
                    .get(&captured.app_id)
                    .cloned()
                    .unwrap_or_else(|| DEFAULT_REASON.to_string()),
            });
        }
        None
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ActivityObservation {
    pub captured_at_ms: u64,
    pub app_id: String,
    pub app_name: String,
    pub executable_path: Option<String>,
    pub window_title: String,
    pub browser_domain: Option<String>,
    pub device_id: String,
    pub device_name: String,
    pub idle: bool,
    #[serde(default)]
    pub idle_millis: Option<u64>,
    pub blocked: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UsageEntry {
    pub date: String,
    pub app_id: String,
    pub app_name: String,
    pub browser_domain: Option<String>,
    pub active_seconds: u64,
    pub device_id: String,
    pub device_name: String,
    pub platform: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TrackingStore {
    version: u8,
    device: DeviceIdentity,
    config: TrackerConfig,
    #[serde(default)]
    blocked_targets: BlockedTargets,
    #[serde(default)]
    blocked_reasons: HashMap<String, String>,
    /// Device-local permanent blocks. Kept in a separate list from
    /// `blocked_targets` so a UI/Convex-driven target flush can never clear
    /// them; `set_blocked_targets` unions this list back in on every write.
    #[serde(default)]
    permanent_targets: Vec<String>,
    #[serde(default)]
    browser_protection_required: bool,
    #[serde(default)]
    browser_protection_enabled: bool,
    #[serde(default)]
    browser_protection_locked_until_ms: u64,
    usage: BTreeMap<String, UsageEntry>,
    /// Set by `record` when usage data changed; the worker persists only when
    /// this is set instead of rewriting the file on a fixed timer.
    #[serde(skip)]
    dirty: bool,
    /// Bumped whenever usage data changes; keys the sorted-snapshot cache.
    #[serde(skip)]
    usage_generation: u64,
    /// Local day pruning last ran for, so it runs at most once per day.
    #[serde(skip)]
    last_pruned_date: Option<String>,
}

impl TrackingStore {
    fn browser_protection_active(&self) -> bool {
        self.browser_protection_enabled && self.browser_protection_required
    }
    fn new() -> Self {
        let name = std::env::var("COMPUTERNAME")
            .ok()
            .filter(|v| !v.trim().is_empty())
            .unwrap_or_else(|| "Windows PC".into());
        Self {
            version: STORE_VERSION,
            device: DeviceIdentity {
                id: Uuid::new_v4().to_string(),
                name,
                platform: "windows".into(),
                created_at_ms: now_ms(),
            },
            config: TrackerConfig::default(),
            blocked_targets: BlockedTargets::default(),
            blocked_reasons: HashMap::new(),
            permanent_targets: Vec::new(),
            browser_protection_required: false,
            browser_protection_enabled: false,
            browser_protection_locked_until_ms: 0,
            usage: BTreeMap::new(),
            dirty: false,
            usage_generation: 0,
            last_pruned_date: None,
        }
    }

    /// Drop usage days older than the retention window. Compares each entry's
    /// own date string so this is independent of the composite key format.
    fn prune_old_days(&mut self) {
        let cutoff = (Local::now() - chrono::Duration::days(USAGE_RETENTION_DAYS as i64))
            .format("%Y-%m-%d")
            .to_string();
        self.usage
            .retain(|_, entry| entry.date.as_str() >= cutoff.as_str());
    }

    fn record(&mut self, observation: &ActivityObservation, seconds: u64) {
        if seconds == 0 || observation.idle || observation.blocked {
            return;
        }
        let date = Local::now().format("%Y-%m-%d").to_string();
        // Prune at most once per local day; the retain walk is only worth it
        // when the date rolls over (or on first load).
        let should_prune = self.last_pruned_date.as_deref() != Some(date.as_str());
        let domain = observation.browser_domain.clone();
        let key = format!(
            "{date}\u{1f}{}\u{1f}{}\u{1f}{}",
            self.device.id,
            observation.app_id,
            domain.as_deref().unwrap_or("")
        );
        let entry = self.usage.entry(key).or_insert_with(|| UsageEntry {
            date: date.clone(),
            app_id: observation.app_id.clone(),
            app_name: observation.app_name.clone(),
            browser_domain: domain,
            active_seconds: 0,
            device_id: self.device.id.clone(),
            device_name: self.device.name.clone(),
            platform: self.device.platform.clone(),
        });
        entry.active_seconds = entry.active_seconds.saturating_add(seconds);
        entry.app_name.clone_from(&observation.app_name);
        self.dirty = true;
        self.usage_generation = self.usage_generation.wrapping_add(1);
        if should_prune {
            self.prune_old_days();
            self.last_pruned_date = Some(date);
        }
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackingSnapshot {
    pub device: DeviceIdentity,
    pub config: TrackerConfig,
    pub blocked_targets: BlockedTargets,
    pub current: Option<ActivityObservation>,
    /// Shared with the snapshot cache so unchanged polls clone nothing
    /// (serializes as a plain JSON array).
    pub usage: Arc<Vec<UsageEntry>>,
    pub running: bool,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackerStatus {
    pub running: bool,
    pub enforcement_active: bool,
    pub current: Option<ActivityObservation>,
    pub last_error: Option<String>,
    pub browser_protection_required: bool,
    pub browser_protection_enabled: bool,
    pub browser_protection: Option<BrowserProtectionStatus>,
    pub browser_protection_scan_state: &'static str,
    pub browser_protection_error: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowserProtectionStatus {
    pub browser: String,
    pub healthy: bool,
    pub grace_remaining_seconds: u64,
    pub reason: Option<String>,
    #[serde(skip)]
    window_handle: isize,
    #[serde(skip)]
    process_id: u32,
    #[serde(skip)]
    app_id: String,
    #[serde(skip)]
    checked_at_ms: u64,
}

#[derive(Clone, Debug)]
struct BrowserProtectionScan {
    state: &'static str,
    browser: Option<BrowserProtectionStatus>,
    error: Option<String>,
}

impl Default for BrowserProtectionScan {
    fn default() -> Self { Self { state: "checking", browser: None, error: None } }
}

pub struct TrackerRuntime {
    browser_repair_resets: Arc<Mutex<Vec<String>>>,
    browser_paths: Arc<Mutex<HashMap<String, String>>>,
    browser_protection: Arc<Mutex<BrowserProtectionScan>>,
    running: Arc<AtomicBool>,
    store: Arc<Mutex<TrackingStore>>,
    current: Arc<Mutex<Option<ActivityObservation>>>,
    last_error: Arc<Mutex<Option<String>>>,
    control_error: Mutex<Option<String>>,
    worker: Mutex<Option<JoinHandle<()>>>,
    store_path: PathBuf,
    /// Latest sorted usage vec, keyed by the store's usage generation so
    /// repeated polls don't re-clone + re-sort the whole map.
    usage_cache: Mutex<Option<(u64, Arc<Vec<UsageEntry>>)>>,
    /// Hot-swapped matcher for the blocked targets, so the tracker loop never
    /// walks (potentially huge) target vecs.
    matcher: Arc<Mutex<Arc<BlockedMatcher>>>,
}

impl TrackerRuntime {
    pub(crate) fn note_control_error(&self, error: String) {
        if let Ok(mut current) = self.control_error.lock() { *current = Some(error); }
    }
    pub fn browser_protection_active(&self) -> bool {
        self.store.lock().map(|s| s.browser_protection_active()).unwrap_or(false)
    }
    pub fn load(store_path: PathBuf) -> Result<Self, String> {
        let store = load_store(&store_path)?;
        let matcher = Arc::new(BlockedMatcher::new(
            &store.blocked_targets,
            &store.blocked_reasons,
        ));
        Ok(Self {
            browser_repair_resets: Arc::new(Mutex::new(Vec::new())),
            browser_paths: Arc::new(Mutex::new(HashMap::new())),
            browser_protection: Arc::new(Mutex::new(BrowserProtectionScan::default())),
            running: Arc::new(AtomicBool::new(false)),
            store: Arc::new(Mutex::new(store)),
            current: Arc::new(Mutex::new(None)),
            last_error: Arc::new(Mutex::new(None)),
            control_error: Mutex::new(None),
            worker: Mutex::new(None),
            store_path,
            usage_cache: Mutex::new(None),
            matcher: Arc::new(Mutex::new(matcher)),
        })
    }

    pub fn start(&self, app: AppHandle) -> bool {
        if self.running.swap(true, Ordering::SeqCst) {
            return false;
        }
        let running = self.running.clone();
        let store = self.store.clone();
        let current = self.current.clone();
        let last_error = self.last_error.clone();
        let store_path = self.store_path.clone();
        let matcher = self.matcher.clone();
        let browser_protection = self.browser_protection.clone();
        let browser_repair_resets = self.browser_repair_resets.clone();
        let browser_paths = self.browser_paths.clone();
        let repair_runtime = app.try_state::<BrowserRepairRuntime>().map(|state| state.inner().clone());
        let worker = thread::Builder::new()
            .name("focuslock-activity-tracker".into())
            .spawn(move || {
                let mut previous_key = None;
                let mut samples_since_save = 0_u64;
                let mut consecutive_capture_errors = 0_u32;
                let mut guard = BrowserGuard::default();
                let lease_dir = store_path.parent().unwrap_or(Path::new(".")).to_path_buf();
                let guard_started = std::time::Instant::now();
                while running.load(Ordering::SeqCst) {
                    let (config, device, protection_required) = match store.lock() {
                        Ok(s) => {
                            (s.config.clone(), s.device.clone(), s.browser_protection_active())
                        },
                        Err(_) => break,
                    };
                    let foreground = capture_foreground(config.capture_browser_domains);
                    if let Ok(Some(window)) = &foreground {
                        if crate::browser_guard::is_browser(&window.app_id) {
                            if let Some(path) = &window.executable_path {
                                if let Ok(mut paths) = browser_paths.lock() {
                                    paths.insert(window.app_id.to_ascii_lowercase(), path.clone());
                                }
                            }
                        }
                    }
                    if let Ok(mut resets) = browser_repair_resets.lock() {
                        for app_id in resets.drain(..) {
                            guard.reset_browser(&app_id, guard_started.elapsed().as_millis() as u64);
                        }
                    }
                    let scan = update_browser_repair_guard(
                        &app,
                        repair_runtime.as_ref(),
                        &mut guard,
                        &lease_dir,
                        protection_required,
                        guard_started.elapsed().as_millis().min(u64::MAX as u128) as u64,
                        now_ms(),
                        &last_error,
                    );
                    if let Ok(mut status) = browser_protection.lock() { *status = scan; }
                    let matcher = match matcher.lock() {
                        Ok(value) => value.clone(),
                        Err(_) => break,
                    };
                    let idle_millis = idle_millis().ok();
                    let idle = idle_millis.is_some_and(|v| v / 1_000 >= config.idle_threshold_seconds);
                    match foreground {
                        Ok(Some(captured)) => {
                            consecutive_capture_errors = 0;
                            // Explicit app blocks keep precedence (including Frog and permanent).
                            let blocked_match = matcher.match_target(&captured);
                            let observation = ActivityObservation {
                                captured_at_ms: now_ms(),
                                app_id: captured.app_id.clone(),
                                app_name: captured.app_name.clone(),
                                executable_path: captured.executable_path.clone(),
                                window_title: captured.window_title.clone(),
                                browser_domain: captured.browser_domain.clone(),
                                device_id: device.id,
                                device_name: device.name,
                                idle,
                                idle_millis,
                                blocked: blocked_match.is_some(),
                            };
                            let key = (
                                observation.app_id.clone(),
                                observation.browser_domain.clone(),
                                idle,
                                observation.blocked,
                            );
                            if previous_key.as_ref() != Some(&key) {
                                let _ = app.emit("focuslock://activity-changed", &observation);
                                previous_key = Some(key);
                            }
                            let blocker = app.try_state::<BlockerRuntime>();
                            match &blocked_match {
                                Some(matched) => {
                                    let covered = blocker
                                        .as_ref()
                                        .is_some_and(|blocker| blocker.show_for(&app, matched));
                                    if covered {
                                        let _ = app
                                            .emit("focuslock://target-blocked", &observation);
                                        if let Ok(mut last) = last_error.lock() {
                                            *last = None;
                                        }
                                    } else {
                                        // The blocker window is unavailable; keep
                                        // the old minimize enforcement instead.
                                        match minimize_foreground() {
                                            Ok(()) => {
                                                let _ = app
                                                    .emit("focuslock://target-blocked", &observation);
                                                if let Ok(mut last) = last_error.lock() {
                                                    *last = None;
                                                }
                                            }
                                            Err(error) => {
                                                if let Ok(mut last) = last_error.lock() {
                                                    *last = Some(error);
                                                }
                                            }
                                        }
                                    }
                                }
                                None => {
                                    // Never react to our own windows: the blocker
                                    // and the main window are foreground while the
                                    // user interacts with them.
                                    if !matcher.is_own_process(&observation.app_id) {
                                        if let Some(blocker) = blocker.as_ref() {
                                            blocker.hide(&app);
                                        }
                                    }
                                    if let Ok(mut last) = last_error.lock() {
                                        *last = None;
                                    }
                                }
                            }
                            if let Ok(mut value) = current.lock() {
                                *value = Some(observation.clone());
                            }
                            if let Ok(mut value) = store.lock() {
                                value.record(&observation, config.sample_interval_ms / 1_000);
                            }
                        }
                        Ok(None) => {
                            previous_key = None;
                            consecutive_capture_errors = 0;
                            if let Ok(mut value) = current.lock() {
                                *value = None;
                            }
                            // No foreground window at all (desktop, lock screen,
                            // UAC): nothing is blocked in front of the user, so
                            // take an already-visible blocker down.
                            if let Some(blocker) = app.try_state::<BlockerRuntime>() {
                                blocker.hide(&app);
                            }
                        }
                        Err(error) => {
                            consecutive_capture_errors =
                                consecutive_capture_errors.saturating_add(1);
                            if let Ok(mut value) = last_error.lock() {
                                *value = Some(error);
                            }
                            if consecutive_capture_errors >= CAPTURE_ERROR_HIDE_THRESHOLD {
                                if let Some(blocker) = app.try_state::<BlockerRuntime>() {
                                    blocker.hide(&app);
                                }
                            }
                        }
                    }
                    samples_since_save += 1;
                    if samples_since_save >= (5_000 / config.sample_interval_ms).max(1) {
                        samples_since_save = 0;
                        // Persist only when `record` actually changed data, not
                        // on a fixed timer. On failure the dirty flag stays set
                        // so the next tick retries.
                        if let Ok(mut value) = store.lock() {
                            if value.dirty {
                                match persist_store(&store_path, &value) {
                                    Ok(()) => value.dirty = false,
                                    Err(error) => {
                                        if let Ok(mut last) = last_error.lock() {
                                            *last = Some(error);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    thread::sleep(Duration::from_millis(config.sample_interval_ms));
                }
                if let Ok(value) = store.lock() {
                    let _ = persist_store(&store_path, &value);
                }
            });
        match worker {
            Ok(handle) => {
                if let Ok(mut slot) = self.worker.lock() {
                    *slot = Some(handle);
                }
                true
            }
            Err(error) => {
                self.running.store(false, Ordering::SeqCst);
                if let Ok(mut value) = self.last_error.lock() {
                    *value = Some(format!("Could not start activity tracker: {error}"));
                }
                false
            }
        }
    }

    fn stop(&self) {
        self.running.store(false, Ordering::SeqCst);
        if let Ok(mut worker) = self.worker.lock() {
            if let Some(handle) = worker.take() {
                let _ = handle.join();
            }
        }
    }
    fn status(&self) -> TrackerStatus {
        let browser_protection_required = self.store.lock()
            .map(|s| s.browser_protection_active()).unwrap_or(false);
        let browser_protection_enabled = self.store.lock()
            .map(|s| s.browser_protection_enabled).unwrap_or(false);
        let enforcement_active = self
            .store
            .lock()
            .map(|s| !s.blocked_targets.app_ids.is_empty() || !s.blocked_targets.domains.is_empty() || s.browser_protection_active())
            .unwrap_or(false);
        let scan = self.browser_protection.lock().map(|v| v.clone()).unwrap_or_else(|_| BrowserProtectionScan {
            state: "scan_error", browser: None, error: Some("Browser status is unavailable".into()),
        });
        let mut browser_protection = scan.browser;
        if let Some(browser) = browser_protection.as_mut() {
            browser.healthy = browser_protection_required && crate::browser_bridge::read_window_health(
                self.store_path.parent().unwrap_or(Path::new(".")), browser.window_handle,
                browser.process_id, &browser.app_id, now_ms());
            browser.grace_remaining_seconds = browser.grace_remaining_seconds
                .saturating_sub(now_ms().saturating_sub(browser.checked_at_ms) / 1_000);
        }
        TrackerStatus {
            running: self.running.load(Ordering::SeqCst),
            enforcement_active,
            current: self.current.lock().ok().and_then(|v| v.clone()),
            last_error: self.last_error.lock().ok().and_then(|v| v.clone())
                .or_else(|| self.control_error.lock().ok().and_then(|v| v.clone())),
            browser_protection_required,
            browser_protection_enabled,
            browser_protection,
            browser_protection_scan_state: scan.state,
            browser_protection_error: scan.error,
        }
    }
    /// Pausing the tracker is refused while device-local permanent blocks
    /// exist. With tracking stopped nothing would show the blocker, so pausing
    /// would release every permanent block until the user manually restarted
    /// enforcement.
    pub(crate) fn ensure_pause_allowed(&self) -> Result<(), String> {
        let store = self
            .store
            .lock()
            .map_err(|_| "Tracking store lock was poisoned".to_string())?;
        if store.browser_protection_active() {
            return Err("Browser extension protection is active. Remove your website boundaries or wait for the commitment to end before pausing.".into());
        }
        if !store.blocked_targets.app_ids.is_empty() || !store.blocked_targets.domains.is_empty() {
            return Err("Active boundaries are being enforced. Protection cannot be paused or quit until those boundaries allow access.".into());
        }
        if store.permanent_targets.is_empty() {
            if let Ok(mut error) = self.control_error.lock() { *error = None; }
            Ok(())
        } else {
            Err("Permanent blocks are active. Tracking cannot be paused.".to_string())
        }
    }
    fn snapshot(&self) -> Result<TrackingSnapshot, String> {
        let store = self
            .store
            .lock()
            .map_err(|_| "Tracking store lock was poisoned".to_string())?;
        let generation = store.usage_generation;
        let cached = self
            .usage_cache
            .lock()
            .ok()
            .and_then(|guard| guard.clone());
        let usage = match cached {
            Some((cached_generation, usage)) if cached_generation == generation => usage,
            _ => {
                let mut sorted: Vec<_> = store.usage.values().cloned().collect();
                sorted.sort_by(|a, b| {
                    b.date
                        .cmp(&a.date)
                        .then_with(|| b.active_seconds.cmp(&a.active_seconds))
                });
                let sorted = Arc::new(sorted);
                if let Ok(mut guard) = self.usage_cache.lock() {
                    *guard = Some((generation, sorted.clone()));
                }
                sorted
            }
        };
        Ok(TrackingSnapshot {
            device: store.device.clone(),
            config: store.config.clone(),
            blocked_targets: store.blocked_targets.clone(),
            current: self.current.lock().ok().and_then(|v| v.clone()),
            usage,
            running: self.running.load(Ordering::SeqCst),
        })
    }
}

impl Drop for TrackerRuntime {
    fn drop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
    }
}

#[tauri::command]
pub fn get_device_identity(state: State<'_, TrackerRuntime>) -> Result<DeviceIdentity, String> {
    Ok(state
        .store
        .lock()
        .map_err(|_| "Tracking store lock was poisoned".to_string())?
        .device
        .clone())
}
#[tauri::command]
pub fn get_tracking_snapshot(state: State<'_, TrackerRuntime>) -> Result<TrackingSnapshot, String> {
    state.snapshot()
}
#[tauri::command]
pub fn get_tracker_status(state: State<'_, TrackerRuntime>) -> TrackerStatus {
    state.status()
}
#[tauri::command]
pub fn get_running_apps() -> Result<Vec<crate::windows_capture::RunningApp>, String> {
    get_running_windows()
}
#[tauri::command]
pub fn start_tracking(app: AppHandle, state: State<'_, TrackerRuntime>) -> TrackerStatus {
    state.start(app);
    state.status()
}
#[tauri::command]
pub fn stop_tracking(
    app: AppHandle,
    state: State<'_, TrackerRuntime>,
    blocker: State<'_, BlockerRuntime>,
) -> Result<TrackerStatus, String> {
    // Pausing enforcement must not release a permanent block: while tracking is
    // stopped nothing would re-show the blocker, and the in-app list can never
    // be removed.
    state.ensure_pause_allowed()?;
    state.stop();
    blocker.hide(&app);
    Ok(state.status())
}
#[tauri::command]
pub fn set_browser_protection_policy(
    app: AppHandle,
    required: bool,
    locked_until_ms: Option<u64>,
    state: State<'_, TrackerRuntime>,
) -> Result<bool, String> {
    if !app.state::<crate::auth::BrowserAuthRuntime>().is_signed_in() {
        return Err("Sign in before updating browser protection policy.".into());
    }
    let mut store = state.store.lock().map_err(|_| "Tracking store lock was poisoned")?;
    // Strict freezes boundary edits; it does not force this optional checker on.
    let _ = locked_until_ms;
    store.browser_protection_required = required;
    store.browser_protection_locked_until_ms = 0;
    persist_store(&state.store_path, &store)?;
    let active = store.browser_protection_active();
    drop(store);
    // A pause made before adding website rules must not leave the guard off.
    if active { state.start(app); }
    Ok(active)
}

#[tauri::command]
pub fn set_browser_protection_enabled(
    app: AppHandle,
    enabled: bool,
    state: State<'_, TrackerRuntime>,
    repair: State<'_, BrowserRepairRuntime>,
) -> Result<bool, String> {
    let signed_in = app.state::<crate::auth::BrowserAuthRuntime>().is_signed_in();
    if enabled && !signed_in { return Err("Sign in to enable the extension checker".into()); }
    let mut store = state.store.lock().map_err(|_| "Tracking store lock was poisoned")?;
    let previous = store.browser_protection_enabled;
    store.browser_protection_enabled = enabled;
    store.browser_protection_locked_until_ms = 0;
    if let Err(error) = persist_store(&state.store_path, &store) {
        store.browser_protection_enabled = previous;
        return Err(error);
    }
    drop(store);
    if enabled { state.start(app); }
    else {
        repair.set(None);
        crate::browser_warning::sync_window(&app, None);
    }
    Ok(enabled)
}

#[tauri::command]
pub async fn open_browser_extension_settings(
    app_id: Option<String>,
    repair: State<'_, BrowserRepairRuntime>,
    state: State<'_, TrackerRuntime>,
) -> Result<(), String> {
    let target = app_id.or_else(|| {
        repair.shared_state().lock().ok().and_then(|state| {
            state.as_ref().map(|state| state.app_id.clone())
        })
    }).ok_or("No browser to repair")?.to_ascii_lowercase();
    if crate::browser_guard::is_unsupported_browser(&target) {
        return Err("Firefox is not supported for FocusLock extension protection. Use Chrome, Edge, Brave, Vivaldi, Opera, or Arc.".into());
    }
    if !crate::browser_bridge::supported_browser(&target) {
        return Err("Use Chrome, Edge, Brave, Vivaldi, Opera, or Arc with the FocusLock extension.".into());
    }
    let browser_paths = state.browser_paths.clone();
    let repair_resets = state.browser_repair_resets.clone();
    // Window discovery and browser startup can take several seconds. Keep the
    // webview responsive while the launcher waits for its dedicated window.
    tauri::async_runtime::spawn_blocking(move || {
        let observed = get_running_windows().unwrap_or_default().into_iter()
            .find(|window| window.app_id.eq_ignore_ascii_case(&target))
            .and_then(|window| window.executable_path)
            .or_else(|| browser_paths.lock().ok().and_then(|paths| paths.get(&target).cloned()));
        let path = crate::browser_launch::resolve_executable(&target, observed.as_deref())
            .ok_or("Could not locate the installed browser. Open its extensions page manually.")?;
        // Reserve a fresh repair cycle before opening Chrome, including if its old
        // window has just closed. The tracker consumes this before its next check.
        repair_resets.lock().map_err(|_| "Browser repair lock failed")?.push(target.clone());
        crate::browser_launch::launch_extensions_page(&path, &target)
    }).await.map_err(|error| format!("Could not open extension settings: {error}"))?
}

#[tauri::command]
pub fn set_tracker_config(
    config: TrackerConfig,
    state: State<'_, TrackerRuntime>,
) -> Result<TrackerConfig, String> {
    let normalized = config.normalized();
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Tracking store lock was poisoned".to_string())?;
    store.config = normalized.clone();
    persist_store(&state.store_path, &store)?;
    Ok(normalized)
}
#[tauri::command]
pub fn get_permanent_targets(state: State<'_, TrackerRuntime>) -> Result<Vec<String>, String> {
    Ok(state
        .store
        .lock()
        .map_err(|_| "Tracking store lock was poisoned".to_string())?
        .permanent_targets
        .clone())
}

/// Add device-local permanent blocks. There is deliberately no companion
/// remove/clear command: permanence is irreversible from inside FocusLock,
/// mirroring Android's `canRemoveInApp() = false`.
#[tauri::command]
pub fn add_permanent_targets(
    app_ids: Vec<String>,
    app: AppHandle,
    state: State<'_, TrackerRuntime>,
    blocker: State<'_, BlockerRuntime>,
) -> Result<PermanentAddResult, String> {
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Tracking store lock was poisoned".to_string())?;
    let (added, rejected) = plan_permanent_additions(&store.permanent_targets, app_ids);
    if !added.is_empty() {
        let mut permanent = store.permanent_targets.clone();
        permanent.extend(added.iter().cloned());
        store.permanent_targets = clean_values(permanent);
        // Merge into the enforcement set immediately: the permanent block must
        // hold from this moment, before the next UI/Convex flush arrives.
        // (Clone/assign because `store` is a MutexGuard: the borrow checker
        // cannot split disjoint fields through DerefMut.)
        let permanent = store.permanent_targets.clone();
        let mut blocked_targets = store.blocked_targets.clone();
        let mut blocked_reasons = store.blocked_reasons.clone();
        merge_permanent_targets(&mut blocked_targets, &mut blocked_reasons, &permanent);
        store.blocked_targets = blocked_targets;
        store.blocked_reasons = blocked_reasons;
        let matcher = BlockedMatcher::new(&store.blocked_targets, &store.blocked_reasons);
        persist_store(&state.store_path, &store)?;
        let empty = store.blocked_targets.app_ids.is_empty() && store.blocked_targets.domains.is_empty();
        drop(store);
        if let Ok(mut value) = state.matcher.lock() {
            *value = Arc::new(matcher);
        }
        // Only take down a stranded blocker when nothing is left to enforce.
        // Adding a permanent target never hides an active overlay.
        if empty {
            blocker.hide(&app);
        } else {
            state.start(app.clone());
        }
    }
    Ok(PermanentAddResult { added, rejected })
}

#[tauri::command]
pub fn set_blocked_targets(
    targets: BlockedTargets,
    reasons: Option<HashMap<String, String>>,
    app: AppHandle,
    state: State<'_, TrackerRuntime>,
    blocker: State<'_, BlockerRuntime>,
) -> Result<BlockedTargets, String> {
    let mut normalized = targets.normalized();
    let mut normalized_reasons = clean_reasons(reasons.unwrap_or_default());
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Tracking store lock was poisoned".to_string())?;
    // Permanence is device-local and authoritative. Union it in AFTER the
    // caller's payload so no UI/Convex flush can drop a permanent id, and force
    // the reason so frog/limit/blocked can never downgrade it.
    let permanent = store.permanent_targets.clone();
    merge_permanent_targets(&mut normalized, &mut normalized_reasons, &permanent);
    remove_disabled_frog_targets(&mut normalized, &mut normalized_reasons);
    let matcher = BlockedMatcher::new(&normalized, &normalized_reasons);
    store.blocked_targets = normalized.clone();
    store.blocked_reasons = normalized_reasons;
    persist_store(&state.store_path, &store)?;
    drop(store);
    if let Ok(mut value) = state.matcher.lock() {
        *value = Arc::new(matcher);
    }
    // Nothing left to enforce: make sure a visible blocker is not stranded.
    if normalized.app_ids.is_empty() && normalized.domains.is_empty() {
        blocker.hide(&app);
    } else {
        // A pause made before adding rules must not create an enforcement bypass.
        state.start(app.clone());
    }
    Ok(normalized)
}
#[tauri::command]
pub fn clear_tracking_data(state: State<'_, TrackerRuntime>) -> Result<TrackingSnapshot, String> {
    {
        let mut store = state
            .store
            .lock()
            .map_err(|_| "Tracking store lock was poisoned".to_string())?;
        store.usage.clear();
        store.dirty = true;
        store.usage_generation = store.usage_generation.wrapping_add(1);
        persist_store(&state.store_path, &store)?;
    }
    state.snapshot()
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}
fn load_store(path: &Path) -> Result<TrackingStore, String> {
    if !path.exists() {
        let store = TrackingStore::new();
        persist_store(path, &store)?;
        return Ok(store);
    }
    let raw = fs::read(path).map_err(|e| format!("Could not read tracking data: {e}"))?;
    let mut store: TrackingStore =
        serde_json::from_slice(&raw).map_err(|e| format!("Tracking data is invalid: {e}"))?;
    store.config = store.config.normalized();
    store.blocked_targets = store.blocked_targets.normalized();
    store.blocked_reasons = clean_reasons(store.blocked_reasons);
    // Permanence is normalized first, then defensively merged into the
    // enforcement set here — before any command runs — so a restart enforces
    // permanent blocks from the very first tracker sample. The full protected
    // set is stripped (not just FocusLock itself): a hand-edited store file
    // must not be able to permanently block the Windows shell.
    store.permanent_targets = clean_values(store.permanent_targets);
    store.permanent_targets.retain(|id| !is_protected_app_id(id));
    let permanent = store.permanent_targets.clone();
    merge_permanent_targets(&mut store.blocked_targets, &mut store.blocked_reasons, &permanent);
    remove_disabled_frog_targets(&mut store.blocked_targets, &mut store.blocked_reasons);
    // Enforce the retention window once at startup; stale days beyond it are
    // rewritten out of the file on the next dirty persist.
    store.prune_old_days();
    store.last_pruned_date = Some(Local::now().format("%Y-%m-%d").to_string());
    store.dirty = true;
    Ok(store)
}
fn persist_store(path: &Path, store: &TrackingStore) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("Could not create tracking data directory: {e}"))?
    }
    let bytes = serde_json::to_vec_pretty(store)
        .map_err(|e| format!("Could not encode tracking data: {e}"))?;
    fs::write(path, bytes).map_err(|e| format!("Could not save tracking data: {e}"))
}

fn update_browser_repair_guard(
    app: &AppHandle,
    runtime: Option<&BrowserRepairRuntime>,
    guard: &mut BrowserGuard,
    lease_dir: &Path,
    required: bool,
    guard_now: u64,
    wall_now: u64,
    last_error: &Arc<Mutex<Option<String>>>,
) -> BrowserProtectionScan {
    let mut known_windows = HashMap::new();
    let mut samples = HashMap::new();
    let mut scan_error = None;
    // Discover open browser windows independently of usage capture. Looking
    // at Settings, another app, or an unreadable foreground must not erase the
    // checker's status or prevent a newly opened browser from being checked.
    if required {
        match get_browser_windows() {
            Ok(windows) => for window in windows {
                known_windows.insert(window.window_handle, (window.process_id, window.app_id.clone()));
                samples.insert(window.window_handle, BrowserWindowSample {
                    healthy: crate::browser_bridge::read_window_health(
                        lease_dir, window.window_handle, window.process_id, &window.app_id, wall_now),
                    window_handle: window.window_handle,
                    process_id: window.process_id,
                    app_id: window.app_id,
                    browser: window.app_name,
                });
            },
            Err(error) => scan_error = Some(error),
        }
    }
    // Recheck already-pending handles even when a window is minimized or
    // has lost foreground. Invalid/reused handles are omitted and the policy
    // drops their stale deadlines. Newly discovered browser windows also get
    // their own deadline even while FocusLock's Settings is foreground.
    for (hwnd, expected_pid, expected_app) in guard.pending_identities() {
        if let Some(identity) = browser_window_identity(hwnd) {
            if identity.process_id == expected_pid
                && identity.app_id.eq_ignore_ascii_case(&expected_app)
            {
                known_windows.insert(hwnd, (identity.process_id, identity.app_id.clone()));
                samples.insert(hwnd, BrowserWindowSample {
                    healthy: required
                        && crate::browser_bridge::supported_browser(&identity.app_id)
                        && crate::browser_bridge::read_window_health(
                            lease_dir, hwnd, identity.process_id, &identity.app_id, wall_now,
                        ),
                    window_handle: hwnd,
                    process_id: identity.process_id,
                    app_id: identity.app_id,
                    browser: identity.app_name,
                });
            }
        }
    }

    let samples: Vec<_> = samples.into_values().collect();
    let (scan, repair_state, due) = observe_browser_samples(
        guard, required, &samples, &known_windows, guard_now, wall_now, scan_error);
    if let Some(runtime) = runtime {
        runtime.set(repair_state.clone());
    }
    crate::browser_warning::sync_window(app, repair_state.as_ref());

    for window in due {
        // A settings click or disabled switch may arrive while the
        // sample was being collected. Recheck before sending a close request.
        let Some(tracker) = app.try_state::<TrackerRuntime>() else { continue };
        let resetting = tracker.browser_repair_resets.lock().map(|resets|
            resets.iter().any(|id| id.eq_ignore_ascii_case(&window.app_id))).unwrap_or(true);
        if resetting || !tracker.browser_protection_active()
            || crate::browser_bridge::read_window_health(
                lease_dir, window.window_handle, window.process_id, &window.app_id, now_ms()) {
            continue;
        }
        if let Err(error) = request_browser_window_close(
            window.window_handle,
            window.process_id,
            &window.app_id,
        ) {
            // The next repair cycle gets its own full grace interval.
            guard.retry_close(window.window_handle);
            if let Ok(mut last) = last_error.lock() {
                *last = Some(error);
            }
        }
    }
    scan
}

/// Build both Settings status and the notice from one complete browser scan.
/// There is deliberately no foreground-app input to this state transition.
fn observe_browser_samples(
    guard: &mut BrowserGuard,
    required: bool,
    samples: &[BrowserWindowSample],
    known_windows: &HashMap<isize, (u32, String)>,
    guard_now: u64,
    wall_now: u64,
    error: Option<String>,
) -> (BrowserProtectionScan, Option<crate::browser_guard::BrowserRepairState>, Vec<BrowserWindowSample>) {
    let (repair, due) = guard.update(required, samples, known_windows, guard_now);
    let selected = if required {
        guard.repair_window_handle().and_then(|hwnd| samples.iter().find(|window| window.window_handle == hwnd))
            .or_else(|| samples.iter().min_by_key(|window| window.window_handle))
    } else { None };
    let browser = selected.map(|window| BrowserProtectionStatus {
        browser: window.browser.clone(),
        healthy: window.healthy,
        grace_remaining_seconds: repair.as_ref().map(|state| state.grace_remaining_seconds).unwrap_or(0),
        reason: repair.as_ref().map(|state| state.reason.to_string()),
        window_handle: window.window_handle,
        process_id: window.process_id,
        app_id: window.app_id.clone(),
        checked_at_ms: wall_now,
    });
    let state = if !required { "idle" }
        else if error.is_some() { "scan_error" }
        else if browser.is_some() { "browser" }
        else { "no_browser" };
    (BrowserProtectionScan { state, browser, error }, repair, due)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enabled_frog_targets_survive_reload_alongside_permanent_targets() {
        let path = std::env::temp_dir().join(format!("focuslock-frog-enabled-{}.json", Uuid::new_v4()));
        let mut store = TrackingStore::new();
        store.blocked_targets = BlockedTargets {
            app_ids: vec!["frog-only.exe".into(), "permanent.exe".into(), "limited.exe".into()],
            domains: vec!["frog.example".into(), "boundary.example".into()],
        };
        store.blocked_reasons = HashMap::from([
            ("frog-only.exe".into(), "frog".into()),
            ("permanent.exe".into(), "frog".into()),
            ("limited.exe".into(), "limit".into()),
            ("frog.example".into(), "frog".into()),
            ("boundary.example".into(), "blocked".into()),
        ]);
        store.permanent_targets = vec!["permanent.exe".into()];
        persist_store(&path, &store).unwrap();
        let restored = load_store(&path).unwrap();
        assert_eq!(restored.blocked_targets.app_ids, ["frog-only.exe", "limited.exe", "permanent.exe"]);
        assert_eq!(restored.blocked_targets.domains, ["boundary.example", "frog.example"]);
        assert_eq!(restored.blocked_reasons.get("permanent.exe").map(String::as_str), Some(PERMANENT_REASON));
        assert_eq!(restored.blocked_reasons.get("limited.exe").map(String::as_str), Some("limit"));
        assert_eq!(restored.blocked_reasons.get("frog-only.exe").map(String::as_str), Some("frog"));
        assert_eq!(restored.blocked_reasons.get("frog.example").map(String::as_str), Some("frog"));
        let _ = fs::remove_file(path);
    }

    fn observation(idle: bool, domain: Option<&str>) -> ActivityObservation {
        ActivityObservation {
            captured_at_ms: 1,
            app_id: "chrome.exe".into(),
            app_name: "Google Chrome".into(),
            executable_path: None,
            window_title: "FocusLock - Google Chrome".into(),
            browser_domain: domain.map(str::to_string),
            device_id: "device-a".into(),
            device_name: "Desk".into(),
            idle,
            idle_millis: Some(if idle { 90_000 } else { 0 }),
            blocked: false,
        }
    }
    #[test]
    fn aggregates_by_app_site_and_device() {
        let mut store = TrackingStore::new();
        store.device.id = "device-a".into();
        store.record(&observation(false, Some("youtube.com")), 1);
        store.record(&observation(false, Some("youtube.com")), 2);
        store.record(&observation(false, Some("reddit.com")), 1);
        assert_eq!(store.usage.len(), 2);
        assert!(store
            .usage
            .values()
            .any(|e| e.browser_domain.as_deref() == Some("youtube.com") && e.active_seconds == 3));
    }
    #[test]
    fn skips_idle_and_blocked() {
        let mut store = TrackingStore::new();
        store.record(&observation(true, None), 10);
        let mut blocked = observation(false, None);
        blocked.blocked = true;
        store.record(&blocked, 10);
        assert!(store.usage.is_empty());
    }
    #[test]
    fn device_survives_reload() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("usage.json");
        assert_eq!(
            load_store(&path).unwrap().device,
            load_store(&path).unwrap().device
        );
    }
    fn captured_window(app_id: &str, domain: Option<&str>) -> CapturedWindow {
        CapturedWindow {
            window_handle: 0,
            process_id: 0,
            app_id: app_id.into(),
            app_name: "Chrome".into(),
            executable_path: None,
            window_title: String::new(),
            browser_domain: domain.map(str::to_string),
        }
    }
    #[test]
    fn website_targets_are_not_enforced_natively_but_explicit_app_targets_are() {
        let targets = BlockedTargets {
            app_ids: vec!["chrome.exe".into()],
            domains: vec!["youtube.com".into()],
        };
        let matcher = BlockedMatcher::new(&targets, &HashMap::new());
        let matched = matcher.match_target(&captured_window("chrome.exe", Some("m.youtube.com")))
            .expect("explicit browser app block should remain active");
        assert_eq!(matched.target, "chrome.exe");
        assert_eq!(matched.kind, "app");
        assert!(matcher.match_target(&captured_window("msedge.exe", Some("m.youtube.com"))).is_none());
    }
    #[test]
    fn reasons_are_looked_up_by_explicit_app_target_only() {
        let targets = BlockedTargets {
            app_ids: vec!["steam.exe".into()],
            domains: vec!["youtube.com".into()],
        };
        let reasons = HashMap::from([
            ("steam.exe".to_string(), "limit".to_string()),
            ("youtube.com".to_string(), "frog".to_string()),
        ]);
        let matcher = BlockedMatcher::new(&targets, &reasons);
        let app = matcher
            .match_target(&captured_window("steam.exe", None))
            .unwrap();
        assert_eq!(app.kind, "app");
        assert_eq!(app.reason, "limit");
        assert!(matcher.match_target(&captured_window("chrome.exe", Some("m.youtube.com"))).is_none());
    }
    #[test]
    fn own_process_is_never_blocked() {
        let targets = BlockedTargets {
            app_ids: vec![own_app_id().to_string()],
            domains: vec![],
        };
        let matcher = BlockedMatcher::new(&targets, &HashMap::new());
        assert!(matcher.match_target(&captured_window(own_app_id(), None)).is_none());
        assert!(BlockedTargets {
            app_ids: vec![own_app_id().to_uppercase()],
            domains: vec![],
        }
        .normalized()
        .app_ids
        .is_empty());
    }
    #[test]
    fn reason_normalization_cleans_keys_and_values() {
        let cleaned = clean_reasons(HashMap::from([
            (" www.YouTube.com ".to_string(), " FROG ".to_string()),
            (String::new(), "limit".to_string()),
        ]));
        assert_eq!(cleaned.get("youtube.com").map(String::as_str), Some("frog"));
        assert_eq!(cleaned.len(), 1);
    }
    #[test]
    fn permanent_merge_cannot_be_dropped_by_a_caller_payload() {
        // Mirrors the merge inside `set_blocked_targets`: a UI/Convex payload
        // omits the permanent id, the store's permanent list is unioned back in
        // with the "permanent" reason and stays enforced.
        let permanent = vec!["steam.exe".to_string()];
        let mut targets = BlockedTargets {
            app_ids: vec!["discord.exe".into()],
            domains: vec![],
        };
        let mut reasons = HashMap::from([("steam.exe".to_string(), "frog".to_string())]);
        merge_permanent_targets(&mut targets, &mut reasons, &permanent);
        assert!(targets.app_ids.contains(&"steam.exe".to_string()));
        assert_eq!(
            reasons.get("steam.exe").map(String::as_str),
            Some(PERMANENT_REASON)
        );
        let matcher = BlockedMatcher::new(&targets, &reasons);
        let matched = matcher
            .match_target(&captured_window("steam.exe", None))
            .expect("permanent app stays enforced");
        assert_eq!(matched.reason, PERMANENT_REASON);
    }
    #[test]
    fn permanent_reason_wins_over_frog_limit_and_blocked() {
        let permanent = vec!["steam.exe".to_string(), "discord.exe".to_string()];
        let mut targets = BlockedTargets {
            app_ids: vec![],
            domains: vec![],
        };
        let mut reasons = HashMap::from([
            ("steam.exe".to_string(), "frog".to_string()),
            ("discord.exe".to_string(), "limit".to_string()),
        ]);
        merge_permanent_targets(&mut targets, &mut reasons, &permanent);
        assert_eq!(
            reasons.get("steam.exe").map(String::as_str),
            Some(PERMANENT_REASON)
        );
        assert_eq!(
            reasons.get("discord.exe").map(String::as_str),
            Some(PERMANENT_REASON)
        );
    }
    #[test]
    fn protected_and_duplicate_additions_are_rejected() {
        let existing = vec!["steam.exe".to_string()];
        let (added, rejected) = plan_permanent_additions(
            &existing,
            vec![
                " Steam.exe ".into(),
                "explorer.exe".into(),
                own_app_id().to_string(),
                "discord.exe".into(),
                "discord.exe".into(),
            ],
        );
        assert_eq!(added, vec!["discord.exe".to_string()]);
        assert_eq!(rejected.len(), 4);
        let reasons: HashMap<_, _> = rejected
            .iter()
            .map(|entry| (entry.id.as_str(), entry.reason.as_str()))
            .collect();
        assert_eq!(reasons.get("steam.exe"), Some(&"duplicate"));
        assert_eq!(reasons.get("discord.exe"), Some(&"duplicate"));
        assert_eq!(reasons.get("explorer.exe"), Some(&"protected"));
        assert_eq!(reasons.get(own_app_id()), Some(&"protected"));
    }
    #[test]
    fn permanent_targets_survive_reload_and_re_merge_into_enforcement() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("usage.json");
        let mut store = load_store(&path).unwrap();
        store.permanent_targets = vec![" Steam.exe ".into(), own_app_id().to_string()];
        persist_store(&path, &store).unwrap();
        let reloaded = load_store(&path).unwrap();
        assert_eq!(reloaded.permanent_targets, vec!["steam.exe".to_string()]);
        assert!(reloaded
            .blocked_targets
            .app_ids
            .contains(&"steam.exe".to_string()));
        assert_eq!(
            reloaded.blocked_reasons.get("steam.exe").map(String::as_str),
            Some(PERMANENT_REASON)
        );
    }
    #[test]
    fn protected_permanents_are_stripped_on_load() {
        // A hand-edited store file must not be able to permanently block the
        // Windows shell: load normalization strips the whole protected set.
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("usage.json");
        let mut store = load_store(&path).unwrap();
        store.permanent_targets = vec![
            " Steam.exe ".into(),
            "explorer.exe".into(),
            own_app_id().to_string(),
            "discord.exe".into(),
        ];
        persist_store(&path, &store).unwrap();
        let reloaded = load_store(&path).unwrap();
        assert_eq!(
            reloaded.permanent_targets,
            vec!["discord.exe".to_string(), "steam.exe".to_string()]
        );
        assert!(!reloaded
            .blocked_targets
            .app_ids
            .contains(&"explorer.exe".to_string()));
    }
    #[test]
    fn pause_is_refused_while_permanent_blocks_exist() {
        let dir = tempfile::tempdir().unwrap();
        let runtime = TrackerRuntime::load(dir.path().join("usage.json")).unwrap();
        assert!(runtime.ensure_pause_allowed().is_ok());
        runtime
            .store
            .lock()
            .unwrap()
            .permanent_targets
            .push("steam.exe".into());
        let error = runtime
            .ensure_pause_allowed()
            .expect_err("pausing must be refused while a permanent block exists");
        assert!(error.contains("Permanent blocks are active"));
        runtime.store.lock().unwrap().permanent_targets.clear();
        assert!(runtime.ensure_pause_allowed().is_ok());
    }
    #[test]
    fn pause_and_quit_gate_survives_restart_until_active_boundaries_are_cleared() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("usage.json");
        let mut store = TrackingStore::new();
        store.blocked_targets.app_ids.push("steam.exe".into());
        persist_store(&path, &store).unwrap();
        let runtime = TrackerRuntime::load(path).unwrap();
        assert!(runtime.ensure_pause_allowed().unwrap_err().contains("Active boundaries"));
        let mut store = runtime.store.lock().unwrap();
        store.blocked_targets.app_ids.clear();
        store.blocked_targets.domains.push("example.com".into());
        drop(store);
        assert!(runtime.ensure_pause_allowed().is_err());
        runtime.store.lock().unwrap().blocked_targets.domains.clear();
        assert!(runtime.ensure_pause_allowed().is_ok());
    }
    #[test]
    fn browser_checker_keeps_opted_in_protection_after_auth_loss_and_restart() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("usage.json");
        let mut store = TrackingStore::new();
        store.browser_protection_required = true;
        store.browser_protection_enabled = true;
        assert!(store.browser_protection_active());
        persist_store(&path, &store).unwrap();
        let runtime = TrackerRuntime::load(path).unwrap();
        assert!(runtime.status().browser_protection_enabled);
        assert!(runtime.status().browser_protection_required);
        assert!(runtime.ensure_pause_allowed().unwrap_err().contains("Browser extension protection"));
        let mut store = runtime.store.lock().unwrap();
        store.browser_protection_required = false;
        store.browser_protection_locked_until_ms = now_ms() + 60_000;
        store.blocked_targets.domains.push("example.com".into());
        assert!(!store.browser_protection_active(), "Strict and blocked domains do not force this option on");
        store.browser_protection_required = true;
        store.browser_protection_enabled = false;
        assert!(!store.browser_protection_active());
        store.browser_protection_enabled = true;
        assert!(store.browser_protection_active(), "Auth loss cannot release a configured website rule");
    }

    #[test]
    fn legacy_store_defaults_checker_to_off_despite_stale_policy() {
        let mut value = serde_json::to_value(TrackingStore::new()).unwrap();
        value.as_object_mut().unwrap().remove("browserProtectionEnabled");
        value["browserProtectionRequired"] = serde_json::json!(true);
        value["browserProtectionLockedUntilMs"] = serde_json::json!(u64::MAX);
        let store: TrackingStore = serde_json::from_value(value).unwrap();
        assert!(!store.browser_protection_enabled);
        assert!(!store.browser_protection_active());
    }
}
