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
**Next action:** Continue M0 simulator scenarios, cross-language golden vectors and C++ parity before proceeding to M1.

## 3. Current engineering status

| Area | Status | Evidence required to advance |
|---|---|---|
| Product requirements | Drafted | Review and approve capacity/device scope |
| Technical requirements | Drafted | Confirm device OS baselines and hardware inventory |
| Architecture | Drafted | Resolve open ADRs and review timing boundaries |
| App flow | Draft v2.0 | Validate state transitions and accept remaining open UX choices |
| UI design brief | Drafted | Visual design and usability review |
| Protocol/data schema | Draft proposal | Protocol review, test vectors and parser tests |
| Implementation plan | Drafted | M0 repository skeleton and task breakdown |
| Security | Draft baseline | Threat review and transport-hardening decision |
| Testing | Draft plan | Test instrumentation and repeatable setup |
| ADR register | Initial record | Review provisional decisions |

## 4. Milestone tracker

| Milestone | Description | Status |
|---|---|---|
| M0 | Python simulator for time sync and drift control | In progress |
| M1 | Time sync between two physical phones | Planned |
| M2 | Scheduled click-track start on two receivers | Planned |
| M3 | Output timestamp mapping and manual offsets | Planned |
| M4 | Drift controller and 30-minute run | Planned |
| M5 | 3–4 receivers, group deadline and reconnect | Planned |
| M6 | Opus and supported live capture | Planned |
| M7 | iOS receiver | Planned |
| M8 | Acoustic calibration | Planned |
| M9 | 6–10 phone target and train trial | Planned |

## 5. Open decisions

1. Resolve whether the device capacity means 10 phones total or 10 receivers plus the master.
2. Confirm the initial Android and iOS test devices.
3. Confirm whether a Mac and physical iPhone are available for M7.
4. Verify OS minimums against the required audio and background APIs.
5. Choose and record the final C++ unit-test framework.
6. Review the NOTICE copyright holder and confirm rights over reserved project branding before release.
7. Decide when authenticated encryption becomes mandatory.

## 6. Test evidence index

No physical-device synchronization results are recorded in this initial log. Add one entry per test run using:
- Run ID.
- Date/time.
- App commit.
- Device and router configuration.
- Scenario and duration.
- Metrics and artifacts.
- Pass/fail decision.
- Limitations and follow-up issue.

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
