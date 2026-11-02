// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.syncinit.app.core.ClockSyncMeasurement
import com.syncinit.app.core.NativeCore
import com.syncinit.app.core.NativeProtocol
import com.syncinit.app.core.WelcomeParameters
import com.syncinit.transport.TcpFrameConnection
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class PcmChunkInsertResult(val code: Int) {
    Inserted(0),
    PartialLate(1),
    Duplicate(2),
    TooLate(3),
    OutOfWindow(4),
    InvalidChunk(5)
}

data class PcmBufferSnapshot(
    val contiguousBufferedFrames: Long,
    val nextSampleIndex: Long,
    val missingFrames: Long
)

class NativePcmJitterBuffer(
    val sessionId: Long,
    val sampleRateHz: Int,
    val channels: Int,
    capacitySeconds: Int = 5
) : Closeable {
    @Volatile private var handle: Long

    init {
        require(sessionId in 0L..0xffff_ffffL)
        require(sampleRateHz in 8_000..192_000)
        require(channels in 1..2)
        require(capacitySeconds in 1..10)
        val capacityFrames = Math.multiplyExact(sampleRateHz, capacitySeconds)
        handle = NativeCore.nativeCreatePcmBuffer(capacityFrames.toLong(), channels, sessionId)
        check(handle != 0L) { "native PCM jitter buffer allocation failed" }
    }

    fun insertEncodedFrame(encodedFrame: ByteArray): PcmChunkInsertResult {
        val current = checkOpen()
        val result = NativeCore.nativeInsertPcmFrame(current, encodedFrame.copyOf())
        return PcmChunkInsertResult.entries.firstOrNull { it.code == result }
            ?: error("Unknown native PCM insert result: $result")
    }

    fun snapshot(): PcmBufferSnapshot {
        val values = checkNotNull(NativeCore.nativePcmBufferStats(checkOpen())) {
            "native PCM buffer statistics are unavailable"
        }
        check(values.size == 3) { "native PCM buffer returned invalid statistics" }
        return PcmBufferSnapshot(values[0], values[1], values[2])
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

private class AndroidPcmAudioOutput(
    private val buffer: NativePcmJitterBuffer,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var writer: Thread? = null

    fun start() {
        check(!closed.get()) { "audio output is closed" }
        check(writer == null) { "audio output has already started" }

        val channelMask = if (buffer.channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minimumBytes = AudioTrack.getMinBufferSize(
            buffer.sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimumBytes > 0) { "AudioTrack does not support the negotiated PCM format" }

        val chunkBytes = 960 * buffer.channels * 2
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(buffer.sampleRateHz)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minimumBytes * 2, chunkBytes * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            "AudioTrack initialization failed"
        }
        track = audioTrack
        audioTrack.play()

        val thread = Thread({ writeLoop(audioTrack) }, "syncinit-audiotrack-writer").apply {
            isDaemon = true
        }
        writer = thread
        thread.start()
    }

    private fun writeLoop(audioTrack: AudioTrack) {
        val samples = ShortArray(960 * buffer.channels)
        try {
            while (!closed.get()) {
                buffer.readNext(samples)
                var offset = 0
                while (offset < samples.size && !closed.get()) {
                    val written = audioTrack.write(
                        samples,
                        offset,
                        samples.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written < 0) throw IOException("AudioTrack write failed with code $written")
                    if (written == 0) {
                        Thread.sleep(1)
                    } else {
                        offset += written
                    }
                }
            }
        } catch (error: InterruptedException) {
            if (!closed.get()) Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed.get()) onStatus("Audio output stopped: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writer?.interrupt()
        val current = track
        track = null
        if (current != null) {
            try { current.pause() } catch (_: IllegalStateException) { }
            try { current.flush() } catch (_: IllegalStateException) { }
            try { current.stop() } catch (_: IllegalStateException) { }
            current.release()
        }
        writer = null
    }
}

class PcmAudioReceiver(
    private val audioConnection: TcpFrameConnection,
    private val controlConnection: TcpFrameConnection,
    private val welcome: WelcomeParameters,
    private val onStatus: (String) -> Unit,
    private val onFailure: (Exception) -> Unit
) : Closeable {
    companion object {
        private const val MIN_READY_BUFFER_MS = 3_000L
        private const val PREFILL_BUFFER_MS = 3_500L
    }

    private val closed = AtomicBoolean(false)
    private val playbackStarted = AtomicBoolean(false)
    private val readySent = AtomicBoolean(false)
    private val latestClock = AtomicReference<ClockSyncMeasurement?>(null)
    private val buffer: NativePcmJitterBuffer
    private val output: AndroidPcmAudioOutput
    @Volatile private var reader: Thread? = null

    init {
        require(welcome.sampleRateHz == 48_000) { "unsupported sample rate ${welcome.sampleRateHz}" }
        require(welcome.channels == 2) { "only stereo PCM16 is supported by this Android preview" }
        require(welcome.codec == 0) { "unsupported audio codec ${welcome.codec}" }
        require(welcome.chunkFrames == 960) { "unsupported audio chunk size ${welcome.chunkFrames}" }
        buffer = NativePcmJitterBuffer(
            sessionId = welcome.sessionId,
            sampleRateHz = welcome.sampleRateHz,
            channels = welcome.channels
        )
        output = AndroidPcmAudioOutput(buffer, onStatus)
    }

    fun start() {
        check(!closed.get()) { "PCM audio receiver is closed" }
        check(reader == null) { "PCM audio receiver has already started" }
        val thread = Thread(::readAudioFrames, "syncinit-audio-network-reader").apply {
            isDaemon = true
        }
        reader = thread
        thread.start()
    }

    fun updateClockMeasurement(measurement: ClockSyncMeasurement) {
        if (closed.get()) return
        latestClock.set(measurement)
        tryStartPlayback()
        trySendReady()
    }

    private fun readAudioFrames() {
        try {
            while (!closed.get()) {
                val frame = audioConnection.readFrame() ?: throw IOException("master closed the audio channel")
                if (frame.messageType != 0x80 || frame.flags != 0) {
                    throw IOException("unexpected frame on the audio channel")
                }
                when (buffer.insertEncodedFrame(frame.toEncodedBytes())) {
                    PcmChunkInsertResult.Inserted,
                    PcmChunkInsertResult.PartialLate,
                    PcmChunkInsertResult.Duplicate,
                    PcmChunkInsertResult.TooLate -> Unit
                    PcmChunkInsertResult.OutOfWindow ->
                        onStatus("Audio buffer rejected a chunk outside its bounded sample window.")
                    PcmChunkInsertResult.InvalidChunk ->
                        throw IOException("received an invalid PCM16 audio chunk")
                }
                tryStartPlayback()
                trySendReady()
            }
        } catch (error: Exception) {
            if (!closed.get()) onFailure(error)
        }
    }

    private fun tryStartPlayback() {
        if (closed.get() || playbackStarted.get()) return
        if (buffer.bufferedMillis() < PREFILL_BUFFER_MS) return
        if (!playbackStarted.compareAndSet(false, true)) return
        try {
            output.start()
            onStatus("PCM16 audio is playing through AudioTrack · 48 kHz stereo · 3.5 s prebuffer.")
        } catch (error: Exception) {
            playbackStarted.set(false)
            throw error
        }
    }

    private fun trySendReady() {
        if (closed.get() || readySent.get() || !playbackStarted.get()) return
        val measurement = latestClock.get() ?: return
        if (!measurement.acquisitionStable) return

        val bufferedMs = buffer.bufferedMillis()
        if (bufferedMs < MIN_READY_BUFFER_MS) return
        if (!readySent.compareAndSet(false, true)) return

        try {
            val residualUs = (measurement.residualRmsMs * 1_000.0)
                .toLong()
                .coerceIn(0L, 0xffff_ffffL)
            controlConnection.writeEncodedFrame(
                NativeProtocol.encodeReadyFrame(
                    bufferedMs = bufferedMs.coerceAtMost(0xffff_ffffL),
                    syncStable = true,
                    residualStdUs = residualUs
                )
            )
            onStatus(
                "Clock acquisition stable · ${bufferedMs} ms buffered · READY sent. " +
                    "Playback is active, but scheduled group start is not implemented yet."
            )
        } catch (error: Exception) {
            readySent.set(false)
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        audioConnection.close()
        output.close()
        buffer.close()
        reader?.interrupt()
        reader = null
    }
}
