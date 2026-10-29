use super::*;
use std::{
    net::{Ipv4Addr, SocketAddr, UdpSocket},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

pub struct Receiver {
    pub buffer: Arc<Mutex<JitterBuffer>>,
    stop: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
}
impl Receiver {
    pub fn connect(ip: &str, port: u16, target: u32) -> Result<Self, String> {
        let ip: Ipv4Addr = ip
            .parse()
            .map_err(|_| "Informe um IPv4 válido do PC".to_string())?;
        if ip.is_unspecified() || ip.is_multicast() || ip.is_broadcast() || port == 0 {
            return Err("Endereço/porta inválidos".into());
        }
        let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
        socket
            .connect(SocketAddr::from((ip, port)))
            .map_err(|e| e.to_string())?;
        socket
            .set_read_timeout(Some(Duration::from_millis(50)))
            .map_err(|e| e.to_string())?;
        let token = token()?;
        let buffer = Arc::new(Mutex::new(JitterBuffer::new(target)));
        {
            let mut b = buffer.lock().unwrap();
            b.stats.state = "connecting".into();
            b.stats.peer = Some(format!("{ip}:{port}"));
        }
        let stop = Arc::new(AtomicBool::new(false));
        let (buf, flag) = (buffer.clone(), stop.clone());
        let worker = thread::Builder::new()
            .name("gab-udp-receive".into())
            .spawn(move || {
                let mut bytes = [0; PACKET + 1];
                let mut hello = Instant::now() - Duration::from_secs(1);
                let mut last = Instant::now();
                let started = last;
                let mut previous: Option<(u64, Instant)> = None;
                let mut epoch = None;
                let mut retired = std::collections::VecDeque::new();
                while !flag.load(Ordering::Relaxed) {
                    if hello.elapsed() >= Duration::from_millis(500) {
                        if let Err(e) = socket.send(&header(HELLO, token, 0)) {
                            let mut b = buf.lock().unwrap();
                            b.stats.error = Some(e.to_string());
                        }
                        hello = Instant::now();
                    }
                    match socket.recv(&mut bytes) {
                        Ok(n) => {
                            let Some(h) = decode(&bytes[..n]) else {
                                buf.lock().unwrap().stats.invalid += 1;
                                continue;
                            };
                            if h.token != token || h.kind != AUDIO {
                                continue;
                            }
                            let now = Instant::now();
                            let mut b = buf.lock().unwrap();
                            if epoch != Some(h.epoch) {
                                if retired.contains(&h.epoch) {
                                    continue;
                                }
                                if let Some(old) = epoch {
                                    retired.push_back(old);
                                    if retired.len() > 8 {
                                        retired.pop_front();
                                    }
                                }
                                epoch = Some(h.epoch);
                                b.reset();
                                previous = None;
                            }
                            if let Some((frame, arrival)) = previous {
                                if h.frame > frame {
                                    let variation = (now.duration_since(arrival).as_secs_f64()
                                        * 1000.0
                                        - (h.frame - frame) as f64 / 48.0)
                                        .abs();
                                    b.stats.jitter_ms += (variation - b.stats.jitter_ms) / 16.0;
                                    previous = Some((h.frame, now));
                                }
                            } else {
                                previous = Some((h.frame, now));
                            }
                            b.insert(h.frame, &bytes[HEADER..n]);
                            b.stats.state = "receiving".into();
                            b.stats.error = None;
                            last = now;
                        }
                        Err(e)
                            if matches!(
                                e.kind(),
                                std::io::ErrorKind::TimedOut
                                    | std::io::ErrorKind::WouldBlock
                                    | std::io::ErrorKind::ConnectionReset
                                    | std::io::ErrorKind::ConnectionRefused
                            ) => {}
                        Err(e) => {
                            let mut b = buf.lock().unwrap();
                            b.stats.error = Some(e.to_string());
                            b.stats.state = "error".into();
                            break;
                        }
                    }
                    if last.elapsed() > Duration::from_secs(3) {
                        let mut b = buf.lock().unwrap();
                        if b.stats.state != "waiting" {
                            b.reset();
                            previous = None;
                        }
                        b.stats.state = "waiting".into();
                        if started.elapsed() > Duration::from_secs(5) {
                            b.stats.error = Some(
                                "Sem áudio do PC. Verifique transmissão, IP, Wi-Fi e firewall UDP."
                                    .into(),
                            );
                        }
                    }
                }
                let _ = socket.send(&header(BYE, token, 0));
                let mut b = buf.lock().unwrap();
                b.reset();
                b.stats.state = "disconnected".into();
            })
            .map_err(|e| e.to_string())?;
        Ok(Self {
            buffer,
            stop,
            worker: Some(worker),
        })
    }
}
impl Drop for Receiver {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(w) = self.worker.take() {
            let _ = w.join();
        }
    }
}
