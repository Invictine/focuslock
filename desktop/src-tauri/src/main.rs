// FocusLock desktop shell. Clerk auth lives in the WebView (@clerk/clerk-react);
// Rust side only provides persistence (store) + http for future native flows.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_store::Builder::new().build())
        .run(tauri::generate_context!())
        .expect("error while running FocusLock desktop");
}
