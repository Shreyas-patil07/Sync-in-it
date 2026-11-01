# Product Requirements Document — Sync In It

**Document status:** Draft baseline  
**Version:** 1.0  
**Last updated:** 1 November 2026  
**Scope:** Product requirements for the offline multi-phone audio synchronization system

## 1. Purpose

Sync In It is intended to turn a group of ordinary smartphones into a coordinated loudspeaker group. One Android phone acts as the master, audio source and authoritative time reference. Other Android and iOS phones receive the same audio and render it against a shared playback timeline.

The initial deployment context is one train coach, where passengers can place several phones around a listening area. The system must operate over a private local Wi-Fi network without internet access, cloud services, accounts or dedicated loudspeakers.

The product objective is synchronized acoustic output, not merely simultaneous packet delivery or simultaneous playback commands.

## 2. Problem statement

Independent phones have different operating-system scheduling, monotonic clocks, audio hardware clocks, output pipeline latency, speaker characteristics and physical distances from the listener. Starting playback when a packet arrives causes audible echoes and drift.

Sync In It therefore distributes audio ahead of time, estimates clock relationships, schedules each receiver against a common timeline, tracks each output device's clock and corrects drift. Optional acoustic calibration compensates for device-specific output delay at one selected listening position.

## 3. Product goals

1. Allow one Android master to create and manage an offline audio session.
2. Allow Android and iOS receivers to join using a QR code or manual connection details.
3. Deliver a common audio stream to all admitted receivers.
4. Schedule the same stream sample against one master timeline.
5. Maintain synchronization during sustained playback and normal local-network variation.
6. Allow a receiver to disconnect and rejoin without restarting healthy receivers.
7. Provide manual per-device offset adjustment and a measured acoustic-calibration workflow.
8. Expose enough telemetry to diagnose clock, buffer, network and playback failures.
9. Work without a public internet connection or cloud backend.

## 4. Non-goals for the first product release

- Protected or DRM-restricted audio capture.
- Multi-coach or multi-router synchronization.
- Video synchronization, spatial audio or multi-room discovery.
- A cloud account system, cloud database or remote control service.
- Guaranteed microsecond-level synchronization.
- Guaranteed T3 performance across arbitrary phones, speaker placements or moving listening positions.
- A production-grade encrypted media protocol in the first laboratory prototype.
- An iOS master. The current plan defines iOS as a receiver platform.

## 5. Users and primary scenarios

### 5.1 Session owner

The session owner operates the Android master. They create a session, select a local file or attempt supported live audio capture, admit receivers, monitor readiness, start or pause playback, calibrate and export diagnostics.

### 5.2 Receiver user

The receiver user opens the app, scans the master's QR code, grants the required local-network/audio permissions, waits for synchronization and buffer readiness, and leaves the app active during playback.

### 5.3 Primary use cases

| ID | Use case | Expected result |
|---|---|---|
| UC-01 | Create a session | Master creates a new session and displays join information |
| UC-02 | Join a session | Receiver authenticates to the session and starts clock synchronization |
| UC-03 | Prepare playback | Receiver receives audio and reaches the required buffer level |
| UC-04 | Start group playback | Every ready receiver follows the same scheduled timeline anchor |
| UC-05 | Monitor health | Master can see receiver state, clock quality, buffer level and errors |
| UC-06 | Pause/resume a file | All receivers apply a shared future timeline anchor |
| UC-07 | Rejoin | A disconnected receiver catches up and joins at a future boundary |
| UC-08 | Calibrate | The system measures acoustic arrival and applies a per-device compensation |
| UC-09 | Diagnose | User exports telemetry and event logs for a test run |
| UC-10 | Handle unavailable capture | Master explains the restriction and offers local-file playback |

## 6. Functional requirements

Priority labels: **P0** = required for the first demonstrable system; **P1** = required for the broader mixed-platform product; **P2** = later enhancement.

| ID | Requirement | Priority |
|---|---|---|
| FR-01 | Create and close an offline session on Android | P0 |
| FR-02 | Generate a QR payload containing master address, port, session ID and token | P0 |
| FR-03 | Join through a session token and negotiate protocol version | P0 |
| FR-04 | Maintain separate control and audio TCP connections per receiver | P0 |
| FR-05 | Synchronize receiver monotonic time to master time using UDP four-timestamp exchanges | P0 |
| FR-06 | Receive timestamped PCM chunks and place them by stream sample index | P0 |
| FR-07 | Buffer audio before scheduled playback | P0 |
| FR-08 | Start receivers at a common master-time deadline | P0 |
| FR-09 | Read actual audio output timestamps and estimate the DAC clock mapping | P0 |
| FR-10 | Track and correct playout drift without blocking the audio callback | P0 |
| FR-11 | Report receiver health and telemetry to the master | P0 |
| FR-12 | Support local-file playback on the Android master | P0 |
| FR-13 | Support Android live capture when permitted by the source app and OS | P1 |
| FR-14 | Support Opus as a later codec option | P1 |
| FR-15 | Provide an iOS receiver using the same wire protocol and shared timing core | P1 |
| FR-16 | Allow coordinated pause, resume and seek for file playback | P1 |
| FR-17 | Support receiver late join and reconnect | P1 |
| FR-18 | Support manual per-device offset adjustment | P1 |
| FR-19 | Support microphone-based acoustic calibration | P1 |
| FR-20 | Export telemetry and event logs | P1 |

## 7. Non-functional requirements

### 7.1 Synchronization

The current engineering targets are:

| Tier | Target acoustic skew | Interpretation |
|---|---:|---|
| T1 | ≤20 ms | Initial practical target |
| T2 | ≤10 ms | Strong mixed-device target |
| T3 | ≤5 ms at the chosen listening position | Stretch target requiring calibration |

These are acceptance goals to measure, not guaranteed product claims. Every result must state the device models, receiver count, network conditions, calibration state, run duration and measurement method. Report mean, p95 and maximum skew.

### 7.2 Reliability

- A receiver that loses connectivity must not destabilize other receivers.
- Audio callbacks must not allocate memory, block on locks, perform network I/O or write logs.
- The first endurance gate is a 30-minute run; the broader reliability target is at least 60 minutes under documented test conditions.
- Buffer underruns, hard resynchronizations and muted/degraded states must be counted.

### 7.3 Performance

- Audio format baseline: 48 kHz, stereo, signed 16-bit PCM.
- Baseline chunk: 960 frames (20 ms).
- Group delay for live capture: configurable in the 2–10 second range; initial default 3 seconds.
- Receiver buffer capacity: 8 seconds in the current design.
- The master must use bounded per-receiver send queues so a slow receiver cannot block all other receivers.

### 7.4 Portability

Timing, timeline and drift logic must remain platform-neutral. Platform-specific audio I/O, lifecycle and permissions stay in native adapters.

### 7.5 Privacy

No cloud account or server is required. Core operation remains on the private LAN. Audio is not included in diagnostic exports by default.

## 8. Product acceptance criteria

The product is not considered complete merely because several phones play audio. The following evidence is required:

1. Two Android receivers start from one scheduled timeline and a recording demonstrates the measured skew.
2. Clock offset and skew estimation converge on physical devices, with raw telemetry retained.
3. Output timestamp mapping is validated per tested device model.
4. Drift remains bounded during a 30-minute run and correction counters are reviewed.
5. Disconnecting one receiver does not interrupt other receivers.
6. A receiver can rejoin without producing unsynchronized audio.
7. File playback works independently of third-party capture permissions.
8. Mixed Android/iOS behavior is tested on physical devices before making cross-platform performance claims.
9. Acoustic-calibration results are repeatable at a fixed listening position.
10. T1/T2/T3 claims are supported by recorded test reports, not design estimates.

## 9. Capacity and unresolved product boundary

The README describes a group of 6–10 phones total. The detailed system design currently sets a protocol limit of 10 receivers in addition to the Android master. These statements are not equivalent.

**Resolution required before the scaling milestone:** decide whether the product limit is 10 total devices or 10 receivers plus one master. Until then, treat 6–10 total phones as the product target and the protocol's 10-receiver value as a draft implementation limit that must be reconciled.

## 10. Assumptions and dependencies

- A dedicated dual-band Wi-Fi router is the intended deployment hardware; a phone hotspot is acceptable only for early prototypes.
- Client isolation must be disabled and all participating phones must be on the same local network.
- Android is the only master platform in the current scope.
- iOS support requires a Mac, Xcode and physical iPhones.
- Android playback capture depends on user approval and source-app capture policy. Local-file playback is the fallback.
- Audio output timestamp quality and acoustic calibration must be verified on real hardware.
- Minimum supported OS versions remain provisional pending device selection and API verification.

## 11. Release strategy

| Stage | Product outcome | Main exit condition |
|---|---|---|
| Prototype A | Simulator and timing core | Simulated clock/network scenarios pass |
| Prototype B | Two Android receivers, scheduled click track | Repeatable measured timing report |
| Prototype C | Drift correction and reconnect | 30-minute stable run and independent receiver recovery |
| Prototype D | File source, capture attempt, diagnostics | Usable Android-only demo |
| Cross-platform | iOS receiver | Mixed-device test report |
| Calibrated group | Acoustic calibration and scaling | Measured target tier at defined position |

## 12. Source documents

This PRD reorganizes requirements already present in the original design. Consult [docs/Sync Engine.md](../docs_original/Sync%20Engine.md) for system purpose and [docs/System Structure.md](../docs_original/System%20Structure.md) for the detailed design baseline. The original files remain unchanged.
