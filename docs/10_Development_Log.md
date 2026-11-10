# Development Log — Sync In It

**Document status:** Active log  
**Created:** 1 November 2026  
**Purpose:** Record verified project changes, decisions and test evidence.

## 1. Logging rules

- Record completed work only after it exists in the repository or has been run and verified.
- Separate implementation progress from planning progress.
- Reference the relevant commit, pull request, file or test artifact where available.
- Do not mark a milestone complete because its document or checklist was written.
- Record failed tests and reverted experiments; do not silently remove unfavorable results.
- Keep detailed measured results in individual test reports and link them here.

## 2. Project baseline

### 1 November 2026 — Documentation restructuring started

**Type:** Documentation / planning  
**Status:** Completed for this documentation commit

- Inspected the existing repository documentation:
  - docs_original/Sync Engine.md
  - docs_original/System Structure.md
  - README.md
- Established `docs/` as the primary 10+1 documentation set based on the original design.
- Preserved the two original design documents in `docs_original/` as reference sources.
- No application code or test implementation was completed by this documentation activity.
- No M0–M9 milestone is marked complete.

### 2 November 2026 — Licensing files added

**Type:** Documentation / decision  
**Status:** Completed  
**Commit/PR:** [74b6b5b157faee051a737466dda2ed56b74b5f5a](https://github.com/Shreyas-patil07/Sync-in-it/commit/74b6b5b157faee051a737466dda2ed56b74b5f5a)  
**Summary:** Added `LICENSE` (Apache-2.0), `LICENSE-DOCS` (CC BY 4.0), `NOTICE`, `LICENSING.md` and `THIRD_PARTY_NOTICES.md`; updated the README license section and accepted ADR-017.  
**Verification:** All five root licensing files are present; README license references resolve; ADR-017 records the split-license decision.  
**Result:** Code, documentation, brand assets and third-party components have an explicit licensing map.  
**Risks or limitations:** `NOTICE` uses the GitHub account name as the copyright holder. Confirm the right to reserve the logo/name before relying on the brand-rights statement. Dependency license entries must be re-verified for exact versions at release. Not legal advice.  
**Next action:** Follow the current tracker and test evidence index below; M0 simulator and C++ parity checks are now passing in CI.

## 3. Current engineering status — 6 November 2026

| Area | Current state | Evidence required to advance |
|---|---|---|
| Product scope | Product target 6–10 phones total; actual Android cap 8 Speakers plus Host | Validate real group capacity before raising the cap |
| M0 simulator and C++ timing core | CI passing on Python 3.11/3.12/3.13 and C++17 | Continue synthetic regression; it does not prove acoustic performance |
| M1 LAN discovery and clock acquisition | Android code implemented; latest supplied run connected but did not reach READY | Compare Speaker READY_GATE_BLOCKED and Host HOST_AUDIO_DELIVERY counters |
| M2a PCM16 codec and native buffer | Implemented with native tests | Verify contiguous PCM on two physical phones |
| M2b Android PCM delivery and AudioTrack writer | Implemented; group start has not been physically proven | Get two Speakers through READY and common ANCHOR |
| M2c group admission and fan-out | Implemented; 15 s admission, 45 s readiness deadline, 8 Speaker cap | Physical group-start validation; no scale claim |
| M2d shared start ANCHOR | Implemented in code | Capture and analyze acoustic output |
| M3a DAC output-clock mapping | AudioTrack timestamp polling and rolling regression; diagnostics only | Compare device logs with acoustic recording |
| Acoustic skew tooling | Implemented; synthetic offset tests pass in CI | First real two-phone WAV and repeatability run |
| M3b/M4 offsets, drift and endurance | Not integrated or validated | Wait for acoustic result; add foreground service/wake locks before long tests |
| Security | Session token is visible in DNS-SD TXT metadata | Choose pairing/token design in ADR before changing protocol |
| iOS, user-media source, calibration and train trial | Planned | Separate implementation and measurement milestones |

## 4. Milestone tracker

| Milestone | Description | Status |
|---|---|---|
| M0 | Python simulator, shared vectors and portable C++ timing core | CI complete/passing; synthetic only |
| M1 | Android discovery, TCP admission and UDP clock acquisition | Implemented; supplied physical run failed before READY |
| M2a | PCM16 codec and native sample-indexed jitter buffer | Implemented/tested |
| M2b | Android PCM delivery and AudioTrack output | Implemented; no physical group-start proof |
| M2c | Multi-Speaker admission and fan-out | Implemented; cap 8 Speakers; READY gate still blocks |
| M2d | Shared future-T0 ANCHOR and scheduled group start | Implemented in code; physical start not observed |
| M3a | AudioTrack output timestamp regression | Implemented as diagnostics only |
| Acoustic measurement | Receiver-specific markers and WAV analyzer | Implemented; synthetic CI tests pass; real recording pending |
| M3b/M4 | Callback-safe mapping, offsets, drift controller and endurance | Not complete |
| M5 | Reconnect, resilience and 4+ Speaker testing | Not validated |
| M6 | User-selected source, capture fallback and Opus | Planned |
| M7 | iOS receiver | Planned |
| M8 | Microphone-based acoustic calibration | Planned |
| M9 | 6–10 phone scale and train trial | Planned; preview limit is currently 8 Speakers plus Host |

## 5. Open decisions and blockers

1. Audio backend: the current implementation uses AudioTrack MODE_STREAM and a worker thread; Oboe/AAudio callbacks are not implemented. See ADR-019.
2. Session admission security: the 16-byte token is exposed in DNS-SD TXT metadata. Decide on host approval or short-lived/out-of-band pairing before untrusted-network use; see ADR-020.
3. Capacity: retain the preview cap of eight Speakers (nine devices including Host). The 6–10 total-phone target is not a capacity test result; see ADR-021.
4. Long-running execution: no foreground playback service or explicit wake-lock lifecycle exists. Long/endurance tests are blocked until this is implemented; see ADR-022.
5. Review the NOTICE copyright holder and project name/logo reservation before release.

## 6. Test evidence index

| Run ID | Date | Devices / setup | Result | Evidence and limitations |
|---|---|---|---|---|
| LAN-READY-2026-11-06 | 6 Nov 2026 | Host plus multiple Speakers; exact models, OS, router and duration absent from supplied log | Failed before READY | mDNS, control/audio TCP and UDP response observed; no READY; 45 s deadline removed peers; no group ANCHOR; no acoustic skew data |
| SYNTHETIC-ACOUSTIC-CI | 6 Nov 2026 | Python 3.11, 3.12 and 3.13; generated PCM16 samples | Passed | Analyzer recovered injected ±5–8 ms offsets within test tolerance; software validation only |
| PHONE-SKEW-001 | Pending | Two Android Speakers IDs 1 and 2; laptop microphone at equal distance | Not run | Requires a physical WAV recording after both Speakers reach READY and start from the common ANCHOR |

Do not infer missing hardware/router metadata. Store raw recordings outside Git unless explicitly approved and link them from the test report.

## 7. Change-entry template

### YYYY-MM-DD — Short title

**Type:** Planning / Implementation / Test / Bug / Decision  
**Status:** Planned / In progress / Completed / Blocked / Reverted  
**Commit/PR:** Link or SHA  
**Summary:** What changed  
**Verification:** Tests and evidence  
**Result:** What was observed  
**Risks or limitations:** What remains uncertain  
**Next action:** Specific next step


### 2 November 2026 — Host/Speaker workflow v2.0 and M0 simulator started

**Type:** UX specification / Implementation  
**Status:** In progress  
**Summary:**
- Replaced `docs/04_App_Flow.md` v1.0 with the supplied Host/Speaker workflow v2.0. User-facing role names are Host and Speaker; protocol/core names remain master and receiver.
- Updated the UI design brief to use Host/Speaker terminology and reference the workflow's Home, network-check, lobby, prepare, player, join, ready and problem-state flows.
- Added the initial dependency-free Python M0 simulator under `sim/`: simulated monotonic clocks, four-timestamp exchanges, directional delay/jitter/loss/spikes, lowest-RTT offset/skew estimation, sample timeline, DAC clock model, drift decisions, late join and a synthetic ten-receiver fan-out scenario.
- Used the supplied Flutter Gaming Cafe Manager project as a structural/UI reference only. Sync In It remains native Kotlin + JNI + C++17 + Oboe/AAudio under ADR-002; no Flutter runtime or gaming-cafe domain logic was copied.

**Verification:**
- `PYTHONPATH=sim/src python -m unittest discover -s sim/tests -v`: 9 tests passed.
- `PYTHONPATH=sim/src python -m sync_in_it_sim --duration 60 --seed 7`: all nine named scenarios and the ten-receiver fan-out scenario completed.
- In the synthetic +30 ppm case, estimated skew was 29.89 ppm and final offset error was -0.011 ms.

**Result:** M0 has a working reference-simulator baseline, but remains **In progress** and is not marked complete.

**Risks or limitations:** These are simulated results, not measurements from phones. The ten-receiver fan-out uses independent paths and does not model shared Wi-Fi airtime contention. M0 still needs fuller playout-error/drift tracking, scenario acceptance thresholds, shared golden vectors and C++ parity. No Android UI, network session or audio playback has been built yet.

**Next action:** Complete the M0 validation work, then begin the M1 native Android Home/minimal lobby/diagnostics and typed-address Speaker join flow.

### 2 November 2026 — M0 burst-loss and playout-tracking diagnostics

**Type:** Implementation / Test  
**Status:** In progress  
**Branch:** `feature/m0-tracking-metrics`

**Summary:**
- Added configurable contiguous burst loss to the network model, alongside independent packet loss.
- Added finite/range validation for simulated network profiles, clock rates and simulation inputs.
- Added progressive estimator diagnostics. Convergence is reported only after three consecutive updates satisfy the 1 ms offset-error and 5 ppm skew-error thresholds.
- Added a simplified 20 ms playout-control simulation that reports frame slips per minute, hard resync count, and p95/maximum/final modeled playout error.
- Added burst-loss, severe-asymmetry, and ten-receiver fan-out coverage to the scenario report.
- Updated the simulator CLI and documentation to define the new metrics and their limits.

**Verification:**
- `PYTHONPATH=src python -m unittest discover -s tests -v`: 18 tests passed locally on Python 3.13.
- `PYTHONPATH=src python -m sync_in_it_sim --duration 60 --seed 7`: 12 scenario reports completed.
- JSON output parsed successfully; Python bytecode compilation completed.
- The `fast_clock_30ppm` scenario reported 0.131 ms final offset error, 30.20 ppm estimated receiver skew, 55.0 s convergence time, 0.128 ms p95 and 0.143 ms maximum modeled playout error, 180 frame slips/minute, and zero hard resyncs.
- The `severe_asymmetric_delay` scenario reported about 7 ms offset bias and exercised one hard resync.

**Result:** The simulator now emits the M0 diagnostic metrics and exercises burst-loss and hard-resync paths. M0 remains **In progress**; no C++ parity, shared golden vectors, Android device timing or acoustic playback is claimed.

**Risks or limitations:** Playout tracking is a simplified 20 ms control-policy model, not a sample-accurate audio engine. Its error statistics are synthetic and sampled before each correction. The ten-receiver fan-out still models independent paths, not shared Wi-Fi airtime contention. The current one-frame slip policy does not implement continuous resampling/slew correction. GitHub Actions matrix results must be checked after the PR is opened.

**Next action:** Define shared estimator/timing golden vectors and implement the matching C++17 reference before starting M1 networking.


### 2 November 2026 — Portable C++17 timing core and shared M0 golden vectors

**Type:** Implementation / Cross-language parity  
**Status:** In progress  
**Branch:** `feature/m0-cpp-golden-parity`

**Summary:**
- Added the initial portable C++17 timing library under `core/`, built with CMake and no third-party runtime dependency.
- Implemented four-timestamp RTT/offset calculation, lowest-RTT filtering and linear skew estimation, timeline sample mapping, DAC frame/time mapping, and the initial bounded frame-slip/hard-resync decision policy.
- Added `protocol/vectors/m0_timing.json` as the shared expected-value source for both languages. Python tests read it directly; CMake generates a C++ test header from it.
- Added C++ invalid-input checks and CI jobs for C++17 build/parity across the existing Python 3.11–3.13 matrix.

**Verification (local):**
- Python 3.13: 23/23 tests passed.
- GCC 14.2, Release: CMake build and CTest passed.
- Clang 17, Release: CMake build and CTest passed.
- The shared vectors cover positive/negative offset arithmetic, low-RTT skew fitting with outlier rejection, time-window filtering, insufficient skew samples, timeline tie rounding, DAC drift in both directions, and drift-controller thresholds.
- The existing 60-second simulator run still produces 12 deterministic scenario reports.

**Result:** The first cross-language timing baseline is implemented and locally verified. It is not yet a complete M0 exit: the branch CI must pass, scenario acceptance thresholds still need to be explicit, and the drift-correction policy needs audio-quality evaluation. No physical-device, Android, acoustic or real-time callback result is claimed.

**Risks or limitations:** The core does not yet implement networking, jitter buffering, native audio output, audio callback scheduling or acoustic calibration. The playout simulator remains a simplified controller model. The Python/C++ vectors prove matching calculations only for their covered cases. The JSON vectors are timing vectors, not the wire-message serialization vectors still required before M1 protocol work.

**Next action:** Add scenario-level acceptance criteria and review higher-quality drift correction candidates before beginning M1 networking.


### 2 November 2026 — Adaptive acquisition cadence and fair A/B benchmark

**Type:** Simulator / Estimator diagnostics
**Status:** In progress
**Branch:** `feature/m0-adaptive-sync-acquisition`

**Summary:**
- Implemented the TRD acquisition cadence: 20 join probes at 50 ms spacing, 500 ms retries while unstable, then 2 s steady-state probes after three consecutive estimator updates meet the acquisition gate.
- Added selected-fit sample-span reporting to Python and C++ estimator results with shared JSON golden-vector coverage.
- The provisional simulator acquisition gate requires a skew estimate, at least 8 selected low-RTT samples, a selected-sample span of at least 10 seconds, and residual RMS below 0.25 ms for three consecutive updates. This is stricter than the TRD's initial <1 ms residual baseline.
- Changed non-burst network scenarios to use deterministic time-indexed random streams. Fixed and adaptive A/B runs therefore see the same delay/loss outcome at overlapping probe timestamps. Burst-loss profiles retain sequential burst state and are not part of this A/B comparison.
- Added fixed-versus-adaptive A/B reports for +30 ppm drift, high jitter and random packet loss.

**Verification (local):**
- Python 3.13: 26/26 tests passed.
- GCC 14.2 Release: CMake build and CTest passed.
- Clang 17 Release: CMake build and CTest passed.
- Default 60-second run, seed 7: fixed versus adaptive ground-truth convergence was 21.0 s vs 7.5 s (+30 ppm), 53.0 s vs 32.0 s (high jitter), and 55.0 s vs 34.5 s (random packet loss). Probe counts were 50 vs 73, 50 vs 139, and 50 vs 139 respectively.
- Across 40 seeds per 60-second profile, median convergence and success counts changed from 34.0 s (34/40) to 22.0 s (39/40) for +30 ppm; 41.0 s (21/40) to 26.0 s (39/40) for high jitter; and 48.0 s (18/40) to 29.0 s (36/40) for random packet loss.

**Result:** Adaptive acquisition improved the synthetic ground-truth convergence distribution in these test profiles at the cost of more time-sync probes. The strict acquisition gate remained unmet at the end of the 40-run high-jitter and packet-loss samples, so those receivers continued retrying instead of being declared ready.

**Risks or limitations:** The benchmark is synthetic. Shared Wi-Fi airtime contention, router queueing caused by additional control traffic, physical device clock behavior and audio output are not modeled. The strict acquisition gate has not been validated on phones. No physical-device timing or acoustic performance is claimed.

**Next action:** Model shared-LAN contention and define scenario acceptance thresholds; then evaluate higher-quality drift correction before starting M1 networking.


### 2 November 2026 — Shared Wi-Fi airtime and router-queue model

**Type:** Simulator / Network contention model  
**Status:** In progress  
**Branch:** `feature/m0-shared-wifi-contention`

**Summary:**
- Added a discrete-event shared-airtime model for unicast audio and UDP clock-sync traffic. Each packet traverses client-to-AP and AP-to-client hops on one half-duplex medium.
- Modeled per-hop payload airtime, MAC overhead, preamble, ACK, random contention backoff, bounded MAC retries, AP forwarding delay and a bounded shared queue.
- Added strict priority for waiting clock-sync packets and reserved queue capacity for control so sync traffic is not queued behind a large audio backlog. In-flight frames remain non-preemptive.
- Modeled 20 ms/3,840-byte PCM16 stereo chunks, 1,460-byte TCP segments, a 3-second live playback deadline and the TRD's 8-second jitter-buffer capacity.
- Adaptive probes use four-timestamp samples generated by the shared LAN model; the 500 ms retry cadence continues until three consecutive stable-fit updates are observed, then backs off to 2 seconds.
- Added fixed-versus-adaptive load cases for 3, 6 and 10 receivers at 72 Mbps, plus 10 receivers at 36 and 24 Mbps.

**Verification:**
- GitHub Actions: Python 3.11, 3.12 and 3.13 all passed. The job runs the full Python unit suite, C++17 build and golden-vector parity, the 60-second clock simulator and the 30-second shared-Wi-Fi simulation.
- LAN-specific tests cover configuration validation, deterministic output, control priority, overload reporting, fixed probe cadence, adaptive acquisition backoff and equal audio workload under fixed/adaptive schedules.
- Corrected 30-second run, seed 7: at 3/6/10 receivers and 72 Mbps, adaptive probing changed audio p95 by +0.01/+0.02/+0.10 ms respectively, with 0% modeled chunk loss. At 10 receivers and 36/24 Mbps, the channel was saturated under both policies; modeled chunk loss was 26.953%/49.140% fixed and 27.440%/49.387% adaptive.
- Adaptive probing added 69, 136, 221, 207 and 208 probes across the five 3/6/10@72, 10@36 and 10@24 Mbps profiles. Every receiver met the operational acquisition gate by the end of the 30-second run; no delivered chunk exceeded the 3-second deadline.

**Debugging note:** An earlier implementation scheduled follow-up probes after each successful join-burst response, in addition to the pre-scheduled burst. This duplicated retry chains and inflated probe totals. That benchmark output was discarded. The response handler now schedules successors only for post-join acquisition probes, and an exact fixed-cadence count test protects the behavior.

**Model limitations:** This is not a full IEEE 802.11 MAC. It omits CSMA/CA carrier sensing, rate adaptation, aggregation/block-ACK, OFDMA/MU-MIMO, hidden nodes, TCP congestion/retransmission, multicast and router-specific firmware queues. MAC retry exhaustion is treated as segment loss, so chunk-loss results are not application-level TCP loss predictions. The control-priority rule is a model approximation; actual Android socket/DSCP/WMM behavior must be measured. No phone/router/acoustic performance claim is made.

**Next action:** Calibrate the model with packet captures and real LAN measurements before using it for capacity decisions.


### 2 November 2026 — Frame-slip versus bounded-resampling comparison

**Type:** Simulator / Drift-control experiment / CI verification  
**Status:** In progress  
**Branch:** `feature/m0-resampler-comparison`  
**PR:** https://github.com/Shreyas-patil07/Sync-in-it/pull/7

**Summary:**
- Preserved the existing dead-band, one-frame drop/duplicate and hard-resync policy as the baseline.
- Added an experimental bounded resampling controller to the Python simulator. It uses feed-forward rate compensation from a synthetic DAC/output-rate estimate, a four-second proportional phase-correction horizon, a ±500 ppm adjustment limit, a 1,000 ppm/s slew limit and the same 240-frame hard-resync gate.
- Ran both strategies over the same initial phase error and effective rate mismatch. Added p95/maximum/final phase error, RMS/peak rate adjustment, saturation and hard-resync metrics.
- Added tests for correction direction, rate/slew limits, invalid inputs, +2 ppm measurement bias and hard-resync behavior.
- Updated the CLI and simulator/implementation documentation. The comparison is deliberately kept in the simulator; no production C++ or Android policy was changed.

**Verification:**
- GitHub Actions: 41/41 Python tests passed on Python 3.11, 3.12 and 3.13.
- C++17 CMake build and shared Python/C++ golden-vector parity passed unchanged.
- The deterministic 60-second simulator completed all clock scenarios and the 30-second shared-Wi-Fi suite.
- In the +30 ppm receiver / +35 ppm DAC case, frame-slip correction reported 186.1 slips/min and 0.135 ms p95 phase error; bounded resampling reported 0.016 ms p95, 0.033 ms maximum and 66.6 ppm RMS / 74.3 ppm peak rate adjustment.
- In the −40 ppm receiver / −30 ppm DAC case, frame-slip correction reported 205.4 slips/min and 0.125 ms p95; bounded resampling reported 0.039 ms p95 and 0.083 ms maximum.
- Under high jitter, p95 changed from 0.096 ms (frame-slip) to 0.054 ms (resampling).
- Under asymmetric delay, resampling p95 was 1.668 ms versus 0.123 ms for frame-slip; the resampler reached its 500 ppm adjustment limit. Under severe asymmetry, both policies recorded one hard resync.
- The +2 ppm rate-estimate-bias test kept the 59-second final modeled phase error below the 0.02 ms test bound.

**Result:** Bounded rate correction looks promising for steady clock-rate mismatch and avoids repeated discrete frame edits in this synthetic model. It is not universally better: a larger initial phase error caused by asymmetric delay can take too long to remove under the configured rate cap. The evidence supports separating initial phase alignment from long-term drift tracking, not replacing the current policy outright.

**Risks or limitations:**
- The initial A/B supplies an ideal synthetic DAC-rate estimate. The +2 ppm test is one bias sensitivity point, not a measurement distribution.
- No PCM is rendered. Interpolation filter response, transient artifacts, aliasing, CPU/thermal load, callback deadlines, real DAC timestamp quality and acoustic output are not modeled.
- Frame-slip p95/max and resampler p95/max are sampled phase-error diagnostics, not sound-quality measurements.
- The candidate is not implemented in C++ or integrated into Android.

**Next action:** Define scenario-level M0 acceptance thresholds, including separate limits for estimator convergence, steady-state phase tracking, resyncs and degraded/asymmetric network behavior. Keep the production correction policy open until PCM and physical-device testing.



### 2 November 2026 — Deterministic M0 scenario acceptance gates

**Type:** Simulator / Acceptance criteria / CI verification  
**Status:** Implemented; awaiting PR review  
**Branch:** `feature/m0-scenario-acceptance-gates`  
**PR:** https://github.com/Shreyas-patil07/Sync-in-it/pull/10

**Summary:**
- Added `sim/src/sync_in_it_sim/acceptance.py` to evaluate the deterministic 60-second, seed-7 M0 scenario suite.
- Added CLI `--check-acceptance`; it is restricted to the reference 60-second/seed-7 run and exits non-zero when a required criterion fails.
- Added a CI step that enforces the acceptance suite on every Python matrix job.
- Split estimator accuracy from operational readiness. Three clean baseline scenarios must meet the strict operational fit gate. High-jitter, loss, burst-loss, RTT-spike, combined-impairment, moderate-asymmetry and late-join cases must remain not-ready when the strict residual condition is not met.
- Added explicit degraded-path checks: moderate asymmetry must not be reported as ground-truth convergence; severe asymmetry must expose >5 ms bias and trigger a hard resync.
- Added ten-independent-receiver fan-out ground-truth checks and fixed-versus-adaptive convergence comparisons.
- Set the clean baseline clock-only profiles to 0.05 ms network jitter so they isolate clock behavior; stressed profiles retain their impairment settings.
- Kept the simulator's 0.25 ms residual gate. An attempted relaxation to 1.0 ms caused the packet-loss adaptive schedule to stop meeting ground-truth convergence and was reverted. The 0.25 ms operational threshold remains stricter than the TRD's provisional 1 ms residual baseline.

**Verification:**
- GitHub Actions run: https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41452431470
- Python 3.11: 46/46 tests passed; M0 acceptance 65/65.
- Python 3.12: 46/46 tests passed; M0 acceptance 65/65.
- Python 3.13: 46/46 tests passed; M0 acceptance 65/65.
- C++17 CMake build and shared Python/C++ golden-vector parity passed.
- The 60-second clock scenarios and 30-second shared-Wi-Fi scenarios completed successfully.

**Result:** The current deterministic M0 reference suite has enforceable pass/fail criteria. Readiness is not inferred from low ground-truth error alone; noisy scenarios remain not-ready when the operational fit gate fails. M0 remains **In progress** and PR #10 has not been merged.

**Risks or limitations:**
- Ground-truth convergence is available only inside the simulator; a runtime phone cannot observe its true clock error.
- Ten-receiver fan-out uses independent paths and does not validate shared-Wi-Fi performance.
- The 0.25 ms fit threshold is provisional and has not been validated against physical Android clock/network measurements.
- The severe-asymmetry case demonstrates that a low fit residual alone cannot prove the path is symmetric.
- No Android networking, audio callback, PCM rendering, acoustic synchronization or physical-device timing is covered by this PR.

**Next action:** Review and merge PR #10. After that, prepare the M1 Android protocol/session implementation while keeping the physical two-phone timing test as its exit gate.



### 2 November 2026 — M1 wire protocol vectors and portable framing codec

**Type:** Protocol foundation / Cross-language parity  
**Status:** Implemented; awaiting CI and PR review  
**Branch:** `feature/m1-wire-protocol-vectors`

**Summary:**
- Added canonical byte-level vectors for all 15 control message types, 960-frame PCM16 audio, gap and silence chunks, unknown framed messages, malformed frames, and UDP time-sync request/response packets, including negative timestamps and unsigned boundary values.
- Added strict platform-neutral TCP framing and fixed-size UDP packet codecs in Python and portable C++17.
- Added Python and C++ tests that consume the same JSON fixtures; CMake generates the C++ fixture header from that source.
- Kept typed control-payload parsing, socket I/O, session authentication and encryption outside this framing-only change.

**Verification:** Pending GitHub Actions on Python 3.11–3.13 and C++17.

**Limitations:** The codecs implement the framing/packet envelope, not typed field-level encoding/decoding for each control payload. The 38-byte UDP packet size and version byte are the concrete encoding of the fixed padded request/response format. Session/receiver identity validation remains a session-layer responsibility. No Android sockets or physical devices are included in this slice.

**Next action:** Build typed protocol payload codecs and stream/session transport adapters, then begin the M1 Android role/session shell.


### 2 November 2026 — M1 typed control-message codecs

**Type:** Protocol implementation / Cross-language parity  
**Status:** Implemented; awaiting CI and PR review  
**Branch:** `feature/m1-typed-control-codecs`

**Summary:**
- Added typed structures and encoders/decoders for HELLO, WELCOME, AUDIO_HELLO, READY, REPORT, ANCHOR, MUTE, SET_COMP, RESYNC, LEAVE, KEEPALIVE, ERROR, VOLUME, CALIB_CHIRP and CALIB_ACK in Python and C++17.
- Enforced exact fixed/variable payload sizes, UTF-8 length-prefixed model/detail strings, boolean bytes restricted to 0/1, ANCHOR kinds 0–4, and VOLUME gain 0–100.
- Added 19 shared negative payload fixtures and Python/C++ tests. Existing valid control-frame fixtures are now decoded into typed messages and re-encoded byte-for-byte.
- Preserved unknown numeric enum fields where the protocol document has not assigned a value catalogue; no undocumented enum values were invented.

**Verification:** GitHub Actions run https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41457826168 passed on Python 3.11, 3.12 and 3.13. The Python unit suite, C++17 build, timing/wire/message golden-vector tests, deterministic simulator, M0 acceptance checks and shared-Wi-Fi regression all passed in each matrix job.

**Limitations:** This layer does not implement audio-chunk typed decoding, TCP stream accumulation, sockets, session-state enforcement, token authorization, or transport encryption. Those remain separate layers. No Android or physical-device behavior is claimed.

**Next action:** Add a bounded TCP stream framer and then implement the platform-neutral control/time-sync transport session before Android UI integration.


### 2 November 2026 — M1 bounded incremental TCP stream framer

**Type:** Protocol implementation / bounded stream parsing  
**Status:** Implemented; CI passed  
**Branch:** `feature/m1-bounded-tcp-framer`

**Summary:**
- Added a Python `FrameStreamDecoder` and portable C++17 `FrameStreamDecoder` for arbitrary TCP byte fragments.
- Handle split length prefixes, split payloads, byte-at-a-time input, multiple frames in one read, and aggregate reads larger than the per-frame limit.
- Validate the four-byte length prefix immediately when complete; reject lengths below the fixed type/flags header or above 65,536 bytes.
- Retain at most one incomplete frame per connection (maximum 65,540 bytes including the prefix); clear parser state on malformed lengths.
- Added Python and C++ regression tests for every split point, coalesced frames, maximum-size frames, aggregate input and decoder recovery after malformed input.
- Updated the protocol specification, core/simulator READMEs and project README to distinguish the implemented protocol foundation from unimplemented socket/session and physical-device work.

**Limitations:** This component parses TCP byte streams only. It does not open sockets, schedule I/O, enforce session state, authenticate peers, parse audio payload fields, or guarantee bounded memory for the caller's returned collection of complete frames. Callers must use bounded socket-read chunks and bounded downstream queues. No Android or physical-device behavior is claimed.

**Verification:** [GitHub Actions run 41460064640](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41460064640) passed on Python 3.11, 3.12 and 3.13. The full Python suite, C++17 configure/build/CTest, deterministic M0 scenarios, M0 acceptance gates and shared-Wi-Fi regression all passed.

**Next action:** Implement the platform-neutral control/time-sync transport adapters with bounded queues, then connect them to the Android session shell. Keep the two-physical-phone time-sync test as the M1 exit gate.


### 2 November 2026 — M1 bounded transport and time-sync primitives

**Type:** Transport foundation / queueing / clock-sample correlation  
**Status:** Implemented; CI passed  
**Branch:** `feature/m1-transport-primitives`

**Summary:**
- Added a thread-safe, non-blocking C++17 bounded frame queue with independent frame-count and byte budgets, close/drain semantics, and explicit rejection results.
- Added a bounded UDP time-sync client that tracks outstanding sequence IDs, handles sequence wrap, validates response type/version/session/receiver/echoed t1, rejects replay/unsolicited responses, checks timestamp ordering and non-negative RTT, and supports cancellation/expiry.
- Added a time-sync responder that binds requests to the session/receiver identity expected for the authenticated control connection.
- Added conversion from an accepted four-timestamp exchange to the existing `SyncSample` estimator input.
- Added native C++ regression tests and CMake/CTest registration.
- Documented the platform boundary: native socket lifecycle remains in Android/Kotlin and iOS/Swift adapters; the shared core supplies portable queueing and protocol/session primitives.

**Verification:** [GitHub Actions run 41461559269](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41461559269) passed on Python 3.11, 3.12 and 3.13. The full Python unit suite, C++17 build and all four CTest targets, deterministic M0 scenarios, M0 acceptance gates and shared-Wi-Fi regression passed.

**Limitations:** No operating-system TCP/UDP sockets, connection retries, keepalive scheduler, authentication handshake, encryption, audio queue, Android app or physical-device exchange is implemented by this change. `TimeSyncClient` is single-owner-thread state; platform code must serialize access. Queue overflow is surfaced to callers and requires a deliberate policy.

**Next action:** Implement the native Android TCP/UDP socket adapter and receiver session state machine, then run an actual two-phone time-sync test. Do not claim M1 device synchronization complete until offset/RTT telemetry from physical phones is recorded.


### 2 November 2026 — M1 receiver control-session state machine

**Type:** Protocol lifecycle / receiver admission and playback state  
**Status:** Implemented; PR validation pending  
**Branch:** `feature/m1-receiver-session-state`

**Summary:**
- Added a master-side C++17 `ReceiverSession` per control connection.
- Enforced HELLO protocol-version, fixed-length session-token and platform checks before making WELCOME available.
- Bound AUDIO_HELLO to the session and receiver IDs assigned in WELCOME; mismatches close the connection.
- Enforced READY gates for stable clock, configured minimum buffered duration and maximum residual standard deviation.
- Added valid anchor transitions for START, PAUSE, RESUME, SEEK and coordinated delay change.
- Added DEGRADED → Synchronizing recovery requiring a fresh READY gate, plus terminal LEAVE/close behavior.
- Added regression tests for successful flow, authentication/identity failures, invalid ordering, readiness gates, anchors and recovery.

**Limitations:** This is a portable state machine, not a socket adapter or Android application. The caller remains responsible for per-connection event serialization, timeouts, admission capacity, receiver buffer allocation, cryptographic transport protection and physical-device validation.


### 2 November 2026 — M1 Kotlin/JVM TCP and UDP socket adapter

**Type:** Platform transport / socket lifecycle / loopback integration tests  
**Status:** Implemented; local tests passed, CI pending  
**Branch:** `feature/m1-kotlin-socket-transport`

**Summary:**
- Added a pure Kotlin/JVM transport module under `android/transport`; it uses Java networking APIs and is intended for the Android application, but is not itself an Android app.
- Added a master-side TCP listener and receiver-side TCP client with independent per-connection frame reads, 4-byte little-endian length validation, a 65,536-byte maximum frame bound, clean EOF/truncation handling, synchronized writes, TCP_NODELAY, keepalive and configurable timeouts.
- Added master-side UDP time-sync listening and receiver-side connected UDP client sockets. Both enforce the protocol's exact 38-byte v1 datagram envelope; the client socket filters inbound datagrams to the configured master endpoint.
- Added local loopback tests for TCP request/response, one-write coalesced frames, byte-fragmented frames, oversized and truncated TCP input, UDP request/response and invalid UDP sizes.
- Added an independent GitHub Actions workflow for the Kotlin module so the transport checks do not run the Vercel frontend build.

**Verification:** Local Kotlin/JVM compilation with `kotlinc` and five consecutive executions of the loopback test runner passed. Gradle/GitHub Actions validation is pending.

**Limitations:** No Android application module, manifest, runtime permission handling, foreground service, JNI bridge, C++ state-machine binding, authenticated transport encryption, retry scheduler, bounded outgoing queue or physical-phone test is included. The Kotlin TCP frame envelope must remain byte-compatible with the C++ core and shared protocol vectors. UDP packet content validation and four-timestamp correlation remain in the protocol/session layer.

**Next action:** Add the Android application shell and JNI bridge, connect socket events to the C++ `ReceiverSession` and `TimeSyncClient`, then validate a two-phone local-Wi-Fi join and time-sync exchange.


### 2 November 2026 — M1 Android application shell and JNI session bridge

**Type:** Android application foundation / native integration  
**Status:** Implemented; Android CI passed  
**Branch:** feature/m1-android-app-jni-shell

**Summary:**
- Added a root Android Gradle workspace with :app and :transport modules.
- Added a minimal launcher Activity and manifest with only Internet and network/Wi-Fi state permissions; no microphone, media projection, notification or foreground playback permissions are requested yet.
- Pinned Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin 2.2.10, JDK 17, Android SDK 36, NDK r30 and CMake 3.22.1.
- Added an Android CMake target that compiles the portable protocol, typed message and receiver-session sources directly from core/, keeping desktop Python/vector-generation dependencies out of the APK build.
- Added a JNI bridge with opaque native session handles, a synchronized handle registry, per-session locking, HELLO validation, encoded WELCOME frame retrieval, AUDIO_HELLO identity checks, READY gates, arm/anchor transitions and degraded-session recovery.
- Added the Kotlin NativeReceiverSession facade and a launch-time native smoke test for HELLO → WELCOME → AUDIO_HELLO → READY → ARMED.
- Added an Android-specific CI workflow that builds the debug APK, runs transport checks, and uploads the APK as a 7-day GitHub Actions artifact without invoking the frontend/Vercel build.

**Verification:** [Android app/JNI workflow 41472511194](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41472511194) passed. It assembled the debug APK, compiled the native C++ library for arm64-v8a and x86_64, and ran the Kotlin TCP/UDP loopback checks. The runtime JNI smoke test is included in the APK but has not yet been executed on an emulator or physical Android device.

**Limitations:** No foreground playback service, QR invitation flow, audio capture/decoder, PCM rendering, native audio callback, socket-to-session orchestration, encryption or two-phone time-sync measurement is included. The smoke test validates local JNI/session wiring only, not network or acoustic synchronization.

**Next action:** Pass the Android APK/NDK CI build, then orchestrate the Kotlin TCP/UDP listeners with the native session API and run a two-device Wi-Fi handshake/time-sync experiment.


### 2 November 2026 — M1 Android TCP admission handshake

**Type:** Android socket orchestration / JNI protocol integration  
**Status:** Implemented; Android/Kotlin/core CI passed  
**Branch:** `feature/m1-android-network-handshake`

**Summary:**
- Added a one-master/one-receiver Android host/join interface for devices on the same local Wi-Fi network.
- The master binds a TCP control listener and a separate audio-channel listener. WELCOME advertises the dynamically allocated audio port.
- The receiver sends HELLO over control TCP, validates WELCOME using the shared C++ decoder, opens the audio TCP channel and sends AUDIO_HELLO.
- Added JNI APIs to encode HELLO/AUDIO_HELLO, decode WELCOME, and route received HELLO/AUDIO_HELLO frames through the native `ReceiverSession`. Kotlin does not duplicate the control payload schema.
- Extended the Kotlin transport adapter with strict conversion between complete native-encoded frames and its `WireFrame` representation, including exact length-prefix validation.
- Added a launch-time device-local loopback integration test that exercises both TCP channels together with JNI and verifies the native session enters Synchronizing.
- Updated Android and project documentation to distinguish network admission from completed time synchronization or audio playback.

**Limitations:** This validates a local TCP/JNI handshake path, not a physical two-phone connection. UDP time-sync sockets are not yet connected to the C++ `TimeSyncResponder`/`TimeSyncClient`; no clock-estimator results, READY transition from live metrics, audio streaming, PCM playback, encryption or acoustic alignment are included. Only one receiver is currently admitted by the Android host UI.

**Verification:** [Android app/JNI workflow 41479374128](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41479374128) passed, including Android Gradle assembly, native builds for arm64-v8a and x86_64, and Kotlin transport checks. [Kotlin socket transport workflow 41479374090](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41479374090) passed. [M0 simulator/native core workflow 41479374219](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41479374219) passed across Python 3.11, 3.12 and 3.13. The APK includes the device-local two-channel loopback smoke test; GitHub CI assembled the APK but did not execute that Android runtime test. No physical two-phone result is claimed.

**Next action:** Integrate the 38-byte UDP time-sync exchange with the shared native responder/client, gather a join burst of clock samples, and only then permit READY when the existing estimator stability and residual gates are met.


### 2 November 2026 — M1 Android UDP time-sync acquisition

**Type:** UDP socket orchestration / shared native time-sync client, responder and estimator  
**Status:** Implemented; Android/Kotlin/core CI passed  
**Branch:** `feature/m1-android-network-handshake`

**Summary:**
- Added the portable C++ estimator and transport implementation to the Android JNI CMake target.
- Added JNI bindings for the shared `TimeSyncResponder`, `TimeSyncClient`, and `estimate_clock_offset` implementation. Kotlin does not duplicate the 38-byte packet parser, request correlation, offset calculation or skew regression.
- Captured receiver `t1/t4` and master `t2/t3` from monotonic clocks. UDP receive timestamps are recorded immediately after `DatagramSocket.receive` returns.
- The master binds UDP port 47802 after TCP admission, validates the packet's magic/version/session/receiver/sequence/timestamps in C++, and sends the response to the same IP that completed TCP admission.
- The receiver runs a 20-probe join burst at 50 ms intervals, retries every 500 ms while unstable, and switches to a 2-second interval after clock acquisition stabilizes.
- Implemented the simulator's acquisition condition: skew estimate present, at least 8 selected lowest-RTT samples, selected-sample span at least 10 seconds, residual RMS below 0.25 ms, and three consecutive passing estimator updates.
- On stable clock acquisition, the receiver sends a shared-codec READY frame. It currently reports 0 ms buffered audio; the master rejects that report with BufferInsufficient and remains in Synchronizing, preventing a false playback-ready state.
- Extended the app-launch loopback test to exercise a real local UDP request/response through the native responder, native correlator and estimator. It verifies that one sample cannot satisfy the stability gate.
- Updated the app status and engineering documentation to separate clock acquisition from audio buffering and playback.

**Limitations:** No audio packets, PCM buffer, renderer, playback start or foreground service are implemented. No result from two physical devices is claimed. The 0.25 ms / 10-second gate is a simulator-derived provisional acquisition threshold, not a measured phone guarantee. The 16-byte session token is not cryptographic transport protection; UDP should be used only on a trusted local network.

**Verification:** [Android app/JNI workflow 41485883288](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41485883288) passed on the final timing-schedule commit, including the NDK build for arm64-v8a and x86_64, APK assembly and Kotlin transport checks. [M0 simulator/native core workflow 41485883250](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41485883250) passed across Python 3.11, 3.12 and 3.13. [Kotlin socket transport workflow 41484743007](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41484743007) passed, including the UDP monotonic receive timestamp tests. The debug APK is uploaded as the Android workflow artifact. The launch-time native TCP/UDP loopback test is packaged in the APK but CI does not execute Android runtime code; it remains to be exercised on a physical device.

**Next action:** Run this build on two physical Android phones on the same Wi-Fi network. Record accepted sample count, RTT p50, offset estimate, skew estimate, residual RMS and selected-sample span before beginning audio-buffer implementation.


### 2 November 2026 — Android nearby host discovery

**Type:** Android LAN discovery / host advertising / UI join flow  
**Status:** Implemented; Android Gradle/JNI and native core CI passed  
**Branch:** `feature/android-nearby-host-discovery`

**Summary:**
- Added DNS-SD/mDNS service advertising with Android `NsdManager` using service type `_syncinit._tcp.`.
- A host now advertises a named Sync In It service with its control TCP port and protocol metadata.
- Added an Android NSD browser that discovers and resolves nearby host services, validates the application/version metadata, and returns the host IPv4 address, control port and session admission token.
- Serialized service resolutions to avoid exceeding Android NSD's outstanding-resolution limit when multiple devices are discovered.
- Added a receiver-side Nearby Sessions list. Tapping a discovered host automatically invokes the existing TCP HELLO/WELCOME/AUDIO_HELLO and UDP time-sync flow. Manual IP/token entry has been removed from the launcher UI.
- The browser holds a Wi-Fi multicast lock only while scanning and releases it when the activity leaves the foreground. Added `CHANGE_WIFI_MULTICAST_STATE` to the manifest for compatibility with older Android Wi-Fi stacks.
- Updated the README, Android guide and architecture document.

**Security limitation:** The current one-tap prototype includes the 16-byte session token in the DNS-SD TXT record. Any device browsing the same LAN can read it. It is an admission token, not cryptographic authentication or transport encryption. Use only a trusted private Wi-Fi network. Replace this with host approval or a short-lived pairing exchange before production/public use.

**Network limitation:** Discovery requires all devices to be on the same local network and the access point to permit mDNS/multicast and client-to-client traffic. Guest/client-isolated Wi-Fi networks may block it. The current app targets API 36; targetSdk 37 must implement Android 17 local-network permission or a supported privacy-preserving service-picker path.

**Verification:** [Android app/JNI workflow 41492793054](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41492793054) passed: Gradle APK assembly, JNI/native C++ builds for arm64-v8a and x86_64, and Kotlin transport checks. [M0 simulator/native core workflow 41492792949](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41492792949) passed across Python 3.11, 3.12 and 3.13. The debug APK is available as the Android workflow artifact. No physical multi-device discovery result is claimed until tested on phones.

**Next action:** Install the same discovery-enabled build on two Android phones on the same private Wi-Fi, verify that each host appears in the other's Nearby Sessions list, and tap to join.


### 2 November 2026 — Persistent Android preview signing and in-place updates

**Type:** Android distribution / APK signing / versioning  
**Status:** Implemented; CI validation pending  
**Branch:** `feature/android-nearby-host-discovery`

**Summary:**
- Preserved the preview package ID `com.syncinit.app.debug` so all future preview APKs target the existing installed app identity.
- Added optional persistent JKS signing to the Android Gradle build. When configured, both debug preview and release variants use the same preview certificate.
- Added a timestamp-based monotonic `versionCode` property, supplied by CI on each build, so newer APKs can be installed over earlier versions.
- Updated Android CI to build with the stable key when secrets exist and only upload an APK artifact when persistent signing is enabled. Compilation and tests can still run without the key; no misleading updateable artifact is published.
- Updated the GitHub release workflow to require the persistent signing secrets, verify the APK signature, and publish the next preview as `v0.3.0-preview.1`.
- Added keystore ignore rules and Windows PowerShell instructions for generating and backing up the key and configuring repository Actions secrets.

**Required repository configuration:** Add `SYNCINIT_PREVIEW_KEYSTORE_BASE64` and `SYNCINIT_PREVIEW_KEYSTORE_PASSWORD` under Settings → Secrets and variables → Actions. The alias is fixed as `syncinit-preview`; the key password reuses the store password.

**Migration limitation:** Earlier APKs were signed by ephemeral GitHub-runner debug keys. Those private keys were not retained, so an already installed disposable-key APK cannot be guaranteed to accept the persistent-key update. Plan one final uninstall/reinstall for the first persistent-key build. All subsequent APKs signed by the same keystore and using the same package ID with a higher versionCode install as updates without uninstalling. Losing the persistent keystore would break that update path.

**Verification:** Pending Android Gradle/NDK and native core CI on the final branch head. Release publication is blocked until the two repository secrets are configured.

**Next action:** Configure the two Actions secrets, rerun Android app/JNI workflow to publish a signed preview artifact, merge PR #23, then install the first persistent-key build once.


### 2 November 2026 — Android host listener readiness before LAN advertisement

**Type:** Android TCP host lifecycle / nearby discovery reliability  
**Status:** Implemented; CI validation pending  
**Branch:** `fix/android-host-listener-lifecycle`

**Observed device failure:** A receiver discovered a host at `192.168.1.35` but the TCP connection to port `47800` failed with `ECONNREFUSED`. mDNS discovery had succeeded, so the advertised service did not correspond to a reachable TCP listener at connection time. This is distinct from a time-sync or protocol-handshake failure.

**Changes:**
- Expose `TcpFrameServer.isListening` so the master can explicitly verify that the socket is bound and not closed.
- Reorder `MasterSessionHost.start()`: bind both TCP listeners, publish the listener references, verify readiness, and start the accept loop before registering the DNS-SD/mDNS advertisement.
- Advertise the service only after the control and audio TCP listeners are ready. This prevents discovery from racing ahead of server startup.
- Add a transport test asserting that a TCP server reports listening immediately after a successful bind.

**Validation:** Android Gradle/JNI build and Kotlin transport tests pending on this branch. This ordering fix addresses a discovery-before-listener startup race; if the error persists, the next investigation is host-process lifetime/background termination and Wi-Fi interface routing. Physical two-phone retest remains required.

**Device retest:** Keep the host app open in the foreground, tap Start hosting, confirm it says the TCP listener is ready on port 47800, then refresh Nearby Sessions on the receiver and tap the host.


### 3 November 2026 — M2a PCM16 audio protocol and jitter-buffer core

**Type:** Shared C++ audio protocol / bounded PCM buffering  
**Status:** Implemented; Android/JNI and C++/Python CI passed  
**Branch:** `feature/m2-pcm-audio-buffer`

**Summary:**
- Added `core/audio.h` and `core/audio.cpp` with the v1 AUDIO_CHUNK (`0x80`) encoder/decoder.
- The codec validates the 21-byte typed audio header, PCM16 format, channel count, sample-index range, exact payload length, silence-chunk rules, maximum TCP frame size and zero outer flags. Reserved inner chunk flag bits remain forward-compatible.
- Added a bounded sample-indexed PCM16 ring buffer with configurable capacity and one/two-channel support.
- The buffer accepts out-of-order sample ranges, rejects duplicate frames, drops fully late chunks, preserves the future part of partially late chunks, rejects chunks outside its capacity window, expands explicit silence chunks, inserts silence for missing data and tracks underrun frame totals.
- Buffer insert/read operations are bounded and mutex-protected for use by a network producer and dedicated audio writer thread; this implementation must not be called from a real-time audio callback.
- Added a six-case C++ audio test executable, including byte-for-byte decoding/re-encoding against both canonical PCM16 audio fixtures already present in `protocol/vectors/wire_protocol.json`.
- Added the new audio source to desktop CMake/CTest and Android JNI CMake builds.
- Updated protocol, architecture, Android and top-level project documentation.

**Limitations:** This is the portable protocol and buffer foundation only. The Android app does not yet connect incoming audio TCP frames to the buffer, choose/capture a master source, decode files or supported system playback, render PCM to AudioTrack/Oboe, or schedule playback from a common anchor. The master still reports 0 ms buffered audio; READY must remain blocked until actual buffered PCM is measured. No synchronized-audio or acoustic result is claimed.

**Verification:** [M0 simulator/native core workflow 41507741127](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41507741127) passed on the current PR head across Python 3.11, 3.12 and 3.13, C++17 build and six CTest targets. [Android app/JNI workflow 41507908658](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/41507908658) passed on the current PR head, including native builds for arm64-v8a and x86_64 and Kotlin transport checks. Physical audio source/output and two-phone playback tests remain outstanding.

**Next action:** Wire a deterministic PCM test source through the master audio TCP connection, decode chunks on the receiver into the jitter buffer, render them on a dedicated Android AudioTrack writer, then replace the test source with a user-selected local audio file or supported Android playback capture.

## 2026-11-03 — M2b Android PCM audio transport and output

- Added JNI access to the shared C++ PCM16 AUDIO_CHUNK encoder, decoder and bounded sample-indexed jitter buffer.
- Added the master-side generated 1 kHz timing-click test source, sent as 20 ms PCM16 chunks over the existing audio TCP connection.
- Added the receiver-side audio TCP reader, native jitter-buffer insertion, contiguous-buffer prefill gate and dedicated Android AudioTrack writer thread.
- Receiver playback begins after a 3.5-second contiguous prebuffer. READY is emitted only after the clock estimator meets its existing stability gate and at least 3 seconds remain buffered.
- This milestone validates the software path from master-generated PCM chunks to Android AudioTrack output. It does not establish synchronized acoustic playback, scheduled common-T0 starts, DAC timestamp mapping, drift correction, user media capture/decoding, or physical two-phone performance.
- The generated timing-click source is an integration test signal and will be replaced by the real master audio-source pipeline in a subsequent milestone.


## 2026-11-03 — Android LAN debugger and connection-stage tracing

**Type:** Android diagnostics / transport observability / test coverage  
**Status:** Implemented; Android/JNI, Kotlin transport and Python/C++ CI passed; physical-device retest pending

**Observed problem:** Two Redmi 9 Power devices fail to connect to each other in either host/receiver direction, while each can connect to the Redmi Note 12 Pro. Both Redmi 9 Power devices also work independently as host and receiver with the Note 12 Pro. The router isolation setting change did not resolve the pair-specific failure. The available screenshot showed a No route to host / EHOSTUNREACH failure while connecting to the advertised host control port. This is not evidence of an audio decoder or clock synchronization failure.

**Changes:**
- Added a copyable on-device Network debugger with a bounded 240-event log, timestamped status/discovery events, local network snapshot capture and clipboard export.
- Added active Android network details: active network transport, validation/metering state, interface name, assigned IP addresses/prefixes, DNS servers, route table/default route, active interfaces, MTU, device model/API and ABI.
- Added an IPv4 peer/subnet relation and route hint using the active LinkProperties; the debugger explicitly reports when the peer appears on-link versus outside the local interface prefix.
- Added unique join-attempt IDs and stage events for selected mDNS target, control TCP connect, HELLO write, WELCOME wait/decode, audio TCP connect, AUDIO_HELLO, audio pipeline creation/start and failure.
- Added connect duration, timeout, selected local endpoint, remote endpoint, connected state and nested OS exception chain to successful/failed TCP connection diagnostics.
- Categorized root socket failures including NO_ROUTE_TO_HOST, CONNECTION_REFUSED, CONNECT_TIMEOUT_OR_FILTERED, HOST_RESOLUTION_FAILED, LOCAL_BIND_FAILED and generic socket failures.
- Added host-side listener bind/readiness, active network snapshot, accepted peer endpoint, HELLO/AUDIO_HELLO and failed admission details.
- Added UDP socket endpoint reporting and correlated clock-probe diagnostics: local/remote ports, probe duration, response timeout counts, malformed/uncorrelated response counts, and categorized port-unreachable errors.
- Added host-side UDP peer pinning and counters for malformed datagrams, rejected source IPs/ports and native packet/session validation failures; valid responses record destination, byte count and send duration.
- Added mDNS service-found, resolve-failed, resolve-success, record rejection and advertisement registration events. Session tokens and Wi-Fi credentials are not written to the diagnostic log.
- Deliberately did not add a separate TCP probe, because a probe would consume the current one-receiver host's accept slot and interfere with the real join attempt.
- Exposed TCP local/remote endpoint properties and added transport assertions for successful endpoint reporting plus a deterministic refused-loopback-connect test that checks endpoint/timing/cause retention.

**Expected diagnostic workflow:** On both phones, open Network debugger → Capture local network state → Clear diagnostic log; start hosting on one device and join from the other; then Copy full diagnostic log from both devices. Compare interface/prefix, peer subnet relation, default route, selected source endpoint, and the same join-attempt stage on host and receiver.

**Interpretation:** NO_ROUTE_TO_HOST means the OS rejected the route/neighbor path before TCP admission; CONNECTION_REFUSED means the peer IP was reachable but no listener accepted the target port; a 5-second timeout suggests silently dropped/filtered connection traffic; failure at WELCOME or AUDIO_HELLO indicates TCP is working and the investigation has moved into protocol/admission. Subnet equality alone does not prove peer-to-peer reachability or rule out AP isolation/firewall policy.

**Validation:** GitHub Actions passed the Android debug APK/NDK build, Kotlin transport integration tests, APK signature verification, and Python 3.11/3.12/3.13 plus C++17 simulator/core matrix on commit `95bae222277230c9ef9538553577a2cb3abd8ffd`. The updateable debug APK was uploaded as workflow artifact `sync-in-it-android-preview-e13a473ea69a3e88305bea56384c64ee4932c0ab` (run `41518255946`). The new debugger has not yet been run on the three physical phones. No root cause for the Redmi-to-Redmi pair is claimed until both devices' exported logs are compared.

## 2026-11-03 — Mobile-hotspot route selection and self-discovery filtering

**Type:** Android networking / hotspot reliability  
**Status:** Implemented on branch; CI and physical-hotspot validation pending  
**Branch:** `feature/hotspot-network-binding`

**Summary:**
- Added `PeerNetworkSelector` to select the connected Wi-Fi `Network` whose route table best matches the discovered IPv4 host. Selection does not require `NET_CAPABILITY_INTERNET` or `NET_CAPABILITY_VALIDATED`, so a phone hotspot with no upstream internet is eligible.
- Bound receiver control and audio TCP sockets to the selected network's `SocketFactory`.
- Bound the receiver UDP clock-sync socket to the same selected Android `Network`. All three receiver transports now use one consistent network instead of independently relying on Android's process-default route.
- Added an actionable failure when no Wi-Fi route to the discovered host exists, plus diagnostics for the selected network, interface, matched route, internet and validation capabilities.
- Added local-interface address filtering so a phone does not offer its own mDNS-advertised session as a join target.
- Updated UI guidance to say that all devices may use the same phone hotspot or local Wi-Fi and that internet is not required.
- Added JVM transport tests proving TCP socket-factory injection and UDP datagram-socket injection.

**Design note:** The host continues to bind TCP/UDP listeners to wildcard addresses so incoming clients can reach it through the phone's hotspot interface. No app-managed hotspot creation has been added in this change; the user enables the Android phone hotspot through system settings.

**Physical validation required:**
1. Turn off access to any external router. Enable a phone hotspot on the Host and connect a Speaker to it.
2. Verify discovery and inspect `PEER_NETWORK_SELECTED`; the selected transport must be Wi-Fi even when `internet=false` and `validated=false`.
3. Confirm control TCP, audio TCP and UDP time sync reach READY.
4. Confirm the Host does not list its own session.
5. Test hotspot implementations from more than one Android manufacturer. Some hotspots may filter multicast discovery or client-to-client traffic; QR/manual pairing is a planned fallback if mDNS is blocked.

**Limitations:** This change does not add multi-receiver admission or scheduled common-T0 playback. The current host still admits one receiver. No physical hotspot result is claimed until tested on devices.

**Next action:** Pass Android/JNI and Kotlin transport CI, install the updateable preview on both phones, and run the router-free hotspot acceptance test before expanding to multi-receiver session management.

## 2026-11-03 — Multi-receiver Host admission and shared PCM fan-out

**Type:** Android session management / audio transport  
**Status:** Implemented on branch; CI and multi-phone validation pending  
**Branch:** `feature/multi-receiver-audio-fanout`

**Summary:**
- Replaced the single native receiver session with one independent native receiver state machine per admitted Speaker. All receivers share the host session ID and authentication token, but receive unique monotonically assigned receiver IDs.
- Kept the protocol wire format unchanged: each per-receiver WELCOME assigns its receiver ID; AUDIO_HELLO validates the same session/receiver pair; UDP time-sync packets are dispatched through native session validation to the matching receiver ID.
- Changed admission to continue accepting new control/audio socket pairs after each Speaker is admitted. Admission is serialized so a Speaker receives WELCOME before the Host accepts that Speaker's audio connection, preventing cross-pairing on the shared audio listener.
- Added a hard initial limit of 8 connected Speakers.
- Replaced per-receiver audio generation with one shared PCM source. A single encoded 48 kHz stereo PCM16 chunk/sample index is fanned out to each connected Speaker.
- Added a separate bounded 8-chunk (about 160 ms) audio send queue and writer per Speaker. A persistently slow receiver is disconnected on queue overflow so it cannot block all other receivers.
- Moved UDP time sync to a host-lifetime listener. The Host matches the packet against each same-IP admitted native session, pins the validated UDP endpoint independently per receiver, and responds only for the receiver ID authenticated by the native session.
- Added per-Speaker control readers and independent READY/LEAVE handling. A Speaker leaving no longer closes the whole Host session.
- Updated Host UI text and status to display the current connected Speaker count.
- Added a C++ core test proving two receiver state machines can share one session ID, use distinct receiver IDs, reach READY independently, and leave independently.

**Design constraints:**
- This is multi-receiver connection and stream fan-out, not yet scheduled synchronized playback. The generated test signal is shared and sample-indexed, but receivers still begin local playback after their own prebuffer and clock acquisition.
- The admission handshake is intentionally serialized; admitted Speakers stream concurrently. Concurrent HELLO/AUDIO_HELLO orchestration and a more explicit BUSY rejection frame can be added later if connection bursts or better overload UX require them.
- The initial limit is 8; performance and hotspot airtime need measurement on physical devices before increasing it.
- QR/manual pairing fallback, app-managed hotspot creation, user-selected audio, common future T0, DAC output timestamp mapping and drift correction remain separate work.

**Validation required:**
1. Connect Speaker 1, confirm READY, then join Speaker 2 while Speaker 1 remains connected.
2. Confirm both receive the same test stream and host shows 2/8 connected.
3. Send LEAVE/disable Speaker 1 and confirm Speaker 2 continues receiving audio and UDP clock replies.
4. Join a third Speaker; test admission to the 8-Speaker limit and observe the full-host response.
5. Confirm one slow/disconnected receiver does not stop the other audio writers.

**Next action:** Pass Android/JNI and native-core CI; then run the two-Speaker test before publishing the multi-receiver preview.


## 2026-11-04 — Android coordinated group playback

**Type:** Android group timeline / PCM prebuffer / playback scheduling  
**Status:** Implemented on `feature/coordinated-group-playback`; CI and physical-device acceptance pending

**Summary:**
- Reused the existing typed C++ ANCHOR message codec and native ReceiverSession state transitions; added JNI encode/decode helpers and Kotlin `PlaybackAnchor` parsing.
- Changed receiver behavior so buffered PCM remains queued while clock acquisition and group admission complete. READY is emitted only after the existing stable-clock gate and at least 3 seconds of contiguous PCM.
- Added a native sample-indexed jitter-buffer seek operation that advances the playback cursor while preserving buffered samples at and after the chosen group start. Added a C++ regression test for preserving the expected PCM samples through a seek.
- Added a 6-second group admission window beginning when the first Speaker is admitted. After the window closes and all current Speakers are READY, the Host arms every receiver, selects a shared sample index approximately 2 seconds behind the producer head, and sends one ANCHOR with a common future Host monotonic timestamp.
- Each receiver maps the Host timestamp to its own monotonic clock using the latest offset estimate, seeks to the shared sample index, primes AudioTrack to its reported start threshold (or buffer capacity on older API levels), and calls `AudioTrack.play()` at the scheduled local target.
- Serialized new Speaker admission against group arming so a receiver cannot be admitted after the coordinator snapshots the cohort.
- Increased the receiver PCM jitter buffer to 20 seconds to cover the existing 10-second clock-stability sample span plus the 6-second group admission window. The PCM and ring-slot arrays use approximately 18.3 MiB per receiver at 48 kHz stereo, excluding runtime overhead.

**Important limitations:**
- This milestone schedules calls to Android `AudioTrack.play()` against a common monotonic-clock target. It does not schedule the hardware DAC against a presentation timestamp and does not guarantee acoustic alignment across different phone models.
- AudioTrack startup latency, audio mixer latency, Bluetooth routing, device-specific resampling and OS thread scheduling remain unmeasured. No acoustic synchronization result is claimed.
- The audio is still the generated 1 kHz timing-click integration source, not user-selected media or supported system playback capture.
- The group is fixed after the 6-second admission window; late Speakers require restarting Host and forming a new group.
- The 20-second buffer is intentionally bounded but has a noticeable native-memory footprint.

**Validation required:**
1. Build Android/JNI and run the full C++/Python CI matrix.
2. Install the updateable preview on at least two phones using the persistent signing key.
3. Run the same-hotspot test with one Host and two Speakers; confirm both remain silent while buffering, then both start on the group anchor.
4. Repeat with one Speaker joining near the end of the admission window; verify the common start sample is still available.
5. Record loopback or external microphone output from both phones and measure acoustic skew. This is a separate measurement from the software anchor timestamps.

**Next action:** Pass CI, review the PR, then perform two- and three-phone hotspot acceptance tests before publishing a new preview.


## 2026-11-04 — Android brand identity and UI polish

**Type:** Android UI / presentation  
**Status:** Implemented on `feature/coordinated-group-playback`; Android build validation pending

**Summary:**
- Added the existing official Sync In It PNG logo to Android drawable resources; reused the same asset in the main screen brand header and Android launcher/round icon metadata.
- Reworked the single-screen native UI into four clear cards: Host, Nearby Sessions, Connection Status and Network Diagnostics.
- Added a dark navy/cyan visual system, clearer heading hierarchy, rounded cards and buttons, compact preview/network chips, improved session rows and better spacing.
- Matched Android status/navigation bars to the dark preview theme.
- Updated product copy to describe the current scheduled group-start behavior and explicitly retain the acoustic-alignment caveat.
- Preserved all existing Host, discovery, join, disconnect, diagnostics and smoke-test actions.

**Validation:** Android Gradle/JNI build and APK resource packaging must pass. No physical-device visual review has been performed yet.


## 2026-11-04 — M3a Android output clock measurement

**Type:** Android audio output timing / portable DAC clock regression  
**Status:** Implemented on feature/m3-dac-timestamp-mapping; CI and physical-device validation pending

**Summary:**
- Added a portable C++ DacClockMapper that fits AudioTrack frame positions to monotonic presentation timestamps over a rolling 30-second window.
- The initial fit gate requires at least four timestamp pairs spanning at least 20 seconds. The mapper reports output-rate error in ppm and regression RMS residual, and can map frame position to local monotonic time and back.
- Added rejection of non-increasing frame/time pairs and implausible interval discontinuities; rejected samples are counted. Rolling samples outside the fit window are pruned.
- Exposed mapper create/add/snapshot/reset/close operations through JNI and included timing.cpp in the Android native build.
- Added an Android AudioTrack timestamp poller on a dedicated helper thread. It warms up at 1-second intervals, then polls at 10-second intervals once the frame clock advances.
- Unwrapped AudioTrack's low-order 32-bit frame-position counter and reset the fit after a backwards timestamp or frame-counter reset.
- Added C++ regression/window/discontinuity tests and an Android startup JNI smoke test.

**Semantics and limitations:**
- Android documents AudioTimestamp.nanoTime for AudioTrack as a best-effort monotonic presentation/commit timestamp. A frame may already have been presented or be committed for presentation; delays unknown to the implementation are not included.
- This is digital output-pipeline timing telemetry, not acoustic arrival measurement. It does not yet feed a drift controller, alter the playout cursor, apply manual offsets or publish a callback-safe snapshot.
- Routes that do not provide timestamps remain observable as unavailable; no fallback to an estimated playback-head clock is treated as equivalent evidence.
- Physical-device timestamp stability and acoustic output skew still need to be measured.

**Validation required:**
1. Pass C++ core tests, Android/JNI build and startup smoke test in CI.
2. On both Xiaomi test phones, run the same click stream and capture DAC timestamp acquisition / DAC output clock mapped diagnostics for at least 60 seconds.
3. Record sample span, fitted rate error, residual, rejected count and unavailable count on each phone.
4. Repeat after changing audio route or stopping/restarting playback; confirm the mapper resets and reacquires rather than carrying stale frame mapping.

**Next action:** pass CI and collect real AudioTrack mapping logs from both phones before adding manual per-device offset control or connecting the estimate to drift correction.


## 2026-11-05 — Multi-Speaker startup and PCM pre-roll hardening

- Changed the receiver's pre-ANCHOR PCM buffering to a rolling sample-indexed window. It advances only the pre-roll cursor when the bounded buffer fills, keeping recent contiguous PCM available while clock acquisition or group admission is delayed.
- The first playback ANCHOR switches the native buffer to strict mode. Post-anchor inserts cannot advance the playback cursor to conceal underruns or sample loss.
- Added JNI diagnostics for rolling-window advance count and regression coverage for rolling before ANCHOR and strict behavior after ANCHOR.
- Increased the Host admission window from 6 to 15 seconds and added a 45-second readiness deadline. Receivers that never reach READY are explicitly excluded instead of causing indefinite buffering and eventual PCM overflow.
- Increased each Speaker's bounded audio writer queue from 160 ms to 500 ms to tolerate brief local-network scheduling stalls without allowing unbounded memory growth.
- Added per-network Android route diagnostics and explicit peer-socket-closed classification so cellular/Wi-Fi selection issues and secondary Broken pipe failures can be separated.


## 2026-11-06 — READY-gate diagnostics and current physical validation

- Latest supplied three-phone LAN run: mDNS discovery, control TCP HELLO/WELCOME, separate audio TCP AUDIO_HELLO and repeated Host UDP time-sync responses were observed for multiple Speakers.
- No Speaker READY event was observed. The 45-second group-readiness deadline removed the admitted receivers; no GROUP_START_SCHEDULED or physical start ANCHOR was observed.
- A UDP source-IP rejection appeared after a receiver was removed. Treat this as a late-packet/peer-cleanup observation until correlated with Speaker-side timing; it is not the proven root cause.
- PR #49 merged Speaker-side READY gate diagnostics (`READY_GATE_BLOCKED`) and per-receiver Host audio queue/write counters (`HOST_AUDIO_DELIVERY`). Its first Android/JNI CI run failed because `AtomicLong` was not imported in `MasterSessionHost.kt`.
- PR #51 fixed that missing import and its Android/JNI CI run passed. The later measurement branch had a separate SPDX comment-syntax failure in Kotlin Gradle settings; those files now use valid `//` comments and require a fresh current-head Android/JNI run.
- The published v0.5.7-preview.1 release predates PR #49 and does not contain the newer READY/audio delivery diagnostic messages.
- Website and engineering docs are being aligned to distinguish implemented code, published APK contents, CI results and physical-device evidence. No acoustic synchronization claim is made.


## 2026-11-06 — Acoustic measurement instrumentation and repository consistency

- Renamed docs_sync_engine_system to docs_original; the legacy system-design links now resolve to the preserved source documents.
- Added the root .gitignore and generated/committed frontend/package-lock.json; frontend CI now uses npm ci.
- Consolidated the source logo at frontend/public/images/sync_in_it.png. Android Gradle generates its drawable resource from that source instead of checking in a second PNG.
- Added SPDX headers to remaining Android, Gradle, workflow and frontend source files.
- Centralized Android ports, capacity, PCM/chunk/buffer limits, readiness thresholds, timing windows, retry cadence and acoustic marker parameters in PreviewConfig.kt.
- Added a receiver-ID-specific 20 ms acoustic marker every second to the generated Android test output. The marker is mixed after the PCM buffer read and before AudioTrack writes; the 1 kHz base test click remains.
- Added tools/clicktrack reference WAV generation and tools/analyze PCM16 WAV detection/reporting. Different frequencies let one laptop recording distinguish two Speakers even when their bursts overlap.
- Synthetic analyzer tests passed on Python 3.11, 3.12 and 3.13 in CI. This is not a physical-device skew result.
- The current output backend remains AudioTrack MODE_STREAM with a dedicated writer thread and scheduled AudioTrack.play(). Oboe/AAudio is not ported; ADR-019 records the measurement-first decision.
- The session token remains visible in DNS-SD TXT metadata, and no foreground service/wake-lock lifecycle exists. Do not run long/endurance tests until the lifecycle is implemented and validated.

**Next action:** build and install the measurement branch on one Host and two Speakers. First solve the READY gate without relaxing clock thresholds; then record a screen-on two-minute run and analyze its WAV. No real phone skew value is recorded yet.


## 2026-11-06 — v0.5.8-preview.1 published

- Published the signed Android preview APK and SHA-256 checksum at [GitHub Releases](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.5.8-preview.1).
- Release build source: `4ad233b00ed4b13cf7710f8b557825d6448a0887`; Android release build, transport checks and APK signature verification passed in [workflow run 42176604387](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42176604387).
- The APK includes the PR #53 receiver-specific acoustic markers and the READY/audio delivery diagnostics. The APK's version metadata is aligned to 0.5.8-preview, with a monotonically generated versionCode.
- Synthetic skew-analysis tests pass CI. PHONE-SKEW-001 has not been run on physical phones, so no acoustic skew value or synchronized-playback claim is published.

## 2026-11-06 — v0.5.9-preview.1 published

- Published the signed Android preview APK and SHA-256 checksum at [GitHub Releases](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.5.9-preview.1).
- Release packaging PR #56 merged at `e6e89518d00d00b6aa911832b0740b497f43d6f1`; release tag `v0.5.9-preview.1` targets this commit.
- Android asset: `SyncInIt-Android-preview-v0.5.9.apk` (5,969,084 bytes). SHA-256: `bdd3c19ef7033d5445ebaab1d9b6ec96df6ee99aa6c97bc35c2fabe18a47185b`.
- Release publisher [workflow run 42230554362](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42230554362) passed the Android build, transport checks, APK signature verification and release asset upload.
- Release-commit Android/JNI CI passed in [run 42230554351](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42230554351); frontend TypeScript/Vite passed in [run 42230554330](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42230554330); Python 3.11/3.12/3.13 and C++17 M0 matrix passed in [run 42230554383](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42230554383).
- The APK includes PR #55's 250 ms PCM snapshot caching, Speaker ingress-rate diagnostics, Host slow TCP-write diagnostics and expanded READY-gate clock details. The READY buffer requirement, 0.25 ms residual threshold, stable-streak requirement and bounded 500 ms Host queue remain unchanged.
- The last physical failure predates PR #55. A post-release Host/Speaker run is still required to verify receiver ingress rate, Host queue behavior, READY/ANCHOR and eventual acoustic skew. No synchronized-playback claim is made.


## 2026-11-06 — v0.5.9 post-PR #55 Speaker-side physical test

**Type:** Physical-device validation / clock acquisition / PCM ingress  
**Build context:** Run occurred after publication of `v0.5.9-preview.1`; the supplied excerpt did not print the installed APK version or commit, so the exact device binary is not independently identified.  
**Run ID:** `SIT-2026-11-06-01` · Session diagnostic ID: `b1ab2fb2`  
**Evidence:** Speaker-side log excerpt covering approximately 23:41:53–23:42:13. Matching Host logs and acoustic recording were not supplied. The detailed table and reusable template are maintained in [Test Results](Test_Results.md).

**Observed results:**
- UDP probes repeatedly returned successfully; the displayed selected-fit RTT median was approximately 5.40–7.21 ms.
- Speaker PCM ingress was mostly around 49–53 frames/sec, with interval reports spanning 44.1–56.2 frames/sec.
- `missingFrames=0` in all supplied ingress and READY diagnostics.
- Contiguous buffered PCM increased from 800 ms to approximately 19,940 ms, exceeding the 3,000 ms pre-roll requirement.
- Clock fit reached 15 selected samples and a selected time span of 19,125 ms.
- Residual RMS stayed approximately 0.412–0.557 ms, exceeding the strict <0.25 ms readiness requirement.
- `stableStreak` remained 0; READY was not demonstrated. No ANCHOR or acoustic skew result was supplied.
- Receiver-skew estimates changed sign and magnitude during the excerpt (examples: +68.66, +17.75, −48.92, −63.54 and −9.50 ppm).

**Interpretation:**
- The receive-path behavior in this Speaker excerpt is encouraging: it sustained near-target ingress, filled the receive buffer and reported no missing PCM frames. The earlier pre-PR #55 receive slowdown was not reproduced in this interval.
- End-to-end playback still failed at clock acquisition. Passing PCM ingress and successful UDP responses do not establish clock precision.
- The matching Host log is absent, so Host audio writer health, queue behavior and TCP backpressure cannot be assessed for this run.
- Repeated rolling-window messages displayed `advances=0` alongside `WindowAdvanced` insert outcomes. Because diagnostics use a cached snapshot refreshed on a 250 ms cadence, stale counter reporting is a plausible explanation; verify using a fresh native snapshot before diagnosing a buffer defect.
- No root cause for the residual noise is established. Network-delay asymmetry, Android scheduling, timestamp capture and estimator/sample-selection behavior remain hypotheses to test.

**Verdict:** **FAIL — clock acquisition / READY gate.** This is partial evidence of improved PCM ingress, not a successful synchronized-playback test.

**Next actions:**
1. Capture Host and Speaker logs for the same session and correlate ingress, queued/written counters, queue depth, slow writes and disconnects.
2. Add per-probe `t1/t2/t3/t4`, individual RTT, raw offset and selected/rejected status; replay the data against the exact estimator offline.
3. Compare residual RMS with RTT percentiles, sample selection and time span without relaxing the 0.25 ms gate.
4. Verify the rolling-window counter from a fresh native snapshot.
5. Repeat one Host + one Speaker through READY and ANCHOR; only then add more Speakers and run the acoustic marker analysis.



## 2026-11-07 — Audio synchronization reverse-engineering research integrated

**Type:** Research integration / documentation  
**Status:** Added to repository documentation branch `docs/audio-sync-research`  
**Source:** User-supplied public-source research bundle dated 7 November 2026.

- Added a dedicated `docs/reverse-engineering/` section for 38 products/systems across multi-phone audio distribution, shared listening sessions, browser rooms, self-hosted multi-room systems, low-latency performance audio, speaker ecosystems and adjacent products.
- Added the architecture comparison, product catalogue, public source index and research-method caveats.
- Added Sync In It-specific recommendations and a repeatable benchmark matrix, prioritizing per-probe clock observability, matched Host/Speaker evidence, READY/ANCHOR proof and acoustic measurement.
- Linked the research index from the root README and current implementation status.
- The integration is a concise public-surface summary of the supplied bundle. Proprietary internals remain unknown; marketing performance claims are not treated as measurements. No application code, thresholds, protocol or release assets changed.


### 7 November 2026 — Final engineering upgrade brief consolidated into canonical docs

**Type:** Documentation / planning / decision register  
**Status:** Completed on documentation branch; engineering work remains pending  
**Branch:** `docs/final-upgrade-brief-2026-11-07`  
**Source:** Final Upgrade Brief and Implementation Plan (7 November 2026)

**Summary:**
- Added WP0–WP8 to the implementation plan: probe/fit evidence, Host-correlated logs, shared core readiness configuration, offline replay, controlled clock A/B, readiness criterion reconciliation, physical acoustic acceptance, earlier M3 manual trim, drift, late join/Host approval, source/codec evaluation and later security/platform work.
- Expanded the testing strategy with a repeated, one-factor-at-a-time clock A/B matrix, required telemetry schema, hold-out prediction error and explicit threshold-integrity rules.
- Added ADR-024 to track inconsistent clock-readiness definitions without changing the currently enforced Android <0.25 ms residual-RMS gate.
- Updated current implementation status and reverse-engineering research index/recommendations to point to the canonical execution order.

**Important distinction:** This is a documentation update only. No source code, runtime gate, simulator behavior, tests or physical measurements were changed. No milestone is marked complete. The latest physical evidence remains the 6 November Speaker-side run in which PCM ingress/pre-roll looked healthy but stable clock acquisition and READY/ANCHOR were not demonstrated; acoustic skew remains unmeasured.

**Next engineering action:** Implement per-probe and fit-update capture plus matching Host diagnostics, then replay the same physical run through the shared C++ estimator/readiness gate. Do not change residual thresholds in that change.


### 7 November 2026 — Continuous output and estimator alternatives documented

**Type:** Documentation / architecture experiments  
**Status:** Proposal and test-plan updates completed; implementation not started  
**Branch:** `docs/final-upgrade-brief-2026-11-07`

**Repository inspection findings:**
- The current Android Speaker primes AudioTrack, waits for the ANCHOR-derived local monotonic time, then calls AudioTrack.play().
- DacClockTimestampPoller is started only after play(), so the existing DAC mapper cannot currently establish the frame/time mapping needed before the first ANCHOR.
- The JNI clock client re-sorts recent valid samples by RTT, reselects the lowest 25%, and resets stable_streak to zero when any fit fails the current gate.
- The READY state machine accepts repeated READY idempotently; the wire protocol has no explicit READY-revocation message.

**Summary:**
- Added the continuous-output candidate: start the stream during join, emit silence, acquire the DAC mapping before ANCHOR, and align the ANCHOR sample index to the output frame corresponding to the mapped timeline time.
- Added an AudioTrack/Oboe comparison on the same two Xiaomi phones. The test includes tuned AudioTrack low-latency mode, Oboe/AAudio callback mode, timestamp fit quality, underruns/XRuns and acoustic skew.
- Added replay-only readiness hysteresis and two-state offset/skew tracker experiments, preserving the current batch regression as the control.
- Added ADR-025 and ADR-026, testing procedures and physical-run capture fields.

**External API check:** Android recommends Oboe for lowest latency and PERFORMANCE_MODE_LOW_LATENCY for latency-sensitive AudioTrack use. The AudioTrack and Oboe timestamp APIs both provide estimated frame/time information with route/implementation limitations; this does not prove Oboe timestamp accuracy is better on the target phones. See references in docs/02_TRD.md and docs/09_Testing.md.

**Non-changes:** No Android/native code, runtime configuration, protocol, estimator, thresholds or test results were changed. No physical-device comparison was run. The continuous-output architecture and both estimator alternatives remain open hypotheses.

**Next action:** Capture/replay the physical clock log for baseline/hysteresis/two-state variants, and run the scheduled-versus-continuous AudioTrack/Oboe output comparison on the two Xiaomi devices before selecting an implementation.



### 7 November 2026 — Single-Speaker process gate and transport alternatives documented

**Type:** Documentation / architecture review  
**Status:** Completed in documentation; all implementation work remains pending  
**Branch:** `docs/final-upgrade-brief-2026-11-07`

**Summary:**
- Reordered the critical path around one Host + one Speaker on the current PCM16/TCP + AudioTrack backend. The hard gate is READY sent/accepted → common ANCHOR → audible, marker-verified output with correlated logs.
- Added a process rule: keep one primary implementation workstream and do not start codec, UDP transport, iOS, PWA, provider/queue, calibration or security work while the one-Speaker path is blocked.
- Made continuous silence-first output the next same-device comparison after the current scheduled-start baseline passes; added frame-coordinate and queue-horizon checks.
- Added gated transport/codec experiments: PCM/TCP baseline, FLAC/TCP, Opus/TCP, then PCM/UDP with selective repair; compare one variable at a time.
- Added transport measurements for payload bitrate, per-Speaker TCP stall/retransmission, UDP loss/repair deadlines, sample-index continuity, decoder delay, buffer depletion, acoustic skew and resource cost.
- Added ADR-027 and updated the Implementation Plan, TRD, Testing Strategy, Current Implementation Status, Test Results and reverse-engineering recommendations/index.

**Technical clarifications:**
- Current PCM payload is 1.536 Mbps per Speaker; eight preview Speakers represent 12.288 Mbps aggregate payload before protocol and Wi-Fi overhead.
- Each Speaker owns a separate TCP audio connection. TCP head-of-line blocking is per affected connection, not one shared group-wide stream.
- FLAC compression is source-dependent; do not assume a fixed 50% reduction.
- Opus delay must be measured and decoded samples must preserve original sample indices; multi-second playout lead permits decode-ahead but does not erase codec delay.
- Android recommends Oboe/AAudio low-latency configuration; this does not establish timestamp accuracy superiority over AudioTrack on the target Xiaomi phones.

**Non-changes:** No runtime code, wire protocol, audio backend, codec, transport or clock algorithm changed. The current <0.25 ms readiness gate is unchanged. No physical-device experiment or build/test run was performed.

**Next action:** Complete the one-Speaker PCM/TCP + AudioTrack vertical slice, then test continuous output on the same hardware before adding a second Speaker or beginning codec/transport work.



## 2026-11-07 — Replayable Android clock-probe telemetry

**Type:** Android/JNI diagnostics  \
**Status:** Implemented on branch `fix/clock-probe-replay-diagnostics-2026-11-07`; CI and physical-device validation pending.

- Retained the last accepted four-timestamp exchange in the native time-sync client and exposed its exact integer timestamps through a JNI `LongArray`; nanosecond values are not passed through floating-point fields.
- Added a `CLOCK_PROBE` record for every accepted response: sequence, `t1/t2/t3/t4`, corrected RTT and raw four-timestamp offset.
- Added a `CLOCK_FIT` record for every successful estimator update: fitted offset, skew, median RTT, residual RMS, samples seen/used, selected span, stable streak and the current 60-second / lowest-RTT-25% selection policy.
- Preserved the existing lower-frequency `UDP_PROBE_OK` summary and all current retry behavior.
- No estimator, clock threshold, stable-streak rule, READY gate, transport, output backend or wire format changed.

**Known gaps:** native rejection enum and per-fit selected-sample membership are not exported yet; matching Host-side session diagnostics and offline replay are still required. No physical log has been captured from this diagnostic build. Tests have not yet been run for this branch.


## 2026-11-07 — Persistent Android diagnostic text export

**Type:** Android diagnostics / user-initiated export  \
**Status:** Implemented on the clock-probe diagnostics branch; Android/JNI CI and device validation pending.

- Added an app-private diagnostic log at `files/diagnostics/sync-in-it-diagnostics.txt`, persisted across Activity recreation and app restarts.
- Kept the on-screen diagnostic preview bounded to the latest 240 events; the export reads the retained log file rather than copying that short in-memory preview.
- Added **Save diagnostic report (.txt)** using `ACTION_CREATE_DOCUMENT` with MIME type `text/plain`; users choose Downloads or another destination without broad storage permissions.
- Added a 4 MB file cap that trims old entries and retains the newest log history.
- Included timestamped app/device/API metadata and diagnostic events, including `CLOCK_PROBE` and `CLOCK_FIT` records.
- Redacted token/password/secret/API-key assignments and excluded audio. Network addresses and device/session metadata may still be sensitive; users should review the report before sharing.
- Removed the clipboard-copy diagnostic action. Clear-log now clears the app-private log and visible event preview.

**Non-changes:** no estimator, readiness threshold, protocol, transport, audio backend or playback behavior changed. Physical READY/ANCHOR and acoustic validation remain open.


## 2026-11-07 — v0.6.1 Android preview published

**Type:** Android release  \
**Status:** Published as a GitHub pre-release  \
**Tag:** [`v0.6.1`](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.6.1)  \
**Source commit:** `886bb9bcd894211f073d09ae0c571258e149ec1b`

- Published signed APK `SyncInIt-Android-preview-v0.6.1.apk` (5,986,806 bytes).
- SHA-256: `053ee227455a0b420effd81a58c9f8967179052097537604d76b1000ed80c959`.
- Added app-private diagnostic history with a 4 MB rolling cap and user-initiated **Save diagnostic report (.txt)** export through Android's document picker.
- Export includes retained `CLOCK_PROBE` / `CLOCK_FIT` events, redacts token/password/secret/API-key assignments, and excludes audio.
- Release workflow [42344911347](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42344911347) passed APK build, transport checks, signature verification and asset publication. M0 workflow [42344911344](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42344911344) passed all supported Python/C++ matrix jobs.

**Validation boundary:** The release does not change estimator behavior or the <0.25 ms clock readiness gate. Physical READY/ANCHOR and acoustic synchronization remain unverified; this is an engineering pre-release.

## 2026-11-07 — Per-probe Host/Speaker clock-boundary correlation

**Type:** Android diagnostics / transport metadata  
**Status:** Implemented on branch `diagnostics/per-probe-clock-boundaries-2026-11-07`; CI and physical-device validation pending.

- Added a shared transport-side extractor for the v1 UDP time-sync packet's unsigned 32-bit little-endian sequence field. It validates packet size, magic and version; the shared C++ protocol remains responsible for full packet validation.
- Added a Host `HOST_UDP_PROBE` event for every accepted response, carrying the same sequence as the Speaker request/response pair.
- Host measurements now include receive-to-`t3`, `t3`-to-response-ready (including receiver selection and JNI/native response construction), response-ready-to-send-call, `t3`-to-send-call, receive-to-send-call and UDP send-call duration.
- Speaker `CLOCK_PROBE` records now include `t4`-to-native-accept processing duration. Rejected, malformed, timeout and unreachable probe records also include the outgoing request sequence where available.
- Added a transport unit test for unsigned sequence decoding, invalid packet size and invalid magic.
- The existing periodic `HOST_UDP_RESPONSE_SENT` summary remains; per-probe events are additional diagnostic records.

**Physical evidence motivating this change:** the 7 November v0.6.3-debug run on Android 14 Host / Android 12 Speaker received 108 accepted probes; 27 were selected over 33.052 seconds, median RTT was 5.5413 ms and residual RMS was 0.416862 ms. PCM ingress and pre-roll were healthy (20 seconds buffered, zero missing frames). The Host wrote all 2,250 queued chunks with queue depth 0/high-water 1, then closed the receiver at the 45-second readiness timeout. No READY or ANCHOR was observed. See `docs/Test_Results.md`.

**Non-changes:** no four-timestamp values, clock estimator, fit selection, readiness criteria, retry cadence, wire packet layout, PCM format, TCP behavior, queue capacity or playback policy changed. The newly added diagnostics have not yet been validated in a build or on physical devices. Send-call durations are syscall-boundary measurements, not proof of actual radio transmission timestamps.

**Next action:** pass Android/transport CI, install the branch on both phones, capture matching reports, join per-probe records by sequence and compare raw versus send-boundary-adjusted residuals offline. Do not apply a correction to the production clock model until the paired measurements and replay support it.

## 2026-11-07 — UDP response T3 send-boundary experiment

**Type:** Android UDP time-sync transport / diagnostics  
**Status:** Implemented on branch `experiment/send-boundary-t3-debug-acquisition-window`; Android/JNI, Kotlin transport and M0 simulator/native-core CI passed on PR head `d420fb91d75f9ff16ce76adabd2aa675205ee6e4`; physical-device validation pending.

- Response generation now creates a valid provisional v1 response with `T3=T2`. The UDP transport overwrites the response's existing little-endian T3 field (byte offset 30, eight bytes) with `System.nanoTime()` after `DatagramPacket` construction and immediately before `DatagramSocket.send()`.
- The 38-byte packet layout, packet type, sequence, session ID, receiver ID, T1 and T2 fields remain unchanged. The Speaker continues to validate the normal response through the existing native correlator.
- Host per-probe telemetry now records `receiveToT3Us`, `responseReadyToT3Us` and `sendElapsedUs`. Host status logging is performed after the datagram send rather than between response preparation and transmission.
- Debug builds receive a 90-second Host readiness observation deadline so a diagnostic run can observe beyond the prior 45-second cutoff. Release builds retain the 45-second timeout.
- Android's default preview version name was corrected to `0.6.4` (debug APK reports `0.6.4-debug`), avoiding a stale `0.6.1-debug` identity in diagnostic exports.
- Diagnostic exports now show the installed app version at export time and separately preserve the original log-initialization time/version. This resolves the confusing header that continued to show an old version after app upgrades.
- Added transport tests for T3 field replacement, packet-type/size/time-order rejection and a UDP loopback assertion that the transmitted T3 equals the transport's captured timestamp.

**Non-changes:** T1 capture and native pending-request registration are unchanged; T1-related preparation/send durations remain in Speaker telemetry. No estimator, lowest-RTT selection, 60-second fit window, residual limit (<0.25 ms), stable streak, READY/ANCHOR, retry cadence, PCM/TCP behavior or queue capacity changed. The longer debug window is not a production timeout change.

**CI evidence:** [Android app and JNI](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42370740702), [Kotlin socket transport](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42370740900), [M0 simulator and native core](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42370740948). The signed debug APK artifact is available from the Android workflow (artifact ID `13573319276`).

**Next action:** install the branch debug build on both phones, run one Host + one Speaker for at least 75 seconds in each role direction, export both reports, correlate by sequence and compare residual RMS/hold-out prediction. Do not change the estimator or readiness threshold based on one run.


### 8 November 2026 — Kernel UDP receive timestamp diagnostics and estimator replay

**Type:** Diagnostic instrumentation / deterministic replay  
**Status:** Implemented on `diagnostics/kernel-udp-timestamps-and-replay`; Android and physical-device validation pending.

- Added API 31+ SO_TIMESTAMPNS_OLD ancillary timestamps through Os.recvmsg on both Host and Speaker, with the existing Java DatagramSocket path retained as fallback.
- Mapped kernel CLOCK_REALTIME timestamps into each device's monotonic time domain using a native realtime clock read bracketed by System.nanoTime(); logged mapping uncertainty and app receive-capture lag.
- Added Host session/receiver/sequence identity and application/kernel t2 fields. Added corresponding application/kernel t4 fields to Speaker probe telemetry.
- Added a C++ replay executable linked to sync_in_it_core, the same portable estimator source used by the Android JNI bridge. It compares live-equivalent app timestamps against an offline kernel-receive-timestamp substitution and computes chronological holdout prediction error.
- Added a deterministic paired-log replay smoke test to CTest.

**Non-changes:** Kernel timestamps are not passed into the live estimator. The four-timestamp packet schema, current estimator selection, 60-second window, 0.25 ms gate, sample/span requirements, stable streak, READY/ANCHOR behavior and PCM/TCP path are unchanged.

**Not yet verified:** Android Gradle/NDK build, SO_TIMESTAMPNS_OLD availability and ancillary payload format on Xiaomi Android 12 and Android 14, fallback behavior, capture-lag measurements, and whether kernel-corrected replay improves holdout prediction. Do not create a release or change estimator behavior until CI and physical validation pass.


### 8 November 2026 — Local audio file source added on feature branch

**Type:** Implementation / Audio pipeline  
**Status:** In progress  
**Branch:** `feature/local-audio-file-sharing`

**Summary:**
- Added a Host-side Android document picker for one selected local audio file while preserving the diagnostic timing signal as a fallback.
- Added a MediaExtractor/MediaCodec source that converts decoded audio to the existing 48 kHz stereo PCM16 transport format using streaming sample-rate conversion.
- Changed file-mode startup to send silence while Speakers acquire clock readiness. Once the admission window closes and all admitted Speakers are READY, the Host begins the file at its first sample, prebuffers two seconds, then schedules the shared ANCHOR at the first file sample.
- Loops the selected track at end-of-file to keep the sample-indexed PCM stream continuous.
- Updated Android and implementation-status documentation to distinguish this branch from released behavior.

**Verification:** Not yet run through GitHub Actions or on physical devices. The feature needs Android debug build/transport CI, followed by testing with representative MP3/M4A/WAV files on Android 12 and Android 14.

**Risks or limitations:** Android decoder/container support varies by device; no playlist, pause/seek, Android system-audio capture or background playback service is included. File looping and the first-sample ANCHOR behavior still require physical confirmation. No clock-estimator, wire-protocol, PCM buffer or readiness-threshold change is intended.

**Next action:** Build and inspect CI, then test a selected file end-to-end on the hotspot with matching Host/Speaker diagnostics and an audible playback check.


### 8 November 2026 — Android system-audio capture and synchronized fan-out

**Type:** Implementation / Android media capture  
**Status:** CI passed on feature branch; awaiting paired-device verification  
**Branch:** `feature/local-audio-file-sharing`

**Summary:**
- Added a Host action to request Android app-playback capture. The flow requests `RECORD_AUDIO`, obtains fresh MediaProjection consent, then starts a user-visible `mediaProjection` foreground service.
- Configured `AudioPlaybackCaptureConfiguration` for the Android-supported `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN` categories, with 48 kHz stereo PCM16 captured through `AudioRecord`.
- Added a bounded 20-second sample-indexed rolling PCM buffer. After every admitted Speaker reaches the existing READY state and the admission window closes, the Host selects the recent two-second capture window, sends the pre-roll through the existing PCM16/TCP producer, and schedules the same common ANCHOR.
- Preserved the diagnostic test-signal and local-file sources, plus the existing UDP sender-address fix carried from PR #75.
- Added cleanup for user stop, MediaProjection revocation, capture failure and host-session exit.

**Verification:** Android app/JNI workflow [42486059495](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42486059495) passed Gradle compilation, debug APK build, transport checks, preview signature verification and artifact upload. The M0 Python 3.11/3.12/3.13 + C++17 matrix [42486059503](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42486059503) passed. APK SHA-256: `e1c14932421828cfdf956f125085ebbdcbc4b77e6ee3b3b979827a0c6205974b`. No physical Android 12/14 app-playback capture or acoustic skew measurement has been completed.

**Limits:** Capture is subject to Android playback usage and each source app's capture policy. DRM/protected or capture-restricted streams may be silent. This is not microphone recording or universal system-mixer capture. No clock-estimator threshold, wire-protocol or Speaker transport change is intended.

**Next action:** Run a two-phone Android 14 Host + Android 12 Speaker test using a capture-eligible media app. Export both diagnostics and measure audible skew before marking the feature validated.


### 8 November 2026 — PR #76 merged to main

**Merge commit:** `163faad3bde01b75d1e38d8b4b793d413393fdef`  
**Release target:** `v0.6.8` engineering prerelease

PR #76 merged the local-file source, user-consented Android system-audio capture, 20-second capture buffer, MediaProjection foreground service, and the UDP recvmsg sender-address correction into main. The Host media sources use the unchanged PCM16/TCP fan-out and common READY/ANCHOR schedule.

Post-merge main workflows passed: Android app/JNI [42487998775](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42487998775) and M0 Python/C++ [42487998642](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42487998642). Physical Android 12/14 capture, successful READY/ANCHOR and acoustic-skew testing remain unverified. The release is therefore an engineering prerelease, not a validated synchronized-audio release.


### 8 November 2026 — v0.6.8 engineering prerelease published

**Release:** [v0.6.8](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.6.8)  
**Source commit:** `8e16eea24afaf0e671105b854df3b2d4fa735f3e`  
**Publisher workflow:** [42488294615](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42488294615) — passed build, transport checks, persistent preview-signature verification and GitHub pre-release publication.

**Assets:**
- `SyncInIt-Android-preview-v0.6.8.apk` — 6,021,838 bytes.
- APK SHA-256: `99fdff4dd9d2ba47514bbcbffd85f40c02fb8bed3f68a99d5be114ef1ee5ce43`.

**Release scope:** local audio-file sharing, consented Android app-playback capture through MediaProjection, bounded rolling PCM capture, shared sample-indexed TCP fan-out and the existing group READY/ANCHOR path. Includes the UDP recvmsg sender-address fix.

**Evidence boundary:** Post-merge Android/JNI and M0 workflows passed. No physical Android 12/14 system-capture run has yet demonstrated READY, ANCHOR or measured acoustic skew. The prerelease must not be described as acoustically validated.


### 8 November 2026 — PR #78 merged and v0.6.9 engineering prerelease published

**PR:** [#78 — Harden audio source lifecycle and capture diagnostics](https://github.com/Shreyas-patil07/Sync-in-it/pull/78)  
**Merge commit / release source:** `79c50372a22a1378d265a2a4f9308a477863540c`  
**Release:** [v0.6.9](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.6.9)  
**Publisher workflow:** [42499713865](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42499713865) — passed APK build, transport checks, signature verification and publication.

- Added Host source-switch lifecycle safeguards so a confirmed source change stops the active Host, while cancelling a picker or denying MediaProjection consent does not.
- Added one-second aggregate PCM16 capture metrics (RMS dBFS, peak dBFS, near-zero percentage); raw captured audio is not logged.
- Extended offline clock-probe replay to compare application and kernel receive timestamps. Kernel timestamps remain diagnostic-only because the paired device replay did not show consistent improvement; production estimator inputs and the strict <0.25 ms gate are unchanged.
- Kotlin transport checks passed; M0 Python 3.11/3.12/3.13 and C++17 tests passed; Android/JNI CI, APK signing and the release publisher passed.
- Published APK: `SyncInIt-Android-preview-v0.6.9.apk` (6,038,222 bytes), SHA-256 `349a6503b47f40b460f3b86f88ccd6535bbab9151854bed52b359478a71df5da`.

**Release workflow note:** Creating `release/v0.6.9` triggered the publisher before the release-notes commit was added. The first run published v0.6.9 using the workflow's generic fallback notes; the subsequent run correctly refused to overwrite the existing release. Detailed release notes were then merged into main through PR #79. No APK or tag was overwritten.

**Validation boundary:** The release is still an engineering prerelease. There is no post-v0.6.9 physical run proving non-silent app-playback capture, READY on every Speaker, common ANCHOR, audible output or measured acoustic skew.


### 9 November 2026 — v0.7.0 RTP experimental prerelease and paired physical test

**Type:** Release / Physical-device validation  
**Status:** Prerelease published; RTP packet ingress partially validated; synchronized playback blocked  
**Release:** [v0.7.0 RTP Experimental Preview](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.7.0)  
**Source commit:** `6facffceb6d5cc84cc52d11164e3ec9b0a89656d`  
**Publisher workflow:** [42518559995](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42518559995) — all build, transport-test, signing, asset-preparation and prerelease-publication steps passed.

**Release asset:**
- `SyncInIt-Android-preview-v0.7.0-RTP-experimental.apk` — 6,054,606 bytes.
- SHA-256: `1fa20770b736ed6a0dee3a24798e883aacb0b0a80c6e905d55dadc9fb3252ae3`.
- `SHA256SUMS.txt` was uploaded with the APK.
- The release is an engineering prerelease. It does not claim physical synchronized playback.

**Physical evidence:**
- Paired diagnostic exports from Xiaomi 22101316I / Android 14 and Xiaomi M2010J19SI / Android 12 show RTP UDP registration and media ingress with Host/Speaker roles reversed.
- Host close counters recorded 6,608 and 6,540 RTP datagrams sent. Speaker ingress snapshots recorded 6,425 and 6,489 packets; the first run observed eight sequence-gap events, while the second observed two duplicates. Both reported zero missing PCM frames in their buffer snapshots.
- Application clock residual RMS was 0.315513 ms and 0.281073 ms. Offline kernel-receive comparisons were 0.272710 ms and 0.319622 ms. All remained at or above the strict 0.25 ms threshold; stable streak was zero in both.
- Neither attempt reached READY, common ANCHOR or measured acoustic playback. Each run lasted about 33 seconds, below the 60-second test target.
- Host source-capture telemetry included signal-present windows, but the logs do not establish non-silent RTP payload at the Speaker or audible output.

**Result:** RTP registration and packet ingress are physically observed in both role directions. The clock/READY gate remains the blocker; the audio output and acoustic result are unverified. Kernel receive timestamps are not a consistent substitute for application timestamps. No readiness threshold or production timestamp policy was changed.

**Next action:** Run a 60-second deterministic non-silent PCM test in both role directions, replay the paired logs with the canonical C++ clock-probe tool, and investigate capture/send-boundary timing plus RTP slow-send/pacing behavior before changing transport or readiness policy.


### 9 November 2026 — Backend API v1.1 realtime event channel

**PR:** [#88 — Add authenticated realtime session events](https://github.com/Shreyas-patil07/Sync-in-it/pull/88) — open, pending merge  
**Branch:** `feature/backend-realtime-events`

- Added an authenticated per-session WebSocket. Bearer credentials are sent in the first frame rather than a URL query parameter.
- Added Host notifications for Speaker join, connection, disconnection and coarse state changes; added Host presence events for Speakers.
- Added non-real-time Host requests (`refresh_status`, `request_diagnostics`, `request_clock_sync`) and correlated per-Speaker acknowledgements.
- Added one active event connection per Host/Speaker identity, stale-connection replacement, message-size limits, command expiry/target validation, and active WebSocket revocation when a Host removes a Speaker.
- Added WebSocket integration coverage: 8 backend tests passed on each of Python 3.11, 3.12 and 3.13 in [workflow run 42670896310](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42670896310).
- Recorded the process-local and timing boundaries in ADR-030. WebSockets carry no PCM, four-timestamp measurements, READY, ANCHOR or playout deadlines. Clock requests remain advisory and actual clock synchronization stays direct over the LAN.

**Deployment status:** The HTTP foundation from merged PR #87 is live on Render and passed live create-session/join checks. PR #88 has not yet been merged, so its WebSocket endpoint is not yet present in the deployed service. Android and iOS integration remain future work.


## Android backend coordination client — 10 November 2026

- Added the standalone `android/backend` Kotlin/JVM module with typed REST methods for backend session creation/join, session and device snapshots, Speaker heartbeat/state, device removal and session closure.
- Added an authenticated OkHttp WebSocket client. It sends the bearer token in the first WebSocket frame, exposes typed event envelopes, and supports heartbeat, device-state, Host command and Speaker acknowledgement messages.
- Added MockWebServer contract tests for REST serialization, normalized join codes, authorization-header placement, HTTP conflict propagation, WebSocket authentication and Host command serialization.
- Linked the module into the Android app and added `:backend:check` to the Android CI workflow.
- Added ADR-031 to preserve the optional coordination-sidecar boundary. The client does not yet change the launcher or existing Host/Speaker lifecycle. PCM, precision clock measurements, READY, ANCHOR and presentation deadlines remain direct-LAN-only.

