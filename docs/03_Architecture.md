# System Architecture — Sync In It

**Document status:** Draft architecture baseline  
**Version:** 1.0  
**Last updated:** 1 November 2026

## 1. Architecture overview

Sync In It is a local-network, master-clock-based audio distribution system. It is not a conventional cloud client/server application. The Android master owns the session and timeline; receiver phones connect directly to it over a private Wi-Fi LAN.

The architecture separates three concerns:

1. **Audio delivery:** deliver samples early and reliably enough to fill receiver buffers.
2. **Time and playout coordination:** estimate clock relationships and schedule samples against a shared master timeline.
3. **Acoustic alignment:** compensate for differences between digital presentation and sound arrival at a selected listening position.

## 2. System context

### 2.1 Components

| Component | Responsibility |
|---|---|
| Android master | Source, session authority, master clock, controller, QR generation, telemetry aggregation |
| Android receiver | Network client, shared timing core, Oboe/AAudio output, receiver UI |
| iOS receiver | Network client, shared timing core, AVAudioEngine output, receiver UI |
| Wi-Fi router | Private local network; internet is not required |
| Recorder | Test instrumentation only; records speaker output for acoustic-skew analysis |
| Listener position | Defined point at which acoustic calibration is valid |

### 2.2 Deployment topology

All phones connect to the same private router. The master listens on the control, audio and time-sync ports. Each receiver connects to the master. The master does not require a public IP address or cloud relay.

## 3. Layered architecture

| Layer | Name | Responsibility | Main mechanism |
|---|---|---|---|
| L1 | Audio transport | Send audio ahead of playback | Timestamped chunks over TCP |
| L2 | Clock distribution | Estimate receiver-to-master time mapping | UDP four-timestamp exchange |
| L3 | Group playout control | Establish common playback deadline | Receiver reports and shared timeline anchors |
| L4 | DAC clock mapping | Relate audio output frames to local time | Native output timestamps and regression |
| L5 | Drift correction | Maintain alignment during playback | Read-position correction, initially controlled slips |
| L6 | Acoustic alignment | Align actual sound arrival | Scheduled chirps, microphone measurement and compensation |
| L7 | Resilience | Handle degraded links and lifecycle events | Buffering, reconnect, quarantine/mute and rejoin |

Each layer exports metrics and state. A failure in one layer must be diagnosable without treating all timing errors as generic network jitter.

## 4. Logical software architecture

### 4.1 Shared C++ core

The portable C++17 library contains timing and stream logic:

- Common types and injected monotonic clock interface.
- Protocol message structures and pure encoders/decoders.
- Clock-offset and skew estimator.
- Timeline anchor manager.
- DAC clock mapping model.
- Sample-indexed jitter buffer.
- Drift controller.
- Group controller and receiver state.
- Calibration signal generation and delay estimation.
- Codec interfaces (PCM first, Opus later).
- Telemetry event structures.
- Small concurrency primitives for safe parameter publication.
- Bounded, non-blocking frame queues for network-thread handoff.
- UDP time-sync request/response correlation, identity validation and pending-request expiry.

The core must compile and run on a desktop without Android or iOS SDKs. Platform-specific audio and lifecycle code must not leak into core modules.

### 4.2 Android application

The Android application provides two roles in one app:

**Master mode**
- Session creation and QR code.
- Local-file source and optional supported live capture.
- Foreground service and lifecycle management.
- Group controller and network listeners.
- Receiver list and diagnostic aggregation.
- Calibration orchestration and export.

**Receiver mode**
- QR scan/manual connection.
- Control/audio TCP clients and UDP time sync.
- Buffer management and native audio output.
- Receiver health reporting.
- Manual offset, volume and diagnostic screens.

Kotlin handles user interface and lifecycle. JNI connects to the shared C++ core. Oboe/AAudio handles low-latency output and microphone input where required.

### 4.3 iOS receiver

The iOS application is receiver-only in the current scope. Swift handles interface, permission and audio-session lifecycle. AVAudioEngine renders output. An Objective-C++ bridge adapts the shared C++ core. iOS must implement the same protocol semantics and timeline rules as Android receivers.

## 4.4 Platform transport boundary

The shared C++ core owns wire encoding/decoding, incremental TCP framing, bounded frame-queue primitives and time-sync request correlation. It does not own operating-system sockets. Android/Kotlin and iOS/Swift adapters own bind/connect/read/write/close operations and call the core from their network/control threads.

Each TCP connection has its own `FrameStreamDecoder`. Control and audio traffic use separate bounded queues and budgets so bulk audio cannot consume control capacity. Queue insertion is non-blocking and rejects the newest frame when full; the adapter must record the rejection and apply an explicit backpressure or disconnect policy. Do not silently drop time-sync/control messages or route any network queue through the audio callback.

The time-sync client tracks a bounded number of outstanding sequence IDs, validates response version/session/receiver/echoed t1, rejects duplicate or unsolicited responses, and expires stale requests. Its accepted four-timestamp exchange converts directly to the shared estimator's `SyncSample`. The client is owned by one network/control thread; platform adapters synchronize access if their callback model requires it.

### 4.5 Receiver control-session state

The master maintains one `ReceiverSession` per authenticated control connection. Its enforced path is:

`AwaitingHello → AwaitingAudioHello → Synchronizing → Ready → Armed → Playing/Paused`

- HELLO is accepted only once. Protocol version, session token and configured platform support are checked before WELCOME is exposed. Version, token or platform failures close the session.
- AUDIO_HELLO must carry the session and receiver IDs assigned in WELCOME. Either mismatch closes the connection.
- READY is accepted only after AUDIO_HELLO and only when the receiver reports a stable clock, at least the configured buffer depth, and residual standard deviation no greater than the configured limit. Failed readiness checks leave the receiver in Synchronizing.
- The master arms only a Ready receiver. START, PAUSE, RESUME, SEEK and delay-change anchors are accepted only in their valid states and with consistent playing flags.
- A degraded receiver returns to Synchronizing and must pass the complete READY gate again before it can be armed. LEAVE and explicit close are terminal.

The session object is single-owner-thread state; the platform adapter must serialize events for each connection. Buffer and clock-quality thresholds are configuration, not hard-coded protocol truths. This state machine validates lifecycle and identity but does not authenticate the audio transport cryptographically or replace socket-level timeout and disconnect handling.

### 4.6 Kotlin/JVM socket transport module

The current `android/transport` module is a pure Kotlin/JVM adapter built on Java networking APIs. It can be consumed by a future Android application, but it is not an Android application module and has no manifest, UI, foreground service, permissions or JNI wiring.

- `TcpFrameServer` provides the master-side listener; `TcpFrameConnection.connect` provides the receiver-side client. Each accepted connection owns its own length-prefixed frame reader, validates the 4-byte little-endian length before allocating the body, and enforces the 65,536-byte frame maximum.
- TCP reads are blocking and distinguish clean EOF between frames from truncated frames. TCP writes are synchronized per connection. Connect, accept and read timeouts are configurable; TCP_NODELAY and keepalive are enabled.
- `UdpTimeSyncServer` receives fixed 38-byte v1 datagrams and returns the peer endpoint for the response. `UdpTimeSyncClient` uses a connected UDP socket so responses from another endpoint are filtered.
- All socket operations run on dedicated I/O threads. This adapter does not create a send queue: callers must use bounded outbound queues and keep network I/O out of the audio callback.
- Kotlin currently duplicates the small TCP frame envelope parser because the C++ core has no JNI bridge yet. Keep it byte-compatible with `core/src/protocol.cpp` and `protocol/vectors/wire_protocol.json`; the UDP adapter validates datagram size only and delegates packet semantics to the shared protocol/session layer.

The Gradle `check` task runs loopback TCP/UDP integration tests, including fragmented and coalesced TCP frames, oversized/truncated TCP frames, malformed UDP sizes and a request/response exchange. Passing loopback tests proves local socket behavior only; it does not prove Android permission/lifecycle behavior, Wi-Fi peer connectivity or synchronization on phones.

## 5. Data and clock domains

The system has at least three clock domains per receiver:

1. **Master monotonic time:** authoritative session timeline.
2. **Receiver OS monotonic time:** used for local scheduling and estimated mapping to master time.
3. **Receiver audio hardware clock:** represented by output frame position and native timestamps.

These are not interchangeable. The implementation must keep LocalTime and MasterTime as distinct types and map audio frame position to local time before converting to master time.

The stream timeline maps sample index to master presentation time. For sample n:

**T(n) = Tₐ + (n − nₐ) / Fs**

The active anchor A defines a master timestamp, sample index and playing flag. All receivers follow the same anchor sequence.

## 6. Master-side data flow

1. User starts a session.
2. Master creates a session ID and random session token.
3. Master exposes the local IP/port and token as a QR payload.
4. Receivers authenticate and open independent control and audio connections.
5. Master answers receiver time-sync requests.
6. Master obtains audio from a local file or supported Android playback capture.
7. The source is decoded or captured into PCM frames.
8. The chunker assigns monotonically increasing sample indices.
9. Audio chunks enter bounded per-receiver send queues.
10. The group controller waits for readiness, computes a common start deadline and sends an anchor.
11. The master collects receiver reports and emits control actions where necessary.
12. Telemetry is stored locally and can be exported by the user.

A slow or disconnected receiver must not block the master source producer or the send queues of healthy receivers.

## 7. Receiver-side data flow

1. Receiver scans the QR code or enters connection details.
2. Receiver opens control TCP and sends HELLO.
3. Master validates the token and returns WELCOME.
4. Receiver opens audio TCP and sends AUDIO_HELLO.
5. Receiver runs a join burst of time-sync exchanges.
6. The offset estimator calculates offset and skew; stability is reported.
7. Audio chunks are received, validated and decoded.
8. Chunks are inserted into the jitter buffer by sample index.
9. Receiver reports READY when sync and buffer requirements are satisfied.
10. Receiver receives the common timeline anchor and enters ARMED.
11. The audio engine outputs silence until the scheduled start, then renders from the appropriate sample position.
12. The DAC clock map and drift controller continuously track timing.
13. Receiver reports state and metrics at approximately 1 Hz.
14. On severe error, receiver becomes DEGRADED or MUTED and requests recovery.

## 8. Group start algorithm

The master must not start receivers when the first device is ready. It waits for every admitted receiver or applies an explicit admission policy.

For each receiver, estimate the preparation time from its required buffer, current buffer and observed fill rate. Then compute a future deadline with enough time for the slowest receiver and for the anchor message to arrive.

The existing design uses these initial values:
- Startup lead: twice the maximum receiver p95 RTT plus 300 ms.
- Additional margin: 500 ms.
- Receiver readiness: stable time estimate and required buffer level.

The formula and thresholds are tuning parameters and must be validated under measured network conditions. A receiver that misses the admission deadline must remain silent and either be excluded or join at a later anchor.

## 9. Audio and buffering architecture

- Master source is converted to 48 kHz stereo PCM16.
- Chunking uses 960 frames (20 ms) in v1.
- Each receiver has a sample-indexed ring buffer.
- The network thread is the producer; the audio callback is the consumer.
- The ring is preallocated and does not allocate during rendering.
- Duplicate chunks are ignored.
- Chunks behind the playback pointer are counted and dropped.
- Missing frames are filled with silence and counted.
- Buffer fill and gap statistics are reported.

TCP is the initial audio transport because the planned multi-second buffer can absorb ordinary retransmission delays and avoids implementing packet loss concealment in the first version. TCP head-of-line blocking remains a known risk and must be measured. Do not assume TCP is inherently low-latency under loss.

## 10. Drift correction architecture

The audio callback derives the expected sample position from:
- The current DAC frame position.
- The DAC-to-local-time mapping.
- The local-to-master clock mapping.
- The active timeline anchor.
- The receiver compensation value.

The drift controller compares expected position with the actual read pointer and emits a bounded correction decision. Heavy estimation stays on helper/control threads.

Current baseline:
- Small errors are ignored inside a dead band.
- Moderate errors are corrected by rate-limited single-frame slips.
- Large errors trigger a controlled hard resynchronization with a fade.
- Persistent error leads to DEGRADED and then MUTED if necessary.

The slip method is a prototype strategy. Evaluate audio artifacts and controlled sample-rate conversion before treating it as a final long-duration solution.

## 11. Acoustic calibration architecture

Digital timing cannot remove speaker and acoustic path differences. The calibration subsystem schedules a known chirp from each receiver, records it at a microphone placed at the chosen listening position, performs matched filtering and estimates arrival time.

For device i:
- Measure scheduled-to-arrival delay dᵢ over repeated trials.
- Reject low-confidence peaks.
- Use the median delay as the device estimate.
- Set compensation relative to the earliest measured device.
- Apply compensation consistently in the expected-sample calculation.

The result is valid only for the measured listening position and current physical phone placement. A train movement or phone relocation can invalidate it.

Manual per-device offset is the fallback if microphone-based calibration is unavailable.

## 12. Fault isolation and resilience

| Fault | Detection | System response |
|---|---|---|
| Short Wi-Fi interruption | TCP error, keepalive gap, buffer decline | Continue from buffered audio where possible; reconnect |
| Router loss | All receivers lose master | Play only remaining buffered audio, then stop cleanly |
| Clock instability | Estimator does not meet stable criteria | Keep receiver out of playback; expose diagnostics |
| Buffer underrun | Missing frames at read position | Insert silence, count gap and request recovery if repeated |
| Receiver drifts | Error and correction counters exceed policy | DEGRADED, then MUTED if necessary |
| Receiver rejoins | New HELLO and stable clock mapping | Send catch-up stream and schedule future entry |
| Audio route change | Native audio route callback | Rebuild mapping and resynchronize |
| Source capture denied | Capture API failure or no usable frames | Prompt to use local-file source |
| Master process exits | Keepalive timeout | Receivers drain buffer and stop cleanly |

## 13. Repository boundaries

The intended implementation layout is:

- core/: portable timing, buffer, protocol and controller library.
- android/: Kotlin app, native audio adapters and JNI bridge.
- ios/: Swift receiver and Objective-C++ bridge.
- sim/: Python reference model and network/clock simulator.
- protocol/: normative protocol specification and golden vectors.
- tools/: click-track generation, recording analysis and telemetry plotting.
- Docs2/: structured product and engineering documentation.
- docs/: existing design source documents, retained unchanged.

## 14. Architecture risks

1. Wi-Fi path asymmetry limits time-offset accuracy.
2. Native timestamp semantics differ by platform and device.
3. TCP retransmission can stall audio delivery during loss.
4. Android playback capture is source-policy dependent.
5. iOS background and local-network behavior requires physical-device validation.
6. Acoustic calibration relies on microphone placement, signal-to-noise ratio and a stable geometry.
7. Sample slips can introduce artifacts if used continuously.
8. The current device-count statements are inconsistent and must be resolved before scale testing.

## 15. Source documents

Primary source references: [docs/Sync Engine.md](../docs_original/Sync%20Engine.md) and [docs/System Structure.md](../docs_original/System%20Structure.md). This architecture document consolidates those sources; it does not replace or modify them.
