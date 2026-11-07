# Testing and Validation Strategy — Sync In It

**Document status:** Draft verification plan  
**Version:** 1.0  
**Last updated:** 7 November 2026

## Latest physical test evidence — 7 November 2026 (v0.6.4-debug)

The latest two-phone test reversed Host/Speaker roles on the same pair of Xiaomi phones. Both runs reached UDP time-sync and full PCM pre-roll, but both failed the clock residual gate:

- Run A: Android 14 Xiaomi 22101316I as Host; Android 12 Xiaomi M2010J19SI as Speaker. 107 accepted probes; 27 selected over 32.036 seconds; median RTT 4.9288 ms; residual RMS 0.324341 ms; stable streak 0.
- Run B: Android 12 Xiaomi M2010J19SI as Host; Android 14 Xiaomi 22101316I as Speaker. 108 accepted probes; 27 selected over 43.815 seconds; median RTT 5.6278 ms; residual RMS 0.500333 ms; stable streak 0.
- Both Speakers reached 20,000 ms buffered PCM, 960,000 contiguous frames and zero missing frames. Host queue depth/high-water remained 0/1.
- Both Host sessions reached the 45-second readiness timeout. Neither run reached READY/ANCHOR or playback.
- The paired logs contain per-probe Host/Speaker records correlated by the UDP sequence number. Reversing the roles did not eliminate the clock residual.

**Verdict: FAIL — clock stability / READY.** The test does not isolate the cause. The current experiment branch `experiment/send-boundary-t3-debug-acquisition-window` stamps final response T3 in the transport send path and extends only the debug readiness observation window to 90 seconds. Release builds retain the 45-second timeout. No estimator, residual threshold, sample-selection policy or READY gate is relaxed. Android/JNI, Kotlin transport and M0 simulator/native-core CI all passed on the PR head; physical validation is pending.

See [the paired device report](Test_Results.md#25-test-record--2026-11-07--v064-debug-paired-per-probe-run).

## Previous physical test evidence — 7 November 2026 (v0.6.3-debug)

The latest supplied v0.6.3-debug two-phone run is a **clock-acquisition / READY-gate failure**. Host and Speaker discovery, TCP admission, UDP exchanges and PCM delivery worked, but clock residual RMS remained above the strict threshold:

- Host: Xiaomi 22101316I, Android 14; Speaker: Xiaomi M2010J19SI, Android 12.
- Both devices were on Wi-Fi and the same IPv4 subnet (`192.168.1.35/24` and `192.168.1.39/24`).
- Speaker reached 108 accepted probes, with 27 selected samples over 33.052 seconds. Median RTT was 5.5413 ms; estimated skew was −3.237 ppm; residual RMS was 0.416862 ms; stable streak remained 0.
- Speaker PCM ingress remained near 50 frames/sec, reached 20,000 ms buffered PCM and reported zero missing frames.
- Host queued and wrote 2,250 audio chunks. Queue depth remained 0, high-water was 1, and no queue-overflow event appeared.
- At 45 seconds, the Host closed the receiver because READY had not been reached. The Speaker then reported that the master closed its control/audio channels.
- No READY, ANCHOR, playback-start or acoustic marker result was demonstrated.

The earlier v0.6.3 telemetry also exposed a measurement gap: Host `t3` was captured before receiver matching and JNI response preparation, while Host timing records were emitted only every twentieth response. This does not prove those delays caused the residual, but it prevented per-probe correlation. The follow-up diagnostics branch records the Host and Speaker timings for every accepted exchange using the v1 UDP sequence number. That branch has not yet been built or physically tested; do not treat its proposed instrumentation as validated.

The queue false-positive correction from PR #68 was present in this run and the observed queue counters support that the earlier teardown/overflow misclassification was not reproduced.

**Verdict: FAIL — clock stability / READY.** This is not a synchronized-playback pass and does not establish the clock-noise root cause. See [the chronological device report](Test_Results.md).
## Historical physical test evidence — 6 November 2026

The latest supplied three-phone LAN run is a **failure at the READY gate**, not a discovery or TCP-admission failure:

- Host NSD advertisement, Speaker discovery and Host resolution worked.
- Multiple control HELLO/WELCOME and separate AUDIO_HELLO handshakes were accepted.
- UDP peers were pinned and the Host sent repeated time-sync responses.
- No `Speaker READY` event was observed.
- `GROUP_START_READINESS_TIMEOUT` fired after 45 seconds and removed the unready receivers.
- No `GROUP_START_SCHEDULED` or physical ANCHOR delivery was observed.
- A UDP source-IP rejection appeared after a peer had been removed; treat it as a possible cleanup/late-packet symptom, not the proven root cause.

The Host log alone cannot distinguish unstable clock acquisition from insufficient contiguous PCM. PR #49 adds READY_GATE_BLOCKED on the Speaker and HOST_AUDIO_DELIVERY on the Host. PR #51 fixed the missing AtomicLong import, and PR #53 added receiver-specific acoustic markers plus WAV analysis. The published v0.5.8 APK contains these diagnostics and markers. CI success is not a physical end-to-end sync pass; do not claim acoustic alignment until PHONE-SKEW-001 is recorded.

## 1. Testing objective

The goal is to prove the timing behavior on physical devices under measurable conditions. A successful build or a successful network connection is not evidence of synchronized acoustic output.

Testing must distinguish:
- Clock-estimation error.
- Network delay and jitter.
- Audio hardware clock drift.
- Buffer delivery failures.
- Digital output scheduling error.
- Acoustic path and speaker latency.
- Measurement-system uncertainty.

Every synchronization claim must have a test report and supporting raw data.

## 2. Test layers

| Layer | Purpose | Typical location |
|---|---|---|
| Unit | Verify small deterministic modules | Desktop CI |
| Property/fuzz | Verify invariants and malformed-input handling | Desktop CI |
| Simulator | Model clock/network/DAC impairments with known truth | Desktop |
| Parity | Compare Python reference and C++ implementation | CI |
| Integration | Verify modules and platform adapters together | Android/iOS devices |
| Acoustic measurement | Measure real sound-arrival differences | Physical setup |
| Endurance | Detect drift, resource and thermal behavior over time | Physical devices |
| Field trial | Validate the intended train environment | Train coach |

## 3. Measurement terminology

- **Offset error:** difference between estimated and ground-truth clock offset in simulation, or a reference measurement in a controlled test.
- **Skew error:** difference between estimated and actual clock-rate difference, expressed in ppm.
- **Digital playout error:** difference between intended and reported output presentation time.
- **Acoustic skew:** difference in recorded sound-arrival time between participating phone speakers at the measurement microphone.
- **RTT:** round-trip time of the time-sync exchange after subtracting master-side processing time.
- **p95:** 95th percentile across the measured samples or test runs; state which population is used.

A clock estimator reporting a low residual does not prove acoustic alignment. A low acoustic skew at one microphone does not prove the entire coach is synchronized everywhere.

## 4. Unit tests

### 4.1 Clock and estimator

Test:
- Four-timestamp formula with known values.
- Positive and negative offsets.
- Zero and nonzero skew.
- RTT calculation with master processing interval.
- Negative and implausible RTT rejection.
- Lowest-RTT selection.
- Median behavior with outliers.
- Regression under positive/negative skew.
- Skew clamp behavior.
- Stability criteria and timeout.
- Conversion local→master and master→local.

### 4.2 Timeline

Test:
- Start anchor.
- Pause and resume.
- Seek.
- Ordered anchor insertion.
- Anchor boundary behavior.
- Expected sample rounding.
- Non-playing interval outputs.
- Group-delay change semantics.

### 4.3 DAC clock map

Test:
- Constant sample rate.
- Positive/negative ppm drift.
- Timestamp noise.
- Missing timestamp.
- Discontinuity rejection.
- Sliding-window update.

### 4.4 Jitter buffer

Test:
- In-order and out-of-order chunks.
- Duplicate chunk.
- Late chunk.
- Wrapped ring indices.
- Partial overlap.
- Missing frame zero-fill.
- Buffer overflow and bounded behavior.
- Single-producer/single-consumer concurrency.

### 4.5 Drift controller

Test:
- Dead-band behavior.
- Positive and negative error.
- Slip rate limit.
- Hard-resync threshold.
- Holdoff.
- Error convergence under constant skew.
- Persistent error escalation.
- Deterministic replay.

### 4.6 Protocol

Test all valid message types and malformed input:
- Truncated prefix and payload.
- Length below minimum or above maximum.
- Unknown type.
- Unknown flag bits.
- Invalid enum.
- Invalid channel count.
- Inconsistent payload length.
- Integer boundary values.
- Incorrect token/session/receiver ID.
- Fuzzed decoder input.

## 5. Simulator scenarios

The simulator must know ground truth and publish machine-readable metrics.

| ID | Scenario | Example impairment |
|---|---|---|
| S1 | Ideal | No skew, constant delay |
| S2 | Clock skew | +30 ppm and −40 ppm receivers |
| S3 | Jitter | Variable one-way delay |
| S4 | Asymmetry | Fixed forward/reverse delay difference |
| S5 | Packet loss | Random and burst UDP loss |
| S6 | RTT spikes | 50–200 ms intermittent spikes |
| S7 | DAC drift | Audio clock 25 ppm from system clock |
| S8 | Late join | Receiver appears 60 seconds into playback |
| S9 | Scale | Mixed receiver clocks at target group size |
| S10 | Combined | Skew, jitter, asymmetry, spikes and loss together |

Metrics:
- Offset error mean, p95 and maximum.
- Skew estimation error.
- Convergence time.
- Stability duration.
- Slip count/rate.
- Hard-resync count.
- Buffer underruns.
- Resulting playout error.

Simulator tests cannot validate native audio timestamps or speaker acoustics.

## 6. Physical-device test setup

Record the following for every run:
- Phone make/model and OS version.
- App build and protocol version.
- Master/receiver role assignment.
- Router model, firmware, band, channel and configuration.
- Distance and geometry between phones and microphone.
- Audio route and volume settings.
- Number of active receivers.
- Source type and codec.
- Run start/end times and duration.
- Whether calibration was applied.
- Any interruptions, thermal warnings or battery state.

Use a dedicated router for repeatable tests. A phone hotspot is acceptable for an early prototype but must be recorded as a different test condition.

## 7. Clock synchronization tests (M1)

Procedure:
1. Connect two Android devices to the same test router.
2. Collect the join burst and steady-state timestamp exchanges.
3. Record t1, t2, t3, t4, RTT and theta.
4. Test in both master/receiver directions if devices permit role changes.
5. Repeat under idle and loaded Wi-Fi conditions.
6. Compare offset estimates with the test's independent reference method where available.

Historical provisional gate: offset-estimator residual standard deviation below 1 ms under a stable, controlled setup. This conflicts with the Android preview's enforced <0.25 ms residual-RMS gate and must be reconciled in ADR-024. Do not weaken or silently reinterpret the device gate. A clock pass does not prove acoustic synchronization.


## 7.1 Clock-readiness diagnostic A/B matrix (7 November 2026)

Run on the same two phones, one factor at a time, 60 seconds per run and three repeats. Keep placement, build and source constant.

| Factor | Conditions |
|---|---|
| AP | Phone hotspot / dedicated router |
| Power | Wi-Fi low-latency lock and CPU wake lock enabled / disabled |
| Receive-thread capture | Default / raised priority; no logging or JNI between receive and timestamp |
| Probe policy | Single / paired probes retaining lower RTT |
| Sample selection | Lowest 25% / lowest 50% / all / robust weighting |
| Regression | Free slope / clamped or smoothed slope / offset-only |

For each run record RTT p50/p95/range and timeouts, residual RMS, sample count/span, offset standard error (state the independence assumption), slope range, hold-out prediction error and outlier share. Calculate hold-out error by fitting the first 70% of probes and predicting later low-RTT probe offsets.

Decision rules: localized outliers → inspect capture and selection; uniform ~0.5 ms scatter with low hold-out error → propose, but do not adopt, an uncertainty-based gate through ADR-024; slope swings correlated with hold-out error → test slope constraints; router-only pass → label hotspot prototype-only.

**Probe evidence schema:** attempt ID and probe sequence; exact receiver/master timestamps `t1`, `t2`, `t3`, `t4`; corrected RTT; raw four-timestamp offset; accepted/rejected/timeout/malformed event; and the reason where the native correlator exposes one. The Android diagnostic branch now emits `CLOCK_PROBE` for every accepted exchange and `CLOCK_FIT` for every successful fit update. `CLOCK_FIT` records samples seen/used, selected span, skew, residual RMS, fitted offset, stable streak and the active `lowest-rtt-25pct` / 60-second policy. Reconstruct selected membership offline from the raw accepted probes and this policy; the client does not yet export a per-fit selection bitmap or native rejection enum.

Correlate Host audio delivery, slow writes, queue depth/high-water/overflow and disconnects under the same session diagnostic ID. Those matching Host diagnostics and the replay fixture remain outstanding.

Replay must run the same C++ estimator and readiness gate as Android and compare against the Python reference. A captured physical run must replay to the on-device residual and streak within rounding. Verify cached PCM snapshot counters using a fresh native read.

**Diagnostic text export checks:**
1. Generate more than 240 diagnostic events, including several `CLOCK_PROBE` and `CLOCK_FIT` records. Confirm the on-screen preview remains limited while the exported text report contains the retained full file history.
2. Recreate the Activity and restart the app; confirm previously written events remain in the app-private log.
3. Export through **Save diagnostic report (.txt)**, choose Downloads in Android's document picker, and verify a readable UTF-8 `.txt` file is created.
4. Confirm timestamps, app version, device/API metadata, multiline network snapshots and clock-probe nanosecond values survive export.
5. Inject token/password/secret/API-key assignments into test log messages and verify the exported report contains `[REDACTED]`, not their values.
6. Confirm no audio is included, export cancellation creates no file, clear-log clears retained history, and log rollover stays within the 4 MB cap.
7. Confirm the export works without broad external-storage permissions.

**Threshold integrity:** the current device gate (<0.25 ms residual RMS, minimum 8 samples, 10 s span, three consecutive stable updates) remains unchanged until an ADR-backed decision. The TRD's <1 ms baseline, Testing doc's <1 ms residual standard deviation, JNI <0.25 ms residual RMS, and effectively unlimited core default are inconsistent; resolve definitions and defaults before changing behavior. Do not mix threshold changes with a failing-test fix.



## 7.2 Estimator replay comparisons — hysteresis and two-state tracker (ADR-026)

Use the exact same time-ordered physical probe log for every variant. Keep the current lowest-RTT-25% batch regression as the control.

| Variant | Change under test |
|---|---|
| Baseline | Current RTT sort, lowest 25% re-selection and batch regression |
| Hysteresis | Strict entry gate, wider exit residual boundary and configurable exit dwell |
| Two-state tracker | Continuous offset/skew state with covariance; RTT-scaled measurement noise |
| Combined | Two-state tracker plus readiness hysteresis |

For hysteresis, keep current entry requirements unchanged initially: at least 8 selected samples, at least 10 seconds of span, residual RMS <0.25 ms, and three consecutive qualifying updates. Sweep exit threshold and dwell in replay; do not select values from one physical failure. Invalid/stale samples, clock discontinuities and loss of timestamp basis must still invalidate readiness. The current protocol has no explicit READY-revocation message, so state whether the hysteresis is only a local acquisition latch or needs a Host-visible revoke/lease transition.

For the two-state tracker, use four-timestamp offset as the measurement and propagate offset using elapsed time and estimated skew. Scale measurement noise by RTT only after calibrating the mapping and applying sensible floor/cap bounds. RTT is not a measure of directional symmetry: a low-RTT exchange can still have persistent forward/reverse delay bias. Include innovation/outlier checks and test asymmetric-delay scenarios.

Replay chronologically. For every held-out low-RTT sample, calculate the prediction before assimilating that sample; do not leak later observations into earlier estimates. Report hold-out prediction error, offset prediction at ANCHOR, time-to-ready, stable-streak resets/flaps, false-ready and blocked transitions, skew stability and outlier rejection. Use simulator ground truth to check covariance coverage/calibration; a covariance value is not useful unless it is calibrated.

Adopt neither candidate unless it improves future-offset prediction without increasing false-ready risk or hiding asymmetric-delay bias. Do not change runtime behavior or the <0.25 ms entry gate in this experiment.

## 8. Scheduled playback and acoustic measurement (M2–M3)

Use a known click track or test signal with exact sample indices. Place the phones in a documented geometry and record their sound with one measurement microphone or a multi-channel recorder.

For simultaneous measurements, make each phone's signal distinguishable (for example, unique coded bursts or frequency markers) so the analysis can associate each detected arrival with the correct phone. Alternatively, measure one receiver at a time against a fixed reference. Do not assume identical simultaneous clicks can be separated reliably in one mono recording.

Use cross-correlation/matched filtering to estimate arrival times. Report measurement sample rate, microphone/recorder latency and analysis resolution. Keep the microphone fixed throughout one comparison run.

Provisional gate: achieve 10 ms or less on the defined two-device setup before scaling, or document the limiting error source.

## 8.1 Two-phone acoustic skew measurement (M2/M3 gate)

The repository contains receiver-specific acoustic marker and WAV analysis tools in [tools/README.md](../tools/README.md). The Android diagnostic output retains the base 1 kHz timing click and overlays a 20 ms Hann-windowed marker every 48,000 sample frames. The Host-assigned receiver ID determines the marker frequency: Speaker 1 uses 1,500 Hz and Speaker 2 uses 2,250 Hz. This separates overlapping arrivals in one microphone recording.

1. Confirm the measurement build is installed and the Host assigns Speaker IDs 1 and 2.
2. Use the phone speakers, not Bluetooth. Set fixed volumes and place both phones about 10 cm from the laptop microphone at equal distances.
3. Keep the app visible and screens awake. Record uncompressed PCM16 WAV at 48 kHz in Audacity.
4. Record approximately 120 seconds only after both receivers reach READY and the common playback ANCHOR starts.
5. Analyze the WAV with the commands in tools/README.md.
6. Record phone models/OS, app commit, router/hotspot, volumes, microphone geometry, matched marker count, mean/median signed skew, p95/max absolute skew, drift slope and report files. Repeat without changing geometry or volume.

The sign convention is Speaker B minus Speaker A; positive means B arrived later. The primary decision metric is p95 absolute acoustic skew ≤10 ms. If it passes, proceed to foreground service/wake-lock, manual-offset and drift-control work. If it fails, inspect marker detection, READY/ANCHOR timing, DAC timestamp diagnostics, microphone geometry and acoustic-path differences before deciding whether the AudioTrack backend should change. Do not assume Oboe is a fix without comparative evidence; see ADR-019.

Synthetic tests inject known ±5–8 ms offsets and validate the analyzer only. They are not phone measurements. A successful build, a stable clock residual or a READY event is not an acoustic-skew pass.

**Long-test restriction:** the app currently has no foreground playback service or explicit wake-lock lifecycle. Keep the initial approximately two-minute run foregrounded and the screen awake. Do not run endurance, screen-off, background or train tests until the foreground service/wake-lock work is implemented and validated.


## 8.2 Continuous output and AudioTrack-versus-Oboe experiment (M2/M3)

Run on the same two Xiaomi phones with the same built-in speaker route, source, router, volume, geometry and test build. Record exact phone models, Android versions and selected audio APIs. Repeat each variant at least three times.

| Variant | Output behavior |
|---|---|
| A — current baseline | Scheduled AudioTrack.play() from the ANCHOR-derived local time |
| B — continuous AudioTrack | Start output during join, render silence, then switch to PCM at the mapped output-frame boundary; request PERFORMANCE_MODE_LOW_LATENCY |
| C — continuous Oboe | Oboe/AAudio output callback, start during join, render silence, then switch to PCM at the mapped output-frame boundary; request LowLatency and Exclusive where supported, and record actual granted modes |

For each variant capture:
- Actual output API/backend (including Oboe's selected AAudio or fallback path), sample rate, channel format, frames per burst and buffer size.
- Timestamp availability during warm-up and steady state; frame position/time pairs; timebase; mapper residual, sample span, rejected points and held-out frame-time prediction error.
- Reported output latency, AudioTrack underrun count or Oboe XRun count, callback duration/jitter and late callbacks.
- Time the output stream became active, ANCHOR receipt, target output-frame index, first PCM frame submitted, first corresponding frame reported presented, and marker-detected acoustic arrival.
- Mean, p95 and maximum acoustic skew across the two devices, plus dropouts/underruns and any discontinuities at the silence-to-PCM transition.

For the continuous variants, the output stream and timestamp acquisition must be active before ANCHOR. Verify that silence has not already been committed beyond the target frame; an output queue that has passed the target cannot be retroactively replaced with audio. Record and validate the relationship between the renderer/write frame counter and the timestamp framePosition; do not assume they share an origin or represent the same point in the output pipeline. Confirm the renderer maps each output frame to the shared timeline and changes source exactly at the ANCHOR sample boundary. The first output frame must be predicted from the DAC mapping and Host/local clock mapping, not from a timed writer-thread wake-up.

Android documents that AudioTrack timestamps may be unavailable during warm-up or after route changes and that the returned frame may already be presented or committed; application-level mapping is needed for higher-resolution frame/time scheduling. Oboe also exposes a best-effort estimate of when a frame entered or left the audio pipeline and cannot account for delay unknown to the implementation. Android recommends Oboe for lowest latency, and recommends PERFORMANCE_MODE_LOW_LATENCY when AudioTrack is used for latency-sensitive output; neither statement proves timestamp superiority on these Xiaomi devices.

References:
- [Android AudioTrack.getTimestamp API](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp))
- [Android audio latency guidance](https://source.android.com/docs/core/audio/latency/app)
- [Oboe AudioStream.getTimestamp API](https://google.github.io/oboe/classoboe_1_1_audio_stream.html)

The primary result is target-output-frame-to-acoustic-arrival error and p95 acoustic skew, not only the delay from calling play(). Continuous output can remove the scheduled play() invocation from the media-start event; it does not by itself remove downstream DSP, speaker or acoustic-path delay. Keep route-specific calibration available until measurements show it is unnecessary.

**Decision gate:** compare timestamp mapping quality and physical acoustic skew on both phones. Require p95 acoustic skew ≤10 ms (T2) with no new underrun/XRun or transition artifact before promoting the continuous path. Do not assume Oboe is better because it is the recommended low-latency API.


## 8.3 Single-Speaker-first and transport/codec experiments

**Execution order is a test constraint.** Do not begin transport/codec implementation while the current PCM/TCP Speaker has not passed the single-Speaker READY → ANCHOR → audible-output gate. First capture a passing one-Speaker run, then the continuous-output comparison, then add a second Speaker and establish the T2 acoustic baseline. The transport/codec matrix starts only after that two-Speaker baseline passes.

### Transport/codec comparison matrix

Use identical sample-indexed source audio, devices, AP/router, placement, output route, volume, buffer configuration and scheduled timeline. Change one dimension per run.

| ID | Codec | Transport | Purpose |
|---|---|---|---|
| T0 | PCM16 | Current per-Speaker TCP | Control/baseline |
| T1 | FLAC lossless | TCP | Measure real source-dependent compression and decode cost |
| T2 | Opus, initially 20 ms frames | TCP | Measure bitrate/quality/codec delay/CPU |
| T3 | PCM16 | UDP + selective retransmit | Isolate transport head-of-line and repair-deadline behavior |
| T4 | Opus, 20 ms frames | UDP + selective retransmit | Optional combined candidate; run only after T2 and T3 are understood |

Do not compare T1/T2 to T3/T4 simultaneously as a single "new transport" result. First hold transport constant while changing codec; then hold codec constant while changing transport.

### Required measurements

- Mean/p95/peak payload bitrate per Speaker and aggregate; bytes per sample interval; protocol and retransmission overhead.
- TCP retransmission count, blocked-stream stall duration and per-Speaker queue wait; distinguish one receiver's TCP head-of-line blocking from other independent Speaker connections.
- UDP datagram loss, reorder, duplicate and late-arrival count; NACK count, repair RTT, repair success and fraction repaired before the target sample deadline.
- Chunk sequence/sample-index continuity; missing sample spans; decode completion time relative to target presentation; decoder resets and concealed/dropped samples.
- Receiver buffer fill/high-water, time-to-depletion during stalls, underruns, dropouts and resynchronization count.
- Encoder/decoder CPU, memory, battery/thermal state and acoustic quality.
- Acoustic skew and the already-established T2 p95 ≤10 ms gate; verify a transport/codec change does not regress output timing.

### Specific guardrails

- FLAC compression ratio is source-dependent. Report the measured ratio for generated tones, speech and representative music; do not assume 50%.
- Opus frame duration and packet aggregation change both latency and loss sensitivity. Record actual encoder lookahead, frames per packet and decoder delay; map decoded PCM back to the original sample indices.
- A multi-second playback buffer gives the system time to receive and decode ahead, but codec latency still exists and must be included in the source-to-output timestamp model.
- For UDP repair, request only chunks whose playout deadlines remain reachable. Once a chunk is too late, count it and apply an explicit concealment/drop policy; never stall the group timeline waiting for it.
- Keep packet sizes below the path MTU; test fragmentation/large-packet behavior explicitly before any field deployment.
- Maintain per-Speaker isolation: one receiver's loss or repair queue must not stall healthy Speakers.

**Exit gate:** a candidate preserves the sample-index timeline, meets acoustic gates, reduces measured bandwidth or improves measured stall behavior, and does not introduce unacceptable loss, quality, CPU, thermal or battery regressions. PCM/TCP remains the fallback until then.

## 8.4 Implementation sequencing and work-in-progress limit

Maintain one primary implementation workstream. The order is: minimum diagnostics → one-Speaker PCM/TCP end-to-end pass → continuous-output comparison → two-Speaker acoustic pass → transport/codec matrix → group scale and resilience. Research and deterministic replay may run in parallel only when they directly diagnose the active gate. No iOS, PWA, provider/queue, calibration, codec or UDP transport implementation should be started as a workaround for a blocked single-Speaker path.

## 9. Drift and endurance tests (M4)

Run a click track or continuous test tone for at least 30 minutes in the first endurance gate and 60 minutes for the broader reliability target.

Record at 1 Hz:
- Offset and residual.
- Skew estimate.
- RTT.
- Buffer level.
- Drift error.
- DAC rate estimate.
- Slip and hard-resync counters.
- Underruns and missing frames.
- Battery and temperature.

Acceptance:
- No unexplained monotonic skew growth.
- No unbounded buffer drift.
- No repeated hard-resync storm.
- Audio callback meets real-time constraints.
- Audible artifacts from correction are reviewed.
- Run result includes mean, p95 and maximum acoustic skew.

## 10. Group and resilience tests (M5)

Test:
- 2, 3–4 and then target receiver group sizes.
- One receiver disconnects while others play.
- One receiver rejoins during playback.
- Receiver sends malformed or stale reports.
- Router briefly loses WAN access (local session should continue).
- Router restarts.
- Wi-Fi congestion increases RTT.
- One receiver has a weak signal.
- Master process stops.
- Receiver changes audio route or receives a call.

Acceptance:
- One receiver's fault does not reset healthy receivers.
- Rejoin uses a future coordinated boundary.
- Receiver that cannot stay in sync is muted.
- Master loss produces clean silence after the defined buffer policy.
- All failures are logged with a state transition and diagnostic reason.

## 11. Mixed-platform tests (M7)

On physical Android and iOS devices, verify:
- Protocol compatibility.
- QR/deep-link handling.
- Local-network permissions.
- Background playback behavior.
- Audio route changes and interruptions.
- Timestamp mapping.
- Buffer and drift behavior.
- Reconnect behavior.

Do not infer iOS timing performance from Android results or simulator output.

## 12. Acoustic calibration tests (M8)

Procedure:
1. Fix the microphone at the target listening position.
2. Fix each phone in its intended physical position.
3. Run at least three chirp measurements per receiver.
4. Calculate matched-filter confidence and delay.
5. Reject poor peaks and inconsistent repetitions.
6. Calculate compensation relative to the earliest measured arrival.
7. Apply compensation and repeat the measurement.

Acceptance:
- Repeated estimates are consistent within a predeclared tolerance.
- Low-confidence devices are not auto-corrected.
- Correct compensation reduces measured spread.
- Relocating a phone causes the stored calibration to be marked for recheck.
- T3 is claimed only if repeated measurements show ≤5 ms at the defined listening position.

## 13. Target tiers and reporting rules

| Tier | Skew target | Evidence required |
|---|---:|---|
| T1 | ≤20 ms | Physical acoustic measurement |
| T2 | ≤10 ms | Physical acoustic measurement across the declared device mix |
| T3 | ≤5 ms | Repeated calibrated acoustic measurement at one specified position |

Every report includes:
- Mean, p95 and maximum acoustic skew.
- Number of samples and runs.
- Device models and OS versions.
- Network/router conditions.
- Session duration.
- Whether calibration was used.
- Failure, underrun and resync counts.
- Known measurement uncertainty.

Do not publish one best-case value as a general guarantee.

## 14. CI requirements

On every relevant change:
1. Build the C++ core with CMake.
2. Run native unit tests.
3. Run Python simulator scenarios.
4. Run Python/C++ golden-vector parity tests.
5. Run protocol decoder fuzz/sanitizer jobs where practical.
6. Preserve test reports when a gate fails.

Physical-device audio tests remain a separate release gate.

## 15. Test artifacts

Store under the designated test-report area:
- Raw time-sync samples.
- Telemetry CSV.
- Event log.
- Test setup metadata.
- Original audio recordings where authorized.
- Analysis scripts and generated charts.
- Summary report with pass/fail and caveats.

Do not overwrite prior test runs. Give each run a unique identifier and retain the app build/commit reference.

## 16. Source documents

This plan expands the simulator, phone-test, endurance, scale and fault-injection strategy in [docs/System Structure.md](../docs_original/System%20Structure.md) and [docs/Sync Engine.md](../docs_original/Sync%20Engine.md).
