// FocusLock desktop native shell and local Windows activity tracker.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod auth;
mod blocker;
mod browser_bridge;
mod browser_guard;
mod browser_launch;
mod browser_warning;
mod tracking;
mod windows_capture;

use tauri::Manager;
use tracking::TrackerRuntime;

fn main() {
    if browser_bridge::maybe_run_host() { return; }
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_store::Builder::new().build())
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            if let Err(error) = browser_bridge::register_host(&data_dir) {
                eprintln!("Could not register browser extension connection: {error}");
            }
            let runtime = TrackerRuntime::load(data_dir.join("activity-v1.json"))
                .map_err(Box::<dyn std::error::Error>::from)?;
            // Every webview can invoke commands as soon as it loads. Register
            // their state before creating either the main or helper windows.
            app.manage(auth::BrowserAuthRuntime::load(
                data_dir.join("auth-session.json"),
            ));
            app.manage(blocker::BlockerRuntime::new());
            app.manage(browser_warning::BrowserRepairRuntime::default());
            app.manage(runtime);
            // The blocker window exists for the whole app lifetime, hidden.
            // If it cannot be created the tracker falls back to minimizing.
            if let Err(error) = blocker::build_blocker_window(app.handle()) {
                eprintln!("Could not create the blocker window: {error}");
            }
            if let Err(error) = browser_warning::build_browser_warning_window(app.handle()) {
                eprintln!("Could not create the browser repair notice: {error}");
            }
            // Tauri creates automatic windows before this setup hook, so the
            // configured main window has create:false and is built here.
            let main_window = app.config().app.windows.iter()
                .find(|window| window.label == "main")
                .ok_or("Missing main window configuration")?;
            tauri::WebviewWindowBuilder::from_config(app.handle(), main_window)?.build()?;
            app.state::<TrackerRuntime>().start(app.handle().clone());
            Ok(())
        })
        .on_window_event(|window, event| match event {
            tauri::WindowEvent::CloseRequested { api, .. } => {
                if window.label() == browser_warning::WINDOW_LABEL {
                    api.prevent_close();
                    browser_warning::sync_window(window.app_handle(), None);
                    return;
                }
                if window.label() == "main" && window.app_handle().try_state::<TrackerRuntime>()
                    .is_some_and(|state| state.browser_protection_active()) {
                    api.prevent_close();
                    let _ = window.minimize();
                    return;
                }
                if window.label() == blocker::BLOCKER_LABEL {
                    // The blocker is hidden, never destroyed, so the tracker can
                    // show it again on the next block. A permanent overlay must
                    // not be taken down at all — Alt+F4 and programmatic closes
                    // are refused here (belt-and-braces on top of
                    // `closable(false)`), otherwise the user could leave the
                    // block by closing the window.
                    api.prevent_close();
                    if let Some(state) = window
                        .app_handle()
                        .try_state::<blocker::BlockerRuntime>()
                    {
                        if state.is_permanent_active() {
                            return;
                        }
                    }
                    let _ = window.hide();
                }
            }
            tauri::WindowEvent::Destroyed if window.label() == blocker::BLOCKER_LABEL => {
                if let Some(state) = window.app_handle().try_state::<blocker::BlockerRuntime>() {
                    state.note_window_destroyed();
                }
            }
            tauri::WindowEvent::Destroyed if window.label() == "main" => {
                // The hidden blocker keeps the event loop alive, so closing the
                // main window still has to quit FocusLock.
                window.app_handle().exit(0);
            }
            _ => {}
        })
        .invoke_handler(tauri::generate_handler![
            auth::get_browser_auth_state,
            auth::get_browser_auth_token,
            auth::start_browser_sign_in,
            auth::sign_out_browser_auth,
            tracking::get_device_identity,
            tracking::get_tracking_snapshot,
            tracking::get_tracker_status,
            tracking::get_running_apps,
            tracking::set_tracker_config,
            tracking::set_browser_protection_policy,
            tracking::set_browser_protection_enabled,
            tracking::open_browser_extension_settings,
            browser_warning::get_browser_repair_state,
            tracking::set_blocked_targets,
            tracking::get_permanent_targets,
            tracking::add_permanent_targets,
            tracking::start_tracking,
            tracking::stop_tracking,
            tracking::clear_tracking_data,
            blocker::get_blocker_state,
            blocker::blocker_action,
        ])
        .build(tauri::generate_context!())
        .expect("error while running FocusLock desktop");
    app.run(|app_handle, event| {
        if matches!(
            event,
            tauri::RunEvent::ExitRequested { .. } | tauri::RunEvent::Exit
        ) {
            if let Some(state) = app_handle.try_state::<blocker::BlockerRuntime>() {
                state.hide(app_handle);
            }
        }
    });
}
