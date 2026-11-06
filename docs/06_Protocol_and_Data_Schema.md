# Protocol and Data Schema — Sync In It

**Document status:** Draft normative v1 proposal  
**Version:** 1.0  
**Last updated:** 2 November 2026

## Current Android implementation note — 6 November 2026

The Android preview currently exercises HELLO/WELCOME, AUDIO_HELLO, READY and start-ANCHOR over its active path, plus AUDIO_CHUNK over the separate audio connection and four-timestamp UDP requests/responses. Other control-message definitions in this document are protocol design/schema coverage; they should not be assumed to have complete app/UI workflows merely because their wire shape is specified.

The Android implementation caps the Host at eight Speakers, opens a 15-second cohort admission window, waits up to 45 seconds for READY, requires stable clock acquisition plus at least 3 seconds of contiguous PCM, retries READY every second, and sends one future start ANCHOR only after the cohort is ready. The Speaker buffer rolls only before ANCHOR and becomes strict after ANCHOR. The latest physical run did not reach READY, so this implementation path is not yet validated end-to-end. See [Current Implementation Status](11_Current_Implementation_Status.md).

## 1. Scope and terminology

This document defines the local network protocol and the data persisted by the apps. Sync In It has no cloud backend and no server-side relational database in the current architecture. The Android master is the session authority, but it is not a cloud service.

This document consolidates the protocol material in the original design. It intentionally resolves one conflict: the detailed frame definition in the system structure document is more complete than the short audio-header summary in the original engine document. Implementations should follow this document's detailed frame proposal, not the older abbreviated header.

### 1.1 Conventions

- Multi-byte wire fields are little-endian.
- Time values use signed 64-bit nanoseconds unless a field explicitly specifies another unit.
- MasterTime and LocalTime are different clock domains.
- SampleIdx is a signed 64-bit stream frame index starting at zero.
- Fs = 48,000 Hz in v1.
- A frame length excludes its own length prefix and includes type, flags and payload.
- All decoders must validate lengths before reading payloads.

## 2. Network endpoints

| Channel | Transport | Default port | Direction |
|---|---|---:|---|
| Control | TCP | 47800 | Bidirectional |
| Audio | TCP | 47801 | Master to receiver |
| Time sync | UDP | 47802 | Bidirectional request/response |

The Android Host currently binds control TCP to 47800 and UDP time sync to 47802. Its audio TCP listener is created on an available port (ephemeral port selection) and the chosen port is supplied in WELCOME. The 47801 value remains the draft/default port, not a guarantee that the current Android Host binds that fixed port. Each Speaker opens separate control and audio TCP connections and uses UDP for clock synchronization.

## 3. Session join payload

The QR payload proposal is:

**syit://1?h={master-ip}&p={control-port}&s={session-id-hex}&t={token-hex-32}**

Fields:
- h: local IPv4 or supported local address of the master.
- p: control TCP port.
- s: session ID represented in hexadecimal.
- t: 16-byte random token represented as 32 hexadecimal characters.

The URI is a connection invitation, not a public URL. Do not put personal information in it. Do not log the full token.

## 4. TCP framing

Control and audio TCP messages use length-prefixed framing:

| Field | Size | Description |
|---|---:|---|
| length | 4 bytes | Unsigned frame length in bytes that follow |
| type | 1 byte | Message type |
| flags | 1 byte | Message flags |
| payload | Variable | Message-specific payload |

Maximum frame size in the current draft: 65,536 bytes.

Receivers must:
1. Read the full 4-byte length prefix.
2. Reject lengths smaller than the fixed header or greater than the maximum.
3. Read exactly the indicated number of bytes.
4. Validate type-specific payload length and fields.
5. Ignore unknown message types only when they are safely length-framed.
6. Close or report malformed frames; never attempt unsafe partial decoding.

A TCP read is an arbitrary byte fragment, not a frame boundary: it may contain part of the four-byte prefix, part of a payload, one complete frame, or several frames. Each connection must own one incremental framer that emits every complete frame and retains only the incomplete trailing frame. The maximum applies to each frame, not to the aggregate size of one socket read.

The framer validates the length as soon as all four prefix bytes are available, before accepting the body. Its retained partial-frame storage is bounded to 4 + 65,536 = 65,540 bytes per connection. A length below two or above the maximum is a protocol error; close the connection rather than scanning for a new frame boundary. Do not call the framer from the real-time audio callback.

## 5. Control message catalogue

All fields in this table are in wire order. u = unsigned, i = signed, and byte arrays are fixed or length-prefixed as indicated. The `model` and `detail` strings are UTF-8 byte sequences with a one-byte byte-length prefix; they are limited to 255 encoded bytes. Control payloads must have exactly the length implied by their fields. Boolean fields (`syncStable`, `playing`, and `mute`) accept only 0 or 1. ANCHOR kind must be 0–4, and VOLUME gain must be 0–100.

| Type | Name | Direction | Payload |
|---:|---|---|---|
| 0x01 | HELLO | Receiver → Master | protoVersion u16, token[16], platform u8, appVersion u16, capabilities u32, modelLen u8, model[modelLen] |
| 0x02 | WELCOME | Master → Receiver | result u8, receiverId u16, sessionId u32, fs u32, channels u8, chunkFrames u16, codec u8, audioPort u16, timePort u16, startupLeadMs u16 |
| 0x03 | AUDIO_HELLO | Receiver → Master | sessionId u32, receiverId u16 |
| 0x04 | READY | Receiver → Master | bufferedMs u32, syncStable u8, residualStdUs u32 |
| 0x05 | REPORT | Receiver → Master, about 1 Hz | state u8, bufferedMs u32, skewPpmX10 i16, residualStdUs u32, rttMedianUs u32, outputLatencyEstUs i32, errMeanUs i32, errMaxUs u32, slips u32, hardResyncs u16, underruns u16, gapFrames u32 |
| 0x06 | ANCHOR | Master → Receiver | kind u8, masterTimeNs i64, sampleIdx i64, playing u8, crossfadeMs u16 |
| 0x07 | MUTE | Master → Receiver | mute u8, atMasterTimeNs i64 |
| 0x08 | SET_COMP | Master → Receiver | compensationNs i32 |
| 0x09 | RESYNC | Master → Receiver | reason u8, resumeFromSampleIdx i64 |
| 0x0A | LEAVE | Both | reason u8 |
| 0x0B | KEEPALIVE | Both, about 1 Hz | counter u32 |
| 0x0C | ERROR | Both | code u16, detailLen u8, detail[detailLen] |
| 0x0D | VOLUME | Master → Receiver | gain u8 (0–100), atMasterTimeNs i64 |
| 0x20 | CALIB_CHIRP | Master → Receiver | chirpId u16, playAtMasterTimeNs i64, f0Hz u32, f1Hz u32, durationMs u16, gain u8 |
| 0x21 | CALIB_ACK | Receiver → Master | chirpId u16, status u8 |

The `platform` field in HELLO is an enum: `1 = Android`, `2 = iOS`. Implementations may reject values they do not support.

### 5.1 ANCHOR kinds

| Value | Name | Meaning |
|---:|---|---|
| 0 | start | Begin playback at the supplied time/sample |
| 1 | pause | Stop at the supplied time/sample |
| 2 | resume | Resume at the supplied time/sample |
| 3 | seek | Switch to a new sample position |
| 4 | delay-change | Apply a coordinated delay/timeline change |

Unknown anchor kinds must be rejected or ignored safely according to protocol compatibility rules; they must not be treated as start.

### 5.1.1 Android preview start coordination

The current Android preview uses ANCHOR kind `0` (start) as a one-shot group-start command:

1. The Host admits Speakers for a 15-second window after the first Speaker completes admission.
2. Every admitted Speaker reports READY after the clock estimator is stable and at least 3 seconds of contiguous PCM is buffered. The Speaker retries READY every second until it receives ANCHOR; duplicate READY is idempotently accepted while the receiver is READY.
3. After the admission window closes and every admitted Speaker is READY, the Host selects one shared sample index already inside the buffered PCM range and one future Host monotonic timestamp.
4. The Host arms each admitted native receiver session, applies the same start anchor to each session, and sends the encoded ANCHOR over each Speaker's control TCP connection.
5. Each Speaker maps the Host timestamp into its local monotonic clock using the latest four-timestamp offset estimate, seeks its jitter-buffer cursor to the shared sample index, primes AudioTrack, and calls `AudioTrack.play()` at the scheduled local target.

The Android preview uses a 20-second sample-indexed PCM ring buffer per Speaker. Before the shared start ANCHOR, the buffer rolls its pre-roll cursor forward when necessary so it retains the newest contiguous audio while clock acquisition completes; after the ANCHOR it becomes strict and will not silently skip playback samples. The Host accepts Speakers for 15 seconds from the first successful admission, then waits up to 45 seconds for the admitted cohort to reach READY. A Speaker that misses the readiness deadline is disconnected with an explicit diagnostic; ready peers can still start together. The cohort is fixed after the admission window closes; restart hosting to form another group. This is a software scheduling milestone, not a claim of sample-accurate DAC or acoustic alignment. Hardware output timestamp mapping, output-latency compensation, drift control and acoustic measurements are outside this milestone.

### 5.2 Receiver join and playback state

The master enforces the following per-control-connection states:

| State | Accepted next message/event | Result |
|---|---|---|
| AwaitingHello | HELLO | Validate version, token and platform; return WELCOME |
| AwaitingAudioHello | AUDIO_HELLO | Bind the audio channel to the WELCOME session/receiver IDs |
| Synchronizing | READY | Accept only when clock is stable and configured buffer/residual gates pass |
| Ready | Arm command | Enter Armed |
| Armed | START anchor | Enter Playing |
| Playing | PAUSE anchor | Enter Paused |
| Paused | RESUME anchor | Enter Playing |
| Playing/Paused | SEEK or delay-change anchor | Preserve the current playback state |
| Synchronizing/Ready/Armed/Playing/Paused | Degradation event | Enter Degraded |
| Degraded | Begin resynchronization | Return to Synchronizing; require READY again |
| Any active state | LEAVE or connection close | Enter Closed |

Invalid ordering is rejected. Version, token, platform, session-ID or receiver-ID mismatches close the connection. Minimum buffered duration and maximum clock residual are master configuration values; the protocol does not impose one universal threshold. The session token is an admission credential only and does not provide channel encryption or cryptographic integrity.

## 6. Audio message

Audio uses message type 0x80 over the audio TCP connection. It uses the common 4-byte length prefix, type byte and outer message-flags byte; the table below defines the audio payload. The payload's `flags` field is distinct from the outer message-flags byte. The typed payload header is exactly 21 bytes, followed by PCM data.

| Field | Size | Description |
|---|---:|---|
| sessionId | 4 bytes | Session identifier |
| seq | 4 bytes | Chunk sequence |
| sampleIdx | 8 bytes | First frame index in the chunk |
| frames | 2 bytes | Number of audio frames |
| codec | 1 byte | `0` = PCM16 in v1 |
| channels | 1 byte | Number of channels |
| flags | 1 byte | Chunk flags |
| payload | Variable | Encoded audio |

Flags:
- bit 0: first chunk after a detected gap.
- bit 1: silence chunk; payload may be empty and receiver supplies zero samples.
- Other bits are reserved and ignored in compatible minor versions.

For PCM16 stereo, a 960-frame chunk has 3,840 payload bytes and represents 20 ms at 48 kHz. A silence chunk has an empty payload and is expanded to zero-valued samples. A receiver inserts audio by sampleIdx, not by arrival order. Duplicates are ignored. Late chunks behind the playback pointer are dropped and counted. The portable C++ core implements the AUDIO_CHUNK encoder/decoder and a bounded sample-indexed PCM16 jitter buffer. The Android app receives AUDIO_CHUNK frames over the separate audio TCP channel, validates/inserts them through JNI, and writes buffered samples through a dedicated AudioTrack writer thread. The Host currently generates one 1 kHz timing-click stream and fans identical sample-indexed chunks out through bounded per-Speaker queues. This is not user-media playback. The latest supplied physical run completed audio-channel handshakes but did not establish that the Speaker's contiguous buffer or clock gate reached READY.

## 7. UDP time-sync packet format

### 7.1 Request

| Field | Size |
|---|---:|
| magic | u16, ASCII ST |
| version | u8 |
| type | u8, value 1 |
| sessionId | u32 |
| receiverId | u16 |
| seq | u32 |
| t1 | i64 |
| padding | To response packet size |

### 7.2 Response

| Field | Size |
|---|---:|
| magic | u16, ASCII ST |
| version | u8 |
| type | u8, value 2 |
| sessionId | u32 |
| receiverId | u16 |
| seq | u32 |
| t1 | i64 |
| t2 | i64 |
| t3 | i64 |

The request is padded to the response size to reduce one source of packet-size asymmetry. Packets with wrong magic, version, session ID or receiver ID are rejected.

The receiver records t1 before send and t4 on response receipt. The master records t2 on request receipt and t3 immediately before response send.

### 7.3 Version 1 datagram rules

- Every time-sync datagram is exactly 38 bytes.
- The magic bytes are the ASCII bytes `S`, `T` in that order.
- The time-sync packet version byte is `1` for protocol v1.
- The request's defined fields occupy 22 bytes; the sender appends 16 zero-filled padding bytes. Receivers ignore these padding bytes in v1.
- The response's defined fields occupy all 38 bytes.
- All multibyte fields use the little-endian convention from Section 1.1.

## 8. Protocol versioning and error codes

Protocol version is u16: high byte is major, low byte is minor. A major mismatch is incompatible. Minor additions must preserve existing field meanings and parsing.

| Code | Name | Meaning |
|---:|---|---|
| 1 | E_PROTO_VERSION | Incompatible major version |
| 2 | E_TOKEN | Invalid session token |
| 3 | E_SESSION_FULL | Receiver capacity reached |
| 4 | E_NOT_READY | Command received in an invalid state |
| 5 | E_CLOCK_UNSTABLE | Clock estimator did not stabilize |
| 6 | E_CATCHUP_FAIL | Receiver could not reach the timeline |
| 7 | E_AUDIO_DEVICE_LOST | Output route unavailable |
| 8 | E_CAPTURE_DENIED | Master could not capture source audio |
| 9 | E_NET_LOST | Keepalive timeout |

## 9. Session and in-memory data model

No cloud database is required. The following objects are held in memory during a session.

### 9.1 Session

| Field | Type | Notes |
|---|---|---|
| sessionId | u32 | Session identifier |
| token | 16 bytes | Random secret; memory-only, do not log |
| masterAddress | local address | LAN address |
| createdAtMasterNs | i64 | Master monotonic time |
| sampleRate | u32 | 48,000 in v1 |
| channels | u8 | 2 in v1 |
| chunkFrames | u16 | 960 in v1 |
| codec | enum | PCM16 in v1 |
| groupDelayMs | u16 | Live mode delay |
| timeline | Anchor[] | Ordered playback anchors |
| state | enum | IDLE, LOBBY, PREPARING, PLAYING, PAUSED, CALIBRATING, ENDED |

### 9.2 Receiver record

| Field | Type | Notes |
|---|---|---|
| receiverId | u16 | Assigned by master |
| platform | enum | Android or iOS |
| model | string | Optional diagnostic label |
| state | enum | Receiver state machine |
| syncOffsetNs | i64 | Current master/local offset estimate |
| skewPpm | float | Regression estimate |
| rttStats | object | Min/median and recent distribution |
| residualStdNs | i64 | Estimator residual |
| bufferMs | u32 | Available audio duration |
| outputLatencyNs | i64 | Estimate, not acoustic ground truth |
| compensationNs | i32 | Calibration/manual compensation |
| slips | u32 | Correction counter |
| hardResyncs | u16 | Hard resync count |
| underruns | u16 | Underrun count |
| lastSeenMasterNs | i64 | Master-side observation |

## 10. Local persistence schema

The app only needs local settings, optional calibration profiles and diagnostics. This is not a remote database schema.

| Record | Suggested fields | Storage rule |
|---|---|---|
| AppSettings | rolePreference, localVolume, channelMode, theme/accessibility preferences | Local key-value storage |
| NetworkSettings | lastUsedAddress (optional), preferred router notes | Store only what improves UX; no credentials in plaintext |
| DeviceCalibration | deviceModel, audioRoute, compensationNs, measuredAt, confidence, layoutNote, appVersion | Local structured file or key-value store; mark stale after route/layout changes |
| SessionDiagnostics | session metadata, receiver summaries, exportedAt, schemaVersion | User-exported local file |
| EventLog | timestampNs, eventType, receiverId, details | Append-only during the session; rotate/export |
| TelemetrySample | time values, RTT, skew, buffer, error and counters | CSV or compact local file |

Do not persist the session token by default. Do not persist audio payloads. Calibration must not be reused as universally valid: it is tied to device model, route and physical layout.

## 11. Telemetry schema

Suggested per-second CSV columns:

timestamp_local_ns, timestamp_master_est_ns, state, theta_ns, skew_ppm, rtt_median_us, rtt_min_us, residual_std_us, samples_used, buffer_ms, err_mean_us, err_max_us, slips, hard_resyncs, underruns, gap_frames, dac_rate_hz, compensation_ns, battery_pct, temperature_c.

Event types include:
- STATE_CHANGE
- HARD_RESYNC
- UNDERRUN
- TIMESTAMP_REJECTED
- KEEPALIVE_LOST
- ANCHOR_RX
- CALIB_RESULT
- ERROR
- AUDIO_ROUTE_CHANGED

Store explicit units in field names. Never mix nanoseconds and microseconds in the same untyped field.

## 12. Timing and protocol test vectors

### 12.1 M0 timing vectors

The shared M0 reference vectors are stored in `protocol/vectors/m0_timing.json`. They are consumed directly by the Python tests and converted to a generated C++ header by the CMake build. They cover:
- Four-timestamp RTT and offset, including negative-floor behavior.
- Lowest-RTT offset/skew regression, invalid/expired/future sample filtering, insufficient skew samples and selected-fit sample span.
- Timeline mapping, paused anchors and ties-to-even rounding.
- DAC frame/time conversion with positive and negative ppm drift.
- Drift-controller dead-band, one-frame slip and hard-resync thresholds.

These vectors establish Python/C++ algorithm parity; they are not wire-message serialization vectors or proof of acoustic synchronization.

### 12.2 Wire-protocol vectors and typed payload validation

The shared vector file `protocol/vectors/wire_protocol.json` provides fixtures for:
1. Valid and invalid HELLO/WELCOME frames.
2. Every control message type.
3. Audio PCM16 chunk with known sample indices.
4. Silence chunk and gap flag.
5. UDP request/response with known timestamps.
6. Unknown message type and unknown minor-version flags.
7. Truncated frame, invalid length and oversized frame.
8. Little-endian edge values and signed timestamp handling.

Both Python reference code and C++ encoders/decoders pass the shared byte-level vectors. Typed control payload codecs validate the documented typed payload shapes and boundary constraints. TCP stream accumulation is implemented in Python and C++ as a bounded incremental framer. It handles partial headers and payloads, multiple frames per read, per-frame length validation and one retained partial frame per connection. Audio chunk parsing and PCM16 jitter-buffer primitives are implemented in `core/audio.h` and `core/audio.cpp`; Android transport-to-buffer integration, generated test-stream fan-out, AudioTrack output and a common start-anchor path are also present. These paths still require physical multi-phone validation. User-selected audio, integrated drift correction, and acoustic calibration remain out of scope for the current preview.
## 13. Outstanding protocol questions

- Preview capacity is 8 Speakers plus one Host (9 devices total). The product target remains 6–10 phones total; M9 must validate the intended 10-phone total before any cap change. See ADR-021.
- Whether sessionId remains 32-bit for the expected session lifetime and collision model.
- Whether token authentication is upgraded to challenge-response and encrypted transport before a public/less-trusted deployment.
- Exact error semantics for late join and group admission.
- Whether output latency estimate is serialized as receiver-reported diagnostics only or used in group control.
- Whether future Opus framing needs an explicit codec configuration message.

## 14. Source documents

This schema is based on section 8 and the telemetry sections of [docs/System Structure.md](../docs_original/System%20Structure.md), plus the protocol summary in [docs/Sync Engine.md](../docs_original/Sync%20Engine.md). Where the summary is abbreviated, the detailed message definitions in this document are the implementation baseline pending protocol review.
