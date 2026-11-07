// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.core

data class ClockSyncMeasurement(
    val offsetNs: Double,
    val receiverSkewPpm: Double?,
    val rttMedianMs: Double,
    val residualRmsMs: Double,
    val samplesUsed: Int,
    val samplesSeen: Int,
    val selectedSampleSpanNs: Long,
    val stableStreak: Int
) {
    val acquisitionStable: Boolean get() = stableStreak >= 3
}

data class ClockSyncProbe(
    val sequence: Long,
    val t1Ns: Long,
    val t2Ns: Long,
    val t3Ns: Long,
    val t4Ns: Long,
    val roundTripNs: Long,
    val rawOffsetNs: Long
)

/** Thread-confined wrapper over the shared C++ time-sync correlator and clock estimator. */
class NativeTimeSyncClient private constructor(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun makeRequest(t1Ns: Long): ByteArray {
        checkOpen()
        return checkNotNull(NativeCore.nativeMakeTimeSyncRequest(handle, t1Ns)) {
            "native time-sync request could not be created"
        }
    }

    @Synchronized
    fun expireRequestsBefore(monotonicNs: Long) {
        checkOpen()
        NativeCore.nativeExpireTimeSyncRequests(handle, monotonicNs)
    }

    @Synchronized
    fun acceptResponse(packet: ByteArray, t4Ns: Long): ClockSyncMeasurement? {
        checkOpen()
        require(packet.size == 38) { "time-sync response must be exactly 38 bytes" }
        val values = NativeCore.nativeAcceptTimeSyncResponse(handle, packet.copyOf(), t4Ns)
            ?: return null
        check(values.size == 8) { "native clock estimator returned an invalid field count" }
        val skew = values[1].takeIf { it.isFinite() }
        return ClockSyncMeasurement(
            offsetNs = values[0],
            receiverSkewPpm = skew,
            rttMedianMs = values[2],
            residualRmsMs = values[3],
            samplesUsed = values[4].toInt(),
            samplesSeen = values[5].toInt(),
            selectedSampleSpanNs = values[6].toLong(),
            stableStreak = values[7].toInt()
        )
    }

    @Synchronized
    fun lastAcceptedProbe(): ClockSyncProbe? {
        checkOpen()
        val values = NativeCore.nativeLastTimeSyncExchange(handle) ?: return null
        check(values.size == 7) { "native clock probe returned an invalid field count" }
        return ClockSyncProbe(
            sequence = values[0],
            t1Ns = values[1],
            t2Ns = values[2],
            t3Ns = values[3],
            t4Ns = values[4],
            roundTripNs = values[5],
            rawOffsetNs = values[6]
        )
    }

    @Synchronized
    fun lastResponseStatus(): String {
        checkOpen()
        return NativeCore.nativeLastTimeSyncResponseStatus(handle) ?: "STATUS_UNAVAILABLE"
    }

    @Synchronized
    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            NativeCore.nativeCloseTimeSyncClient(current)
        }
    }

    private fun checkOpen() {
        check(handle != 0L) { "native time-sync client is closed" }
    }

    companion object {
        fun create(sessionId: Long, receiverId: Int): NativeTimeSyncClient {
            require(sessionId in 0L..0xffff_ffffL)
            require(receiverId in 0..65_535)
            val handle = NativeCore.nativeCreateTimeSyncClient(sessionId, receiverId)
            check(handle != 0L) { "native time-sync client allocation failed" }
            return NativeTimeSyncClient(handle)
        }
    }
}
