//! Native, nonblocking browser-extension repair notice.

use crate::browser_guard::BrowserRepairState;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

pub const WINDOW_LABEL: &str = "browser-repair";
const WINDOW_URL: &str = "index.html#/browser-repair";

#[derive(Clone, Default)]
pub struct BrowserRepairRuntime {
    state: Arc<Mutex<Option<BrowserRepairState>>>,
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
    if state.is_some() {
        #[cfg(windows)]
        if let Ok(hwnd) = window.hwnd() {
            let _ = crate::windows_capture::show_nonactivating_topmost(hwnd.0 as isize);
        }
        #[cfg(not(windows))]
        let _ = window.show();
    } else {
        let _ = window.hide();
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
