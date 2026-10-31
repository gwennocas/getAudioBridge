use bridge_core::{receiver::Receiver, Stats};
use serde::Serialize;
use std::sync::Mutex;
use tauri::{AppHandle, State};

#[derive(Default)]
pub struct AppState {
    #[cfg(windows)]
    pub sender: Mutex<Option<crate::windows::Sender>>,
}
pub static RECEIVER: Mutex<Option<Receiver>> = Mutex::new(None);

#[tauri::command]
pub fn platform() -> &'static str {
    if cfg!(target_os = "android") {
        "android"
    } else {
        "windows"
    }
}
#[tauri::command]
pub async fn devices() -> Result<serde_json::Value, String> {
    #[cfg(windows)]
    {
        return serde_json::to_value(crate::windows::devices()?).map_err(|e| e.to_string());
    }
    #[cfg(not(windows))]
    {
        Ok(serde_json::json!([]))
    }
}
#[tauri::command]
pub fn addresses() -> Vec<String> {
    #[cfg(windows)]
    {
        return local_ip_address::list_afinet_netifas()
            .unwrap_or_default()
            .into_iter()
            .filter_map(|(_, ip)| {
                if ip.is_ipv4() && !ip.is_loopback() {
                    Some(ip.to_string())
                } else {
                    None
                }
            })
            .collect();
    }
    #[cfg(not(windows))]
    {
        vec![]
    }
}
#[tauri::command]
pub async fn start(
    state: State<'_, AppState>,
    device_id: String,
    bind_ip: String,
    port: u16,
) -> Result<(), String> {
    #[cfg(windows)]
    {
        let mut guard = state.sender.lock().map_err(|e| e.to_string())?;
        if guard.is_some() {
            return Err("Pare a transmissão antes de iniciar novamente".into());
        }
        *guard = Some(crate::windows::Sender::start(device_id, bind_ip, port)?);
        return Ok(());
    }
    #[cfg(not(windows))]
    {
        let _ = (state, device_id, bind_ip, port);
        Err("Disponível no Windows".into())
    }
}
#[tauri::command]
pub async fn stop(state: State<'_, AppState>) -> Result<(), String> {
    #[cfg(windows)]
    {
        state.sender.lock().map_err(|e| e.to_string())?.take();
    }
    let _ = state;
    Ok(())
}

#[cfg(target_os = "android")]
fn output(app: &AppHandle, command: &str) -> Result<serde_json::Value, String> {
    output_payload(app, command, ())
}
#[cfg(target_os = "android")]
fn output_payload(
    app: &AppHandle,
    command: &str,
    payload: impl serde::Serialize,
) -> Result<serde_json::Value, String> {
    use tauri::Manager;
    app.state::<tauri_plugin_audio_output::AudioOutput<tauri::Wry>>()
        .0
        .run_mobile_plugin(command, payload)
        .map_err(|e| e.to_string())
}
#[tauri::command]
pub async fn connect(app: AppHandle, ip: String, port: u16, buffer_ms: u32) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        output_payload(
            &app,
            "start",
            serde_json::json!({"ip":ip,"port":port,"bufferMs":buffer_ms.clamp(20,120)}),
        )?;
        Ok(())
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (app, ip, port, buffer_ms);
        Err("Conecte pelo aplicativo Android".into())
    }
}
#[tauri::command]
pub async fn disconnect(app: AppHandle) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        output(&app, "stop")?;
    }
    let _ = app;
    RECEIVER.lock().map_err(|e| e.to_string())?.take();
    Ok(())
}
#[tauri::command]
pub async fn set_buffer(app: AppHandle, buffer_ms: u32) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        output_payload(
            &app,
            "setBuffer",
            serde_json::json!({"bufferMs":buffer_ms.clamp(20,120)}),
        )?;
        return Ok(());
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        if let Some(r) = RECEIVER.lock().map_err(|e| e.to_string())?.as_ref() {
            r.buffer.lock().unwrap().set_target(buffer_ms);
        }
        Ok(())
    }
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    stream: Stats,
    output: serde_json::Value,
}
#[tauri::command]
pub async fn set_output(app: AppHandle, output: String) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        output_payload(&app, "setOutput", serde_json::json!({"output":output}))?;
        Ok(())
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (app, output);
        Err("Selecione a saída no aplicativo Android".into())
    }
}
#[tauri::command]
pub async fn status(app: AppHandle, state: State<'_, AppState>) -> Result<Status, String> {
    #[cfg(windows)]
    {
        let _ = app;
        let s = state.sender.lock().map_err(|e| e.to_string())?;
        return Ok(Status {
            stream: s
                .as_ref()
                .map(|s| s.stats.lock().unwrap().clone())
                .unwrap_or(Stats {
                    state: "stopped".into(),
                    ..Default::default()
                }),
            output: serde_json::Value::Null,
        });
    }
    #[cfg(not(windows))]
    {
        let _ = state;
        let stream = RECEIVER
            .lock()
            .map_err(|e| e.to_string())?
            .as_ref()
            .map(|r| r.buffer.lock().unwrap().stats.clone())
            .unwrap_or(Stats {
                state: "disconnected".into(),
                ..Default::default()
            });
        #[cfg(target_os = "android")]
        let native = output(&app, "status")?;
        #[cfg(not(target_os = "android"))]
        let native = {
            let _ = app;
            serde_json::Value::Null
        };
        Ok(Status {
            stream,
            output: native,
        })
    }
}
