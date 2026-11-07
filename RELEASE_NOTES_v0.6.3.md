# Sync In It Android Preview v0.6.3

## Diagnostic improvements

- Add receiver-side timing telemetry for the interval between capturing the time-sync request timestamp (t1) and invoking UDP send, including the UDP send-call duration.
- Add host-side telemetry for the interval between capturing the response timestamp (t3) and beginning UDP response transmission.
- Preserve the raw four-timestamp exchange and current clock-fit outputs so timestamp-boundary overhead can be assessed from paired device reports.
- Log Speaker-side close triggers and control/audio TCP endpoints alongside audio pipeline failures.
- Distinguish explicit audio queue outcomes: queued, receiver already closed, and queue actually full.

## Queue teardown correction

- A receiver already closed by another teardown path is no longer reported as an audio queue overflow.
- Queue offer and queue clearing are serialized with receiver close to avoid an enqueue/teardown race.
- An overflow diagnostic is emitted only when the bounded audio queue is genuinely full.

## Behavior preserved

- No change to the clock estimator's least-squares fit, lowest-RTT 25% sample selection, 60-second sample window, 0.25 ms residual threshold, 10-second selected-span requirement, or three-update stable streak.
- No change to the UDP/TCP wire protocol, probe retry cadence, PCM format, AudioTrack behavior, or playback scheduling.
- This is a diagnostics and queue-classification update, not a synchronization fix. The previously observed clock acquisition instability and sustained-playback TCP reset are not claimed to be resolved.

## Validation

- Android app/JNI build, Android transport checks, and preview APK signature verification passed for the merged diagnostic changes.
- Native C++ golden vectors, deterministic simulator scenarios, acceptance gates, and shared-Wi-Fi airtime/queue scenarios passed across Python 3.11, 3.12, and 3.13 CI jobs.
- Physical multi-device validation remains necessary to measure timestamp-boundary overhead and attribute the original TCP reset.
- Install the same v0.6.3 APK on both Host and Speaker when collecting the next paired diagnostic run.

## Privacy

Diagnostic reports may contain device model, local IP addresses, session diagnostic IDs, socket endpoints, and timing metadata. Review reports before sharing. Raw audio is not included.
