//! JNI passes a direct PCM buffer between Rust and AudioTrack, never through Tauri IPC.
use crate::commands::RECEIVER;
use jni::{
    objects::{JByteBuffer, JClass, JObject, JString},
    sys::{jint, jstring},
    JNIEnv,
};
use std::sync::OnceLock;
use tauri::Manager;

pub static APP: OnceLock<tauri::AppHandle> = OnceLock::new();

/// Wry starts the Rust entry point only for the first Activity in a process.
/// A later launcher Activity needs its own window after recents destroyed the old one.
#[no_mangle]
pub extern "system" fn Java_app_getaudiobridge_mobile_MainActivity_nativeRestoreWindow(
    _env: JNIEnv,
    _activity: JObject,
) {
    let Some(app) = APP.get().cloned() else {
        return;
    };
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        if handle.get_webview_window("main").is_some() {
            return;
        }
        if let Some(config) = handle
            .config()
            .app
            .windows
            .iter()
            .find(|w| w.label == "main")
        {
            if let Err(error) = tauri::WebviewWindowBuilder::from_config(&handle, config)
                .and_then(|builder| builder.build())
            {
                eprintln!("getAudioBridge: failed to restore controls: {error}");
            }
        }
    });
}

/// The foreground service owns connection startup, including sticky recreation
/// without an Activity or a WebView. Only configuration crosses this JNI call.
#[no_mangle]
pub extern "system" fn Java_app_getaudiobridge_output_AudioOutputPlugin_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    ip: JString,
    port: jint,
    buffer_ms: jint,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let ip: String = env.get_string(&ip).map_err(|e| e.to_string())?.into();
        let port = u16::try_from(port).map_err(|_| "Porta inválida".to_string())?;
        let mut receiver = RECEIVER.lock().map_err(|e| e.to_string())?;
        receiver.take();
        *receiver = Some(bridge_core::receiver::Receiver::connect(
            &ip,
            port,
            buffer_ms.clamp(20, 120) as u32,
        )?);
        Ok(())
    })();
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(error) => env
            .new_string(error)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_getaudiobridge_output_AudioOutputPlugin_nativeSetBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_ms: jint,
) {
    if let Ok(receiver) = RECEIVER.lock() {
        if let Some(r) = receiver.as_ref() {
            if let Ok(mut buffer) = r.buffer.lock() {
                buffer.set_target(buffer_ms.clamp(20, 120) as u32);
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_getaudiobridge_output_AudioOutputPlugin_nativeRead(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
    frames: jint,
) -> jint {
    if frames != 240 {
        return -1;
    }
    let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) else {
        return -1;
    };
    if capacity < 960 {
        return -1;
    }
    let Ok(ptr) = env.get_direct_buffer_address(&buffer) else {
        return -1;
    };
    let mut pcm = [0_i16; 480];
    if let Ok(receiver) = RECEIVER.lock() {
        if let Some(r) = receiver.as_ref() {
            if let Ok(mut b) = r.buffer.lock() {
                b.render(&mut pcm);
            }
        }
    }
    // Direct buffer remains owned by Kotlin, alive for this synchronous call. Byte writes need no alignment.
    let bytes = unsafe { std::slice::from_raw_parts_mut(ptr, 960) };
    for (p, v) in bytes.chunks_exact_mut(2).zip(pcm) {
        p.copy_from_slice(&v.to_le_bytes());
    }
    960
}

/// Optional native metrics for device qualification. Kotlin only calls this when
/// Android's getAudioBridge debug log tag is explicitly enabled; no PCM is logged.
#[no_mangle]
pub extern "system" fn Java_app_getaudiobridge_output_AudioOutputPlugin_nativeStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let stats = RECEIVER.lock().ok().and_then(|receiver| {
        receiver
            .as_ref()
            .and_then(|r| r.buffer.lock().ok().map(|buffer| buffer.stats.clone()))
    });
    let json = serde_json::to_string(&stats).unwrap_or_else(|_| "null".into());
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_getaudiobridge_output_AudioOutputPlugin_nativeSuspend(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut r) = RECEIVER.lock() {
        r.take();
    }
}
