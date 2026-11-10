# Sync In It Android Preview v0.8.0

**Type:** Engineering preview  
**Focus:** QR-based Host/Speaker pairing and optional backend session coordination

## What's new

- The Android Host now creates an optional backend coordination session when local hosting starts.
- The Host displays a QR invitation containing the backend's eight-character join code and a non-secret fingerprint for matching the correct LAN Host.
- Speakers can scan the invitation using the in-app Google Play services Code Scanner flow.
- After scanning, the Speaker resolves the matching Host through local Android NSD/DNS-SD discovery, then uses the existing direct TCP admission and UDP clock-synchronization paths.
- Once the local media connection is established, the Speaker joins the backend session for presence and coordination.
- Host and Speaker WebSocket lifecycle now uses the reconnecting coordinator, authenticated first-frame WebSocket authentication, reconnect backoff and snapshot reconciliation.
- Speaker heartbeat continues over the WebSocket, with REST fallback while reconnecting. Speaker session membership is removed through the authenticated self-leave endpoint on disconnect.
- The backend health endpoint supports GET and HEAD for monitoring services such as UptimeRobot.

## Architecture and pairing requirements

- Both phones must be on the same reachable private Wi-Fi network or hotspot. The network must allow client-to-client traffic and mDNS/multicast.
- QR pairing does not provide internet-wide discovery, NAT traversal or cross-network audio.
- The QR contains no Host or Speaker bearer tokens. It contains only the join code and local Host fingerprint.
- The existing local NSD advertisement still exposes the local 16-byte admission token to devices able to browse the same LAN. Use a trusted private network.
- PCM/audio, four-timestamp clock probes, READY, ANCHOR and playback deadlines remain on the direct Host/Speaker channels. The backend is not an audio relay.
- Backend coordination remains optional. If the backend is unavailable, local nearby discovery can still be used.
- Backend sessions currently expire after two hours. Backend WebSocket fan-out and pending commands are process-local and are not durable.
- Render Free storage is ephemeral; do not rely on it for durable session or device history.

## Validation status

- Backend API test matrix: Python 3.11, 3.12 and 3.13.
- Native simulator/core test matrix: Python 3.11, 3.12 and 3.13 with C++17.
- Android APK is built and signature-verified by the release workflow using the persistent preview signing key.
- QR scanning on physical phones, live Render WebSocket behavior, multi-Speaker reconnect behavior, READY/ANCHOR completion and acoustic synchronization still require device testing.

A successful build is not evidence of synchronized audible output. Do not interpret this engineering preview as a production audio synchronization release.
