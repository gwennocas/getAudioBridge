use bridge_core::{decode, header, receiver::Receiver, AUDIO, BYE, HEADER, HELLO, PACKET};
use std::{
    net::UdpSocket,
    thread,
    time::{Duration, Instant},
};

#[test]
fn handshake_stream_restart_and_disconnect_over_udp() {
    let server = UdpSocket::bind("127.0.0.1:0").unwrap();
    server
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let port = server.local_addr().unwrap().port();
    let receiver = Receiver::connect("127.0.0.1", port, 20).unwrap();
    let mut control = [0; HEADER];
    let (n, peer) = server.recv_from(&mut control).unwrap();
    let hello = decode(&control[..n]).unwrap();
    assert_eq!(hello.kind, HELLO);
    let send = |frame: u64, epoch: u32, token: u64| {
        let mut packet = [0_u8; PACKET];
        packet[..HEADER].copy_from_slice(&header(AUDIO, token, frame));
        packet[36..40].copy_from_slice(&epoch.to_le_bytes());
        for chunk in packet[HEADER..].chunks_exact_mut(2) {
            chunk.copy_from_slice(&1234_i16.to_le_bytes());
        }
        server.send_to(&packet, peer).unwrap();
    };
    // Wrong connection token must not contribute to the buffer.
    send(0, 1, hello.token.wrapping_add(1));
    for i in 0..8 {
        send(240_000 + i * 240, 1, hello.token);
    }
    let wait_packets = |count| {
        let deadline = Instant::now() + Duration::from_secs(2);
        while receiver.buffer.lock().unwrap().stats.packets < count {
            assert!(Instant::now() < deadline, "receiver stalled");
            thread::sleep(Duration::from_millis(5));
        }
    };
    wait_packets(8);
    let mut pcm = [0_i16; 480];
    receiver.buffer.lock().unwrap().render(&mut pcm);
    assert!(pcm.iter().all(|v| *v == 1234));
    // Same client, new PC epoch, timeline reset to zero; old epoch cannot roll it back.
    for i in 0..8 {
        send(i * 240, 2, hello.token);
    }
    send(242_000 / 240 * 240, 1, hello.token);
    wait_packets(16);
    let mut buffer = receiver.buffer.lock().unwrap();
    buffer.render(&mut pcm);
    assert!(pcm.iter().all(|v| *v == 1234));
    assert_eq!(buffer.stats.packets, 16);
    assert_eq!(buffer.stats.late, 0);
    drop(buffer);
    drop(receiver);
    loop {
        let (n, _) = server.recv_from(&mut control).unwrap();
        if decode(&control[..n]).unwrap().kind == BYE {
            break;
        }
    }
}
