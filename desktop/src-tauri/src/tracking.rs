use crate::windows_capture::{
    capture_foreground, get_running_windows, idle_seconds, minimize_foreground, CapturedWindow,
};
use chrono::Local;
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, State};
use uuid::Uuid;

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
        self.domains = clean_values(self.domains);
        self
    }

    fn matches(&self, captured: &CapturedWindow) -> bool {
        self.app_ids.iter().any(|id| id == &captured.app_id)
            || captured.browser_domain.as_ref().is_some_and(|domain| {
                self.domains
                    .iter()
                    .any(|blocked| domain == blocked || domain.ends_with(&format!(".{blocked}")))
            })
    }
}

fn clean_values(values: Vec<String>) -> Vec<String> {
    let mut values: Vec<_> = values
        .into_iter()
        .map(|v| v.trim().trim_start_matches("www.").to_ascii_lowercase())
        .filter(|v| !v.is_empty())
        .collect();
    values.sort();
    values.dedup();
    values
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
}

pub struct TrackerRuntime {
    running: Arc<AtomicBool>,
    store: Arc<Mutex<TrackingStore>>,
    current: Arc<Mutex<Option<ActivityObservation>>>,
    last_error: Arc<Mutex<Option<String>>>,
    worker: Mutex<Option<JoinHandle<()>>>,
    store_path: PathBuf,
    /// Latest sorted usage vec, keyed by the store's usage generation so
    /// repeated polls don't re-clone + re-sort the whole map.
    usage_cache: Mutex<Option<(u64, Arc<Vec<UsageEntry>>)>>,
}

impl TrackerRuntime {
    pub fn load(store_path: PathBuf) -> Result<Self, String> {
        Ok(Self {
            running: Arc::new(AtomicBool::new(false)),
            store: Arc::new(Mutex::new(load_store(&store_path)?)),
            current: Arc::new(Mutex::new(None)),
            last_error: Arc::new(Mutex::new(None)),
            worker: Mutex::new(None),
            store_path,
            usage_cache: Mutex::new(None),
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
        let worker = thread::Builder::new()
            .name("focuslock-activity-tracker".into())
            .spawn(move || {
                let mut previous_key = None;
                let mut samples_since_save = 0_u64;
                while running.load(Ordering::SeqCst) {
                    let (config, blocked_targets, device) = match store.lock() {
                        Ok(s) => (
                            s.config.clone(),
                            s.blocked_targets.clone(),
                            s.device.clone(),
                        ),
                        Err(_) => break,
                    };
                    let idle = idle_seconds()
                        .map(|v| v >= config.idle_threshold_seconds)
                        .unwrap_or(false);
                    match capture_foreground(config.capture_browser_domains) {
                        Ok(Some(captured)) => {
                            let blocked = blocked_targets.matches(&captured);
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
                                blocked,
                            };
                            let key = (
                                observation.app_id.clone(),
                                observation.browser_domain.clone(),
                                idle,
                                blocked,
                            );
                            if previous_key.as_ref() != Some(&key) {
                                let _ = app.emit("focuslock://activity-changed", &observation);
                                previous_key = Some(key);
                            }
                            if blocked {
                                match minimize_foreground() {
                                    Ok(()) => {
                                        let _ =
                                            app.emit("focuslock://target-blocked", &observation);
                                    }
                                    Err(error) => {
                                        if let Ok(mut last) = last_error.lock() {
                                            *last = Some(error);
                                        }
                                    }
                                }
                            }
                            if let Ok(mut value) = current.lock() {
                                *value = Some(observation.clone());
                            }
                            if let Ok(mut value) = store.lock() {
                                value.record(&observation, config.sample_interval_ms / 1_000);
                            }
                            if !blocked {
                                if let Ok(mut value) = last_error.lock() {
                                    *value = None;
                                }
                            }
                        }
                        Ok(None) => {
                            previous_key = None;
                            if let Ok(mut value) = current.lock() {
                                *value = None;
                            }
                        }
                        Err(error) => {
                            if let Ok(mut value) = last_error.lock() {
                                *value = Some(error);
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
        let enforcement_active = self
            .store
            .lock()
            .map(|s| !s.blocked_targets.app_ids.is_empty() || !s.blocked_targets.domains.is_empty())
            .unwrap_or(false);
        TrackerStatus {
            running: self.running.load(Ordering::SeqCst),
            enforcement_active,
            current: self.current.lock().ok().and_then(|v| v.clone()),
            last_error: self.last_error.lock().ok().and_then(|v| v.clone()),
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
pub fn stop_tracking(state: State<'_, TrackerRuntime>) -> TrackerStatus {
    state.stop();
    state.status()
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
pub fn set_blocked_targets(
    targets: BlockedTargets,
    state: State<'_, TrackerRuntime>,
) -> Result<BlockedTargets, String> {
    let normalized = targets.normalized();
    let mut store = state
        .store
        .lock()
        .map_err(|_| "Tracking store lock was poisoned".to_string())?;
    store.blocked_targets = normalized.clone();
    persist_store(&state.store_path, &store)?;
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

#[cfg(test)]
mod tests {
    use super::*;
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
    #[test]
    fn target_matching_includes_subdomains() {
        let targets = BlockedTargets {
            app_ids: vec![],
            domains: vec!["youtube.com".into()],
        };
        let captured = CapturedWindow {
            app_id: "chrome.exe".into(),
            app_name: "Chrome".into(),
            executable_path: None,
            window_title: String::new(),
            browser_domain: Some("m.youtube.com".into()),
        };
        assert!(targets.matches(&captured));
    }
}
