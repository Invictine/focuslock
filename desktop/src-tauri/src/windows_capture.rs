use serde::Serialize;
use url::Url;

#[derive(Clone, Debug)]
pub struct CapturedWindow {
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
            Foundation::{CloseHandle, HWND, LPARAM},
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
                Input::KeyboardAndMouse::{GetLastInputInfo, LASTINPUTINFO},
                WindowsAndMessaging::{
                    EnumWindows, GetForegroundWindow, GetWindowTextLengthW, GetWindowTextW,
                    GetWindowThreadProcessId, IsWindowVisible, ShowWindow, SW_MINIMIZE,
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
            let browser_domain = if capture_browser_domains && is_supported_browser(&app_id) {
                browser_domain(hwnd).ok().flatten()
            } else {
                None
            };
            Ok(Some(CapturedWindow {
                app_name: friendly_app_name(&app_id),
                app_id,
                executable_path: path,
                window_title: title,
                browser_domain,
            }))
        }
    }

    pub fn idle_seconds() -> Result<u64, String> {
        unsafe {
            let mut info = LASTINPUTINFO {
                cbSize: std::mem::size_of::<LASTINPUTINFO>() as u32,
                dwTime: 0,
            };
            if !GetLastInputInfo(&mut info).as_bool() {
                return Err("GetLastInputInfo failed".into());
            }
            let now = windows::Win32::System::SystemInformation::GetTickCount();
            Ok(now.wrapping_sub(info.dwTime) as u64 / 1_000)
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

    unsafe fn browser_domain(hwnd: HWND) -> windows::core::Result<Option<String>> {
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
                let pattern: IUIAutomationValuePattern =
                    match element.GetCurrentPatternAs(UIA_ValuePatternId) {
                        Ok(v) => v,
                        Err(_) => continue,
                    };
                let value = pattern
                    .CurrentValue()
                    .map(|v| v.to_string())
                    .unwrap_or_default();
                if let Some(domain) = normalized_domain(&value) {
                    return Ok(Some(domain));
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
    pub fn idle_seconds() -> Result<u64, String> {
        Ok(0)
    }
    pub fn minimize_foreground() -> Result<(), String> {
        Ok(())
    }
    pub fn get_running_windows() -> Result<Vec<RunningApp>, String> {
        Ok(Vec::new())
    }
}
pub use platform::{capture_foreground, get_running_windows, idle_seconds, minimize_foreground};

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
    fn recognizes_browsers() {
        assert!(is_supported_browser("MSEDGE.EXE"));
        assert!(is_supported_browser("firefox.exe"));
        assert!(!is_supported_browser("notepad.exe"));
    }
}
