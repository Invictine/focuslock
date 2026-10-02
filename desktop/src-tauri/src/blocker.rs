// Full-screen enforcement window. Replaces the old "minimize the foreground
// window" behavior with an Android-style blocker that covers the monitor of
// the blocked window and offers `dismiss` / `eat_frog` actions.
use crate::{
    tracking::BlockedMatch,
    windows_capture::{
        focus_window, foreground_window, minimize_foreground, minimize_window, window_rect,
    },
};
use serde::Serialize;
use std::{
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};
use tauri::{
    AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, State, WebviewUrl, WebviewWindow,
    WebviewWindowBuilder,
};

pub const BLOCKER_LABEL: &str = "blocker";
pub const EVENT_BLOCKER: &str = "focuslock://blocker";
pub const EVENT_NAVIGATE: &str = "focuslock://navigate";
const MAIN_WINDOW_LABEL: &str = "main";
const BLOCKER_URL: &str = "index.html#/blocked";
/// While the blocker is already on screen, a blocked app that manages to grab
/// the foreground again is pulled back at most this often. The ALT-tap focus
/// steal is intrusive, so it is rate limited while `set_focus` is tried on
/// every tracker sample.
const FOCUS_RETRY_INTERVAL_MS: u64 = 2_000;

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct BlockerState {
    pub visible: bool,
    pub target: Option<String>,
    pub kind: Option<String>,
    pub reason: Option<String>,
}

#[derive(Clone, Serialize)]
struct BlockerPayload<'a> {
    target: &'a str,
    kind: &'a str,
    reason: &'a str,
}

#[derive(Clone, Debug, Default)]
struct BlockerInternal {
    visible: bool,
    active: Option<BlockedMatch>,
    /// Handle of the window that was foreground when the blocker appeared, so
    /// `dismiss`/`eat_frog` minimize the blocked app instead of the blocker.
    blocked_hwnd: Option<isize>,
    last_focus_attempt_ms: u64,
}

pub struct BlockerRuntime {
    inner: Mutex<BlockerInternal>,
}

impl BlockerRuntime {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(BlockerInternal::default()),
        }
    }

    pub fn state(&self) -> BlockerState {
        let Ok(inner) = self.inner.lock() else {
            return BlockerState {
                visible: false,
                target: None,
                kind: None,
                reason: None,
            };
        };
        BlockerState {
            visible: inner.visible,
            target: inner.active.as_ref().map(|active| active.target.clone()),
            kind: inner.active.as_ref().map(|active| active.kind.to_string()),
            reason: inner.active.as_ref().map(|active| active.reason.clone()),
        }
    }

    /// True while the tracked block is a device-local permanent one. The window
    /// close handler uses this so Alt+F4 / a programmatic close can never take
    /// a permanent overlay down.
    pub fn is_permanent_active(&self) -> bool {
        self.state().reason.as_deref() == Some(crate::tracking::PERMANENT_REASON)
    }

    /// Show (or re-target) the blocker for `matched`. Returns `false` when the
    /// blocker window cannot be shown, in which case the caller must fall back
    /// to minimizing the foreground window.
    pub fn show_for(&self, app: &AppHandle, matched: &BlockedMatch) -> bool {
        let now = now_ms();
        let (changed, was_visible, blocked_hwnd) = {
            let Ok(mut inner) = self.inner.lock() else {
                return false;
            };
            let changed = inner.active.as_ref() != Some(matched);
            let was_visible = inner.visible;
            inner.visible = true;
            inner.active = Some(matched.clone());
            inner.blocked_hwnd = foreground_window();
            if changed || !was_visible {
                inner.last_focus_attempt_ms = now;
            }
            (changed, was_visible, inner.blocked_hwnd)
        };
        let Some(window) = ensure_window(app) else {
            self.reset();
            return false;
        };
        let on_screen = window.is_visible().unwrap_or(false);
        if changed || !was_visible || !on_screen {
            position_over_monitor(app, &window, blocked_hwnd);
            if window.show().is_err() {
                let _ = window.hide();
                self.reset();
                return false;
            }
            force_focus(&window);
            self.emit(app, matched);
        } else if !window.is_focused().unwrap_or(false) {
            self.refocus(&window);
        }
        true
    }

    /// Hide the blocker without touching the blocked window (the foreground
    /// moved to something that is not blocked). Cheap no-op when already
    /// hidden, because the tracker calls this on every unblocked sample.
    pub fn hide(&self, app: &AppHandle) {
        let (was_active, _) = self.clear_active();
        if !was_active {
            return;
        }
        if let Some(window) = app.get_webview_window(BLOCKER_LABEL) {
            let _ = window.hide();
        }
    }

    /// Hide the blocker and minimize the window it was covering.
    pub fn dismiss(&self, app: &AppHandle) {
        let (was_active, target_hwnd) = self.clear_active();
        if let Some(window) = app.get_webview_window(BLOCKER_LABEL) {
            let _ = window.hide();
        }
        if !was_active {
            return;
        }
        // The stored handle is the blocked app itself; the foreground window
        // fallback only kicks in when that window no longer exists.
        if !target_hwnd
            .map(|hwnd| minimize_window(hwnd).is_ok())
            .unwrap_or(false)
        {
            let _ = minimize_foreground();
        }
    }

    /// The window is gone (destroyed outside our control); forget it so the
    /// next block recreates it.
    pub fn note_window_destroyed(&self) {
        self.reset();
    }

    fn emit(&self, app: &AppHandle, matched: &BlockedMatch) {
        let _ = app.emit_to(
            BLOCKER_LABEL,
            EVENT_BLOCKER,
            BlockerPayload {
                target: &matched.target,
                kind: matched.kind,
                reason: &matched.reason,
            },
        );
    }

    fn refocus(&self, window: &WebviewWindow) {
        let now = now_ms();
        let due = {
            let Ok(mut inner) = self.inner.lock() else {
                return;
            };
            if now.saturating_sub(inner.last_focus_attempt_ms) >= FOCUS_RETRY_INTERVAL_MS {
                inner.last_focus_attempt_ms = now;
                true
            } else {
                false
            }
        };
        if due {
            force_focus(window);
        } else {
            let _ = window.set_focus();
        }
    }

    /// Clear the tracked block; returns whether one was tracked and the handle
    /// of the window it covered.
    fn clear_active(&self) -> (bool, Option<isize>) {
        let Ok(mut inner) = self.inner.lock() else {
            return (false, None);
        };
        let was_active = inner.visible || inner.active.is_some();
        let hwnd = inner.blocked_hwnd;
        inner.visible = false;
        inner.active = None;
        inner.blocked_hwnd = None;
        (was_active, hwnd)
    }

    fn reset(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.visible = false;
            inner.active = None;
            inner.blocked_hwnd = None;
        }
    }
}

#[tauri::command]
pub fn get_blocker_state(blocker: State<'_, BlockerRuntime>) -> BlockerState {
    blocker.state()
}

#[tauri::command]
pub fn blocker_action(
    app: AppHandle,
    action: String,
    blocker: State<'_, BlockerRuntime>,
) -> Result<(), String> {
    // A permanent block has no in-app escape hatch: the overlay renders no
    // buttons, and any stray/queued `blocker_action` is refused here too and
    // leaves the window visible. Only the tracker's normal hide-on-foreground
    // change (switching to an unblocked app) takes the overlay down.
    if blocker.is_permanent_active() {
        return Err("Permanent blocks cannot be dismissed in FocusLock.".into());
    }
    match action.as_str() {
        "dismiss" => {
            blocker.dismiss(&app);
            Ok(())
        }
        "eat_frog" => {
            blocker.dismiss(&app);
            if let Some(main) = app.get_webview_window(MAIN_WINDOW_LABEL) {
                let _ = main.unminimize();
                let _ = main.show();
                let _ = main.set_focus();
            }
            let _ = app.emit_to(
                MAIN_WINDOW_LABEL,
                EVENT_NAVIGATE,
                serde_json::json!({ "view": "home" }),
            );
            Ok(())
        }
        other => Err(format!("Unknown blocker action: {other}")),
    }
}

/// Create the hidden blocker webview. Called once at setup and again if the
/// window was destroyed while the app was running.
///
/// `closable(false)` (on top of the `CloseRequested` guard in `main.rs`) means
/// the OS cannot close the overlay for ANY reason — required so a permanent
/// block has no Alt+F4 escape. Frog/limit/boundary overlays were already
/// unclosable through the event handler; this makes it absolute instead of
/// letting a close briefly hide the window until the next tracker sample.
pub fn build_blocker_window(app: &AppHandle) -> tauri::Result<WebviewWindow> {
    WebviewWindowBuilder::new(app, BLOCKER_LABEL, WebviewUrl::App(BLOCKER_URL.into()))
        .title("FocusLock")
        .decorations(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(false)
        .closable(false)
        .visible(false)
        .focused(false)
        .build()
}

fn ensure_window(app: &AppHandle) -> Option<WebviewWindow> {
    if let Some(window) = app.get_webview_window(BLOCKER_LABEL) {
        return Some(window);
    }
    build_blocker_window(app).ok()
}

/// Cover the monitor that contains the center of the blocked window, falling
/// back to the primary monitor and finally to maximizing.
fn position_over_monitor(app: &AppHandle, window: &WebviewWindow, blocked_hwnd: Option<isize>) {
    let monitor = blocked_hwnd
        .and_then(window_rect)
        .and_then(|(left, top, right, bottom)| {
            let center_x = left + (right - left) / 2;
            let center_y = top + (bottom - top) / 2;
            app.monitor_from_point(center_x as f64, center_y as f64)
                .ok()
                .flatten()
        })
        .or_else(|| app.primary_monitor().ok().flatten());
    if let Some(monitor) = monitor {
        let position = monitor.position();
        let size = monitor.size();
        let _ = window.set_position(PhysicalPosition::new(position.x, position.y));
        let _ = window.set_size(PhysicalSize::new(size.width, size.height));
    } else {
        let _ = window.maximize();
    }
}

fn force_focus(window: &WebviewWindow) {
    let _ = window.set_focus();
    if let Some(hwnd) = window_handle(window) {
        focus_window(hwnd);
    }
}

#[cfg(windows)]
fn window_handle(window: &WebviewWindow) -> Option<isize> {
    window.hwnd().ok().map(|hwnd| hwnd.0 as isize)
}

#[cfg(not(windows))]
fn window_handle(_: &WebviewWindow) -> Option<isize> {
    None
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}
