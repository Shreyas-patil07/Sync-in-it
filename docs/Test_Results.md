# Sync In It — Test Results

This file is the chronological record of physical-device and integration testing. Append a new dated run for every meaningful test; do not overwrite earlier results. Keep observed facts separate from hypotheses.

## 1. Targeted goal

**Primary goal:** reliably start synchronized PCM audio playback across the Host and connected Android Speaker devices on the same local network.

A test is not considered successful merely because devices discover each other or PCM arrives. The complete path must work:

1. A Speaker joins the Host and establishes the control and audio TCP channels.
2. The Speaker receives a continuous PCM stream without missing frames or receive-path stalls.
3. The Speaker acquires a sufficiently stable mapping between its monotonic clock and the Host clock.
4. The Speaker has the required contiguous PCM pre-roll and sends READY.
5. The Host accepts READY from the participating Speakers and sends a coordinated playback ANCHOR.
6. Each Speaker starts at the scheduled target, and physical output is validated using the acoustic marker/analyzer workflow.

### Current synchronization and readiness gates

These are the existing implementation requirements, not relaxed targets:

| Requirement | Acceptance condition |
|---|---:|
| Clock samples used | At least 8 |
| Selected clock-sample time span | At least 10,000 ms |
| Clock-fit residual RMS | Strictly below 0.25 ms |
| Consecutive stable estimator results | At least 3 |
| Contiguous PCM pre-roll | At least 3,000 ms |
| PCM ingress | Approximately 50 frames/sec for the current 20 ms chunk cadence |
| PCM loss | 0 missing frames during the measured interval |
| Session transition | READY accepted by Host, followed by the coordinated ANCHOR |
| Playback validation | Audio starts on each Speaker and acoustic marker measurements are recorded |

The project must also define and record a numerical maximum inter-device acoustic skew before physical synchronization can receive a final pass. Do not infer that limit from the clock-estimator threshold; they measure different things.

## 2. Test record — 2026-11-06 — v0.5.9 post-fix validation

**Run ID:** SIT-2026-11-06-01  
**Build context:** Testing performed after the v0.5.9-preview.1 release. The supplied log excerpt does not print the installed APK version or commit, so the exact device build is not independently identified by this log.  
**Log window:** 23:41:53–23:42:13 device log timestamps (timezone not embedded in the excerpt).  
**Perspective:** Speaker-side diagnostics. A matching Host log was not supplied.  
**Session diagnostic ID:** `b1ab2fb2`

### Test intent

Validate whether the PCM-ingress/backpressure diagnostics and buffer-snapshot optimization from PR #55 allow a Speaker to receive PCM continuously, acquire a stable clock, pass the READY gate, and proceed to coordinated playback.

### Observed results

| Area | Observed evidence | Result |
|---|---|---|
| UDP time-sync transport | Probe responses succeeded; reported median RTT was approximately 5.40–7.21 ms | Working in this excerpt |
| PCM ingress | Reported rate was mostly around 49–53 frames/sec, with observed interval values from 44.1 to 56.2 frames/sec | Generally keeping pace; continue monitoring |
| PCM continuity | `missingFrames=0` in the supplied ingress and READY diagnostics | Pass for the captured interval |
| PCM pre-roll | Buffer grew from 800 ms to approximately 19,940 ms | Passes the 3,000 ms minimum |
| Clock sample count | Reached 15 selected samples | Pass |
| Clock sample span | Reached 19,125 ms | Pass |
| Clock residual RMS | Varied approximately 0.412–0.557 ms; final reported value was 0.5225 ms | **Fail** — above the strict 0.25 ms limit |
| Stable streak | Remained at 0 | **Fail** — requires at least 3 |
| READY gate | Initially reported `CLOCK_AND_PCM_INSUFFICIENT`, then `CLOCK_UNSTABLE` | **Blocked by clock stability** |
| READY / ANCHOR | No READY-sent or playback-ANCHOR event appears in the supplied excerpt | Not demonstrated |
| Host audio delivery | No matching `HOST_AUDIO_DELIVERY` or `HOST_AUDIO_WRITE_SLOW` lines supplied | Not evaluated |
| Acoustic alignment | No post-fix acoustic marker analysis supplied | Not evaluated |

### Clock estimator observations

The selected fit reached the minimum count and time-span requirements, but residual RMS stayed above the configured threshold. Representative observations:

| Probe count | Samples used | Sample span | Residual RMS | Stable streak |
|---:|---:|---:|---:|---:|
| 19 | 5 | 995 ms | 0.4506 ms | 0 |
| 30 | 8 | 5,103 ms | 0.4278 ms | 0 |
| 41 | 11 | 11,114 ms | 0.4576 ms | 0 |
| 57 | 15 | 19,125 ms | 0.5225 ms | 0 |

The reported receiver-skew estimate also varied in sign and magnitude during the run (examples include +68.66 ppm, +17.75 ppm, −48.92 ppm, −63.54 ppm and −9.50 ppm). This is evidence of an unstable/noisy fit in this sample; it does not by itself identify whether the dominant cause is network-delay asymmetry, Android scheduling, timestamp capture, or estimator behavior.

### Interpretation

- **PR #55 receive-path objective:** encouraging. The Speaker sustained near-target ingress, accumulated a full pre-roll buffer and reported no missing frames during the supplied interval. The earlier receive-path failure was not reproduced in this excerpt.
- **End-to-end synchronization objective:** not achieved. The clock residual remained above 0.25 ms, so the stability streak stayed at zero and the READY gate remained blocked.
- **Cause:** not yet isolated. A healthy PCM receive path does not prove the Host writer is healthy, and successful UDP responses do not prove the clock measurements are sufficiently precise.
- **Capture limitation:** the supplied excerpt ends during repeated pre-roll rolling-window messages. It is not a complete session log; therefore it cannot establish what happened after the final supplied line.

### Diagnostic caveat to investigate

The excerpt repeatedly reports `PCM pre-roll rolling window active · advances=0` while the corresponding insert result is `WindowAdvanced`. The diagnostic path reads a cached buffer snapshot (refreshed on a 250 ms interval), so the displayed count may be stale. Verify this behavior before treating `advances=0` as the actual native counter value. The current excerpt does not establish a buffer-counter defect.

### Run verdict

**FAIL — clock acquisition / READY gate.**

PCM ingress and pre-roll were healthy in the captured interval, but stable clock acquisition was not achieved. Coordinated playback and acoustic synchronization were not demonstrated. Do not mark v0.5.9 as physically validated based on this run.

### Next actions

1. Capture the matching Host log for the same session and time window. Include `HOST_AUDIO_DELIVERY`, `HOST_AUDIO_WRITE_SLOW`, queue depth/overflow, and disconnect events.
2. Install the diagnostic build on Host and Speaker. In **Network debugger**, tap **Save diagnostic report (.txt)** and save each device's report in Downloads. The file preserves the retained app-private event history, including every accepted `CLOCK_PROBE` and `CLOCK_FIT` line. Preserve exact `t1/t2/t3/t4`, sequence, corrected RTT, raw offset and selection-policy metadata so sample selection can be reconstructed offline. The diagnostic build does not yet export a per-fit selection bitmap or native rejection enum.
3. Compare residual behavior against RTT and sample-window selection. Investigate timestamp capture and network scheduling before changing estimator policy.
4. Verify the pre-roll rolling-window counter against a fresh native snapshot; distinguish stale cached diagnostics from the actual counter.
5. Keep the existing residual threshold (<0.25 ms), 3-second PCM requirement and bounded Host queue unchanged until the cause is supported by evidence.
6. Repeat the one-Host/one-Speaker test. Record whether READY is sent, accepted, and followed by an ANCHOR.
7. Once the one-Speaker path passes, test multiple Speakers and record acoustic marker results for each device.


## 2.1 Next-run capture requirements — final upgrade brief (7 November 2026)

Before another acceptance attempt, capture a replayable, session-correlated evidence set:

- Speaker per-probe record: attempt ID, t1, t2, t3, t4, RTT, raw offset, selected/rejected/expired status and reason.
- Speaker fit-update record: samples seen/used, selected span, slope/skew, residual RMS, predicted offset at t4 and stable streak.
- Matching Host record: audio delivery, slow writes, queue depth/high-water/overflow and disconnect events with the same session diagnostic ID.
- Fresh native PCM-counter snapshot to verify whether the cached 250 ms `advances=0` diagnostic is stale.
- Capture all run-sheet fields in section 3, including AP type/band, Wi-Fi low-latency lock, CPU wake-lock state, receive-thread priority, probe style, estimator variant, RTT p95, hold-out prediction error, offset standard error and its independence assumption, slope range, AudioTrack start latency, marker method/placement and both log filenames.

Replay the physical run through the same C++ estimator and gate used by Android; check parity against Python and preserve the raw log as a real-log fixture. Expected exit gate: replay reproduces on-device residual and stable streak within documented rounding.

Do not edit the current <0.25 ms residual-RMS gate, 8-sample minimum, 10-second span, three-update streak or 500 ms Host queue as part of a failing-run fix. The documented <1 ms residual baselines and effectively unlimited core default must be reconciled in ADR-024 before any threshold change. Next controlled A/B: same two phones, one variable at a time, 60 seconds per run, three repeats; compare hotspot/router, power locks, receive priority/timestamp capture, single/paired probes, lowest 25%/50%/all/robust selection and free/clamped/offset-only slope. Report hold-out prediction error by fitting the first 70% and predicting later low-RTT offsets.

## 2.2 Proposed output/estimator comparison run fields — 7 November 2026

For the AudioTrack-versus-Oboe and continuous-output experiment, add:
- Output backend and actual API (AudioTrack / Oboe with AAudio or fallback).
- AudioTrack performance mode; Oboe performance mode and requested/granted sharing mode.
- Stream sample rate, channel format, frames per burst, buffer size and route.
- Stream activation time relative to join; timestamp acquisition start and availability.
- DAC frame/time mapper sample count/span, residual, rejected count and held-out prediction error.
- ANCHOR Host time, mapped local time, target output-frame index and ANCHOR sample index.
- First PCM frame submitted, first corresponding frame reported presented/committed, and marker-detected acoustic arrival.
- Callback duration/jitter/late callbacks; AudioTrack underruns or Oboe XRuns.
- Acoustic mean/p95/max skew, transition discontinuity and dropout count.

For estimator variants, preserve the same ordered raw probe log and record algorithm/configuration ID, per-update predicted offset before measurement assimilation, covariance (for the two-state tracker), entry/exit state and stable-streak transitions. Keep baseline, hysteresis, tracker and combined results separate. Do not compare runs whose input probe sequence or device/network conditions differ.



## 2.3 Single-Speaker and transport baseline fields — 7 November 2026

The first next-run record must prove one Host + one Speaker over the existing PCM16/TCP + AudioTrack path before any transport or codec experiment. Record READY sent/accepted, ANCHOR received, first audible marker, output-frame-to-marker timing, missing sample spans, underruns and a physical recording. A single Speaker proves the end-to-end path; it does not establish inter-device acoustic skew.

After the first one-/two-Speaker timing gates pass, transport/codec runs must add:
- Codec and transport ID (PCM/TCP, FLAC/TCP, Opus/TCP, PCM/UDP-repair).
- Payload bytes and mean/p95/peak bitrate per Speaker and aggregate.
- TCP retransmission count, per-Speaker HOL stall duration, write queue and buffer depletion.
- UDP lost/reordered/duplicated/late datagrams; NACK count; repair RTT; repaired-before-deadline ratio; unrepaired sample spans.
- Encoder lookahead, packetization duration, frames per packet, decoder processing/delay and decoded sample-index continuity.
- CPU, memory, battery and thermal state; underruns/dropouts; acoustic quality and p95 skew.
- Whether each missing chunk was repaired before its target presentation deadline or was concealed/dropped.

Do not compare combined codec/transport changes as one result. First compare codecs over TCP, then transport with a fixed codec. Keep PCM/TCP as the fallback until a candidate passes acoustic and reliability gates.

## 2.4 Test record — 2026-11-07 — v0.6.3-debug per-device diagnostics

- **Run ID:** SIT-2026-11-07-02
- **Build:** `0.6.3-debug (1794038210)`; the diagnostic release is `0.6.3`, but these are debug builds; no installed APK filename or source commit is recorded in the supplied logs.
- **Devices:** Host Xiaomi 22101316I / Android 14; Speaker Xiaomi M2010J19SI / Android 12.
- **Network:** Both devices on Wi-Fi, same `192.168.1.0/24` subnet. Router/AP model, band and channel were not recorded.
- **Configuration:** One Host, one Speaker; 48 kHz stereo PCM16; separate TCP control/audio channels and UDP time-sync.
- **Test duration:** Host admission at 13:32:58.695 to readiness teardown at 13:33:43.672 (local log time, +05:30).
- **Test intent:** Validate PR #68 queue classification and new Host/Speaker close attribution while exercising clock acquisition and PCM delivery.

**Observed metrics**

| Metric | Result |
|---|---:|
| UDP probes accepted by Speaker | 108 |
| RTT median | 5.5413 ms |
| Selected samples / seen | 27 / 108 |
| Selected sample span | 33,052 ms |
| Residual RMS | 0.416862 ms |
| Estimated receiver skew | −3.237 ppm |
| Stable streak | 0 |
| PCM ingress | Approximately 50 frames/sec |
| Buffered contiguous PCM | 20,000 ms |
| Missing frames | 0 |
| Window advances | 1,238 |
| Host queue queued / written | 2,250 / 2,250 |
| Host queue depth / high-water | 0 / 1 |
| Host queue-overflow event | None observed |
| READY sent / accepted | Not reached |
| ANCHOR received | No |
| Playback start result | Not started |
| Acoustic marker skew | Not measured |

**Verdict:** FAIL — clock acquisition / READY gate.

**Observed failure:**
- The Speaker met the PCM-buffer and continuity conditions but did not meet the clock residual requirement (`0.416862 ms` versus strict `<0.25 ms`).
- The Host fired `GROUP_START_READINESS_TIMEOUT` at 45 seconds and closed receiver 1 with `trigger=readiness-timeout`.
- Host queue counters remained healthy; the old post-cleanup `HOST_AUDIO_QUEUE_OVERFLOW` false positive was not reproduced.
- The Speaker then observed `master closed the control channel` and `master closed the audio channel`, followed by `SPEAKER_CLIENT_CLOSE trigger=control-reader-failure`.
- No spontaneous TCP reset is established in this run; closure follows the Host readiness timeout.

**Hypothesis — unproven:** variable delay between captured `t1/t3` and their respective send operations may contribute to four-timestamp offset noise. The run's older Host telemetry sampled response timings only every 20 responses, so the exact Host/Speaker per-sequence relationship cannot be reconstructed from the supplied pair.

**Next action:** build and test `diagnostics/per-probe-clock-boundaries-2026-11-07`. Correlate `CLOCK_PROBE` and `HOST_UDP_PROBE` by the same sequence. Replay the existing raw four-timestamp samples without changing estimator selection or readiness gates; evaluate send-boundary corrections offline only.

**Evidence:** `SyncInIt-diagnostics-20261107-133401.txt` (Host) and `SyncInIt-diagnostics-20261107-133357.txt` (Speaker).

## 2.5. Test record — 2026-11-07 — v0.6.4-debug paired per-probe run

- **Run ID:** SIT-2026-11-07-03
- **Build:** Both devices report `0.6.4-debug (1794040821)` in the latest `APP_INSTANCE` entries. The first eight-line export header still shows the older log-initialization version `0.6.1-debug`; this is a persisted-header issue, not the version used by the latest session.
- **Devices:** Xiaomi 22101316I / Android 14 / API 34 and Xiaomi M2010J19SI / Android 12 / API 31.
- **Network:** Both phones used Wi-Fi on `192.168.1.0/24`. Router model, band and channel were not captured.
- **Test intent:** Validate the per-probe Host/Speaker telemetry with the two phones in both role directions.
- **Duration:** Run A: approximately 16:17:06–16:17:51 (+05:30). Run B: approximately 16:18:58–16:19:43 (+05:30).
- **Evidence:** `SyncInIt-diagnostics-20261107-162019.txt` and `SyncInIt-diagnostics-20261107-162018.txt`.

### Observed results

| Metric | Run A: Android 14 Host | Run B: Android 12 Host |
|---|---:|---:|
| Session diagnostic ID | `38403299` | `511f7512` |
| Accepted probes | 107 | 108 |
| Selected samples / seen | 27 / 107 | 27 / 108 |
| Selected span | 32,036 ms | 43,815 ms |
| RTT median | 4.9288 ms | 5.6278 ms |
| Residual RMS | 0.324341 ms | 0.500333 ms |
| Estimated receiver skew | −5.577 ppm | −0.003 ppm |
| Stable streak | 0 | 0 |
| PCM buffer | 20,000 ms | 20,000 ms |
| Missing PCM frames | 0 | 0 |
| Host queue depth / high-water | 0 / 1 | 0 / 1 |
| READY / ANCHOR | Not reached | Not reached |

**Verdict:** FAIL — clock acquisition / READY gate in both role directions.

**Observed facts:**
- Both role directions completed discovery, control/audio TCP admission and repeated UDP time-sync exchanges.
- Both Speakers reached the full 20-second PCM buffer, with 960,000 contiguous frames and zero missing frames.
- Neither residual met the strict `<0.25 ms` requirement. The stable streak remained zero.
- Both Host sessions closed the receiver at the 45-second readiness deadline. No READY/ANCHOR or playback start was demonstrated.
- Per-probe `HOST_UDP_PROBE` records were present in the paired reports, and sequence values can be correlated across the two devices.

**Interpretation — not a root-cause finding:** reversing the roles did not remove the residual problem. The different residuals indicate the result is sensitive to the device/role or run conditions, but this evidence does not isolate Android scheduling, network asymmetry, timestamp capture or estimator behavior. The 45-second deadline also stopped both runs before a full 60-second fit-window observation.

**Next action:** test branch `experiment/send-boundary-t3-debug-acquisition-window` on both phones. The branch moves final T3 stamping to the UDP transport send path and gives debug builds a 90-second readiness observation window. Keep all estimator and READY thresholds unchanged. Compare the new Host send-boundary metrics with Speaker raw four-timestamp samples by sequence.


## 2.6 Test record — 2026-11-08 — v0.6.9-debug paired application/kernel timestamp replay

- **Run IDs:** SIT-2026-11-08-04 (attempt `3b0cf4b8`) and SIT-2026-11-08-05 (attempt `eb1fc9dc`)
- **Build:** Host and Speaker both report `0.6.9-debug (1794155082)` in their `APP_INSTANCE` records.
- **Devices:** Host Xiaomi M2010J19SI / Android 12 / API 31; Speaker Xiaomi 22101316I / Android 14 / API 34.
- **Network:** Both devices on Wi-Fi in `192.168.1.0/24`; AP model, band and channel were not recorded.
- **Configuration:** One Host, one Speaker; 48 kHz stereo PCM16 over separate TCP control/audio channels; existing UDP four-timestamp clock probes.
- **Source:** Android system-playback capture in both attempts.
- **Test intent:** Correlate every Speaker clock probe with the matching Host receive timestamp and compare the production application-timestamp fit with an offline kernel-receive-timestamp variant. No production estimator or readiness-gate setting was changed.
- **Evidence:** `SyncInIt-diagnostics-20261108-223010.txt` (Host) and `SyncInIt-diagnostics-20261108-223010 (1).txt` (Speaker).

### Paired replay results

The offline replay used the same four-timestamp calculation, 60-second estimator window, lowest-RTT 25% selection, linear skew fit, and stability requirements as the Android implementation. The application-timestamp results below reproduce the final on-device `CLOCK_FIT` values to the reported precision. Kernel timestamp values are an offline comparison only.

| Metric | Attempt `3b0cf4b8` app | Attempt `3b0cf4b8` kernel | Attempt `eb1fc9dc` app | Attempt `eb1fc9dc` kernel |
|---|---:|---:|---:|---:|
| Host session ID | `1826297231` | `1826297231` | `2594760042` | `2594760042` |
| Paired probes | 110 | 110 | 70 | 70 |
| Selected samples / seen | 28 / 110 | 28 / 110 | 18 / 70 | 18 / 70 |
| Selected sample span | 38,100.274 ms | 38,100.484 ms | 24,539.235 ms | 24,539.139 ms |
| Selected-sample RTT p50 | 6.0708 ms | 5.1287 ms | 5.7162 ms | 4.5642 ms |
| Residual RMS | **0.368338 ms** | **0.304824 ms** | **0.449305 ms** | **0.307115 ms** |
| Estimated receiver skew | −0.891 ppm | +3.455 ppm | +9.553 ppm | −4.648 ppm |
| Stable streak | 0 | 0 | 0 | 0 |
| READY gate | **Blocked** | **Would remain blocked** | **Blocked** | **Would remain blocked** |

All four residual values exceed the unchanged strict `<0.25 ms` readiness gate. The kernel timestamp variant improves residual RMS in both sessions, but it does not establish clock readiness or justify switching the production estimator.

### Hold-out comparison

The existing replay methodology trains on the first 70% of samples, then predicts later low-RTT offsets. The hold-out set is small in these runs, so treat this as preliminary evidence, not a production decision.

| Metric | Attempt `3b0cf4b8` app | Attempt `3b0cf4b8` kernel | Attempt `eb1fc9dc` app | Attempt `eb1fc9dc` kernel |
|---|---:|---:|---:|---:|
| Hold-out samples | 6 | 6 | 5 | 6 |
| RMS absolute offset error | 0.3284 ms | 0.2386 ms | 0.4730 ms | 0.3992 ms |
| Median absolute offset error | 0.1730 ms | 0.1584 ms | 0.4603 ms | 0.1902 ms |
| p95 absolute offset error | 0.6786 ms | 0.4722 ms | 0.7835 ms | 0.7725 ms |

The kernel variant improves hold-out RMS in both runs, but the second run's p95 barely changes. Five or six hold-out points are insufficient to establish a robust generalization improvement.

### Timestamp capture observations

| Capture metric | Attempt `3b0cf4b8` median / p95 | Attempt `eb1fc9dc` median / p95 |
|---|---:|---:|
| Host application T2 minus kernel T2 | 663 / 1,433 µs | 621 / 1,554 µs |
| Speaker application T4 minus kernel T4 | 675 / 1,256 µs | 602 / 1,361 µs |
| Host kernel timestamp mapping uncertainty | 6.745 / 16.797 µs | 6.550 / 17.422 µs |
| Speaker kernel timestamp mapping uncertainty | 9.058 / 13.346 µs | 8.943 / 12.924 µs |

The application timestamp is typically captured roughly 0.6–0.7 ms after the kernel receive timestamp. This is a plausible contributor to measurement noise, but the residual comparison alone does not prove it is the sole cause. Kernel timestamp mapping uncertainty was in the low-microsecond range for most probes; it is still measurement uncertainty and must remain part of the error budget.

### Other observed results

- Discovery, TCP control/audio admission and UDP time-sync exchanges completed in both attempts.
- The Host's audio delivery counters remained balanced (queued/written), with queue depth at zero and a high-water mark of one in the captured intervals.
- The Speaker received PCM near the expected 50 frames/sec cadence, reached the 20-second rolling buffer, and reported `missingFrames=0`.
- These are transport and buffering observations only. They do not prove that the PCM carried non-silent audio or that AudioTrack played it.
- System-capture level windows were intermittent: the first attempt included both near-silent and signal-present windows; the second attempt reported repeated near-silent windows. Source-content validation remains a separate issue.
- Neither attempt demonstrated READY, a common ANCHOR, AudioTrack playback start or acoustic-marker output. The Host remained in `AUDIO_SOURCE_WAITING_FOR_READY_COHORT` while the Speaker clock gate remained blocked.

### Interpretation and next action

**Verdict: FAIL — clock stability / READY gate.** The PCM/TCP delivery path is not the observed blocker in these captures. Kernel receive timestamps improve the offline residuals but remain above the production threshold. The data does not justify lowering the threshold or replacing application timestamps in production.

Next:
1. Run the repository's canonical `tools/clock_probe_replay.cpp` against both raw log pairs and preserve its CSV output; verify parity with the on-device application fit and the offline kernel comparison.
2. Repeat the single-Speaker test using the diagnostic timing-click source so source-app capture eligibility/silence is not mixed into the clock experiment.
3. Keep the 0.25 ms residual gate, sample/span requirements and three-update stability streak unchanged until repeated hold-out tests support a measured change.
4. Complete the single-Speaker READY → ANCHOR → audible-output gate, then the two-Speaker acoustic baseline. Only after those gates pass may codec/transport experiments begin.

## 3. Reusable template for future runs

Copy this section for each new run and replace the placeholders. Preserve prior test records.

### Test record — YYYY-MM-DD — [release / branch / build]

- **Run ID:** SIT-YYYY-MM-DD-NN
- **Build:** version, tag, commit SHA, APK filename
- **Devices:** Host model/Android version; each Speaker model/Android version
- **Network:** access point/router, band, topology, and whether all devices used the same LAN
- **Configuration:** sample rate, channels, chunk size, buffer size, connected Speaker count
- **Test duration:** start/end timestamps and timezone
- **Test intent:** exact behavior being validated

**Observed metrics**

| Metric | Result |
|---|---|
| UDP probe count / timeout count | |
| RTT distribution (p50 and range) | |
| Clock samples used / seen | |
| Selected sample span | |
| Residual RMS | |
| Receiver skew estimate | |
| Stable streak | |
| PCM ingress rate | |
| Buffered contiguous PCM | |
| Missing frames | |
| Window advances | |
| Host queue depth / overflow | |
| Host slow-write events | |
| READY sent / accepted | |
| ANCHOR received | |
| Playback start result | |
| Acoustic marker skew | |
| READY sent / Host accepted | |
| First audible marker / output-frame mapping error | |
| Transport / codec | |
| Mean / p95 / peak payload bitrate per Speaker + aggregate | |
| TCP retransmissions / HOL stall duration | |
| UDP loss / reorder / duplicate / late count | |
| NACK / repair success before deadline | |
| Encoder lookahead / decoder delay / packetization | |
| Buffer depletion / underrun / dropouts | |
| CPU / battery / thermal state | |

- **Verdict:** PASS / FAIL / BLOCKED / INCONCLUSIVE
- **Evidence:** log filenames, workflow runs, analysis artifacts
- **Observed failure:** facts only
- **Hypothesis:** suspected cause, clearly marked as unproven
- **Next action:** smallest test or code change that resolves the uncertainty

## 4. Change history

- **2026-11-06:** Created this test record. Initial entry documents the v0.5.9 post-fix Speaker log and the remaining clock-stability blocker.
- **2026-11-07:** Added the paired v0.6.3-debug Host/Speaker failure record and queue/clock findings.
- **2026-11-08:** Added two v0.6.9-debug paired runs, application-versus-kernel timestamp replay, hold-out metrics and the unchanged READY-gate verdict.
