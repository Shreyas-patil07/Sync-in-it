# M0 golden vectors

`m0_timing.json` is the canonical, versioned test input for the Python reference and portable C++17 implementation. The JSON records frozen expected outputs as well as the inputs needed to reproduce each calculation.

Covered behavior:
- Four-timestamp RTT and master-minus-receiver offset, including negative integer-floor behavior.
- Lowest-RTT clock-offset/skew fitting, sample-window filtering, invalid RTT rejection, insufficient data for skew and selected-fit sample-span reporting.
- Timeline sample-index mapping, pause behavior and ties-to-even rounding.
- DAC frame/time conversion at positive and negative ppm rates.
- Drift-controller dead-band, one-frame correction and hard-resync thresholds.

The Python tests load this JSON file directly. CMake generates a C++ header from this same file using `tools/generate_cpp_vectors.py`; generated files are build artifacts and must not be committed.

Schema changes require incrementing `schema_version` and updating both consumers. Expected values must be reviewed, not mechanically accepted merely because a generator produced them. The selected sample span is measured across the exact low-RTT subset used by the fit and is used by the acquisition-stability gate.
