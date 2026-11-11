# Sync In It

<p align="center">
  <img src="frontend/public/images/sync_in_it.png" alt="Sync In It logo" width="260" />
</p>

> Android-first multi-phone audio distribution. The cloud-migration branch uses backend-issued QR invitations and LiveKit Cloud for Host-to-Speaker media delivery.

**Project website:** [Sync In It](./frontend)

## Current branch and release state

- **Latest published release:** [v0.8.0](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.8.0). This release still uses direct local-network media; backend integration is optional coordination only.
- **Cloud migration branch:** feature/livekit-cloud-audio-transport. This branch removes Android NSD/mDNS and direct Host-to-Speaker sockets and replaces media delivery with a Render-hosted FastAPI ingress and LiveKit Cloud SFU.
- **Merge/release state:** the cloud migration has not been merged into main or published. Build and backend validation for the current branch head are still required.
- **Synchronization status:** the cloud migration currently uses standard LiveKit remote-audio playout. It does **not** preserve the old sample-indexed jitter buffer, clock estimator, READY gate or common playback ANCHOR. Synchronized acoustic playback is therefore not implemented or demonstrated on this branch.

Do not interpret a successful build, QR join or WebRTC connection as proof of synchronized multi-phone output.

## Cloud architecture

Host Android sends 48 kHz stereo PCM16 in 20 ms frames to an authenticated Render WebSocket ingress. The FastAPI backend publishes those frames through its Python LiveKit RTC publisher into a LiveKit Cloud room. Speaker Android devices subscribe to the room through WebRTC.

The Render backend also handles session creation, join codes, role-scoped LiveKit access tokens, QR invitation validation and device presence. The Host QR contains a backend session ID and an eight-character join code. It does not contain a LAN address, LAN fingerprint or bearer token. Speakers can join over different internet connections; phones do not need to share a Wi-Fi network.

## Cloud configuration

Configure these variables on the Render backend service:

| Variable | Purpose |
|---|---|
| LIVEKIT_URL | LiveKit project WebSocket URL (wss://...) |
| LIVEKIT_API_KEY | LiveKit server API key |
| LIVEKIT_API_SECRET | LiveKit server API secret |

Keep LIVEKIT_API_SECRET on the backend only. Never place it in Android resources, BuildConfig, source control, QR payloads or diagnostic logs. A screenshot of masked Render variables confirms only the variable names, not the correctness of their stored values.

## Media format

- Host source is converted to 48 kHz, stereo, signed PCM16.
- Each uplink frame is 960 samples per channel (20 ms; 3,840 bytes).
- Android sends binary frames to the authenticated Render WebSocket ingress.
- The Python backend publishes each frame to a LiveKit audio track.
- Speakers subscribe to the room with speaker-only credentials.

## Build and test

Requirements: JDK 17, Gradle 8.13, Android SDK 36 and Python 3.11+.

    gradle -p android :app:assembleDebug :backend:check --stacktrace
    python -m pip install -e "backend[test]"
    cd backend
    python -m pytest

The Android build includes the LiveKit Android SDK and Google Play services QR scanner. The scanner requires a working Play services Code Scanner component on the device. The Host can use a selected local audio file, eligible Android app-playback capture or a diagnostic test tone.

## Current limitations

- The cloud media transport and token issuance are implemented on the feature branch but still need passing CI at the final branch head and physical testing across separate internet connections.
- Speaker audio currently uses normal LiveKit SDK playout. Precise clock synchronization, common start-time scheduling, drift correction, output-latency calibration and acoustic skew measurement are not part of this cloud migration yet.
- Render Free deployments can restart and their local filesystem is ephemeral. SQLite session state is not durable across instance replacement; active in-memory WebSocket/media-publisher state ends when the process restarts.
- LiveKit account limits apply. Check the current account quota before extended sessions.
- Android system playback capture is subject to Android source-app capture policy and may be silent for protected or capture-restricted content.
- iOS Speaker support and measured 6–10-device capacity are not implemented or validated in this branch.

## Project records

- [Current implementation status](docs/11_Current_Implementation_Status.md)
- [Android application](android/README.md)
- [Backend API and LiveKit media ingress](backend/README.md)
- [Test results](docs/Test_Results.md)
- [Architecture decisions](docs/ADR/Architecture_Decisions.md)
