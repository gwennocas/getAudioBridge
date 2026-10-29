#[cfg(target_os = "android")]
mod android;
mod commands;
#[cfg(windows)]
mod windows;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_audio_output::init())
        .manage(commands::AppState::default())
        .invoke_handler(tauri::generate_handler![
            commands::platform,
            commands::devices,
            commands::addresses,
            commands::start,
            commands::stop,
            commands::connect,
            commands::disconnect,
            commands::set_buffer,
            commands::status
        ])
        .run(tauri::generate_context!())
        .expect("Falha ao iniciar getAudioBridge");
}
