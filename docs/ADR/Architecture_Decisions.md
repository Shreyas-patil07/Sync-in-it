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

**Status:** Open  
**Context:** The original design references Snapcast as an architectural reference. Architectural inspiration does not mean code can be copied without license review.  
**Decision required:** Select the repository license after checking dependencies and any copied or derived code. Review third-party licenses before importing source.

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
