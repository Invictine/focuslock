//! Native messaging bridge used by the FocusLock browser extension.
//!
//! The extension can attest only browser windows that can be independently
//! matched to visible top-level Windows HWNDs owned by the browser process.

use serde::{Deserialize, Serialize};
use std::{fs, path::Path};

pub const EXTENSION_ID: &str = "fkkpmoiageeieaoplphafmhjkkdadcnf";
const HOST_NAME: &str = "com.focuslock.browser";
const MAX_MESSAGE_BYTES: usize = 64 * 1024;
const MAX_TITLE_CHARS: usize = 512;
const MAX_WINDOWS: usize = 128;
const MAX_LEASE_FILE_BYTES: u64 = 256 * 1024;
const LEASE_TTL_MS: u64 = 10_000;
const MAX_MANIFEST_BYTES: u64 = 64 * 1024;

const CHROME_REGISTRY_BASE: &str = r"Software\Google\Chrome\NativeMessagingHosts";
const EDGE_REGISTRY_BASE: &str = r"Software\Microsoft\Edge\NativeMessagingHosts";
const BRAVE_REGISTRY_BASE: &str = r"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts";
const VIVALDI_REGISTRY_BASE: &str = r"Software\Vivaldi\NativeMessagingHosts";
const OPERA_REGISTRY_BASE: &str = r"Software\Opera Software\Opera Stable\NativeMessagingHosts";
const OPERA_GX_REGISTRY_BASE: &str =
    r"Software\Opera Software\Opera GX Stable\NativeMessagingHosts";

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HostMessage {
    #[serde(rename = "type")]
    message_type: String,
    version: u32,
    all_urls: bool,
    incognito_allowed: bool,
    windows: Vec<ExtensionWindow>,
}

#[derive(Clone, Debug, Deserialize)]
struct ExtensionWindow {
    id: i32,
    title: String,
    #[serde(default)]
    incognito: bool,
    left: i32,
    top: i32,
    width: i32,
    height: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct BrowserLease {
    browser_pid: u32,
    app_id: String,
    updated_at_ms: u64,
    all_urls: bool,
    incognito_allowed: bool,
    window_handles: Vec<i64>,
}

#[derive(Debug, Deserialize)]
struct NativeHostManifest {
    name: String,
    path: String,
    #[serde(rename = "type")]
    host_type: String,
    allowed_origins: Vec<String>,
}

fn registry_bases_for_browser(app_id: &str) -> Vec<&'static str> {
    let app_id = app_id
        .rsplit(['\\', '/'])
        .next()
        .unwrap_or(app_id)
        .to_ascii_lowercase();
    let browser_key = match app_id.as_str() {
        "msedge.exe" => Some(EDGE_REGISTRY_BASE),
        "brave.exe" => Some(BRAVE_REGISTRY_BASE),
        "vivaldi.exe" => Some(VIVALDI_REGISTRY_BASE),
        "opera.exe" => Some(OPERA_REGISTRY_BASE),
        "opera_gx.exe" => Some(OPERA_GX_REGISTRY_BASE),
        "chrome.exe" | "arc.exe" => Some(CHROME_REGISTRY_BASE),
        _ => None,
    };
    let Some(browser_key) = browser_key else {
        return Vec::new();
    };
    if browser_key == CHROME_REGISTRY_BASE {
        vec![browser_key]
    } else {
        vec![browser_key, CHROME_REGISTRY_BASE]
    }
}

fn all_registry_bases() -> [&'static str; 6] {
    [
        CHROME_REGISTRY_BASE,
        EDGE_REGISTRY_BASE,
        BRAVE_REGISTRY_BASE,
        VIVALDI_REGISTRY_BASE,
        OPERA_REGISTRY_BASE,
        OPERA_GX_REGISTRY_BASE,
    ]
}

fn parse_registry_manifest_path(output: &str) -> Option<std::path::PathBuf> {
    output.lines().find_map(|line| {
        let marker = line
            .as_bytes()
            .windows(b"REG_SZ".len())
            .position(|window| window.eq_ignore_ascii_case(b"REG_SZ"))?;
        let value = line[marker + "REG_SZ".len()..].trim().trim_matches('"');
        (!value.is_empty()).then(|| std::path::PathBuf::from(value))
    })
}

fn validate_native_host_manifest(bytes: &[u8], executable: &Path) -> bool {
    if bytes.is_empty() || bytes.len() as u64 > MAX_MANIFEST_BYTES {
        return false;
    }
    let Ok(manifest) = serde_json::from_slice::<NativeHostManifest>(bytes) else {
        return false;
    };
    if manifest.name != HOST_NAME
        || manifest.host_type != "stdio"
        || manifest.allowed_origins != [format!("chrome-extension://{EXTENSION_ID}/")]
    {
        return false;
    }
    let (Ok(manifest_executable), Ok(current_executable)) = (
        fs::canonicalize(&manifest.path),
        fs::canonicalize(executable),
    ) else {
        return false;
    };
    manifest_executable
        .to_string_lossy()
        .eq_ignore_ascii_case(&current_executable.to_string_lossy())
}

fn registered_manifest_data_dir(
    manifest_path: &Path,
    executable: &Path,
) -> Option<std::path::PathBuf> {
    use std::io::Read as _;

    let metadata = fs::metadata(manifest_path).ok()?;
    if !metadata.is_file() || metadata.len() > MAX_MANIFEST_BYTES {
        return None;
    }
    let file = fs::File::open(manifest_path).ok()?;
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(MAX_MANIFEST_BYTES + 1)
        .read_to_end(&mut bytes)
        .ok()?;
    if !validate_native_host_manifest(&bytes, executable) {
        return None;
    }
    fs::canonicalize(manifest_path)
        .ok()?
        .parent()
        .map(Path::to_path_buf)
}

/// True when `app_id` is a Chromium browser for which this host can attest
/// native top-level windows. Firefox intentionally is not supported.
pub fn supported_browser(app_id: &str) -> bool {
    matches!(
        app_id
            .rsplit(['\\', '/'])
            .next()
            .unwrap_or(app_id)
            .to_ascii_lowercase()
            .as_str(),
        "chrome.exe"
            | "msedge.exe"
            | "brave.exe"
            | "vivaldi.exe"
            | "opera.exe"
            | "opera_gx.exe"
            | "arc.exe"
    )
}

/// Read a recent lease and require an exact browser process, executable, and
/// OS window handle match. Invalid, oversized, stale, or ambiguous lease files
/// never grant browser health.
pub fn read_window_health(
    data_dir: &Path,
    hwnd: isize,
    pid: u32,
    app_id: &str,
    now_ms: u64,
) -> bool {
    use std::io::Read as _;

    if hwnd == 0 || pid == 0 || !supported_browser(app_id) {
        return false;
    }
    let directory = data_dir.join("browser-leases");
    let Ok(entries) = fs::read_dir(directory) else {
        return false;
    };
    for entry in entries.flatten().take(MAX_WINDOWS) {
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if !metadata.is_file() || metadata.len() > MAX_LEASE_FILE_BYTES {
            continue;
        }
        let Ok(file) = fs::File::open(entry.path()) else {
            continue;
        };
        let mut bytes =
            Vec::with_capacity((metadata.len() as usize).min(MAX_LEASE_FILE_BYTES as usize));
        if std::io::Read::take(file, MAX_LEASE_FILE_BYTES + 1)
            .read_to_end(&mut bytes)
            .is_err()
        {
            continue;
        }
        if bytes.len() as u64 > MAX_LEASE_FILE_BYTES {
            continue;
        }
        let Ok(lease) = serde_json::from_slice::<BrowserLease>(&bytes) else {
            continue;
        };
        let age = now_ms.checked_sub(lease.updated_at_ms);
        if lease.browser_pid == pid
            && lease.app_id.eq_ignore_ascii_case(app_id)
            && lease.all_urls
            && !lease.window_handles.is_empty()
            && lease.window_handles.len() <= MAX_WINDOWS
            && age.is_some_and(|age| age <= LEASE_TTL_MS)
            && lease
                .window_handles
                .iter()
                .filter(|&&handle| handle == hwnd as i64)
                .count()
                == 1
        {
            return true;
        }
    }
    false
}

/// Register this executable as the user's native messaging host for supported
/// Chromium browsers. The manifest lives beside FocusLock's app data.
#[cfg(windows)]
pub fn register_host(data_dir: &Path) -> Result<(), String> {
    use std::{os::windows::process::CommandExt, process::Command};
    use windows::Win32::System::Threading::CREATE_NO_WINDOW;

    fs::create_dir_all(data_dir).map_err(|e| format!("Create native host directory: {e}"))?;
    let executable =
        std::env::current_exe().map_err(|e| format!("Locate FocusLock executable: {e}"))?;
    let manifest = data_dir.join("browser-native-host.json");
    let contents = serde_json::json!({
        "name": HOST_NAME,
        "description": "FocusLock browser window health bridge",
        "path": executable,
        "type": "stdio",
        "allowed_origins": [format!("chrome-extension://{EXTENSION_ID}/")]
    });
    write_json_atomic(&manifest, &contents)?;
    let manifest_arg = manifest.to_string_lossy().into_owned();
    let is_32_bit_os = std::env::var("PROCESSOR_ARCHITEW6432")
        .or_else(|_| std::env::var("PROCESSOR_ARCHITECTURE"))
        .is_ok_and(|arch| arch.eq_ignore_ascii_case("x86"));
    for key in all_registry_bases() {
        for view in if is_32_bit_os {
            vec!["/reg:32"]
        } else {
            vec!["/reg:32", "/reg:64"]
        } {
            let status = Command::new("reg.exe")
                .args([
                    "add",
                    &format!("HKCU\\{key}\\{HOST_NAME}"),
                    "/ve",
                    "/t",
                    "REG_SZ",
                    "/d",
                    &manifest_arg,
                    view,
                    "/f",
                ])
                .creation_flags(CREATE_NO_WINDOW.0)
                .status()
                .map_err(|e| format!("Start hidden reg.exe: {e}"))?;
            if !status.success() {
                return Err(format!(
                    "Could not register native host under HKCU\\{key} ({view})"
                ));
            }
        }
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn register_host(_data_dir: &Path) -> Result<(), String> {
    Err("The FocusLock native messaging host is available only on Windows".into())
}

/// Recognize native-messaging invocation before the desktop application starts.
/// An argument with the native-host scheme is always consumed, including an
/// unauthorized extension ID, so it can never open the normal app UI.
#[cfg(windows)]
pub fn maybe_run_host() -> bool {
    let Some(argument) = std::env::args_os().nth(1) else {
        return false;
    };
    let Some(argument) = argument.to_str() else {
        return false;
    };
    if !argument.starts_with("chrome-extension://") {
        return false;
    }
    let expected = format!("chrome-extension://{EXTENSION_ID}/");
    if argument != expected {
        return true;
    }
    if let Err(error) = run_host() {
        eprintln!("FocusLock browser connection failed: {error}");
    }
    true
}

#[cfg(not(windows))]
pub fn maybe_run_host() -> bool {
    false
}

#[cfg(windows)]
fn run_host() -> Result<(), String> {
    use std::io::{self, Read};

    let browser = platform::browser_ancestor(std::process::id())
        .ok_or_else(|| "Could not identify the launching Chromium browser".to_string())?;
    let data_dir = native_host_data_dir(&browser.1)?;
    let process_id = std::process::id();
    let lease_path = data_dir
        .join("browser-leases")
        .join(format!("host-{process_id}.json"));
    let mut stdin = io::stdin().lock();
    let mut stdout = io::stdout().lock();
    loop {
        let mut length_bytes = [0u8; 4];
        match stdin.read_exact(&mut length_bytes) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::UnexpectedEof => break,
            Err(error) => return Err(format!("Read native message length: {error}")),
        }
        let length = u32::from_le_bytes(length_bytes) as usize;
        if !valid_frame_length(length) {
            return Err("Native message length is invalid".into());
        }
        let mut bytes = vec![0; length];
        stdin
            .read_exact(&mut bytes)
            .map_err(|e| format!("Read native message: {e}"))?;
        let message = match parse_message_payload(&bytes) {
            Ok(message) => message,
            Err(_) => {
                write_response(&mut stdout, false)?;
                continue;
            }
        };
        if !valid_message(&message) {
            write_response(&mut stdout, false)?;
            continue;
        }
        let lease = resolve_lease(&message, &browser);
        if let Some(lease) = lease {
            write_json_atomic(&lease_path, &lease)?;
        } else {
            let _ = fs::remove_file(&lease_path);
        }
        write_response(&mut stdout, true)?;
    }
    let _ = fs::remove_file(lease_path);
    Ok(())
}

#[cfg(windows)]
fn native_host_data_dir(app_id: &str) -> Result<std::path::PathBuf, String> {
    use std::{os::windows::process::CommandExt, process::Command};
    use windows::Win32::System::Threading::CREATE_NO_WINDOW;

    let executable = std::env::current_exe()
        .map_err(|_| "Could not identify the native host executable".to_string())?;
    for base in registry_bases_for_browser(app_id) {
        for view in ["/reg:32", "/reg:64"] {
            let output = Command::new("reg.exe")
                .args(["query", &format!("HKCU\\{base}\\{HOST_NAME}"), "/ve", view])
                .creation_flags(CREATE_NO_WINDOW.0)
                .output();
            let Ok(output) = output else { continue };
            if !output.status.success() {
                continue;
            }
            let Some(manifest_path) =
                parse_registry_manifest_path(&String::from_utf8_lossy(&output.stdout))
            else {
                continue;
            };
            if let Some(data_dir) = registered_manifest_data_dir(&manifest_path, &executable) {
                return Ok(data_dir);
            }
        }
    }
    Err("No valid FocusLock native-host manifest is registered for this browser".into())
}

#[cfg(windows)]
fn write_response(output: &mut impl std::io::Write, ok: bool) -> Result<(), String> {
    let bytes: &[u8] = if ok {
        br#"{"ok":true}"#
    } else {
        br#"{"ok":false}"#
    };
    output
        .write_all(&(bytes.len() as u32).to_le_bytes())
        .and_then(|_| output.write_all(bytes))
        .and_then(|_| output.flush())
        .map_err(|e| format!("Write native response: {e}"))
}

fn valid_message(message: &HostMessage) -> bool {
    message.message_type == "heartbeat"
        && message.version == 1
        && message.windows.len() <= MAX_WINDOWS
        && message.windows.iter().all(|window| {
            window.title.chars().count() <= MAX_TITLE_CHARS
                && window.id > 0
                && window.width > 0
                && window.height > 0
                && window.width <= 100_000
                && window.height <= 100_000
        })
}

fn should_attest_window(window: &ExtensionWindow, incognito_allowed: bool) -> bool {
    !window.incognito || incognito_allowed
}

fn parse_message_payload(bytes: &[u8]) -> Result<HostMessage, serde_json::Error> {
    serde_json::from_slice(bytes)
}

#[cfg(windows)]
fn resolve_lease(message: &HostMessage, browser: &(u32, String)) -> Option<BrowserLease> {
    let (browser_pid, app_id) = browser;
    let windows = platform::visible_windows(*browser_pid);
    let mut matched_counts = std::collections::HashMap::new();
    for reported in &message.windows {
        if !should_attest_window(reported, message.incognito_allowed) {
            continue;
        }
        let candidates: Vec<_> = windows
            .iter()
            .filter(|native| window_matches(reported, native))
            .map(|native| native.hwnd)
            .collect();
        if candidates.len() == 1 {
            *matched_counts.entry(candidates[0] as i64).or_insert(0usize) += 1;
        }
    }
    let mut matched: Vec<_> = matched_counts
        .into_iter()
        .filter_map(|(hwnd, count)| (count == 1).then_some(hwnd))
        .collect();
    matched.sort_unstable();
    Some(BrowserLease {
        browser_pid: *browser_pid,
        app_id: app_id.clone(),
        updated_at_ms: epoch_ms(),
        all_urls: message.all_urls,
        incognito_allowed: message.incognito_allowed,
        window_handles: matched,
    })
}

fn epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u64::MAX as u128) as u64
}

#[cfg(windows)]
#[derive(Clone, Debug)]
struct NativeWindow {
    hwnd: isize,
    title: String,
    rect: (i32, i32, i32, i32),
    dpi_scale: f64,
}

#[cfg(windows)]
fn window_matches(reported: &ExtensionWindow, native: &NativeWindow) -> bool {
    title_matches(&reported.title, &native.title)
        && bounds_match(
            (reported.left, reported.top, reported.width, reported.height),
            native.rect,
            native.dpi_scale,
        )
}

fn title_matches(reported: &str, native: &str) -> bool {
    let reported = reported.trim();
    !reported.is_empty() && native.starts_with(reported)
}

fn bounds_match(
    reported: (i32, i32, i32, i32),
    native: (i32, i32, i32, i32),
    dpi_scale: f64,
) -> bool {
    const TOLERANCE: i32 = 12;
    if !dpi_scale.is_finite() || !(1.0..=4.0).contains(&dpi_scale) {
        return false;
    }
    let scaled = (
        (reported.0 as f64 * dpi_scale).round() as i32,
        (reported.1 as f64 * dpi_scale).round() as i32,
        (reported.2 as f64 * dpi_scale).round() as i32,
        (reported.3 as f64 * dpi_scale).round() as i32,
    );
    (scaled.0 as i64 - native.0 as i64).abs() <= TOLERANCE as i64
        && (scaled.1 as i64 - native.1 as i64).abs() <= TOLERANCE as i64
        && (scaled.2 as i64 - native.2 as i64).abs() <= TOLERANCE as i64
        && (scaled.3 as i64 - native.3 as i64).abs() <= TOLERANCE as i64
}

#[cfg(windows)]
fn write_json_atomic(path: &Path, value: &impl Serialize) -> Result<(), String> {
    use std::{
        os::windows::ffi::OsStrExt,
        sync::atomic::{AtomicU64, Ordering},
    };
    use windows::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);
    let parent = path
        .parent()
        .ok_or_else(|| "Output path has no parent".to_string())?;
    fs::create_dir_all(parent).map_err(|e| format!("Create output directory: {e}"))?;
    let temp = parent.join(format!(
        ".tmp-{}-{}",
        std::process::id(),
        NEXT_TEMP.fetch_add(1, Ordering::Relaxed)
    ));
    let bytes = serde_json::to_vec(value).map_err(|e| format!("Serialize JSON: {e}"))?;
    fs::write(&temp, bytes).map_err(|e| format!("Write temporary JSON: {e}"))?;
    let from: Vec<u16> = temp.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let moved = unsafe {
        MoveFileExW(
            windows::core::PCWSTR(from.as_ptr()),
            windows::core::PCWSTR(to.as_ptr()),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if let Err(error) = moved {
        let _ = fs::remove_file(temp);
        return Err(format!("Replace JSON atomically: {error}"));
    }
    Ok(())
}

#[cfg(not(windows))]
fn write_json_atomic(path: &Path, value: &impl Serialize) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "Output path has no parent".to_string())?;
    fs::create_dir_all(parent).map_err(|e| format!("Create output directory: {e}"))?;
    let temp = parent.join(format!(".tmp-{}", std::process::id()));
    fs::write(&temp, serde_json::to_vec(value).map_err(|e| e.to_string())?)
        .map_err(|e| format!("Write temporary JSON: {e}"))?;
    fs::rename(&temp, path).map_err(|e| format!("Replace JSON: {e}"))
}

#[cfg(windows)]
mod platform {
    use super::{NativeWindow, MAX_TITLE_CHARS};
    use std::cell::RefCell;
    use windows::core::BOOL;
    use windows::Win32::{
        Foundation::{CloseHandle, HWND, LPARAM, RECT},
        System::Diagnostics::ToolHelp::{
            CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
            TH32CS_SNAPPROCESS,
        },
        UI::{
            HiDpi::{
                GetDpiForWindow, SetThreadDpiAwarenessContext,
                DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2,
            },
            WindowsAndMessaging::{
                EnumWindows, GetWindowRect, GetWindowTextLengthW, GetWindowTextW,
                GetWindowThreadProcessId, IsWindowVisible,
            },
        },
    };

    pub fn browser_ancestor(host_pid: u32) -> Option<(u32, String)> {
        let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0).ok()? };
        let mut entries = std::collections::HashMap::new();
        let mut entry = PROCESSENTRY32W {
            dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32,
            ..Default::default()
        };
        unsafe {
            if Process32FirstW(snapshot, &mut entry).is_ok() {
                loop {
                    let image = String::from_utf16_lossy(&entry.szExeFile)
                        .trim_end_matches('\0')
                        .to_ascii_lowercase();
                    entries.insert(entry.th32ProcessID, (entry.th32ParentProcessID, image));
                    if Process32NextW(snapshot, &mut entry).is_err() {
                        break;
                    }
                }
            }
            let _ = CloseHandle(snapshot);
        }
        let mut pid = host_pid;
        for _ in 0..32 {
            let (parent, image) = entries.get(&pid)?;
            if super::supported_browser(image) {
                return Some((pid, image.clone()));
            }
            if *parent == 0 || *parent == pid {
                return None;
            }
            pid = *parent;
        }
        None
    }

    pub fn visible_windows(pid: u32) -> Vec<NativeWindow> {
        struct WindowSearch {
            pid: u32,
            windows: RefCell<Vec<NativeWindow>>,
        }
        let search = WindowSearch {
            pid,
            windows: RefCell::new(Vec::new()),
        };
        unsafe extern "system" fn callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
            let search = &*(lparam.0 as *const WindowSearch);
            let mut owner = 0;
            GetWindowThreadProcessId(hwnd, Some(&mut owner));
            if owner == 0 || !IsWindowVisible(hwnd).as_bool() {
                return BOOL(1);
            }
            let length = GetWindowTextLengthW(hwnd).max(0) as usize;
            if length == 0 || length > MAX_TITLE_CHARS * 4 {
                return BOOL(1);
            }
            let mut title = vec![0u16; length + 1];
            let copied = GetWindowTextW(hwnd, &mut title);
            if copied <= 0 {
                return BOOL(1);
            }
            let mut rect = RECT::default();
            if GetWindowRect(hwnd, &mut rect).is_err() {
                return BOOL(1);
            }
            let title = String::from_utf16_lossy(&title[..copied as usize]);
            if owner == search.pid {
                search.windows.borrow_mut().push(NativeWindow {
                    hwnd: hwnd.0 as isize,
                    title,
                    rect: (
                        rect.left,
                        rect.top,
                        rect.right - rect.left,
                        rect.bottom - rect.top,
                    ),
                    dpi_scale: {
                        let dpi = GetDpiForWindow(hwnd);
                        if dpi == 0 {
                            1.0
                        } else {
                            dpi as f64 / 96.0
                        }
                    },
                });
            }
            BOOL(1)
        }
        unsafe {
            // Native-host startup precedes the desktop event loop's DPI setup.
            // Read physical window rectangles consistently on scaled displays.
            let previous_dpi =
                SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
            let _ = EnumWindows(Some(callback), LPARAM(&search as *const _ as isize));
            if !previous_dpi.0.is_null() {
                let _ = SetThreadDpiAwarenessContext(previous_dpi);
            }
        }
        search.windows.into_inner()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn lease(
        pid: u32,
        app_id: &str,
        updated_at_ms: u64,
        all_urls: bool,
        handles: Vec<i64>,
    ) -> BrowserLease {
        BrowserLease {
            browser_pid: pid,
            app_id: app_id.into(),
            updated_at_ms,
            all_urls,
            incognito_allowed: true,
            window_handles: handles,
        }
    }

    #[test]
    fn health_requires_fresh_exact_browser_window_and_all_urls_permission() {
        let temp = tempdir().unwrap();
        let dir = temp.path().join("browser-leases");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("host-123.json");
        fs::write(
            &path,
            serde_json::to_vec(&lease(123, "chrome.exe", 5_000, true, vec![44])).unwrap(),
        )
        .unwrap();
        assert!(read_window_health(
            temp.path(),
            44,
            123,
            "chrome.exe",
            10_000
        ));
        assert!(!read_window_health(
            temp.path(),
            45,
            123,
            "chrome.exe",
            10_000
        ));
        assert!(!read_window_health(
            temp.path(),
            44,
            124,
            "chrome.exe",
            10_000
        ));
        assert!(!read_window_health(
            temp.path(),
            44,
            123,
            "msedge.exe",
            10_000
        ));
        assert!(!read_window_health(
            temp.path(),
            44,
            123,
            "chrome.exe",
            15_001
        ));
        fs::write(
            &path,
            serde_json::to_vec(&lease(123, "chrome.exe", 10_000, false, vec![44])).unwrap(),
        )
        .unwrap();
        assert!(!read_window_health(
            temp.path(),
            44,
            123,
            "chrome.exe",
            10_000
        ));
        fs::write(
            &path,
            serde_json::to_vec(&BrowserLease {
                incognito_allowed: false,
                ..lease(123, "chrome.exe", 10_000, true, vec![44])
            })
            .unwrap(),
        )
        .unwrap();
        assert!(read_window_health(
            temp.path(),
            44,
            123,
            "chrome.exe",
            10_000
        ));
        fs::write(
            &path,
            serde_json::to_vec(&lease(123, "chrome.exe", 10_001, true, vec![44])).unwrap(),
        )
        .unwrap();
        assert!(!read_window_health(
            temp.path(),
            44,
            123,
            "chrome.exe",
            10_000
        ));
    }

    #[test]
    fn rejects_ambiguous_handle_duplicates_and_non_chromium_browsers() {
        let temp = tempdir().unwrap();
        let dir = temp.path().join("browser-leases");
        fs::create_dir_all(&dir).unwrap();
        fs::write(
            dir.join("a.json"),
            serde_json::to_vec(&lease(123, "chrome.exe", 10_000, true, vec![44, 44])).unwrap(),
        )
        .unwrap();
        assert!(!read_window_health(
            temp.path(),
            44,
            123,
            "chrome.exe",
            10_000
        ));
        assert!(!read_window_health(
            temp.path(),
            44,
            123,
            "firefox.exe",
            10_000
        ));
        assert!(supported_browser("C:\\Program Files\\Arc\\arc.exe"));
        assert!(!supported_browser("firefox.exe"));
    }

    #[test]
    fn validates_bounded_heartbeat_fields_and_title_bounds() {
        let base = HostMessage {
            message_type: "heartbeat".into(),
            version: 1,
            all_urls: true,
            incognito_allowed: false,
            windows: vec![ExtensionWindow {
                id: 1,
                title: "Example".into(),
                incognito: false,
                left: 0,
                top: 0,
                width: 100,
                height: 100,
            }],
        };
        assert!(valid_message(&base));
        let mut malformed = base.clone();
        malformed.version = 2;
        assert!(!valid_message(&malformed));
        let mut too_many = base.clone();
        too_many.windows = vec![too_many.windows[0].clone(); MAX_WINDOWS + 1];
        assert!(!valid_message(&too_many));
        let mut oversized_title = base;
        oversized_title.windows[0].title = "x".repeat(MAX_TITLE_CHARS + 1);
        assert!(!valid_message(&oversized_title));
    }

    #[test]
    fn incognito_windows_are_attested_only_when_incognito_access_is_allowed() {
        let normal = ExtensionWindow {
            id: 1,
            title: "Normal".into(),
            incognito: false,
            left: 0,
            top: 0,
            width: 100,
            height: 100,
        };
        let incognito = ExtensionWindow {
            incognito: true,
            ..normal.clone()
        };
        assert!(should_attest_window(&normal, false));
        assert!(!should_attest_window(&incognito, false));
        assert!(should_attest_window(&incognito, true));
        let legacy: ExtensionWindow = serde_json::from_str(
            r#"{"id":1,"title":"Legacy","left":0,"top":0,"width":100,"height":100}"#,
        )
        .unwrap();
        assert!(!legacy.incognito);
    }

    #[test]
    fn native_message_frame_parser_rejects_zero_and_oversize_lengths() {
        assert!(!valid_frame_length(0));
        assert!(!valid_frame_length(MAX_MESSAGE_BYTES + 1));
        assert!(valid_frame_length(MAX_MESSAGE_BYTES));
        assert!(parse_message_payload(br#"{"type":"heartbeat","version":1,"allUrls":true,"incognitoAllowed":false,"windows":[]}"#).is_ok());
        assert!(parse_message_payload(b"not-json").is_err());
        assert!(parse_message_payload(&vec![b' '; MAX_MESSAGE_BYTES + 1]).is_err());
    }

    #[test]
    fn title_and_bounds_must_match_os_window_evidence() {
        assert!(title_matches(
            "Example page",
            "Example page - Google Chrome"
        ));
        assert!(!title_matches("Example", "Google Chrome - Example"));
        assert!(bounds_match(
            (100, 200, 800, 600),
            (125, 250, 1000, 750),
            1.25
        ));
        assert!(!bounds_match(
            (100, 200, 800, 600),
            (400, 800, 3200, 2400),
            1.25
        ));
    }

    #[test]
    fn registry_query_parser_handles_spacing_and_localized_default_label() {
        let output = "HKEY_CURRENT_USER\\Software\\Google\\Chrome\\NativeMessagingHosts\\com.focuslock.browser\n    (Standardwert)       REG_SZ       C:\\Users\\Person\\Focus Lock\\host.json\n";
        assert_eq!(
            parse_registry_manifest_path(output).unwrap(),
            std::path::PathBuf::from(r"C:\Users\Person\Focus Lock\host.json")
        );
        assert!(parse_registry_manifest_path("value REG_DWORD 1").is_none());
    }

    #[test]
    fn manifest_validation_requires_focuslock_identity_and_current_executable() {
        let temp = tempdir().unwrap();
        let install_dir = temp.path().join("install");
        let data_dir = temp.path().join("redirected-app-data");
        fs::create_dir_all(&install_dir).unwrap();
        fs::create_dir_all(&data_dir).unwrap();
        let executable = install_dir.join("FocusLock.exe");
        fs::write(&executable, b"test executable").unwrap();
        let manifest_path = data_dir.join("browser-native-host.json");
        let manifest = serde_json::json!({
            "name": HOST_NAME,
            "description": "FocusLock browser window health bridge",
            "path": executable,
            "type": "stdio",
            "allowed_origins": [format!("chrome-extension://{EXTENSION_ID}/")]
        });
        let bytes = serde_json::to_vec(&manifest).unwrap();
        fs::write(&manifest_path, &bytes).unwrap();
        assert_eq!(
            registered_manifest_data_dir(&manifest_path, &executable).unwrap(),
            fs::canonicalize(&data_dir).unwrap()
        );
        assert_ne!(
            registered_manifest_data_dir(&manifest_path, &executable).unwrap(),
            fs::canonicalize(&install_dir).unwrap()
        );
        let mut invalid = manifest;
        invalid["allowed_origins"] =
            serde_json::json!(["chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/"]);
        assert!(!validate_native_host_manifest(
            &serde_json::to_vec(&invalid).unwrap(),
            &executable
        ));
        assert!(!validate_native_host_manifest(
            &vec![b' '; MAX_MANIFEST_BYTES as usize + 1],
            &executable
        ));
    }
}

fn valid_frame_length(length: usize) -> bool {
    length > 0 && length <= MAX_MESSAGE_BYTES
}
