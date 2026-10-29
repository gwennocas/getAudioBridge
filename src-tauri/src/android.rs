//! JNI passes a direct PCM buffer between Rust and AudioTrack, never through Tauri IPC.
use crate::commands::RECEIVER;
use jni::{
    objects::{JByteBuffer, JClass},
    sys::jint,
    JNIEnv,
};

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

#[no_mangle]
pub extern "system" fn Java_app_getaudiobridge_output_AudioOutputPlugin_nativeSuspend(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut r) = RECEIVER.lock() {
        r.take();
    }
}
