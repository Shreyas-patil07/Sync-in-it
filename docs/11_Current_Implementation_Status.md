# Current Implementation Status — Sync In It

**Status date:** 6 November 2026  
**Repository baseline:** `main` at `c5da8bdb11cfeb27d05e138a1f263f18414b9ea1` before this documentation update  
**Latest published Android preview:** [`v0.5.7-preview.1`](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.5.7-preview.1)  
**Audience:** contributors, testers and anyone assessing what is implemented versus what is still a design target.

This file is the source of truth for present-tense implementation claims. Product targets, architecture proposals and future behavior in the other design documents remain useful, but they must not be read as evidence that those features are already implemented or validated.

## Executive summary

Sync In It has progressed beyond the original discovery/clock-acquisition shell. The Android preview now contains a working implementation path for local Host discovery, multi-Speaker TCP admission, UDP clock synchronization, generated PCM16 test-stream fan-out, bounded receiver buffering, group readiness, a shared future playback ANCHOR, Android AudioTrack rendering and DAC output-timestamp diagnostics.

**The end-to-end multi-phone playback path is not yet physically validated.** The latest supplied three-phone LAN run successfully discovered the Host, established the control and audio TCP channels for multiple Speakers, and exchanged UDP time-sync packets. None of the Speakers reached `READY`; the Host's 45-second readiness deadline removed the receivers. No successful multi-Speaker group start or measured acoustic synchronization was demonstrated by that run.

The current development focus is therefore **READY-gate diagnosis and Android build verification**, not adding more playback features or weakening clock thresholds.

## Release, branch and build state

| Item | Current state | Evidence / interpretation |
|---|---|---|
| Published preview | `v0.5.7-preview.1` | Public GitHub pre-release with APK and SHA-256 checksum |
| APK asset | `SyncInIt-Android-preview-v0.5.7.apk` | 5,969,072 bytes on the release page |
| Release scope | Multi-Speaker admission, rolling pre-ANCHOR PCM prebuffer, READY retry, shared ANCHOR path and M3a timestamp diagnostics | See the linked release notes |
| Current main | PR #49 merged | Adds READY-gate and Host audio-delivery diagnostics |
| Native/simulator CI for PR #49 | Passed | M0 simulator/native workflow succeeded |
| Android/JNI CI for PR #49 | Failed before this documentation branch | Kotlin compile error: unresolved `AtomicLong` in `MasterSessionHost.kt` |
| This branch | Adds the missing `AtomicLong` import plus documentation/site updates | Android CI must be rerun; do not treat the import fix as verified until it passes |

The published `v0.5.7-preview.1` APK was built before PR #49 was merged. It contains the v0.5.7 playback/admission implementation, but **does not include the later `READY_GATE_BLOCKED` and `HOST_AUDIO_DELIVERY` diagnostics added by PR #49**. The PR #49 changes are source on current `main`, not a separately published APK.

## Implemented Android path

### Discovery and admission

- One Android phone acts as the Host; Android phones join as Speakers.
- Host discovery and advertising use Android NSD/DNS-SD/mDNS service type `_syncinit._tcp.`.
- Each Speaker establishes a control TCP connection and a separate audio TCP connection. The Host binds those channels to the same admitted peer.
- UDP time synchronization is served through a shared Host listener and correlated to an admitted receiver session.
- The current Host admission cap is **8 Speakers** (the separate product target remains 6–10 phones total, including the Host).
- The admission window is **15 seconds from the first successfully admitted Speaker**. The cohort is fixed after the window closes.
- The Host waits up to **45 seconds from first admission** for the admitted cohort to reach READY. A Speaker that has not reached READY at the deadline is removed. This timeout is a failure boundary, not evidence that readiness works.

### Audio transport and prebuffer

- The Host generates one **48 kHz, stereo, PCM16** timing-click test stream and fans the same sample-indexed chunks to connected Speakers.
- Chunks contain **960 frames (20 ms)** and are sent over the separate audio TCP connection.
- Each Speaker inserts audio into a bounded **20-second** native sample-indexed PCM buffer.
- Before the common playback ANCHOR, the buffer can roll its pre-roll cursor forward to retain the newest contiguous window while clock acquisition completes.
- After ANCHOR, rolling is disabled. Out-of-window data is a strict playback error; the buffer must not silently skip samples to conceal an underrun.
- Per-Speaker outbound audio queues are bounded at **25 chunks / approximately 500 ms**. A slow Speaker is removed rather than being allowed to block the other peers.
- The source is still a generated 1 kHz timing-click/test signal. User-selected music, local-file decoding and Android system-audio capture are not part of this preview path.

### Clock acquisition and group start

- Each Speaker uses the shared four-timestamp UDP time-sync client and C++ clock estimator.
- The current readiness gate requires a stable clock estimate and at least **3,000 ms** of contiguous PCM.
- The estimator's implemented acquisition criteria include at least 8 selected samples, at least 10 seconds of selected-sample span, residual RMS below 0.25 ms, and three consecutive stable updates.
- The Speaker sends READY and retries every second until it receives ANCHOR. Repeated READY messages are idempotently accepted by the native Host-side receiver state machine.
- When the admission window is closed and every remaining admitted Speaker is READY, the Host arms the cohort and sends one shared future Host-monotonic timestamp and sample index in an ANCHOR frame.
- Each Speaker maps the Host time through its latest offset estimate, seeks to the shared sample index, primes AudioTrack and schedules playback.

These are implemented software paths. They do **not** establish that a real device reaches READY, that AudioTrack starts at the requested physical presentation instant, or that the speakers are acoustically aligned.

### M3a DAC diagnostics

- A helper thread polls Android AudioTrack frame-position/presentation-time timestamps.
- The low-order frame counter is unwrapped before fitting.
- A rolling 30-second regression estimates output rate and maps frame position to Android monotonic time.
- Diagnostics expose sample span, estimated rate error, regression residual and rejected timestamp count.
- This mapping is currently **diagnostic only**. It is not yet consumed by a drift controller, used to compensate output latency, or proven against microphone-recorded sound arrival.

## Latest physical test evidence

The latest supplied run used a Host and multiple Speaker phones on the same Wi-Fi subnet.

| Stage | Observed result |
|---|---|
| Host NSD advertisement and self-session filtering | Working |
| Speaker discovers and resolves Host | Working |
| Control TCP HELLO/WELCOME | Accepted for multiple receiver IDs |
| Separate audio TCP AUDIO_HELLO | Accepted for those receivers |
| Host UDP time-sync socket | Bound and listening |
| UDP peer pinning and response | Requests were validated and responses sent repeatedly |
| Speaker READY | Not observed |
| Group ANCHOR / GROUP_START_SCHEDULED | Not reached |
| Readiness deadline | Expired; receivers disconnected as not READY |
| Late UDP packet after receiver removal | Rejected because its source IP no longer matched an admitted control peer |

The `HOST_UDP_SOURCE_IP_REJECTED` event occurred after receivers had been removed. It is consistent with a packet arriving during/after peer cleanup and is not, by itself, proof of the initial READY failure.

The Host-side log proves UDP requests were exchanged; it does not reveal whether each Speaker's estimator passed its stability gate or whether the Speaker's PCM buffer reached 3 seconds. The next evidence required is the Speaker's `READY_GATE_BLOCKED` diagnostic and Host `HOST_AUDIO_DELIVERY` counters.

## Current diagnostics added on main

PR #49 adds two diagnostic families:

- `READY_GATE_BLOCKED`: identifies `CLOCK_ESTIMATE_UNAVAILABLE`, `CLOCK_UNSTABLE`, `PCM_BUFFER_INSUFFICIENT` or both; includes samples used/seen, selected span, stable streak, residual RMS, RTT median, contiguous buffered milliseconds, missing frames and pre-roll advances.
- `HOST_AUDIO_DELIVERY`: per receiver, reports audio frames queued/written, current queue depth and observed queue high-water mark.

These diagnostics are intended to tell us whether READY is blocked by clock acquisition, PCM delivery/buffering, or both. They do not change the clock threshold or playback algorithm.

## Current validation state

| Area | Status |
|---|---|
| Python simulator and C++ timing core | Automated CI passing on the last PR #49 native workflow |
| Shared Python/C++ timing vectors | CI passing |
| Kotlin/JVM transport tests | Included in Android workflow; rerun required after the current import correction |
| Android/JNI debug build | Latest PR #49 run failed at Kotlin compile due to missing `AtomicLong` import; this branch adds that import and requires a fresh CI result |
| Host discovery and TCP join on physical LAN | Observed working in the supplied run |
| Multi-Speaker UDP response | Observed working in the supplied run |
| Speaker clock stability / READY | Failed to reach READY in the supplied run; Speaker-side gate metrics still required |
| Group-start ANCHOR on physical phones | Not demonstrated |
| Audible two-/multi-phone synchronization | Not measured |
| DAC timestamp regression on physical phones | Implemented in code; device evidence still required |
| Drift correction / acoustic calibration | Not integrated/validated as end-to-end features |
| iOS receiver | Not implemented |

A successful desktop CI run verifies deterministic core behavior. A successful Android build verifies compilation and packaging. Neither substitutes for a physical multi-phone test or acoustic measurement.

## Known limits and safe-use guidance

- Use a trusted private Wi-Fi network with mDNS multicast and client-to-client traffic permitted. Guest-network/client isolation can block peers.
- The session token is currently included in DNS-SD TXT metadata so nearby Speakers can join. Devices on the same LAN may be able to read it.
- The token is an admission credential, not transport encryption or cryptographic peer authentication. Do not use this prototype on public/untrusted Wi-Fi.
- The preview supports Android roles only. iOS receiver support is planned, not implemented.
- No claim of zero-millisecond, sample-accurate DAC, or acoustically perfect synchronization is supported.
- Bluetooth output latency, device-specific HAL delay, microphone calibration and long-duration acoustic drift remain unmeasured.

## Next engineering sequence

1. Get Android/JNI CI green after the missing-import correction.
2. Install a build containing PR #49 diagnostics on the Host and each Speaker.
3. Run one Host + one Speaker for at least 45 seconds; export both devices' logs.
4. Compare `READY_GATE_BLOCKED` with `HOST_AUDIO_DELIVERY` for the same receiver/session.
5. Once one Speaker reaches READY and starts from ANCHOR, repeat with two Speakers, then three/four.
6. Only after stable group start is observed, run 10–15 minute playback, disconnect/reconnect tests and independent acoustic measurements.
7. Keep clock thresholds unchanged until diagnostic evidence identifies a justified cause.

## Repository map

- `sim/`: Python reference simulator and synthetic network/clock scenarios.
- `core/`: portable C++17 protocol, session, timing and PCM buffer primitives.
- `android/app/`: Android app, Kotlin Host/Speaker networking, AudioTrack integration and JNI.
- `android/transport/`: Kotlin/JVM TCP and UDP transport module.
- `protocol/vectors/`: shared timing and wire-protocol fixtures.
- `docs/`: requirements, architecture, protocol, security, testing and current implementation status.
- `frontend/`: public project website and Android preview downloads page.
- `ios/`: not yet implemented in the current repository.
