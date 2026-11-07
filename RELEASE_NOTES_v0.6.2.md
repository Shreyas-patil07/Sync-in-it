# Sync In It Android Preview v0.6.2

## Diagnostics

- Preserve and report the exact native UDP time-sync response rejection status.
- `UDP_RESPONSE_REJECTED` now records the rejection reason, packet size, attempt timing and master endpoint. Reasons include wrong packet type/version, wrong session/receiver, unknown sequence, echoed timestamp mismatch, invalid timestamps and negative RTT.
- Host TCP diagnostics now distinguish control-reader EOF/failure from audio-writer failure.
- Audio writer failure records uptime, time since the last successful write, last write duration, queue counters and TCP endpoints.
- Speaker teardown records the initiating trigger, reason, connection endpoints, uptime and queue state.
- Locally initiated socket teardown is distinguished from a peer-originated control-channel EOF/error to avoid misleading duplicate failure reports.

## Behavior

- No wire-protocol, clock estimator, sample selection, retry cadence, readiness threshold, PCM format, AudioTrack behavior or synchronization policy change.
- Host remains a controller/audio source; the Speaker is the playback endpoint.
- These diagnostics improve failure attribution. They do not fix or claim to fix the previously observed TCP reset or reverse-direction clock instability.

## Validation and testing

- Android/JNI compilation, Android transport checks and preview APK signature verification passed for the diagnostic branch build.
- Run the same APK on both devices.
- Test A: Android 14 Host → Android 12 Speaker. Keep playback active for at least 3 minutes.
- Test B: Android 12 Host → Android 14 Speaker. Allow UDP clock acquisition to run for at least 30 seconds.
- Export the `.txt` diagnostic report from both devices after each test and compare timestamps, UDP rejection reasons and TCP teardown triggers.

## Privacy

Diagnostic reports may contain device model, local IP addresses, session diagnostic IDs, socket endpoints and timing metadata. Review before sharing. Raw audio is not included.
