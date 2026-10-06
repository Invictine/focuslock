//! Companion monitor for browser extension health.
//!
//! Website policy remains in the extension. This module only starts a local
//! repair grace for browser windows that do not have a valid extension lease,
//! then asks Windows to close that exact browser window after the grace ends.

use serde::Serialize;
use std::collections::HashMap;

pub const GRACE_MS: u64 = 60_000;
const CLOSE_RETRY_MS: u64 = 2_000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BrowserWindowSample {
    pub window_handle: isize,
    pub process_id: u32,
    pub app_id: String,
    pub browser: String,
    pub healthy: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowserRepairState {
    pub browser: String,
    pub app_id: String,
    pub grace_remaining_seconds: u64,
    pub reason: &'static str,
}

#[derive(Clone, Debug)]
struct PendingWindow {
    process_id: u32,
    app_id: String,
    browser: String,
    started_at_ms: u64,
    reason: &'static str,
    last_close_attempt_ms: Option<u64>,
}

/// Keeps a separate, nonrenewable deadline for each observed browser HWND.
/// A healthy profile can cancel only its own window's pending deadline.
#[derive(Debug, Default)]
pub struct BrowserGuard {
    incidents: HashMap<String, u64>,
    pending: HashMap<isize, PendingWindow>,
}

impl BrowserGuard {
    /// Observe the current foreground browser plus already-pending windows and
    /// return windows whose close request should be retried. `known_windows` is the caller's
    /// current identity check for existing pending HWNDs; stale/reused handles
    /// are forgotten before they can receive a close message.
    pub fn update(
        &mut self,
        required: bool,
        windows: &[BrowserWindowSample],
        known_windows: &HashMap<isize, (u32, String)>,
        now_ms: u64,
    ) -> (Option<BrowserRepairState>, Vec<BrowserWindowSample>) {
        if !required {
            self.incidents.clear();
            self.pending.clear();
            return (None, Vec::new());
        }

        self.pending.retain(|hwnd, pending| {
            known_windows.get(hwnd).is_some_and(|(pid, app_id)| {
                *pid == pending.process_id && app_id.eq_ignore_ascii_case(&pending.app_id)
            })
        });

        let mut healthy_browsers = Vec::new();
        for window in windows {
            if window.window_handle == 0 || window.process_id == 0 {
                continue;
            }
            if window.healthy {
                // Recovery cancels this window's deadline only. Another
                // unhealthy profile in the same process keeps its own timer.
                if self
                    .pending
                    .get(&window.window_handle)
                    .is_some_and(|pending| {
                        pending.process_id == window.process_id
                            && pending.app_id.eq_ignore_ascii_case(&window.app_id)
                    })
                {
                    self.pending.remove(&window.window_handle);
                }
                healthy_browsers.push(base_app_id(&window.app_id));
                continue;
            }

            let reason = if is_unsupported_browser(&window.app_id) {
                "browser_unsupported"
            } else {
                "extension_missing"
            };
            let browser_id = base_app_id(&window.app_id);
            let started_at_ms = *self.incidents.entry(browser_id).or_insert(now_ms);
            let same_identity = self
                .pending
                .get(&window.window_handle)
                .is_some_and(|pending| {
                    pending.process_id == window.process_id
                        && pending.app_id.eq_ignore_ascii_case(&window.app_id)
                });
            if !same_identity {
                self.pending.insert(
                    window.window_handle,
                    PendingWindow {
                        process_id: window.process_id,
                        app_id: window.app_id.clone(),
                        browser: window.browser.clone(),
                        started_at_ms,
                        reason,
                        last_close_attempt_ms: None,
                    },
                );
            }
        }

        // Only an actual healthy lease can resolve an executable incident.
        // Empty enumeration, closed windows, or a newly opened HWND cannot
        // silently buy another grace period.
        for browser_id in healthy_browsers {
            if !self
                .pending
                .values()
                .any(|pending| base_app_id(&pending.app_id) == browser_id)
            {
                self.incidents.remove(&browser_id);
            }
        }

        let mut due: Vec<_> = self
            .pending
            .iter_mut()
            .filter_map(|(hwnd, pending)| {
                if now_ms.saturating_sub(pending.started_at_ms) < GRACE_MS
                    || pending
                        .last_close_attempt_ms
                        .is_some_and(|last| now_ms.saturating_sub(last) < CLOSE_RETRY_MS)
                {
                    return None;
                }
                pending.last_close_attempt_ms = Some(now_ms);
                Some(BrowserWindowSample {
                    window_handle: *hwnd,
                    process_id: pending.process_id,
                    app_id: pending.app_id.clone(),
                    browser: pending.browser.clone(),
                    healthy: false,
                })
            })
            .collect();
        due.sort_by_key(|window| window.window_handle);

        let state = self
            .pending
            .values()
            .min_by_key(|pending| pending.started_at_ms)
            .map(|pending| BrowserRepairState {
                browser: pending.browser.clone(),
                app_id: pending.app_id.clone(),
                grace_remaining_seconds: GRACE_MS
                    .saturating_sub(now_ms.saturating_sub(pending.started_at_ms))
                    .div_ceil(1_000),
                reason: pending.reason,
            });
        (state, due)
    }

    #[cfg(test)]
    pub fn pending_count(&self) -> usize {
        self.pending.len()
    }

    pub fn pending_identities(&self) -> Vec<(isize, u32, String)> {
        self.pending
            .iter()
            .map(|(hwnd, pending)| (*hwnd, pending.process_id, pending.app_id.clone()))
            .collect()
    }

    pub fn retry_close(&mut self, hwnd: isize) {
        if let Some(pending) = self.pending.get_mut(&hwnd) {
            pending.last_close_attempt_ms = None;
        }
    }
}

pub fn is_browser(app_id: &str) -> bool {
    matches!(
        base_app_id(app_id).as_str(),
        "chrome.exe"
            | "msedge.exe"
            | "brave.exe"
            | "firefox.exe"
            | "vivaldi.exe"
            | "opera.exe"
            | "opera_gx.exe"
            | "arc.exe"
    )
}

pub fn is_unsupported_browser(app_id: &str) -> bool {
    base_app_id(app_id) == "firefox.exe"
}

fn base_app_id(app_id: &str) -> String {
    app_id
        .rsplit(['\\', '/'])
        .next()
        .unwrap_or(app_id)
        .to_ascii_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(hwnd: isize, pid: u32, app_id: &str, healthy: bool) -> BrowserWindowSample {
        BrowserWindowSample {
            window_handle: hwnd,
            process_id: pid,
            app_id: app_id.into(),
            browser: app_id.into(),
            healthy,
        }
    }

    fn known(samples: &[BrowserWindowSample]) -> HashMap<isize, (u32, String)> {
        samples
            .iter()
            .map(|window| {
                (
                    window.window_handle,
                    (window.process_id, window.app_id.clone()),
                )
            })
            .collect()
    }

    #[test]
    fn deadline_is_per_window_and_independent_of_foreground_switches() {
        let mut guard = BrowserGuard::default();
        let first = sample(10, 100, "chrome.exe", false);
        let (state, due) = guard.update(true, &[first.clone()], &known(&[first.clone()]), 0);
        assert_eq!(state.unwrap().grace_remaining_seconds, 60);
        assert!(due.is_empty());

        let second = sample(11, 101, "chrome.exe", false);
        let all = [first.clone(), second.clone()];
        let (state, due) = guard.update(true, &[second], &known(&all), 20_000);
        assert_eq!(state.unwrap().grace_remaining_seconds, 40);
        assert!(due.is_empty());

        let (_, due) = guard.update(true, &[], &known(&all), 60_000);
        assert_eq!(due.len(), 2);
        assert_eq!(
            due.iter()
                .map(|window| window.window_handle)
                .collect::<Vec<_>>(),
            [10, 11]
        );
        let (_, due) = guard.update(true, &[], &known(&all), 61_999);
        assert!(due.is_empty());
        let (_, due) = guard.update(true, &[], &known(&all), 62_000);
        assert_eq!(
            due.len(),
            2,
            "queued close requests are retried while unhealthy"
        );
    }

    #[test]
    fn healthy_profile_cancels_only_its_own_window() {
        let mut guard = BrowserGuard::default();
        let one = sample(10, 100, "chrome.exe", false);
        let two = sample(11, 100, "chrome.exe", false);
        let both = [one.clone(), two.clone()];
        guard.update(true, &both, &known(&both), 0);
        let healthy_one = sample(10, 100, "chrome.exe", true);
        let (_, due) = guard.update(true, &[healthy_one, two.clone()], &known(&both), 59_000);
        assert!(due.is_empty());
        assert_eq!(guard.pending_count(), 1);
        let (_, due) = guard.update(true, &[], &known(&[two]), 60_000);
        assert_eq!(
            due.iter()
                .map(|window| window.window_handle)
                .collect::<Vec<_>>(),
            [11]
        );
    }

    #[test]
    fn firefox_gets_same_grace_with_unsupported_reason() {
        assert!(is_browser("C:\\Apps\\Firefox.exe"));
        assert!(is_unsupported_browser("firefox.exe"));
        let mut guard = BrowserGuard::default();
        let firefox = sample(20, 200, "firefox.exe", false);
        let (state, due) =
            guard.update(true, &[firefox.clone()], &known(&[firefox.clone()]), 1_000);
        let state = state.unwrap();
        assert_eq!(state.reason, "browser_unsupported");
        assert_eq!(state.grace_remaining_seconds, 60);
        assert!(due.is_empty());
        let (_, due) = guard.update(true, &[], &known(&[firefox]), 61_000);
        assert_eq!(due.len(), 1);
    }

    #[test]
    fn healthy_restoration_cancels_and_later_outage_gets_new_grace() {
        let mut guard = BrowserGuard::default();
        let unhealthy = sample(10, 100, "chrome.exe", false);
        let known = known(&[unhealthy.clone()]);
        guard.update(true, &[unhealthy.clone()], &known, 0);
        let healthy = sample(10, 100, "chrome.exe", true);
        assert!(guard.update(true, &[healthy], &known, 30_000).0.is_none());
        let (state, _) = guard.update(true, &[unhealthy.clone()], &known, 40_000);
        assert_eq!(state.unwrap().grace_remaining_seconds, 60);
    }

    #[test]
    fn destroyed_or_reused_hwnds_are_forgotten_without_closing_new_identity() {
        let mut guard = BrowserGuard::default();
        let old = sample(10, 100, "chrome.exe", false);
        guard.update(
            true,
            &[old],
            &known(&[sample(10, 100, "chrome.exe", false)]),
            0,
        );
        let reused = sample(10, 200, "msedge.exe", false);
        let (state, due) = guard.update(true, &[reused.clone()], &known(&[reused]), 60_000);
        assert_eq!(state.unwrap().grace_remaining_seconds, 60);
        assert!(due.is_empty());
    }

    #[test]
    fn closing_and_reopening_browser_does_not_renew_unresolved_executable_deadline() {
        let mut guard = BrowserGuard::default();
        let old = sample(10, 100, "chrome.exe", false);
        guard.update(true, &[old.clone()], &known(&[old]), 0);
        // Browser closes; the HWND can be forgotten, but no healthy lease was
        // observed, so the Chrome incident remains unresolved.
        assert!(guard.update(true, &[], &HashMap::new(), 20_000).0.is_none());
        let reopened = sample(11, 200, "chrome.exe", false);
        let (state, due) = guard.update(
            true,
            &[reopened.clone()],
            &known(&[reopened.clone()]),
            40_000,
        );
        assert_eq!(state.unwrap().grace_remaining_seconds, 20);
        assert!(due.is_empty());
        let (_, due) = guard.update(true, &[], &known(&[reopened]), 60_000);
        assert_eq!(due.len(), 1);
    }

    #[test]
    fn disabling_requirement_clears_all_pending_windows() {
        let mut guard = BrowserGuard::default();
        let chrome = sample(10, 100, "chrome.exe", false);
        guard.update(true, &[chrome.clone()], &known(&[chrome]), 0);
        assert!(guard.update(false, &[], &HashMap::new(), 1).0.is_none());
        assert_eq!(guard.pending_count(), 0);
    }
}
