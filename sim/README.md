# Sync In It — M0 timing simulator

A dependency-free Python reference model for clock synchronization and playout timing. It is not an audio player and does not claim that phone hardware has been validated.

## Run

From this directory, install the local package and run the tests and scenarios:

```bash
python -m pip install -e .
python -m unittest discover -s tests -v
sync-in-it-sim --duration 60 --seed 7
```

For a no-install run on macOS or Linux, use the source tree explicitly:

```bash
PYTHONPATH=src python -m unittest discover -s tests -v
PYTHONPATH=src python -m sync_in_it_sim --duration 60 --seed 7
```

For PowerShell on Windows:

```powershell
$env:PYTHONPATH = "src"
python -m unittest discover -s tests -v
python -m sync_in_it_sim --duration 60 --seed 7
```

Add `--json` to emit the complete machine-readable scenario report.

## Modeled behavior

- Independent master and receiver monotonic clocks with offset and rate error.
- Four-timestamp NTP-style exchanges with directional delay, jitter, asymmetric paths, RTT spikes, independent packet loss and contiguous burst loss.
- Lowest-RTT sample selection, robust linear offset fit, receiver-skew estimate and selected-fit sample-span reporting.
- Adaptive probe cadence: 20 join probes 50 ms apart, 500 ms retries while unstable, then 2 s steady-state intervals after three consecutive stable estimator updates.
- Estimator convergence time: first point with three consecutive estimates at or below 1 ms offset error and 5 ppm skew error.
- A 48 kHz sample-index timeline and a parameterized DAC clock model.
- A simplified 20 ms playout-tracking loop that applies the current one-frame drop/duplicate and hard-resync policy.
- Slip count/rate, hard-resync count, and p95/maximum/final modeled playout error.
- Deterministic scenarios so regressions can be reproduced, including late join, severe asymmetry and a synthetic ten-receiver fan-out.
- Fixed-2 s versus adaptive-acquisition A/B comparisons for +30 ppm drift, high jitter and random packet loss. For profiles without burst loss, time-indexed random streams make overlapping probe timestamps use the same network outcome in both schedules.

## Interpreting the tracking metrics

The playout loop is an M0 control-policy model, not a sample-accurate audio engine. It projects the fitted receiver clock rate and a configured DAC rate error onto a 20 ms loop, back-projects the final estimator offset error to the simulated playback start, and applies the existing `DriftController` decisions. Error percentiles/maxima are sampled immediately before each correction. This is useful for deterministic policy regression tests, not evidence of acoustic synchronization or real-time audio quality.

The convergence threshold is a ground-truth simulator diagnostic, not a runtime signal available to a phone. `null` means that the simulation did not meet the threshold for three consecutive estimator updates.

`acquisition_stable_at_s` is the operational simulator gate: a skew estimate must exist, at least 8 selected low-RTT samples must span at least 10 seconds, selected-fit residual RMS must be below 0.25 ms, and these conditions must hold for three consecutive estimator updates. The 0.25 ms threshold is a provisional simulator performance gate, stricter than the TRD's initial <1 ms residual baseline. An unstable receiver remains on the 500 ms retry cadence; a stable one backs off to 2 seconds.

## Default cadence comparison

For `--duration 60 --seed 7`, fixed and adaptive schedules use the same profile, seed and time-indexed network events at overlapping probe timestamps:

| Profile | Fixed convergence | Adaptive convergence | Probes: fixed → adaptive |
|---|---:|---:|---:|
| +30 ppm clock drift | 21.0 s | 7.5 s | 50 → 73 |
| High jitter | 53.0 s | 32.0 s | 50 → 139 |
| Random packet loss | 55.0 s | 34.5 s | 50 → 139 |

A separate 40-seed, 60-second comparison showed the following median ground-truth convergence times and successful-convergence counts:

| Profile | Fixed | Adaptive |
|---|---:|---:|
| +30 ppm | 34.0 s (34/40) | 22.0 s (39/40) |
| High jitter | 41.0 s (21/40) | 26.0 s (39/40) |
| Random packet loss | 48.0 s (18/40) | 29.0 s (36/40) |

The adaptive schedule sent about 99, 139 and 139 probes per 60-second run on average for those profiles, versus 50 for the fixed schedule. The strict acquisition gate was not met at the end of the 40-run high-jitter and packet-loss samples, so those receivers kept retrying instead of being declared ready. This improves the simulator's ground-truth convergence distribution at a real probe-traffic cost. The 40-seed results are synthetic and do not include shared Wi-Fi airtime contention.

## Scope and limitations

M0 is a simulation gate. It cannot validate Android scheduler behavior, Wi-Fi chipset behavior, Oboe/AAudio timestamps, DAC presentation semantics, acoustic propagation or speaker hardware. The ten-receiver fan-out uses independent simulated paths; it does not model shared Wi-Fi airtime contention. Values in the scenario report are simulated outputs, not measurements from phones. The estimator is a Python reference implementation; shared golden vectors, C++ parity and physical-device calibration remain outstanding. The simplified frame-slip policy also does not implement continuous resampling/slew correction or a real audio callback.
