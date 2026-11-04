// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import com.syncinit.app.core.NativeCore
import kotlin.math.roundToLong

object DacClockSmokeTest {
    fun run(): String {
        val handle = NativeCore.nativeCreateDacClock(48_000)
        check(handle != 0L) { "native DAC clock mapper allocation failed" }
        try {
            val baseTimeNs = 1_700_000_000_000_000L
            val rate = 48_000.0 * (1.0 + 50.0 / 1_000_000.0)
            for (index in 0L..20L) {
                val elapsedNs = index * 200_000_000L
                val framePosition = (elapsedNs.toDouble() * rate / 1_000_000_000.0).roundToLong()
                check(
                    NativeCore.nativeAddDacTimestamp(
                        handle,
                        framePosition,
                        baseTimeNs + elapsedNs
                    )
                ) { "native DAC clock mapper rejected an increasing timestamp pair" }
            }

            val snapshot = checkNotNull(NativeCore.nativeDacClockSnapshot(handle)) {
                "native DAC clock mapper did not return a snapshot"
            }
            check(snapshot.size == 9 && snapshot[0] == 1.0) {
                "native DAC clock mapper did not produce a stable fit"
            }
            check(kotlin.math.abs(snapshot[1] - 50.0) < 1.0) {
                "native DAC clock mapper produced an incorrect rate estimate"
            }
            check(snapshot[3].toInt() == 21) {
                "native DAC clock mapper reported an incorrect sample count"
            }
        } finally {
            NativeCore.nativeCloseDacClock(handle)
        }
        return "DAC timestamp regression JNI smoke test passed"
    }
}
