use tauri::{
    plugin::{Builder, TauriPlugin},
    Runtime,
};
#[cfg(target_os = "android")]
pub struct AudioOutput<R: Runtime>(pub tauri::plugin::PluginHandle<R>);
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("audio-output")
        .setup(|_app, _api| {
            #[cfg(target_os = "android")]
            {
                use tauri::Manager;
                let handle =
                    _api.register_android_plugin("app.getaudiobridge.output", "AudioOutputPlugin")?;
                _app.manage(AudioOutput(handle));
            }
            Ok(())
        })
        .build()
}
