// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.syncinit.app.core.ClockSyncMeasurement
import com.syncinit.app.core.NativeCore
import com.syncinit.app.core.NativeProtocol
import com.syncinit.app.core.WelcomeParameters
import com.syncinit.transport.TcpFrameConnection
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    capacitySeconds: Int = 20
) : Closeable {
    @Volatile private var handle: Long

    init {
        require(sessionId in 0L..0xffff_ffffL)
        require(sampleRateHz in 8_000..192_000)
        require(channels in 1..2)
        require(capacitySeconds in 1..20)
        val capacityFrames = Math.multiplyExact(sampleRateHz, capacitySeconds)
        require(capacityFrames in 1..960_000) {
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

private class AndroidPcmAudioOutput(
    private val buffer: NativePcmJitterBuffer,
    private val onStatus: (String) -> Unit,
    private val onFailure: (Exception) -> Unit
) : Closeable {
    companion object {
        private const val CHUNK_FRAMES = 960
    }

    private val closed = AtomicBoolean(false)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var writer: Thread? = null
    @Volatile private var timestampPoller: DacClockTimestampPoller? = null

    fun startAt(masterAlignedLocalTimeNs: Long) {
        check(!closed.get()) { "audio output is closed" }
        check(writer == null) { "audio output has already started" }
        check(masterAlignedLocalTimeNs > System.nanoTime()) {
            "scheduled group-start time has already passed"
        }

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

        val chunkBytes = CHUNK_FRAMES * buffer.channels * 2
        val trackBufferBytes = maxOf(minimumBytes * 2, chunkBytes * 4)
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
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            "AudioTrack initialization failed"
        }
        track = audioTrack

        val thread = Thread(
            { writeLoop(audioTrack, masterAlignedLocalTimeNs) },
            "syncinit-audiotrack-writer"
        ).apply { isDaemon = true }
        writer = thread
        thread.start()
    }

    private fun writeLoop(audioTrack: AudioTrack, startAtNs: Long) {
        val playbackSamples = ShortArray(CHUNK_FRAMES * buffer.channels)
        try {
            val requiredPrerollFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioTrack.startThresholdInFrames
            } else {
                audioTrack.bufferCapacityInFrames
            }
            check(requiredPrerollFrames > 0) { "AudioTrack reported an invalid start threshold" }

            var primedFrames = 0
            // MODE_STREAM can be primed before play(). Fill the device-specific start threshold
            // so play() does not wait for additional frames after the shared T0.
            while (!closed.get() && primedFrames < requiredPrerollFrames) {
                val framesToWrite = minOf(CHUNK_FRAMES, requiredPrerollFrames - primedFrames)
                val bufferedMs = buffer.bufferedMillis()
                val requiredBufferedMs =
                    framesToWrite.toLong() * 1_000L / buffer.sampleRateHz.toLong()
                if (bufferedMs < requiredBufferedMs) {
                    Thread.sleep(1)
                    continue
                }

                val prerollSamples = ShortArray(framesToWrite * buffer.channels)
                buffer.readNext(prerollSamples)
                writeFully(audioTrack, prerollSamples, AudioTrack.WRITE_NON_BLOCKING)
                primedFrames += framesToWrite
            }

            if (closed.get()) return
            waitUntil(startAtNs)
            if (closed.get()) return
            audioTrack.play()
            onStatus(
                "Group playback started at the scheduled local clock target · " +
                    "$primedFrames PCM frames preloaded."
            )
            timestampPoller = DacClockTimestampPoller(buffer.sampleRateHz, onStatus).also {
                it.start(audioTrack)
            }

            while (!closed.get()) {
                buffer.readNext(playbackSamples)
                writeFully(audioTrack, playbackSamples, AudioTrack.WRITE_BLOCKING)
            }
        } catch (error: InterruptedException) {
            if (!closed.get()) Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed.get()) {
                onStatus("Audio output stopped: ${error.message ?: error.javaClass.simpleName}")
                onFailure(error)
            }
        }
    }

    private fun writeFully(audioTrack: AudioTrack, samples: ShortArray, mode: Int) {
        var offset = 0
        while (offset < samples.size && !closed.get()) {
            val written = audioTrack.write(
                samples,
                offset,
                samples.size - offset,
                mode
            )
            if (written < 0) throw IOException("AudioTrack write failed with code $written")
            if (written == 0) {
                if (mode == AudioTrack.WRITE_NON_BLOCKING) {
                    throw IOException("AudioTrack reached its pre-start buffer limit before the start threshold was filled")
                }
                Thread.sleep(1)
            } else {
                offset += written
            }
        }
    }

    private fun waitUntil(targetNs: Long) {
        while (!closed.get()) {
            val remainingNs = targetNs - System.nanoTime()
            if (remainingNs <= 0L) return
            if (remainingNs > 3_000_000L) {
                Thread.sleep((remainingNs - 1_000_000L) / 1_000_000L)
            } else {
                Thread.yield()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timestampPoller?.close()
        timestampPoller = null
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
        private const val READY_RETRY_INTERVAL_NS = 1_000_000_000L
    }

    private val closed = AtomicBoolean(false)
    private val anchorReceived = AtomicBoolean(false)
    private val readySent = AtomicBoolean(false)
    private val lastReadySentAtNs = AtomicLong(0L)
    private val lastReadyGateDiagnosticAtNs = AtomicLong(0L)
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
        output = AndroidPcmAudioOutput(buffer, onStatus, onFailure)
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
        trySendReady()
    }

    private fun readAudioFrames() {
        var lastReportedWindowAdvance = 0L
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
                    PcmChunkInsertResult.WindowAdvanced -> {
                        val snapshot = buffer.snapshot()
                        val advances = snapshot.windowAdvanceCount
                        if (lastReportedWindowAdvance == 0L || advances - lastReportedWindowAdvance >= 250L) {
                            lastReportedWindowAdvance = advances
                            onStatus(
                                "PCM pre-roll rolling window active · advances=$advances · " +
                                    "cursor=${snapshot.nextSampleIndex} · " +
                                    "buffered=${snapshot.contiguousBufferedFrames * 1_000L / welcome.sampleRateHz} ms."
                            )
                        }
                    }
                    PcmChunkInsertResult.OutOfWindow -> {
                        val snapshot = buffer.snapshot()
                        val phase = if (anchorReceived.get()) "during playback" else "before the group ANCHOR"
                        throw IOException(
                            "PCM jitter buffer overrun $phase; cursor=${snapshot.nextSampleIndex}, " +
                                "buffered=${snapshot.contiguousBufferedFrames * 1_000L / welcome.sampleRateHz} ms, " +
                                "windowAdvances=${snapshot.windowAdvanceCount}. " +
                                "The stream exceeded the bounded 20-second receive window."
                        )
                    }
                    PcmChunkInsertResult.InvalidChunk ->
                        throw IOException("received an invalid PCM16 audio chunk")
                }
                trySendReady()
            }
        } catch (error: Exception) {
            if (!closed.get()) onFailure(error)
        }
    }

    private fun trySendReady() {
        if (closed.get() || anchorReceived.get()) return

        val measurement = latestClock.get()
        val snapshot = buffer.snapshot()
        val bufferedMs = snapshot.contiguousBufferedFrames * 1_000L / welcome.sampleRateHz.toLong()
        val clockStable = measurement?.acquisitionStable == true
        val bufferReady = bufferedMs >= MIN_READY_BUFFER_MS

        if (!clockStable || !bufferReady) {
            val nowNs = System.nanoTime()
            val previousDiagnosticNs = lastReadyGateDiagnosticAtNs.get()
            if ((previousDiagnosticNs == 0L || nowNs - previousDiagnosticNs >= 1_000_000_000L) &&
                lastReadyGateDiagnosticAtNs.compareAndSet(previousDiagnosticNs, nowNs)) {
                val gate = when {
                    measurement == null -> "CLOCK_ESTIMATE_UNAVAILABLE"
                    !clockStable && !bufferReady -> "CLOCK_AND_PCM_INSUFFICIENT"
                    !clockStable -> "CLOCK_UNSTABLE"
                    else -> "PCM_BUFFER_INSUFFICIENT"
                }
                onStatus(
                    "DIAG READY_GATE_BLOCKED gate=$gate " +
                        "clockStable=$clockStable " +
                        "samplesUsed=${measurement?.samplesUsed ?: 0} " +
                        "samplesSeen=${measurement?.samplesSeen ?: 0} " +
                        "sampleSpanMs=${measurement?.selectedSampleSpanNs?.div(1_000_000L) ?: 0L} " +
                        "stableStreak=${measurement?.stableStreak ?: 0} " +
                        "residualRmsMs=${measurement?.residualRmsMs?.let { "%.4f".format(it) } ?: "unavailable"} " +
                        "rttMedianMs=${measurement?.rttMedianMs?.let { "%.2f".format(it) } ?: "unavailable"} " +
                        "bufferedMs=$bufferedMs requiredMs=$MIN_READY_BUFFER_MS " +
                        "contiguousFrames=${snapshot.contiguousBufferedFrames} " +
                        "missingFrames=${snapshot.missingFrames} " +
                        "windowAdvances=${snapshot.windowAdvanceCount}"
                )
            }
            return
        }

        // There is no READY acknowledgement in the current wire protocol.
        // Retry periodically until ANCHOR arrives; the Host state machine accepts
        // duplicate READY frames idempotently and can recover from a transient gate.
        val nowNs = System.nanoTime()
        val previousSentAtNs = lastReadySentAtNs.get()
        if (previousSentAtNs != 0L && nowNs - previousSentAtNs < READY_RETRY_INTERVAL_NS) return
        if (!lastReadySentAtNs.compareAndSet(previousSentAtNs, nowNs)) return

        val firstReady = readySent.compareAndSet(false, true)
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
                "Clock acquisition stable · ${bufferedMs} ms buffered · " +
                    if (firstReady) {
                        "READY sent; retrying every 1 second until the group ANCHOR."
                    } else {
                        "READY refreshed; waiting for the master's coordinated playback anchor."
                    }
            )
        } catch (error: Exception) {
            if (firstReady) readySent.compareAndSet(true, false)
            lastReadySentAtNs.compareAndSet(nowNs, previousSentAtNs)
            throw error
        }
    }

    fun onPlaybackAnchor(anchor: com.syncinit.app.core.PlaybackAnchor) {
        if (closed.get()) return
        require(anchor.kind == 0 && anchor.playing) {
            "only a start ANCHOR is supported by this Android preview"
        }
        check(readySent.get()) { "master sent a start ANCHOR before this receiver was READY" }
        val measurement = latestClock.get()
            ?: throw IOException("clock estimate is unavailable for the start ANCHOR")
        check(measurement.acquisitionStable) {
            "clock estimate is no longer stable enough for scheduled playback"
        }

        val localStartNsDouble = anchor.masterTimeNs.toDouble() - measurement.offsetNs
        check(localStartNsDouble.isFinite() &&
            localStartNsDouble >= 0.0 &&
            localStartNsDouble <= Long.MAX_VALUE.toDouble()) {
            "start ANCHOR cannot be mapped into the receiver's monotonic clock"
        }
        val localStartNs = localStartNsDouble.toLong()
        check(localStartNs - System.nanoTime() >= 100_000_000L) {
            "start ANCHOR arrived too close to or after its scheduled start"
        }
        if (!anchorReceived.compareAndSet(false, true)) return

        try {
            val beforeAnchor = buffer.snapshot()
            buffer.seekToSampleIndex(anchor.sampleIndex)
            output.startAt(localStartNs)
            onStatus(
                "Group start armed · sample ${anchor.sampleIndex} · " +
                    "pre-roll advances=${beforeAnchor.windowAdvanceCount} · " +
                    "${(localStartNs - System.nanoTime()) / 1_000_000L} ms until target."
            )
        } catch (error: Exception) {
            anchorReceived.set(false)
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
