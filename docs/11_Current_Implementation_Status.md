# Current Implementation Status — Sync In It

**Status date:** 7 November 2026  
**Main baseline:** PR #55 merged at `6b87eadd018e4d73c4f2d1b40aa6c9692f5d11c0`  
**Release packaging commit:** `886bb9bcd894211f073d09ae0c571258e149ec1b`  
**Latest published Android preview:** [`v0.6.1`](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.6.1)  
**Audience:** contributors, testers and anyone assessing what is implemented versus what is still a design target.

This file is the source of truth for present-tense implementation claims. Product targets, architecture proposals and future behavior in the other design documents remain useful, but they must not be read as evidence that those features are already implemented or validated.

## Executive summary

Sync In It has progressed beyond the original discovery/clock-acquisition shell. The Android preview contains a working implementation path for local Host discovery, multi-Speaker TCP admission, UDP clock synchronization, generated PCM16 test-stream fan-out, bounded receiver buffering, group readiness, a shared future playback ANCHOR, Android AudioTrack rendering and DAC output-timestamp diagnostics. PR #55 also reduces repeated PCM-buffer scanning in the Speaker receive hot path and adds ingress/slow-write diagnostics.

**The end-to-end multi-phone playback path remains physically unvalidated.** The post-PR #55 Speaker-side test on 6 November showed materially better receive-path evidence: ingress was generally close to the 50 frames/sec producer cadence, the contiguous buffer grew to approximately 19.94 seconds, and no missing frames were reported in the supplied interval. However, clock residual RMS stayed around 0.412–0.557 ms, above the unchanged strict 0.25 ms threshold. The stability streak remained zero, READY was not demonstrated, and no ANCHOR or acoustic synchronization result was supplied. The matching Host log was not provided, so Host writer/queue health in this run is unknown.

The earlier pre-PR #55 run remains relevant historical evidence: its Host-side 500 ms queue overflowed after the Speaker failed to reach READY. PR #55 added 250 ms cached PCM snapshots and Speaker ingress/Host slow-write diagnostics. The post-fix Speaker log suggests PCM receive continuity improved in that capture, but it does not prove the Host writer is healthy or establish the root cause of clock residual noise. The current focus is **clock-measurement diagnosis with per-probe evidence, matching Host/Speaker log correlation, then successful READY/ANCHOR and acoustic measurement**. The 0.25 ms threshold, three-update stability gate and queue bound remain unchanged. See [Test Results](Test_Results.md) for the dated record and future-run template.

## Release, branch and build state

| Item | Current state | Evidence / interpretation |
|---|---|---|
| Published preview | `v0.6.1` | Public GitHub engineering pre-release with signed APK and SHA-256 checksum |
| APK asset | `SyncInIt-Android-preview-v0.6.1.apk` | 5,986,806 bytes; persistent preview signing key used; SHA-256 `053ee227455a0b420effd81a58c9f8967179052097537604d76b1000ed80c959` |
| Release scope | Persistent app-private diagnostic history, user-initiated `.txt` export, replayable `CLOCK_PROBE` / `CLOCK_FIT` telemetry, plus the existing acoustic markers and backpressure diagnostics | See the [v0.6.1 release notes](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.6.1) |
| Current main | PR #63 merged at `886bb9bcd894` | Adds native probe timestamp access, persistent bounded diagnostic history and Android document-picker export; clock estimator and readiness gate unchanged |
| M0 simulator/native CI on release commit | Passed | [Workflow run 42230554383](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42230554383) passed the Python 3.11/3.12/3.13 and C++17 matrix, vectors, simulator and queue scenarios |
| Android/JNI CI on release commit | Passed | [Workflow run 42230554351](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42230554351) passed build, Kotlin transport checks, signature verification and artifact upload |
| Release publisher | Passed | [Workflow run 42230554362](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42230554362) built the updateable APK, passed transport checks, verified its signature and uploaded the APK/checksum |
| Physical validation | Partial post-fix device evidence; end-to-end playback not validated | 6 Nov Speaker log: ingress near 50 frames/sec, zero missing frames, buffer to ~19.94 s; clock residual 0.412–0.557 ms kept stable streak at 0 and blocked READY. Matching Host log and acoustic result absent |

The published `v0.6.1` APK is built from release commit `886bb9bcd894211f073d09ae0c571258e149ec1b` with the persistent preview signing key. [Release workflow run 42344911347](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42344911347) passed the Android build, transport checks, APK signature verification and GitHub asset publication; [M0 workflow run 42344911344](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42344911344) passed the Python 3.11/3.12/3.13 and C++17 matrix. The app now exports retained diagnostics as a user-selected `.txt` file. The latest physical evidence remains the 6 November Speaker-side run, which failed clock stability and did not demonstrate READY, ANCHOR or acoustic skew. CI remains distinct from physical synchronization evidence.


## Local audio file source — feature branch, not yet released

The branch `feature/local-audio-file-sharing` introduces the first user-media source behind the existing PCM16/TCP transport:

- The Host can select one `audio/*` content URI through Android's system document picker. A persistable read grant is requested; broad storage permissions are not added.
- The Host decodes the selected track with Android MediaExtractor/MediaCodec and converts decoded PCM to the existing 48 kHz, stereo, signed PCM16 format. Mono is duplicated to stereo; multichannel sources use their first two channels; sample-rate conversion uses streaming linear interpolation.
- While Speakers acquire a stable clock, the Host sends silence so the existing 3-second PCM readiness requirement can be satisfied without consuming the selected track.
- After the admission window closes and the remaining cohort is READY, the Host starts the file at its first sample, sends a 2-second pre-roll, and then issues the shared ANCHOR at that first file sample.
- The single selected track loops at EOF. The generated timing-click signal remains available for synchronization diagnostics.

**Not included:** Android system-audio capture, DRM/protected playback capture, playlists, coordinated pause/seek, track queueing, background/foreground-service playback, or acoustic validation with real music. Device decoder/container compatibility still requires testing. The feature branch has not passed CI or physical-device validation yet.

## Final upgrade direction — 7 November 2026

The active work order is evidence tooling → controlled clock A/B → readiness criterion reconciliation → physical READY/ANCHOR and acoustic proof → manual offset/DAC snapshot → drift correction → group recovery/approval → source/codec expansion. See [Implementation Plan](07_Implementation.md) for work packages and [ADR-024](ADR/Architecture_Decisions.md#adr-024--clock-readiness-criterion-and-replay-parity) for the unresolved gate definition.

**Do not change the current device readiness gate yet.** Keep the Android preview's <0.25 ms residual-RMS, ≥8 selected samples, ≥10 s span and three consecutive stable updates. The TRD's <1 ms baseline, Testing plan's <1 ms residual standard deviation and core's effectively unlimited default are inconsistent with that runtime gate. ADR-024 records the issue as open and requires replay/A-B evidence plus an explicit clock-error allocation from the T2 acoustic target before any threshold change.

The first implementation deliverable is shared evidence: complete per-probe and fit-update logs, matching Host-side delivery/queue/slow-write diagnostics keyed by session ID, one shared core readiness configuration for JNI and desktop replay, and a replay fixture that reproduces the physical run. The next physical gate remains one Host + one Speaker through READY and common ANCHOR, followed by receiver-specific acoustic measurement at p95 ≤10 ms. Do not treat software readiness or successful transport as acoustic validation.

Manual offset is an existing M3 item pulled earlier in sequence, not a new milestone. Keep it separate from network clock offset and DAC mapping, persist by endpoint + output route, and flag Bluetooth route changes. Defer Opus, provider/queue control, PWA receiver, calibration, security hardening and file-sync adoption until the timing path is measured; file-sync remains an M6 bandwidth experiment. Screen-off/endurance tests remain blocked until foreground-service and wake-lock lifecycle work exists.



## Additional timing proposals under evaluation — 7 November 2026

These are review proposals, not implemented behavior or accepted architecture decisions.

### Output-frame-aligned start

The current Speaker implementation still primes AudioTrack, waits for the ANCHOR-derived local target and calls AudioTrack.play(). Its DAC timestamp poller starts only after play(), so the current mapper is not available to schedule the first ANCHOR. ADR-025 tracks the candidate to start output during join, emit silence, acquire the frame/time mapping, and switch to the ANCHOR sample at the mapped output-frame boundary.

The proposal must be tested against the current scheduled-start path on the same two Xiaomi phones. Compare tuned AudioTrack low-latency mode with Oboe/AAudio callback output. Oboe is Android's recommended low-latency route, but its timestamp is best-effort and this does not establish better timestamp accuracy. No backend or start-architecture change is approved yet. Downstream speaker/DSP/acoustic delay remains a measured quantity.

### Clock estimator alternatives

The current JNI path reselects the lowest-RTT 25% for each batch regression and resets the stable streak to zero on a failed update. ADR-026 records two replay-only candidates: strict-entry/wider-exit readiness hysteresis, and a continuous two-state offset/skew tracker with covariance and calibrated RTT-scaled measurement noise.

Neither candidate has been evaluated on the physical probe log. RTT does not prove path symmetry, and the current wire protocol has no explicit READY-revocation message. Replay all variants against the same ordered log and check held-out prediction, time-to-ready, flap/false-ready behavior, asymmetry and covariance calibration before any implementation decision.

**No runtime code or thresholds changed.** The Android preview's existing <0.25 ms residual-RMS gate, sample count/span and stable-streak requirements remain in force. Physical READY/ANCHOR and acoustic skew are still unvalidated.



## Delivery gate and next priority — 7 November 2026

The largest immediate risk is that multiple architecture layers are advancing before one physical Speaker has completed the existing playback path. The active vertical slice is deliberately limited to one Host + one Speaker, current PCM16/TCP and the current AudioTrack backend:

1. Establish both channels and sustain sample-indexed PCM ingress.
2. Pass the existing clock and contiguous-prebuffer gates without changing thresholds.
3. Send/accept READY and receive the common ANCHOR.
4. Produce audible output and verify the expected sample marker in a physical recording.
5. Preserve correlated logs and repeatable output-frame-to-marker timing.

**No codec, UDP transport, iOS, PWA, provider/queue, calibration or security implementation should start while this one-Speaker path is blocked.** Once it passes, compare scheduled-start AudioTrack against continuous-output, silence-first timeline rendering on the same phone/setup. Then add a second Speaker and establish repeatable T2 acoustic skew before testing transport alternatives or group scaling.

## Transport and codec direction — gated research

The baseline is 48 kHz stereo PCM16 over one TCP audio connection per Speaker: 1.536 Mbps payload per Speaker and 12.288 Mbps across the current eight-Speaker preview cap, excluding all transport and Wi-Fi overhead. TCP retransmission can hold later bytes on the affected Speaker's ordered connection; separate Speaker connections isolate that HOL stall but do not prevent buffer depletion on the affected phone.

After the one-/two-Speaker timing path passes, compare PCM/TCP against FLAC/TCP and Opus/TCP with identical sample-indexed audio. Test UDP selective retransmission only after codec behavior and the TCP baseline are understood. FLAC compression is signal-dependent; Opus codec delay must be measured and decoded samples must retain original sample positions. A multi-second playout lead allows decoding before presentation but does not erase codec delay. See [ADR-027](ADR/Architecture_Decisions.md#adr-027--audio-transport-and-codec-alternatives).

Oboe/AAudio remains a lower-confidence backend experiment after an end-to-end output baseline exists. Android recommends Oboe/AAudio low-latency configuration, but that is not proof of more accurate timestamps on the target Xiaomi devices. Compare actual timestamp mapping and acoustic measurements before switching.

**Clock algorithm scope:** do not add new clock-estimator changes beyond the existing replay-only hysteresis/two-state alternatives in ADR-026. The priority is to determine whether the single-Speaker failure is clock readiness, transport/buffer or output scheduling, using evidence rather than more parallel architecture work.

**Diagnostic instrumentation in progress:** the current engineering branch adds exact accepted-probe `t1/t2/t3/t4`, sequence, corrected RTT and raw offset records (`CLOCK_PROBE`), plus one `CLOCK_FIT` record per successful fit. The fit record preserves counts/span, skew, residual, fitted offset, stable streak and the 60-second / lowest-RTT-25% policy. The Android app now persists the retained event history in an app-private rolling text file (4 MB cap) and exposes **Save diagnostic report (.txt)** through Android's document picker. Export is UTF-8, user initiated, contains no audio and redacts token/password/secret/API-key assignments. Local network addresses and device/session metadata can still be sensitive. This does not change the estimator or thresholds. The published v0.6.1 APK includes the probe and fit records plus persistent `.txt` export. Remaining work includes explicit native rejection enums, per-fit selected-sample membership export, matching Host diagnostics and offline replay.



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

## Physical test evidence

### Earlier pre-PR #55 baseline

The earlier multi-Speaker run used a Host and multiple Speaker phones on the same Wi-Fi subnet.

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

The earlier Speaker-side log showed a full 20,000 ms buffer, `missingFrames=0`, selected sample span around 23.3 seconds and residual RMS around 0.308–0.348 ms. The matching Host log showed audio writes initially tracking the producer at roughly 50 chunks/second, then the 25-chunk queue overflowing at its 500 ms bound. These symptoms did not isolate whether receive hot-path work, clock acquisition, TCP backpressure or a combination caused the stall. PR #55 was introduced to reduce repeated snapshot scans and add diagnostics.

### Post-PR #55 Speaker-side run — 6 November 2026

**Run ID:** `SIT-2026-11-06-01` · **Session diagnostic ID:** `b1ab2fb2`  
**Build context:** captured after publication of `v0.5.9-preview.1`; the supplied excerpt does not independently print the installed APK version or commit.  
**Log window:** approximately 23:41:53–23:42:13 in device log time.  
**Evidence scope:** Speaker-side excerpt only; no matching Host log or acoustic recording was supplied. See [Test Results](Test_Results.md) for the full chronological record.

| Metric / stage | Observed result | Assessment |
|---|---|---|
| UDP time-sync responses | Repeated responses succeeded; reported selected-fit RTT median approximately 5.40–7.21 ms | Transport functioning in captured interval; not proof of clock precision |
| PCM ingress | Interval rates ranged from 44.1 to 56.2 frames/sec, with most reports around 49–53 frames/sec | Close to the 50 frames/sec producer cadence overall |
| PCM continuity | `missingFrames=0` in all supplied ingress/READY diagnostics | No missing frames observed in this excerpt |
| Contiguous PCM buffer | Increased from 800 ms to approximately 19,940 ms | Exceeded the 3,000 ms READY buffer requirement |
| Selected clock samples | Reached 15 | Count requirement met |
| Selected sample span | Reached 19,125 ms | 10,000 ms span requirement met |
| Clock residual RMS | Approximately 0.412–0.557 ms; latest supplied estimator value 0.5225 ms | **Failed** the strict <0.25 ms gate |
| Stable streak | Stayed at 0 | **Failed** the three-update requirement |
| READY gate | Changed from `CLOCK_AND_PCM_INSUFFICIENT` to `CLOCK_UNSTABLE` as PCM accumulated | Clock stability remained the blocker |
| READY / ANCHOR | No READY-sent or ANCHOR-received event appears in this excerpt | Not reached / not demonstrated |
| Host queue and TCP writer | No matching `HOST_AUDIO_DELIVERY` or `HOST_AUDIO_WRITE_SLOW` evidence supplied | Not evaluated |
| Acoustic synchronization | No microphone recording or analyzer output supplied | Not measured |

Representative estimator samples:

| Probe count | Samples used | Selected span | Residual RMS | Stable streak |
|---:|---:|---:|---:|---:|
| 19 | 5 | 995 ms | 0.4506 ms | 0 |
| 30 | 8 | 5,103 ms | 0.4278 ms | 0 |
| 41 | 11 | 11,114 ms | 0.4576 ms | 0 |
| 57 | 15 | 19,125 ms | 0.5225 ms | 0 |

The receiver-skew estimate also changed sign and magnitude during the run (examples: +68.66, +17.75, −48.92, −63.54 and −9.50 ppm). This indicates a noisy/unstable fitted result in this capture. It does not isolate whether network-delay asymmetry, Android scheduling, timestamp capture or estimator behavior is the dominant source.

**Interpretation:** PR #55's main receive-path concern was not reproduced as missing PCM in this Speaker excerpt: ingress stayed near the target cadence, the buffer filled and no missing frames were reported. However, clock acquisition still failed. This is a partial improvement in observed receive behavior, not a successful end-to-end playback result. Since Host counters are absent, do not infer that the Host writer/queue was healthy in this run.

**Diagnostic caveat:** repeated `PCM pre-roll rolling window active · advances=0` messages occur alongside `WindowAdvanced` insert results. PR #55 caches buffer snapshots for up to 250 ms, so these log lines may reflect a stale cached counter. Verify the native counter with a fresh snapshot before concluding that rolling did not occur or that the counter is defective.

**Run verdict: FAIL — clock acquisition / READY gate.** PCM ingress and pre-roll looked healthy in the captured interval; clock residual stayed above 0.25 ms, stable streak remained zero, and coordinated playback/acoustic skew were not demonstrated.

## Current diagnostics added on main

PR #49 established the READY-gate and Host queue counters. PR #55 extends them:

- `READY_GATE_BLOCKED`: reports the blocker, selected sample count/span, stable streak, receiver skew, residual RMS, RTT median, the exact stability criteria and PCM buffer state.
- `HOST_AUDIO_DELIVERY`: per receiver, reports queued/written audio frames, queue depth and queue high-water mark.
- `DIAG SPEAKER_AUDIO_INGRESS`: once per second, reports total/interval frames, measured ingress rate, buffered milliseconds, rolling-window advances and missing frames.
- `DIAG HOST_AUDIO_WRITE_SLOW`: rate-limited report when a Host TCP audio write takes at least one 20 ms chunk period, including current queue pressure.

The Speaker reuses a buffer snapshot for up to 250 ms rather than rescanning the 20-second ring buffer on every incoming audio frame. No wire protocol, READY buffer requirement, clock-stability threshold or Host queue limit was changed.

## Current validation state

| Area | Status |
|---|---|
| Python simulator and C++ timing core | Passed on release commit | Python 3.11/3.12/3.13 + C++17 matrix and M0 gates passed in run 42230554383 |
| Shared Python/C++ timing vectors | Passed in release-commit M0 CI |
| Kotlin/JVM transport tests | Passed in release-commit Android workflow 42230554351 and release publisher 42230554362 |
| Android/JNI debug build | Passed in release-commit Android workflow 42230554351; updateable signed APK artifact uploaded |
| Host discovery and TCP join on physical LAN | Observed working in the earlier pre-PR #55 run; not independently established by the supplied post-fix excerpt |
| UDP time-sync response | Repeatedly observed in both earlier and post-fix captures; clock precision still fails the gate |
| Speaker clock stability / READY | Failed in the 6 Nov post-PR #55 Speaker-side run: residual 0.412–0.557 ms, stable streak 0; READY not demonstrated |
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

1. Use one Host + one Speaker on the current PCM16/TCP + AudioTrack path, foregrounded and screen-on on a trusted LAN.
2. Capture matching Host/Speaker logs and replay the exact clock probes through the same C++ estimator/gate. Include Host delivery, TCP write stalls, per-Speaker queue depth/overflow, receiver buffer, READY and disconnect reason.
3. Keep the current clock threshold unchanged. Diagnose whether the first Speaker is blocked by clock readiness, audio transport/buffering or output scheduling; do not add another architecture layer to hide the failure.
4. Prove READY sent/accepted → common ANCHOR received → audible marker output. Preserve a physical recording and machine-readable frame-to-marker result.
5. Compare scheduled AudioTrack with continuous silence-first, output-frame-aligned rendering on the same device/setup. DAC timestamps must be active before ANCHOR and the renderer/timestamp frame counter relationship must be validated.
6. Add a second Speaker and prove repeatable p95 acoustic skew ≤10 ms before group scaling.
7. Only after that baseline passes, compare PCM/TCP against FLAC/TCP and Opus/TCP, then test UDP selective retransmission separately. Keep the current PCM/TCP path as fallback.
8. Compare Oboe/AAudio against AudioTrack on the same Xiaomi devices only after an output baseline exists. Select from timestamp and acoustic measurements, not API claims.
9. Keep one primary implementation workstream. Do not start iOS, PWA, provider/queue, calibration, security, codec or transport expansion while the single-Speaker path remains blocked. Long/endurance work remains blocked by the foreground-service/wake-lock dependency.


## Repository map

- `sim/`: Python reference simulator and synthetic network/clock scenarios.
- `core/`: portable C++17 protocol, session, timing and PCM buffer primitives.
- `android/app/`: Android app, Kotlin Host/Speaker networking, AudioTrack integration and JNI.
- `android/transport/`: Kotlin/JVM TCP and UDP transport module.
- `protocol/vectors/`: shared timing and wire-protocol fixtures.
- `docs/`: requirements, architecture, protocol, security, testing, [chronological physical test results](Test_Results.md), current implementation status and [audio synchronization reverse-engineering research](reverse-engineering/README.md) covering 38 products/systems.
- `frontend/`: public project website and Android preview downloads page.
- `ios/`: not yet implemented in the current repository.


## Acoustic measurement tooling — 6 November 2026

The measurement branch adds a 20 ms receiver-specific acoustic marker every second to the generated Android test stream. Speaker 1 uses 1,500 Hz; Speaker 2 uses 2,250 Hz. This allows their overlapping arrivals to be separated in one mono/stereo recording.

- Generate references with: python -m tools.clicktrack.generate --output-dir recordings/reference --receiver-ids 1 2 --duration-seconds 120
- Analyze a PCM16/48 kHz Audacity export with: python -m tools.analyze.analyze --input recordings/run.wav --receiver-ids 1 2 --output-dir test-results/run
- Synthetic tests inject known offsets and pass on Python 3.11/3.12/3.13. They are not a substitute for a physical recording.
- No PHONE-SKEW-001 result exists yet. The latest supplied physical run failed before READY and never reached the group ANCHOR.

## Architecture and operational constraints

- Android currently uses AudioTrack.MODE_STREAM with a worker thread. Oboe/AAudio output is not implemented; see ADR-019.
- The 16-byte session token is included in DNS-SD TXT metadata and is readable by LAN browsers; see ADR-020. Use a trusted private network only.
- The code cap is eight Speakers plus one Host (nine devices total), while the product target remains 6–10 phones total; see ADR-021.
- No foreground playback service or explicit wake-lock lifecycle exists. Keep the short two-minute test foregrounded/screen-on. Do not run endurance tests until that is addressed; see ADR-022.
- Android ports, capacity, buffer sizes and timers are centralized in PreviewConfig.kt. Marker constants are mirrored in Python and checked by CI.
