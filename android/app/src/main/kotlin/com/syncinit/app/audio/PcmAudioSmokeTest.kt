// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

object PcmAudioSmokeTest {
    fun run(): String {
        val sessionId = 0x53494954L
        val expected = shortArrayOf(
            1_000, -1_000,
            2_000, -2_000,
            3_000, -3_000,
            4_000, -4_000
        )
        val payload = ByteArray(expected.size * 2)
        expected.forEachIndexed { index, sample ->
            val value = sample.toInt()
            payload[index * 2] = (value and 0xff).toByte()
            payload[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }

        val encoded = NativePcmJitterBuffer.encodeChunk(
            sessionId = sessionId,
            sequence = 0L,
            sampleIndex = 0L,
            frames = 4,
            channels = 2,
            pcm16LittleEndian = payload
        )

        NativePcmJitterBuffer(
            sessionId = sessionId,
            sampleRateHz = 48_000,
            channels = 2,
            capacitySeconds = 1
        ).use { buffer ->
            check(buffer.insertEncodedFrame(encoded) == PcmChunkInsertResult.Inserted) {
                "native PCM decoder did not insert the first chunk"
            }
            check(buffer.insertEncodedFrame(encoded) == PcmChunkInsertResult.Duplicate) {
                "native PCM jitter buffer did not reject a duplicate chunk"
            }
            check(buffer.snapshot().contiguousBufferedFrames == 4L) {
                "native PCM jitter buffer reported an incorrect contiguous frame count"
            }

            val output = ShortArray(expected.size)
            check(buffer.readNext(output) == 0L) {
                "native PCM jitter buffer unexpectedly inserted silence"
            }
            check(output.contentEquals(expected)) {
                "native PCM jitter buffer changed PCM16 sample values"
            }
        }
        return "PCM16 JNI encode/decode and jitter-buffer smoke test passed"
    }
}
