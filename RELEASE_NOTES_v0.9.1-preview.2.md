# Sync In It Android v0.9.1 Preview 2

**Release channel:** Engineering preview (updateable Android preview APK)  
**Version:** 0.9.1  
**Tag:** `v0.9.1-preview.2`

## Included changes

- Fixes silent cloud playback when LiveKit supplies valid 48 kHz mono PCM16 audio callbacks.
- Converts mono frames to the app's 48 kHz stereo output by duplicating each sample into left and right channels.
- Preserves stereo PCM16 callbacks and their channel order.
- Rejects unsupported bit depths, sample rates, channel counts, incomplete callback buffers, and oversized callbacks.
- Adds regression tests for mono conversion, stereo pass-through, and invalid PCM formats.
- Emits a diagnostic when actual LiveKit PCM callbacks begin flowing.

## Validation status

- Backend API tests passed on Python 3.11, 3.12, and 3.13 for the merged change.
- Android unit tests and APK build/signature verification are part of the release pipeline.
- Real-device audible playback and multi-device synchronization still require testing after installation.
- Android system playback capture remains subject to each source app's capture policy; an app that blocks capture may still produce silent Host input.

## Installation

- Preview package ID: `com.syncinit.app.debug`.
- This APK is intended to update the existing Sync In It preview installation using the persistent preview signing identity.
- Keep the current preview app installed when upgrading. Uninstalling can delete local app data.
