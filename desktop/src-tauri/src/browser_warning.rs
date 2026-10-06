//! Native, nonblocking browser-extension repair notice.

use crate::browser_guard::{BrowserRepairState, GRACE_MS};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

pub const WINDOW_LABEL: &str = "browser-repair";
const WINDOW_URL: &str = "index.html#/browser-repair";
// Allow two heartbeat intervals for startup and tab/window transitions.
const NOTICE_GRACE_THRESHOLD_SECONDS: u64 = GRACE_MS / 1_000 - 4;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct NoticeVisibility {
    visible: bool,
}

impl NoticeVisibility {
    fn update(&mut self, state: Option<&BrowserRepairState>) -> bool {
        match state {
            None => self.visible = false,
            Some(state) if state.reason == "browser_unsupported" => self.visible = true,
            Some(state) if state.grace_remaining_seconds <= NOTICE_GRACE_THRESHOLD_SECONDS => {
                self.visible = true;
            }
            Some(_) => {}
        }
        self.visible
    }
}

#[derive(Clone, Default)]
pub struct BrowserRepairRuntime {
    state: Arc<Mutex<Option<BrowserRepairState>>>,
    notice_visibility: Arc<Mutex<NoticeVisibility>>,
}

impl BrowserRepairRuntime {
    pub fn shared_state(&self) -> Arc<Mutex<Option<BrowserRepairState>>> {
        self.state.clone()
    }

    pub fn set(&self, state: Option<BrowserRepairState>) {
        if let Ok(mut current) = self.state.lock() {
            *current = state.clone();
        }
    }
}

pub fn build_browser_warning_window(app: &AppHandle) -> tauri::Result<WebviewWindow> {
    WebviewWindowBuilder::new(app, WINDOW_LABEL, WebviewUrl::App(WINDOW_URL.into()))
        .title("FocusLock browser protection")
        .inner_size(440.0, 330.0)
        .decorations(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(false)
        .focused(false)
        .visible(false)
        .build()
}

/// Reflect the latest repair state in the warning window. Failure to show or
/// hide the notice never pauses the native timer or affects the close request.
pub fn sync_window(app: &AppHandle, state: Option<&BrowserRepairState>) {
    let Some(window) = app.get_webview_window(WINDOW_LABEL) else {
        return;
    };
    let visible = app
        .try_state::<BrowserRepairRuntime>()
        .and_then(|runtime| runtime.notice_visibility.lock().ok().map(|mut latch| latch.update(state)))
        .unwrap_or_else(|| NoticeVisibility::default().update(state));
    if visible {
        #[cfg(windows)]
        if let Ok(hwnd) = window.hwnd() {
            let _ = crate::windows_capture::show_nonactivating_topmost(hwnd.0 as isize);
        }
        #[cfg(not(windows))]
        let _ = window.show();
    } else {
        // ShowWindow bypasses Tauri's cached visibility. Hide through the same
        // native API so a recovered notice cannot remain on screen.
        #[cfg(windows)]
        if let Ok(hwnd) = window.hwnd() {
            unsafe {
                let _ = windows::Win32::UI::WindowsAndMessaging::ShowWindow(
                    windows::Win32::Foundation::HWND(hwnd.0),
                    windows::Win32::UI::WindowsAndMessaging::SW_HIDE,
                );
            }
        }
        #[cfg(not(windows))]
        let _ = window.hide();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state(seconds: u64, reason: &'static str) -> BrowserRepairState {
        BrowserRepairState {
            browser: "Chrome".into(),
            app_id: "chrome.exe".into(),
            grace_remaining_seconds: seconds,
            reason,
        }
    }

    #[test]
    fn supported_notice_debounces_and_recovers_without_a_flash() {
        let mut notice = NoticeVisibility::default();
        assert!(!notice.update(Some(&state(60, "extension_missing"))));
        assert!(!notice.update(Some(&state(59, "extension_missing"))));
        assert!(!notice.update(Some(&state(58, "extension_missing"))));
        assert!(!notice.update(Some(&state(57, "extension_missing"))));
        assert!(!notice.update(None));
    }

    #[test]
    fn sustained_loss_shows_at_threshold_and_latches_across_countdown_reset() {
        let mut notice = NoticeVisibility::default();
        assert!(!notice.update(Some(&state(57, "extension_missing"))));
        assert!(notice.update(Some(&state(56, "extension_missing"))));
        assert!(notice.update(Some(&state(60, "extension_missing"))));
    }

    #[test]
    fn recovery_resets_latch_and_new_outage_debounces() {
        let mut notice = NoticeVisibility::default();
        assert!(notice.update(Some(&state(56, "extension_missing"))));
        assert!(!notice.update(None));
        assert!(!notice.update(Some(&state(60, "extension_missing"))));
    }

    #[test]
    fn unsupported_browser_shows_immediately_and_none_hides_immediately() {
        let mut notice = NoticeVisibility::default();
        assert!(notice.update(Some(&state(60, "browser_unsupported"))));
        assert!(!notice.update(None));
    }

    #[test]
    fn debouncing_notice_preserves_native_window_close_deadline() {
        use crate::browser_guard::{BrowserGuard, BrowserWindowSample};
        use std::collections::HashMap;

        let window = BrowserWindowSample {
            window_handle: 10,
            process_id: 100,
            app_id: "chrome.exe".into(),
            browser: "Chrome".into(),
            healthy: false,
        };
        let known = HashMap::from([(10, (100, "chrome.exe".into()))]);
        let mut guard = BrowserGuard::default();
        let mut notice = NoticeVisibility::default();
        let (repair, due) = guard.update(true, &[window.clone()], &known, 0);
        assert!(!notice.update(repair.as_ref()));
        assert!(due.is_empty());
        let (repair, due) = guard.update(true, &[window.clone()], &known, 4_000);
        assert!(notice.update(repair.as_ref()));
        assert_eq!(repair.unwrap().grace_remaining_seconds, 56);
        assert!(due.is_empty());
        let (repair, due) = guard.update(true, &[window], &known, GRACE_MS);
        assert!(notice.update(repair.as_ref()));
        assert_eq!(due.len(), 1);
        assert_eq!(due[0].window_handle, 10);
    }
}

#[tauri::command]
pub fn get_browser_repair_state(
    state: State<'_, BrowserRepairRuntime>,
) -> Result<Option<BrowserRepairState>, String> {
    state
        .state
        .lock()
        .map(|current| current.clone())
        .map_err(|_| "Browser repair state is unavailable".into())
}
