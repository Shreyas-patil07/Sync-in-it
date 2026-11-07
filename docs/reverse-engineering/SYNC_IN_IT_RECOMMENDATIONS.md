# Sync In It — Engineering Recommendations from the Research

**Basis:** supplied public-source reverse-engineering bundle dated 7 November 2026, cross-read against the current repository documentation and the latest recorded physical test.  
**Status:** engineering recommendations and prioritization—not claims that competitor internals have been verified.

## 1. Keep the product architecture explicit

Sync In It's current Android path is primarily **host-to-client PCM audio distribution**. A Host produces sample-indexed PCM chunks, Speakers buffer them, estimate the Host clock, pass a READY gate and schedule output from a shared future ANCHOR.

Do not conflate this with a Spotify-Jam-style playback-state room. They solve different problems:

- Audio distribution makes every endpoint render the same distributed media timeline. The Host's PCM and packet timing matter.
- Playback-state coordination makes clients agree on track/queue/position/play commands while each client may obtain its own licensed media stream.
- A later product could add a provider/queue/control plane, but that is a separate layer and should not destabilize the currently failing audio/timing path.

## 2. Highest-value references

| Reference | Use it for | Do not assume |
|---|---|---|
| [Snapcast](https://github.com/snapcast/snapcast) | Public protocol, clock exchanges, timestamped audio chunks, buffered playout and drift correction. First source-level reference for the core distribution model. | Its reported timing performance transfers unchanged to Android phones, Wi-Fi, AudioTrack or Bluetooth. |
| [Unison](https://play.google.com/store/apps/details?id=com.prajwol.unison) | Nearby host/join UX, QR-based joining, local session flow and per-device calibration concept. | Its private clock estimator or audio backend is known. |
| [Boomi](https://play.google.com/store/apps/details?id=com.nevsoft.boomi) | Device health visibility, local file-transfer verification, per-device trim and drift-correction UX. | The claimed algorithm, transfer protocol or checksum design is public. |
| [SoundSeeder](https://soundseeder.com/) | Mature player/speaker roles, cross-platform receiver workflow and saved per-speaker offset calibration. | Its manual offset is a replacement for clock estimation or a fully automatic drift controller. |
| [Together](https://github.com/chtnnh/together) | Room actor/event model, typed realtime messages, queue semantics, late join and web control-plane testing. | It is a raw PCM multi-room transport reference; it coordinates playback state. |
| [Music Assistant](https://www.music-assistant.io/documentation/) and [Lyrion](https://lyrion.org/reference/lyrion-music-server/) | Future media-provider, player abstraction, library and shared queue design. | A music-library controller solves sample-accurate endpoint timing by itself. |
| [Airfoil](https://www.rogueamoeba.com/support/manuals/airfoil/) and speaker ecosystems | Output-route delay compensation, device-group semantics and source/receiver boundary. | Bluetooth or proprietary receiver timing can be assumed from app timestamps alone. |
| [SonoBus](https://github.com/sonosaurus/sonobus) / [Jamulus](https://jamulus.app/wiki/FAQ) | Jitter-buffer and live-network fault scenarios. | Their low round-trip live-performance optimization is the same as synchronized multi-room playback. |

## 3. Priorities mapped to current test evidence

The latest recorded v0.5.9 post-PR #55 Speaker-side run showed PCM ingress mostly around 49–53 frames/sec (interval reports 44.1–56.2), zero missing frames and contiguous buffering to approximately 19.94 seconds. Clock residual RMS remained approximately 0.412–0.557 ms against the strict <0.25 ms threshold; stable streak stayed at zero and READY was not demonstrated. Matching Host logs and acoustic recordings are absent. See [Test Results](../Test_Results.md).

That makes clock acquisition and measurement quality the immediate blocker—not competitor feature breadth.

### P0 — Make the timing failure diagnosable

1. Capture every probe's t1, t2, t3, t4, raw RTT and raw offset.
2. Record whether each sample was selected by the estimator, and why any sample was rejected/expired.
3. Record raw fit inputs, selected low-RTT subset, fitted slope/skew, residual and uncertainty. Preserve a replayable export from a run.
4. Correlate Speaker ingress with Host queued/written frames, queue depth/high-water/overflow and slow TCP writes from the same session.
5. Check whether estimator residual is correlated with RTT, network path, Wi-Fi power/scheduling or sample-window selection.
6. Verify the cached rolling-window diagnostic against a fresh native snapshot: the recorded log showed WindowAdvanced inserts alongside advances=0 cached messages.

**Do not** relax the 0.25 ms residual gate, enlarge the 500 ms queue, or infer the root cause from the Speaker-only log. The current data does not justify those changes.

### P1 — Prove one complete playback path

Use one Host + one Speaker first:

1. Join and establish control/audio channels.
2. Sustain continuous PCM ingress and prebuffer.
3. Achieve the existing stable clock gate.
4. Send READY and verify Host acceptance.
5. Receive the common ANCHOR.
6. Capture output with the receiver-specific acoustic marker and compute start skew.

Then run two Speakers, followed by three/four. The benchmark's next acoustic decision gate in the project docs is p95 absolute skew ≤10 ms; use the same placement, route and recording method for comparisons.

### P2 — Separate clock error from output-path error

Track three independent quantities:

- **Network clock mapping:** offset, skew, probe delay and estimator uncertainty.
- **Digital playout scheduling:** target sample index, target monotonic time, actual writer start and underruns.
- **Acoustic output:** measured sound arrival, per-device output route and calibration.

A successful READY or common target timestamp does not prove the speakers are acoustically aligned. Keep stored output calibration separate from temporary network clock offset; recalibrate when the audio route changes (built-in speaker, wired output, Bluetooth).

### P3 — Drift and recovery

After first acoustic start passes:

- Measure position/acoustic drift at 1, 5, 30 and 60 minutes.
- Test 0/20/50 ms added delay, jitter and 1–5% packet loss.
- Test 2/3/5/8 Speakers where the current build admits them.
- Exercise host loss, Speaker reconnect, late join, Wi-Fi roam and route change.
- Compare gentle rate correction against seek/rebuffer for large errors, with objective dropout and skew metrics.

Long screen-off/endurance runs remain blocked until the foreground-service and wake-lock lifecycle is implemented and reviewed.

### P4 — Add source/player abstractions only after timing is stable

Once end-to-end PCM scheduling and output alignment are demonstrated, consider a separate source/control layer:

- provider/source adapters and media item identity;
- room queue and playback epoch/revision;
- sequenced play/pause/seek/skip events;
- late-join position/epoch snapshot;
- explicit conflict policy (host authority or controlled DJ handoff);
- optional self-hosted provider integration (Music Assistant/Lyrion).

Do not build provider integrations as a workaround for unresolved output timing.


## 3.1 Final upgrade decision — 7 November 2026

The consolidated implementation order is now maintained in [the implementation plan](../07_Implementation.md):

1. Per-probe and fit-update telemetry, matching Host delivery/queue logs, shared core/JNI readiness configuration and an offline replay harness.
2. Controlled router/hotspot, power-lock, timestamp-capture, probe-selection and slope A/B matrix.
3. Resolve residual-threshold disagreement in [ADR-024](../ADR/Architecture_Decisions.md#adr-024--clock-readiness-criterion-and-replay-parity), deriving clock uncertainty from the T2 acoustic budget. Do not change the current <0.25 ms device gate before evidence and an ADR amendment.
4. Prove one Host + one Speaker through READY/ANCHOR, then measure physical acoustic skew (p95 ≤10 ms) before scaling.
5. Pull the already-planned manual per-device/route offset earlier; then callback-safe DAC snapshot, drift correction and M5 late join/reconnect/Host approval.
6. Defer provider/queue control, Opus, PWA, calibration and security hardening until the timing path is validated. Evaluate file-sync at M6 only if bandwidth measurements show PCM is limiting.

The clock-gate discrepancy must remain visible: TRD <1 ms baseline, Testing plan historical <1 ms residual standard deviation, JNI <0.25 ms residual RMS and effectively unlimited core default are not interchangeable. The currently enforced Android gate remains unchanged. The plan does not turn hypotheses or competitor behavior into accepted performance claims.


## 4. Suggested session and telemetry boundaries

A future control-plane model can use the research's room/event concepts while leaving PCM transport and timing in their own modules.

- **Room/Session:** session ID, owner/host, playback epoch, queue revision, state and scheduled anchor.
- **Participant:** stable participant ID, role, capabilities, connection and readiness state.
- **PlaybackEvent:** monotonic sequence number, event type, target position and target server time.
- **SyncSample:** per-probe timestamps, RTT, offset, selected status and uncertainty.
- **OutputCalibration:** endpoint + output-route key + measured latency/trim + measurement metadata.
- **TelemetryEvent:** privacy-minimized counters/latency buckets, not raw audio or credentials.

This is a proposed logical model, not a claim that the current repository already implements all these entities.

## 5. Decision rule

Use competitor research to generate testable hypotheses and test fixtures. Accept an implementation only when it improves repeatable device measurements without unacceptable dropouts, CPU, battery, thermal or memory costs. Synthetic results screen ideas; physical acoustic measurements validate playback claims.


## 3.2 Additional architecture review — output, transport and process (7 November 2026)

The next recommendations are ranked by confidence. They are candidate changes and test priorities, not accepted implementation decisions.

### Priority 1 — Continuous output; ANCHOR selects a frame (high confidence)

Keep the output stream active from Speaker join and render silence while the shared timeline is not playing. Acquire the output frame/time mapping during that silent period. When ANCHOR arrives, map its Host time to local monotonic time and invert the DAC mapping to identify the corresponding output-frame boundary; associate the ANCHOR sample index with that frame.

This removes a scheduled `AudioTrack.play()` call from the media-start decision. It does **not** prove that downstream mixer/DSP, speaker transducer or acoustic-path delay disappears. Output-frame and timestamp-frame coordinate origins, already-queued frames and the writable horizon must be validated. Keep physical acoustic measurement and route-specific compensation available until repeatable evidence says otherwise. See [ADR-025](../ADR/Architecture_Decisions.md#adr-025--continuous-output-and-timeline-frame-scheduling).

### Priority 2 — Reduce bandwidth and contain per-Speaker transport stalls (medium confidence)

Current PCM16 stereo at 48 kHz consumes 1.536 Mbps payload per Speaker; eight concurrent unicast Speaker streams consume 12.288 Mbps before networking and Wi-Fi airtime overhead.

- FLAC/TCP: test lossless compression and decode cost. Compression ratio varies by source; do not assume a 50% reduction.
- Opus/TCP: start with 20 ms frames; record encoder lookahead, packet aggregation, decoder delay, bitrate, audio quality and CPU. Decode ahead of presentation but preserve original sample indices. Multi-second group delay does not make codec delay disappear.
- UDP selective repair: defer until after a working start and a TCP baseline. Add sequence/sample indices, bounded NACKs, repair deadlines before playout and explicit late-loss concealment. UDP loss does not block later packets, but it still leaves a missing sample range if repair misses the deadline.

The current architecture uses one TCP audio connection per Speaker. A lost segment can head-of-line block later bytes on that one connection; it does not automatically block other Speakers' independent connections. The 20-second receive buffer only absorbs a bounded stall. See [ADR-027](../ADR/Architecture_Decisions.md#adr-027--audio-transport-and-codec-alternatives).

Snapcast is a relevant public reference for this option set: its project documentation describes time-tagged chunks, continuous client/server synchronization, client buffering, TCP delivery and FLAC as its default codec. Do not assume identical results on Android phones or a Wi-Fi hotspot: [Snapcast](https://github.com/snapcast/snapcast).

### Priority 3 — Oboe/AAudio backend comparison (medium-low confidence)

Compare the current scheduled AudioTrack path, continuous AudioTrack with `PERFORMANCE_MODE_LOW_LATENCY`, and Oboe/AAudio callback output on the same two Xiaomi phones. Capture actual selected API and sharing/performance modes, sample rate, frames per burst, timestamp availability/mapping error, underruns/XRuns, callback timing and acoustic skew.

Android recommends Oboe/AAudio low-latency configuration for low-latency audio; that does not establish that AAudio timestamps are more accurate than AudioTrack timestamps on these particular devices. Make the backend choice from repeatable timestamp and acoustic measurements, not API reputation. The references and test procedure are in [Testing §8.2](../09_Testing.md#82-continuous-output-and-audiotrack-versus-oboe-experiment-m2m3).

### Process constraint — one Speaker before more layers

The larger project risk is advancing transport, codec, platform and product layers before one Speaker has passed the end-to-end path. Keep one primary implementation workstream:

1. Current PCM/TCP + AudioTrack: one Speaker reaches READY, receives ANCHOR and produces audible, marker-verified output.
2. Compare continuous-output start on that same setup.
3. Add a second Speaker and prove the acoustic target.
4. Only then test codec/transport alternatives and group scaling.
5. Keep Oboe/AAudio as a later backend comparison after a measurable output baseline.

Do not start codec, UDP, iOS, PWA, provider/queue, calibration or security implementation as a workaround for a blocked single-Speaker path. Clock algorithm work stays limited to the replay experiments already captured in ADR-026; first determine whether the failing path is clock readiness, transport/buffering or output scheduling.

