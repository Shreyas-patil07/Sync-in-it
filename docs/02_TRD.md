# Technical Requirements Document — Sync In It

**Document status:** Draft technical baseline  
**Version:** 1.0  
**Last updated:** 1 November 2026

## 1. Technical objective

Define the platform, runtime, timing, networking, audio, resource and development constraints required to implement Sync In It. This document specifies technical requirements; architecture rationale is in [03_Architecture.md](03_Architecture.md), while wire-level definitions are in [06_Protocol_and_Data_Schema.md](06_Protocol_and_Data_Schema.md).

## 2. Platform and toolchain baseline

| Area | Planned technology | Status |
|---|---|---|
| Android application | Kotlin | Selected in current design |
| Android audio output | Oboe over AAudio where available | Selected; device behavior to verify |
| Android playback capture | MediaProjection / AudioPlaybackCapture | Conditional; capture may be blocked |
| Android file decoding | MediaExtractor + MediaCodec or equivalent native decoder | Planned |
| Android/native boundary | JNI | Planned |
| Shared timing engine | Portable C++17 | Selected |
| iOS receiver | Swift + AVAudioEngine | Planned |
| iOS/native boundary | Objective-C++ bridge | Planned |
| Simulator/reference model | Python | Selected |
| Native build | CMake | Planned |
| Native unit tests | GoogleTest (preferred) or Catch2 | Framework choice to confirm |
| Source control/CI | GitHub + GitHub Actions | Planned |
| Audio codec v1 | PCM16 | Selected |
| Later codec | Opus | Planned after PCM timing baseline |

### 2.1 Provisional platform minimums

The detailed design currently proposes Android API 26 as the receiver baseline, API 29 or later for master playback-capture features, and iOS 15 or later for receivers. These are provisional. Verify the required audio timestamp, foreground-service, local-network and background-audio behavior on the actual supported OS versions before freezing these minimums.

## 3. Audio requirements

| Parameter | Baseline |
|---|---|
| Sample rate | 48,000 Hz |
| Stream channels | 2 (stereo) |
| Sample representation | Signed PCM16 |
| Chunk duration | 20 ms |
| Frames per chunk | 960 |
| Bytes per PCM16 stereo chunk | 3,840 bytes |
| Receiver rendering | Mono downmix by default; optional L/R channel selection |
| Codec v1 | PCM pass-through |
| Codec extension | Opus, after timing validation |

The first implementation must keep the audio path deliberately simple. Do not add a codec or resampler before proving scheduled playback and drift behavior with PCM.

### 3.1 Sample timeline

For sample rate Fs = 48,000 Hz, sample n has a target time derived from the active timeline anchor:

**T(n) = Tₐ + (n − nₐ) / Fs**

An anchor consists of master time, sample index and playing state. Start, pause, resume, seek and coordinated delay changes append anchors; receivers must not invent an independent playback clock.

## 4. Clock and synchronization requirements

1. Use monotonic clocks only for scheduling. Wall-clock time is not a playback clock.
2. Represent local monotonic time and master monotonic time as distinct types in the C++ core.
3. Use a four-timestamp exchange: receiver sends t1, master receives at t2, master sends at t3, receiver receives at t4.
4. Calculate RTT and offset using the equations below.

   RTT = (t4 − t1) − (t3 − t2)

   theta = ((t2 − t1) + (t3 − t4)) / 2

   Convention: estimated master time = receiver local time + theta.

5. Use lowest-RTT filtering and robust offset statistics; reject negative or implausible RTT samples.
6. Estimate clock skew using regression over a moving window.
7. Track the audio hardware clock separately from the operating-system monotonic clock.
8. Do not mark a receiver ready until the clock estimator meets its stability conditions.
9. Preserve the last valid clock mapping during temporary poor samples; do not replace it with a high-delay outlier.

### 4.1 Initial estimator parameters

| Parameter | Initial value |
|---|---:|
| Join burst | 20 requests, 50 ms apart |
| Steady interval | 2 s |
| Unstable retry interval | 500 ms |
| Estimator window | 60 s |
| Lowest-RTT sample fraction | 25% |
| Minimum samples for skew fit | 8 |
| Skew clamp | ±200 ppm |
| Stability residual | <1 ms |
| Stable sample span | ≥10 s |

These values are tuning parameters, not proven properties of every Wi-Fi chipset.

## 5. Audio output clock mapping

- Read output frame-position/time pairs from the native audio API.
- Poll timestamps on a helper thread, initially every 200 ms.
- Keep a 30-second regression window.
- Fit local presentation time as a function of audio frame position.
- Publish a consistent mapping snapshot to the audio callback.
- Reject timestamp discontinuities and count them.
- The callback must not run the regression itself.

The implementation must establish what each platform timestamp actually means (queued, presented, or hardware frame position). Never treat an API timestamp as acoustic sound-arrival time without measurement.

## 6. Drift-control requirements

The drift controller compares the timeline-expected sample index to the receiver's actual read position.

Initial thresholds from the design:

| Error | Initial behavior |
|---|---|
| ≤5 frames (about 0.1 ms) | Dead band; no correction |
| 5–240 frames (about 0.1–5 ms) | Rate-limited one-frame slip |
| >240 frames (about 5 ms) | Hard resynchronization with a short fade and holdoff |

These are starting values to test, not universal perceptual thresholds. The design currently favors simple slips for the initial prototype. A later controlled asynchronous sample-rate converter may offer better audio quality for sustained fine correction and must be evaluated before committing to it.

A receiver with persistent error is marked DEGRADED. If it cannot safely meet the timeline, it is muted and resynchronized rather than intentionally emitting obviously late audio.

## 7. Network requirements

### 7.1 Network environment

- Private Wi-Fi LAN; no internet dependency.
- Dedicated dual-band router preferred.
- 5 GHz preferred, 2.4 GHz fallback.
- Client isolation disabled.
- All participants on the same IP subnet unless routing behavior is explicitly tested.
- Router failure and temporary congestion must be included in fault testing.

### 7.2 Connections

| Channel | Transport | Default port | Purpose |
|---|---|---:|---|
| Control | TCP | 47800 | Session, state, reports, anchors |
| Audio | TCP | 47801 | Timestamped audio chunks |
| Time sync | UDP | 47802 | Four-timestamp exchanges |

Each receiver opens separate control and audio TCP connections and uses UDP for time synchronization. Control traffic must not be queued behind a large audio transfer.

### 7.3 Bandwidth and buffering

- PCM16 stereo at 48 kHz: 1.536 Mbps per receiver, excluding protocol overhead.
- Ten receiver streams: approximately 15.36 Mbps payload aggregate.
- Receiver jitter-buffer capacity: 8 seconds in the current draft.
- Live-mode group delay D: initial default 3 seconds; configurable between 2 and 10 seconds.
- The master maintains a short stream-history ring for rejoin; current design estimates approximately 12 seconds.

The real capacity limit depends on router airtime, retransmissions, interference and per-client performance. Theoretical throughput is not a substitute for a ten-device test.

## 8. Protocol and compatibility requirements

- TCP messages use length-prefixed framing.
- Multi-byte numeric fields are little-endian.
- Maximum TCP frame size: 64 KiB in the current draft.
- Receivers ignore unknown message types when safely length-framed.
- Major protocol mismatch is rejected; additive minor changes must remain backward-compatible.
- Audio chunks are indexed by stream sample position; arrival order is not playback order.
- Duplicate chunks are ignored and late chunks are counted/dropped.
- The normative v1 draft is in [06_Protocol_and_Data_Schema.md](06_Protocol_and_Data_Schema.md).

## 9. Runtime and real-time requirements

The receiver's audio callback runs every few milliseconds depending on hardware burst size. It must perform bounded work and remain independent of network delays.

Forbidden inside the audio callback:
- Heap allocation or deallocation.
- Mutex waits, blocking locks or condition-variable waits.
- Network operations or file I/O.
- Logging, console output or telemetry serialization.
- JNI, Swift, Objective-C or other cross-runtime calls.
- Regression, sorting, median calculations or other unbounded work.

Permitted work includes bounded buffer reads/writes, atomic or seqlock snapshot reads, small deterministic arithmetic and sample correction.

All queues crossing from producer/network threads to the audio callback must be bounded and have explicit overflow behavior.

## 10. Threading requirements

| Execution context | Responsibility |
|---|---|
| Audio callback | Read jitter buffer, apply current sync parameters, render output |
| DAC timestamp poller | Query output timestamps and update clock fit |
| Time-sync thread | Send/receive UDP and update offset estimator |
| Audio network thread | Receive and decode audio, fill jitter buffer |
| Control thread | State machine, reports and session commands |
| Master capture/decoder | Produce PCM and assign sample indices |
| Master sender | Fan out bounded per-receiver audio queues |
| Telemetry writer | Drain event/metric buffers and write files |
| UI thread | Display snapshots and accept user actions |

## 11. Resource requirements

| Resource | Planning estimate |
|---|---|
| Five seconds of stereo PCM16 | 0.96 MB |
| Eight seconds of stereo PCM16 | 1.536 MB |
| Twelve seconds of stereo history | 2.304 MB |
| CPU | Audio callback target below 20% of its period |
| Network | Roughly 1.536 Mbps payload per receiver for PCM |
| Storage | Logs and settings only by default; no persistent audio recording |

The estimates exclude object overhead, queue metadata, decoder buffers and OS-level buffering.

## 12. Permissions and lifecycle

### Android

Verify current requirements for microphone access, MediaProjection, foreground service type, notifications, camera/QR scanning, wake locks, Wi-Fi state and battery optimization. Ask for permissions at the point they are needed, with clear rationale.

The master must use a foreground service while hosting playback. Wake and Wi-Fi locks must be acquired only while needed and released on shutdown.

### iOS

The receiver requires local-network access and background audio configuration appropriate to continuous playback. Handle audio interruptions, route changes and app lifecycle transitions. These behaviors must be tested on real iPhones.

## 13. Engineering conventions

- C++ core uses explicit LocalTime, MasterTime and SampleIdx types.
- Wire encoders/decoders are pure functions and bounds-checked.
- No implicit unit conversion between nanoseconds, microseconds, samples and milliseconds.
- Use fixed-width integers in protocol structures.
- Configuration constants live in one typed configuration definition.
- Platform adapters do not duplicate clock-estimation or drift-control algorithms.
- Add golden test vectors before changing protocol or estimator behavior.
- Keep measured values separate from estimates and design targets.
- Code-style details are maintained in the implementation guide until a standalone style guide is justified.

## 14. Open technical decisions

1. Final supported Android and iOS minimum versions.
2. Whether group capacity means 10 total phones or 10 receivers plus master.
3. Actual initial test-device inventory and whether a Mac/iPhone is available.
4. Whether the audio sender uses one event loop or a worker per receiver under load.
5. Whether the eventual fine drift correction uses controlled resampling.
6. Whether production networking upgrades from token-protected plain TCP/UDP to authenticated encryption.
7. Which iOS timestamp source provides the most useful mapping to presentation time.

## 15. Source documents

This TRD expands the baseline in [docs/System Structure.md](../docs/System%20Structure.md) and [docs/Sync Engine.md](../docs/Sync%20Engine.md). It is a planning document; it does not assert that these APIs or performance levels have already been validated.
