# Sync In It

<p align="center">
  <img src="frontend/public/images/sync_in_it.png" alt="Sync In It logo" width="260" />
</p>

> Android-first multi-phone audio distribution using backend-issued QR invitations and LiveKit Cloud media delivery.

**Project website:** [Sync In It](./frontend)

## Current branch and release state

- **Current main commit:** `9f46f905ada3fe654aeec1053c1e620034e63d67` — cloud transport migration merged through [PR #94](https://github.com/Shreyas-patil07/Sync-in-it/pull/94).
- **Latest published APK:** [v0.8.0](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.8.0). It is the previous direct-LAN implementation and is not the cloud migration.
- **Current main architecture:** Render-hosted FastAPI session/authentication service + LiveKit Cloud SFU + LiveKit Android SDK.
- **v0.9.0:** release notes and cloud implementation are on main, but no v0.9.0 tag or APK has been published. The current release workflow only publishes preview APKs; a production-signed stable release lane is not yet configured.

## Important product limitation

The cloud transport currently uses standard LiveKit/WebRTC remote-audio playout. It does **not** preserve the earlier sample-indexed jitter buffer, clock estimator, READY gate, common playback ANCHOR or custom AudioTrack playout scheduler.

**Do not describe the current cloud build as synchronized multi-phone playback.** Shared clock mapping, timestamped playout scheduling, drift correction, output-latency compensation and acoustic calibration are not implemented or measured in this architecture. A successful build, QR join, WebRTC connection or PCM frame count is not proof of synchronized audible output.

## Cloud architecture

Host Android sends 48 kHz stereo PCM16 frames in 20 ms packets to an authenticated Render WebSocket ingress. The FastAPI backend publishes those frames through its Python LiveKit RTC publisher into a LiveKit Cloud room. Speaker Android devices subscribe to the room over WebRTC.

The backend also handles session creation, eight-character join codes, role-scoped LiveKit access tokens, QR invitation validation and device presence. The Host QR contains a session ID and join code only; it does not contain a LAN address or bearer token. Phones can join from different internet connections without sharing Wi-Fi.

## Cloud configuration

Configure these variables on the Render backend service:

| Variable | Purpose |
|---|---|
| LIVEKIT_URL | LiveKit project WebSocket URL (wss://...) |
| LIVEKIT_API_KEY | LiveKit server API key |
| LIVEKIT_API_SECRET | LiveKit server API secret |

Keep `LIVEKIT_API_SECRET` on the backend only. Never put it in Android resources, BuildConfig, source control, QR payloads or diagnostic logs.

## Media format

- Host audio is converted to 48 kHz, stereo, signed PCM16.
- Each uplink frame contains 960 samples per channel (20 ms; 3,840 bytes).
- Android sends binary frames to the authenticated Render WebSocket ingress.
- The Python backend publishes each frame to a LiveKit audio track.
- Speakers subscribe using role-scoped LiveKit credentials.

## Build and test

Requirements: JDK 17, Gradle 8.13, Android SDK 36 and Python 3.11+.

```bash
gradle -p android :app:assembleDebug :backend:check --stacktrace
python -m pip install -e "backend[test]"
cd backend
python -m pytest
```

The Android build includes the LiveKit Android SDK and Google Play services Code Scanner. The scanner requires a working Play services Code Scanner component on the device. The Host supports a selected local audio file, eligible Android app-playback capture or a diagnostic test tone.

## Current limitations and operational risks

- Render Free uses ephemeral local SQLite storage. Session data is not durable across instance replacement, and active WebSocket/LiveKit publisher state is process-local. A service restart interrupts active sessions.
- The current backend runs as one Render instance. Multi-instance coordination and durable event/session storage are not implemented.
- LiveKit credentials and actual Host-publish/Speaker-subscribe behavior still require live integration validation; a healthy FastAPI endpoint alone does not prove media works.
- Android system playback capture is subject to the source app's capture policy and may be silent for protected or capture-restricted content.
- iOS Speaker support and measured 6–10-device capacity are not implemented or validated.
- Physical cross-network playback and acoustic synchronization have not been demonstrated.

## Release readiness

See [v0.9.0 stable release readiness](docs/Release_Readiness_v0.9.0.md) for the current go/no-go checklist and required evidence. The current v0.9.0 notes intentionally describe an engineering preview, not a stable synchronized-speaker release.

## Project records

- [Current implementation status](docs/11_Current_Implementation_Status.md)
- [Android application](android/README.md)
- [Backend API and LiveKit media ingress](backend/README.md)
- [Test results](docs/Test_Results.md)
- [Architecture decisions](docs/ADR/Architecture_Decisions.md)
