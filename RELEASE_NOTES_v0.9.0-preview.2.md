# Sync In It Android Cloud Transport Testing Preview v0.9.0-preview.2

**Release type:** Testing APK / engineering preview — not a stable release  
**Architecture:** Render FastAPI + LiveKit Cloud SFU + LiveKit Android SDK  
**Preview source:** `release/v0.9.0-preview.2`

## Changes in this preview

- Fixes Host QR visibility: the invitation QR is generated and displayed immediately after backend session creation, without waiting for LiveKit media startup.
- The Host UI shows that media is still connecting while the QR invitation is already available.
- Retains the persistent preview signing key and package ID `com.syncinit.app.debug` so this APK can update an existing preview installation.

## Included

- Backend session invitations and cloud media transport through LiveKit.
- Host QR invitation contains the session ID and join code only.
- Speaker scans the QR, joins the backend session and receives a role-scoped LiveKit participant token.
- Host uploads fixed 20 ms, 48 kHz stereo PCM16 frames to the authenticated Render WebSocket media ingress.
- Speaker Android devices subscribe to the LiveKit room using WebRTC.
- Host supports a selected local audio file, eligible Android app-playback capture or a diagnostic test tone.

## Important limitation — synchronization is not included

This cloud transport migration removes the previous Android native sample-indexed jitter buffer, clock estimator, READY gate, common playback ANCHOR and custom AudioTrack scheduling pipeline. The current Speaker uses standard LiveKit/WebRTC remote-audio playout.

**Do not describe this preview as synchronized multi-phone playback.** Shared clock estimation, timestamped playout scheduling, drift correction, output-latency compensation and acoustic calibration are not implemented or validated. A successful build, QR join, LiveKit room connection or PCM uplink counter does not prove synchronized audible output.

## Validation boundaries

- The publishing workflow builds the Android preview APK and runs backend checks.
- Backend LiveKit unit tests use a fake publisher and do not contact the real LiveKit project, Render service or physical Android devices.
- Physical two-phone playback over separate networks, reconnect behavior, endurance and acoustic skew have not been evidenced.
- A successful build proves artifact generation, not end-to-end media functionality.

## Operational limitations

- Render Free SQLite storage is ephemeral; session data is not durable across instance replacement.
- Active WebSocket and LiveKit publisher registrations are process-local. A Render restart interrupts active sessions.
- Multi-instance session/publisher coordination and durable event replay are not implemented.
- Android playback capture is subject to Android source-app capture policy; protected or capture-restricted content may be silent.
- iOS Speaker support and measured 6–10-device capacity are not implemented or validated.

## Installation and signing

The preview channel builds the debug variant with application ID `com.syncinit.app.debug` and requires the persistent preview signing key. Keep that key and package ID to update an existing preview installation.

Install only after the matching workflow succeeds and the APK signature is verified. Do not uninstall an existing preview unless a package/signature change is intended.

## Stable-release decision

**v0.9.0 is not cleared for stable publication.** Real LiveKit media flow, physical playback testing and production signing have not been evidenced. This is a testing preview only.

See [v0.9.0 Stable Release Readiness](docs/Release_Readiness_v0.9.0.md) for the stable-release go/no-go criteria.
