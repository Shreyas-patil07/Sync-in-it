# Android application

The Android workspace contains two Gradle modules:

- `app/`: minimal Android launcher app and JNI bridge into the shared C++ receiver-session state machine.
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

## Host and receiver handshake

The launcher now exposes a minimal one-master/one-receiver flow:

1. Connect both Android phones to the same private Wi-Fi network; client isolation must be disabled.
2. On the master phone, tap **Create session**. The app binds the control listener to TCP port 47800, opens an audio TCP listener on an available port, creates a random 16-byte session token and displays the master's local IPv4 address.
3. On the receiver phone, enter that address and token, then tap **Join master**.
4. The receiver sends HELLO on the control channel. The master validates it through the native C++ `ReceiverSession` and returns the native-encoded WELCOME frame.
5. The receiver opens the separate audio TCP channel advertised in WELCOME and sends AUDIO_HELLO. The master validates the session and receiver IDs and advances to Synchronizing.

The Kotlin transport handles socket lifecycle and TCP frame boundaries. HELLO, WELCOME and AUDIO_HELLO payload codecs remain in the shared C++ core and are exposed through JNI. The app runs a local two-channel TCP/JNI loopback smoke test on launch.

## Current scope and limitations

This milestone demonstrates admission on two TCP channels and the end-to-end UDP four-timestamp exchange using the shared C++ packet validator, request correlator, and clock estimator. It implements the simulator's provisional acquisition gate and keeps READY blocked until real audio buffering is available. It does **not** yet implement audio chunks, PCM buffering/rendering, playback, foreground service, QR onboarding, cryptographic transport protection, or an actual two-phone measurement result. The receiver stays in Synchronizing; the app must not claim synchronized playback.

The session token is an admission credential, not cryptographic transport protection. Use this build only on a trusted local network.
