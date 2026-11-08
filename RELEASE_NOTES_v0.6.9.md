# Sync In It Android Preview v0.6.9

**Release type:** Engineering prerelease  
**Platform:** Android Host + Android Speakers  
**Build:** Updateable debug APK (`com.syncinit.app.debug`)  
**Previous preview:** v0.6.8

## What's new

### Safer audio-source switching
- Changing the Host audio source now stops an active Host session before the new source is applied. The Host must be started again after the change.
- Cancelling the local-file picker or denying Android MediaProjection consent does not stop the currently running Host.
- The source-selection status is updated when Android playback capture starts, stops, or fails.

### System-audio capture diagnostics
- Adds one-second aggregate PCM16 measurements: RMS level (dBFS), peak level (dBFS), and near-zero sample percentage.
- Reports whether the captured signal is near-silent or has measurable signal.
- Diagnostics contain aggregate levels only; captured PCM/audio content is not logged.
- These measurements help distinguish an active capture service from actual non-silent PCM capture. They do not prove that the Host is streaming the correct source or that a Speaker is playing it.

### Clock diagnostic replay
- Preserves kernel UDP receive timestamps as diagnostic side-channel data.
- Extends the offline clock-probe replay tool to compare application receive timestamps with kernel receive timestamps and to replay older diagnostic exports.
- Kernel timestamps were **not** promoted into the live estimator: replay of the available paired Android 12/Android 14 runs did not show consistent improvement.
- The production estimator continues to use application receive timestamps. The strict residual threshold (<0.25 ms), sample-span requirement, three-update stability gate, and READY behavior are unchanged.

## Install and test

1. Install the same APK on the Android Host and every Speaker.
2. Connect all phones to the same trusted hotspot or private Wi-Fi network with client-to-client traffic enabled.
3. On the Host, select **Capture system audio**, grant `RECORD_AUDIO`, then approve Android's MediaProjection prompt.
4. Start playback in an eligible app and wait for capture-active status plus `SYSTEM_AUDIO_LEVEL` diagnostics. Confirm that RMS/peak indicate `signal-present`, not `near-silent`.
5. Start hosting, then join from the Speaker phones.
6. Export Host and Speaker diagnostic reports after the run. Confirm every Speaker reaches READY and that the Host sends `GROUP_START_SCHEDULED` / a common ANCHOR.
7. Verify audible output on every phone and measure acoustic skew separately.

You can also select a local audio file or the diagnostic test signal. If you change audio sources while hosting, the Host session is stopped and must be started again.

## Validation status

- PR #78 merged into main at `79c50372a22a1378d265a2a4f9308a477863540c`.
- Android app/JNI build, transport checks and persistent APK signature verification passed.
- Kotlin transport tests passed.
- M0 tooling, C++ parity and simulator acceptance tests passed on Python 3.11, 3.12 and 3.13.
- The release workflow rebuilds the APK, reruns Android transport checks, verifies the persistent preview signature, and publishes this prerelease.

**Physical validation is still pending.** The available paired Android 12/Android 14 timestamp replay did not clear the strict gate: the application-timestamp residual RMS values were approximately 0.354 ms and 0.298 ms in the two directions, both above 0.25 ms. Kernel timestamp comparisons were approximately 0.374 ms and 0.296 ms respectively, so they were retained for diagnosis rather than enabled in production. No successful physical run has yet demonstrated non-silent app-playback capture, READY on every Speaker, common ANCHOR delivery, audible synchronized output, or measured acoustic skew. A successful CI build is not proof of synchronized playback.

## Known limitations and security

- One Host and up to eight Speakers in the current Android implementation.
- Android playback capture requires Android 10 (API 29) or newer, `RECORD_AUDIO` permission and fresh MediaProjection consent.
- Capture is restricted by source-app playback policy and supported usages (`USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`). Protected or capture-restricted apps may produce silence.
- The Host phone continues to render the source app's audio; this preview does not automatically mute the Host.
- No playlist, queue, seek, pause/resume controls, background/endurance guarantee, automatic drift correction, or microphone-based acoustic calibration.
- DNS-SD discovery exposes the current session token to devices browsing the same LAN. Use a trusted private network.
- Review diagnostic exports before sharing; they may include device model, local network addresses, session IDs, and timing metadata.

This is an engineering prerelease for controlled testing, not a production or acoustically validated release.
