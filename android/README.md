# Android application

**Current migration branch:** feature/livekit-cloud-audio-transport  
**Architecture:** QR invitation + Render FastAPI session backend + LiveKit Cloud SFU.  
**Latest published APK:** v0.8.0 on main; it is still the previous direct-LAN implementation.

## Modules and media flow

The Android Gradle project now contains:

- app/: Android launcher, QR generation/scanning, local-file/system-playback audio sources, cloud Host publisher and LiveKit Speaker room.
- backend/: typed Kotlin REST/WebSocket client for the Render FastAPI service, including session credentials and the authenticated binary PCM uplink.

The old Android NSD/mDNS discovery, local TCP/UDP sockets, RTP socket path, native C++ session bridge and direct-LAN transport module are removed from this branch.

Host Android sends PCM16/48 kHz/20 ms frames to the Render FastAPI WebSocket ingress. The backend Python LiveKit RTC publisher sends the frames into a LiveKit Cloud room. Speaker Android devices subscribe to remote audio tracks over WebRTC.

The Host starts a backend session and the LiveKit publisher before displaying a QR invitation. The QR v2 payload contains only session_id and join_code. A Speaker scans the QR, joins the backend using the code, verifies the returned session ID, requests a role-scoped LiveKit token and connects to the LiveKit room. No same-Wi-Fi network or local Host discovery is used.

## Audio sources

The Host can publish one of three sources:

1. A selected local audio file, decoded with Android MediaExtractor/MediaCodec and converted to 48 kHz stereo PCM16.
2. Eligible Android app playback captured with MediaProjection on Android 10+ after RECORD_AUDIO permission and fresh system consent.
3. A generated diagnostic 1 kHz test tone.

The Host sends 960 stereo frames per 20 ms uplink packet (3,840 PCM bytes). The backend publishes these frames to one LiveKit audio track. Speakers subscribe through the LiveKit Android SDK and use its normal media-audio playout.

System playback capture is subject to the source app's Android capture policy. DRM/protected or capture-restricted streams may be silent.

## Important synchronization limitation

This branch removes the previous native sample-indexed PCM jitter buffer, four-timestamp clock estimator, strict clock-readiness gate, READY retry, common future ANCHOR path and custom AudioTrack playout scheduler. The current Speaker receives a LiveKit track using standard WebRTC playout.

**This is cloud audio transport, not yet synchronized multi-phone playback.** Shared clock estimation, timestamped playout scheduling, drift correction, output-latency calibration and acoustic skew validation are not included in the current branch. Do not claim synchronized output based only on QR pairing, a LiveKit room connection or PCM frame counters. This is a known gap against the original synchronized-speaker goal and must be resolved before claiming that goal is achieved.

## Cloud environment

The Android app connects to the fixed backend origin in MainActivity.kt:

https://sync-in-it.onrender.com

Configure these secrets on the Render backend service, not in Android:

- LIVEKIT_URL
- LIVEKIT_API_KEY
- LIVEKIT_API_SECRET

The API secret signs server-side LiveKit credentials and creates the backend publisher. Never add it to the APK, a QR code, a GitHub file or a client log.

## Build and checks

Toolchain: JDK 17, Gradle 8.13 and Android SDK 36.

    gradle -p android :app:assembleDebug :backend:check --stacktrace

Backend API tests:

    python -m pip install -e "backend[test]"
    cd backend
    python -m pytest

The CI workflow also verifies the preview APK signature when persistent preview signing secrets are configured.

## QR scanner

The app uses Google Play services Code Scanner, with the barcode_ui dependency declared for download. A device needs working Google Play services and the scanner component. The QR contains no IP address or bearer token.

## Release signing

Updateable preview APKs keep the existing debug application ID com.syncinit.app.debug. To install over an existing copy, the package ID, signing certificate and increasing versionCode must all match. Keep the persistent preview keystore and the GitHub Actions secrets:

- SYNCINIT_PREVIEW_KEYSTORE_BASE64
- SYNCINIT_PREVIEW_KEYSTORE_PASSWORD

Never commit the keystore or passwords.

## Validation status

The cloud migration has not been merged or released. Current-head Android CI, Python backend tests, live Render integration, physical two-network Host/Speaker audio and reconnect tests must pass before release. The older v0.8.0 APK remains the only published release and still depends on the local network for audio.
