# Implementation Plan — Sync In It

**Document status:** Active execution plan  
**Version:** 1.5  
**Last updated:** 2 November 2026

**Current progress (7 November 2026):** M0 simulator/native CI and acoustic analyzer synthetic tests pass on Python 3.11, 3.12 and 3.13. Android source includes NSD discovery, per-Speaker TCP control/audio admission, UDP clock acquisition, generated PCM16 fan-out, rolling pre-ANCHOR buffering, READY retry, 15-second admission, 45-second readiness deadline, common future ANCHOR and M3a AudioTrack timestamp diagnostics. The pre-PR #55 physical baseline had successful joins/UDP responses and PCM buffering but failed clock stability; the Host audio queue later overflowed. The post-PR #55 Speaker-side run on 6 November showed ingress mostly around 49–53 frames/sec, zero missing frames and buffering up to ~19.94 seconds, but residual RMS remained ~0.412–0.557 ms against the strict <0.25 ms requirement. Stable streak stayed 0 and READY was not demonstrated. Matching Host diagnostics and acoustic results are still absent. PR #51 fixed the prior AtomicLong compile error and Android/JNI CI passed. The receiver-specific acoustic marker and PCM16/48 kHz WAV analyzer synthetic tests pass CI; no real two-phone skew value is recorded.

## Current execution snapshot — 7 November 2026

The immediate task is now to diagnose the post-PR #55 clock-fit residual and reach physical READY/ANCHOR without relaxing the current clock or 3-second PCM gates. The 6 November Speaker excerpt showed the receive path sustaining near-target ingress and accumulating contiguous PCM, while residual RMS remained above threshold and stable streak stayed at zero. Capture matching Host diagnostics and per-probe timestamps to determine whether the remaining variance comes from network delay, Android scheduling, timestamp capture or estimator/sample selection. After one Speaker reaches READY and the Host sends ANCHOR, make the screen-on two-minute acoustic recording with the measurement build and analyze it with `tools/analyze`. Do not change to Oboe before the result is measured; ADR-019 sets p95 absolute skew ≤10 ms as the next decision gate. Do not run longer tests until the foreground-service/wake-lock gate in ADR-022 is resolved.

The latest post-fix observed result is: Speaker ingress mostly near 50 frames/sec = encouraging; `missingFrames=0` = pass for the captured interval; contiguous buffer up to ~19.94 s = buffer requirement met; selected sample count/span = met; residual RMS ~0.412–0.557 ms = fail; stable streak = 0; READY = not demonstrated; ANCHOR = not reached in supplied evidence; Host queue/write state = not provided; acoustic skew = not measured. Do not mark M1/M2 group playback complete. The chronological evidence is in [Test Results](Test_Results.md), and the overall evidence table is in [Current Implementation Status](11_Current_Implementation_Status.md).

## Current measurement and platform boundaries — 7 November 2026

- **Audio backend:** Android currently uses `AudioTrack.MODE_STREAM` with a dedicated writer thread and a scheduled `AudioTrack.play()` call. Oboe/AAudio callbacks are not implemented. Start-time accuracy is unknown until measured; see ADR-019.
- **Acoustic toolchain:** current measurement branch overlays a 20 ms receiver-specific marker every second and provides reference WAV generation plus PCM16/48 kHz analysis. Synthetic offsets pass CI; no physical skew result exists.
- **Measurement APK:** the public v0.5.9-preview.1 release includes the receiver ingress diagnostics and the measurement toolchain from the v0.5.8 preview. Synthetic offsets pass CI; the post-fix physical run did not reach READY, so no physical skew result exists yet. See [the measurement guide](../tools/README.md) and [Test Results](Test_Results.md).
- **Security:** the session token remains in DNS-SD TXT metadata; use a trusted private LAN. See ADR-020.
- **Capacity:** 8 Speakers + Host is the preview cap. The product goal is 6–10 total phones, not a proof of capacity. See ADR-021.
- **Long tests:** no foreground playback service or explicit wake-lock lifecycle exists. Limit testing to the short, foregrounded, screen-on recording. See ADR-022.

## 1. Implementation strategy

Build the timing model and its tests before attempting a multi-phone product. The most expensive risk is synchronization correctness, not UI construction. Prove the clock estimator, timeline and drift controller in simulation; then validate each layer on physical Android devices.

**Performance-first rule:** optimize for the best sustained end-to-end synchronization, playback quality, reliability and group performance. Do not treat smaller code, lower CPU use or algorithmic elegance as the objective by themselves. Resource efficiency is a constraint, not the primary success metric. Measure bottlenecks, benchmark alternatives under equivalent conditions, and accept additional complexity or resource use when physical-device evidence shows a material playback-performance gain without unacceptable thermal, battery, memory or network cost. Never claim a synchronization improvement from simulator data alone when acoustic device measurements are required.

Do not build every feature in parallel. Each milestone must have a measurable exit gate and an implementation artifact.

## 2. Development sequence

### Phase 0 — Repository and engineering foundation

Deliverables:
- Confirm the directory structure.
- Add native CMake project skeleton. **Implemented:** `core/CMakeLists.txt` builds the portable C++17 timing library and its shared-vector tests.
- Add Python simulator skeleton.
- Add protocol version and golden-vector conventions.
- Configure CI to compile the core and run tests.
- Record decisions in ADRs.

Exit gate:
- Desktop build runs in CI.
- A test vector can be consumed by both Python and C++.

The implementation is now present. The CI gate for this branch must pass before this phase is treated as verified.

### Phase 1 — Time and drift simulator (M0)

Modules:
- Simulated master and receiver monotonic clocks.
- Configurable clock skew.
- Network delay, jitter, asymmetry, packet loss and spikes.
- Shared single-channel LAN queue: per-receiver PCM TCP segment fan-out, two-hop AP airtime, bounded MAC retries, control priority, and audio deadline/buffer metrics.
- Adaptive acquisition probes: 20 requests at 50 ms spacing, then 500 ms retries until three consecutive estimator updates meet the provisional gate, followed by 2 s steady-state probes.
- Offset estimator.
- DAC clock model.
- Timeline and drift controller.
- Metrics and scenario runner.

Scenarios:
- Ideal network.
- Receiver skews of +30 ppm and −40 ppm.
- Variable jitter.
- Asymmetric forward/reverse delay.
- UDP loss and burst loss.
- RTT spikes caused by scheduling/power saving.
- DAC clock drift.
- Late receiver join.
- Ten-receiver load.
- Combined impairment scenario.

Exit gate:
- M0 scenarios pass.
- The +30 ppm simulation case remains within the agreed 1 ms estimator/tracking gate.
- Offset, skew, convergence time, slip rate, hard resync count and resulting playout error are reported.
- The C++ timing implementation matches Python on the committed M0 golden vectors; C++/Python parity is a CI gate.
- A fixed-versus-adaptive cadence A/B report covers +30 ppm drift, high jitter and random packet loss using the same time-indexed network outcomes at overlapping timestamps.

#### M0 simulator acceptance criteria

The deterministic CI acceptance profile is fixed at **60 seconds, seed 7**. Run it with `python -m sync_in_it_sim --duration 60 --seed 7 --check-acceptance`. A failure exits non-zero.

| Scenario class | Acceptance criteria |
|---|---|
| Three clean cases: ideal, +30 ppm and −40 ppm clock drift | Three consecutive ground-truth updates within 1 ms offset and 5 ppm skew within 50 s; final absolute offset error <=1 ms; strict operational acquisition gate met; modeled frame-slip phase-error p95 <1 ms; zero hard resyncs |
| Noisy/stressed cases: high jitter, packet loss, burst loss, RTT spikes and combined impairment | Estimator accuracy still meets the convergence/offset checks where specified, but the strict 0.25 ms operational fit gate must keep the receiver not-ready when residual RMS is >=0.25 ms |
| Moderate asymmetric delay | Must not claim ground-truth convergence; final offset bias >1 ms; remain not-ready and do not hard-resync while modeled bias remains below 5 ms |
| Severe asymmetric delay | Must not claim ground-truth convergence; bias >5 ms and at least one hard resync. A low fit residual must not be mistaken for proof that path asymmetry is absent |
| Late join | Exactly 20 initial join probes, additional post-join probes and final absolute offset error <=1 ms; the scenario remains not-ready if the strict fit gate is not met |
| Ten-receiver isolated fan-out | At least 8/10 independent paths meet ground-truth convergence; maximum absolute final offset error <=1 ms. This does not assert the strict operational gate or shared-Wi-Fi capacity |
| Adaptive acquisition A/B | Adaptive ground-truth convergence is faster than fixed cadence in +30 ppm, high-jitter and packet-loss profiles |

For the operational acquisition gate, each receiver needs a skew estimate, at least 8 selected samples, at least 10 seconds of selected-sample span and residual RMS below 0.25 ms for three consecutive estimator updates. This is deliberately stricter than the TRD's provisional <1 ms residual baseline; the acceptance suite requires noisy scenarios to remain not-ready rather than silently weakening the gate. The ground-truth convergence diagnostic is a simulator-only validation measure; a real device cannot observe its own ground-truth clock error.

The criteria are provisional M0 regression gates calibrated to the current deterministic scenarios. They are not product guarantees. The frame-slip versus resampling comparison is diagnostic and does not select a production drift policy. The shared-Wi-Fi suite remains a separate model and is not converted into a capacity guarantee by these checks.

The shared vectors establish calculation parity, not physical timing accuracy. C++ networking, audio callbacks, jitter buffering and acoustic measurement are not part of this core task.

The ground-truth convergence diagnostic requires three consecutive estimator updates with absolute offset error at or below 1 ms and absolute skew error at or below 5 ppm. The operational acquisition gate requires a skew estimate, at least 8 selected samples, a selected-sample span of at least 10 seconds, residual RMS below 0.25 ms, and three consecutive updates meeting those conditions. These are distinct simulator diagnostics: the runtime can observe fit quality and sample span, but cannot observe ground-truth estimator error. The playout metrics come from simplified 20 ms control-policy simulations. The candidate resampler uses an ideal synthetic DAC-rate estimate in the initial A/B; a biased-estimate sensitivity test is included. Neither policy models a production audio callback, PCM interpolation filters, subjective artifacts or acoustic measurements.

M0 drift-control experiment (PR #7): the 60-second seed-7 CI comparison showed lower modeled phase-error p95 for the bounded resampling candidate in the nominal clock-drift and high-jitter cases, but worse p95 under asymmetric delay when the ±500 ppm adjustment limit was reached. The candidate uses an ideal synthetic rate estimate in the A/B. This is control-model evidence only; audio quality, filter behavior, runtime safety and physical-device performance remain untested. Results are recorded in `sim/README.md` and the development log.

### Phase 2 — Protocol and clock sync on two Android phones (M1)

Deliverables:
- Message framing and codecs.
- Control TCP listener/client.
- UDP time-sync request and response.
- Four-timestamp estimator.
- Receiver and master state snapshots.
- Time-sync telemetry.
- Minimal Android role-selection and join UI.

Exit gate:
- Two physical Android devices establish a session.
- Offset standard deviation is below the provisional 1 ms gate under a documented stable test setup.
- A-to-B and B-to-A measurements are compared.
- Raw RTT and timestamp samples are retained.

### Phase 3 — Scheduled playback (M2)

Deliverables:
- PCM source/test tone generator.
- Sample-index assignment and chunker.
- Receiver jitter buffer.
- Native Oboe/AAudio output.
- Timeline anchors and common T0 calculation.
- Click-track generation and recording analysis.

Exit gate:
- Two receiver phones begin a known click track from one scheduled anchor.
- Acoustic skew is measured and reported.
- No playback start is triggered by packet arrival.
- Buffer gaps and device timestamps are logged.

### Phase 4 — DAC mapping and manual compensation (M3)

Deliverables:
- Output timestamp poller.
- DAC clock regression.
- Safe snapshot publication to callback.
- Manual offset control.
- Device-specific diagnostics.

**M3a implementation in progress:** the Android AudioTrack adapter now polls best-effort frame-position/presentation-time pairs, unwraps its low-order 32-bit counter, and fits a 30-second rolling regression in the portable C++ core. The current app reports the fitted rate error, residual, sample span and rejected timestamp count. This is diagnostics only; it is not yet used to alter playback or compensate device latency. Callback-safe snapshot publication and manual offsets remain pending.

Exit gate:
- Output clock mapping remains stable through the test.
- Manual adjustment moves acoustic output in the expected direction.
- Two-device skew reaches the provisional 10 ms-or-better gate under the defined test setup, or the failure is characterized with evidence.

### Phase 5 — Drift correction and endurance (M4)

Deliverables:
- Drift controller integrated into the audio callback.
- Slip and hard-resync telemetry.
- Mute/degraded escalation policy.
- 30-minute endurance test and fault injection.

Exit gate:
- Drift remains bounded.
- Callback has no allocation, locks, I/O or logging.
- Correction rates and audio artifacts are reviewed.
- Hard resyncs and underruns are explained.

### Phase 6 — Group control and reconnect (M5)

Deliverables:
- Full receiver group controller.
- Receiver reports and readiness policy.
- Late join/rejoin history ring.
- Admission and removal behavior.
- Network timeout and clean failure paths.

Exit gate:
- Three to four receivers tested.
- Disconnecting one receiver does not disturb the others.
- Rejoining receiver enters only at a future coordinated boundary.
- Capacity semantics are resolved before larger scale tests.

### Phase 7 — Audio source and codec expansion (M6)

Deliverables:
- Local-file decoder and source UI.
- Supported Android system-audio capture path.
- Explicit fallback when capture is blocked.
- Opus encoder/decoder behind the codec interface, if required.
- Comparative measurements for PCM and Opus.

Exit gate:
- File playback is reliable on the tested master device.
- Live capture is validated only for source apps that permit it.
- Codec changes do not regress timing or acoustic-skew gates.

### Phase 8 — iOS receiver (M7)

Deliverables:
- Swift receiver UI and connection lifecycle.
- AVAudioEngine output adapter.
- Objective-C++ bridge to the shared C++ core.
- Local-network permission and background audio handling.
- Mixed-platform diagnostics.

Exit gate:
- Physical iPhone joins an Android-hosted session.
- Same protocol vectors pass.
- Mixed Android/iOS playback is measured on a documented device set.

### Phase 9 — Acoustic calibration (M8)

Deliverables:
- Chirp generation and scheduling.
- Microphone capture and timestamp mapping.
- Matched filter and confidence score.
- Compensation solver.
- Calibration UI and persistence.

Exit gate:
- Repeated measurements are consistent at a fixed layout.
- Low-confidence measurements are rejected.
- Compensation moves arrival times toward the reference.
- T3 is reported only if measured and repeatable at the chosen listening point.

### Phase 10 — Scale and train trial (M9)

Deliverables:
- Capacity and router characterization.
- Ten-phone-class test, subject to final capacity definition.
- Endurance and fault-injection reports.
- Power/thermal measurement.
- Train-coach trial with documented device geometry and network conditions.

Exit gate:
- Report mean, p95 and maximum acoustic skew.
- Include receiver count, device models, router model, band/channel, run duration, packet/RTT behavior, calibration state and failure count.
- No tier claim without a repeatable report.


## 2.1 Final upgrade brief — execution order (7 November 2026)

This section supersedes conflicting sequencing in earlier snapshots. It does not change runtime thresholds or mark milestones complete.

### WP0 — Evidence tooling (M1 diagnostics; do first)

- Capture every clock probe: attempt ID, t1–t4, RTT, raw offset, selected/rejected/expired state and rejection reason.
- Capture each fit update: samples seen/used, selected span, slope/skew, residual, predicted offset at t4 and stable streak.
- Add a Host-side log correlated by the same session diagnostic ID: audio delivery, slow writes, queue depth/high-water/overflow and disconnects.
- Move readiness-gate configuration out of JNI literals into a shared core `ClockReadinessConfig`, used by Android and desktop replay. Core and device defaults must be the same object.
- Add a desktop replay command that runs the same C++ estimator and gate as the phone, with Python-reference parity and real-log golden fixtures.
- Verify cached 250 ms PCM snapshot diagnostics against a fresh native counter read. Treat `advances=0` as potentially stale until checked.
- Logs remain metadata/counters only; do not include audio.

**Exit gate:** offline replay of one physical run reproduces the on-device residual and stable streak within documented rounding.

### WP1 — Controlled clock A/B matrix

Run one factor at a time on the same two phones, 60 seconds per run and three repeats:

| Factor | Comparison |
|---|---|
| Access point | Phone hotspot vs dedicated router |
| Power state | Wi-Fi low-latency lock and CPU wake lock on/off |
| Capture | Default vs raised receive-thread priority; avoid logging/JNI between receive and timestamp capture |
| Probe style | Single probes vs paired probes retaining the lower RTT |
| Selection | Lowest 25%, lowest 50%, all samples and robust weighting |
| Slope | Free fit, clamped/smoothed fit and offset-only |

Record RTT p50/p95/range and timeouts, residual RMS, offset standard error (including its independence assumption), slope range, hold-out prediction error (fit first 70%, predict later low-RTT probes) and RTT-outlier share. Hold-out prediction error is the key scheduling metric and does not require an external clock reference.

Interpretation: outliers/time-localized residual spikes point to capture or selection; uniform ~0.5 ms scatter with low hold-out error motivates (but does not adopt) an uncertainty-based gate; slope swings correlated with hold-out error motivate slope constraints; router-only pass means hotspot is prototype-only.


### WP1b — Estimator stability and tracker replay experiments (ADR-026)

Run both candidates against the same chronological, replayable probe logs before replacing the current batch estimator.

**Candidate A — readiness hysteresis**
- Keep the current strict entry conditions as the baseline: at least 8 selected samples, at least 10 seconds of span, residual RMS <0.25 ms and 3 consecutive qualifying updates.
- Add a wider exit boundary (exit residual greater than entry residual) and a configurable number/duration of exit violations before dropping the acquired stable state. Choose the exit value and dwell only from replay results; do not invent a new threshold from the failing device run.
- Invalid/stale measurements, clock discontinuity or a lost timestamp basis must not be hidden by hysteresis.
- The current protocol has no explicit READY-revocation message. The Host treats repeated READY as idempotent, and the Speaker checks the latest acquisition state again when ANCHOR arrives. Define whether hysteresis is a local pre-ANCHOR acquisition latch or requires a Host-visible revoke/lease state before implementing a full enter/leave state machine.

**Candidate B — two-state offset/skew tracker**
- Track an offset/skew state continuously, propagate offset using elapsed monotonic time, and maintain the state covariance.
- Use each four-timestamp offset as a measurement. Evaluate an RTT-scaled measurement-noise term with calibrated floor/cap and innovation/outlier checks.
- Treat RTT only as a noise proxy: low RTT does not prove symmetric path delay, and an offset bias caused by directional asymmetry can remain even with a small RTT.
- Preserve the current batch regression over the lowest-RTT subset as the control implementation. Evaluate the tracker alone, hysteresis alone, then their combination; do not bundle both changes into one unexplained result.

**Replay comparison and exit gate**
- Replay the same physical log in timestamp order. Predict each held-out low-RTT measurement before assimilating it; prohibit future-sample leakage.
- Compare hold-out prediction error, offset-at-ANCHOR prediction, time-to-ready, stable-streak resets/flaps, false-ready/blocked transitions, skew stability, outlier behavior and (where simulation ground truth exists) covariance coverage/calibration.
- Keep the current runtime estimator and gate unchanged until a candidate wins on repeatable replay and is recorded in ADR-026/ADR-024 as appropriate.

### WP2 — Reconcile clock-readiness criterion (ADR-024)

The documents currently state materially different residual criteria: TRD baseline <1 ms; Testing doc provisional residual standard deviation <1 ms; Android JNI `stable_fit` residual RMS <0.25 ms; and core `ReceiverSession` default effectively unlimited (`UINT32_MAX`). This is an unresolved consistency issue, not permission to change the runtime gate.

Before the next acceptance run, amend ADR-024 with the T2 acoustic target (p95 ≤10 ms), explicit allocation of that budget to clock error, resulting maximum start-time offset uncertainty, minimum sample/span, uncertainty, hold-out error, bounded worst residual and streak requirements, plus golden-vector and document reconciliation. Do not change a threshold in the same patch as a failing run. No relaxation without replay evidence and an ADR.


### WP2b — Continuous output and output-backend experiment (M2/M3 dependency)

The current Android Speaker primes AudioTrack, waits for the local time derived from ANCHOR, then calls AudioTrack.play(). The DAC timestamp poller starts only after that call. The current DAC map therefore cannot schedule the first ANCHOR.

**Candidate — continuous timeline-driven rendering**
- Start the output stream after join/format negotiation, before the group ANCHOR. Emit silence while the session timeline is not yet playing.
- Start DAC timestamp acquisition while this silent stream is already running.
- Map ANCHOR.masterTime through the receiver's current Host-to-local clock mapping. Use the inverse DAC frame-to-local-time mapping to find the output-frame boundary corresponding to that local target time.
- Associate ANCHOR.sampleIndex with that output frame. For each output frame requested by the renderer, map its presentation time onto the shared session timeline and render the corresponding PCM sample; render silence before the anchor and while the timeline is paused.
- Publish clock/DAC mapping snapshots to the renderer safely. The callback must not allocate, lock, call JNI, perform I/O or log.
- Do not submit silence beyond the target frame if it is already committed to the platform output queue. The anchor lead time and writable-frame horizon must leave enough time to switch to source PCM without missing the boundary.
- If timestamp mapping is unavailable, stale, reset by a route change or outside its validated uncertainty, keep the receiver not-ready/degraded instead of claiming a precise frame start.

This changes the control point: play() starts a silent output stream during join; the ANCHOR chooses the first media frame on the already-running output timeline. It removes the scheduled play() invocation from the group-start decision, but does **not** prove that all downstream DAC, DSP, speaker or acoustic-path latency disappears. Route-specific output delay and physical acoustic skew still require measurement; manual trim/calibration are not automatically obsolete.

**Backend comparison on the two Xiaomi phones**
- Compare the current scheduled-start AudioTrack path, a continuously running AudioTrack path using PERFORMANCE_MODE_LOW_LATENCY, and Oboe/AAudio low-latency callback output (request exclusive sharing where supported and record whether it was actually granted).
- Record actual Oboe API (AAudio/fallback), sample rate, frames per burst, buffer size, timestamp availability/rate, frame/time regression residual and prediction error, reported latency, underruns/XRuns, callback timing and acoustic marker skew.
- Use the same two phones, built-in speaker route, negotiated/native-rate strategy, router, volume and microphone geometry. Repeat each variant; compare measured acoustic output, not API claims alone.
- Android's guidance recommends Oboe for lowest latency and low-latency performance mode for AudioTrack. That is a latency recommendation, not evidence that Oboe timestamps are more accurate on these phones. See the Android audio latency guide and both API timestamp references in the TRD.

**Exit gate:** the continuous path can identify and render the ANCHOR sample at the mapped output-frame boundary without underruns or missed-frame jumps; timestamp availability/fit is repeatable on both phones; the physical p95 acoustic skew is measured against T2 (≤10 ms). Do not select Oboe or continuous output solely from documentation or simulator results.

### WP3 — Physical playback acceptance (M2)

Prove one Host + one Speaker first: READY sent/accepted, common ANCHOR received, playback started and marker-based acoustic output measured. Then two Speakers, then three/four. Record `AudioTrack.play()` start latency per tested model. The next decision line is p95 absolute acoustic skew ≤10 ms (T2), using the same placement, route and measurement method. T1 ≤20 ms is the minimum readable result, not a substitute for the T2 decision.

### WP4 — Manual offset and callback-safe DAC snapshot (M3)

Pull the existing M3 manual-offset item earlier; this is sequencing, not a new milestone. Apply per-Speaker trim to the scheduled local start target in 1 ms increments with a coarse control. Persist by endpoint + output route. Keep manual trim separate from network clock offset and DAC mapping. Flag Bluetooth explicitly and require re-trim after route change. Publish the DAC mapper snapshot to the audio callback without locks or allocation.

### WP5 — Drift control (M4)

Connect the DAC mapper to the drift controller. Select bounded frame slips versus bounded resampling only after physical skew and artifact evidence; simulator A/B alone is not a production decision. Measure at 1, 5, 30 and 60 minutes. Add injected delay/jitter/loss only after lifecycle support exists.

### WP6 — Group control, late join and Host approval (M5)

Late join/rejoin from the Host history ring with a targeted ANCHOR at a future sample boundary; do not disturb already-playing Speakers. Add explicit Host approval alongside QR admission. Resolve ADR-013 capacity semantics before scale tests. Implement foreground-service and wake-lock lifecycle before screen-off/endurance testing.

### WP7 — Sources and codec (M6)

Add local-file decoding through the same PCM path. Evaluate file-sync as an alternative only if a target-group bandwidth test proves PCM distribution is limiting; current PCM is approximately 1.536 Mbps per Speaker and a 20-second buffer is approximately 18.3 MiB per Speaker. Keep Opus behind the codec interface and reject it if it regresses acoustic gates.

### WP8 — Later milestones

- Security: reviewed authenticated encryption/key derivation; no custom cryptography (ADR-015). Keep the prototype on trusted networks until then.
- iOS: native receiver on shared core; a PWA spike is optional research only.
- Microphone calibration and scale remain after physical timing validation.

### Deferred until acoustic validation

Do not advance Opus, provider/queue control, PWA receiver, microphone calibration or encryption hardening ahead of the first successful physical timing path. Evaluate file-sync at M6 against measured bandwidth, not as an assumed upgrade.

## 3. Repository layout

- `core/`: portable C++17 timing library, headers, source, CMake build and unit tests. The initial estimator, timeline, DAC clock and drift-policy modules are implemented.
- `android/`: one native Kotlin app for both Host and Speaker roles, Android services, Oboe/AAudio adapters and JNI.
- `ios/`: Swift Speaker app, AVAudioEngine adapter and Objective-C++ bridge (M7).
- `sim/`: Python reference simulator and deterministic scenarios.
- `protocol/`: wire-format definitions and golden test vectors.
- `tools/`: click-track generation, analysis and telemetry visualization.
- `docs/`: primary product and engineering documentation.
- `docs_original/`: preserved original design references.
- `frontend/`: informational React/TypeScript project website; not the mobile application.

This is the intended implementation layout; directories not yet present are not claimed as implemented.

### 3.1 Reference application structure

The supplied Gaming Cafe Manager project is a Flutter application. It is used as a reference for separation of screens, reusable widgets, models, services and theme—not as a framework or codebase to port. Its gaming-cafe models, persistence and business logic do not belong in Sync In It.

Sync In It retains the native Android architecture selected in ADR-002: Kotlin UI and lifecycle, JNI to a shared C++17 timing core, and Oboe/AAudio for audio. The same separation can be expressed with native Android components. The iOS Speaker remains Swift with AVAudioEngine. Do not move clock estimation, sample scheduling or drift control into UI code.

## 4. Module implementation order

| Order | Module | Depends on |
|---:|---|---|
| 1 | Types and injected clock | None |
| 2 | Protocol structs and codecs | Types |
| 3 | Python estimator reference | Clock model |
| 4 | C++ offset estimator | Types, vectors |
| 5 | Timeline | Types |
| 6 | DAC clock map | Clock and timestamp model |
| 7 | Jitter buffer | Sample index and PCM layout |
| 8 | Drift controller | Timeline and DAC map |
| 9 | Networking | Protocol and sockets |
| 10 | Session master/receiver | Networking and core state |
| 11 | Android audio adapter | Jitter buffer and timeline |
| 12 | Group controller | Receiver reports and timeline |
| 13 | iOS audio adapter | Shared core |
| 14 | Calibration | Scheduling and microphone timestamp path |
| 15 | Telemetry/export | Events from all modules |

## 5. Definition of done for each feature

A feature is done only when:
- The behavior is documented.
- Inputs and outputs are defined.
- Invalid inputs and failure behavior are tested.
- Relevant metrics are emitted.
- Unit tests pass.
- Integration tests cover the platform boundary.
- Resource/lifecycle cleanup is implemented.
- No unverified performance claim is used as an acceptance result.
- Any architecture change has an ADR.

## 6. Branching and code review workflow

Suggested workflow:
- Main branch remains buildable.
- Use short-lived feature branches named by milestone/module.
- Keep commits focused and descriptive.
- Require CI build and relevant test suite before merging.
- Protocol changes must include updated golden vectors and compatibility notes.
- Timing changes must include before/after simulator results.
- Audio callback changes must include an explicit real-time safety review.
- Do not mix documentation-only changes with unrelated implementation changes.

## 7. Coding conventions

### C++
- C++17 baseline.
- Fixed-width integers for wire fields.
- Explicit time/sample types; units in names.
- RAII for resource management.
- No exceptions across the platform bridge unless explicitly handled.
- No allocation or blocking in audio callbacks.
- Pure functions for serialization and estimator calculations where possible.

### Kotlin/Swift
- Keep UI state separate from service/audio engine state.
- Make permission and lifecycle transitions explicit.
- Do not call heavy native work on UI threads.
- Handle interruptions and route changes as state transitions.

### Python
- Deterministic seeds for randomized tests.
- Reference equations and golden vectors shared with C++.
- Keep simulator ground truth available for error calculations.

## 8. Build and CI gates

Initial CI should:
1. Configure and build the C++ core with CMake.
2. Run native unit tests.
3. Run simulator scenarios.
4. Run Python/C++ golden-vector parity tests.
5. Validate formatting/linting when chosen.
6. Package test artifacts and metrics for failed scenarios.

Android and iOS CI may be expanded after native project skeletons exist. Physical-device audio tests cannot be replaced by CI simulator tests.

## 9. Implementation risks

- Android background execution and audio capture restrictions.
- Native output timestamp quality varies by device.
- iOS requires a Mac and real hardware for meaningful audio validation.
- TCP stalls under packet loss may exceed the buffer.
- A simple frame-slip controller may create artifacts.
- Calibration may be invalidated by phone movement.
- Building all layers at once will make failures difficult to isolate.

## 10. Immediate next engineering actions

1. **Implemented:** define and enforce scenario-level acceptance thresholds for nominal, degraded/asymmetric, late-join and ten-receiver simulator cases. CI runs the deterministic acceptance gate.
2. Review the frame-slip versus bounded-resampling results; do not select a production policy until PCM artifacts, callback safety and physical-device output timestamps are tested.
3. Extend cross-language vectors when estimator or timing behavior changes.
4. Calibrate the shared-airtime model against packet captures and real AP/device measurements; it omits full CSMA/CA, TCP retransmission and router-specific scheduling.
5. Validate retry traffic and acquisition behavior under measured shared-Wi-Fi contention before carrying this cadence into the Android runtime.
6. Complete the M0 exit gate before beginning M1 Android networking.

## 11. Source documents

This plan maps the current M0–M9 roadmap in [docs/Sync Engine.md](../docs_original/Sync%20Engine.md) and [docs/System Structure.md](../docs_original/System%20Structure.md) into implementation phases. No milestone is marked complete by this plan.
