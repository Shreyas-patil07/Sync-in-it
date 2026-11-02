# Android application

The Android workspace contains two Gradle modules:

- `app/`: Android launcher, local-network NSD discovery/advertising, and JNI bridge into the shared C++ receiver-session and clock-estimator APIs.
- `transport/`: pure Kotlin/JVM TCP and UDP socket transport, reusable by the Android app.

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

The app currently targets API 36 and supports arm64-v8a and x86_64 builds. The CMake project compiles the protocol/message codecs and `ReceiverSession` sources directly from `core/`; it intentionally does not invoke the desktop CMake project, which requires Python to generate test vectors and also defines desktop test executables.

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

This milestone demonstrates nearby Android host discovery, admission on two TCP channels and the end-to-end UDP four-timestamp exchange using the shared C++ packet validator, request correlator, and clock estimator. It implements the simulator's provisional acquisition gate and keeps READY blocked until real audio buffering is available. It does **not** yet implement audio chunks, PCM buffering/rendering, playback, foreground service, QR onboarding, cryptographic transport protection, host approval/pairing, or an actual two-phone measurement result. The receiver stays in Synchronizing; the app must not claim synchronized playback.

The session token is advertised in a DNS-SD TXT record so nearby devices can join automatically. It is visible to devices on the LAN and is not cryptographic transport protection. Use this build only on a trusted private network. When raising targetSdk to API 37, add the Android 17 local-network permission/picker path described in the [Android local network permission guide](https://developer.android.com/privacy-and-security/local-network-permission).
