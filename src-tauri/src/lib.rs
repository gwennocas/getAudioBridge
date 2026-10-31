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
        .setup(|_app| {
            #[cfg(target_os = "android")]
            {
                let _ = android::APP.set(_app.handle().clone());
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::platform,
            commands::devices,
            commands::addresses,
            commands::start,
            commands::stop,
            commands::connect,
            commands::disconnect,
            commands::set_buffer,
            commands::set_output,
            commands::status
        ])
        .build(tauri::generate_context!())
        .expect("Falha ao iniciar getAudioBridge")
        .run(|_app, _event| {
            #[cfg(target_os = "android")]
            if let tauri::RunEvent::ExitRequested {
                api, code: None, ..
            } = _event
            {
                // Activities can be recreated while this process and its media service live.
                // The idle loop holds no audio/power locks after an explicit disconnect.
                api.prevent_exit();
            }
        });
}
