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

The transport module now contains an isolated RTP v2 packet profile and unconnected UDP socket adapter:

- Fixed 12-byte RTP v2 header: marker, 7-bit payload type, uint16 sequence, uint32 media timestamp and uint32 SSRC.
- No CSRC list, header extension or padding in this first profile; those header bits are rejected rather than silently misparsed.
- Datagram budget is capped at 1,200 bytes, leaving at most 1,188 bytes for payload to avoid routine IP fragmentation on common LAN paths.
- Payload is opaque. The transport does not yet encode/decode Opus or PCM, schedule playout, reorder packets, repair loss, authenticate peers or integrate with the Android Host/Speaker session.
- RtpUdpSocket owns a UDP socket, reports source endpoint and monotonic receive time, and leaves session/SSRC/sequence validation to its caller.

This is a packet/socket foundation, not a working audio-stream mode. Existing PCM/TCP remains the application path and fallback. RTCP Sender Report and empty Receiver Report packet/socket primitives are now present, but periodic scheduling, report blocks with loss/jitter statistics, RTP↔NTP clock mapping in the session, NACK/repair, Opus codec integration and playout-buffer wiring are subsequent stages.

## Boundaries and limitations

This module owns socket lifecycle and bounded wire-frame reads/writes. It does not implement the Android UI, JNI bridge, C++ codec binding, token/session state machine integration, authenticated encryption, audio queues, audio decoding, or physical-device synchronization. The frame envelope is implemented in Kotlin for now; keep it byte-compatible with `core/src/protocol.cpp` and the shared vectors. A later JNI integration can remove duplicate parsing once the mobile core bridge exists.
