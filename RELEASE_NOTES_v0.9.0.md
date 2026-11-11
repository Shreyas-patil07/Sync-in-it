# Sync In It Android Cloud Transport Preview v0.9.0

**Release type:** Engineering preview — cloud media transport  
**Architecture:** Render FastAPI + LiveKit Cloud SFU + LiveKit Android SDK

## Included in this migration

- Replaces local NSD/mDNS Host discovery and direct Host-to-Speaker LAN TCP/UDP media with backend session invitations and cloud media transport.
- Host creates a backend session and displays a QR invitation containing the session ID and join code only.
- Speaker scans the QR, joins the backend session and receives a role-scoped LiveKit participant token.
- Host uploads fixed 20 ms, 48 kHz stereo PCM16 frames to the authenticated Render WebSocket media ingress.
- The backend's Python LiveKit RTC publisher publishes PCM to the session LiveKit room.
- Speaker Android devices subscribe to the room using WebRTC.
- Host supports a selected local audio file, eligible Android app-playback capture or a diagnostic test tone.

## Important limitation — synchronization is not included

This cloud transport migration removes the previous Android native sample-indexed jitter buffer, clock estimator, READY gate, common playback ANCHOR and custom AudioTrack scheduling pipeline. The current Speaker uses standard LiveKit/WebRTC remote-audio playout.

**This preview must not be described as synchronized multi-phone playback.** Shared clock estimation, timestamped playout scheduling, drift correction, output-latency compensation and acoustic calibration are not implemented or validated on this branch. A successful build, QR join, LiveKit room connection or PCM uplink counter does not prove synchronized audible output.

## Setup

Configure the Render backend environment:

- LIVEKIT_URL
- LIVEKIT_API_KEY
- LIVEKIT_API_SECRET

The API secret must remain server-side. Do not add it to the APK, QR payload or repository.

## Validation status

At the time these notes were drafted, the cloud migration branch had no published release. Latest-head Android CI and Python backend tests were not yet confirmed passing; live Render/LiveKit integration and physical cross-network Speaker playback remain unverified.

Render Free SQLite storage is ephemeral and active media publisher connections are process-local. Render restarts interrupt active sessions. Check LiveKit account limits before extended use.

## Installation

This is an engineering preview. Use only after the corresponding branch passes CI and the signed APK is published through the repository release workflow. Keep the persistent preview signing key for update installs.
