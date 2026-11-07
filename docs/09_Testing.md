# Testing and Validation Strategy — Sync In It

**Document status:** Draft verification plan  
**Version:** 1.0  
**Last updated:** 1 November 2026

## Latest physical test evidence — 6 November 2026

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

Historical provisional gate: offset-estimator residual standard deviation below 1 ms under a stable, controlled setup. This conflicts with the Android preview's enforced <0.25 ms residual-RMS gate and must be reconciled in ADR-018. Do not weaken or silently reinterpret the device gate. A clock pass does not prove acoustic synchronization.


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

Decision rules: localized outliers → inspect capture and selection; uniform ~0.5 ms scatter with low hold-out error → propose, but do not adopt, an uncertainty-based gate through ADR-018; slope swings correlated with hold-out error → test slope constraints; router-only pass → label hotspot prototype-only.

**Probe evidence schema:** attempt ID; t1, t2, t3, t4; RTT; raw offset; selected/rejected/expired flag and reason. Each fit update includes samples seen/used, sample span, slope, residual, predicted offset at t4 and stable streak. Correlate Host audio delivery, slow writes, queue depth/high-water/overflow and disconnects under the same session diagnostic ID.

Replay must run the same C++ estimator and readiness gate as Android and compare against the Python reference. A captured physical run must replay to the on-device residual and streak within rounding. Verify cached PCM snapshot counters using a fresh native read.

**Threshold integrity:** the current device gate (<0.25 ms residual RMS, minimum 8 samples, 10 s span, three consecutive stable updates) remains unchanged until an ADR-backed decision. The TRD's <1 ms baseline, Testing doc's <1 ms residual standard deviation, JNI <0.25 ms residual RMS, and effectively unlimited core default are inconsistent; resolve definitions and defaults before changing behavior. Do not mix threshold changes with a failing-test fix.

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
