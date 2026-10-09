//! Owns the bundled Void focus window and its small newline-delimited JSON pipe.
//! The only process this module may create is FocusLock.Void.exe shipped with
//! FocusLock. The webview can send session snapshots, never executable commands.

use crate::windows_capture::get_running_windows;
use serde::{Deserialize, Serialize};
use std::{
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{mpsc, Arc, Mutex},
    thread,
    time::{Duration, Instant},
};
use tauri::{AppHandle, Emitter, Manager, State};

const HELPER_NAME: &str = "FocusLock.Void.exe";
const MAX_FRAME_BYTES: usize = 64 * 1024;
const MAX_TOOLS: usize = 64;
const MAX_DOMAINS: usize = 512;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VoidTool {
    pub id: String,
    pub label: String,
    #[serde(default)]
    pub executable_path: Option<String>,
    #[serde(default)]
    pub app_user_model_id: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VoidSnapshot {
    pub protocol_version: u8,
    pub title: String,
    #[serde(default)]
    pub project_name: Option<String>,
    pub phase: String,
    #[serde(default)]
    pub grace_remaining_seconds: u64,
    pub cycle_date: String,
    pub tracked_seconds: u64,
    pub required_seconds: u64,
    pub ticked_off: bool,
    pub running: bool,
    pub remaining_seconds: u64,
    pub block_minutes: u32,
    pub tools: Vec<VoidTool>,
    pub domains: Vec<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VoidLauncherStatus {
    pub running: bool,
    pub launch_error: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
enum VoidAction {
    Ready,
    ToggleTimer,
    TickOff,
    OpenFocuslock,
    Closed,
    SetDuration { minutes: u32 },
    LaunchError { message: String },
    SelectFrog { title: String, #[serde(rename = "appIds")] app_ids: Vec<String>, domains: Vec<String> },
}

struct ManagedChild {
    child: Child,
    stdin: ChildStdin,
}

#[derive(Clone, Default)]
pub struct VoidSessionState {
    child: Arc<Mutex<Option<ManagedChild>>>,
    launch_lock: Arc<Mutex<()>>,
    last_error: Arc<Mutex<Option<String>>>,
}

impl VoidSnapshot {
    fn validate_and_resolve(mut self) -> Result<Self, String> {
        if self.protocol_version != 1 {
            return Err("Unsupported Void launcher protocol version".into());
        }
        bounded_text(&self.title, 1, 200, "title")?;
        if self
            .project_name
            .as_deref()
            .is_some_and(|name| name.trim().is_empty())
        {
            self.project_name = None;
        }
        if let Some(name) = &self.project_name {
            bounded_text(name, 1, 160, "projectName")?;
        }
        bounded_text(&self.phase, 1, 32, "phase")?;
        if !matches!(
            self.phase.as_str(),
            "not_armed" | "grace" | "pick_frog" | "working" | "complete"
        ) {
            return Err("Invalid Void phase".into());
        }
        bounded_text(&self.cycle_date, 1, 32, "cycleDate")?;
        if self.tracked_seconds > 7 * 24 * 60 * 60
            || self.required_seconds == 0
            || self.required_seconds > 24 * 60 * 60
            || self.remaining_seconds > 24 * 60 * 60
            || self.grace_remaining_seconds > 300
            || !(1..=480).contains(&self.block_minutes)
        {
            return Err("Void session duration is out of range".into());
        }
        if self.tools.len() > MAX_TOOLS || self.domains.len() > MAX_DOMAINS {
            return Err("Void session contains too many tools or domains".into());
        }
        let running = get_running_windows()?;
        let mut seen_tools = std::collections::HashSet::new();
        for tool in &mut self.tools {
            bounded_text(&tool.id, 1, 260, "tool.id")?;
            bounded_text(&tool.label, 1, 120, "tool.label")?;
            if !seen_tools.insert(normalize_id(&tool.id)) {
                return Err("Void session contains duplicate tool identities".into());
            }
            if let Some(id) = &tool.app_user_model_id {
                bounded_text(id, 1, 256, "tool.appUserModelId")?;
            }
            let key = normalize_id(&tool.id);
            let match_app = resolve_running_tool(&tool.id, &running);
            if let Some(path) = tool.executable_path.as_deref() {
                bounded_text(path, 1, 2048, "tool.executablePath")?;
                if !Path::new(path).is_absolute() {
                    return Err(format!(
                        "Tool '{}' must have an absolute executable path",
                        tool.id
                    ));
                }
                let path_id = normalize_id(path);
                if path_id != key
                    && !match_app
                        .and_then(|app| app.executable_path.as_deref())
                        .is_some_and(|current| same_path(current, path))
                {
                    return Err(format!(
                        "Tool '{}' does not match its executable path",
                        tool.id
                    ));
                }
            } else if let Some(path) = match_app.and_then(|app| app.executable_path.as_ref()) {
                tool.executable_path = Some(path.clone());
            }
        }
        for domain in &self.domains {
            bounded_text(domain, 1, 253, "domain")?;
            if !valid_domain(domain) {
                return Err("Invalid Void domain".into());
            }
        }
        let encoded = serde_json::to_vec(&self).map_err(|e| e.to_string())?;
        if encoded.len() + 1 > MAX_FRAME_BYTES {
            return Err("Void session snapshot exceeds the 64 KB frame limit".into());
        }
        Ok(self)
    }
}

fn resolve_running_tool<'a>(
    id: &str,
    running: &'a [crate::windows_capture::RunningApp],
) -> Option<&'a crate::windows_capture::RunningApp> {
    let key = normalize_id(id);
    running.iter().find(|app| {
        normalize_id(&app.app_id) == key
            || app
                .executable_path
                .as_deref()
                .and_then(|path| Path::new(path).file_name())
                .map(|name| normalize_id(&name.to_string_lossy()) == key)
                .unwrap_or(false)
    })
}

fn valid_domain(domain: &str) -> bool {
    let host = domain.trim().trim_end_matches('.');
    !host.is_empty()
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        })
}

fn bounded_text(value: &str, min: usize, max: usize, field: &str) -> Result<(), String> {
    let length = value.trim().len();
    if length < min || length > max || value.contains('\0') {
        return Err(format!("Invalid Void {field}"));
    }
    Ok(())
}

fn normalize_id(value: &str) -> String {
    Path::new(value.trim())
        .file_name()
        .map(|name| name.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_else(|| value.trim().to_ascii_lowercase())
}

fn same_path(left: &str, right: &str) -> bool {
    PathBuf::from(left).canonicalize().ok() == PathBuf::from(right).canonicalize().ok()
        && PathBuf::from(left).canonicalize().is_ok()
}

fn helper_path(app: &AppHandle) -> Result<PathBuf, String> {
    let bundled = app
        .path()
        .resource_dir()
        .map_err(|e| format!("Could not locate FocusLock resources: {e}"))?
        .join("void")
        .join(HELPER_NAME);
    if bundled.is_file() {
        return Ok(bundled);
    }
    let development = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("resources")
        .join("void")
        .join(HELPER_NAME);
    if development.is_file() {
        return Ok(development);
    }
    Err(format!(
        "Bundled Void launcher was not found at {}",
        bundled.display()
    ))
}

fn write_snapshot(stdin: &mut ChildStdin, snapshot: &VoidSnapshot) -> Result<(), String> {
    let frame = serde_json::to_vec(snapshot).map_err(|e| e.to_string())?;
    if frame.len() + 1 > MAX_FRAME_BYTES {
        return Err("Void session snapshot exceeds the 64 KB frame limit".into());
    }
    stdin
        .write_all(&frame)
        .and_then(|_| stdin.write_all(b"\n"))
        .and_then(|_| stdin.flush())
        .map_err(|e| format!("Could not update Void session: {e}"))
}

#[tauri::command]
pub async fn start_void_launcher(
    app: AppHandle,
    session: State<'_, VoidSessionState>,
    state: VoidSnapshot,
) -> Result<VoidLauncherStatus, String> {
    // Use an explicit argument key at the Tauri boundary so callers send
    // `{ state: snapshot }`, matching the frontend API contract.
    let app = app.clone();
    let session = session.inner().clone();
    tauri::async_runtime::spawn_blocking(move || start_or_update(&app, &session, state))
        .await
        .map_err(|error| format!("Void launcher startup worker failed: {error}"))?
}

#[tauri::command]
pub fn update_void_launcher(
    session: State<'_, VoidSessionState>,
    state: VoidSnapshot,
) -> Result<(), String> {
    if session
        .child
        .lock()
        .map_err(|_| "Void launcher lock is unavailable")?
        .is_none()
    {
        return Ok(());
    }
    let snapshot = state.validate_and_resolve()?;
    let mut slot = session
        .child
        .lock()
        .map_err(|_| "Void launcher lock is unavailable")?;
    if let Some(managed) = slot.as_mut() {
        write_snapshot(&mut managed.stdin, &snapshot)?;
    }
    Ok(())
}

#[tauri::command]
pub fn stop_void_launcher(state: State<'_, VoidSessionState>) -> Result<(), String> {
    stop(state.inner())
}

#[tauri::command]
pub fn get_void_launcher_status(state: State<'_, VoidSessionState>) -> VoidLauncherStatus {
    let running = state
        .child
        .lock()
        .ok()
        .and_then(|slot| slot.as_ref().map(|managed| managed.child.id()))
        .is_some();
    VoidLauncherStatus {
        running,
        launch_error: state.last_error.lock().ok().and_then(|value| value.clone()),
    }
}

#[tauri::command]
pub fn show_void_launcher(state: State<'_, VoidSessionState>) -> Result<(), String> {
    let pid = {
        let mut slot = state
            .child
            .lock()
            .map_err(|_| "Void launcher lock is unavailable")?;
        let Some(managed) = slot.as_mut() else {
            return Err("Void launcher is not running".into());
        };
        if managed
            .child
            .try_wait()
            .map_err(|error| format!("Could not inspect Void launcher: {error}"))?
            .is_some()
        {
            *slot = None;
            return Err("Void launcher has already exited".into());
        }
        managed.child.id()
    };
    post_return_to_launcher(pid)
}

#[cfg(windows)]
fn post_return_to_launcher(pid: u32) -> Result<(), String> {
    use windows::{
        core::BOOL,
        Win32::{
            Foundation::{HWND, LPARAM, WPARAM},
            UI::WindowsAndMessaging::{
                EnumWindows, GetWindowTextLengthW, GetWindowTextW, GetWindowThreadProcessId,
                PostMessageW,
            },
        },
    };

    struct Search {
        pid: u32,
        found: bool,
    }
    unsafe extern "system" fn callback(hwnd: HWND, data: LPARAM) -> BOOL {
        let search = unsafe { &mut *(data.0 as *mut Search) };
        let mut window_pid = 0u32;
        unsafe { GetWindowThreadProcessId(hwnd, Some(&mut window_pid)) };
        if window_pid != search.pid || unsafe { GetWindowTextLengthW(hwnd) } <= 0 {
            return BOOL(1);
        }
        let mut title = vec![0u16; 64];
        let copied = unsafe { GetWindowTextW(hwnd, &mut title) };
        if copied <= 0 || String::from_utf16_lossy(&title[..copied as usize]) != "FocusLock Frog" {
            return BOOL(1);
        }
        if unsafe { PostMessageW(Some(hwnd), 0x8003, WPARAM(0), LPARAM(0)) }.is_ok() {
            search.found = true;
            BOOL(0)
        } else {
            BOOL(1)
        }
    }

    let mut search = Search { pid, found: false };
    unsafe {
        EnumWindows(Some(callback), LPARAM(&mut search as *mut _ as isize))
            .map_err(|error| format!("Could not find the Void launcher window: {error}"))?;
    }
    if search.found {
        Ok(())
    } else {
        Err("Could not find the FocusLock Frog window owned by the running helper".into())
    }
}

#[cfg(not(windows))]
fn post_return_to_launcher(_pid: u32) -> Result<(), String> {
    Err("Returning to the Void launcher is only available on Windows".into())
}

fn start_or_update(
    app: &AppHandle,
    state: &VoidSessionState,
    raw_snapshot: VoidSnapshot,
) -> Result<VoidLauncherStatus, String> {
    let _launch_guard = state
        .launch_lock
        .lock()
        .map_err(|_| "Void launcher lock is unavailable")?;
    let snapshot = raw_snapshot.validate_and_resolve()?;
    {
        let mut slot = state
            .child
            .lock()
            .map_err(|_| "Void launcher lock is unavailable")?;
        if let Some(managed) = slot.as_mut() {
            if managed
                .child
                .try_wait()
                .map_err(|e| e.to_string())?
                .is_none()
            {
                write_snapshot(&mut managed.stdin, &snapshot)?;
                return Ok(VoidLauncherStatus {
                    running: true,
                    launch_error: None,
                });
            }
            *slot = None;
        }
    }

    let path = helper_path(app)?;
    let mut child = Command::new(&path)
        .arg("--focuslock")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("Could not start bundled Void launcher: {e}"))?;
    let mut stdin = child
        .stdin
        .take()
        .ok_or("Void launcher stdin was unavailable")?;
    // Send the first complete state before reporting successful startup.
    if let Err(error) = write_snapshot(&mut stdin, &snapshot) {
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }
    let stdout = child
        .stdout
        .take()
        .ok_or("Void launcher stdout was unavailable")?;
    let stderr = child
        .stderr
        .take()
        .ok_or("Void launcher stderr was unavailable")?;
    let (ready_sender, ready_receiver) = mpsc::channel();
    let pid = child.id();
    *state
        .last_error
        .lock()
        .map_err(|_| "Void launcher status lock is unavailable")? = None;
    *state
        .child
        .lock()
        .map_err(|_| "Void launcher lock is unavailable")? = Some(ManagedChild { child, stdin });
    spawn_stdout_reader(app.clone(), state.clone(), pid, Some(ready_sender), stdout);
    spawn_stderr_reader(stderr);
    spawn_child_monitor(state.clone(), app.clone(), pid);
    match ready_receiver.recv_timeout(Duration::from_secs(5)) {
        Ok(Ok(())) => {}
        Ok(Err(error)) => return Err(error),
        Err(mpsc::RecvTimeoutError::Disconnected) => {
            return Err("Void launcher exited before it became ready".into())
        }
        Err(mpsc::RecvTimeoutError::Timeout) => {
            let error = "Void launcher did not show its window within 5 seconds".to_string();
            fail_generation(app, state, pid, error.clone());
            return Err(error);
        }
    }
    Ok(VoidLauncherStatus {
        running: true,
        launch_error: None,
    })
}

fn stop(state: &VoidSessionState) -> Result<(), String> {
    let mut slot = state
        .child
        .lock()
        .map_err(|_| "Void launcher lock is unavailable")?;
    let Some(mut managed) = slot.take() else {
        return Ok(());
    };
    // Closing stdin is the graceful shutdown signal; kill only this owned child
    // if it fails to exit promptly.
    drop(managed.stdin);
    let deadline = Instant::now() + Duration::from_millis(900);
    loop {
        match managed.child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(40)),
            _ => {
                let _ = managed.child.kill();
                let _ = managed.child.wait();
                break;
            }
        }
    }
    Ok(())
}

fn spawn_child_monitor(state: VoidSessionState, app: AppHandle, pid: u32) {
    thread::spawn(move || loop {
        thread::sleep(Duration::from_millis(300));
        let exited = match state.child.lock() {
            Ok(mut slot) => match slot.as_mut() {
                Some(managed) if managed.child.id() == pid => match managed.child.try_wait() {
                    Ok(Some(_)) | Err(_) => {
                        *slot = None;
                        true
                    }
                    Ok(None) => false,
                },
                _ => return,
            },
            Err(_) => return,
        };
        if exited {
            let _ = app.emit(
                "focuslock-void-action",
                serde_json::json!({"action":"closed"}),
            );
            return;
        }
    });
}

fn spawn_stdout_reader(
    app: AppHandle,
    state: VoidSessionState,
    pid: u32,
    ready_sender: Option<mpsc::Sender<Result<(), String>>>,
    stdout: impl Read + Send + 'static,
) {
    thread::spawn(move || {
        let mut ready_sender = ready_sender;
        let mut reader = BufReader::new(stdout);
        let failure = loop {
            let mut frame = Vec::new();
            match (&mut reader)
                .take(MAX_FRAME_BYTES as u64)
                .read_until(b'\n', &mut frame)
            {
                Ok(0) => break Some("Void launcher output closed".to_string()),
                Ok(size) if size <= MAX_FRAME_BYTES => match parse_pipe_frame(&frame) {
                    Ok(VoidAction::Ready)
                        if ready_sender.is_some() && is_current_generation(&state, pid) =>
                    {
                        let _ = ready_sender.take().unwrap().send(Ok(()));
                    }
                    Ok(VoidAction::Ready) => {
                        break Some("Void launcher sent a duplicate ready action".into())
                    }
                    Ok(action) if is_current_generation(&state, pid) => {
                        if let Some(sender) = ready_sender.take() {
                            let error = "Void launcher sent an action before becoming ready";
                            let _ = sender.send(Err(error.into()));
                            break Some(error.into());
                        }
                        if matches!(action, VoidAction::OpenFocuslock) {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                        let _ = app.emit("focuslock-void-action", action);
                    }
                    Ok(_) => break None,
                    Err(error) => break Some(error),
                },
                Ok(_) => break Some("Void launcher frame exceeded the 64 KB limit".into()),
                Err(_) => break Some("Could not read Void launcher output".into()),
            }
        };
        if let Some(error) = failure {
            if let Some(sender) = ready_sender.take() {
                let _ = sender.send(Err(error.clone()));
            }
            fail_generation(&app, &state, pid, error);
        }
    });
}

fn is_current_generation(state: &VoidSessionState, pid: u32) -> bool {
    state
        .child
        .lock()
        .map(|slot| {
            slot.as_ref()
                .is_some_and(|managed| managed.child.id() == pid)
        })
        .unwrap_or(false)
}

fn fail_generation(app: &AppHandle, state: &VoidSessionState, pid: u32, error: String) {
    let managed = match state.child.lock() {
        Ok(mut slot) if slot.as_ref().is_some_and(|child| child.child.id() == pid) => slot.take(),
        _ => None,
    };
    let Some(mut managed) = managed else {
        // The helper was intentionally stopped or superseded; its reader is
        // stale and must not affect the current UI session.
        return;
    };
    if let Ok(mut last_error) = state.last_error.lock() {
        *last_error = Some(error.clone());
    }
    drop(managed.stdin);
    let _ = managed.child.kill();
    let _ = managed.child.wait();
    let _ = app.emit(
        "focuslock-void-action",
        serde_json::json!({"action":"closed"}),
    );
    let _ = app.emit(
        "focuslock-void-action",
        serde_json::json!({"action":"launch_error","message":error}),
    );
}

fn validate_action(action: &VoidAction) -> Result<(), ()> {
    match action {
        VoidAction::SelectFrog { title, app_ids, domains } => {
            if bounded_text(title, 1, 200, "title").is_err() || app_ids.len() > 64 || domains.len() > 64 ||
                app_ids.iter().any(|id| id.is_empty() || id.len() > 260 || !id.to_lowercase().ends_with(".exe") || id.contains(['/', '\\', '\0'])) ||
                domains.iter().any(|domain| !valid_domain(domain)) {
                Err(())
            } else { Ok(()) }
        }
        VoidAction::SetDuration { minutes } if (1..=480).contains(minutes) => Ok(()),
        VoidAction::SetDuration { .. } => Err(()),
        VoidAction::LaunchError { message }
            if !message.trim().is_empty() && message.len() <= 512 =>
        {
            Ok(())
        }
        VoidAction::LaunchError { .. } => Err(()),
        _ => Ok(()),
    }
}

fn parse_action_frame(frame: &[u8]) -> Result<VoidAction, String> {
    let mut value: serde_json::Value =
        serde_json::from_slice(frame).map_err(|_| "Invalid JSON from Void launcher".to_string())?;
    let object = value
        .as_object_mut()
        .ok_or_else(|| "Void launcher frame must be a JSON object".to_string())?;
    let version = object
        .remove("protocolVersion")
        .and_then(|version| version.as_u64())
        .ok_or_else(|| "Void launcher frame has no protocolVersion".to_string())?;
    if version != 1 {
        return Err("Unsupported Void launcher action protocol version".into());
    }
    let action_name = object
        .get("action")
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| "Void launcher frame has no action".to_string())?;
    let allowed_fields: &[&str] = match action_name {
        "toggle_timer" | "tick_off" | "open_focuslock" | "closed" | "ready" => &["action"],
        "set_duration" => &["action", "minutes"],
        "launch_error" => &["action", "message"],
        "select_frog" => &["action", "title", "appIds", "domains"],
        _ => return Err("Unknown Void launcher action".into()),
    };
    if object
        .keys()
        .any(|field| !allowed_fields.contains(&field.as_str()))
    {
        return Err("Void launcher action contains unexpected fields".into());
    }
    object.remove("protocolVersion");
    let action: VoidAction = serde_json::from_value(value)
        .map_err(|_| "Invalid Void launcher action frame".to_string())?;
    validate_action(&action).map_err(|_| "Void launcher action values are invalid".to_string())?;
    Ok(action)
}

fn parse_pipe_frame(frame: &[u8]) -> Result<VoidAction, String> {
    if frame.len() > MAX_FRAME_BYTES || frame.last() != Some(&b'\n') {
        return Err("Void launcher output did not contain one bounded newline frame".into());
    }
    let mut payload = &frame[..frame.len() - 1];
    if payload.last() == Some(&b'\r') {
        payload = &payload[..payload.len() - 1];
    }
    parse_action_frame(payload)
}

fn spawn_stderr_reader(stderr: impl Read + Send + 'static) {
    thread::spawn(move || {
        let mut reader = stderr;
        let mut buffer = [0_u8; 1024];
        let mut captured = 0usize;
        loop {
            match reader.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(size) => {
                    if captured < 8 * 1024 {
                        let count = size.min(8 * 1024 - captured);
                        let text = String::from_utf8_lossy(&buffer[..count]);
                        eprintln!("Void launcher: {}", text.trim_end());
                        captured += count;
                    }
                    // Continue draining after the diagnostic cap to avoid
                    // blocking the helper on a full stderr pipe.
                }
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::windows_capture::RunningApp;

    fn snapshot(phase: &str, block_minutes: u32) -> VoidSnapshot {
        VoidSnapshot {
            protocol_version: 1,
            title: "Study".into(),
            project_name: Some(" ".into()),
            phase: phase.into(),
            grace_remaining_seconds: if phase == "grace" { 300 } else { 0 },
            cycle_date: "2026-10-05".into(),
            tracked_seconds: 0,
            required_seconds: 1,
            ticked_off: false,
            running: false,
            remaining_seconds: 1,
            block_minutes,
            tools: vec![],
            domains: vec!["example.com".into()],
        }
    }

    #[test]
    fn accepts_frog_phases_and_duration_boundary() {
        for phase in ["not_armed", "grace", "pick_frog", "working", "complete"] {
            let normalized = snapshot(phase, 480).validate_and_resolve().unwrap();
            assert_eq!(normalized.phase, phase);
            assert_eq!(normalized.project_name, None);
        }
        assert!(snapshot("working", 481).validate_and_resolve().is_err());
        let mut invalid_grace = snapshot("grace", 25);
        invalid_grace.grace_remaining_seconds = 301;
        assert!(invalid_grace.validate_and_resolve().is_err());
        assert!(snapshot("short_break", 25).validate_and_resolve().is_err());
    }

    #[test]
    fn resolves_missing_executable_path_only_from_observed_identity() {
        let running = vec![RunningApp {
            app_id: "code.exe".into(),
            app_name: "Visual Studio Code".into(),
            executable_path: Some(r"C:\Apps\Code.exe".into()),
            window_title: "notes".into(),
        }];
        assert_eq!(
            resolve_running_tool("Code.EXE", &running)
                .and_then(|app| app.executable_path.as_deref()),
            Some(r"C:\Apps\Code.exe")
        );
        assert!(resolve_running_tool("other.exe", &running).is_none());
        // An unresolved but well-formed tool remains in the protocol so the
        // helper can match it later by its saved executable identity.
        let mut state = snapshot("working", 25);
        state.tools.push(VoidTool {
            id: "not-running.exe".into(),
            label: "Not running".into(),
            executable_path: None,
            app_user_model_id: None,
        });
        assert!(state.validate_and_resolve().is_ok());
    }

    #[test]
    fn rejects_non_host_domains_and_unknown_actions() {
        assert!(valid_domain("sub.example.com"));
        assert!(!valid_domain("https://example.com/path"));
        assert!(parse_action_frame(br#"{"protocolVersion":1,"action":"spawn_process"}"#).is_err());
    }

    #[test]
    fn accepts_csharp_action_envelopes_and_rejects_bad_versions_or_frames() {
        let selected = parse_action_frame(
            br#"{"protocolVersion":1,"action":"select_frog","title":"Study chemistry","appIds":["Code.exe"],"domains":["docs.google.com"]}"#,
        ).unwrap();
        assert!(matches!(selected, VoidAction::SelectFrog { title, app_ids, domains }
            if title == "Study chemistry" && app_ids == ["Code.exe"] && domains == ["docs.google.com"]));
        for invalid in [
            br#"{"protocolVersion":1,"action":"select_frog","title":"  ","appIds":[],"domains":[]}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"select_frog","title":"Study","appIds":["../Code.exe"],"domains":[]}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"select_frog","title":"Study","appIds":[],"domains":["https://docs.google.com/path"]}"#.as_slice(),
        ] {
            assert!(parse_action_frame(invalid).is_err(), "accepted invalid selection: {}", String::from_utf8_lossy(invalid));
        }
        let frames = [
            br#"{"protocolVersion":1,"action":"ready"}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"toggle_timer"}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"tick_off"}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"open_focuslock"}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"closed"}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"set_duration","minutes":480}"#.as_slice(),
            br#"{"protocolVersion":1,"action":"launch_error","message":"Could not start."}"#
                .as_slice(),
            br#"{"protocolVersion":1,"action":"tick_off"}"#.as_slice(),
        ];
        for frame in frames {
            assert!(
                parse_action_frame(frame).is_ok(),
                "invalid frame: {}",
                String::from_utf8_lossy(frame)
            );
            let mut wire_frame = frame.to_vec();
            wire_frame.push(b'\n');
            assert!(parse_pipe_frame(&wire_frame).is_ok());
        }
        assert!(parse_action_frame(br#"{"protocolVersion":2,"action":"closed"}"#).is_err());
        assert!(parse_action_frame(br#"{"action":"closed"}"#).is_err());
        assert!(parse_action_frame(b"not-json").is_err());
        assert!(parse_action_frame(
            br#"{"protocolVersion":1,"action":"closed","toolId":"other.exe"}"#
        )
        .is_err());
        assert!(parse_pipe_frame(br#"{"protocolVersion":1,"action":"closed"}"#).is_err());
        let mut oversized = vec![b' '; MAX_FRAME_BYTES];
        oversized.push(b'\n');
        assert!(parse_pipe_frame(&oversized).is_err());
    }
}
