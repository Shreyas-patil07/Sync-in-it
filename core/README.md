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

## Scope and limitations

This is a portable timing core, not a complete streaming/audio engine. It does not implement sockets, buffering, codec processing, native audio callbacks, DAC timestamp acquisition or acoustic calibration. Passing synthetic parity vectors demonstrates algorithm agreement, not device performance or acoustic synchronization. The drift controller remains the initial bounded frame-slip policy and needs listening tests before production use.
