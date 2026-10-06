//! Runtime policy for the desktop browser-extension guard.
//!
//! The tracker supplies only committed address-bar URLs. A missing extension
//! gets one setup grace period per browser executable while protection stays
//! required; changing processes, tabs, or HWNDs does not restart it. Firefox
//! is recognized so it can fail closed as unsupported.

use std::collections::HashMap;
use url::Url;

pub const GRACE_MS: u64 = 60_000;
pub const RECOVERY_HANDOFF_MS: u64 = 15_000;

const MAX_BROWSER_INCIDENTS: usize = 256;
const MAX_RECOVERIES: usize = 256;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum BrowserKind {
    Chromium,
    Unsupported,
    Other,
}

#[derive(Clone, Copy, Debug)]
struct Incident {
    started_at_ms: u64,
}

#[derive(Clone, Copy, Debug, Default)]
struct Recovery {
    opened_while_missing: bool,
    healthy_until: Option<u64>,
}

/// In-memory extension health state. A browser executable's first missing
/// sample starts its grace; health and process restarts never reset that start.
/// An extension page opened while missing gets a 15-second return handoff once
/// that same process reports healthy.
#[derive(Debug, Default)]
pub struct BrowserGuard {
    incidents: HashMap<String, Incident>,
    recoveries: HashMap<(String, u32), Recovery>,
}

impl BrowserGuard {
    /// Evaluate a foreground sample. `committed_url` must be `None` while the
    /// address bar is being edited, so uncommitted text never opens a recovery
    /// exception or triggers an internal-page rule.
    pub fn evaluate(
        &mut self,
        app_id: &str,
        process_id: u32,
        required: bool,
        healthy: bool,
        committed_url: Option<&str>,
        now_ms: u64,
    ) -> Option<&'static str> {
        if !required {
            self.incidents.clear();
            self.recoveries.clear();
            return None;
        }

        match browser_kind(app_id) {
            BrowserKind::Other => return None,
            BrowserKind::Unsupported => return Some("browser_unsupported"),
            BrowserKind::Chromium => {}
        }

        let extension_page = committed_url.is_some_and(is_extension_page);
        let reset_page = committed_url.is_some_and(is_reset_page);

        let browser_id = normalized_browser_id(app_id);
        let recovery_key = (browser_id.clone(), process_id);
        if process_id != 0 && committed_url.is_some() && !extension_page {
            // Leaving extension management ends the short handoff. A later
            // visit is a new settings entry and remains blocked.
            self.recoveries.remove(&recovery_key);
        }

        if healthy {
            if reset_page {
                return Some("extension_settings");
            }
            if extension_page {
                if let Some(recovery) = self.recoveries.get_mut(&recovery_key) {
                    if recovery.opened_while_missing {
                        // Set once. A later health loss on the same page cannot
                        // renew the deadline.
                        let healthy_until = *recovery
                            .healthy_until
                            .get_or_insert_with(|| now_ms.saturating_add(RECOVERY_HANDOFF_MS));
                        if now_ms < healthy_until {
                            return None;
                        }
                    }
                }
                return Some("extension_settings");
            }
            return None;
        }

        let started_at_ms = if process_id == 0 {
            None
        } else {
            match self.incidents.get(&browser_id) {
                Some(incident) => Some(incident.started_at_ms),
                None => {
                    if !self.begin_incident(browser_id, now_ms) {
                        // At capacity, preserve all live incidents and fail closed
                        // for an untrackable browser executable.
                        return if extension_page && !reset_page {
                            None
                        } else {
                            Some("extension_missing")
                        };
                    }
                    Some(now_ms)
                }
            }
        };

        // A missing extension may recover on its management page. Reset pages
        // always block, but still start the browser's one-time grace period.
        if reset_page {
            return Some("extension_settings");
        }
        if extension_page {
            if process_id != 0 {
                self.note_recovery_page(recovery_key);
            }
            return None;
        }

        let Some(started_at_ms) = started_at_ms else {
            return Some("extension_missing");
        };
        (now_ms.saturating_sub(started_at_ms) >= GRACE_MS).then_some("extension_missing")
    }

    /// Remaining initial setup grace for a browser executable. `None` means
    /// no missing sample has started its grace; expired incidents return 0.
    pub fn missing_remaining_ms(&self, app_id: &str, now_ms: u64) -> Option<u64> {
        self.incidents
            .get(&normalized_browser_id(app_id))
            .map(|incident| GRACE_MS.saturating_sub(now_ms.saturating_sub(incident.started_at_ms)))
    }

    fn begin_incident(&mut self, browser_id: String, now_ms: u64) -> bool {
        // Keep the deadline until protection is removed, including long
        // commitments. Only the finite supported-browser names become keys.
        if self.incidents.len() >= MAX_BROWSER_INCIDENTS {
            return false;
        }
        self.incidents.insert(
            browser_id,
            Incident {
                started_at_ms: now_ms,
            },
        );
        true
    }

    fn note_recovery_page(&mut self, key: (String, u32)) {
        if self.recoveries.contains_key(&key) {
            if let Some(recovery) = self.recoveries.get_mut(&key) {
                recovery.opened_while_missing = true;
            }
            return;
        }
        if self.recoveries.len() >= MAX_RECOVERIES {
            return;
        }
        self.recoveries.insert(
            key,
            Recovery {
                opened_while_missing: true,
                healthy_until: None,
            },
        );
    }
}

fn browser_kind(app_id: &str) -> BrowserKind {
    match normalized_browser_id(app_id).as_str() {
        "chrome.exe" | "msedge.exe" | "brave.exe" | "vivaldi.exe" | "opera.exe"
        | "opera_gx.exe" | "arc.exe" => BrowserKind::Chromium,
        "firefox.exe" | "firefox" => BrowserKind::Unsupported,
        _ => BrowserKind::Other,
    }
}

fn normalized_browser_id(app_id: &str) -> String {
    app_id.trim().to_ascii_lowercase()
}

/// Recognize browser-owned extension management pages for recovery and
/// enforcement. Normal settings pages remain usable.
fn is_extension_page(raw: &str) -> bool {
    let Some((scheme, host, _path)) = parsed_internal_url(raw) else {
        return false;
    };
    matches!(
        (scheme.as_str(), host.as_str()),
        ("chrome", "extensions")
            | ("edge", "extensions")
            | ("brave", "extensions")
            | ("vivaldi", "extensions")
            | ("opera", "extensions")
            | ("arc", "extensions")
    )
}

/// Reset pages always block while protection is required, including when
/// extension health is missing; they can undo browser configuration.
fn is_reset_page(raw: &str) -> bool {
    let Some((scheme, host, path)) = parsed_internal_url(raw) else {
        return false;
    };
    let settings_host = matches!(
        (scheme.as_str(), host.as_str()),
        ("chrome", "settings")
            | ("edge", "settings")
            | ("brave", "settings")
            | ("vivaldi", "settings")
            | ("opera", "settings")
            | ("arc", "settings")
    );
    settings_host
        && (path == "/reset"
            || path.starts_with("/reset/")
            || path == "/resetprofilesettings"
            || path.starts_with("/resetprofilesettings/"))
}

fn parsed_internal_url(raw: &str) -> Option<(String, String, String)> {
    let Ok(url) = Url::parse(raw) else {
        return None;
    };
    if !url.username().is_empty() || url.password().is_some() {
        return None;
    }

    let scheme = url.scheme().to_ascii_lowercase();
    let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
    let path = url.path().trim_end_matches('/').to_ascii_lowercase();
    Some((scheme, host, path))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn eval(
        guard: &mut BrowserGuard,
        app: &str,
        pid: u32,
        healthy: bool,
        url: Option<&str>,
        now: u64,
    ) -> Option<&'static str> {
        guard.evaluate(app, pid, true, healthy, url, now)
    }

    #[test]
    fn one_grace_period_survives_process_and_window_switches() {
        let mut guard = BrowserGuard::default();
        assert_eq!(eval(&mut guard, "chrome.exe", 10, false, None, 100), None);
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 30_000),
            Some(30_100)
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 11, false, None, 45_000),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 12, false, None, 60_100),
            Some("extension_missing")
        );
    }

    #[test]
    fn healthy_samples_and_browser_restart_do_not_reset_initial_grace() {
        let mut guard = BrowserGuard::default();
        eval(&mut guard, "chrome.exe", 10, false, None, 0);
        assert_eq!(eval(&mut guard, "chrome.exe", 11, true, None, 20_000), None);
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 20_000),
            Some(40_000)
        );
        assert_eq!(
            eval(
                &mut guard,
                "chrome.exe",
                12,
                false,
                Some("chrome://extensions/?id=abc"),
                50_000
            ),
            None
        );
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 50_000),
            Some(10_000)
        );
        assert_eq!(eval(&mut guard, "chrome.exe", 12, true, None, 55_000), None);
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 55_000),
            Some(5_000)
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 99, false, None, 60_000),
            Some("extension_missing")
        );
        assert_eq!(guard.missing_remaining_ms("chrome.exe", 60_000), Some(0));
    }

    #[test]
    fn recovery_pages_and_settings_rules_are_narrow() {
        for url in [
            "chrome://extensions/",
            "edge://extensions/?id=abc",
            "brave://extensions/subpage",
            "vivaldi://settings/reset",
            "opera://settings/resetProfileSettings/confirm",
        ] {
            assert!(
                is_extension_page(url) || is_reset_page(url),
                "expected match: {url}"
            );
        }
        for url in [
            "chrome://settings/",
            "chrome://settings/privacy",
            "https://example.com/?next=chrome://extensions",
            "https://extensions/",
            "chrome://extensions.evil/",
            "chrome://user@extensions/",
            "chrome://settings/?search=reset",
            "chrome://settings/resetting",
            "not a url",
        ] {
            assert!(
                !is_extension_page(url) && !is_reset_page(url),
                "unexpected match: {url}"
            );
        }
    }

    #[test]
    fn extension_page_blocks_when_healthy_and_recovers_when_missing_but_reset_always_blocks() {
        let mut guard = BrowserGuard::default();
        let url = Some("chrome://extensions/");
        assert_eq!(
            eval(&mut guard, "chrome.exe", 1, true, url, 0),
            Some("extension_settings")
        );
        assert_eq!(eval(&mut guard, "chrome.exe", 2, false, url, 0), None);
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 1),
            Some(GRACE_MS - 1)
        );
        assert_eq!(
            eval(
                &mut guard,
                "chrome.exe",
                2,
                false,
                Some("chrome://settings/reset"),
                10
            ),
            Some("extension_settings")
        );
    }

    #[test]
    fn unsupported_firefox_is_immediate_and_chromium_health_isolated() {
        let mut guard = BrowserGuard::default();
        eval(&mut guard, "chrome.exe", 10, false, None, 0);
        assert_eq!(
            eval(&mut guard, "firefox.exe", 11, true, None, 1),
            Some("browser_unsupported")
        );
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 1),
            Some(GRACE_MS - 1)
        );
        assert_eq!(eval(&mut guard, "notepad.exe", 10, true, None, 2), None);
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 2),
            Some(GRACE_MS - 2)
        );
    }

    #[test]
    fn disabling_requirement_clears_incidents_but_non_browser_does_not() {
        let mut guard = BrowserGuard::default();
        eval(&mut guard, "chrome.exe", 10, false, None, 0);
        assert_eq!(guard.evaluate("notepad.exe", 0, true, true, None, 1), None);
        assert_eq!(
            guard.missing_remaining_ms("chrome.exe", 1),
            Some(GRACE_MS - 1)
        );
        assert_eq!(guard.evaluate("chrome.exe", 0, false, false, None, 2), None);
        assert_eq!(guard.missing_remaining_ms("chrome.exe", 2), None);
    }

    #[test]
    fn zero_pid_fails_closed_without_grace() {
        let mut guard = BrowserGuard::default();
        assert_eq!(
            eval(&mut guard, "chrome.exe", 0, false, None, 0),
            Some("extension_missing")
        );
        assert_eq!(guard.missing_remaining_ms("chrome.exe", 0), None);
    }

    #[test]
    fn missing_extension_page_gets_handoff_then_leaving_removes_it() {
        let mut guard = BrowserGuard::default();
        let extension_page = Some("chrome://extensions/");
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, false, extension_page, 0),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, true, extension_page, 1_000),
            None
        );
        assert_eq!(
            eval(
                &mut guard,
                "chrome.exe",
                41,
                true,
                Some("https://example.com/"),
                2_000
            ),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, true, extension_page, 3_000),
            Some("extension_settings")
        );
    }

    #[test]
    fn recovery_handoff_expires_and_health_loss_does_not_renew_it() {
        let mut guard = BrowserGuard::default();
        let extension_page = Some("chrome://extensions/");
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, false, extension_page, 0),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, true, extension_page, 1_000),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, false, extension_page, 10_000),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, true, extension_page, 15_999),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, true, extension_page, 16_000),
            Some("extension_settings")
        );
    }

    #[test]
    fn recovery_handoff_does_not_cross_processes() {
        let mut guard = BrowserGuard::default();
        let extension_page = Some("chrome://extensions/");
        assert_eq!(
            eval(&mut guard, "chrome.exe", 41, false, extension_page, 0),
            None
        );
        assert_eq!(
            eval(&mut guard, "chrome.exe", 42, true, extension_page, 1_000),
            Some("extension_settings")
        );
    }
}
