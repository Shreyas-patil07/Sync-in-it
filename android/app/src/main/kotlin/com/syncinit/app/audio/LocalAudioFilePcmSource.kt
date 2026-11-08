// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes one user-selected audio track and resamples it to the Host's fixed PCM16/48 kHz/stereo
 * transport format. The source loops at end-of-file so a short track cannot exhaust the shared
 * sample timeline while the group is acquiring clock readiness.
 *
 * Calls are serialized: the Host's single audio-producer thread reads frames while close() may be
 * called from the session teardown thread.
 */
internal class LocalAudioFilePcmSource(
    context: Context,
    private val uri: Uri
) : Closeable {
    private val appContext = context.applicationContext
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    private var outputIndex = -1
    private var outputBuffer: ByteBuffer? = null
    private var outputBufferEndsStream = false
    private var outputEosPending = false
    private var inputEos = false
    private var decodedFramesThisPass = 0L

    private var sourceSampleRateHz = 0
    private var sourceChannels = 0
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var bytesPerSample = 2
    private var sourceFrameBytes = 0

    private var resamplerInitialized = false
    private var sourceFrameBaseIndex = 0L
    private var sourcePosition = 0.0
    private val sourceFrame0 = FloatArray(2)
    private val sourceFrame1 = FloatArray(2)

    private var closed = false

    init {
        openDecoder()
    }

    @Synchronized
    fun readFrames(frameCount: Int): ByteArray {
        check(!closed) { "audio source is closed" }
        require(frameCount > 0) { "frame count must be positive" }

        val output = ByteBuffer
            .allocate(frameCount * OUTPUT_CHANNELS * BYTES_PER_OUTPUT_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        val interpolated = FloatArray(2)

        repeat(frameCount) {
            readResampledFrame(interpolated)
            output.putShort(toPcm16(interpolated[0]))
            output.putShort(toPcm16(interpolated[1]))
        }
        return output.array()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        releaseDecoder()
        extractor?.release()
        extractor = null
    }

    private fun readResampledFrame(output: FloatArray) {
        if (!resamplerInitialized) {
            readSourceFrame(sourceFrame0)
            readSourceFrame(sourceFrame1)
            resamplerInitialized = true
            sourceFrameBaseIndex = 0L
            sourcePosition = 0.0
        }

        while (sourcePosition - sourceFrameBaseIndex >= 1.0) {
            sourceFrame0[0] = sourceFrame1[0]
            sourceFrame0[1] = sourceFrame1[1]
            readSourceFrame(sourceFrame1)
            sourceFrameBaseIndex += 1L
        }

        val fraction = (sourcePosition - sourceFrameBaseIndex).toFloat().coerceIn(0f, 1f)
        val inverse = 1f - fraction
        output[0] = sourceFrame0[0] * inverse + sourceFrame1[0] * fraction
        output[1] = sourceFrame0[1] * inverse + sourceFrame1[1] * fraction
        sourcePosition += sourceSampleRateHz.toDouble() / OUTPUT_SAMPLE_RATE_HZ
    }

    private fun readSourceFrame(destination: FloatArray) {
        while (true) {
            val current = outputBuffer
            if (current != null && current.remaining() >= sourceFrameBytes) {
                current.order(ByteOrder.LITTLE_ENDIAN)
                val left = readSample(current)
                val right = if (sourceChannels == 1) left else readSample(current)
                for (channel in 2 until sourceChannels) readSample(current)
                destination[0] = left
                destination[1] = right
                decodedFramesThisPass += 1L
                return
            }

            releaseCurrentOutput()
            if (outputEosPending) {
                if (decodedFramesThisPass == 0L) {
                    throw IOException("Selected audio file contains no decodable audio samples")
                }
                reopenDecoderAtStart()
            }

            feedDecoderInput()
            val activeDecoder = decoder ?: throw IOException("Audio decoder is unavailable")
            when (val index = activeDecoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateOutputFormat(activeDecoder.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (index < 0) {
                        throw IOException("Unexpected MediaCodec output status: $index")
                    }
                    val buffer = activeDecoder.getOutputBuffer(index)
                    outputIndex = index
                    outputBufferEndsStream =
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (buffer == null || bufferInfo.size <= 0) {
                        outputBuffer = null
                        releaseCurrentOutput()
                    } else {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                    }
                }
            }
        }
    }

    private fun feedDecoderInput() {
        if (inputEos) return
        val activeDecoder = decoder ?: throw IOException("Audio decoder is unavailable")
        val activeExtractor = extractor ?: throw IOException("Audio extractor is unavailable")
        val inputIndex = activeDecoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex < 0) return

        val input = activeDecoder.getInputBuffer(inputIndex)
            ?: throw IOException("Audio decoder returned an empty input buffer")
        input.clear()
        val sampleSize = activeExtractor.readSampleData(input, 0)
        if (sampleSize < 0) {
            activeDecoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            inputEos = true
        } else {
            val presentationTimeUs = activeExtractor.sampleTime.coerceAtLeast(0L)
            activeDecoder.queueInputBuffer(
                inputIndex,
                0,
                sampleSize,
                presentationTimeUs,
                0
            )
            activeExtractor.advance()
        }
    }

    private fun releaseCurrentOutput() {
        val index = outputIndex
        if (index < 0) return
        try {
            decoder?.releaseOutputBuffer(index, false)
        } finally {
            outputIndex = -1
            outputBuffer = null
            if (outputBufferEndsStream) {
                outputEosPending = true
                outputBufferEndsStream = false
            }
        }
    }

    private fun reopenDecoderAtStart() {
        releaseCurrentOutput()
        releaseDecoder()
        extractor?.release()
        extractor = null
        openDecoder()
        outputEosPending = false
        inputEos = false
        decodedFramesThisPass = 0L
    }

    private fun openDecoder() {
        val newExtractor = MediaExtractor()
        try {
            newExtractor.setDataSource(appContext, uri, null)
            val audioTrack = (0 until newExtractor.trackCount).firstOrNull { index ->
                newExtractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IOException("Selected file does not contain an audio track")

            newExtractor.selectTrack(audioTrack)
            val trackFormat = newExtractor.getTrackFormat(audioTrack)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Audio track has no MIME type")
            sourceSampleRateHz = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sourceChannels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            requireSupportedSourceFormat()

            val newDecoder = MediaCodec.createDecoderByType(mime)
            try {
                newDecoder.configure(trackFormat, null, null, 0)
                newDecoder.start()
            } catch (error: Exception) {
                newDecoder.release()
                throw error
            }

            extractor = newExtractor
            decoder = newDecoder
            inputEos = false
            outputEosPending = false
            outputIndex = -1
            outputBuffer = null
            outputBufferEndsStream = false
            decodedFramesThisPass = 0L
            sourceFrameBytes = sourceChannels * bytesPerSample
        } catch (error: Exception) {
            newExtractor.release()
            throw IOException(
                "Could not open selected audio file: ${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
    }

    private fun requireSupportedSourceFormat() {
        if (sourceSampleRateHz !in 8_000..192_000) {
            throw IOException("Unsupported audio sample rate: $sourceSampleRateHz Hz")
        }
        if (sourceChannels !in 1..8) {
            throw IOException("Unsupported audio channel count: $sourceChannels")
        }
    }

    private fun updateOutputFormat(format: MediaFormat) {
        sourceSampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

        bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IOException("Unsupported decoded PCM encoding: $pcmEncoding")
        }
        requireSupportedSourceFormat()
        sourceFrameBytes = sourceChannels * bytesPerSample
    }

    private fun readSample(buffer: ByteBuffer): Float {
        val sample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT ->
                ((buffer.get().toInt() and 0xff) - 128) / 128f
            AudioFormat.ENCODING_PCM_16BIT ->
                buffer.short.toInt() / 32768f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val b0 = buffer.get().toInt() and 0xff
                val b1 = buffer.get().toInt() and 0xff
                val b2 = buffer.get().toInt() and 0xff
                var value = b0 or (b1 shl 8) or (b2 shl 16)
                if ((value and 0x800000) != 0) value = value or -0x1000000
                value / 8_388_608f
            }
            AudioFormat.ENCODING_PCM_32BIT ->
                buffer.int / 2_147_483_648f
            AudioFormat.ENCODING_PCM_FLOAT ->
                buffer.float.coerceIn(-1f, 1f)
            else -> throw IOException("Unsupported decoded PCM encoding: $pcmEncoding")
        }
        return sample.coerceIn(-1f, 1f)
    }

    private fun releaseDecoder() {
        val activeDecoder = decoder ?: return
        decoder = null
        try {
            activeDecoder.stop()
        } catch (_: Exception) {
        }
        try {
            activeDecoder.release()
        } catch (_: Exception) {
        }
    }

    private fun toPcm16(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()

    private companion object {
        const val OUTPUT_SAMPLE_RATE_HZ = 48_000
        const val OUTPUT_CHANNELS = 2
        const val BYTES_PER_OUTPUT_SAMPLE = 2
        const val CODEC_TIMEOUT_US = 10_000L
    }
}
