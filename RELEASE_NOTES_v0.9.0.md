# Sync In It Android Cloud Transport Testing Preview v0.9.0-preview.1

**Release type:** Testing APK / engineering preview — not a stable release  
**Architecture:** Render FastAPI + LiveKit Cloud SFU + LiveKit Android SDK  
**Cloud transport integration:** `9f46f905ada3fe654aeec1053c1e620034e63d67` (PR #94)  
**Preview build source:** current `main` baseline `513b8c1276b928763d8ef7686e6287b66bb2636a`, plus this release-only notes commit.

## Included

- Replaces local NSD/mDNS Host discovery and direct Host-to-Speaker LAN TCP/UDP media with backend session invitations and cloud media transport.
- Host creates a backend session and displays a QR invitation containing the session ID and join code only.
- Host QR invitation is displayed immediately after backend session creation while LiveKit media connects.
- Speaker scans the QR, joins the backend session and receives a role-scoped LiveKit participant token.
- Host uploads fixed 20 ms, 48 kHz stereo PCM16 frames to the authenticated Render WebSocket media ingress.
- The backend's Python LiveKit RTC publisher publishes PCM to the session LiveKit room.
- Speaker Android devices subscribe to the room using WebRTC.
- Host supports a selected local audio file, eligible Android app-playback capture or a diagnostic test tone.

## Important limitation — synchronization is not included

This cloud transport migration removes the previous Android native sample-indexed jitter buffer, clock estimator, READY gate, common playback ANCHOR and custom AudioTrack scheduling pipeline. The current Speaker uses standard LiveKit/WebRTC remote-audio playout.

**Do not describe this preview as synchronized multi-phone playback.** Shared clock estimation, timestamped playout scheduling, drift correction, output-latency compensation and acoustic calibration are not implemented or validated in this architecture. A successful build, QR join, LiveKit room connection or PCM uplink counter does not prove synchronized audible output.

## Deployment and validation evidence

- Render deployed main commit `9f46f905` successfully on 11 November 2026. The deploy reached `live`; subsequent observed `HEAD /healthz` requests returned HTTP 200.
- These health checks prove that the FastAPI process responds. They do not prove LiveKit credentials, participant-token issuance, Host PCM publishing, Speaker subscription or audible playback.
- The repository's CI backlog was throttled through PRs #97 and #98. Eight older queued runs were still stuck in GitHub's Actions scheduler at the time this preview was prepared; they have not been treated as passing checks.
- The preview publishing workflow performs its own Android debug APK build and backend checks. A successful preview build is artifact-generation evidence, not a substitute for the full release CI matrix.
- Backend LiveKit unit tests use a fake publisher and do not contact the real LiveKit project, Render service or physical Android devices.
- Physical two-phone playback over separate networks, reconnect behavior, endurance and acoustic skew have not been evidenced.

## Operational limitations

- Render Free SQLite storage is ephemeral; session data is not durable across instance replacement.
- Active WebSocket and LiveKit publisher registrations are process-local. A Render restart interrupts active sessions.
- Multi-instance session/publisher coordination and durable event replay are not implemented.
- Android playback capture is subject to Android source-app capture policy; protected or capture-restricted content may be silent.
- iOS Speaker support and measured 6–10-device capacity are not implemented or validated.

## Installation and signing

The preview channel builds the debug variant with application ID `com.syncinit.app.debug` and requires the persistent preview signing key. Keep that key and package ID to update an existing preview installation.

The stable channel is also present in the current `main` publisher workflow, but requires a separate production keystore, protected GitHub Actions secrets, successful release gates and explicit sign-off. It is not being invoked by this preview.

Install the APK only after the matching build succeeds and the APK signature is verified. Do not uninstall an existing preview unless a package/signature change is intended.

## Stable-release decision

**v0.9.0 is not cleared for stable publication.** The original synchronized-speaker product behavior is absent from this cloud transport architecture, real LiveKit media flow is unverified, physical playback testing has not been evidenced, and production signing has not been exercised.

This file is for a clearly labeled testing preview only. See [v0.9.0 Stable Release Readiness](docs/Release_Readiness_v0.9.0.md) for the stable-release go/no-go criteria.
