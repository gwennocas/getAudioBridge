//! Wire format and bounded, clock-adaptive playout. No webview dependencies.
pub mod receiver;
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};

pub const RATE: u32 = 48_000;
pub const FRAMES: usize = 240;
pub const SAMPLES: usize = FRAMES * 2;
pub const HEADER: usize = 40;
pub const PACKET: usize = HEADER + SAMPLES * 2;
pub const HELLO: u8 = 1;
pub const AUDIO: u8 = 2;
pub const BYE: u8 = 3;
// The wire uses u64, while fractional playout uses f64. Keep even deliberately
// malformed timestamps in the exactly representable range (thousands of years).
const MAX_FRAME: u64 = (1_u64 << 52) - FRAMES as u64;

pub fn token() -> Result<u64, String> {
    let mut b = [0; 8];
    getrandom::fill(&mut b).map_err(|e| e.to_string())?;
    Ok(u64::from_le_bytes(b))
}

#[derive(Debug)]
pub struct Header {
    pub kind: u8,
    pub token: u64,
    pub sequence: u32,
    pub frame: u64,
    pub epoch: u32,
}

pub fn header(kind: u8, token: u64, frame: u64) -> [u8; HEADER] {
    let mut b = [0; HEADER];
    b[..4].copy_from_slice(b"GAB1");
    b[4] = 1;
    b[5] = kind;
    b[6] = 2;
    b[7] = 1; // version, kind, stereo, PCM16LE
    b[8..16].copy_from_slice(&token.to_le_bytes());
    b[16..20].copy_from_slice(&((frame / FRAMES as u64) as u32).to_le_bytes());
    b[20..24].copy_from_slice(&RATE.to_le_bytes());
    b[24..32].copy_from_slice(&frame.to_le_bytes());
    if kind == AUDIO {
        b[32..34].copy_from_slice(&(FRAMES as u16).to_le_bytes());
    }
    b
}

pub fn decode(b: &[u8]) -> Option<Header> {
    if b.len() < HEADER
        || &b[..4] != b"GAB1"
        || b[4] != 1
        || b[6..8] != [2, 1]
        || u32::from_le_bytes(b[20..24].try_into().ok()?) != RATE
    {
        return None;
    }
    let kind = b[5];
    if ![HELLO, AUDIO, BYE].contains(&kind) {
        return None;
    }
    let frame = u64::from_le_bytes(b[24..32].try_into().ok()?);
    let sequence = u32::from_le_bytes(b[16..20].try_into().ok()?);
    let frames = u16::from_le_bytes(b[32..34].try_into().ok()?);
    if kind == AUDIO {
        if b.len() != PACKET
            || frames as usize != FRAMES
            || frame % FRAMES as u64 != 0
            || frame > MAX_FRAME
            || sequence != (frame / FRAMES as u64) as u32
        {
            return None;
        }
    } else if b.len() != HEADER || frames != 0 {
        return None;
    }
    Some(Header {
        kind,
        token: u64::from_le_bytes(b[8..16].try_into().ok()?),
        sequence,
        frame,
        epoch: u32::from_le_bytes(b[36..40].try_into().ok()?),
    })
}

#[derive(Clone, Default, Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Stats {
    pub state: String,
    pub error: Option<String>,
    pub packets: u64,
    pub bytes: u64,
    pub lost: u64,
    pub late: u64,
    pub duplicates: u64,
    pub invalid: u64,
    pub rebuffers: u64,
    pub trims: u64,
    pub buffer_ms: f64,
    pub target_ms: u32,
    pub drift_ppm: f64,
    pub jitter_ms: f64,
    pub peer: Option<String>,
    pub peak: f64,
}

pub struct JitterBuffer {
    packets: BTreeMap<u64, [i16; SAMPLES]>,
    cursor: Option<f64>,
    latest: u64,
    target: u32,
    filtered_error: f64,
    missing: BTreeSet<u64>,
    pub stats: Stats,
}

impl JitterBuffer {
    pub fn new(target: u32) -> Self {
        let mut s = Self {
            packets: BTreeMap::new(),
            cursor: None,
            latest: 0,
            target: 60,
            filtered_error: 0.0,
            missing: BTreeSet::new(),
            stats: Stats::default(),
        };
        s.set_target(target);
        s
    }
    pub fn set_target(&mut self, ms: u32) {
        let target = ms.clamp(20, 120);
        if target != self.target {
            if self.cursor.is_some() && target < self.target {
                self.stats.trims += 1;
            }
            self.cursor = None; // refill for a larger target, jump to recent audio for a smaller one
            self.filtered_error = 0.0;
            self.missing.clear();
        }
        self.target = target;
        self.stats.target_ms = self.target;
    }
    pub fn reset(&mut self) {
        self.packets.clear();
        self.cursor = None;
        self.latest = 0;
        self.filtered_error = 0.0;
        self.missing.clear();
        self.stats.buffer_ms = 0.0;
        self.stats.drift_ppm = 0.0;
        self.stats.peak = 0.0;
    }
    pub fn insert(&mut self, frame: u64, data: &[u8]) {
        if data.len() != SAMPLES * 2 || frame > MAX_FRAME {
            self.stats.invalid += 1;
            return;
        }
        if let Some(c) = self.cursor {
            if (frame + FRAMES as u64) as f64 <= c {
                self.stats.late += 1;
                return;
            }
        }
        // Refuse stale backlog and retain at most 200 ms, even before playback starts.
        if self.latest > frame.saturating_add(RATE as u64 / 5) {
            self.stats.late += 1;
            return;
        }
        if self.packets.contains_key(&frame) {
            self.stats.duplicates += 1;
            return;
        }
        let mut pcm = [0; SAMPLES];
        for (out, pair) in pcm.iter_mut().zip(data.chunks_exact(2)) {
            *out = i16::from_le_bytes([pair[0], pair[1]]);
        }
        self.packets.insert(frame, pcm);
        self.latest = self.latest.max(frame + FRAMES as u64);
        let cutoff = self.latest.saturating_sub(RATE as u64 / 5);
        self.packets.retain(|frame, _| *frame >= cutoff);
        self.stats.packets += 1;
        self.stats.bytes += PACKET as u64;
    }
    fn sample(&mut self, frame: u64, channel: usize) -> i16 {
        let key = frame / FRAMES as u64 * FRAMES as u64;
        if let Some(p) = self.packets.get(&key) {
            return p[(frame - key) as usize * 2 + channel];
        }
        if self.missing.insert(key) {
            self.stats.lost += 1;
        }
        0 // bounded loss concealment: silence, no retransmission backlog
    }
    pub fn render(&mut self, output: &mut [i16]) {
        output.fill(0);
        self.stats.peak = 0.0;
        let target = self.target as f64 * 48.0;
        if self.cursor.is_none() {
            let Some((&first, _)) = self.packets.first_key_value() else {
                return;
            };
            if self.latest.saturating_sub(first) < target as u64 {
                return;
            }
            self.cursor = Some((self.latest as f64 - target).max(first as f64));
        }
        let mut c = self.cursor.unwrap();
        let occupancy = self.latest as f64 - c;
        if occupancy > target + 1920.0 {
            // >40 ms beyond target: catch up, never accumulate latency
            c = self.latest as f64 - target;
            self.filtered_error = 0.0;
            self.stats.trims += 1;
        }
        if c + (output.len() / 2) as f64 + 2.0 >= self.latest as f64 {
            self.stats.rebuffers += 1;
            self.reset();
            return;
        }
        self.filtered_error = self.filtered_error * 0.995 + (occupancy - target) * 0.005;
        let correction = (self.filtered_error * 0.000002).clamp(-0.002, 0.002);
        self.stats.drift_ppm = correction * 1_000_000.0;
        for pair in output.chunks_exact_mut(2) {
            let base = c.floor() as u64;
            let fraction = c - base as f64;
            for (ch, sample) in pair.iter_mut().enumerate() {
                let a = self.sample(base, ch) as f64;
                let b = self.sample(base + 1, ch) as f64;
                *sample = (a + (b - a) * fraction).round().clamp(-32768.0, 32767.0) as i16;
            }
            c += 1.0 + correction;
        }
        self.cursor = Some(c);
        let retain = (c as u64 / FRAMES as u64) * FRAMES as u64;
        self.packets.retain(|frame, _| *frame >= retain);
        self.missing.retain(|frame| *frame >= retain);
        self.stats.buffer_ms = (self.latest as f64 - c).max(0.0) / 48.0;
        self.stats.peak = output
            .iter()
            .map(|v| (*v as f64).abs() / 32768.0)
            .fold(0.0, f64::max);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn packet(frame: u64) -> Vec<u8> {
        let mut b = header(AUDIO, 42, frame).to_vec();
        b.extend_from_slice(&[0; SAMPLES * 2]);
        b
    }
    fn fill(j: &mut JitterBuffer, from: u64, count: u64) {
        for n in from..from + count {
            j.insert(n * 240, &[1; 960]);
        }
    }
    #[test]
    fn wire_rejects_truncation_and_bad_timeline() {
        let mut b = packet(480);
        assert_eq!(decode(&b).unwrap().sequence, 2);
        assert!(decode(&b[..b.len() - 1]).is_none());
        b[16] = 5;
        assert!(decode(&b).is_none());
        assert!(decode(&header(HELLO, 42, 0)).is_some());
    }
    #[test]
    fn reorder_duplicate_loss_and_late() {
        let mut j = JitterBuffer::new(20);
        fill(&mut j, 0, 6);
        j.insert(240, &[1; 960]);
        assert_eq!(j.stats.duplicates, 1);
        j.packets.remove(&480);
        let mut b = [0; 480];
        j.render(&mut b);
        j.render(&mut b);
        assert!(j.stats.lost > 0);
        j.insert(0, &[1; 960]);
        assert_eq!(j.stats.late, 1);
    }
    #[test]
    fn bounded_backlog_and_underflow() {
        let mut j = JitterBuffer::new(20);
        fill(&mut j, 0, 2000);
        assert!(j.packets.len() <= 40);
        let mut b = [0; 480];
        j.render(&mut b);
        assert!(j.stats.buffer_ms <= 20.0);
        for _ in 0..20 {
            j.render(&mut b);
        }
        assert!(j.stats.rebuffers > 0);
        assert!(b.iter().all(|v| *v == 0));
    }
    #[test]
    fn catches_up_after_playback_stall() {
        let mut j = JitterBuffer::new(40);
        fill(&mut j, 0, 10);
        j.render(&mut [0; 480]);
        fill(&mut j, 10, 30);
        j.render(&mut [0; 480]);
        assert_eq!(j.stats.trims, 1);
        assert!(j.stats.buffer_ms <= 40.0);
    }
    #[test]
    fn compensates_clock_without_unbounded_buffer() {
        let mut j = JitterBuffer::new(40);
        fill(&mut j, 0, 9);
        let mut seq = 9;
        for tick in 0..30_000 {
            fill(&mut j, seq, 1);
            seq += 1;
            if tick % 1000 == 0 {
                fill(&mut j, seq, 1);
                seq += 1;
            }
            j.render(&mut [0; 480]);
        }
        assert!(j.stats.drift_ppm > 0.0);
        assert_eq!(j.stats.rebuffers, 0);
        assert!(j.stats.buffer_ms < 80.0);
    }

    #[test]
    fn slower_source_clock_remains_bounded() {
        let mut j = JitterBuffer::new(60);
        fill(&mut j, 0, 13);
        let mut seq = 13;
        for tick in 0..30_000 {
            if tick % 1000 != 0 {
                fill(&mut j, seq, 1);
                seq += 1;
            }
            j.render(&mut [0; SAMPLES]);
        }
        assert!(j.stats.drift_ppm < 0.0);
        assert_eq!(j.stats.rebuffers, 0);
        assert!(j.stats.buffer_ms > 10.0 && j.stats.buffer_ms < 100.0);
    }

    #[test]
    fn target_changes_refill_and_trim_without_growing_a_queue() {
        let mut j = JitterBuffer::new(20);
        fill(&mut j, 0, 6);
        let mut output = [0; SAMPLES];
        j.render(&mut output);
        j.set_target(120);
        j.render(&mut output);
        assert!(output.iter().all(|sample| *sample == 0));
        fill(&mut j, 6, 30);
        j.render(&mut output);
        assert!(output.iter().all(|sample| *sample != 0));
        assert!(j.stats.buffer_ms <= 120.0);
        j.set_target(1);
        j.render(&mut output);
        assert_eq!(j.stats.target_ms, 20);
        assert!(j.stats.buffer_ms <= 20.0);
        assert!(j.stats.trims > 0);
    }

    #[test]
    fn stereo_order_and_missing_packets_are_counted_once() {
        let mut j = JitterBuffer::new(40);
        let mut pcm = [0_u8; SAMPLES * 2];
        for frame in pcm.chunks_exact_mut(4) {
            frame[..2].copy_from_slice(&1234_i16.to_le_bytes());
            frame[2..].copy_from_slice(&(-2345_i16).to_le_bytes());
        }
        for n in 0..10 {
            j.insert(n * FRAMES as u64, &pcm);
        }
        j.packets.remove(&(4 * FRAMES as u64));
        j.packets.remove(&(5 * FRAMES as u64));
        let mut output = [0; SAMPLES];
        j.render(&mut output);
        assert_eq!(output[0], 1234);
        assert_eq!(output[1], -2345);
        for _ in 0..5 {
            j.render(&mut output);
        }
        assert_eq!(j.stats.lost, 2);
    }

    #[test]
    fn rejects_overflowing_timestamps_and_wrong_format() {
        let mut p = packet(u64::MAX / FRAMES as u64 * FRAMES as u64);
        assert!(decode(&p).is_none());
        p = packet((u64::MAX / FRAMES as u64 - 2) * FRAMES as u64);
        assert!(decode(&p).is_none());
        p = packet(0);
        p[6] = 1;
        assert!(decode(&p).is_none());
        p = packet(0);
        p[7] = 2;
        assert!(decode(&p).is_none());
        let mut j = JitterBuffer::new(60);
        j.insert(u64::MAX, &[0; SAMPLES * 2]);
        assert_eq!(j.stats.invalid, 1);
        assert!(j.packets.is_empty());
    }
}
