//! Persistent account ownership and sign-out protection.
use std::fs;
use std::io::Write;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountProtectionPolicy {
    pub account_id: String,
    pub restricted: bool,
    pub strict_until_ms: Option<u64>,
}

pub struct AccountProtectionRuntime {
    path: PathBuf,
    policy: Mutex<Option<AccountProtectionPolicy>>,
}

impl AccountProtectionRuntime {
    pub fn load(path: impl AsRef<Path>) -> Result<Self, String> {
        let path = path.as_ref().to_path_buf();
        let policy = if path.exists() { Some(read_policy(&path)?) } else { None };
        Ok(Self { path, policy: Mutex::new(policy) })
    }

    pub fn update(&self, account_id: &str, restricted: bool, strict_until_ms: Option<u64>) -> Result<(), String> {
        validate_account_id(account_id)?;
        let mut guard = self.lock()?;
        if let Some(old) = guard.as_ref() {
            if old.account_id != account_id && (old.restricted || strict_active(old.strict_until_ms)) {
                return Err("cannot switch account while restrictions are active".into());
            }
        }
        let next = AccountProtectionPolicy { account_id: account_id.to_owned(), restricted, strict_until_ms };
        let previous = guard.clone();
        if let Err(error) = write_policy(&self.path, &next) {
            *guard = previous;
            return Err(error);
        }
        *guard = Some(next);
        Ok(())
    }

    pub fn with_sign_out_allowed<F, T>(&self, operation: F) -> Result<T, String>
    where F: FnOnce() -> Result<T, String> {
        let _guard = self.allowed_sign_out_guard()?;
        operation()
    }

    pub fn with_account_allowed<F, T>(&self, account_id: &str, operation: F) -> Result<T, String>
    where F: FnOnce() -> Result<T, String> {
        validate_account_id(account_id)?;
        let _guard = self.allowed_account_guard(account_id)?;
        operation()
    }

    /// Returns whether this account owns the persisted policy, for auth-session recovery.
    pub fn owns_account(&self, account_id: &str) -> Result<bool, String> {
        validate_account_id(account_id)?;
        let guard = self.lock()?;
        Ok(guard.as_ref().map(|policy| policy.account_id == account_id).unwrap_or(false))
    }

    fn allowed_sign_out_guard(&self) -> Result<MutexGuard<'_, Option<AccountProtectionPolicy>>, String> {
        let guard = self.lock()?;
        match guard.as_ref() {
            Some(policy) if !policy.restricted && !strict_active(policy.strict_until_ms) => Ok(guard),
            Some(_) => Err("sign out is unavailable while FocusLock restrictions are active".into()),
            None => Err("sign out is unavailable before account protection is initialized".into()),
        }
    }

    fn allowed_account_guard(&self, account_id: &str) -> Result<MutexGuard<'_, Option<AccountProtectionPolicy>>, String> {
        let guard = self.lock()?;
        match guard.as_ref() {
            None => Ok(guard),
            Some(policy) if policy.account_id == account_id => Ok(guard),
            Some(policy) if !policy.restricted && !strict_active(policy.strict_until_ms) => Ok(guard),
            Some(_) => Err("a different account cannot be used while restrictions are active".into()),
        }
    }

    fn lock(&self) -> Result<MutexGuard<'_, Option<AccountProtectionPolicy>>, String> {
        self.policy.lock().map_err(|_| "account protection lock poisoned".into())
    }
}

fn validate_account_id(id: &str) -> Result<(), String> {
    if id.trim().is_empty() { Err("account id must be nonempty".into()) } else { Ok(()) }
}

fn now_ms() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 }
fn strict_active(value: Option<u64>) -> bool {
    match value { Some(0) => true, Some(deadline) => deadline > now_ms(), None => false }
}

fn read_policy(path: &Path) -> Result<AccountProtectionPolicy, String> {
    let text = fs::read_to_string(path).map_err(|e| format!("read account protection: {e}"))?;
    let policy: AccountProtectionPolicy = serde_json::from_str(&text).map_err(|e| format!("invalid account protection: {e}"))?;
    validate_account_id(&policy.account_id)?;
    Ok(policy)
}

fn write_policy(path: &Path, policy: &AccountProtectionPolicy) -> Result<(), String> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent).map_err(|e| format!("create account protection directory: {e}"))?; }
    let temp = path.with_extension("tmp");
    let mut file = fs::File::create(&temp).map_err(|e| format!("write account protection: {e}"))?;
    let encoded = serde_json::to_vec_pretty(policy).map_err(|e| format!("serialize account protection: {e}"))?;
    file.write_all(&encoded).map_err(|e| format!("write account protection: {e}"))?;
    file.sync_all().map_err(|e| format!("sync account protection: {e}"))?;
    drop(file);
    fs::rename(&temp, path).map_err(|e| format!("replace account protection: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn path() -> (tempfile::TempDir, PathBuf) {
        let dir = tempdir().unwrap();
        let path = dir.path().join("account.json");
        (dir, path)
    }

    #[test]
    fn persists_across_restart_and_allows_clear() {
        let (_dir, path) = path();
        let runtime = AccountProtectionRuntime::load(&path).unwrap();
        runtime.update("a", true, None).unwrap();
        assert!(runtime.with_sign_out_allowed(|| Ok(())).is_err());
        drop(runtime);
        let runtime = AccountProtectionRuntime::load(&path).unwrap();
        assert!(runtime.with_account_allowed("a", || Ok(7)).is_ok());
        runtime.update("a", false, None).unwrap();
        assert!(runtime.with_sign_out_allowed(|| Ok(())).is_ok());
    }

    #[test]
    fn unknown_signout_is_blocked_but_first_account_is_allowed() {
        let (_dir, path) = path();
        let runtime = AccountProtectionRuntime::load(&path).unwrap();
        let mut ran = false;
        assert!(runtime.with_sign_out_allowed(|| { ran = true; Ok(()) }).is_err());
        assert!(!ran);
        assert!(runtime.with_account_allowed("first", || Ok(())).is_ok());
    }

    #[test]
    fn different_account_denied_and_same_owner_recovers_after_expiry() {
        let (_dir, path) = path();
        let runtime = AccountProtectionRuntime::load(&path).unwrap();
        runtime.update("a", false, Some(1)).unwrap();
        assert!(runtime.with_account_allowed("b", || Ok(())).is_ok());
        runtime.update("a", true, Some(1)).unwrap();
        assert!(runtime.with_account_allowed("b", || Ok(())).is_err());
        runtime.update("a", true, Some(0)).unwrap();
        assert!(runtime.with_account_allowed("b", || Ok(())).is_err());
        assert!(runtime.with_account_allowed("a", || Ok(())).is_ok());
        assert!(runtime.owns_account("a").unwrap());
    }

    #[test]
    fn failed_persist_keeps_old_policy() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("account.json");
        let runtime = AccountProtectionRuntime::load(&path).unwrap();
        runtime.update("a", false, None).unwrap();
        let bad = dir.path().join("blocked");
        fs::create_dir(&bad).unwrap();
        let runtime = AccountProtectionRuntime { path: bad, policy: runtime.policy };
        assert!(runtime.update("b", true, None).is_err());
        assert!(runtime.with_account_allowed("a", || Ok(())).is_ok());
    }

    #[test]
    fn blocked_signout_preserves_session_file() {
        let (_dir, path) = path();
        let session = path.with_file_name("session.json");
        fs::write(&session, "session").unwrap();
        let runtime = AccountProtectionRuntime::load(&path).unwrap();
        runtime.update("a", true, None).unwrap();
        let result = runtime.with_sign_out_allowed(|| {
            fs::remove_file(&session).map_err(|e| e.to_string())
        });
        assert!(result.is_err());
        assert!(session.exists());
    }
}
