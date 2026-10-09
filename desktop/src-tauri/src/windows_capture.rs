use serde::Serialize;
use url::Url;

#[derive(Clone, Debug)]
pub struct CapturedWindow {
    pub window_handle: isize,
    pub process_id: u32,
    pub app_id: String,
    pub app_name: String,
    pub executable_path: Option<String>,
    pub window_title: String,
    pub browser_domain: Option<String>,
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RunningApp {
    pub app_id: String,
    pub app_name: String,
    pub executable_path: Option<String>,
    pub window_title: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BrowserWindowIdentity {
    pub window_handle: isize,
    pub process_id: u32,
    pub app_id: String,
    pub app_name: String,
}

pub fn normalized_domain(value: &str) -> Option<String> {
    let candidate = value.trim();
    if candidate.is_empty() || candidate.chars().any(char::is_whitespace) {
        return None;
    }
    let with_scheme = if candidate.contains("://") {
        candidate.to_string()
    } else {
        format!("https://{candidate}")
    };
    let parsed = Url::parse(&with_scheme).ok()?;
    if !matches!(parsed.scheme(), "http" | "https") {
        return None;
    }
    let host = parsed
        .host_str()?
        .trim_end_matches('.')
        .to_ascii_lowercase();
    if !host.contains('.') && host != "localhost" {
        return None;
    }
    Some(host.strip_prefix("www.").unwrap_or(&host).to_string())
}

/// The value exposed by a browser's address bar is also the value being typed
/// before navigation is committed. Do not turn that transient edit into a
/// tracked domain; once the address bar loses keyboard focus, the committed
/// page URL is still captured and normal website enforcement remains intact.
fn committed_browser_url(value: &str, has_keyboard_focus: bool) -> Option<String> {
    if has_keyboard_focus {
        return None;
    }
    (!value.trim().is_empty()).then(|| value.to_string())
}

pub fn is_supported_browser(app_id: &str) -> bool {
    matches!(
        app_id.to_ascii_lowercase().as_str(),
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
fn friendly_app_name(app_id: &str) -> String {
    match app_id.to_ascii_lowercase().as_str() {
        "chrome.exe" => "Google Chrome".into(),
        "msedge.exe" => "Microsoft Edge".into(),
        "brave.exe" => "Brave".into(),
        "firefox.exe" => "Mozilla Firefox".into(),
        "vivaldi.exe" => "Vivaldi".into(),
        "opera.exe" => "Opera".into(),
        "opera_gx.exe" => "Opera GX".into(),
        "arc.exe" => "Arc".into(),
        other => other.strip_suffix(".exe").unwrap_or(other).to_string(),
    }
}

#[cfg(windows)]
mod platform {
    use super::*;
    use std::{cell::RefCell, collections::BTreeMap, path::Path};
    use windows::{
        core::{BOOL, PWSTR},
        Win32::{
            Foundation::{CloseHandle, HWND, LPARAM, RECT},
            System::{
                Com::{
                    CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
                },
                Threading::{
                    OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
                    PROCESS_QUERY_LIMITED_INFORMATION,
                },
            },
            UI::{
                Accessibility::{
                    CUIAutomation, IUIAutomation, IUIAutomationElement, IUIAutomationValuePattern,
                    TreeScope_Descendants, UIA_ControlTypePropertyId, UIA_EditControlTypeId,
                    UIA_ValuePatternId,
                },
                Input::KeyboardAndMouse::{
                    keybd_event, GetLastInputInfo, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP, LASTINPUTINFO,
                    VK_MENU,
                },
                WindowsAndMessaging::{
                    EnumWindows, GetForegroundWindow, GetWindowRect, GetWindowTextLengthW,
                    GetWindowTextW, GetWindowThreadProcessId, IsWindow, IsWindowVisible,
                    IsIconic, PostMessageW, SetForegroundWindow, SetWindowPos, ShowWindow, HWND_TOPMOST,
                    SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE, SWP_SHOWWINDOW, SW_MINIMIZE,
                    SW_SHOWNOACTIVATE, WM_CLOSE,
                },
            },
        },
    };

    pub fn capture_foreground(
        capture_browser_domains: bool,
    ) -> Result<Option<CapturedWindow>, String> {
        unsafe {
            let hwnd = GetForegroundWindow();
            if hwnd.0.is_null() {
                return Ok(None);
            }
            let title = window_title(hwnd);
            let path = process_path(hwnd);
            let app_id = path
                .as_deref()
                .and_then(|v| Path::new(v).file_name())
                .and_then(|v| v.to_str())
                .unwrap_or("unknown")
                .to_ascii_lowercase();
            let browser_url = if is_supported_browser(&app_id) {
                browser_url(hwnd).ok().flatten()
            } else {
                None
            };
            let browser_domain = if capture_browser_domains {
                browser_url.as_deref().and_then(normalized_domain)
            } else { None };
            let mut process_id = 0;
            GetWindowThreadProcessId(hwnd, Some(&mut process_id));
            Ok(Some(CapturedWindow {
                window_handle: hwnd.0 as isize,
                process_id,
                app_name: friendly_app_name(&app_id),
                app_id,
                executable_path: path,
                window_title: title,
                browser_domain,
            }))
        }
    }

    pub fn idle_millis() -> Result<u64, String> {
        unsafe {
            let mut info = LASTINPUTINFO {
                cbSize: std::mem::size_of::<LASTINPUTINFO>() as u32,
                dwTime: 0,
            };
            if !GetLastInputInfo(&mut info).as_bool() {
                return Err("GetLastInputInfo failed".into());
            }
            let now = windows::Win32::System::SystemInformation::GetTickCount();
            Ok(now.wrapping_sub(info.dwTime) as u64)
        }
    }

    pub fn minimize_foreground() -> Result<(), String> {
        unsafe {
            let hwnd = GetForegroundWindow();
            if hwnd.0.is_null() {
                return Err("No foreground window to minimize".into());
            }
            let _ = ShowWindow(hwnd, SW_MINIMIZE);
            Ok(())
        }
    }

    pub fn foreground_window() -> Option<isize> {
        unsafe {
            let hwnd = GetForegroundWindow();
            if hwnd.0.is_null() {
                None
            } else {
                Some(hwnd.0 as isize)
            }
        }
    }

    /// Recheck a pending HWND's PID and executable before retaining or closing
    /// it. This makes handle reuse harmless and prevents closing a replacement
    /// process that happens to receive the old numeric HWND.
    pub fn browser_window_identity(hwnd_value: isize) -> Option<BrowserWindowIdentity> {
        // Chrome menus, tab previews, tooltips and owned dialogs belong to the
        // browser process but cannot carry an extension window lease.
        if !crate::browser_window::is_browser_content_window(hwnd_value) {
            return None;
        }
        let hwnd = HWND(hwnd_value as *mut _);
        if hwnd.0.is_null() {
            return None;
        }
        unsafe {
            if !IsWindow(Some(hwnd)).as_bool() {
                return None;
            }
            let mut pid = 0_u32;
            GetWindowThreadProcessId(hwnd, Some(&mut pid));
            if pid == 0 {
                return None;
            }
            let path = process_path(hwnd)?;
            let app_id = Path::new(&path)
                .file_name()
                .and_then(|v| v.to_str())?
                .to_ascii_lowercase();
            crate::browser_guard::is_browser(&app_id).then(|| BrowserWindowIdentity {
                window_handle: hwnd_value,
                process_id: pid,
                app_name: friendly_app_name(&app_id),
                app_id,
            })
        }
    }

    /// Enumerate browser windows independently of the foreground window.
    /// Minimized windows are excluded because browser bridge discovery does
    /// not consider them actionable; this also avoids treating minimized
    /// windows as extension failures during initial discovery.
    pub fn get_browser_windows() -> Result<Vec<BrowserWindowIdentity>, String> {
        unsafe extern "system" fn callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
            unsafe {
                if !IsWindowVisible(hwnd).as_bool() || IsIconic(hwnd).as_bool() {
                    return BOOL(1);
                }
                let windows = &mut *(lparam.0 as *mut Vec<BrowserWindowIdentity>);
                if let Some(identity) = browser_window_identity(hwnd.0 as isize) {
                    windows.push(identity);
                }
                BOOL(1)
            }
        }

        let mut windows: Vec<BrowserWindowIdentity> = Vec::new();
        unsafe {
            EnumWindows(Some(callback), LPARAM(&mut windows as *mut _ as isize))
                .map_err(|error| format!("Could not enumerate browser windows: {error}"))?;
        }
        windows.sort_by_key(|window| window.window_handle);
        Ok(windows)
    }

    /// Request a normal close only after revalidating the current HWND, PID,
    /// and browser executable against the identity recorded by the guard.
    pub fn request_browser_window_close(
        hwnd_value: isize,
        expected_pid: u32,
        expected_app_id: &str,
    ) -> Result<(), String> {
        let current = browser_window_identity(hwnd_value)
            .ok_or_else(|| "Browser window no longer exists".to_string())?;
        if current.process_id != expected_pid
            || !current.app_id.eq_ignore_ascii_case(expected_app_id)
        {
            return Err("Browser window identity changed; close request cancelled".into());
        }
        let hwnd = HWND(hwnd_value as *mut _);
        unsafe {
            PostMessageW(Some(hwnd), WM_CLOSE, Default::default(), Default::default())
                .map_err(|error| format!("Could not request browser window close: {error}"))
        }
    }

    /// Physical screen rectangle (`left`, `top`, `right`, `bottom`) of a window.
    pub fn window_rect(hwnd: isize) -> Option<(i32, i32, i32, i32)> {
        let hwnd = HWND(hwnd as *mut _);
        if hwnd.0.is_null() {
            return None;
        }
        unsafe {
            let mut rect = RECT::default();
            GetWindowRect(hwnd, &mut rect).ok()?;
            Some((rect.left, rect.top, rect.right, rect.bottom))
        }
    }

    pub fn minimize_window(hwnd: isize) -> Result<(), String> {
        let hwnd = HWND(hwnd as *mut _);
        if hwnd.0.is_null() {
            return Err("No window to minimize".into());
        }
        unsafe {
            if !IsWindow(Some(hwnd)).as_bool() {
                return Err("Blocked window is no longer available".into());
            }
            let _ = ShowWindow(hwnd, SW_MINIMIZE);
            Ok(())
        }
    }

    pub fn show_nonactivating_topmost(hwnd_value: isize) -> Result<(), String> {
        let hwnd = HWND(hwnd_value as *mut _);
        if hwnd.0.is_null() {
            return Err("Warning window is unavailable".into());
        }
        unsafe {
            if !IsWindow(Some(hwnd)).as_bool() {
                return Err("Warning window no longer exists".into());
            }
            let _ = ShowWindow(hwnd, SW_SHOWNOACTIVATE);
            SetWindowPos(
                hwnd,
                Some(HWND_TOPMOST),
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW | SWP_NOACTIVATE,
            )
            .map_err(|error| format!("Could not show browser repair notice: {error}"))
        }
    }

    /// Best-effort foreground grab for the blocker window. Windows refuses
    /// `SetForegroundWindow` from a background process unless it recently
    /// received input, so an ALT tap is used to lift that restriction.
    pub fn focus_window(hwnd: isize) {
        let hwnd = HWND(hwnd as *mut _);
        if hwnd.0.is_null() {
            return;
        }
        unsafe {
            keybd_event(VK_MENU.0 as u8, 0, KEYBD_EVENT_FLAGS(0), 0);
            keybd_event(VK_MENU.0 as u8, 0, KEYEVENTF_KEYUP, 0);
            let _ = SetForegroundWindow(hwnd);
            let _ = SetWindowPos(
                hwnd,
                Some(HWND_TOPMOST),
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW,
            );
        }
    }

    pub fn get_running_windows() -> Result<Vec<RunningApp>, String> {
        unsafe extern "system" fn callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
            unsafe {
                if !IsWindowVisible(hwnd).as_bool() || GetWindowTextLengthW(hwnd) <= 0 {
                    return BOOL(1);
                }
                let apps = &mut *(lparam.0 as *mut Vec<RunningApp>);
                let title = window_title(hwnd);
                if let Some(path) = process_path(hwnd) {
                    let app_id = Path::new(&path)
                        .file_name()
                        .and_then(|v| v.to_str())
                        .unwrap_or("unknown")
                        .to_ascii_lowercase();
                    apps.push(RunningApp {
                        app_name: friendly_app_name(&app_id),
                        app_id,
                        executable_path: Some(path),
                        window_title: title,
                    });
                }
                BOOL(1)
            }
        }
        let mut apps: Vec<RunningApp> = Vec::new();
        unsafe {
            EnumWindows(Some(callback), LPARAM(&mut apps as *mut _ as isize))
                .map_err(|e| format!("Could not enumerate windows: {e}"))?;
        }
        let mut unique = BTreeMap::new();
        for app in apps {
            unique.entry(app.app_id.clone()).or_insert(app);
        }
        Ok(unique.into_values().collect())
    }

    unsafe fn window_title(hwnd: HWND) -> String {
        unsafe {
            let length = GetWindowTextLengthW(hwnd);
            if length <= 0 {
                return String::new();
            }
            let mut buffer = vec![0_u16; length as usize + 1];
            let copied = GetWindowTextW(hwnd, &mut buffer);
            String::from_utf16_lossy(&buffer[..copied.max(0) as usize])
        }
    }
    unsafe fn process_path(hwnd: HWND) -> Option<String> {
        unsafe {
            let mut pid = 0_u32;
            GetWindowThreadProcessId(hwnd, Some(&mut pid));
            if pid == 0 {
                return None;
            }
            let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
            let mut buffer = vec![0_u16; 32_768];
            let mut length = buffer.len() as u32;
            let result = QueryFullProcessImageNameW(
                process,
                PROCESS_NAME_WIN32,
                PWSTR(buffer.as_mut_ptr()),
                &mut length,
            );
            let _ = CloseHandle(process);
            result.ok()?;
            Some(String::from_utf16_lossy(&buffer[..length as usize]))
        }
    }
    // Building the UIA client (CoInitializeEx + CoCreateInstance CUIAutomation)
    // costs more than the actual tree walk. `capture_foreground` runs on one
    // dedicated tracker thread, so a thread-local cache is safe and avoids
    // re-initialising COM on every 1s sample.
    thread_local! {
        static CACHED_AUTOMATION: RefCell<Option<IUIAutomation>> = RefCell::new(None);
    }

    unsafe fn cached_automation() -> windows::core::Result<IUIAutomation> {
        unsafe {
            if let Some(automation) = CACHED_AUTOMATION.with(|cell| cell.borrow().clone()) {
                return Ok(automation);
            }
            CoInitializeEx(None, COINIT_MULTITHREADED).ok()?;
            let automation: IUIAutomation =
                CoCreateInstance(&CUIAutomation, None, CLSCTX_INPROC_SERVER)?;
            CACHED_AUTOMATION.with(|cell| {
                *cell.borrow_mut() = Some(automation.clone());
            });
            Ok(automation)
        }
    }

    unsafe fn browser_url(hwnd: HWND) -> windows::core::Result<Option<String>> {
        unsafe {
            let automation = cached_automation()?;
            let root: IUIAutomationElement = automation.ElementFromHandle(hwnd)?;
            let condition = automation.CreatePropertyCondition(
                UIA_ControlTypePropertyId,
                &windows::Win32::System::Variant::VARIANT::from(UIA_EditControlTypeId.0),
            )?;
            let elements = root.FindAll(TreeScope_Descendants, &condition)?;
            for index in 0..elements.Length()? {
                let element = elements.GetElement(index)?;
                let name = element
                    .CurrentName()
                    .map(|v| v.to_string())
                    .unwrap_or_default()
                    .to_ascii_lowercase();
                let automation_id = element
                    .CurrentAutomationId()
                    .map(|v| v.to_string())
                    .unwrap_or_default()
                    .to_ascii_lowercase();
                // Only address-bar-like elements can yield a tracked domain, so
                // bail before paying for the (expensive) value-pattern fetch on
                // every other edit control. Same observable result as before —
                // non-address elements never contributed a domain.
                let likely = name.contains("address and search")
                    || name.contains("search or enter address")
                    || name.contains("address bar")
                    || automation_id.contains("urlbar")
                    || automation_id.contains("omnibox");
                if !likely {
                    continue;
                }
                // A focused address-like edit control contains an omnibox
                // candidate, not necessarily the page the browser is showing.
                // This also protects a React/Tauri picker when Chromium exposes
                // its input in the same UI Automation branch. The next sample
                // after Enter sees the same address bar unfocused and enforces
                // a genuinely navigated blocked domain.
                let has_keyboard_focus = element
                    .CurrentHasKeyboardFocus()
                    .map(|focused| focused.as_bool())
                    .unwrap_or(false);
                let pattern: IUIAutomationValuePattern =
                    match element.GetCurrentPatternAs(UIA_ValuePatternId) {
                        Ok(v) => v,
                        Err(_) => continue,
                    };
                let value = pattern
                    .CurrentValue()
                    .map(|v| v.to_string())
                    .unwrap_or_default();
                if let Some(url) = committed_browser_url(&value, has_keyboard_focus) {
                    return Ok(Some(url));
                }
            }
            Ok(None)
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use super::*;
    pub fn capture_foreground(_: bool) -> Result<Option<CapturedWindow>, String> {
        Ok(None)
    }
    pub fn idle_millis() -> Result<u64, String> { Ok(0) }
    pub fn minimize_foreground() -> Result<(), String> {
        Ok(())
    }
    pub fn foreground_window() -> Option<isize> {
        None
    }
    pub fn browser_window_identity(_: isize) -> Option<BrowserWindowIdentity> {
        None
    }
    pub fn get_browser_windows() -> Result<Vec<BrowserWindowIdentity>, String> {
        Ok(Vec::new())
    }
    pub fn request_browser_window_close(_: isize, _: u32, _: &str) -> Result<(), String> {
        Err("Browser window close is only available on Windows".into())
    }
    pub fn window_rect(_: isize) -> Option<(i32, i32, i32, i32)> {
        None
    }
    pub fn minimize_window(_: isize) -> Result<(), String> {
        Ok(())
    }
    pub fn show_nonactivating_topmost(_: isize) -> Result<(), String> {
        Ok(())
    }
    pub fn focus_window(_: isize) {}
    pub fn get_running_windows() -> Result<Vec<RunningApp>, String> {
        Ok(Vec::new())
    }
}
pub use platform::{
    browser_window_identity, capture_foreground, focus_window, foreground_window,
    get_browser_windows, get_running_windows, idle_millis, minimize_foreground, minimize_window,
    request_browser_window_close, show_nonactivating_topmost, window_rect,
};

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn normalizes_without_paths() {
        assert_eq!(
            normalized_domain("https://www.YouTube.com/watch?v=secret"),
            Some("youtube.com".into())
        );
        assert_eq!(
            normalized_domain("reddit.com/r/rust"),
            Some("reddit.com".into())
        );
    }
    #[test]
    fn rejects_search_and_files() {
        assert_eq!(normalized_domain("cats doing things"), None);
        assert_eq!(normalized_domain("file:///C:/private.txt"), None);
    }
    #[test]
    fn ignores_focused_address_bar_candidates_but_tracks_committed_values() {
        assert_eq!(committed_browser_url("x.com", true), None);
        assert_eq!(committed_browser_url("x.com", false).as_deref().and_then(normalized_domain), Some("x.com".into()));
        assert_eq!(committed_browser_url("chrome://extensions/", true), None);
        assert_eq!(committed_browser_url("chrome://extensions/", false), Some("chrome://extensions/".into()));
    }
    #[test]
    fn recognizes_browsers() {
        assert!(is_supported_browser("MSEDGE.EXE"));
        assert!(is_supported_browser("firefox.exe"));
        assert!(!is_supported_browser("notepad.exe"));
    }
}
