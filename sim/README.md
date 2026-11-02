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
- Lowest-RTT sample selection, robust linear offset fit and receiver-skew estimate.
- Estimator convergence time: first point with three consecutive estimates at or below 1 ms offset error and 5 ppm skew error.
- A 48 kHz sample-index timeline and a parameterized DAC clock model.
- A simplified 20 ms playout-tracking loop that applies the current one-frame drop/duplicate and hard-resync policy.
- Slip count/rate, hard-resync count, and p95/maximum/final modeled playout error.
- Deterministic scenarios so regressions can be reproduced, including late join, severe asymmetry and a synthetic ten-receiver fan-out.

## Interpreting the tracking metrics

The playout loop is an M0 control-policy model, not a sample-accurate audio engine. It projects the fitted receiver clock rate and a configured DAC rate error onto a 20 ms loop, back-projects the final estimator offset error to the simulated playback start, and applies the existing `DriftController` decisions. Error percentiles/maxima are sampled immediately before each correction. This is useful for deterministic policy regression tests, not evidence of acoustic synchronization or real-time audio quality.

The convergence threshold is a simulator diagnostic, not a product acceptance guarantee. `null` means that the simulation did not meet the threshold for three consecutive estimator updates.

## Scope and limitations

M0 is a simulation gate. It cannot validate Android scheduler behavior, Wi-Fi chipset behavior, Oboe/AAudio timestamps, DAC presentation semantics, acoustic propagation or speaker hardware. The ten-receiver fan-out uses independent simulated paths; it does not model shared Wi-Fi airtime contention. Values in the scenario report are simulated outputs, not measurements from phones. The estimator is a Python reference implementation; shared golden vectors, C++ parity and physical-device calibration remain outstanding. The simplified frame-slip policy also does not implement continuous resampling/slew correction or a real audio callback.
