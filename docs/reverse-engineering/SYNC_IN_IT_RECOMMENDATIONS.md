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
