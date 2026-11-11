# Android application

**Current branch:** `main`  
**Current architecture:** QR invitation + Render FastAPI session backend + LiveKit Cloud SFU.  
**Latest published APK:** v0.8.0; it remains the previous direct-LAN implementation.  
**Current cloud implementation:** merged to main through PR #94 at `9f46f905ada3fe654aeec1053c1e620034e63d67`.

## Modules and media flow

The Android Gradle project contains:

- `app/`: Android launcher, QR generation/scanning, local-file/system-playback audio sources, cloud Host publisher and LiveKit Speaker room.
- `backend/`: typed Kotlin REST/WebSocket client for the Render FastAPI service, including session credentials and the authenticated binary PCM uplink.

The cloud implementation removes Android NSD/mDNS discovery, direct Host-to-Speaker TCP/UDP media, and the previous native synchronized-playback pipeline.

Host Android sends PCM16/48 kHz/20 ms frames to the Render FastAPI WebSocket ingress. The Python LiveKit RTC publisher sends the frames into a LiveKit Cloud room. Speaker Android devices subscribe to remote audio tracks over WebRTC.

The Host starts a backend session and LiveKit publisher before displaying a QR invitation. The QR v2 payload contains only `session_id` and `join_code`. A Speaker scans the QR, joins the backend, verifies the returned session ID, requests a role-scoped LiveKit token and connects to the LiveKit room. No same-Wi-Fi network or local Host discovery is required.

## Audio sources

The Host supports:

1. A selected local audio file, decoded with Android MediaExtractor/MediaCodec and converted to 48 kHz stereo PCM16.
2. Eligible Android app playback captured with MediaProjection on Android 10+ after RECORD_AUDIO permission and fresh system consent.
3. A generated diagnostic 1 kHz test tone.

The Host sends 960 stereo frames per 20 ms uplink packet (3,840 PCM bytes). The backend publishes these frames to one LiveKit audio track. Speakers use standard LiveKit/WebRTC playout.

System playback capture is subject to the source app's Android capture policy. DRM/protected or capture-restricted streams may be silent.

## Important synchronization limitation

This cloud implementation does not include the previous sample-indexed PCM jitter buffer, four-timestamp clock estimator, strict clock-readiness gate, READY retry, common future ANCHOR path or custom AudioTrack playout scheduler.

**This is cloud audio transport, not yet synchronized multi-phone playback.** Shared clock estimation, timestamped playout scheduling, drift correction, output-latency compensation and acoustic skew validation are not included. Do not claim synchronized output based only on QR pairing, a LiveKit room connection or PCM frame counters. This remains a gap against the original synchronized-speaker goal.

## Cloud environment

The Android app connects to the backend origin:

`https://sync-in-it.onrender.com`

Configure these secrets on the Render backend service, not in Android:

- `LIVEKIT_URL`
- `LIVEKIT_API_KEY`
- `LIVEKIT_API_SECRET`

The API secret signs server-side LiveKit credentials and creates the backend publisher. Never add it to the APK, a QR code, a GitHub file or a client log.

## Build and checks

Toolchain: JDK 17, Gradle 8.13 and Android SDK 36.

```bash
gradle -p android :app:assembleDebug :backend:check --stacktrace
```

Backend API tests:

```bash
python -m pip install -e "backend[test]"
cd backend
python -m pytest
```

The CI workflow verifies the preview APK signature when persistent preview signing secrets are configured. The latest main-commit CI runs must complete successfully before publishing any build.

## QR scanner

The app uses Google Play services Code Scanner, with the `barcode_ui` dependency declared for download. A device needs working Google Play services and the scanner component. The QR contains no IP address or bearer token.

## Preview signing and stable-release distinction

Updateable preview APKs use application ID `com.syncinit.app.debug`. To install over an existing preview, the package ID, signing certificate and increasing versionCode must all match. The preview workflow uses:

- `SYNCINIT_PREVIEW_KEYSTORE_BASE64`
- `SYNCINIT_PREVIEW_KEYSTORE_PASSWORD`

The main-branch publisher currently builds `:app:assembleDebug`, signs with the preview key and always marks releases as prereleases. This is **not** a production stable-release pipeline. The release-preparation branch adds a separate stable lane that builds `:app:assembleRelease` (application ID `com.syncinit.app`), requires a dedicated production keystore, verifies the APK signature, and publishes without the prerelease flag only after explicit manual sign-off and stable release notes. The lane is not available on `main` until this branch is merged. It requires these GitHub Actions secrets:

- `SYNCINIT_RELEASE_KEYSTORE_BASE64` — base64-encoded production JKS keystore.
- `SYNCINIT_RELEASE_KEYSTORE_PASSWORD` — keystore password.
- `SYNCINIT_RELEASE_KEY_ALIAS` — production signing alias.
- `SYNCINIT_RELEASE_KEY_PASSWORD` — private-key password.

Preview signing remains separate and continues to use `SYNCINIT_PREVIEW_KEYSTORE_BASE64` and `SYNCINIT_PREVIEW_KEYSTORE_PASSWORD`.

Never commit keystores or passwords.

## Validation status

The cloud backend deploy for main commit `9f46f905` completed successfully on Render, and observed `HEAD /healthz` requests returned HTTP 200. That verifies API process liveness only.

At the latest repository audit, the four CI workflows for the main merge were still queued. Real LiveKit token issuance, Host PCM publishing, Speaker subscription, cross-network phone playback, reconnect behavior and acoustic skew remain unverified. Render Free SQLite is ephemeral and active media state is process-local.

See [v0.9.0 stable release readiness](../docs/Release_Readiness_v0.9.0.md) for the release gate.
