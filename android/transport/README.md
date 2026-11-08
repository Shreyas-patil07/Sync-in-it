# Sync In It — JVM socket transport

A dependency-light Kotlin/JVM transport module intended for use by the Android master and receiver roles. It uses only Java networking APIs available on Android and the JVM; it is not an Android application module and has no UI or audio-engine dependency.

## Build and test

Requirements: JDK 17 and Gradle 8.10.2 or newer.

```bash
gradle -p android/transport check
```

The check task compiles the Kotlin source and runs loopback integration tests for TCP framing and UDP time-sync datagrams.

## TCP

- `TcpFrameServer` accepts master-side connections; `TcpFrameConnection.connect` opens receiver-side connections.
- Each connection has independent framing state and enforces the v1 maximum frame length before allocating its body.
- Reads are blocking, return `null` only on clean EOF between frames, and throw on truncated or malformed frames.
- Writes are synchronized to prevent byte interleaving between concurrent control senders.
- TCP_NODELAY and keepalive are enabled. Connect/read/accept timeouts are configurable.
- Call from dedicated I/O threads. Do not read/write from the audio callback. Writes are synchronous; callers must keep their own bounded outbound queue and overflow policy.

## UDP time sync

- `UdpTimeSyncClient` binds a local ephemeral port, connects to the configured master endpoint and filters responses to that peer.
- `UdpTimeSyncServer` receives 38-byte datagrams and returns the source endpoint so the caller can send the matching response.
- The adapter enforces the protocol's fixed 38-byte envelope only. The shared protocol codec/session core must still validate magic, version, session ID, receiver ID, sequence, timestamps and request authentication binding.
- Receive timeouts surface as `SocketTimeoutException`; retry cadence and four-timestamp correlation remain caller responsibilities.

## RTP/UDP audio transport prototype

The transport module contains an isolated RTP v2 packet profile and unconnected UDP socket adapter:

- Fixed 12-byte RTP v2 header: marker, 7-bit payload type, uint16 sequence, uint32 media timestamp and uint32 SSRC.
- No CSRC list, header extension or padding in this first profile; those header bits are rejected rather than silently misparsed.
- Datagram budget is capped at 1,200 bytes, leaving at most 1,188 bytes for payload to avoid routine IP fragmentation on common LAN paths.
- `RtpUdpSocket` owns a UDP socket, reports source endpoint and monotonic receive time, and leaves session/SSRC/sequence admission to its caller.

### Diagnostic PCM16 RTP profile

`RtpPcm16Packetizer` and `RtpPcm16Decoder` define the first project-specific payload profile:

- Input is one 20 ms, 48 kHz, stereo, signed PCM16 little-endian chunk: 960 sample frames / 3,840 bytes.
- That raw chunk cannot fit in a 1,200-byte RTP datagram. It is split into four 5 ms packets, each carrying 240 stereo frames / 960 bytes.
- Dynamic RTP payload type 96 identifies this diagnostic PCM profile. Sequence number increments for every 5 ms packet; timestamp identifies the first sample frame in that packet and advances by 240 at a 48 kHz clock rate.
- The four packets retain one session SSRC. The decoder validates payload type, SSRC and exact payload size, and extends 16-bit sequence / 32-bit timestamp counters across wrap.
- The Android native PCM buffer already stores samples by sample index and accepts out-of-order ranges and duplicates. The intended receiver adapter will insert each decoded 240-frame packet into that buffer; packet arrival order must never become playback order.

This profile is a transport-library primitive with packetizer/decoder tests. **It is not yet wired into the Android Host/Speaker session**; the current application path remains PCM16/TCP. The next integration must be feature-flagged, use per-Speaker unicast endpoints, preserve the TCP fallback and prove received PCM energy as well as packet continuity.

### RTCP boundary

The current `RtcpPacketCodec` and `RtcpUdpSocket` are packet primitives only. They do not yet implement a complete RFC 3550 RTCP feedback plane: periodic scheduling, compound packets with SDES/CNAME, Receiver Report report blocks (fraction/cumulative loss, extended highest sequence, interarrival jitter, LSR/DLSR), or the RTP↔sender-clock mapping are still required. Do not treat the current empty RR packet as full receiver feedback.

An RTCP Sender Report correlates an RTP timestamp with the sender's NTP-format reference clock; it does not synchronize Android monotonic clocks by itself. Keep the existing four-timestamp clock estimator and common `(masterTimeNs, sampleIndex)` ANCHOR. Use RTCP to monitor the media clock and receiver delivery quality, not to bypass the clock-readiness gate.

Opus is a later profile: a 20 ms Opus packet advances the 48 kHz RTP timestamp by 960 ticks and normally fits one RTP payload. Its payload format must follow RFC 7587.

## Boundaries and limitations

This module owns socket lifecycle and bounded wire-frame reads/writes. It does not implement the Android UI, JNI bridge, C++ codec binding, token/session state machine integration, authenticated encryption, audio queues, audio decoding, or physical-device synchronization. The frame envelope is implemented in Kotlin for now; keep it byte-compatible with `core/src/protocol.cpp` and the shared vectors. A later JNI integration can remove duplicate parsing once the mobile core bridge exists.
