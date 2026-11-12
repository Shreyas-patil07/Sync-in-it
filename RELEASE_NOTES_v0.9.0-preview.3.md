# Sync In It Android Cloud Transport Testing Preview v0.9.0-preview.3

**Release type:** Testing APK / engineering preview — not a stable release  
**Architecture:** Render FastAPI + LiveKit Cloud SFU + LiveKit Android SDK  
**Preview source:** `release/v0.9.0-preview.3`

## Changes in this preview

- Adds a Host-controlled **Start synchronized playback** action. The Host waits until all connected Speakers report ready before scheduling the group.
- Adds authenticated backend monotonic-clock sampling and a shared future start timestamp.
- Speakers mute LiveKit's built-in playout, capture decoded LiveKit PCM through an audio sink, and write it through a locally scheduled Android AudioTrack.
- Adds queue-depth-based playback-rate correction to reduce long-run clock drift.
- Keeps LiveKit as the cloud transport. The Host still streams the selected local audio file as 20 ms, 48 kHz stereo PCM chunks.
- Retains the preview package ID `com.syncinit.app.debug` and persistent preview signing key.

## How to test

1. Install this preview on the Host and every Speaker.
2. Select an audio file on the Host and start hosting.
3. Join all Speaker phones and wait until each reports ready.
4. Press **Start synchronized playback** on the Host. All ready Speakers are scheduled against the same backend monotonic-clock target.
5. Test with a track containing sharp transient clicks, then compare the acoustic output of two phones using a third device recording both speakers.

## Validation boundary

A common scheduled start and local PCM playout are now implemented, but this build has not yet been physically measured for acoustic skew across different phone models. Android output pipelines and speaker hardware have different latencies. Do not claim a sub-10-ms acoustic guarantee until a two-phone recording test measures both initial skew and drift over time.

A successful build proves compilation and artifact generation; it does not prove physical-device synchronization. Repeat the click-track test for at least 60 seconds and 10 minutes, and inspect the exported diagnostics for clock-offset refresh, queue correction, and playback-start events.

## Operational limitations

- Render Free SQLite storage is ephemeral; session data is not durable across instance replacement.
- Active WebSocket and LiveKit publisher registrations are process-local. A Render restart interrupts active sessions.
- Multi-instance session/publisher coordination and durable event replay are not implemented.
- Android playback capture is subject to Android source-app capture policy; protected or capture-restricted content may be silent.
- iOS Speaker support and measured 6–10-device capacity are not implemented or validated.

## Installation and signing

The preview channel builds the debug variant with application ID `com.syncinit.app.debug` and requires the persistent preview signing key. Keep that key and package ID to update an existing preview installation.

Install only after the matching workflow succeeds and the APK signature is verified. Do not uninstall an existing preview unless a package/signature change is intended.

## Stable-release decision

**v0.9.0 is not cleared for stable publication.** Physical multi-phone playback, acoustic latency compensation and production signing have not been evidenced. This is a testing preview only.

See [v0.9.0 Stable Release Readiness](docs/Release_Readiness_v0.9.0.md) for the stable-release go/no-go criteria.
