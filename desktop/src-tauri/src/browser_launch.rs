//! Locate a browser even after the unhealthy window has closed.
use std::path::{Path, PathBuf};

pub fn extensions_url(app_id: &str) -> &'static str {
    match app_id {
        "msedge.exe" => "edge://extensions/",
        "brave.exe" => "brave://extensions/",
        "vivaldi.exe" => "vivaldi://extensions/",
        "opera.exe" | "opera_gx.exe" => "opera://extensions/",
        _ => "chrome://extensions/",
    }
}

fn user_data_dir(app_id: &str) -> Option<PathBuf> {
    let (base, relative) = match app_id {
        "chrome.exe" => ("LOCALAPPDATA", r"Google\Chrome\User Data"),
        "msedge.exe" => ("LOCALAPPDATA", r"Microsoft\Edge\User Data"),
        "brave.exe" => ("LOCALAPPDATA", r"BraveSoftware\Brave-Browser\User Data"),
        "vivaldi.exe" => ("LOCALAPPDATA", r"Vivaldi\User Data"),
        "opera.exe" => ("APPDATA", r"Opera Software\Opera Stable"),
        "opera_gx.exe" => ("APPDATA", r"Opera Software\Opera GX Stable"),
        _ => return None,
    };
    std::env::var_os(base).map(|root| PathBuf::from(root).join(relative))
}

fn safe_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && !name.chars().any(|ch| matches!(ch, '/' | '\\' | '\0'))
        && Path::new(name).components().count() == 1
        && matches!(Path::new(name).components().next(), Some(std::path::Component::Normal(_)))
}

/// Read Chromium's selected profile. Guest and system profiles cannot manage
/// installed extensions, so prefer the first usable regular profile instead.
fn selected_profile(user_data: &Path) -> String {
    let fallback = || "Default".to_owned();
    let Ok(state) = std::fs::read(user_data.join("Local State")) else { return fallback() };
    let Ok(state) = serde_json::from_slice::<serde_json::Value>(&state) else { return fallback() };
    let profiles = state.get("profile");
    let usable = |value: &serde_json::Value| -> Option<String> {
        let name = value.as_str()?;
        if !safe_profile_name(name) || matches!(name, "Guest Profile" | "System Profile") {
            return None;
        }
        user_data.join(name).is_dir().then(|| name.to_owned())
    };
    profiles
        .and_then(|profile| profile.get("last_used"))
        .and_then(usable)
        .or_else(|| {
            profiles
                .and_then(|profile| profile.get("last_active_profiles"))
                .and_then(serde_json::Value::as_array)
                .and_then(|active| active.iter().find_map(usable))
        })
        .unwrap_or_else(fallback)
}

fn extensions_page_args(user_data: Option<&Path>) -> Vec<String> {
    let profile = user_data.map(selected_profile).unwrap_or_else(|| "Default".into());
    vec![
        format!("--profile-directory={profile}"),
        "--new-window".into(),
        "about:blank".into(),
    ]
}

/// Open the browser's extension manager in the last-used regular profile.
/// Chromium rejects privileged chrome:// URLs supplied at process startup, so
/// launch a blank tab first and navigate only the newly created browser window.
pub fn launch_extensions_page(executable: &Path, app_id: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        return launch_extensions_page_windows(executable, app_id);
    }
    #[cfg(not(windows))]
    {
        let _ = (executable, app_id);
        Err("Opening extension settings through the browser UI is supported on Windows only".into())
    }
}

#[cfg(windows)]
fn launch_extensions_page_windows(executable: &Path, app_id: &str) -> Result<(), String> {
    use std::{collections::HashSet, thread, time::{Duration, Instant}};
    use windows::{
        core::BOOL,
        Win32::{
            Foundation::{HWND, LPARAM},
            UI::WindowsAndMessaging::{EnumWindows, IsIconic, IsWindowVisible, SetForegroundWindow},
        },
    };

    unsafe extern "system" fn callback(hwnd: HWND, data: LPARAM) -> BOOL {
        unsafe {
            let windows = &mut *(data.0 as *mut Vec<(isize, u32, String)>);
            if let Some(identity) = crate::windows_capture::browser_window_identity(hwnd.0 as isize) {
                windows.push((identity.window_handle, identity.process_id, identity.app_id));
            }
            BOOL(1)
        }
    }

    fn browser_windows() -> Result<Vec<(isize, u32, String)>, String> {
        let mut windows = Vec::new();
        unsafe {
            EnumWindows(Some(callback), LPARAM(&mut windows as *mut _ as isize))
                .map_err(|error| format!("Could not inspect browser windows: {error}"))?;
        }
        Ok(windows)
    }

    let before: HashSet<isize> = browser_windows()?.into_iter()
        .filter(|(_, _, id)| id.eq_ignore_ascii_case(app_id))
        .map(|(hwnd, _, _)| hwnd).collect();
    let data_dir = user_data_dir(app_id);
    std::process::Command::new(executable)
        .args(extensions_page_args(data_dir.as_deref()))
        .spawn()
        .map_err(|error| format!("Could not start browser: {error}"))?;

    let deadline = Instant::now() + Duration::from_secs(5);
    let (target, target_pid) = 'poll: loop {
        let candidates: Vec<_> = browser_windows()?.into_iter()
            .filter(|(hwnd, _, id)| id.eq_ignore_ascii_case(app_id) && !before.contains(hwnd))
            .filter(|(_, pid, _)| process_matches_executable(*pid, executable))
            .filter(|(hwnd, _, _)| unsafe {
                let hwnd = HWND(*hwnd as *mut _);
                IsWindowVisible(hwnd).as_bool() && !IsIconic(hwnd).as_bool()
            })
            .collect();
        match candidates.as_slice() {
            [candidate] => break 'poll (candidate.0, candidate.1),
            [] => {},
            // Chromium creates temporary root HWNDs during startup. Wait for
            // one visible content window rather than choosing an arbitrary one.
            _ => {},
        }
        if Instant::now() >= deadline {
            return Err("The browser opened, but FocusLock could not identify its new window".into());
        }
        thread::sleep(Duration::from_millis(100));
    };

    let hwnd = HWND(target as *mut _);
    unsafe {
        let _ = SetForegroundWindow(hwnd);
    }
    if !wait_for_foreground(target, Duration::from_secs(2)) {
        return Err("The new browser window could not receive keyboard focus".into());
    }
    if !is_foreground(target) || !is_target_window(target, target_pid, app_id)
        || !process_matches_executable(target_pid, executable) {
        return Err("Browser focus changed before the extension settings page could be opened".into());
    }
    send_navigation_input(extensions_url(app_id))?;
    Ok(())
}

#[cfg(windows)]
fn process_matches_executable(process_id: u32, executable: &Path) -> bool {
    use windows::Win32::{Foundation::CloseHandle, System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
        PROCESS_QUERY_LIMITED_INFORMATION,
    }};
    unsafe {
        let Ok(process) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, process_id) else { return false };
        let mut buffer = vec![0u16; 32_768];
        let mut length = buffer.len() as u32;
        let result = QueryFullProcessImageNameW(process, PROCESS_NAME_WIN32,
            windows::core::PWSTR(buffer.as_mut_ptr()), &mut length);
        let _ = CloseHandle(process);
        result.is_ok() && String::from_utf16_lossy(&buffer[..length as usize])
            .eq_ignore_ascii_case(&executable.to_string_lossy())
    }
}

#[cfg(windows)]
fn is_foreground(hwnd_value: isize) -> bool {
    use windows::Win32::UI::WindowsAndMessaging::GetForegroundWindow;
    unsafe { GetForegroundWindow().0 as isize == hwnd_value }
}

#[cfg(windows)]
fn wait_for_foreground(hwnd_value: isize, timeout: std::time::Duration) -> bool {
    let deadline = std::time::Instant::now() + timeout;
    while std::time::Instant::now() < deadline {
        if is_foreground(hwnd_value) { return true; }
        std::thread::sleep(std::time::Duration::from_millis(25));
    }
    is_foreground(hwnd_value)
}

#[cfg(windows)]
fn is_target_window(hwnd_value: isize, process_id: u32, app_id: &str) -> bool {
    crate::windows_capture::browser_window_identity(hwnd_value).is_some_and(|identity| {
        identity.process_id == process_id && identity.app_id.eq_ignore_ascii_case(app_id)
    })
}

#[cfg(windows)]
fn send_navigation_input(text: &str) -> Result<(), String> {
    use windows::Win32::UI::Input::KeyboardAndMouse::{SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, VIRTUAL_KEY};
    let mut inputs = Vec::with_capacity(text.encode_utf16().count() * 2 + 6);
    let key = |vk: u16, flags| INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: VIRTUAL_KEY(vk), wScan: 0, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
    };
    inputs.push(key(0x11, Default::default())); // Ctrl down
    inputs.push(key(0x4c, Default::default())); // L down
    inputs.push(key(0x4c, KEYEVENTF_KEYUP));
    inputs.push(key(0x11, KEYEVENTF_KEYUP)); // Ctrl up
    for code in text.encode_utf16() {
        for flags in [KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP] {
            inputs.push(INPUT {
                r#type: INPUT_KEYBOARD,
                Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: VIRTUAL_KEY(0), wScan: code, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
            });
        }
    }
    inputs.push(key(0x0d, Default::default())); // Enter down
    inputs.push(key(0x0d, KEYEVENTF_KEYUP));
    let sent = unsafe { SendInput(&inputs, std::mem::size_of::<INPUT>() as i32) };
    if sent as usize != inputs.len() {
        return Err("Windows did not accept the extension settings navigation".into());
    }
    Ok(())
}

fn matching_executable(path: &Path, app_id: &str) -> bool {
    path.is_absolute() && path.is_file() && path.file_name().and_then(|name| name.to_str())
        .is_some_and(|name| name.eq_ignore_ascii_case(app_id))
}

pub fn resolve_executable(app_id: &str, observed: Option<&str>) -> Option<PathBuf> {
    if !crate::browser_bridge::supported_browser(app_id) { return None; }
    if let Some(path) = observed.map(PathBuf::from).filter(|path| matching_executable(path, app_id)) {
        return Some(path);
    }
    #[cfg(windows)]
    {
        use std::{os::windows::process::CommandExt, process::Command};
        use windows::Win32::System::Threading::CREATE_NO_WINDOW;
        for hive in ["HKCU", "HKLM"] {
            for view in ["/reg:64", "/reg:32"] {
                let key = format!(r"{hive}\Software\Microsoft\Windows\CurrentVersion\App Paths\{app_id}");
                let Ok(output) = Command::new("reg.exe").args(["query", &key, "/ve", view])
                    .creation_flags(CREATE_NO_WINDOW.0).output() else { continue };
                if !output.status.success() { continue; }
                if let Some(value) = String::from_utf8_lossy(&output.stdout).lines()
                    .find_map(|line| line.split_once("REG_SZ").map(|(_, value)| value.trim().trim_matches('"').to_owned())) {
                    let path = PathBuf::from(value);
                    if matching_executable(&path, app_id) { return Some(path); }
                }
            }
        }
    }
    let relative = match app_id {
        "chrome.exe" => r"Google\Chrome\Application\chrome.exe",
        "msedge.exe" => r"Microsoft\Edge\Application\msedge.exe",
        "brave.exe" => r"BraveSoftware\Brave-Browser\Application\brave.exe",
        "vivaldi.exe" => r"Vivaldi\Application\vivaldi.exe",
        _ => return None,
    };
    ["LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)"].iter()
        .filter_map(std::env::var_os).map(|root| PathBuf::from(root).join(relative))
        .find(|path| matching_executable(path, app_id))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn closed_browser_can_be_launched_from_last_observed_path() {
        let dir = tempfile::tempdir().unwrap();
        let exe = dir.path().join("chrome.exe");
        std::fs::write(&exe, b"test").unwrap();
        assert_eq!(resolve_executable("chrome.exe", exe.to_str()), Some(exe.clone()));
        assert!(!matching_executable(&exe, "msedge.exe"));
        assert!(resolve_executable("powershell.exe", exe.to_str()).is_none());
    }
    #[test]
    fn uses_each_browsers_extensions_page() {
        assert_eq!(extensions_url("chrome.exe"), "chrome://extensions/");
        assert_eq!(extensions_url("msedge.exe"), "edge://extensions/");
        assert_eq!(extensions_url("brave.exe"), "brave://extensions/");
    }

    #[test]
    fn chooses_last_used_regular_profile_and_builds_safe_navigation_args() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("Default")).unwrap();
        std::fs::create_dir(dir.path().join("Profile 2")).unwrap();
        std::fs::write(
            dir.path().join("Local State"),
            br#"{"profile":{"last_used":"Profile 2","last_active_profiles":["Default"]}}"#,
        ).unwrap();
        assert_eq!(selected_profile(dir.path()), "Profile 2");
        assert_eq!(
            extensions_page_args(Some(dir.path())),
            ["--profile-directory=Profile 2", "--new-window", "about:blank"],
        );
    }

    #[test]
    fn guest_profile_falls_back_to_first_existing_regular_profile() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("Default")).unwrap();
        std::fs::write(
            dir.path().join("Local State"),
            br#"{"profile":{"last_used":"Guest Profile","last_active_profiles":["System Profile","Default"]}}"#,
        ).unwrap();
        assert_eq!(selected_profile(dir.path()), "Default");
    }

    #[test]
    fn malformed_or_unsafe_profile_selection_falls_back_to_default() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            dir.path().join("Local State"),
            br#"{"profile":{"last_used":"../outside","last_active_profiles":["C:\\\\outside"]}}"#,
        ).unwrap();
        assert_eq!(selected_profile(dir.path()), "Default");
        std::fs::write(dir.path().join("Local State"), b"not-json").unwrap();
        assert_eq!(selected_profile(dir.path()), "Default");
    }

    #[test]
    #[ignore = "opens the installed Chrome profile and navigates it to chrome://extensions/"]
    fn live_chrome_launch_opens_the_extensions_page() {
        let observed = std::env::var("FOCUSLOCK_TEST_CHROME_EXE").ok();
        let executable = resolve_executable("chrome.exe", observed.as_deref())
            .expect("Chrome must be installed for this manual integration check");
        launch_extensions_page(&executable, "chrome.exe").unwrap();
    }
}
