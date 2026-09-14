// FocusLock desktop native shell and local Windows activity tracker.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod auth;
mod tracking;
mod windows_capture;

use tracking::TrackerRuntime;

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_store::Builder::new().build())
        .setup(|app| {
            use tauri::Manager;
            let data_dir = app.path().app_data_dir()?;
            let runtime = TrackerRuntime::load(data_dir.join("activity-v1.json"))
                .map_err(Box::<dyn std::error::Error>::from)?;
            runtime.start(app.handle().clone());
            app.manage(runtime);
            app.manage(auth::BrowserAuthRuntime::load(data_dir.join("auth-session.json")));
            Ok(())
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
            tracking::set_blocked_targets,
            tracking::start_tracking,
            tracking::stop_tracking,
            tracking::clear_tracking_data,
        ])
        .run(tauri::generate_context!())
        .expect("error while running FocusLock desktop");
}
