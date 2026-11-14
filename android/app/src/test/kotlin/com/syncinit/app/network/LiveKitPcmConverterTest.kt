// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LiveKitPcmConverterTest {
    @Test
    fun duplicatesMonoSamplesIntoBothOutputChannels() {
        val input = pcm16(1200, -2400, 32767, -32768)

        val output = LiveKitPcmConverter.toStereoPcm16(
            input, bitsPerSample = 16, sampleRateHz = 48_000, channels = 1, frames = 4
        )

        assertArrayEquals(
            pcm16(1200, 1200, -2400, -2400, 32767, 32767, -32768, -32768).array(),
            output
        )
        assertEquals(16, output.size)
    }

    @Test
    fun preservesStereoSamplesAndChannelOrder() {
        val expected = pcm16(100, -100, 200, -200)

        val output = LiveKitPcmConverter.toStereoPcm16(
            expected.asReadOnlyBuffer(),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 2,
            frames = 2
        )

        assertArrayEquals(expected.array(), output)
    }

    @Test
    fun rejectsUnsupportedFormatsAndIncompleteFrames() {
        expectIOException {
            LiveKitPcmConverter.toStereoPcm16(pcm16(1), 8, 48_000, 1, 1)
        }
        expectIOException {
            LiveKitPcmConverter.toStereoPcm16(pcm16(1), 16, 44_100, 1, 1)
        }
        expectIOException {
            LiveKitPcmConverter.toStereoPcm16(pcm16(1), 16, 48_000, 3, 1)
        }
        expectIOException {
            LiveKitPcmConverter.toStereoPcm16(pcm16(1), 16, 48_000, 2, 1)
        }
    }

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }
}
