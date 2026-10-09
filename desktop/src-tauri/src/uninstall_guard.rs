//! Durable, account-scoped Strict Mode uninstall locks.
//!
//! This is a best-effort user-level guard. It deliberately does not install a
//! service, watchdog, or UI; callers may use [`maybe_run_uninstall_check`]
//! before initializing the normal desktop application.

use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct Lock {
    account_id: String,
    session_id: Option<String>,
    /// Unix epoch milliseconds. `None` represents a legacy indefinite lock.
    ends_at: Option<u64>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Store {
    locks: Vec<Lock>,
}

/// Thread-safe runtime. State is intentionally read from disk for every
/// operation so startup helpers and app commands always observe fresh data.
pub struct UninstallGuardRuntime {
    path: PathBuf,
    operation: Mutex<()>,
}

impl UninstallGuardRuntime {
    /// Construct without touching disk; missing files mean no persisted lock.
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self {
            path: path.into(),
            operation: Mutex::new(()),
        }
    }

    pub fn sync(
        &self,
        account_id: &str,
        enabled: bool,
        ends_at: Option<u64>,
        session_id: Option<&str>,
        approved_session_id: Option<&str>,
        approved_ends_at: Option<u64>,
    ) -> Result<(), String> {
        let _guard = self
            .operation
            .lock()
            .map_err(|_| "uninstall guard lock poisoned".to_string())?;
        let mut store = read_store(&self.path)?;

        // An explicit approval is a compare-and-clear operation. A missing
        // session or expiry can never clear a lock.
        if let (Some(session), Some(expiry)) = (approved_session_id, approved_ends_at) {
            store.locks.retain(|lock| {
                !(lock.account_id == account_id
                    && lock.session_id.as_deref() == Some(session)
                    && lock.ends_at == Some(expiry))
            });
        }

        // Legacy indefinite strict state has no timed commitment and follows
        // the same account's authoritative disabled snapshot. Timed locks are
        // cleared only by the exact approval compare-and-clear above.
        if !enabled {
            store
                .locks
                .retain(|lock| !(lock.account_id == account_id && lock.ends_at.is_none()));
        }

        if enabled {
            let incoming = Lock {
                account_id: account_id.to_owned(),
                session_id: session_id.map(str::to_owned),
                ends_at,
            };
            if let Some(existing) = store
                .locks
                .iter_mut()
                .find(|lock| lock.account_id == incoming.account_id)
            {
                // An explicit timed activation migrates a legacy indefinite
                // record. Once finite, its expiry remains monotonic.
                let migrate_legacy = existing.ends_at.is_none() && incoming.ends_at.is_some();
                if migrate_legacy || expiry_rank(incoming.ends_at) >= expiry_rank(existing.ends_at)
                {
                    existing.ends_at = if migrate_legacy {
                        incoming.ends_at
                    } else {
                        max_expiry(existing.ends_at, incoming.ends_at)
                    };
                    if incoming
                        .session_id
                        .as_ref()
                        .is_some_and(|id| !id.is_empty())
                    {
                        existing.session_id = incoming.session_id;
                    }
                }
            } else {
                store.locks.push(incoming);
            }
        }

        write_store(&self.path, &store)
    }

    /// Returns true when any account has an active (or indefinite) lock.
    pub fn check(&self, now_ms: u64) -> Result<bool, String> {
        let _guard = self
            .operation
            .lock()
            .map_err(|_| "uninstall guard lock poisoned".to_string())?;
        check_path(&self.path, now_ms)
    }
}

/// Tauri command wrapper; register this command and manage the runtime as
/// application state in the host.
#[tauri::command]
pub fn sync_strict_uninstall_guard(
    runtime: tauri::State<'_, UninstallGuardRuntime>,
    account_id: String,
    enabled: bool,
    ends_at: Option<u64>,
    session_id: Option<String>,
    approved_session_id: Option<String>,
    approved_ends_at: Option<u64>,
) -> Result<(), String> {
    runtime.sync(
        &account_id,
        enabled,
        ends_at,
        session_id.as_deref(),
        approved_session_id.as_deref(),
        approved_ends_at,
    )
}

fn max_expiry(current: Option<u64>, incoming: Option<u64>) -> Option<u64> {
    match (current, incoming) {
        (None, _) | (_, None) => None,
        (Some(a), Some(b)) => Some(a.max(b)),
    }
}

fn expiry_rank(expiry: Option<u64>) -> u128 {
    expiry.map(u128::from).unwrap_or(u128::MAX)
}

fn read_store(path: &Path) -> Result<Store, String> {
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map_err(|e| format!("strict uninstall state is corrupt: {e}")),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Store::default()),
        Err(e) => Err(format!("cannot read strict uninstall state: {e}")),
    }
}

/// Pure file check used by the early CLI path and tests. Missing means clear;
/// any other read/parse failure propagates so the caller fails closed.
pub fn check_path(path: &Path, now_ms: u64) -> Result<bool, String> {
    let store = read_store(path)?;
    Ok(store.locks.iter().any(|lock| match lock.ends_at {
        None => true,
        Some(expiry) => expiry > now_ms,
    }))
}

fn write_store(path: &Path, store: &Store) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "strict uninstall state has no parent directory".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|e| format!("cannot create uninstall state directory: {e}"))?;
    let bytes =
        serde_json::to_vec(store).map_err(|e| format!("cannot encode uninstall state: {e}"))?;
    let temp = path.with_extension(format!("tmp-{}", std::process::id()));
    let result = (|| {
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&temp)
            .map_err(|e| format!("cannot create temporary uninstall state: {e}"))?;
        file.write_all(&bytes)
            .map_err(|e| format!("cannot write uninstall state: {e}"))?;
        file.sync_all()
            .map_err(|e| format!("cannot flush uninstall state: {e}"))?;
        replace_file(&temp, path)?;
        // Best effort directory metadata flush on platforms that support it.
        if let Ok(dir) = File::open(parent) {
            let _ = dir.sync_all();
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}

#[cfg(windows)]
fn replace_file(from: &Path, to: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    let from_w: Vec<u16> = from.as_os_str().encode_wide().chain(Some(0)).collect();
    let to_w: Vec<u16> = to.as_os_str().encode_wide().chain(Some(0)).collect();
    unsafe {
        MoveFileExW(
            windows::core::PCWSTR(from_w.as_ptr()),
            windows::core::PCWSTR(to_w.as_ptr()),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    }
    .map_err(|e| format!("cannot atomically replace uninstall state: {e}"))
}

#[cfg(not(windows))]
fn replace_file(from: &Path, to: &Path) -> Result<(), String> {
    fs::rename(from, to).map_err(|e| format!("cannot atomically replace uninstall state: {e}"))
}

/// Recognize the maintenance probe before Tauri starts. Exit codes are part of
/// the launcher contract: 0=allow, 10=active lock, 11=state could not be read.
pub fn maybe_run_uninstall_check(identifier: &str) -> bool {
    if !std::env::args().any(|arg| arg == "--focuslock-check-uninstall") {
        return false;
    }
    let result = std::env::var_os("APPDATA")
        .ok_or_else(|| "APPDATA is not set".to_string())
        .and_then(|appdata| {
            check_path(
                &PathBuf::from(appdata)
                    .join(identifier)
                    .join("strict-uninstall-v1.json"),
                now_ms(),
            )
        });
    let code = match result {
        Ok(false) => 0,
        Ok(true) => 10,
        Err(_) => 11,
    };
    std::process::exit(code);
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn runtime(dir: &tempfile::TempDir) -> UninstallGuardRuntime {
        UninstallGuardRuntime::new(dir.path().join("strict-uninstall-v1.json"))
    }

    #[test]
    fn missing_file_is_clear_and_reload_observes_expiry() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("guard.json");
        assert_eq!(check_path(&path, 100).unwrap(), false);
        let guard = UninstallGuardRuntime::new(&path);
        guard
            .sync("a", true, Some(200), Some("s"), None, None)
            .unwrap();
        assert!(UninstallGuardRuntime::new(&path).check(199).unwrap());
        assert!(!check_path(&path, 200).unwrap());
    }

    #[test]
    fn false_and_other_account_do_not_clear_lock_and_expiry_only_extends() {
        let dir = tempfile::tempdir().unwrap();
        let guard = runtime(&dir);
        guard
            .sync("a", true, Some(500), Some("s"), None, None)
            .unwrap();
        guard
            .sync("a", true, Some(300), Some("s"), None, None)
            .unwrap();
        guard.sync("a", false, None, Some("s"), None, None).unwrap();
        guard.sync("b", false, None, Some("s"), None, None).unwrap();
        assert!(guard.check(499).unwrap());
        assert!(!guard.check(500).unwrap());
    }

    #[test]
    fn only_exact_explicit_approval_clears_timed_and_account_disable_clears_legacy() {
        let dir = tempfile::tempdir().unwrap();
        let guard = runtime(&dir);
        guard
            .sync("a", true, Some(500), Some("s"), None, None)
            .unwrap();
        guard
            .sync("a", false, None, None, Some("s"), Some(499))
            .unwrap();
        assert!(guard.check(100).unwrap());
        guard
            .sync("b", false, None, None, Some("s"), Some(500))
            .unwrap();
        assert!(guard.check(100).unwrap());
        guard
            .sync("a", false, None, None, Some("s"), Some(500))
            .unwrap();
        assert!(!guard.check(100).unwrap());
        guard
            .sync("a", true, None, Some("legacy"), None, None)
            .unwrap();
        guard
            .sync("a", false, None, Some("legacy"), None, None)
            .unwrap();
        assert!(!guard.check(u64::MAX).unwrap());
    }

    #[test]
    fn malformed_state_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("guard.json");
        fs::write(&path, b"{").unwrap();
        assert!(check_path(&path, 0).is_err());
        let guard = UninstallGuardRuntime::new(path);
        assert!(guard.sync("a", false, None, None, None, None).is_err());
    }

    #[test]
    fn extension_requires_current_exact_approval_and_adopts_provisional_session() {
        let dir = tempfile::tempdir().unwrap();
        let guard = runtime(&dir);
        guard.sync("a", true, Some(500), None, None, None).unwrap();
        guard
            .sync("a", true, Some(500), Some("backend-session"), None, None)
            .unwrap();
        guard
            .sync("a", true, Some(700), Some("extended-session"), None, None)
            .unwrap();
        // A shorter late sync cannot roll back the expiry or active session.
        guard
            .sync("a", true, Some(600), Some("stale-session"), None, None)
            .unwrap();
        // Approval for the stale shorter commitment cannot clear an extension.
        guard
            .sync("a", false, None, None, Some("backend-session"), Some(500))
            .unwrap();
        assert!(guard.check(100).unwrap());
        guard
            .sync("a", false, None, None, Some("extended-session"), Some(700))
            .unwrap();
        assert!(!guard.check(100).unwrap());
    }

    #[test]
    fn legacy_indefinite_follows_same_account_disable_and_can_migrate_to_timed() {
        let dir = tempfile::tempdir().unwrap();
        let guard = runtime(&dir);
        guard
            .sync("a", true, None, Some("legacy"), None, None)
            .unwrap();
        guard.sync("b", false, None, None, None, None).unwrap();
        assert!(guard.check(10_000).unwrap());
        // Explicit timed activation replaces legacy indefinite. Later shorter
        // syncs cannot reduce the finite commitment, and false cannot clear it.
        guard
            .sync("a", true, Some(500), Some("timed"), None, None)
            .unwrap();
        guard
            .sync("a", true, Some(400), Some("stale"), None, None)
            .unwrap();
        guard.sync("a", false, None, None, None, None).unwrap();
        assert!(guard.check(499).unwrap());
        assert!(!guard.check(500).unwrap());

        guard
            .sync("a", true, None, Some("legacy-again"), None, None)
            .unwrap();
        guard.sync("a", false, None, None, None, None).unwrap();
        assert!(!guard.check(100).unwrap());
    }
}
