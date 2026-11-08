# Architecture Decision Records — Sync In It

**Document status:** Initial ADR register  
**Version:** 1.0  
**Last updated:** 1 November 2026

## 1. Purpose and process

This file records decisions that materially affect system structure, protocol compatibility, timing behavior or implementation cost.

Statuses:
- **Accepted:** intended baseline; still subject to implementation validation.
- **Provisional:** selected for the first prototype but requires evidence.
- **Open:** not yet decided.
- **Superseded:** replaced by a newer ADR.

A decision is not validated merely because it is recorded. Changes to an accepted decision require an amendment explaining the impact on code, protocol, tests and documentation.

## ADR-001 — Offline local-network architecture

**Status:** Accepted  
**Decision:** Core operation uses a private Wi-Fi LAN and does not require internet, cloud accounts or a remote backend.  
**Rationale:** The target use case is a train coach with local phones and a portable router. Cloud dependency would reduce availability and add unnecessary latency and operational complexity.  
**Consequences:** Master and receivers communicate directly on the LAN. Session and diagnostic data are local. Router setup and client-isolation behavior become deployment requirements.

## ADR-002 — Android master; Android and iOS receivers

**Status:** Accepted for current scope  
**Decision:** One Android app provides master and receiver modes. iOS is receiver-only in the current roadmap.  
**Rationale:** The Android master uses platform capture and session-control capabilities. A receiver-only iOS scope reduces the initial cross-platform surface.  
**Consequences:** Master lifecycle and capture are Android-specific. Shared timing and protocol logic must remain platform-neutral.

## ADR-003 — Portable C++ timing core with native adapters

**Status:** Accepted  
**Decision:** Implement the offset estimator, timeline, DAC clock mapping, jitter buffer and drift controller in a portable C++17 core. Use Kotlin/JNI/Oboe on Android and Swift/Objective-C++/AVAudioEngine on iOS.  
**Rationale:** One timing implementation reduces behavioral divergence across platforms and can be tested on a desktop.  
**Consequences:** Define narrow platform interfaces. Avoid platform API calls in core. Maintain a desktop build and shared test vectors.

## ADR-004 — Python reference model before device integration

**Status:** Accepted  
**Decision:** Build a Python reference simulator for clock, network and drift behavior, then implement the same behavior in C++ against golden vectors.  
**Rationale:** Simulation enables reproducible testing of known clock skew, asymmetric delay, jitter and packet loss before physical audio integration.  
**Consequences:** The simulator must retain ground truth and deterministic seeds. Parity tests are required; Python output is not evidence of acoustic performance.

## ADR-005 — PCM first; Opus later

**Status:** Accepted for prototype  
**Decision:** Start with 48 kHz stereo PCM16 and 20 ms chunks. Add Opus only after timing is measured with PCM. Receivers downmix to mono by default.  
**Rationale:** Establish synchronization without adding codec buffering and algorithmic delay.  
**Consequences:** PCM bandwidth is approximately 1.536 Mbps per receiver before overhead. Router capacity must be tested. Codec negotiation must be versioned.

## ADR-006 — Separate TCP control/audio and UDP time sync

**Status:** Provisional  
**Decision:** Each receiver uses separate TCP connections for control and audio, plus UDP for four-timestamp time synchronization. Initial ports are 47800, 47801 and 47802.  
**Rationale:** Separate control and audio paths prevent large audio transfers from directly queueing control messages on one TCP stream. UDP avoids retransmission delay contaminating time-sync timestamps.  
**Consequences:** Multiple sockets and independent reconnect logic are required. Audio TCP head-of-line blocking remains a risk and must be benchmarked against alternatives before locking the transport for a release.

## ADR-007 — One shared sample timeline and future start deadline

**Status:** Accepted  
**Decision:** Audio packets are buffered by sample index; all receivers follow common master-time timeline anchors. Receivers do not start on packet arrival.  
**Rationale:** Arrival times differ and are not presentation deadlines. A future common deadline gives every receiver time to buffer and arm its output.  
**Consequences:** Clock mapping, DAC mapping and timeline state must be explicit. Late receivers join at a future boundary rather than shifting healthy receivers.

## ADR-008 — Timestamp-based DAC clock mapping

**Status:** Provisional  
**Decision:** Estimate output-frame-to-local-time mapping from native audio timestamps, using a sliding regression window.  
**Rationale:** The audio hardware clock can drift relative to the OS monotonic clock. Scheduling only against the OS clock can therefore accumulate error.  
**Consequences:** Timestamp meaning and accuracy must be verified per platform/device. Reject discontinuities and expose timestamp-quality telemetry.

## ADR-009 — Initial drift correction by bounded frame slips

**Status:** Provisional  
**Decision:** Use a dead band, rate-limited one-frame slips for moderate error, and a controlled hard resync for large error in the first prototype.  
**Rationale:** A simple deterministic controller is easier to validate than a full asynchronous sample-rate converter during early milestones.  
**Consequences:** Slips may create audible artifacts. Measure correction rate and audio quality. Evaluate controlled resampling before treating this as a final long-duration solution.

## ADR-010 — Acoustic calibration at one defined position

**Status:** Provisional  
**Decision:** Schedule receiver chirps, record them at a microphone placed at the selected listening position, estimate arrival delays and apply per-device compensation. Provide manual offset as fallback.  
**Rationale:** Digital synchronization alone cannot remove speaker-output and acoustic-path differences.  
**Consequences:** Calibration is layout-specific, affected by distance and phone movement, and must carry a confidence score. T3 applies only at the measurement position.

## ADR-011 — Session token in QR invitation

**Status:** Provisional  
**Decision:** Generate a 16-byte random token per session and include it in the QR payload; validate it during HELLO.  
**Rationale:** Prevent casual joins by devices that do not have the invitation.  
**Consequences:** The current draft does not encrypt or authenticate transport messages. This control is not sufficient for an untrusted LAN. Production hardening requires authenticated encryption and replay protection.

## ADR-012 — Local-file playback is the reliable source fallback

**Status:** Accepted  
**Decision:** The master supports local-file playback through the same PCM pipeline as live capture. File mode is the fallback when third-party capture is unavailable.  
**Rationale:** Android playback capture depends on user approval and source-app policy. Some applications prohibit capture.  
**Consequences:** Source decoding must feed the same chunk/timeline path. The UI must explain capture failure rather than bypass restrictions.

## ADR-013 — Receiver capacity semantics

**Status:** Open  
**Context:** The README describes 6–10 phones total, while the detailed protocol draft allows up to 10 receivers in addition to the master.  
**Options:** (A) 10 total devices including master; (B) 10 receivers plus one master.  
**Recommendation:** Treat 6–10 total phones as the product capacity goal until the owner confirms otherwise. Reconcile the protocol capacity field and M9 test plan before scaling implementation.  
**Impact:** Admission logic, protocol error behavior, bandwidth test sizing, UI counts and acceptance criteria.

## ADR-014 — Supported OS versions and initial device matrix

**Status:** Open  
**Context:** The system structure suggests Android API 26 for receiver, API 29 for capture-capable master and iOS 15 for receiver, all marked for verification.  
**Decision required:** Confirm actual test hardware, minimum OS versions, and whether a Mac/iPhone is available for the iOS milestone.  
**Impact:** Platform architecture, permission handling, API selection and milestone timing.

## ADR-015 — Encryption and production deployment requirement

**Status:** Open  
**Context:** The prototype protocol currently uses token validation but no transport encryption.  
**Recommendation:** Keep the current design restricted to controlled development networks. Before untrusted/public deployment, use reviewed authenticated key exchange and encryption/integrity protection for control, audio and time-sync traffic. Do not design custom cryptography.  
**Impact:** Protocol versioning, key lifecycle, performance, test vectors and security review.

## ADR-016 — Native unit-test framework

**Status:** Open  
**Options:** GoogleTest or Catch2.  
**Recommendation:** Choose one before substantial C++ module work and use it consistently in local development and CI.

## ADR-017 — Project license and third-party reuse

**Status:** Accepted (owner may amend by a new ADR)  
**Decision:** Source code, test vectors and website source are licensed under Apache-2.0. Documentation, specifications and measurement reports are licensed under CC BY 4.0. The project name, logo and brand images are not licensed under either and remain all rights reserved. Third-party components keep their own licenses and are listed in `THIRD_PARTY_NOTICES.md`. Rules for adding dependencies are in `LICENSING.md`.  
**Rationale:** Apache-2.0 is permissive, includes a patent grant, and is compatible with the planned dependencies (Oboe, Opus, GoogleTest or Catch2, ZXing). CC BY 4.0 is the usual choice for reusable documentation. Snapcast is GPL-licensed and is used as an architectural reference only, so no GPL code enters the repository.  
**Consequences:** No GPL or AGPL code may be copied or linked. New dependencies need a license check and a row in `THIRD_PARTY_NOTICES.md` in the same change. Binary releases must display required third-party notices. The copyright line in `NOTICE` should be reviewed by the owner. This record is not legal advice.

## ADR-018 — Android multi-Speaker admission and READY gate

**Status:** Accepted for the current engineering preview; subject to physical validation.

**Decision:** The Android Host admits up to eight Speakers. The cohort admission window is 15 seconds from the first successful Speaker admission. Each Speaker must meet the clock-stability gate and at least 3 seconds of contiguous PCM before READY. READY is retried once per second until the shared ANCHOR arrives; repeated READY messages are idempotently accepted. The Host applies a 45-second readiness deadline and removes Speakers that remain unready. The pre-ANCHOR PCM buffer may roll its cursor to retain a bounded newest contiguous window; after ANCHOR, buffer handling is strict and must not silently skip sample positions.

**Rationale:** Bounded admission and retryable readiness prevent a transient READY rejection from leaving a receiver silently waiting until its prebuffer overflows. A rolling prebuffer retains recent audio while the estimator acquires a stable fit, without weakening sample-index correctness after the common start point.

**Evidence and limitation:** The latest supplied physical run completed discovery, TCP admission and UDP request/response, but no Speaker reached READY before the 45-second deadline. The readiness gate must be diagnosed using Speaker clock/PCM metrics and Host audio-delivery counters. Do not weaken clock thresholds or claim synchronized group playback until repeated physical tests reach READY, receive one shared ANCHOR and pass acoustic measurements.

## ADR-019 — AudioTrack preview backend versus planned Oboe/AAudio output

**Status:** Accepted for the measurement prototype only; revisit after acoustic evidence.

**Context:** The architecture baseline selects Oboe/AAudio for Android real-time output. The current preview instead uses AudioTrack.MODE_STREAM, a dedicated writer thread, pre-primes the stream, waits against the receiver's mapped local monotonic start target, and calls AudioTrack.play(). It does not use an Oboe data callback, and the scheduled call is not a hardware presentation-time guarantee.

**Decision:** Keep the existing AudioTrack path for the first controlled two-phone measurement. Do not port the backend before a repeatable skew recording exists. Use p95 absolute acoustic skew ≤10 ms as the next decision point. If the result is worse, correlate marker arrival with READY/ANCHOR timing and DAC timestamp diagnostics before deciding whether backend start/presentation behavior is the limiting factor. An Oboe port is an experiment to measure, not an assumed fix.

**Consequences:** The current source does not satisfy the final Oboe/AAudio target. Do not describe AudioTrack start timing as acoustically precise. A backend change requires a follow-up ADR and comparable recordings on the same phones, router and geometry.

## ADR-020 — LAN-visible session token in DNS-SD metadata

**Status:** Open security blocker; no protocol change in this measurement branch.

**Context:** The Host includes its 16-byte session token in DNS-SD/mDNS TXT metadata. Any device on the same LAN that browses the service may read the token and attempt to join. The token is an admission credential, not transport encryption, peer authentication or protection against an active LAN attacker.

**Decision:** Do not silently change the join flow while building the acoustic measurement tool. Restrict tests to a trusted private Wi-Fi network. Before public/shared-LAN use, choose a reviewed pairing approach: explicit Host approval, a short-lived one-time pairing code, or an out-of-band QR/secret exchange. Authenticated transport and replay protection require a separate protocol/security review.

**Consequences:** The current preview is not safe for public or untrusted Wi-Fi. Record a new ADR before changing the token/pairing design.

## ADR-021 — Preview receiver capacity versus product target

**Status:** Accepted for the current preview; scale goal remains open.

**Decision:** The Android preview cap remains **8 Speakers plus one Host**. The product goal remains **6–10 phones total**, not ten receivers in addition to the Host. Keep the code cap in PreviewConfig.kt; do not raise it to ten until shared-airtime and physical group testing validate the additional load.

**Rationale:** The current Host admits eight Speakers, while target prose refers to 6–10 phones total. These values are not interchangeable.

**Consequences:** The maximum current preview group is nine devices total. The M9 target is not achieved or capacity-validated by this code limit.

## ADR-022 — Foreground playback service and wake-lock gate for long tests

**Status:** Required before endurance/background testing; not implemented in this branch.

**Context:** The app currently owns Host/Speaker networking and AudioTrack playback in an Activity-scoped process. The manifest has no playback foreground-service declaration and the app does not hold an explicit CPU/Wi-Fi wake-lock lifecycle. Screen-off, backgrounding and process reclamation can interrupt a longer run.

**Decision:** Permit only the initial short, foregrounded, screen-on two-minute acoustic test with the device awake and stable power. Block long/endurance, screen-off, background and train trials until a foreground playback service, notification lifecycle, partial CPU wake lock and appropriate Wi-Fi/network lifecycle are implemented and validated.

**Consequences:** The absence of foreground-service/wake-lock handling remains a reliability blocker. Do not report long-run stability from a short screen-on test.

## ADR-023 — Central Android preview configuration

**Status:** Accepted.

**Decision:** Keep Android preview ports, receiver capacity, audio format/chunk sizes, queue and PCM-buffer sizes, readiness thresholds, timing windows, retry cadence and acoustic marker parameters in android/app/src/main/kotlin/com/syncinit/app/PreviewConfig.kt. The Python click-track tool mirrors the acoustic marker parameters and has a CI test checking parity.

**Rationale:** The same numeric limits were repeated across the Host, receiver, audio writer and diagnostics. Central configuration reduces drift.

**Consequences:** Change central values and corresponding tests/docs together. Product goals are not runtime constants and must remain labeled separately.

## 2. Decision review checklist

When an ADR changes, review:
- Product requirements and capacity wording.
- TRD and platform constraints.
- Architecture diagrams and thread boundaries.
- Protocol message fields/versioning and golden vectors.
- App flows and user-visible error states.
- Security and privacy implications.
- Testing gates and milestone dependencies.
- Development log and implementation status.

## 3. Source documents

These records consolidate decisions and open questions from [docs/System Structure.md](../../docs_original/System%20Structure.md) and [docs/Sync Engine.md](../../docs_original/Sync%20Engine.md). The original source documents remain unchanged.


## ADR-024 — Clock-readiness criterion and replay parity

**Status:** Open — evidence required before changing runtime behavior  
**Date:** 7 November 2026

**Context:** The repository contains inconsistent readiness criteria. The TRD records a <1 ms baseline residual; the Testing plan historically records residual standard deviation <1 ms; Android JNI `stable_fit` uses residual RMS <0.25 ms with at least 8 samples and 10 seconds of span, plus a three-update streak; the core `ReceiverSession` default is `UINT32_MAX`, effectively disabling its residual bound unless overridden. These values measure related but non-identical quantities and must not be treated as interchangeable.

**Current decision:** Preserve the Android preview's existing <0.25 ms residual-RMS gate, sample-count/span requirements and three-consecutive-update rule while gathering evidence. No threshold is relaxed or reinterpreted by this ADR.

**Required evidence and decision process:**
1. Add per-probe t1–t4, RTT, offset, selected/rejected/expired status and reason.
2. Add fit-update fields: samples seen/used, span, slope, residual, predicted offset at t4 and stable streak.
3. Correlate Speaker and Host audio delivery, slow-write, queue and disconnect diagnostics by session ID.
4. Replay a captured physical run through the same C++ estimator and readiness gate used on Android; compare with the Python reference and golden fixtures.
5. Run the controlled hotspot/router, power, receive-priority, probe-policy, sample-selection and slope A/B matrix.
6. Derive the clock-error allocation from the T2 acoustic acceptance target (p95 absolute skew ≤10 ms). Explicitly state the allocated clock budget, uncertainty metric and why it is sufficient.

**Change control:** A threshold change requires replayable evidence, updated shared core/device configuration, golden-vector and test updates, and a separate reviewed amendment. Do not combine a threshold change with a patch fixing a failing acceptance run.

**Consequences:** Until this decision is completed, the strict Android gate remains authoritative for device readiness; TRD/testing references to <1 ms are historical/provisional baselines, not permission to bypass it. Core and JNI defaults must ultimately share one explicit `ClockReadinessConfig` object.

**Open questions:** What clock-error budget is appropriate within the 10 ms acoustic target? Is residual RMS an adequate readiness statistic, or should start-time uncertainty and hold-out prediction error be the principal criteria? How should correlation between probes affect standard-error calculations?

## ADR-025 — Continuous output and timeline-frame scheduling

**Status:** Open — output-backend and physical-device experiment required  
**Date:** 7 November 2026

**Context:** The current Speaker output path primes AudioTrack, waits until the ANCHOR-derived local time and invokes AudioTrack.play(). The DAC timestamp poller starts only after play(), so it does not provide an established frame-to-time mapping before the first group start. The existing design therefore relies on scheduled stream start even though the shared media timeline and DAC mapping are intended to describe presentation timing.

**Proposal under test:** Start the output stream after join/format negotiation and keep it running with silence before playback. Acquire the DAC frame/time mapping during this silent period. When ANCHOR arrives, map its Host time to receiver-local monotonic time, invert the DAC mapping to obtain the output-frame boundary, and associate ANCHOR.sampleIndex with that output frame. The renderer then maps each requested output frame to the shared media timeline and emits silence or the corresponding PCM sample.

**Hard constraints:**
- Callback-safe clock/DAC snapshots; no allocation, lock, JNI, I/O or logging in the audio callback.
- Never commit silence beyond the target frame; the output queue/writable-frame horizon and ANCHOR lead must be accounted for.
- Establish a canonical stream-frame coordinate and explicitly relate callback/write counters to the timestamp framePosition. Do not equate the renderer's next writable frame with a frame already presented or committed; validate the offset and queued-frame horizon for each backend.
- Treat absent, stale or route-reset timestamps as a readiness/degraded condition, not as a precise scheduling basis.
- This does not prove that downstream DSP, speaker transducer latency or acoustic-path delay disappears. Preserve acoustic measurement and route-specific calibration until measurements justify changing them.

**Backend experiment:** Compare current scheduled AudioTrack; continuous AudioTrack with PERFORMANCE_MODE_LOW_LATENCY; and continuous Oboe/AAudio callback output with LowLatency and Exclusive requested where supported. Record actual backend/mode, rate, frames per burst, buffer size, timestamp availability and mapping error, callback timing, underruns/XRuns and microphone-measured skew on the same two Xiaomi phones. Android recommends Oboe for low latency and low-latency mode for AudioTrack, but those recommendations do not establish timestamp accuracy superiority. Both timestamp APIs are best-effort and have different documented frame/pipeline semantics. See the references in [TRD §9.1](../02_TRD.md).

**Decision rule:** Do not replace the current output path until continuous timeline rendering places the ANCHOR sample at the mapped output frame repeatably on both test phones and achieves the T2 acoustic gate (p95 absolute skew ≤10 ms) without added underruns/XRuns or transition artifacts. This ADR is not an implementation approval.

**Consequences:** If validated, output activation becomes a join-time operation and ANCHOR becomes a media-timeline boundary rather than a scheduled play() call. Start-call latency is no longer the media-start event, but output-path and acoustic latency remain measured quantities.

## ADR-026 — Clock-estimator hysteresis and two-state tracker experiments

**Status:** Open — replay experiment only; current runtime estimator remains unchanged  
**Date:** 7 November 2026

**Context:** The current C++ estimator sorts all recent valid samples by RTT and reselects the lowest 25% on every update before fitting offset and skew with batch regression. The JNI bridge increments the stable streak when the fit meets its criteria and resets the streak to zero on any failed update. This can make acquisition sensitive to one transient miss or changes in the selected subset. The available physical logs have not yet shown that this is the cause of the READY failure.

**Candidate A — hysteresis:** Retain the existing strict entry conditions initially (≥8 selected samples, ≥10 s span, residual RMS <0.25 ms, three consecutive passing updates). After acquisition, use a wider exit residual boundary and configurable exit dwell before dropping the stable state. Derive the exit threshold/dwell by replay, not by choosing a value that makes a failed phone run pass. Invalid/stale samples, clock discontinuities and loss of the timestamp basis must remain hard failures.

**Protocol constraint:** The current protocol has no explicit READY-revocation message. The Speaker checks its latest acquisition state when ANCHOR arrives, while the Host accepts repeated READY idempotently. Decide whether hysteresis is only a local pre-ANCHOR acquisition latch or whether a future protocol change is required for Host-visible readiness revocation/lease semantics. Do not imply a full enter/leave handshake exists today.

**Candidate B — two-state offset/skew tracker:** Maintain offset and skew as a continuously updated state with covariance. Propagate offset using elapsed time and use four-timestamp exchange offset as the measurement. Evaluate RTT-scaled measurement noise with calibrated floor/cap bounds, innovation/outlier checks and explicit process-noise assumptions.

**Known limitation:** RTT is only a proxy for measurement noise. A low RTT does not prove forward/reverse path symmetry, and a systematic offset bias from directional asymmetry may remain. The covariance is useful as a readiness metric only after calibration and coverage checks against simulator ground truth.

**Evaluation:** Replay the same chronological physical logs with the current batch regression, hysteresis alone, the two-state tracker alone and the combined variant. Predict each held-out low-RTT measurement before assimilating it. Compare hold-out prediction error, offset-at-ANCHOR prediction, time-to-ready, stable-streak flapping, false-ready/blocked transitions, skew stability and outlier handling. Check covariance calibration/coverage in ground-truth simulation cases. Do not leak future samples into replay predictions.

**Decision rule:** Keep the current estimator and <0.25 ms entry gate until a candidate improves predictive performance without increasing false-ready risk or masking asymmetry. Any production change must be reconciled with ADR-024 and include updated core/JNI configuration, fixtures and test reporting.

**Consequences:** These are alternatives to measure, not committed algorithm changes. Batch regression remains the production baseline until replay evidence supports a replacement.



## ADR-027 — Audio transport and codec alternatives

**Status:** Open — gated experiment; PCM16/TCP remains the baseline  
**Date:** 7 November 2026

**Context:** The current Android preview sends 48 kHz stereo PCM16 over a separate TCP audio connection per Speaker. Payload is 1.536 Mbps per Speaker and 12.288 Mbps aggregate at the eight-Speaker preview cap, before protocol, TCP/IP, retransmission and Wi-Fi airtime overhead. TCP provides ordered reliable delivery but a lost segment can delay later bytes on that same Speaker connection until retransmission. Other Speakers use independent connections, so this is not one shared TCP head-of-line queue. The existing 20-second receive buffer can absorb only bounded stalls.

**Decision for current implementation:** Do not replace PCM/TCP before one Speaker passes READY → ANCHOR → audible physical output and two Speakers establish a repeatable acoustic baseline. No new transport or codec is authorized as a workaround for the current readiness/start blocker.

**Candidates to compare after the baseline:**
1. **FLAC/TCP:** lossless sample recovery; measure source-dependent compression ratio, encoder/decoder delay and CPU. Do not assume a fixed 50% bandwidth reduction. Snapcast publicly documents FLAC as its default codec and TCP chunk delivery with continuous client synchronization and buffering; this is a reference architecture, not a performance guarantee on Android.
2. **Opus/TCP:** initially test 20 ms frames and sample-indexed decode. Measure encoder lookahead, packet aggregation, decode delay, bitrate, quality, CPU and thermal impact. A multi-second playout lead permits encoding/decoding ahead of presentation but does not erase algorithmic delay.
3. **UDP with selective retransmission:** later experiment. Require sequence/sample indices, bounded per-Speaker NACK/retransmission state, repair deadlines before playout, MTU-safe packetization, explicit concealment for unrepaired late chunks and proof that one receiver's repair work cannot stall others.

**Experiment isolation:** First vary codec while TCP is fixed (PCM/TCP vs FLAC/TCP vs Opus/TCP). Then vary transport while codec is fixed (PCM/TCP vs PCM/UDP repair). A combined Opus/UDP experiment is optional only after both dimensions are understood.

**Acceptance:** Preserve the exact decoded sample-index timeline and meet the existing acoustic acceptance gates. Compare bitrate/airtime, retransmissions, TCP stall durations, UDP loss/reorder/repair success, buffer depletion, missing sample spans, dropouts, acoustic skew, decoder latency, CPU, thermal and battery. Keep PCM/TCP as fallback until a candidate is repeatably better without unacceptable regressions.

**References:** [Snapcast](https://github.com/snapcast/snapcast), [RFC 6716 — Opus](https://datatracker.ietf.org/doc/html/rfc6716), [Android audio latency and performance guidance](https://developer.android.com/games/sdk/oboe/low-latency-audio).

**Consequences:** Transport/codec design becomes a measured optimization after synchronization correctness, not an additional parallel project layer.

## ADR-028 — RTP/UDP audio data plane with RTCP feedback

**Status:** Accepted as the target migration architecture; staged implementation in progress  
**Date:** 9 November 2026  
**Supersedes:** The TCP-only transport sequencing recommendation in ADR-027. It does not relax ADR-024's clock-readiness gate or authorize removing the existing TCP fallback.

**Context:** The Android preview currently sends 48 kHz stereo PCM16 over a dedicated TCP audio connection per Speaker. The project already has isolated RTP v2 and basic RTCP packet/socket primitives, but neither is integrated into the Android Host/Speaker audio path. Replacing TCP with UDP alone would not fix the current clock-readiness failure: the receiver must reconstruct the media timeline and map it to its local AudioTrack presentation clock.

**Decision:**
1. Use RTP over UDP unicast for the target audio data plane. Each Speaker has an independent UDP destination and RTCP feedback path. Multicast is deferred.
2. Retain the existing TCP control channel for pairing/admission, session identity, transport negotiation, group-start ANCHOR, and play/pause/resynchronization commands. Keep the current PCM16/TCP data path as a feature-flagged fallback until the RTP path passes physical acceptance.
3. Migrate in separate stages. First send diagnostic PCM16 through RTP without Opus. Then integrate the sample-indexed receiver buffer, add Opus, implement complete RTCP feedback, and finally validate clock mapping/group playout and drift correction. Do not combine transport, codec and clock-estimator changes in one untestable switch.
4. Keep the current strict device clock gate unchanged: residual RMS <0.25 ms, required sample count/span and three consecutive stable updates. UDP packet arrival, RTCP reports or buffer fill do not satisfy the clock gate by themselves.
5. Keep one authoritative media timeline. The Host's 48 kHz RTP timestamp represents the sample time of the first sample in a packet. A common group ANCHOR continues to bind a future Host monotonic time to a 64-bit media sample index. Receivers map that time through the existing four-timestamp clock estimate and later correlate media sample positions with AudioTrack output timestamps.
6. Use RTCP Sender Reports to correlate the RTP media clock with the sender's NTP-format reference clock, and Receiver Reports to return reception statistics. RTCP is feedback and media-clock observation, not a replacement for inter-device monotonic clock acquisition.

**Diagnostic PCM packet profile:** A 20 ms 48 kHz stereo PCM16 chunk is 3,840 bytes and exceeds the current 1,188-byte RTP payload budget. The initial diagnostic profile therefore splits it into four 5 ms packets of 240 stereo frames / 960 bytes each. Sequence numbers advance per datagram; 48 kHz RTP timestamps advance by 240. This is intentionally different from the eventual Opus profile, where one 20 ms payload advances the RTP timestamp by 960 ticks.

**RTCP compliance boundary:** The existing SR and empty RR codec primitives are not a complete RTCP implementation. Production feedback must use RFC 3550 compound RTCP packets (including SDES/CNAME), and Receiver Reports must carry report blocks with loss, extended highest sequence, interarrival jitter, LSR and DLSR data as applicable. Periodic scheduling, sender/receiver state and report validation remain to be implemented. Do not label the current primitives RFC-complete.

**Required migration stages and acceptance:**
1. **Diagnostic PCM RTP profile:** packetization, 16-/32-bit wrap and out-of-order timestamp tests. This is transport-library groundwork only; Android Host/Speaker integration is not complete.
2. **Feature-flagged unicast integration:** authenticate/associate UDP registration with the already-admitted TCP session; verify each receiver's source endpoint, session, SSRC and token; keep TCP audio fallback available. Report packet rate, bytes, duplicates, reordering, loss and receiver buffer fill.
3. **Receiver playout-buffer integration:** feed decoded sample-indexed packets into the bounded native PCM jitter buffer. Test out-of-order delivery, duplicates, missing ranges, late packets, bounded memory and underrun behavior. Verify received PCM energy rather than packet counts alone.
4. **Opus:** integrate a 48 kHz stereo Opus profile using RFC 7587; begin with 20 ms frames / 960 RTP ticks, and measure codec delay, bitrate, decode time and audio quality.
5. **RTCP:** implement periodic compound Sender Reports and per-Speaker Receiver Reports with SDES/CNAME and populated reception report blocks. Map RTP timestamps to the Host reference clock; correlate that clock with the existing Host monotonic timebase.
6. **Synchronized group playout:** preserve one shared future `(masterTimeNs, sampleIndex)` ANCHOR. Establish the relation among RTP timestamps, receiver monotonic time and AudioTrack timestamp frame positions before changing output scheduling.
7. **Drift and physical validation:** measure offset/skew and correct playout without changing sample identity; test one Host/one Speaker first, then two Speakers, then the wider group. Require repeated acoustic recordings with p95 absolute skew ≤10 ms before replacing the TCP fallback.

**Security and network constraints:** UDP registration must be bound to the admitted TCP control peer, current session/receiver identity and admission credential; do not trust an SSRC alone. The existing LAN-discoverable token/security concern in ADR-020 remains open. Use a selected Wi-Fi Network for UDP sockets on Android, and test hotspots/APs with client isolation and UDP filtering. UDP congestion/loss handling and per-Speaker queue bounds must be explicit; unicast is not permission to ignore airtime or congestion.

**Consequences:** The transport target is RTP/UDP + RTCP feedback + TCP control, but the active Android application remains PCM16/TCP until the feature-flagged RTP path passes the same end-to-end and acoustic gates. The diagnostic PCM packetizer currently added to the transport module is not evidence of Android integration or synchronized playback.


## ADR-029 — LAN-local session coordination API

**Status:** Accepted as an additive integration layer; Android direct-host protocol remains authoritative  
**Date:** 9 November 2026

**Context:** The product requirement is offline operation on a private Wi-Fi LAN without cloud accounts or a remote service. The Android Host already performs direct TCP admission/control, UDP clock acquisition and direct audio fan-out. A conventional cloud backend in the critical path would violate the offline requirement and introduce unnecessary network latency and availability dependencies.

**Decision:**
1. Add a versioned FastAPI + SQLite service for session metadata, join-code admission, device identity, coarse presence and diagnostic state.
2. Treat this API as an optional LAN-local coordination service. It may run on a laptop or small LAN host during integration; it is not required for audio delivery or clock synchronization.
3. Keep audio media direct from Host to each Speaker over the existing TCP or experimental RTP/UDP path. Do not proxy PCM through HTTP/WebSocket.
4. Keep four-timestamp clock probes, READY, common ANCHOR and time-critical playback commands on the direct device protocol until a measured migration plan explicitly replaces them.
5. Store only hashes of generated bearer tokens. Return raw credentials once, enforce protocol-version compatibility, use transactional receiver-capacity checks and rate-limit join attempts.
6. Keep the Android app capable of operating without this service. A future embedded Android implementation or a no-backend local discovery path must preserve the product's offline guarantee.

**Initial API scope:** create/close session, join by short code, Host device listing, Speaker heartbeat and coarse device-state reporting. The first implementation is under `backend/`.

**Not a decision:** This ADR does not authorize a cloud account system, remote media relay, remote database, or a requirement for internet connectivity. The API is not yet integrated with Android/iOS clients and is not a production security boundary on untrusted networks.

**Consequences:** Session metadata can be tested independently from audio timing and transport. API state must not be mistaken for device clock readiness or proof of audible playback. Integration, TLS/authenticated encryption, event delivery, migrations, and operational deployment remain follow-up work.
