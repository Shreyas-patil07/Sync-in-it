# Sync In It Android v0.9.1 Preview

**Release channel:** Engineering preview (updateable Android preview APK)  
**Version:** 0.9.1  
**Source commit:** `5dedd04b22eb7ab60015b1b3614efa1c0541d92e` (merge of PR #107)  
**Tag:** `v0.9.1-preview.1`

## Included changes

- Fixes Speaker-side Android UI-thread violations during QR join and diagnostic updates.
- Detects missing backend WebSocket heartbeat acknowledgements and reconnects half-open Speaker event channels.
- Handles LiveKit reconnect, disconnect, audio-track unsubscribe/resubscribe, and paused/resumed stream events.
- Refreshes the shared server clock after transport recovery and updates Speaker readiness only when the audio path and clock are valid.
- Tracks in-flight PCM writes and requires at least 3.5 seconds of buffered PCM before a scheduled start.
- Invalidates a pending or active synchronized start when its media track, transport, or clock is interrupted.
- Reports Speaker readiness and degraded-state changes to the Host.

## Important validation status

This is an engineering preview, not a stable release.

- Automated Android build, backend contract checks, APK signature verification and release asset upload must pass for this exact release commit.
- Physical multi-device playback, separate-network reconnects, and measured acoustic skew have not yet been validated.
- A successful build or accepted PCM uplink frames do not prove audible playback or precise synchronization.
- Render process restarts can interrupt active sessions; durable session state and multi-instance coordination are not implemented.

## Installation

- Preview package ID: `com.syncinit.app.debug`.
- The APK uses the persistent preview signing identity so it can update an existing preview installation.
- Keep the existing preview app installed when upgrading. Do not uninstall it unless you intend to discard its app data.
