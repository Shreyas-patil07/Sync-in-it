# Sync In It Android Preview v0.6.8

**Release type:** Engineering prerelease  
**Platform:** Android Host + Android Speakers  
**Build:** Updateable debug APK (\`com.syncinit.app.debug\`)

## What's new

### Android system-audio capture
- The Host can capture eligible playback from other Android apps using Android's MediaProjection and AudioPlaybackCapture APIs.
- Capture requires the Android \`RECORD_AUDIO\` runtime permission and a fresh user-approved MediaProjection session.
- Captured audio is normalized to the existing 48 kHz, stereo, PCM16 stream and placed in a bounded rolling buffer.
- Capture runs in a visible foreground service and stops when the session/capture is stopped or Android revokes projection access.

### Local audio-file sharing
- The Host can select one local audio file through Android's document picker.
- MediaExtractor/MediaCodec decodes the file into the existing 48 kHz stereo PCM16 format.
- The selected track loops at EOF. The diagnostic 1 kHz test signal remains available as a fallback.

### Shared playback path
- Local-file and system-audio sources use the same sample-indexed PCM producer and TCP fan-out.
- The existing clock-stability and buffer-readiness gates are unchanged.
- Once the full admitted Speaker cohort reaches READY and the admission window closes, the Host prebuffers two seconds and sends the common future ANCHOR for the group start.
- Includes the Android kernel UDP \`recvmsg\` source-address fix carried in PR #75.

## System-audio capture compatibility

System-audio capture is supported on Android 10 (API 29) and newer, subject to Android's playback-capture policy. Only supported playback usages are requested (\`USAGE_MEDIA\`, \`USAGE_GAME\`, \`USAGE_UNKNOWN\`). Source apps must permit capture; protected, DRM, or capture-restricted playback may be silent. This is app-playback capture, not microphone recording, not a universal system mixer tap, and not a DRM bypass.

The Host phone continues to render audio in the original source app. Sync In It forwards captured PCM to connected Speakers; this release does not provide source-app volume control or automatic local muting.

## Install and test

1. Install the APK on the Android Host and all Speaker phones.
2. Connect all phones to the same trusted hotspot or private Wi-Fi network with client-to-client traffic enabled.
3. On the Host, choose **Capture system audio**, grant \`RECORD_AUDIO\`, then approve Android's MediaProjection prompt.
4. Start playback in a capture-compatible app, start hosting, and join from the Speaker phones.
5. Keep the Sync In It Host activity foregrounded during this preview test. Export Host and Speaker diagnostic reports after the run.

You can also choose a local audio file instead of system capture, or use the diagnostic test signal.

## Validation status

- Post-merge Android app/JNI workflow passed: [run 42487998775](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42487998775).
- Post-merge M0 Python 3.11/3.12/3.13 and C++17 workflow passed: [run 42487998642](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42487998642).
- The release workflow rebuilds the APK, runs Android transport checks, verifies the persistent preview signature, and uploads the APK plus SHA-256 file.

**Physical validation is still pending.** No paired Android 12/14 system-capture run has yet demonstrated that eligible app audio is captured on the target Xiaomi devices, that every Speaker reaches READY, that a group ANCHOR is delivered, or that acoustic skew is within a measured bound. Previous 6 November evidence had clock residual RMS around 0.412–0.557 ms, above the unchanged strict 0.25 ms gate; the stable streak remained zero and READY was not reached in that run. A successful CI build is not proof of synchronized acoustic output.

## Known limitations and security

- One Host; up to eight Speakers in the current Android implementation.
- No playlist, queue, seek, pause/resume controls, or receiver-side media selection.
- No background/endurance guarantee, explicit wake-lock lifecycle, automatic drift correction, or microphone-based acoustic calibration.
- DNS-SD discovery exposes the current session token to devices browsing the same LAN. Use a trusted private network; do not use an untrusted public Wi-Fi network.
- Review diagnostic exports before sharing; they may include device model, local network addresses, session IDs, and timing metadata.

This is an engineering prerelease for controlled testing, not a production or acoustically validated release.
