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
}
