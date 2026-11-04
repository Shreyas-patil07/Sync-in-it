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

        // Exercise the native rolling pre-roll path through JNI. A one-second
        // buffer must keep accepting live PCM before ANCHOR, then become strict
        // after the playback cursor is moved to the shared sample index.
        NativePcmJitterBuffer(
            sessionId = sessionId + 1L,
            sampleRateHz = 48_000,
            channels = 2,
            capacitySeconds = 1
        ).use { rolling ->
            val rollingPayload = ByteArray(960 * 2 * 2)
            repeat(50) { index ->
                val encodedChunk = NativePcmJitterBuffer.encodeChunk(
                    sessionId = sessionId + 1L,
                    sequence = index.toLong(),
                    sampleIndex = index * 960L,
                    frames = 960,
                    channels = 2,
                    pcm16LittleEndian = rollingPayload
                )
                check(rolling.insertEncodedFrame(encodedChunk) == PcmChunkInsertResult.Inserted) {
                    "native PCM pre-roll rejected an in-window chunk at index $index"
                }
            }
            val overflowChunk = NativePcmJitterBuffer.encodeChunk(
                sessionId = sessionId + 1L,
                sequence = 50L,
                sampleIndex = 48_000L,
                frames = 960,
                channels = 2,
                pcm16LittleEndian = rollingPayload
            )
            check(rolling.insertEncodedFrame(overflowChunk) == PcmChunkInsertResult.WindowAdvanced) {
                "native PCM pre-roll did not advance its bounded rolling window"
            }
            val beforeAnchor = rolling.snapshot()
            check(beforeAnchor.windowAdvanceCount == 1L && beforeAnchor.nextSampleIndex == 960L) {
                "native PCM pre-roll diagnostics did not report the expected cursor advance"
            }
            check(beforeAnchor.contiguousBufferedFrames == 48_000L) {
                "native PCM pre-roll did not retain a full contiguous one-second window"
            }

            rolling.seekToSampleIndex(48_000L)
            val nextChunk = NativePcmJitterBuffer.encodeChunk(
                sessionId = sessionId + 1L,
                sequence = 51L,
                sampleIndex = 48_960L,
                frames = 960,
                channels = 2,
                pcm16LittleEndian = rollingPayload
            )
            check(rolling.insertEncodedFrame(nextChunk) == PcmChunkInsertResult.Inserted) {
                "native PCM stream failed after the playback anchor"
            }
            val tooFarAhead = NativePcmJitterBuffer.encodeChunk(
                sessionId = sessionId + 1L,
                sequence = 52L,
                sampleIndex = 100_000L,
                frames = 960,
                channels = 2,
                pcm16LittleEndian = rollingPayload
            )
            check(rolling.insertEncodedFrame(tooFarAhead) == PcmChunkInsertResult.OutOfWindow) {
                "native PCM buffer silently advanced after playback had started"
            }
        }
        return "PCM16 JNI codec, rolling pre-roll and post-anchor window smoke tests passed"
    }
}
