# Sync In It Android Cloud Transport Preview v0.9.0

**Release type:** Engineering preview candidate — not a stable release  
**Architecture:** Render FastAPI + LiveKit Cloud SFU + LiveKit Android SDK  
**Integrated main commit:** `9f46f905ada3fe654aeec1053c1e620034e63d67` (PR #94)

## Included

- Replaces local NSD/mDNS Host discovery and direct Host-to-Speaker LAN TCP/UDP media with backend session invitations and cloud media transport.
- Host creates a backend session and displays a QR invitation containing the session ID and join code only.
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
- At the latest audit, the Android app/JNI, Backend API, Kotlin transport and M0 simulator/native CI runs for this main commit were still queued. No green latest-head CI result was available.
- Backend LiveKit unit tests use a fake publisher and do not contact the real LiveKit project, Render service or physical Android devices.
- Physical two-phone playback over separate networks, reconnect behavior, endurance and acoustic skew have not been evidenced.

## Operational limitations

- Render Free SQLite storage is ephemeral; session data is not durable across instance replacement.
- Active WebSocket and LiveKit publisher registrations are process-local. A Render restart interrupts active sessions.
- Multi-instance session/publisher coordination and durable event replay are not implemented.
- Android playback capture is subject to Android source-app capture policy; protected or capture-restricted content may be silent.
- iOS Speaker support and measured 6–10-device capacity are not implemented or validated.

## Installation and signing

The current publisher workflow builds a debug APK with application ID `com.syncinit.app.debug`, signs it with the persistent preview key and always creates a GitHub prerelease. It does not create a production-signed stable APK.

Install only after the matching CI build and signed APK are available. To update an existing preview, preserve the same preview signing key and package ID. Do not uninstall first unless a package/signature change is intended.

## Stable-release decision

**v0.9.0 is not cleared for stable publication.** The original synchronized-speaker product behavior is absent from this cloud transport architecture, latest-head CI has not yet passed, real LiveKit media flow is unverified, and the stable signing/publishing path is not configured.

Use this file for a clearly labeled engineering preview only. See [v0.9.0 Stable Release Readiness](docs/Release_Readiness_v0.9.0.md) for the go/no-go criteria.
