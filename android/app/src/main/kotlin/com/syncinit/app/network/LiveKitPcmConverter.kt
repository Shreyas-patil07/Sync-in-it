// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts LiveKit's decoded PCM16 callbacks to the app's fixed 48 kHz stereo output format. */
internal object LiveKitPcmConverter {
    fun toStereoPcm16(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRateHz: Int,
        channels: Int,
        frames: Int
    ): ByteArray {
        if (bitsPerSample != 16) {
            throw IOException("Unsupported LiveKit PCM bit depth: $bitsPerSample")
        }
        if (sampleRateHz != SAMPLE_RATE_HZ) {
            throw IOException("Unsupported LiveKit PCM sample rate: $sampleRateHz Hz")
        }
        if (channels !in 1..2) {
            throw IOException("Unsupported LiveKit PCM channel count: $channels")
        }
        if (frames <= 0) {
            throw IOException("LiveKit supplied an empty PCM callback")
        }

        val inputBytes = frames.toLong() * channels * BYTES_PER_SAMPLE
        val outputBytes = frames.toLong() * OUTPUT_CHANNELS * BYTES_PER_SAMPLE
        if (inputBytes > Int.MAX_VALUE || outputBytes > Int.MAX_VALUE) {
            throw IOException("LiveKit supplied an oversized PCM callback")
        }

        val input = audioData.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining().toLong() < inputBytes) {
            throw IOException("LiveKit supplied an incomplete PCM callback buffer")
        }

        val output = ByteBuffer.allocate(outputBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val left = input.short
            val right = if (channels == 1) left else input.short
            output.putShort(left)
            output.putShort(right)
        }
        return output.array()
    }

    private const val SAMPLE_RATE_HZ = 48_000
    private const val OUTPUT_CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
}
