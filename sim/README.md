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

## Modeled behavior

- Independent master and receiver monotonic clocks with offset and rate error.
- Four-timestamp NTP-style exchanges with directional delay, jitter, spikes and loss.
- Lowest-RTT sample selection, robust linear offset fit and skew estimate.
- A sample-index timeline anchored to master monotonic time.
- A DAC clock model and bounded drift-controller decisions.
- Deterministic scenarios so regressions can be reproduced, including a synthetic ten-receiver fan-out.

## Scope and limitations

M0 is a simulation gate. It cannot validate Android scheduler behavior, Wi-Fi chipset behavior, Oboe/AAudio timestamps, DAC presentation semantics, acoustic propagation or speaker hardware. The ten-receiver fan-out uses independent simulated paths; it does not model shared Wi-Fi airtime contention. Values in the scenario report are simulated outputs, not measurements from phones. The estimator is a reference implementation; matching C++ golden vectors and calibration against physical devices remain later work.
