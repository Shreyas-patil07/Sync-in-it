# Sync In It Android Preview v0.6.1

## Diagnostics

- Persistent app-private diagnostic history at `files/diagnostics/sync-in-it-diagnostics.txt`.
- Rolling log is bounded to 4 MB; the on-screen debugger remains a 240-event preview.
- **Save diagnostic report (.txt)** uses Android's document picker so the user can save the retained history to Downloads or another destination.
- Clock diagnostics include exact accepted `t1/t2/t3/t4`, corrected RTT, raw offset, `CLOCK_PROBE` and `CLOCK_FIT` records.
- Token, password, secret and API-key assignments are redacted. Audio is not included.

## Validation and limitations

- M0 simulator/native matrix passed on Python 3.11, 3.12 and 3.13 with C++17.
- Android/JNI build, transport checks and preview APK signature verification passed.
- The clock estimator, retry cadence and <0.25 ms residual-RMS readiness gate were not changed.
- Physical READY/ANCHOR and acoustic synchronization remain unverified. This is an engineering pre-release, not a claim that synchronized playback is fixed.
- Diagnostic reports can contain device model, local network addresses, session IDs and timing metadata. Review before sharing.
