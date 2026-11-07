# Sync In It Android Preview v0.6.5

**Release type:** Engineering pre-release  
**Validation status:** Software CI passed; physical-device validation of the timestamp experiment is pending.

## What's changed

This release carries the UDP response T3 send-boundary experiment from PR #71.

- The Host now creates the normal v1 UDP response with a provisional T3 equal to T2. The transport replaces the response's existing T3 field immediately before `DatagramSocket.send()` using `System.nanoTime()`.
- The 38-byte v1 packet format is unchanged. Packet type, sequence, session ID, receiver ID, T1 and T2 retain their existing wire positions and values.
- Host per-probe diagnostics now report receive-to-T3, response-ready-to-T3 and send-call duration.
- Debug builds use a 90-second Host readiness observation timeout to allow a longer clock-acquisition run. Release builds retain the existing 45-second timeout.
- Diagnostic exports show the installed app version at export time and retain the original log-initialization metadata separately.
- The default Android preview version name is updated to 0.6.5.

## What has not changed

- No clock estimator, regression, sample-selection policy, 60-second fit window, minimum sample count/span, stable-streak rule or clock residual threshold was changed.
- The strict residual requirement remains below 0.25 ms, with three successive stable estimator results.
- No T1 capture or native pending-request behavior changed.
- No READY/ANCHOR, PCM format, TCP audio path, retry cadence, queue limit or playback behavior changed.
- The longer readiness timeout applies to debug builds only.

## Evidence and validation

The preceding v0.6.4-debug role-reversal test failed the clock-acquisition gate in both directions:

| Direction | Selected samples | Selected span | RTT median | Residual RMS |
|---|---:|---:|---:|---:|
| Android 14 Host → Android 12 Speaker | 27 / 107 | 32.036 s | 4.9288 ms | 0.324341 ms |
| Android 12 Host → Android 14 Speaker | 27 / 108 | 43.815 s | 5.6278 ms | 0.500333 ms |

Both runs had 20 seconds of contiguous PCM and zero missing frames, but neither reached READY or ANCHOR.

PR #71 software validation passed:
- Android app and JNI build, transport checks and APK signature verification.
- Kotlin socket transport integration tests.
- M0 simulator and native core matrix (Python 3.11, 3.12 and 3.13).

The timestamp experiment itself has **not yet been validated on the two physical phones**. A successful build is not proof that clock residuals improve or that synchronized acoustic playback works.

## Required next test

Install this preview on both phones and run at least 75 seconds per direction:

1. Xiaomi 22101316I (Android 14) as Host; Xiaomi M2010J19SI (Android 12) as Speaker.
2. Reverse the Host and Speaker roles.
3. Export both diagnostic reports from each run.
4. Correlate Host and Speaker probe records by sequence; compare residual RMS and held-out prediction error.
5. Confirm whether the stable streak reaches three and whether READY/ANCHOR are reached.

Do not change the 0.25 ms gate or estimator policy based on a single run.

## Limitations

- T3 is captured immediately before the application invokes the UDP socket send operation; it is not a hardware or radio transmit timestamp.
- Android scheduling, kernel receive wake-up timing and network path asymmetry remain possible sources of measurement noise.
- Physical playback and acoustic synchronization remain unverified.
- Diagnostic reports may include device models, local network addresses, session identifiers and timing metadata. Review them before sharing.
