# Sync In It Android Preview v0.6.4

**Release type:** Engineering preview / diagnostic instrumentation  
**Source:** PR #69 — per-probe Host/Speaker clock-boundary correlation

## What's new

### Per-probe clock-boundary correlation

- Added a shared UDP v1 sequence extractor so Host and Speaker diagnostics can be matched by the same request/response sequence.
- Host now emits one `HOST_UDP_PROBE` record for every accepted time-sync response instead of sampling timing diagnostics only once every 20 responses.
- Host timing fields include:
  - `t2ToT3Us`: elapsed time from Host UDP receive timestamp to the captured `t3`.
  - `t3ToResponseReadyUs`: time from captured `t3` until the encoded response is ready.
  - `responseReadyToSendStartUs`: time between response preparation and the UDP send call.
  - `t3ToSendStartUs` and `receiveToSendStartUs`: combined Host processing intervals.
  - `sendElapsedUs`: duration of the UDP send call.
- Speaker `CLOCK_PROBE` records now include `t4ToAcceptUs`, measuring local processing after the response receive timestamp and before native response acceptance returns.
- Rejected, malformed, timed-out and unreachable Speaker probe diagnostics include the outgoing request sequence where available.
- Added transport unit coverage for unsigned 32-bit little-endian sequence extraction and invalid metadata input.

## What did not change

- No UDP packet layout, protocol version, four-timestamp value or wire behavior changed.
- No change to the clock estimator, sample selection (lowest RTT 25%), 60-second fit window, regression, residual calculation or skew estimation.
- No change to the 0.25 ms residual gate, minimum sample count, selected-span requirement or three-update stability streak.
- No change to probe retry cadence, READY/ANCHOR behavior, PCM16/TCP transport, audio queue capacity or playback scheduling.

## Validation

The following CI workflows passed for the PR #69 source commit:

- Android app and JNI build, transport checks, APK signature verification and artifact upload: [run 42355711409](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42355711409).
- Kotlin socket transport tests: [run 42355711400](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42355711400).
- M0 simulator and native core matrix: [run 42355711433](https://github.com/Shreyas-patil07/Sync-in-it/actions/runs/42355711433).

**Physical-device validation of the new telemetry is still pending.** The preceding v0.6.3-debug run on Android 14 Host / Android 12 Speaker ended with 0.416862 ms residual RMS, stable streak 0 and no READY/ANCHOR. PCM ingress and queue delivery were healthy. This release adds observability; it does not claim to correct clock synchronization or prove synchronized playback.

## Recommended test

Install this same v0.6.4 APK on the Host and Speaker. Use the Android 14 Xiaomi 22101316I as Host and Android 12 Xiaomi M2010J19SI as Speaker for a comparable run. Export diagnostic reports from both devices and correlate `CLOCK_PROBE` with `HOST_UDP_PROBE` by sequence number. Preserve the raw four timestamps and fit results; evaluate any timestamp-boundary adjustment offline before changing production clock behavior.

Diagnostic reports can contain device models, local network addresses, session identifiers and timing metadata. Review reports before sharing them.
