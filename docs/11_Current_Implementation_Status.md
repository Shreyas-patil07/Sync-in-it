# Current Implementation Status — Sync In It

**Status date:** 6 November 2026  
**Main baseline:** PR #55 merged at `6b87eadd018e4d73c4f2d1b40aa6c9692f5d11c0`  
**Release packaging:** v0.5.9-preview.1 publishing workflow prepared; this release-preparation PR triggers the publisher on merge  
**Latest published Android preview:** [`v0.5.8-preview.1`](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.5.8-preview.1)  
**Audience:** contributors, testers and anyone assessing what is implemented versus what is still a design target.

This file is the source of truth for present-tense implementation claims. Product targets, architecture proposals and future behavior in the other design documents remain useful, but they must not be read as evidence that those features are already implemented or validated.

## Executive summary

Sync In It has progressed beyond the original discovery/clock-acquisition shell. The Android preview contains a working implementation path for local Host discovery, multi-Speaker TCP admission, UDP clock synchronization, generated PCM16 test-stream fan-out, bounded receiver buffering, group readiness, a shared future playback ANCHOR, Android AudioTrack rendering and DAC output-timestamp diagnostics. PR #55 also reduces repeated PCM-buffer scanning in the Speaker receive hot path and adds ingress/slow-write diagnostics.

**The end-to-end multi-phone playback path is not yet physically validated.** In the latest supplied pre-PR #55 run, the Speaker discovered the Host, established control/audio TCP and exchanged UDP time-sync packets. It accumulated 20 seconds of contiguous PCM with zero missing frames, but never reached `READY`: residual RMS remained around 0.308–0.522 ms, above the unchanged 0.25 ms stability threshold, leaving the stable streak at zero. The Host later disconnected the Speaker after its 500 ms audio queue overflowed. No group ANCHOR or acoustic synchronization was demonstrated.

The current focus is **post-PR #55 receive-throughput retesting, clock-gate diagnosis and then a measured two-phone acoustic-skew result**. PR #55 caches the expensive contiguous-buffer snapshot for up to 250 ms, adds one-second Speaker ingress-rate metrics and adds rate-limited Host slow TCP write diagnostics. The repeated full-buffer scan was a plausible contributor to the observed receive slowdown, but it is not confirmed until the updated APK is tested. The 0.25 ms clock threshold and three-update stability gate remain unchanged. The acoustic marker, click-track reference generator and WAV analyzer still have no physical skew result.

## Release, branch and build state

| Item | Current state | Evidence / interpretation |
|---|---|---|
| Latest published preview before this packaging PR | `v0.5.8-preview.1` | Existing public release; it predates PR #55 |
| Next preview | `v0.5.9-preview.1` | Release workflow is configured to build the merged PR #55 code and publish a new signed APK/checksum on merge |
| Release scope | Adds cached PCM snapshots, Speaker ingress-rate diagnostics and Host slow-write diagnostics to the v0.5.8 measurement toolchain | See the v0.5.9 release notes after publishing |
| Current main | PR #55 merged at `6b87eadd` | Adds PCM snapshot throttling, Speaker ingress metrics, Host slow-write metrics and expanded clock diagnostics |
| M0 simulator/native CI on merged main | Passed | [Workflow run 42185917316](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42185917316) passed the Python 3.11/3.12/3.13 and C++17 matrix, vectors, simulator and queue scenarios |
| Android/JNI CI on merged main | Passed | [Workflow run 42185917399](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42185917399) passed build, Kotlin transport checks, signature verification and artifact upload |
| Physical validation | Pending after PR #55 | No post-fix device run, successful READY/ANCHOR or real acoustic skew measurement is recorded yet |

The current `v0.5.8-preview.1` APK was built before PR #55 and does not contain the PCM snapshot optimization or the new ingress/slow-write metrics. The v0.5.9 release-preparation workflow will build from the PR #55 merged main commit using the persistent preview signing key. The merged-main Android/JNI and M0 workflows passed, but **no post-PR #55 physical run or physical two-phone skew value is recorded yet**; synthetic analyzer tests do not establish real-device synchronization.

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

The latest supplied **pre-PR #55** run used a Host and multiple Speaker phones on the same Wi-Fi subnet.

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

The Speaker-side log now shows the missing detail: by the end of the run the buffer was full at 20,000 ms, `missingFrames=0`, the selected sample span had reached about 23.3 seconds, and the residual RMS had fallen only to roughly 0.308–0.348 ms. Since that still exceeds 0.25 ms, `stableStreak` remained zero and READY was never sent. In the matching Host log, audio writes initially tracked the producer at roughly 50 chunks/second, then the 25-chunk queue overflowed at the 500 ms limit. The Host-side queue overflow is a downstream symptom; the logs do not prove whether the receive hot-path scan, the clock acquisition issue, TCP backpressure or a combination caused the stall. PR #55 reduces scan frequency and adds the measurements needed to distinguish these possibilities on the next run.

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
| Python simulator and C++ timing core | Passed on merged main | Python 3.11/3.12/3.13 + C++17 matrix and M0 gates passed in run 42185917316 |
| Shared Python/C++ timing vectors | Passed in merged-main M0 CI |
| Kotlin/JVM transport tests | Passed in merged-main Android workflow 42185917399 |
| Android/JNI debug build | Passed in merged-main Android workflow 42185917399; signed preview artifact uploaded |
| Host discovery and TCP join on physical LAN | Observed working in the supplied run |
| Multi-Speaker UDP response | Observed working in the supplied run |
| Speaker clock stability / READY | Failed in the pre-PR #55 supplied run because residual RMS stayed above 0.25 ms; post-fix device retest pending |
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

1. Publish and install v0.5.9-preview.1 on one Host and one Speaker using the persistent signing key; keep both apps foregrounded and screens awake on the same trusted Wi-Fi.
2. Capture both logs from join through READY/ANCHOR or disconnect. Check `DIAG SPEAKER_AUDIO_INGRESS` remains near the 50-chunk/sec producer rate and that Host queued/written counters remain close.
3. Inspect `DIAG HOST_AUDIO_WRITE_SLOW`. If ingress is still slow but Host writes are fast, investigate receiver parsing/buffer insertion and Android scheduling; if Host writes stall, investigate TCP backpressure/network behavior.
4. Inspect the clock residual, skew, sample span and stable streak separately. The 0.25 ms threshold must remain unchanged unless measurements justify a different estimator design.
5. Once one Speaker reaches READY and starts from ANCHOR, repeat with two Speakers, then three/four.
6. Only after stable group start is observed, run the short two-minute acoustic measurement. Longer/endurance tests remain blocked by the missing foreground-service/wake-lock lifecycle.

## Repository map

- `sim/`: Python reference simulator and synthetic network/clock scenarios.
- `core/`: portable C++17 protocol, session, timing and PCM buffer primitives.
- `android/app/`: Android app, Kotlin Host/Speaker networking, AudioTrack integration and JNI.
- `android/transport/`: Kotlin/JVM TCP and UDP transport module.
- `protocol/vectors/`: shared timing and wire-protocol fixtures.
- `docs/`: requirements, architecture, protocol, security, testing and current implementation status.
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
