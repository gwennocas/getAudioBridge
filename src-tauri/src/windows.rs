use ::windows::Win32::Media::Audio::{
    IAudioCaptureClient, AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY, AUDCLNT_BUFFERFLAGS_SILENT,
};
use bridge_core::{decode, header, Stats, AUDIO, BYE, FRAMES, HEADER, HELLO, PACKET};
use serde::Serialize;
use std::{
    collections::VecDeque,
    net::{SocketAddr, UdpSocket},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};
use wasapi::*;
type Result<T> = std::result::Result<T, String>;
fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

fn transient_receive_error(error: &std::io::Error) -> bool {
    // On Windows, oversized UDP datagrams report WSAEMSGSIZE instead of returning
    // truncated data. ICMP port-unreachable can surface as WSAECONNRESET after a
    // phone disconnects. Neither should terminate the capture thread.
    error.raw_os_error() == Some(10040)
        || matches!(
            error.kind(),
            std::io::ErrorKind::ConnectionReset
                | std::io::ErrorKind::ConnectionRefused
                | std::io::ErrorKind::TimedOut
        )
}

fn transient_send_error(error: &std::io::Error) -> bool {
    transient_receive_error(error)
        || matches!(error.raw_os_error(), Some(10051 | 10065))
        || matches!(
            error.kind(),
            std::io::ErrorKind::WouldBlock | std::io::ErrorKind::Interrupted
        )
}
struct Com;
impl Com {
    fn init() -> Result<Self> {
        initialize_mta().ok().map_err(err)?;
        Ok(Self)
    }
}
impl Drop for Com {
    fn drop(&mut self) {
        deinitialize();
    }
}

#[derive(Serialize)]
pub struct OutputDevice {
    id: String,
    name: String,
}
pub fn devices() -> Result<Vec<OutputDevice>> {
    thread::spawn(|| {
        let _com = Com::init()?;
        let en = DeviceEnumerator::new().map_err(err)?;
        let collection = en.get_device_collection(&Direction::Render).map_err(err)?;
        let mut items = vec![];
        for device in &collection {
            let d = device.map_err(err)?;
            items.push(OutputDevice {
                id: d.get_id().map_err(err)?,
                name: d.get_friendlyname().map_err(err)?,
            });
        }
        Ok(items)
    })
    .join()
    .map_err(|_| "Falha ao listar saídas".to_string())?
}

pub struct Sender {
    flag: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
    pub stats: Arc<Mutex<Stats>>,
}
impl Sender {
    pub fn start(device_id: String, bind_ip: String, port: u16) -> Result<Self> {
        let ip: std::net::Ipv4Addr = bind_ip
            .parse()
            .map_err(|_| "IPv4 local inválido".to_string())?;
        if port == 0 || ip.is_multicast() || ip.is_broadcast() {
            return Err("Endereço/porta inválidos".into());
        }
        let socket = UdpSocket::bind((ip, port)).map_err(err)?;
        socket.set_nonblocking(true).map_err(err)?;
        let flag = Arc::new(AtomicBool::new(false));
        let stats = Arc::new(Mutex::new(Stats {
            state: "starting".into(),
            ..Default::default()
        }));
        let (f, s) = (flag.clone(), stats.clone());
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let worker = thread::Builder::new()
            .name("gab-wasapi-udp".into())
            .spawn(move || {
                let result = capture(socket, device_id, f, s.clone(), ready_tx.clone());
                if let Err(e) = result {
                    let _ = ready_tx.try_send(Err(e.clone()));
                    let mut st = s.lock().unwrap();
                    st.state = "error".into();
                    st.error = Some(e);
                }
            })
            .map_err(err)?;
        let sender = Self {
            flag,
            worker: Some(worker),
            stats,
        };
        ready_rx
            .recv_timeout(Duration::from_secs(8))
            .map_err(err)??;
        Ok(sender)
    }
}
impl Drop for Sender {
    fn drop(&mut self) {
        self.flag.store(true, Ordering::Relaxed);
        if let Some(w) = self.worker.take() {
            let _ = w.join();
        }
    }
}

fn capture(
    socket: UdpSocket,
    device_id: String,
    flag: Arc<AtomicBool>,
    stats: Arc<Mutex<Stats>>,
    ready: mpsc::SyncSender<Result<()>>,
) -> Result<()> {
    let _com = Com::init()?;
    let en = DeviceEnumerator::new().map_err(err)?;
    let device = if device_id.is_empty() {
        en.get_default_device(&Direction::Render)
    } else {
        en.get_device(&device_id)
    }
    .map_err(err)?;
    let mut client = device.get_iaudioclient().map_err(err)?;
    let format = WaveFormat::new(16, 16, &SampleType::Int, 48_000, 2, None);
    let mode = StreamMode::PollingShared {
        autoconvert: true,
        // Retention capacity, not a sleep/playout delay: read every 2 ms, but survive
        // scheduling hiccups without the audio engine overwriting a tiny capture buffer.
        buffer_duration_hns: 1_000_000,
    };
    client
        .initialize_client(&format, &Direction::Capture, &mode)
        .map_err(err)?;
    // Read the raw capture service so SILENT buffers can be handled without dereferencing
    // the optionally null WASAPI data pointer (including on idle endpoints).
    let capture: IAudioCaptureClient =
        unsafe { client.as_iaudioclient().GetService() }.map_err(err)?;
    client.start_stream().map_err(err)?;
    stats.lock().unwrap().state = "listening".into();
    let _ = ready.send(Ok(()));
    let mut peer: Option<(SocketAddr, u64, Instant)> = None;
    let mut pending = VecDeque::with_capacity(48_000);
    let mut control = [0; HEADER + 1];
    let mut packet = [0; PACKET];
    let mut frame = 0_u64;
    let epoch = bridge_core::token()? as u32;
    let mut last_capture = Instant::now();
    let mut silence_due = Instant::now();
    while !flag.load(Ordering::Relaxed) {
        for _ in 0..32 {
            // bounded control work; never starve capture
            match socket.recv_from(&mut control) {
                Ok((n, addr)) => {
                    let Some(h) = decode(&control[..n]) else {
                        stats.lock().unwrap().invalid += 1;
                        continue;
                    };
                    if h.kind == HELLO {
                        if peer.as_ref().is_none_or(|(p, t, seen)| {
                            (*p == addr && *t == h.token) || seen.elapsed() > Duration::from_secs(3)
                        }) {
                            peer = Some((addr, h.token, Instant::now()));
                            let mut st = stats.lock().unwrap();
                            st.peer = Some(addr.to_string());
                            st.state = "streaming".into();
                        }
                    } else if h.kind == BYE
                        && peer
                            .as_ref()
                            .is_some_and(|(p, t, _)| *p == addr && *t == h.token)
                    {
                        peer = None;
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(e) if transient_receive_error(&e) => {
                    if e.raw_os_error() == Some(10040) {
                        stats.lock().unwrap().invalid += 1;
                    }
                }
                Err(e) => return Err(err(e)),
            }
        }
        if peer
            .as_ref()
            .is_some_and(|(_, _, seen)| seen.elapsed() > Duration::from_secs(3))
        {
            peer = None;
        }
        if peer.is_none() {
            let mut st = stats.lock().unwrap();
            st.state = "listening".into();
            st.peer = None;
        }
        while unsafe { capture.GetNextPacketSize() }.map_err(err)? > 0 {
            let mut data = std::ptr::null_mut();
            let mut count = 0;
            let mut flags = 0;
            unsafe { capture.GetBuffer(&mut data, &mut count, &mut flags, None, None) }
                .map_err(err)?;
            let bytes = count as usize * 4;
            if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 {
                pending.resize(pending.len() + bytes, 0);
            } else if !data.is_null() {
                // WASAPI owns this allocation until ReleaseBuffer; copy only the returned PCM frames.
                pending.extend(unsafe { std::slice::from_raw_parts(data, bytes) });
            } else if count > 0 {
                let _ = unsafe { capture.ReleaseBuffer(count) };
                return Err("WASAPI retornou um buffer nulo sem SILENT".into());
            }
            if count > 0 {
                unsafe { capture.ReleaseBuffer(count) }.map_err(err)?;
            }
            if flags & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY.0 as u32 != 0 {
                stats.lock().unwrap().trims += 1;
            }
            last_capture = Instant::now();
            silence_due = last_capture + Duration::from_millis(20);
        }
        // WASAPI may stop issuing packets when the entire endpoint is idle.
        // Keep a paced silent timeline alive after a 20 ms grace period.
        if last_capture.elapsed() > Duration::from_millis(20) && Instant::now() >= silence_due {
            let now = Instant::now();
            if now.duration_since(silence_due) > Duration::from_millis(100) {
                silence_due = now;
            }
            while silence_due <= now {
                pending.extend([0; FRAMES * 4]);
                silence_due += Duration::from_millis(5);
            }
        }
        // After scheduling stalls, discard old capture rather than transmit a growing queue.
        if pending.len() > 48_000 / 5 * 4 {
            let discard = (pending.len().saturating_sub(FRAMES * 4)) / (FRAMES * 4) * (FRAMES * 4);
            pending.drain(..discard);
            frame += (discard / 4) as u64;
            stats.lock().unwrap().trims += 1;
        }
        while pending.len() >= FRAMES * 4 {
            if let Some((addr, token, _)) = peer {
                packet[..HEADER].copy_from_slice(&header(AUDIO, token, frame));
                packet[36..40].copy_from_slice(&epoch.to_le_bytes());
                for byte in &mut packet[HEADER..] {
                    *byte = pending.pop_front().unwrap();
                }
                match socket.send_to(&packet, addr) {
                    Ok(n) => {
                        let mut s = stats.lock().unwrap();
                        s.packets += 1;
                        s.bytes += n as u64;
                        s.peak = packet[HEADER..]
                            .chunks_exact(2)
                            .map(|p| (i16::from_le_bytes([p[0], p[1]]) as f64).abs() / 32768.0)
                            .fold(0.0, f64::max);
                    }
                    Err(e) if transient_send_error(&e) => {
                        stats.lock().unwrap().lost += 1;
                    }
                    Err(e) => return Err(err(e)),
                }
            } else {
                pending.drain(..FRAMES * 4);
            }
            frame = frame
                .checked_add(FRAMES as u64)
                .ok_or("Timeline esgotada")?;
        }
        thread::sleep(Duration::from_millis(2));
    }
    client.stop_stream().map_err(err)?;
    stats.lock().unwrap().state = "stopped".into();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn malformed_datagrams_and_disconnected_receivers_do_not_stop_capture() {
        assert!(transient_receive_error(&std::io::Error::from_raw_os_error(
            10040
        )));
        assert!(transient_receive_error(&std::io::Error::from(
            std::io::ErrorKind::ConnectionReset
        )));
        assert!(transient_receive_error(&std::io::Error::from(
            std::io::ErrorKind::ConnectionRefused
        )));
        assert!(!transient_receive_error(&std::io::Error::from(
            std::io::ErrorKind::PermissionDenied
        )));
        assert!(transient_send_error(&std::io::Error::from_raw_os_error(
            10065
        )));
        assert!(transient_send_error(&std::io::Error::from_raw_os_error(
            10051
        )));
        assert!(!transient_send_error(&std::io::Error::from(
            std::io::ErrorKind::PermissionDenied
        )));
    }
}
