# Sync In It — portable timing core

C++17 reference implementation for the timing functions shared by Android and iOS adapters. The library has no Android, iOS, audio-device or third-party runtime dependency.

## Build and test

Requirements: CMake 3.20+, a C++17 compiler, and Python 3.11+ to generate the C++ view of the shared golden vectors.

```bash
cmake -S core -B build/core -DCMAKE_BUILD_TYPE=Release
cmake --build build/core --config Release
ctest --test-dir build/core -C Release --output-on-failure
```

## Shared parity vectors

`protocol/vectors/m0_timing.json` is the single source of truth for the M0 offset formula, lowest-RTT estimator and selected sample-span diagnostic, timeline rounding, DAC clock mapping and drift-controller decisions.

- Python tests load the JSON file directly.
- The CMake build runs `tools/generate_cpp_vectors.py` to generate a C++ header from the same JSON file.
- C++ tests compare the native implementation against the frozen expected results.

Do not manually edit generated headers. Add or change a test case in the JSON file, verify the expected values against the Python reference, and run both Python and C++ suites.

## Incremental TCP stream framing

`syit::FrameStreamDecoder` consumes arbitrary TCP byte fragments and returns complete `Frame` objects. Each instance retains at most one incomplete frame, validates the length prefix before buffering the body, and supports partial headers, partial payloads and multiple frames in one read. Use one decoder per connection on its network thread, pass bounded socket-read chunks, and keep it out of the real-time audio callback.

## Transport primitives

`syit::BoundedFrameQueue` is a non-blocking, thread-safe FIFO with frame-count and byte budgets. A full queue rejects the newest frame; closed queues reject new work but allow queued items to drain. Create distinct queues for control and audio traffic.

`syit::TimeSyncClient` creates bounded outstanding UDP requests and correlates responses by protocol version, session ID, receiver ID, sequence and echoed t1. It rejects duplicate/unsolicited responses, validates timestamp ordering and RTT, and exposes accepted exchanges as `SyncSample`. `syit::TimeSyncResponder` validates connection-bound identity before constructing a response. Keep each client/responder on its owning network/control thread.

These are transport primitives, not socket implementations. Android/Kotlin and iOS/Swift own socket lifecycle and call these components from network threads. They do not add cryptographic packet authentication or encryption.

## Receiver control-session state

`syit::ReceiverSession` enforces the master-side lifecycle for one receiver connection: HELLO token/version/platform validation, WELCOME assignment, AUDIO_HELLO session/receiver binding, and READY gating against configured clock-residual and buffer thresholds. It then validates the ARMED → PLAYING/PAUSED transitions driven by timeline anchors. DEGRADED receivers must repeat synchronization and readiness checks before they can be armed again.

Each instance is single-owner-thread state. The caller still owns socket I/O, timeout policy, token generation, receiver-capacity admission and cryptographic transport protection. A successful token check is not encryption or protection from a LAN attacker.

## Scope and limitations

This is a portable timing/protocol core, not a complete platform audio engine. It implements wire codecs/framing, time-sync correlation, receiver session state, PCM16 audio-chunk parsing and a bounded sample-indexed native jitter buffer, plus timing/timeline/DAC regression primitives. It does not own operating-system sockets, Android AudioTrack/Oboe or iOS audio callbacks, hardware timestamp polling, production resampling/drift-control integration or acoustic microphone calibration. The Android app integrates the PCM buffer and AudioTrack writer through JNI; the current Host emits only a generated 1 kHz test signal. Passing synthetic parity vectors demonstrates algorithm agreement, not physical-device performance or acoustic synchronization.
