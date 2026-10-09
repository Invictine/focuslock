// FocusLock desktop native shell and local Windows activity tracker.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod account_protection;
mod auth;
mod background;
mod blocker;
mod browser_bridge;
mod browser_guard;
mod browser_launch;
mod browser_warning;
mod browser_window;
mod tracking;
mod uninstall_guard;
mod void_launcher;
mod windows_capture;

use tauri::Manager;
use tracking::TrackerRuntime;

fn main() {
    let context: tauri::Context<tauri::Wry> = tauri::generate_context!();
    if uninstall_guard::maybe_run_uninstall_check(&context.config().identifier) { return; }
    if browser_bridge::maybe_run_host() { return; }
    if background::maybe_run_watchdog() { return; }
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if !args.iter().any(|arg| arg == "--background") {
                background::show_main(app);
            }
        }))
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_store::Builder::new().build())
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            app.manage(uninstall_guard::UninstallGuardRuntime::new(
                data_dir.join("strict-uninstall-v1.json"),
            ));
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
            app.manage(account_protection::AccountProtectionRuntime::load(
                data_dir.join("account-protection-v1.json"),
            ).map_err(Box::<dyn std::error::Error>::from)?);
            app.manage(blocker::BlockerRuntime::new());
            app.manage(browser_warning::BrowserRepairRuntime::default());
            app.manage(void_launcher::VoidSessionState::default());
            app.manage(runtime);
            app.manage(background::BackgroundRuntime::default());
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
            tauri::WebviewWindowBuilder::from_config(app.handle(), main_window)?
                .visible(!std::env::args().any(|arg| arg == "--background"))
                .focused(!std::env::args().any(|arg| arg == "--background"))
                .build()?;
            background::install_tray(app.handle())?;
            app.state::<TrackerRuntime>().start(app.handle().clone());
            if let Err(error) = background::start_recovery(app.handle()) {
                eprintln!("FocusLock background recovery unavailable: {error}");
                app.state::<TrackerRuntime>().note_control_error(error);
            }
            Ok(())
        })
        .on_window_event(|window, event| match event {
            tauri::WindowEvent::CloseRequested { api, .. } => {
                if window.label() == browser_warning::WINDOW_LABEL {
                    api.prevent_close();
                    browser_warning::sync_window(window.app_handle(), None);
                    return;
                }
                if window.label() == "main" {
                    api.prevent_close();
                    background::hide_main(window);
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
                    if let Some(state) = window.app_handle().try_state::<blocker::BlockerRuntime>()
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
            _ => {}
        })
        .invoke_handler(tauri::generate_handler![
            uninstall_guard::sync_strict_uninstall_guard,
            auth::get_browser_auth_state,
            auth::get_browser_auth_token,
            auth::start_browser_sign_in,
            auth::sign_out_browser_auth,
            auth::sync_account_protection,
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
            void_launcher::start_void_launcher,
            void_launcher::update_void_launcher,
            void_launcher::stop_void_launcher,
            void_launcher::get_void_launcher_status,
            void_launcher::show_void_launcher,
        ])
        .build(context)
        .expect("error while running FocusLock desktop");
    app.run(|app_handle, event| {
        if let tauri::RunEvent::ExitRequested { api, .. } = &event {
            if !app_handle.state::<background::BackgroundRuntime>().exiting() {
                api.prevent_exit();
                return;
            }
        }
        if matches!(
            event,
            tauri::RunEvent::ExitRequested { .. } | tauri::RunEvent::Exit
        ) {
            if let Some(state) = app_handle.try_state::<blocker::BlockerRuntime>() {
                state.hide(app_handle);
            }
            if let Some(state) = app_handle.try_state::<void_launcher::VoidSessionState>() {
                let _ = void_launcher::stop_void_launcher(state);
            }
        }
    });
}
