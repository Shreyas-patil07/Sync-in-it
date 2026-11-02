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

## Current scope

This is an app/build/bridge foundation, not a working audio player. There is no audio capture, PCM rendering, foreground playback service, QR onboarding, native audio callback, encryption or two-phone synchronization test yet. The socket module and native session state machine are built but not yet orchestrated into a complete master/receiver flow. The session token remains an admission credential, not cryptographic transport protection.
