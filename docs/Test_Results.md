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
2. Add or enable per-probe diagnostic capture for the four timestamps (`t1`, `t2`, `t3`, `t4`), individual RTT, raw offset, selected/rejected status, and estimator residual. Preserve enough data to replay the exact estimator offline.
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

- **Verdict:** PASS / FAIL / BLOCKED / INCONCLUSIVE
- **Evidence:** log filenames, workflow runs, analysis artifacts
- **Observed failure:** facts only
- **Hypothesis:** suspected cause, clearly marked as unproven
- **Next action:** smallest test or code change that resolves the uncertainty

## 4. Change history

- **2026-11-06:** Created this test record. Initial entry documents the v0.5.9 post-fix Speaker log and the remaining clock-stability blocker.
