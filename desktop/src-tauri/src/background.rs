//! User-visible background protection and recovery. This is not a privileged
//! service: Windows administrators retain control of their machine.

use std::sync::atomic::{AtomicBool, Ordering};
use tauri::{AppHandle, Manager};

#[cfg(windows)]
#[path = "background_recovery.rs"]
mod recovery;

#[derive(Default)]
pub struct BackgroundRuntime {
    exiting: AtomicBool,
}

impl BackgroundRuntime {
    pub fn exiting(&self) -> bool {
        self.exiting.load(Ordering::SeqCst)
    }
}

pub fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

pub fn hide_main(window: &tauri::Window) {
    let _ = window.hide();
    // Shell restores can bypass Tauri's cached visibility. Always hide the
    // actual HWND too so closing a reopened window reliably returns to tray.
    #[cfg(windows)]
    if let Ok(hwnd) = window.hwnd() {
        unsafe {
            let _ = windows::Win32::UI::WindowsAndMessaging::ShowWindow(
                windows::Win32::Foundation::HWND(hwnd.0),
                windows::Win32::UI::WindowsAndMessaging::SW_HIDE,
            );
        }
    }
}

pub fn install_tray(app: &AppHandle) -> tauri::Result<()> {
    use tauri::{
        menu::{Menu, MenuItem},
        tray::{TrayIconBuilder, TrayIconEvent},
    };
    let open = MenuItem::with_id(app, "open", "Open FocusLock", true, None::<&str>)?;
    let quit = MenuItem::with_id(
        app,
        "quit",
        "Quit when protection is off",
        true,
        None::<&str>,
    )?;
    let menu = Menu::with_items(app, &[&open, &quit])?;
    let mut tray = TrayIconBuilder::with_id("focuslock")
        .tooltip("FocusLock · protection continues in the background")
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_tray_icon_event(|tray, event| {
            if matches!(event, TrayIconEvent::DoubleClick { .. }) {
                show_main(tray.app_handle());
            }
        })
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "quit" => {
                // The same native gate protects the Pause button and quitting.
                if let Err(error) = app
                    .state::<crate::tracking::TrackerRuntime>()
                    .ensure_pause_allowed()
                {
                    show_main(app);
                    // Native menu selection otherwise looks like an inert Quit.
                    // The existing tracker error surface is also visible in Settings.
                    app.state::<crate::tracking::TrackerRuntime>()
                        .note_control_error(error.clone());
                    return;
                }
                if let Err(error) = disarm_watchdog(app) {
                    eprintln!("Could not stop background recovery: {error}");
                    return;
                }
                app.state::<BackgroundRuntime>()
                    .exiting
                    .store(true, Ordering::SeqCst);
                app.exit(0);
            }
            _ => {}
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

fn stop_path(data_dir: &std::path::Path, pid: u32) -> std::path::PathBuf {
    data_dir.join(format!("recovery-{pid}.stop"))
}

fn disarm_watchdog(app: &AppHandle) -> Result<(), String> {
    // Development shells have no watchdog and shouldn't leave marker files.
    if cfg!(debug_assertions) {
        return Ok(());
    }
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    std::fs::write(stop_path(&dir, std::process::id()), b"intentional quit")
        .map_err(|e| e.to_string())?;
    #[cfg(windows)]
    if let Err(error) = recovery::disarm(&dir) {
        let _ = std::fs::remove_file(stop_path(&dir, std::process::id()));
        return Err(error);
    }
    Ok(())
}

#[cfg(windows)]
pub fn start_recovery(app: &AppHandle) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    if cfg!(debug_assertions) {
        return Ok(());
    }
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    let _ = std::fs::remove_file(stop_path(&dir, std::process::id()));
    let args: Vec<_> = std::env::args().collect();
    let attempt = args
        .iter()
        .position(|arg| arg == "--recovery-attempt")
        .and_then(|i| args.get(i + 1))
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(0);
    let installed = !exe.components().any(|part| part.as_os_str() == "target");
    let test_task = args.iter().any(|arg| arg == "--recovery-test-task")
        && app
            .config()
            .identifier
            .starts_with("com.focuslock.browserqa.");
    let scheduled_result = if installed || test_task {
        Some(recovery::start(&exe, &dir, attempt))
    } else {
        None
    };
    // Keep crash-only recovery as a fallback if Task Scheduler is unavailable.
    // The error is surfaced in Settings; fallback is never reported as full
    // process-tree protection.
    if !matches!(scheduled_result, Some(Ok(()))) {
        std::process::Command::new(&exe)
            .arg("--focuslock-watchdog")
            .arg(std::process::id().to_string())
            .arg(&dir)
            .arg(attempt.to_string())
            .creation_flags(0x0800_0000)
            .spawn()
            .map_err(|e| format!("Could not start FocusLock recovery: {e}"))?;
    }
    // Only installed releases register at sign-in; running build output for QA
    // must not replace an installed app's startup entry.
    if installed && !test_task {
        let command = format!("\"{}\" --background", exe.display());
        let result = std::process::Command::new("reg.exe")
            .args([
                "add",
                r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                "/v",
                "FocusLock",
                "/t",
                "REG_SZ",
                "/d",
                &command,
                "/f",
            ])
            .creation_flags(0x0800_0000)
            .output()
            .map_err(|e| e.to_string())?;
        if !result.status.success() {
            return Err("Could not register FocusLock at Windows sign-in".into());
        }
    }
    if let Some(result) = scheduled_result {
        result?;
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn start_recovery(_app: &AppHandle) -> Result<(), String> {
    Ok(())
}

/// Native messaging hosts return before this path: they never create a tray,
/// register startup, or take ownership of the desktop instance.
#[cfg(windows)]
pub fn maybe_run_watchdog() -> bool {
    if recovery::maybe_run() {
        return true;
    }
    use std::{
        os::windows::process::CommandExt,
        path::PathBuf,
        time::{Duration, Instant},
    };
    use windows::Win32::{
        Foundation::{CloseHandle, WAIT_OBJECT_0, WAIT_TIMEOUT},
        System::Threading::{OpenProcess, WaitForSingleObject, PROCESS_SYNCHRONIZE},
    };
    let args: Vec<_> = std::env::args_os().collect();
    if args.get(1).is_none_or(|arg| arg != "--focuslock-watchdog") {
        return false;
    }
    let Some(pid) = args
        .get(2)
        .and_then(|s| s.to_str())
        .and_then(|s| s.parse::<u32>().ok())
        .filter(|p| *p > 0)
    else {
        return true;
    };
    let Some(dir) = args.get(3).map(PathBuf::from) else {
        return true;
    };
    let attempt = args
        .get(4)
        .and_then(|s| s.to_str())
        .and_then(|s| s.parse::<u32>().ok())
        .unwrap_or(0);
    let started = Instant::now();
    let Ok(exe) = std::env::current_exe() else {
        return true;
    };
    unsafe {
        // Opening a handle pins the original process identity even if its PID
        // is later reused. Hold it until exit rather than polling process names.
        let Ok(parent) = OpenProcess(PROCESS_SYNCHRONIZE, false, pid) else {
            return true;
        };
        loop {
            if stop_path(&dir, pid).exists() {
                let _ = std::fs::remove_file(stop_path(&dir, pid));
                let _ = CloseHandle(parent);
                return true;
            }
            let result = WaitForSingleObject(parent, 1_000);
            if result == WAIT_OBJECT_0 {
                break;
            }
            if result != WAIT_TIMEOUT {
                let _ = CloseHandle(parent);
                return true;
            }
        }
        let _ = CloseHandle(parent);
    }
    // Give an installer or OS shutdown time to complete; the stop marker is
    // checked again after exit so deliberate Quit never resurrects the app.
    let attempt = if started.elapsed() >= Duration::from_secs(60) {
        0
    } else {
        attempt.saturating_add(1)
    };
    std::thread::sleep(Duration::from_secs(if attempt > 5 { 30 } else { 2 }));
    if stop_path(&dir, pid).exists() {
        let _ = std::fs::remove_file(stop_path(&dir, pid));
        return true;
    }
    unsafe {
        use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_SHUTTINGDOWN};
        if GetSystemMetrics(SM_SHUTTINGDOWN) != 0 {
            return true;
        }
    }
    // Let a late scheduled supervisor take ownership while the app is alive.
    // The fallback claims recovery only once its parent has died, so a slow
    // Windows task launch cannot be starved by the fallback's lock.
    let Ok(_recovery_lock) = recovery::claim_lock(&dir) else {
        return true;
    };
    // A new desktop instance creates its own recovery companion. Back off on
    // launch failure rather than running a busy crash loop.
    for _ in 0..3 {
        if std::process::Command::new(&exe)
            .arg("--background")
            .arg("--recovery-attempt")
            .arg(attempt.to_string())
            .creation_flags(0x0800_0000)
            .spawn()
            .is_ok()
        {
            break;
        }
        std::thread::sleep(Duration::from_secs(5));
    }
    true
}

#[cfg(not(windows))]
pub fn maybe_run_watchdog() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn markers_are_scoped_to_one_process_and_deliberate_exit_is_explicit() {
        let dir = std::path::Path::new("test-data");
        assert_ne!(stop_path(dir, 10), stop_path(dir, 11));
        let state = BackgroundRuntime::default();
        assert!(!state.exiting());
        state.exiting.store(true, Ordering::SeqCst);
        assert!(state.exiting());
    }
}
