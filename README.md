# Sync In It

<p align="center">
  <img src="docs/images/sync_in_it.png" alt="Sync In It logo" width="260" />
</p>

> Turn 6-10 phones (Android + iOS) into one synchronized speaker group, fully offline.

**Project website:** [Sync In It](./frontend) — project overview and pre-release downloads page.

**Sync In It** is an offline, master-clock-based audio distribution system. One Android phone plays the role of audio source and master clock. Other Android or iOS phones play the same audio in sync over a private local Wi-Fi network. The current product target is 6–10 phones in total; exact receiver capacity remains an open design decision. It is designed for a single train coach (for example a Bandra-Delhi journey), with no internet, no cloud, and no dedicated speakers.

*Sync In It is a working name. Rename it freely.*

---

## Status

**Current engineering preview: multi-receiver Android networking and PCM fan-out are implemented; physical validation is pending.**

The Android Host advertises sessions over DNS-SD/mDNS. Speaker phones can discover and join over a local Wi-Fi network or phone hotspot; internet access is not required when the hotspot allows client-to-client traffic. The receiver explicitly selects the Wi-Fi network routing to the discovered Host and binds its control TCP, audio TCP and UDP time-sync sockets to that same network.

The Host admits up to **8 Speakers** in one session. Each Speaker receives a unique receiver ID and independent native readiness/clock state. Control/leave handling and UDP time-sync validation are per Speaker. Audio is generated once as a 48 kHz stereo PCM16 timing-click stream and fanned out with identical sample indexes through bounded per-Speaker send queues; one slow Speaker cannot block the others.

The current Android audio source is a generated 1 kHz timing-click test signal, not user-selected music or system audio. Each receiver begins local AudioTrack playback after its own prebuffer and clock acquisition. The group does **not** yet start on a common future T0; DAC output timestamp mapping, drift correction and measured acoustic alignment remain pending. CI validates builds and protocol/state-machine behavior, but physical hotspot, multi-phone and acoustic tests are still required.

The Python reference simulator, portable C++17 timing core and shared Python/C++ golden vectors remain available. The Kotlin/JVM TCP/UDP transport is a separate Gradle module. The current product target is 6–10 phones in total; the Android preview's initial admission cap is 8 Speakers.

| Milestone | Description | State |
|---|---|---|
| M0 | Python simulator for time sync, drift and shared Wi-Fi airtime | In progress |
| M1 | Android LAN discovery, TCP admission and UDP clock acquisition | In progress; physical-device validation pending |
| M2a | PCM16 audio codec and bounded sample-indexed jitter-buffer core | Complete |
| M2b | Android PCM16 TCP transport and AudioTrack test playback | Complete |
| M2c | Multi-Speaker admission, independent receiver state and shared PCM fan-out (up to 8) | Implemented; physical validation pending |
| M3 | Output-timestamp mapping and manual offsets | Planned |
| M4 | Drift controller and 30-minute endurance run | Planned |
| M5 | Hotspot resilience, receiver reconnect and measured 4+ Speaker testing | Planned |
| M6 | Opus codec and user-selected/system audio source | Planned |
| M7 | iOS receiver | Planned |
| M8 | Acoustic calibration | Planned |
| M9 | 6–10 Speaker scaling and train trial | Planned |

### Run the M0 simulator

Requires Python 3.11 or newer. No runtime dependencies are required.

```bash
cd sim
python -m pip install -e .
python -m unittest discover -s tests -v
sync-in-it-sim --duration 60 --seed 7
sync-in-it-sim --lan --duration 60 --seed 7
```

The `--lan` mode compares fixed and adaptive clock probing while audio streams share one modeled Wi-Fi channel. It reports airtime utilization, audio p95 latency, group-delay misses, control RTT, queue waits, retries and drops. These outputs are synthetic, not router/device measurements.

M0 outputs are simulated, not measurements from Android devices. The default scenario run reports fixed-versus-adaptive acquisition comparisons for +30 ppm drift, high jitter and random packet loss. See [`sim/README.md`](sim/README.md) for gate definitions, probe overhead and limitations.

### Build and test the portable C++17 timing core

Requires CMake 3.20+, a C++17 compiler and Python 3.11+.

```bash
cmake -S core -B build/core -DCMAKE_BUILD_TYPE=Release
cmake --build build/core --config Release --parallel 2
ctest --test-dir build/core -C Release --output-on-failure
```

The build generates its C++ test data from [`protocol/vectors/m0_timing.json`](protocol/vectors/m0_timing.json). The Python test suite loads that same JSON directly. Passing parity tests proves algorithm agreement on the supplied vectors; it does not prove physical-device or acoustic synchronization.

### Build and test the Kotlin/JVM socket transport

Requires JDK 17 and Gradle 8.10.2 or newer.

```bash
gradle -p android/transport check
```

This compiles the JVM-compatible TCP/UDP adapter and runs loopback integration tests. It does not build an Android APK.


### Build the Android application shell

Requires JDK 17, Android SDK 36, NDK 30.0.16248370, CMake 3.22.1 and Gradle 8.13.

Run `gradle -p android :app:assembleDebug` to build the debug APK and `gradle -p android :transport:check` to run TCP/UDP transport checks.

The Android workspace and JNI boundary are documented in [android/README.md](android/README.md). The Android CI workflow publishes the debug APK as a short-lived artifact. Launching the debug app runs a small native session smoke test. It validates JNI/session wiring only; it does not connect phones or play audio.

For in-place APK updates, preview builds must be signed with the same persistent keystore and use a higher `versionCode`. Configure the two GitHub Actions secrets documented in [android/README.md](android/README.md) before downloading the updateable preview artifact or publishing a release. The package ID remains `com.syncinit.app.debug` for preview builds. Existing CI APKs signed with disposable runner keys may require one final uninstall/reinstall to migrate to the persistent signing key; future builds signed with that key install over the existing app.

---

## Why this is hard

Phones that receive identical audio still play it at different times. Each phone has its own audio clock, operating-system scheduling, output pipeline, and speaker. Starting playback when packets arrive produces echoes and drift.

Sync In It does not play audio on arrival. It gives every sample a target presentation time on a shared timeline and makes each phone hit that time. A deliberate group delay of 2-10 seconds provides the preparation time.

---

## How it works

```mermaid
flowchart LR
    S[Android master<br/>source + clock + controller] -- audio chunks TCP --> R1[Receiver 1]
    S -- audio chunks TCP --> R2[Receiver 2]
    S -- audio chunks TCP --> RN[Receiver N]
    R1 <-- time sync UDP --> S
    R2 <-- time sync UDP --> S
    RN <-- time sync UDP --> S
    R1 -- status reports --> S
    S -- START T0, resync --> R1
```

Seven layers, each using the best-known technique:

| Layer | Job | Technique |
|---|---|---|
| L1 Transport | Deliver audio early | Timestamped chunks, deep buffer, TCP (Snapcast-style) |
| L2 Clock distribution | Shared notion of time | Lowest-RTT NTP-style exchange; optional RBS refinement |
| L3 Group playout control | One common deadline | IDMS-style reports and common target playout point (RFC 7272) |
| L4 DAC clock mapping | Master time to real output time | Platform output timestamps plus skew fit |
| L5 Drift correction | Hold alignment for hours | Sample drop/duplicate, gentle rate adjustment |
| L6 Acoustic alignment | Align sound in the air | Microphone chirp calibration; optional ultrasonic beacon |
| L7 Resilience | Survive a train | Quarantine, rejoin, adaptive delay |

### The core idea in four lines

```
M = local + theta                       # master time from local clock
T(n) = T0 + n / 48000                   # when stream sample n must be heard
n_expected(p) = ((M(p) + c_i) - T0) * 48000   # sample that frame p must carry
error e = n_expected - n_actual         # drift controller drives e to zero
```

`T0` is one start deadline for the whole group, set by the slowest receiver. `c_i` is a per-device compensation from calibration.

---

## Features (target)

- Fully offline operation over a local router
- Android master, Android and iOS receivers, one shared protocol
- Configurable group delay up to 10 seconds
- Continuous clock-offset and clock-skew estimation
- Automatic drift correction by sample drop/duplicate
- Receiver reconnect and resynchronization without disturbing others
- Manual per-device offset slider
- Microphone-based acoustic calibration at one listening position
- Per-device telemetry logs for debugging

---

## Synchronization targets

These are goals to be validated by measurement, not guarantees.

| Tier | Skew between phones | Meaning |
|---|---|---|
| T1 | 20 ms or less | Heard as a single sound source by most listeners |
| T2 | 10 ms or less | Solid result for mixed phones over Wi-Fi |
| T3 | 5 ms or less at the listening position | Stretch goal, needs calibration |

Results are reported as mean, maximum and 95th-percentile error. A literal zero-millisecond difference across different phones is not physically guaranteed.

---

## What you need

**Hardware**

- 1 Android phone for the master (Android 10+ for system audio capture)
- 2-10 receiver phones (Android and/or iOS)
- 1 portable dual-band Wi-Fi router (a phone hotspot works for early prototypes only)
- For iOS receivers: a Mac, Xcode, and physical iPhones
- For measurement: a laptop or third phone as a recorder

**Network**

- 5 GHz preferred, 2.4 GHz fallback
- Client isolation must be **off**
- No internet connection needed

---

## Quickstart *(planned)*

> These steps describe the intended workflow. They will work once milestones M1-M5 land.

1. **Set up the network.** Power the router, disable client isolation, connect all phones to it.
2. **Install the apps.** Install the master app on the Android source phone and the receiver app on every receiver.
3. **Start a session.** On the master, tap *Start session*. A QR code appears.
4. **Join receivers.** Scan the QR code on each receiver.
5. **Wait for READY.** The master shows each receiver's buffer and offset status.
6. **Play.** Choose a local audio file (or, in a later milestone, capture system audio) and press *Play*. All phones start at the common deadline.
7. **Calibrate (optional).** Place the master's microphone at your listening position and run *Calibrate*.

---

## Planned repository layout

```
Sync In It/
  core/            # portable C++: time estimator, timeline, drift controller, group deadline
  android/
    master/        # Kotlin app: session, capture, controller
    receiver/      # Kotlin + Oboe receiver
    app/           # Android launcher shell and C++ JNI bridge
    transport/     # Pure Kotlin/JVM TCP and UDP socket adapters
  ios/
    receiver/      # Swift + AVAudioEngine receiver
  protocol/        # wire format spec and test vectors
  sim/             # Python simulator for clocks, jitter, loss
  tools/
    analyze/       # click-track recording analysis (cross-correlation)
    logs/          # telemetry plotting
  docs/            # primary product and engineering documentation
  docs_original/   # original source design documents
```

The `core/` library is shared by Android and iOS so the timing logic is identical on every platform.

---

## Protocol summary

| Channel | Transport | Content |
|---|---|---|
| Control | TCP (`TCP_NODELAY`) | HELLO, READY, START, PAUSE, STATS, RESYNC |
| Audio | TCP | Timestamped audio chunks |
| Time sync | UDP | Ping/pong with four timestamps |

Audio chunk header:

```
magic(2) version(1) type(1) session(4) seq(4)
sampleIndex(8) frames(2) codec(1) payloadLen(2) payload
```

Chunks are placed in the jitter buffer by `sampleIndex`, never by arrival order. All fields are little-endian. The protocol is platform-neutral so Android and iOS receivers behave identically on the wire. The normative protocol and data-schema draft is documented in [`docs/06_Protocol_and_Data_Schema.md`](docs/06_Protocol_and_Data_Schema.md); `protocol/` will hold implementation-ready definitions and golden test vectors.

---

## Clock synchronization in brief

```
RTT   = (t4 - t1) - (t3 - t2)
theta = ((t2 - t1) + (t3 - t4)) / 2        # master = local + theta
```

- Send bursts of about 20 requests at join, then periodic requests.
- Keep the lowest-RTT quarter of recent samples and take the median offset.
- Fit offset against local time to estimate skew in ppm.
- Optional experimental refinement: Reference Broadcast Synchronization, adopted only if measurements show it beats the baseline on phones.

---

## Drift correction in brief

| Error `e` | Action |
|---|---|
| Under about 0.1 ms | Do nothing (dead-band) |
| 0.1 ms to about 5 ms | Drop or duplicate single samples, at most about one per 10 ms |
| Over about 5 ms | Hard resync: jump the read pointer and log it |

Thresholds are starting values to be tuned by listening and measurement.

---

## Configuration *(planned)*

| Setting | Default | Notes |
|---|---|---|
| Group delay `D` | 3 s | Range 2-10 s, changed only at a coordinated boundary |
| Sample rate | 48 kHz | Fixed in v1 |
| Chunk size | 20 ms | |
| Jitter buffer | 3-5 s | |
| Time-sync interval | few seconds | Burst at join |
| Manual offset | 0 ms | Range about plus/minus 50 ms, 0.1 ms steps |
| Codec | PCM | Opus planned |

---

## Measuring sync

1. Generate a click-track with a click each second at known sample positions.
2. Put all phones about 10 cm from one recording microphone, at equal distance.
3. Play from all phones and record.
4. Cross-correlate or peak-detect per click, giving each phone a distinct click pitch if you use one mono recording.
5. Report mean, maximum and 95th-percentile arrival differences.

Per-device telemetry (CSV): offset, RTT, skew in ppm, buffer fill, error `e`, sample corrections, hard resyncs, underruns.

Test matrix: offset quality, acoustic skew, 60-minute endurance, scaling to 2/4/6/10 receivers, fault injection (disconnects, router reboot, congestion), and a real train trial.

---

## Resilience behavior

| Event | Behavior |
|---|---|
| Receiver disconnects | Others continue on the existing timeline |
| Receiver rejoins | Re-estimates offset, enters at a future coordinated boundary |
| Cannot catch up within `D` | Marked unavailable and kept silent |
| RTT or loss rises | Controller raises `D` at a coordinated boundary |
| Buffer underrun | Silence fill, counted, resync if repeated |
| Master loses router | Receivers play buffered audio, then stop cleanly |

On the master, use a foreground service, a partial wake lock, a high-performance Wi-Fi lock, and a battery-optimization exemption. On iOS, enable background audio and configure the audio session correctly. Validate all of this on the target phones.

---

## Known limitations

- Android can only capture audio from apps that allow it. Protected or restricted media may be unavailable. A local-file fallback is planned.
- Android and iOS use different native audio pipelines, so receivers are implemented separately on top of a shared core.
- Phone output latency and speaker hardware add device-specific delay that only calibration can reduce.
- A common digital deadline does not guarantee identical acoustic arrival. Calibration is valid only at one listening position and for the current phone placement. A train moves, so re-calibration will be needed.
- A long Wi-Fi outage can exceed the buffer and force a controlled resync.
- No published system I know of combines all these layers on mixed phones over Wi-Fi, so the integration is unproven. Microsecond-level numbers in the cited papers come from controlled hardware, not phone Wi-Fi stacks or phone speakers.

---

## Roadmap

1. Android-only demo: master plus 2-3 receivers with scheduled playback (M0-M5)
2. Opus and system audio capture (M6)
3. iOS receiver (M7)
4. Acoustic calibration (M8)
5. Scale to 6-10 receivers and train trial (M9)
6. Stretch: RBS refinement, mutual-microphone calibration, ultrasonic drift beacon

Rough effort for one person part-time (an estimate): about 4-5 weeks to a working Android-only demonstration.

---

## Contributing

The project is at the design stage. Useful contributions right now: review of the design document, measurement ideas, and phone-specific timing data. Once code exists, open an issue before large changes and include logs and device models with any sync bug report.

---

## License

| Material | License |
|---|---|
| Source code (core, apps, simulator, tools, frontend) | [Apache License 2.0](LICENSE) |
| Documentation, specifications and reports | [Creative Commons Attribution 4.0](LICENSE-DOCS) |
| Project name, logo and brand images | All rights reserved |
| Third-party components | Their own licenses, listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) |

See [LICENSING.md](LICENSING.md) for the full map, contribution terms and the rules for adding dependencies. Snapcast is an architectural reference only, and no Snapcast code is used in this repository.

---

## References

- Snapcast, synchronous multiroom audio: https://github.com/rusitschka/snapcast
- Elson, Girod, Estrin, *Fine-Grained Network Time Synchronization Using Reference Broadcasts* (OSDI 2002): https://usenix.org/conference/osdi-02/fine-grained-network-time-synchronization-using-reference-broadcasts
- RFC 7272, Inter-Destination Media Synchronization (IDMS) Using RTCP: https://www.rfc-editor.org/info/rfc7272
- Adaptive and standardized RTCP-based IDMS (thesis): https://ir.cwi.nl/pub/23633
- Lazik et al., *Ultrasonic Time Synchronization and Ranging on Smartphones*: https://ptolemy.berkeley.edu/projects/terraswarm/pubs/492.html
- Android CDD change on output timestamp accuracy: https://android.googlesource.com/platform/compatibility/cdd/+/f845c6c%5E%21/
- Oboe `AudioStream` (getTimestamp, latency): https://android.googlesource.com/platform/external/oboe/+/10cd299e/include/oboe/AudioStream.h
- Patent, multi-device audio playback correction: https://patents.google.com/patent/US12401947
- Patent application, microphone-based playback delay: https://www.goveda.com/patent/CN-122002178-A