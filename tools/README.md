<!-- SPDX-License-Identifier: CC-BY-4.0 -->
# Measurement tools

This toolchain measures **acoustic arrival skew**, not just clock-estimator residual or the time at which the app calls `AudioTrack.play()`.

The current Android test stream keeps the existing 1 kHz timing click and overlays a low-level receiver-specific 20 ms marker at every 48,000-sample boundary. The marker frequency is derived from the Host-assigned receiver ID (Speaker 1 = 1,500 Hz, Speaker 2 = 2,250 Hz, etc.). Different frequencies let a mono laptop recording distinguish two phones even when their clicks overlap in time.

## Generate reference tracks

From the repository root:

```bash
python -m tools.clicktrack.generate --output-dir recordings/reference --receiver-ids 1 2 --duration-seconds 120
```

This creates one mono reference WAV per receiver and a manifest. These references are useful for checking the recording and marker identity; the analyzer detects the marker waveform directly and does not need the files as input.

## Record a two-phone run

1. Install the same diagnostic build on one Host and two Android Speakers. The Host assigns the first two Speakers receiver IDs 1 and 2; confirm those IDs in the Host log.
2. Keep all three devices on one trusted private Wi-Fi network. Put the two Speaker phones about 10 cm from the laptop microphone and at equal distance. Keep their orientation and volume fixed.
3. Use the phone speakers, not Bluetooth. Set each phone to a fixed, comfortable volume. Avoid clipping at the laptop input.
4. Keep the app visible and the screens awake for this short measurement run. The current app does not yet have a foreground playback service or wake-lock lifecycle. **Do not run long/endurance tests with this build.**
5. Start a stereo or mono 48 kHz recording in Audacity, start the Host, join the two Speakers, and let the test stream play for about 120 seconds after the group actually reaches PLAYING.
6. Export the recording as uncompressed WAV, signed 16-bit PCM, 48,000 Hz. Do not normalize, time-stretch, denoise or resample the export.

The run is valid only if both Speakers reached the common start ANCHOR and the diagnostic markers are present. A READY timeout or failed group start is a transport/readiness failure, not a skew measurement. Record the Host/Speaker logs and commit SHA beside the WAV.

## Analyze

```bash
python -m tools.analyze.analyze --input recordings/run-001.wav --receiver-ids 1 2 --output-dir test-results/run-001
```

The report gives signed mean/median skew (Speaker B minus Speaker A), p95 and maximum absolute skew, matched click count, and a linear drift-slope estimate in ppm. JSON and per-event CSV are written to the output directory.

**Interpretation:** a positive skew means Speaker B's marker arrived later than Speaker A's. The p95 absolute skew is the main decision metric. The marker is a diagnostic tone, not the final music source. Microphone placement, speaker-to-microphone distance, room reflections, OS audio routing and the recorder's sample clock all affect the result. Keep the geometry fixed and include measurement uncertainty in any report.

## Unit tests

```bash
python -m unittest discover -s tools/tests -v
```

The synthetic tests validate the detector against known 5–8 ms offsets. They verify the analysis implementation only; they are not physical-device evidence.
