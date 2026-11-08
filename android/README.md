# Android application

The Android workspace contains two Gradle modules:

- `app/`: Android launcher, local-network NSD discovery/advertising, and JNI bridge into the shared C++ receiver-session and clock-estimator APIs.
- `transport/`: pure Kotlin/JVM TCP and UDP socket transport, reusable by the Android app.

## Current validation status — 8 November 2026

The Android source implements multi-Speaker Host admission (up to eight), separate control/audio TCP channels, UDP clock acquisition, local-file and consented Android app-playback sources, PCM16 fan-out, rolling pre-ANCHOR buffering, retryable READY, a shared future ANCHOR path and M3a AudioTrack timestamp diagnostics. System playback capture uses MediaProjection plus a foreground service on Android 10+ and is limited by source-app capture policy.

The post-PR #55 Speaker-side run on 6 November showed PCM ingress mostly around 49–53 frames/sec (reported interval range 44.1–56.2), `missingFrames=0`, and contiguous buffering up to approximately 19,940 ms. This is encouraging evidence that the earlier receive-path slowdown was not reproduced in the captured interval. It is not a complete end-to-end pass: clock residual RMS remained approximately 0.412–0.557 ms, above the unchanged strict <0.25 ms threshold; stable streak stayed at zero and READY was not demonstrated. No matching Host log, ANCHOR event or acoustic measurement was supplied, so Host writer/queue health and physical synchronization remain unverified.

The earlier pre-PR #55 run still records the separate Host-side 500 ms audio-queue overflow. PR #55's snapshot caching and ingress/slow-write diagnostics remain present. The signed [v0.6.9 Android engineering prerelease](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.6.9) includes the v0.6.8 local-file and consented system-playback sources plus PR #78's safe Host source switching, one-second PCM signal-level diagnostics and offline clock-probe comparison. The live clock estimator and strict readiness gate remain unchanged. Use the persistent preview signing key for update installs; read [v0.6.9 release notes](../RELEASE_NOTES_v0.6.9.md) before testing.


See [Test Results](../docs/Test_Results.md) for the complete dated test record, measured values, verdict and next actions. See [Current Implementation Status](../docs/11_Current_Implementation_Status.md) for the overall engineering state.

## Diagnostic text export

The Android app keeps a rolling diagnostic history in app-private storage at `files/diagnostics/sync-in-it-diagnostics.txt` (bounded to 4 MB). The on-screen debugger still shows only the latest 240 events; the saved report contains the retained file history across activity recreation and app restarts.

Use **Network debugger → Save diagnostic report (.txt)**. Android's document picker lets the user save the report in Downloads or another location without granting broad storage permissions. The report includes app/device metadata and timestamped events such as `CLOCK_PROBE` and `CLOCK_FIT`; it does not include audio. Token, password, secret and API-key assignments are redacted. Local network addresses, device model, session diagnostic IDs and timing data may still be present, so review the report before sharing it.

## Toolchain

Pinned versions:

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin 2.2.10
- JDK 17
- Android SDK 36
- Android NDK 30.0.16248370
- CMake 3.22.1

Open the `android/` directory in Android Studio or run from the repository root:

```bash
gradle -p android :app:assembleDebug
gradle -p android :transport:check
```

The app currently targets API 36 and supports arm64-v8a and x86_64 builds. The CMake project compiles the shared PCM audio-chunk codec, bounded jitter buffer, protocol/message codecs and `ReceiverSession` sources directly from `core/`; it intentionally does not invoke the desktop CMake project, which requires Python to generate test vectors and also defines desktop test executables.

## Updateable preview APK signing

Android only installs an APK over an existing app when the **application ID is identical**, the **signing certificate is identical**, and the new APK's **versionCode is higher**. Preview builds intentionally keep the existing `com.syncinit.app.debug` application ID by retaining `applicationIdSuffix = ".debug"`.

GitHub Actions must use one persistent keystore for every preview APK. Do not commit the keystore or passwords. The workflows read these repository Actions secrets:

- `SYNCINIT_PREVIEW_KEYSTORE_BASE64`: Base64-encoded JKS file.
- `SYNCINIT_PREVIEW_KEYSTORE_PASSWORD`: the JKS password and the key password.

Create the keystore once on a trusted computer (PowerShell on Windows):

```powershell
keytool -genkeypair -v -keystore syncinit-preview.jks -storetype JKS -alias syncinit-preview -keyalg RSA -keysize 3072 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\syncinit-preview.jks))) | Set-Clipboard
```

When `keytool` asks for the key password, press Enter to reuse the keystore password. Back up `syncinit-preview.jks` securely outside the repository; losing it means future APKs cannot update existing installs. Then open **GitHub → repository Settings → Secrets and variables → Actions → New repository secret** and add:

1. `SYNCINIT_PREVIEW_KEYSTORE_BASE64` — paste the clipboard contents.
2. `SYNCINIT_PREVIEW_KEYSTORE_PASSWORD` — enter the password used when creating the keystore.

After both secrets exist, run the **Android app and JNI** workflow again from the Actions tab. It will sign the APK with the persistent key and publish the artifact. The release workflow also requires the same key. Each CI build receives a new timestamp-based `versionCode`, so a later APK can replace an earlier install.

**One-time migration warning:** older APKs were signed with disposable GitHub-runner debug keys. Their private keys were not retained, so a persistent-key APK cannot be guaranteed to update those installs. Expect to uninstall the currently installed preview once, install the first persistent-key build, and then install every later preview directly over it without uninstalling. The package ID remains the same after this migration.

## Coordinated playback preview

The current Android preview does not start AudioTrack as soon as an individual Speaker reaches its prebuffer threshold. Each receiver sends READY after clock acquisition is stable and at least 3 seconds of contiguous PCM is buffered, then retries READY every second until ANCHOR arrives. The Host accepts repeated READY reports idempotently, admits a cohort for 15 seconds after the first successful admission, and waits up to 45 seconds for readiness before excluding non-ready peers. It then sends one shared ANCHOR start message with a future Host monotonic timestamp and a common sample index.

Before ANCHOR, the sample-indexed PCM buffer rolls its pre-roll cursor forward when full, retaining the newest contiguous 20-second window while clock acquisition or admission is delayed. Applying ANCHOR freezes that rolling behavior: the receiver maps the timestamp to its local monotonic clock, seeks to the common sample index, primes AudioTrack, and schedules `AudioTrack.play()`. After playback starts, out-of-window audio remains a hard error rather than silently skipping samples. The bounded buffer uses approximately 18.3 MiB per receiver for its PCM and ring-slot arrays at 48 kHz stereo, excluding object/runtime overhead.

This is software-level scheduling on Android monotonic clocks. A call to `AudioTrack.play()` from a worker thread is not a hardware presentation timestamp. Do not describe this milestone as acoustically synchronized: DAC output timestamp mapping, device-latency compensation, drift correction and microphone-based acoustic validation are still required. The main-branch preview now offers three Host sources: a selected local audio file, eligible Android app playback capture, and the diagnostic 1 kHz test signal. Local files are decoded with MediaExtractor/MediaCodec and converted to 48 kHz stereo PCM16. System playback capture requires RECORD_AUDIO permission and fresh MediaProjection consent, then reads supported media/game/unknown playback usages into a bounded 20-second rolling PCM buffer. Both media sources use the existing TCP fan-out, strict clock/BUFFER READY cohort gate, two-second pre-roll and common ANCHOR. Local files loop at EOF. Protected or capture-restricted app streams may be silent. Neither source has yet passed physical Android 12/14 two-phone synchronization or acoustic-skew validation.

## Native session bridge

`NativeCore` loads `libsyncinit_jni.so`. `NativeReceiverSession` provides a synchronized Kotlin facade around the C++ `syit::ReceiverSession` API. The current bridge supports:

- Create/close a receiver session.
- Validate HELLO protocol version, 16-byte token and platform.
- Retrieve the encoded WELCOME TCP frame.
- Validate AUDIO_HELLO session/receiver IDs.
- Submit READY metrics and gate the state transition.
- Arm, process playback anchors, mark degraded and begin resynchronization.
- Read the native session state.

The native handle registry protects lifetime across JNI calls, and each session is serialized by its own mutex. Do not expose raw native handles to UI code. Keep JNI calls and socket I/O off the audio callback.

## Nearby host discovery and join

The launcher uses Android `NsdManager` (DNS-SD/mDNS) with service type `_syncinit._tcp.`:

1. Connect both phones to the same private Wi-Fi network. The access point must allow client-to-client traffic and mDNS/multicast; guest networks with client isolation commonly block this.
2. On the master phone, tap **Start hosting**. The app binds TCP control port 47800, opens the separate audio listener, creates a random session token, and advertises a named Sync In It service on the LAN.
3. On the receiver phone, open the app and use **Nearby sessions**. Discovered hosts appear as rows. Tap a host to join; the app resolves its address/port and performs the existing HELLO → WELCOME → AUDIO_HELLO handshake automatically.
4. The receiver then starts the existing UDP clock-acquisition loop. No IP address or token needs to be typed or copied manually.

The Android NSD browser holds a Wi-Fi multicast lock only while scanning, for compatibility with older Android Wi-Fi stacks. The app declares `CHANGE_WIFI_MULTICAST_STATE` for this purpose. Discovery is local-network-only; it is not internet-wide discovery and does not use a cloud rendezvous service.

**Prototype security limitation:** the 16-byte session token is currently included in the DNS-SD TXT record so a receiver can join with one tap. Any device on the same LAN that browses the service can read that token. The token is only an admission credential, not encryption or peer authentication. Use a trusted private Wi-Fi network; replace this with host approval or a short-lived pairing exchange before a production/public release.

## Current scope and limitations

The Android preview streams a generated 1 kHz timing-click test track from the Host over a separate audio TCP channel. Each Speaker validates AUDIO_CHUNK frames through the shared C++ codec, inserts PCM16 samples into the native bounded jitter buffer, and writes buffered samples through a dedicated AudioTrack writer thread. The Host admits up to eight Speakers during a 15-second admission window and sends one shared future ANCHOR timestamp and sample index after the window closes and every admitted Speaker is READY. Before the ANCHOR, each Speaker's 20-second sample-indexed PCM ring buffer rolls forward to retain the newest contiguous pre-roll; applying the ANCHOR switches the buffer to strict playback mode, where it never silently skips samples. Each Speaker maps the Host timestamp to its local monotonic clock, seeks to the shared sample index, primes AudioTrack and schedules playback.

M3 output-clock measurement is now in progress. A helper thread polls AudioTrack presentation timestamps, unwraps the API's 32-bit frame counter and feeds a rolling 30-second regression in the shared C++ core. It warms up at 1-second intervals, then polls at 10-second intervals after timestamp advancement is observed. The diagnostic reports estimated output-rate error, regression residual, sample span and rejected samples. It does **not** yet feed this mapping into a drift controller or change playback timing. Manual per-device output offsets, callback-safe mapping publication, drift correction, acoustic calibration and physical multi-phone measurement remain pending.

AudioTimestamp is a best-effort estimate of a frame's position/time in the output pipeline. The timestamp may refer to a frame already presented or committed to be presented, and cannot account for delays unknown to Android; it is not a microphone measurement of acoustic arrival. Routes may not provide timestamps. The generated 1 kHz source remains an integration test signal, not a user media source.

The session token is advertised in a DNS-SD TXT record so nearby devices can join automatically. It is visible to devices on the LAN and is not cryptographic transport protection. Use this build only on a trusted private network. When raising targetSdk to API 37, add the Android 17 local-network permission/picker path described in the [Android local network permission guide](https://developer.android.com/privacy-and-security/local-network-permission).


## Acoustic measurement build and long-test limits

The receiver-specific marker is generated in `AcousticTestMarker.kt` and overlaid after the PCM buffer read, immediately before AudioTrack writes. It is included in the published v0.5.8-preview.1 APK. The analyzer has only been validated against synthetic recordings so far; real PHONE-SKEW-001 measurement remains pending.

The current Android output path remains `AudioTrack.MODE_STREAM` with a dedicated writer thread and a scheduled `AudioTrack.play()` call. Oboe/AAudio callbacks are not implemented. See [ADR-019](../docs/ADR/Architecture_Decisions.md).

No foreground playback service or explicit CPU/Wi-Fi wake-lock lifecycle exists. Keep the first approximately two-minute skew test foregrounded and screen-on. Do not run endurance, background, screen-off or train trials until ADR-022 is implemented and validated.


## Android system-audio capture

The Host can request Android playback capture from other apps on Android 10 (API 29) and newer. The user must grant the runtime `RECORD_AUDIO` permission and approve a fresh MediaProjection consent prompt for each capture session. Capture runs in a foreground service declared with the `mediaProjection` service type; stopping the Sync In It session stops capture.

Captured audio is read as 48 kHz, stereo, PCM16 and enters the same sample-indexed PCM producer as local-file playback. A bounded 20-second rolling buffer allows the Host to select a recent two-second pre-roll only after all admitted Speakers pass the existing clock-stability and buffer-readiness checks. The existing common ANCHOR and TCP fan-out then coordinate playback; the estimator thresholds and wire protocol are unchanged.

**Capture is not guaranteed for every app.** Android playback capture is limited to supported media/game/unknown usages and is subject to the source app's capture policy and manifest opt-in. DRM/protected or capture-blocked playback may be silent. Android 10+ and matching user-profile requirements apply. This is playback capture, not microphone recording or a universal system mixer tap.
