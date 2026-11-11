// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import com.syncinit.app.PreviewConfig

import com.syncinit.app.core.NativeCore
import java.io.Closeable

enum class PcmChunkInsertResult(val code: Int) {
    Inserted(0),
    PartialLate(1),
    Duplicate(2),
    TooLate(3),
    OutOfWindow(4),
    InvalidChunk(5),
    WindowAdvanced(6)
}

data class PcmBufferSnapshot(
    val contiguousBufferedFrames: Long,
    val nextSampleIndex: Long,
    val missingFrames: Long,
    val windowAdvanceCount: Long
)

class NativePcmJitterBuffer(
    val sessionId: Long,
    val sampleRateHz: Int,
    val channels: Int,
    capacitySeconds: Int = PreviewConfig.PCM_BUFFER_SECONDS
) : Closeable {
    @Volatile private var handle: Long

    init {
        require(sessionId in 0L..0xffff_ffffL)
        require(sampleRateHz in 8_000..192_000)
        require(channels in 1..2)
        require(capacitySeconds in 1..PreviewConfig.PCM_BUFFER_SECONDS)
        val capacityFrames = Math.multiplyExact(sampleRateHz, capacitySeconds)
        require(capacityFrames in 1..PreviewConfig.MAX_PCM_BUFFER_FRAMES) {
            "native PCM jitter buffer capacity is limited to 960,000 frames"
        }
        handle = NativeCore.nativeCreatePcmBuffer(capacityFrames.toLong(), channels, sessionId)
        check(handle != 0L) { "native PCM jitter buffer allocation failed" }
    }

    fun insertEncodedFrame(encodedFrame: ByteArray): PcmChunkInsertResult {
        val current = checkOpen()
        val result = NativeCore.nativeInsertPcmFrame(current, encodedFrame.copyOf())
        return PcmChunkInsertResult.entries.firstOrNull { it.code == result }
            ?: error("Unknown native PCM insert result: $result")
    }

    fun seekToSampleIndex(firstSampleIndex: Long) {
        require(firstSampleIndex >= 0L) { "PCM seek sample index must be non-negative" }
        NativeCore.nativeSeekPcmBuffer(checkOpen(), firstSampleIndex)
    }

    fun snapshot(): PcmBufferSnapshot {
        val values = checkNotNull(NativeCore.nativePcmBufferStats(checkOpen())) {
            "native PCM buffer statistics are unavailable"
        }
        check(values.size == 4) { "native PCM buffer returned invalid statistics" }
        return PcmBufferSnapshot(values[0], values[1], values[2], values[3])
    }

    fun bufferedMillis(): Long =
        snapshot().contiguousBufferedFrames * 1_000L / sampleRateHz.toLong()

    fun readNext(output: ShortArray): Long {
        require(output.isNotEmpty() && output.size % channels == 0) {
            "PCM output array must contain complete channel frames"
        }
        return NativeCore.nativeReadPcmBuffer(checkOpen(), output)
    }

    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            NativeCore.nativeClosePcmBuffer(current)
        }
    }

    private fun checkOpen(): Long {
        val current = handle
        check(current != 0L) { "native PCM jitter buffer is closed" }
        return current
    }

    companion object {
        fun encodeChunk(
            sessionId: Long,
            sequence: Long,
            sampleIndex: Long,
            frames: Int,
            channels: Int,
            pcm16LittleEndian: ByteArray
        ): ByteArray = checkNotNull(
            NativeCore.nativeEncodePcmChunkFrame(
                sessionId,
                sequence,
                sampleIndex,
                frames,
                channels,
                pcm16LittleEndian
            )
        ) { "native PCM chunk encoder returned no frame" }
    }
}
