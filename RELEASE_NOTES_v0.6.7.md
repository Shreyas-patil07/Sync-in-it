# Sync In It Android Preview v0.6.7

## Kernel UDP receive timestamp diagnostics

- Adds API 31+ kernel UDP receive timestamps on Host and Speaker using Linux `SO_TIMESTAMPNS_OLD` ancillary data.
- Retains the Java `DatagramSocket` receive path as a fallback and logs which timestamp path is active.
- Adds application receive-capture lag and realtime-to-monotonic mapping uncertainty to probe diagnostics.
- Adds a native C++ replay utility that compares the current application-captured timestamps against an offline kernel receive timestamp substitution.
- Adds a deterministic replay smoke test using the same portable estimator implementation as Android JNI.

## Runtime behavior intentionally unchanged

Kernel timestamps are diagnostic-only in this preview. They are not passed into the live estimator. The packet format, four-timestamp exchange, 60-second estimator window, low-RTT sample selection, minimum sample/span requirements, 0.25 ms residual gate, three-update stability streak, READY/ANCHOR flow and PCM/TCP audio path are unchanged.

## CI validation

- Android debug APK built for `arm64-v8a` and `x86_64`.
- APK signature verification passed.
- Kotlin transport tests passed.
- C++ replay and existing native tests passed across Python 3.11, 3.12 and 3.13.

## Physical-device validation still required

This is an engineering preview, not a stable release. Physical testing on the Xiaomi Android 12 and Android 14 devices is still required to verify kernel ancillary timestamp availability, fallback behavior, capture-lag distributions and whether kernel-corrected replay improves future-offset prediction under real Wi-Fi conditions.

Test both Host/Speaker role directions and retain paired diagnostic exports. Do not infer acoustic synchronization accuracy from a successful build or software timing logs alone.