// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack as PlatformAudioTrack
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import io.livekit.android.room.track.RemoteAudioTrack
import livekit.org.webrtc.AudioTrackSink

/**
 * Replays decoded LiveKit PCM through a local AudioTrack at a shared monotonic-clock target.
 * LiveKit remains the transport; CloudSpeakerRoom mutes its built-in speaker output.
 */
internal class SynchronizedPcmPlayer(
    private val serverMinusLocalClockOffsetNs: AtomicLong,
    private val onStatus: (String) -> Unit,
    private val onPlaybackStarted: () -> Unit,
    private val onPlaybackDegraded: (String) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val playbackStarted = AtomicBoolean(false)
    private val primedStatusSent = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val failure = AtomicReference<Throwable?>(null)
    private val writtenBytes = AtomicLong(0L)
    private val pendingWriteBytes = AtomicLong(0L)
    private val remoteTrackRef = AtomicReference<RemoteAudioTrack?>(null)
    private val outputRef = AtomicReference<PlatformAudioTrack?>(null)
    private var writerThread: Thread? = null
    private var schedulerThread: Thread? = null
    private var driftThread: Thread? = null

    private val sink = object : AudioTrackSink {
        override fun onData(
            audioData: ByteBuffer,
            bitsPerSample: Int,
            sampleRate: Int,
            numberOfChannels: Int,
            numberOfFrames: Int,
            absoluteCaptureTimestampMs: Long
        ) {
            if (closed.get() || failure.get() != null) return
            if (bitsPerSample != 16 || sampleRate != SAMPLE_RATE_HZ ||
                numberOfChannels != CHANNELS || numberOfFrames <= 0
            ) {
                fail(
                    IOException(
                        "LiveKit PCM format mismatch: bits=" + bitsPerSample +
                            " rate=" + sampleRate + " channels=" + numberOfChannels
                    )
                )
                return
            }
            val byteCount = numberOfFrames.toLong() * BYTES_PER_FRAME
            if (byteCount > MAX_CALLBACK_BYTES) {
                fail(IOException("LiveKit supplied an oversized PCM callback"))
                return
            }
            val view = audioData.duplicate()
            if (view.remaining().toLong() < byteCount) {
                fail(IOException("LiveKit supplied an incomplete PCM callback buffer"))
                return
            }
            val copy = ByteArray(byteCount.toInt())
            view.get(copy)
            if (!queue.offer(copy)) {
                fail(IOException("Synchronized Speaker PCM queue overflowed before playout"))
            }
        }
    }

    @Synchronized
    fun attach(track: RemoteAudioTrack) {
        check(!closed.get()) { "synchronized PCM player is closed" }
        failure.get()?.let {
            throw IOException("Synchronized PCM output cannot recover after a playback failure", it)
        }

        val currentTrack = remoteTrackRef.get()
        if (currentTrack === track) return
        if (playbackStarted.get()) {
            val error = IOException(
                "The LiveKit audio track changed during playback. Rejoin and restart synchronized playback."
            )
            fail(error)
            throw error
        }

        currentTrack?.let { runCatching { it.removeSink(sink) } }
        remoteTrackRef.set(null)
        if (outputRef.get() == null) outputRef.set(createOutputTrack())
        track.addSink(sink)
        remoteTrackRef.set(track)
        if (writerThread == null) {
            writerThread = Thread(
                { writeAudioLoop() },
                "syncinit-synchronized-audio-write"
            ).apply {
                isDaemon = true
                start()
            }
        }
        onStatus("Synchronized PCM output attached · waiting for the Host playback schedule.")
    }

    @Synchronized
    fun detach(track: RemoteAudioTrack) {
        if (!remoteTrackRef.compareAndSet(track, null)) return
        runCatching { track.removeSink(sink) }
        if (playbackStarted.get()) {
            fail(
                IOException(
                    "The LiveKit audio track was interrupted during playback. " +
                        "Rejoin and restart the synchronized session to restore alignment."
                )
            )
        }
    }

    fun hasAttachedTrack(): Boolean = remoteTrackRef.get() != null

    fun hasPlaybackStarted(): Boolean = playbackStarted.get()

    fun markTransportInterrupted(detail: String) {
        if (playbackStarted.get()) fail(IOException(detail))
    }

    fun schedule(serverStartAtNs: Long) {
        check(!closed.get()) { "synchronized PCM player is closed" }
        check(remoteTrackRef.get() != null && outputRef.get() != null) {
            "LiveKit audio sink is not ready"
        }
        val offsetNs = serverMinusLocalClockOffsetNs.get()
        check(offsetNs != Long.MIN_VALUE) { "shared clock estimate is unavailable" }
        val localTargetNs = serverStartAtNs - offsetNs
        check(localTargetNs - System.nanoTime() >= MINIMUM_SCHEDULE_LEAD_NS) {
            "playback schedule arrived too late to arm the local audio output"
        }
        check(scheduled.compareAndSet(false, true)) {
            "a synchronized playback schedule is already armed"
        }
        onStatus("Synchronized playback armed · waiting for the shared start instant.")

        schedulerThread = Thread({
            try {
                while (!closed.get() && !cancelled.get()) {
                    val remainingNs = localTargetNs - System.nanoTime()
                    if (remainingNs <= 0L) break
                    LockSupport.parkNanos(min(remainingNs, MAX_PARK_NS))
                }
                if (closed.get() || cancelled.get()) return@Thread
                failure.get()?.let { throw IOException("PCM receiver failed before scheduled start", it) }
                val output = outputRef.get() ?: throw IOException("Android audio output is unavailable")
                val bufferedFrames = pendingFramesBeforePlayback()
                if (bufferedFrames < MIN_START_BUFFER_FRAMES) {
                    val bufferedMs = bufferedFrames * 1_000L / SAMPLE_RATE_HZ
                    val requiredMs = MIN_START_BUFFER_FRAMES * 1_000L / SAMPLE_RATE_HZ
                    throw IOException(
                        "Insufficient synchronized audio prebuffer at the shared start " +
                            "($bufferedMs ms available; $requiredMs ms required). " +
                            "Check the Host uplink and Speaker network before retrying."
                    )
                }
                output.play()
                playbackStarted.set(true)
                onPlaybackStarted()
                onStatus("Synchronized playback started at the shared clock target.")
                monitorPlaybackClock(output)
            } catch (error: Exception) {
                if (!closed.get() && !cancelled.get()) fail(error)
            }
        }, "syncinit-synchronized-audio-start").apply {
            isDaemon = true
            start()
        }
    }

    fun cancelScheduledPlayback() {
        cancelled.set(true)
        schedulerThread?.interrupt()
        if (!playbackStarted.get()) onStatus("Synchronized playback schedule cancelled.")
    }

    private fun writeAudioLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val output = outputRef.get() ?: throw IOException("Android audio output was not initialized")
            while (!closed.get() && failure.get() == null) {
                val pcm = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                pendingWriteBytes.set(pcm.size.toLong())
                while (offset < pcm.size && !closed.get() && failure.get() == null) {
                    val written = output.write(
                        pcm,
                        offset,
                        pcm.size - offset,
                        PlatformAudioTrack.WRITE_BLOCKING
                    )
                    if (written < 0) throw IOException("Android AudioTrack write failed: " + written)
                    if (written == 0) {
                        Thread.yield()
                    } else {
                        offset += written
                        writtenBytes.addAndGet(written.toLong())
                        pendingWriteBytes.addAndGet(-written.toLong())
                    }
                }
                pendingWriteBytes.set(0L)
                if (writtenBytes.get() >= MIN_PRIMED_BYTES &&
                    primedStatusSent.compareAndSet(false, true)
                ) {
                    onStatus("Speaker PCM output buffer primed.")
                }
            }
        } catch (error: InterruptedException) {
            if (!closed.get()) fail(error)
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed.get()) fail(error)
        }
    }

    private fun monitorPlaybackClock(output: PlatformAudioTrack) {
        driftThread = Thread({
            var lastRate = SAMPLE_RATE_HZ
            while (!closed.get() && playbackStarted.get() && failure.get() == null) {
                try {
                    Thread.sleep(DRIFT_SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val queuedBytes = queue.sumOf { it.size.toLong() }
                val totalWrittenFrames = writtenBytes.get() / BYTES_PER_FRAME
                val playedFrames = output.playbackHeadPosition.toLong() and 0xffff_ffffL
                val outputPendingFrames = max(0L, totalWrittenFrames - playedFrames)
                val pendingFrames = outputPendingFrames +
                    (queuedBytes + pendingWriteBytes.get()) / BYTES_PER_FRAME
                val errorFrames = pendingFrames - TARGET_PENDING_FRAMES
                val correctionHz = (errorFrames * RATE_CONTROL_GAIN)
                    .roundToInt()
                    .coerceIn(-MAX_RATE_CORRECTION_HZ, MAX_RATE_CORRECTION_HZ)
                val nextRate = SAMPLE_RATE_HZ + correctionHz
                if (abs(nextRate - lastRate) >= RATE_UPDATE_STEP_HZ) {
                    if (output.setPlaybackRate(nextRate) >= 0) lastRate = nextRate
                }
            }
        }, "syncinit-audio-clock-drift").apply {
            isDaemon = true
            start()
        }
    }

    private fun pendingFramesBeforePlayback(): Long {
        val pendingBytes = writtenBytes.get() +
            pendingWriteBytes.get() +
            queue.sumOf { it.size.toLong() }
        return pendingBytes / BYTES_PER_FRAME
    }

    private fun createOutputTrack(): PlatformAudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val minBufferBytes = PlatformAudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            throw IOException("Android audio output does not support 48 kHz stereo PCM")
        }
        val bufferBytes = max(minBufferBytes * 2, CHUNK_BYTES * OUTPUT_BUFFER_CHUNKS)
        val builder = PlatformAudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(PlatformAudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(PlatformAudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val output = builder.build()
        if (output.state != PlatformAudioTrack.STATE_INITIALIZED) {
            output.release()
            throw IOException("Android AudioTrack failed to initialize")
        }
        return output
    }

    private fun fail(error: Throwable) {
        if (failure.compareAndSet(null, error) && !closed.get()) {
            val detail = error.message ?: error.javaClass.simpleName
            onStatus("Synchronized playback degraded: " + detail)
            onPlaybackDegraded(detail)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelled.set(true)
        remoteTrackRef.getAndSet(null)?.let { track ->
            runCatching { track.removeSink(sink) }
        }
        schedulerThread?.interrupt()
        driftThread?.interrupt()
        writerThread?.interrupt()
        outputRef.getAndSet(null)?.let { output ->
            runCatching { output.pause() }
            runCatching { output.flush() }
            runCatching { output.stop() }
            runCatching { output.release() }
        }
        queue.clear()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2
        const val CHUNK_BYTES = 960 * BYTES_PER_FRAME
        const val QUEUE_CAPACITY = 700
        const val MAX_CALLBACK_BYTES = SAMPLE_RATE_HZ * BYTES_PER_FRAME / 5
        const val OUTPUT_BUFFER_CHUNKS = 12
        const val MIN_PRIMED_BYTES = SAMPLE_RATE_HZ / 5L * BYTES_PER_FRAME
        const val MINIMUM_SCHEDULE_LEAD_NS = 250_000_000L
        const val MIN_START_BUFFER_FRAMES = SAMPLE_RATE_HZ * 7L / 2L
        const val MAX_PARK_NS = 10_000_000L
        const val DRIFT_SAMPLE_INTERVAL_MS = 1_000L
        const val TARGET_PENDING_FRAMES = SAMPLE_RATE_HZ * 4L
        const val RATE_CONTROL_GAIN = 0.001
        const val MAX_RATE_CORRECTION_HZ = 24
        const val RATE_UPDATE_STEP_HZ = 2
    }
}
