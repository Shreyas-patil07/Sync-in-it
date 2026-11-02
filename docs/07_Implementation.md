# Implementation Plan — Sync In It

**Document status:** Active execution plan  
**Version:** 1.4  
**Last updated:** 2 November 2026

**Current progress:** M0 is in progress. The Python reference simulator has deterministic impairment scenarios, playout diagnostics and adaptive acquisition cadence (500 ms while unstable, 2 s after three consecutive stable-fit updates). A portable C++17 timing core implements the four-timestamp offset formula, lowest-RTT offset/skew estimator, selected-fit sample-span reporting, timeline sample mapping, DAC clock mapping and the initial drift-controller policy. Both languages consume the shared `protocol/vectors/m0_timing.json` test vectors. No phone synchronization or audio playback has been validated.

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

The shared vectors establish calculation parity, not physical timing accuracy. C++ networking, audio callbacks, jitter buffering and acoustic measurement are not part of this core task.

The ground-truth convergence diagnostic requires three consecutive estimator updates with absolute offset error at or below 1 ms and absolute skew error at or below 5 ppm. The operational acquisition gate requires a skew estimate, at least 8 selected samples, a selected-sample span of at least 10 seconds, residual RMS below 0.25 ms, and three consecutive updates meeting those conditions. These are distinct simulator diagnostics: the runtime can observe fit quality and sample span, but cannot observe ground-truth estimator error. The playout metrics come from a simplified 20 ms control-policy simulation; they are not acoustic measurements and do not model a production audio callback or continuous resampling.

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

1. Define and enforce scenario-level acceptance thresholds for the M0 simulator, including asymmetric-delay and degraded-sync behavior.
2. Review the initial frame-slip controller against a performance-first audio-quality strategy; compare controlled rate adjustment/resampling before treating the policy as final.
3. Extend cross-language vectors when estimator or timing behavior changes.
4. Validate retry traffic and acquisition behavior under packet-level shared-Wi-Fi contention before carrying this cadence into the Android runtime.
5. Complete the M0 exit gate before beginning M1 Android networking.

## 11. Source documents

This plan maps the current M0–M9 roadmap in [docs/Sync Engine.md](../docs_original/Sync%20Engine.md) and [docs/System Structure.md](../docs_original/System%20Structure.md) into implementation phases. No milestone is marked complete by this plan.
