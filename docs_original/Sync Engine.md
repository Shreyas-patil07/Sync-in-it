    # System Design: Offline Synchronized Multi-Phone Audio

> **Current-state warning (6 November 2026):** This file records the intended Sync Engine architecture and product behavior. It is not a status report. The current Android implementation is narrower: one Host, up to eight Speakers, generated PCM16 timing-click source, TCP admission/audio, UDP clock acquisition, READY gating and a start ANCHOR path. The latest supplied physical run admitted multiple Speakers but did not reach READY; no acoustic synchronization result is established. Use [docs/11_Current_Implementation_Status.md](../docs/11_Current_Implementation_Status.md) as the implementation source of truth. Features such as music/file capture, drift correction, reconnect, calibration and iOS should be treated as design targets unless that status report says otherwise.


*Master-clock group playout with acoustic calibration for 6-10 Android and iOS phones on a moving train. Draft v1, November 2026.*

## 1. Purpose and scope

This document specifies the architecture of an offline system that turns 6-10 independent smartphones into one synchronized speaker group. One Android phone acts as the audio source and master clock. The other phones, Android and iOS, act as receivers. The system runs on a private local Wi-Fi network with no internet access, and is intended for a single train coach (for example, a Bandra-Delhi journey).

The design goal is to minimize the difference between the real sound-emission times of all phones. It does not try to eliminate source-to-speaker latency. A common delay of 2-10 seconds is deliberately used as preparation time.

**In scope:** timing architecture, protocol, receiver audio engine, drift control, calibration, resilience, verification.

**Out of scope for v1:** capture of protected audio, multi-coach operation, video synchronization, stereo/spatial rendering.

## 2. Requirements

### 2.1 Functional

1. One Android phone starts a session and distributes audio to 6-10 receivers.
2. Receivers run on Android and iOS using one shared protocol.
3. All receivers play the same audio sample at the same real time.
4. The group delay is configurable up to 10 seconds.
5. The system works without any external network.
6. Receivers that disconnect can rejoin without disturbing the others.
7. A calibration step reduces differences in actual sound arrival at a chosen listening position.

### 2.2 Non-functional

- **Reliability:** continuous playback for at least 60 minutes under defined test conditions.
- **Power:** usable for long sessions with a foreground service and managed Wi-Fi locks.
- **Extensibility:** adding another receiver platform must not require changing the timing logic.

### 2.3 Synchronization targets (tiered)

These tiers are engineering goals to be validated by measurement, not guarantees.

| Tier | Skew between phones | Meaning |
| --- | --- | --- |
| T1 | 20 ms or less | Heard as a single sound source by most listeners (practical goal) |
| T2 | 10 ms or less | Solid result for mixed phones over Wi-Fi |
| T3 | 5 ms or less at the listening position | Stretch goal, requires calibration |

Results are reported as mean, maximum and 95th-percentile error. A literal zero-millisecond difference is not physically guaranteed across different phones.

## 3. Design principles

1. **Synchronize output, not arrival.** Packet arrival time is never used to start playback. Every sample has a target presentation time on a shared timeline.
2. **One group deadline.** Receivers never choose unrelated delays. Faster receivers wait for the slowest.
3. **Stand on proven designs.** Reuse established techniques (Snapcast-style timestamped chunks, NTP-style offset estimation, IETF IDMS group control).
4. **Fail silent, not out of sync.** A receiver that cannot meet the timeline is muted and resynchronized.
5. **Measure everything.** Every layer exposes telemetry so a failed run can be explained.

## 4. Architecture overview

### 4.1 Roles

- **Master (Android):** audio source, authoritative clock, session manager, and group controller. Its own speaker is muted during playback.
- **Receiver (Android or iOS):** independent playback device that follows the master timeline.
- **Router:** a dedicated portable Wi-Fi router. An Android hotspot is acceptable only for early prototypes. Client isolation must be disabled.

### 4.2 Layers

| Layer | Responsibility | Technique | Primary reference |
| --- | --- | --- | --- |
| L1 Transport | Deliver audio ahead of time | Timestamped chunks, deep buffer, TCP | Snapcast |
| L2 Clock distribution | Shared notion of time | Lowest-RTT NTP-style exchange, optional receiver-to-receiver refinement | NTP, RBS |
| L3 Group playout control | One common deadline | Receivers report, controller sets target playout point | IETF RFC 7272 (IDMS) |
| L4 DAC clock mapping | Master time to real output time | Platform output timestamps plus skew estimation | Android/Oboe, CMU |
| L5 Drift correction | Hold alignment for hours | Sample drop/duplicate, gentle rate adjustment | Snapcast, IDMS thesis |
| L6 Acoustic alignment | Align sound in the air | Microphone chirp measurement, optional ultrasonic beacon | Patents, CMU |
| L7 Resilience | Survive real conditions | Quarantine, rejoin, adaptive delay | This design |

### 4.3 Core software split

A portable **C++ core** contains the offset estimator, timeline mapper, drift controller and group-deadline logic. Thin native layers provide audio I/O and networking: Kotlin with Oboe/AAudio on Android, Swift with AVAudioEngine on iOS. The core is unit-testable on a laptop against simulated clocks.

## 5. Time model

### 5.1 Master time

Master time `M` is the master phone's monotonic clock. Receivers estimate `M` from their local monotonic clock.

### 5.2 Clock offset estimation (L2)

A receiver sends a UDP request and records `t1`. The master records `t2` on arrival and `t3` on reply. The receiver records `t4` on reply arrival.

```
RTT   = (t4 - t1) - (t3 - t2)
theta = ((t2 - t1) + (t3 - t4)) / 2        # master = local + theta
```

Because Wi-Fi delay is not symmetrical, the estimator does the following:

1. Send a burst of about 20 requests at join, then one every few seconds.
2. Keep only the lowest-RTT quarter of recent samples.
3. Take the median `theta` of those samples as the offset.
4. Fit `theta` against local time by linear regression over a window of one to two minutes to obtain the skew in ppm.
5. Convert time with `M(t) = t + theta0 + skew * (t - t0)`.

**Optional refinement (experimental):** Reference Broadcast Synchronization. The master broadcasts a reference packet. Each receiver records its own arrival time and shares it. Receivers compare arrival times with each other, which removes sender-side delay uncertainty. This requires a working broadcast path and may be degraded by Wi-Fi power saving, so it is adopted only if measurements show it improves on the baseline.

### 5.3 Stream timeline

```
T(n) = T0 + n / Fs        # target presentation time of stream sample n, Fs = 48000
```

`T0` is the common start deadline (see section 7).

### 5.4 DAC clock mapping (L4)

On each receiver, the audio API reports pairs `(framePosition, localTime)` for frames leaving the pipeline. A background thread polls these (not the data callback on older Android versions) and keeps a fitted mapping from output frame position to local time. The fit tracks the audio hardware clock, which skews relative to the system clock by tens of ppm.

The master time at which frame `p` is presented is therefore:

```
M(p) = localTime(p) + theta_total
```

The stream sample that must be playing at frame `p` is:

```
n_expected(p) = ( (M(p) + c_i) - T0 ) * Fs
```

where `c_i` is the per-device compensation (section 9). If a device's sound emerges late relative to the group, `c_i` is positive so the device presents earlier.

## 6. Protocol

All multi-byte fields are little-endian. The protocol is platform-neutral so Android and iOS receivers are identical on the wire.

### 6.1 Channels

| Channel | Transport | Content |
| --- | --- | --- |
| Control | TCP, `TCP_NODELAY` | HELLO, READY, START, PAUSE, STATS, RESYNC |
| Audio | TCP | Timestamped audio chunks |
| Time sync | UDP | Ping/pong with four timestamps |

TCP is used for audio because the multi-second buffer absorbs retransmission delay and avoids building loss concealment. UDP is used for time sync so that retransmits do not corrupt timestamps.

### 6.2 Audio chunk

```
magic(2) version(1) type(1) session(4) seq(4)
sampleIndex(8)  frames(2)  codec(1)  payloadLen(2)  payload
```

`sampleIndex` is the stream position of the first frame in the chunk. Receivers place chunks into the jitter buffer by `sampleIndex`, never by arrival order. Chunk size is about 20 ms. v1 carries PCM; Opus is added later without changing the header.

### 6.3 Time-sync packets

```
REQ:  type(1) seq(4) t1(8)
RESP: type(1) seq(4) t1(8) t2(8) t3(8)
```

Timestamps are monotonic-clock nanoseconds taken as close to the socket as the platform permits.

### 6.4 Join

The master shows a QR code containing the session ID, master address and a connection token. Receivers connect directly, avoiding reliance on network discovery that some access points block.

## 7. Group playout control (L3)

The master follows the IDMS model: receivers report their state, and the controller distributes one common target playout point.

### 7.1 Receiver report

Each receiver periodically sends: buffered duration, offset estimate and RTT statistics, measured output-latency estimate, correction counters, and underrun count.

### 7.2 Start deadline

```
T0 = now_master + max_i(prep_i) + margin
```

`prep_i` is the time receiver `i` needs to be ready, estimated from its buffer fill and link statistics. The receiver with the greatest preparation time sets the deadline. The master broadcasts START(T0) only after every active receiver reports READY.

### 7.3 Group delay D

The common delay between source capture and speaker output defaults to 2-4 seconds on a stable network, and can be raised toward 10 seconds under unstable conditions or during recovery. These ranges are starting points to be tuned experimentally. A change of D is applied only at a coordinated future boundary for all receivers at once, never to a single receiver.

## 8. Receiver audio engine and drift control (L5)

### 8.1 Components

- **Jitter buffer:** ring buffer indexed by `sampleIndex`, holding 3-5 seconds. Missing data is filled with silence and reported.
- **Output engine:** low-latency stream (Oboe/AAudio on Android, AVAudioEngine on iOS) running a data callback.
- **Drift controller:** runs in the callback.

### 8.2 Start

The engine outputs silence until the frame whose expected sample index reaches 0, then begins reading from sample 0.

### 8.3 Correction policy

At each callback, compute the error `e = n_expected - n_actual` in samples.

| Error | Action |
| --- | --- |
| Under about 0.1 ms | Do nothing (dead-band) |
| Between 0.1 ms and about 5 ms | Drop or duplicate single samples, rate-limited to roughly one per 10 ms |
| Over about 5 ms | Hard resync: jump the read pointer, log an event |

The thresholds are design parameters to be tuned by listening and measurement. Sample drop/duplicate is chosen over full asynchronous sample-rate conversion because it is simpler and inaudible at these rates. For larger sustained errors, a gentle playout-rate adjustment inside perceptually tolerable limits is used before resorting to a jump.

## 9. Acoustic calibration (L6)

Digital synchronization does not guarantee simultaneous sound, because output pipelines, speaker hardware and distance differ.

### 9.1 Method (v1)

1. Place the master's microphone at the chosen listening position.
2. Each receiver in turn plays a short linear chirp at a scheduled master time.
3. The master records, matched-filters the recording with the chirp, and finds the arrival time with sub-sample interpolation.
4. Measured delay for receiver `i` is `arrival_i - scheduled_i`. The compensation `c_i` is set so all receivers share one acoustic arrival time at that position.
5. Microphone timestamps are mapped with the input-stream timestamp API, as in section 5.4.

The air path contributes about 2.9 ms per meter, which is on the order of the T3 target, so calibration is valid only for the chosen position and the current phone placement.

### 9.2 Manual fallback

Every receiver exposes a manual offset slider (for example, plus or minus 50 ms in 0.1 ms steps). This is both a debugging tool and a cheap substitute for automatic calibration.

### 9.3 Extensions (stretch)

- **Mutual calibration:** all phones record each other's chirps, and pairwise delays are solved jointly, removing the need for one reference microphone.
- **Ultrasonic beacon:** a continuous inaudible signal to track drift during playback. Phone speakers are weak at ultrasonic frequencies, so feasibility must be tested first.
- **Re-calibration button:** needed on a train because phones shift position.

## 10. Resilience (L7)

| Event | Behavior |
| --- | --- |
| Receiver disconnects | Others continue on the existing timeline. |
| Receiver rejoins | Receives chunks ahead of the current position, re-estimates offset, and enters at a future coordinated boundary. |
| Receiver cannot catch up within D | Marked unavailable and kept silent. |
| RTT or loss rises | Controller increases D at a coordinated boundary. |
| Buffer underrun | Fill with silence, count the event, resync if repeated. |
| Master loses the router | Receivers keep playing buffered audio and then stop cleanly. |

Platform survival measures: foreground service on the master, partial wake lock, high-performance Wi-Fi lock, battery-optimization exemption, and the iOS background-audio mode with a correctly configured audio session. These are standard practices and need to be validated on the target phones.

## 11. Platform notes

- **Android master:** audio capture via AudioPlaybackCapture with user-approved MediaProjection. Many apps block capture, so a fallback to playing local files inside the app is included.
- **Android receiver:** Oboe/AAudio, exclusive low-latency mode where available, output timestamps from the stream.
- **iOS receiver:** AVAudioEngine with host-time scheduling and output presentation latency. Building and testing needs a Mac, Xcode and physical iPhones.
- **Audio format:** 48 kHz. PCM first, then Opus.

## 12. Verification plan

### 12.1 Simulation

A laptop simulator injects clock skew, asymmetric and jittery delay, and packet loss into the C++ core to tune the estimator and drift controller before using phones.

### 12.2 Phone tests

1. **Offset quality:** offset standard deviation, and A-to-B versus B-to-A consistency.
2. **Acoustic skew:** a click-track with known sample positions, phones about 10 cm from one recording microphone, per-click arrival differences computed by cross-correlation. Report mean, maximum, 95th percentile.
3. **Endurance:** at least 60 minutes, recording correction counts, hard resyncs, underruns, battery and temperature.
4. **Scaling:** 2, 4, 6, 10 receivers.
5. **Fault injection:** disconnects, router reboot, Wi-Fi congestion.
6. **Train trial:** offline operation in the real environment.

### 12.3 Telemetry (CSV per device)

Offset, RTT, skew in ppm, buffer fill, error `e`, sample corrections, hard resyncs, underruns.

## 13. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Asymmetric Wi-Fi delay limits clock accuracy | Skew floor of a few ms | Lowest-RTT filtering, wired-quality router, measurement |
| Output timestamps inaccurate on some phones | Residual skew | Acoustic calibration, manual offsets |
| Wi-Fi power saving causes RTT spikes | Offset errors | Wi-Fi locks, outlier rejection |
| Phones move during the trip | Calibration invalid | Re-calibration button |
| Protected audio cannot be captured | Source limited | Local-file fallback |
| iOS toolchain or devices unavailable | Delays iOS receiver | Android-only milestone first |
| Scope too large | Missed deadline | Staged build with go/no-go gates |

## 14. Build plan

| Milestone | Work | Gate |
| --- | --- | --- |
| M0 | Simulator for time sync and drift control | Tracks a simulated 30 ppm skew within 1 ms |
| M1 | Time sync on two real phones | Offset standard deviation under 1 ms |
| M2 | Scheduled click-track start on 2 receivers | Skew measured and recorded |
| M3 | Output-timestamp mapping and manual offsets | 10 ms or less |
| M4 | Drift controller, 30-minute run | Skew stays flat, few hard resyncs |
| M5 | 3-4 receivers, group deadline, reconnect | Rejoin without disturbing others |
| M6 | Opus, system audio capture | Stable at the same skew |
| M7 | iOS receiver | Mixed Android/iOS within target tier |
| M8 | Acoustic calibration | Tier T3 measured at one position |
| M9 | Scale to 6-10 and train trial | Targets reported with p95 values |

Rough effort for one person working part-time (an estimate, not a measurement): about 4-5 weeks to a working Android-only demonstration (M0-M5).

## 15. Assumptions and unproven items

- No published system I found combines all these layers on mixed phones over Wi-Fi, so the integration itself is unproven.
- The microsecond-level results in the cited papers come from controlled hardware, not phone Wi-Fi stacks or phone speakers.
- The mutual-microphone calibration and ultrasonic beacon are extrapolations from patents and research, and need feasibility tests.
- Synchronization thresholds, delay ranges and timing constants in this document are initial values to be tuned.
- Whether a maintained iOS client exists for Snapcast has not been verified. It affects reuse versus writing the iOS receiver from scratch.

## 16. References

1. Snapcast, synchronous multiroom audio: https://github.com/rusitschka/snapcast
2. Elson, Girod, Estrin, Fine-Grained Network Time Synchronization Using Reference Broadcasts (OSDI 2002): https://usenix.org/conference/osdi-02/fine-grained-network-time-synchronization-using-reference-broadcasts
3. RFC 7272, Inter-Destination Media Synchronization (IDMS) Using RTCP: https://www.rfc-editor.org/info/rfc7272
4. Montagut et al., adaptive and standardized RTCP-based IDMS solution (thesis): https://ir.cwi.nl/pub/23633
5. Lazik et al., Ultrasonic Time Synchronization and Ranging on Smartphones: https://ptolemy.berkeley.edu/projects/terraswarm/pubs/492.html
6. Android Compatibility Definition change on timestamp accuracy: https://android.googlesource.com/platform/compatibility/cdd/+/f845c6c%5E%21/
7. Oboe AudioStream header (getTimestamp, latency): https://android.googlesource.com/platform/external/oboe/+/10cd299e/include/oboe/AudioStream.h
8. Patent, multi-device audio playback correction with ultrasonic signals: https://patents.google.com/patent/US12401947
9. Patent application, microphone-based playback delay determination: https://www.goveda.com/patent/CN-122002178-A
