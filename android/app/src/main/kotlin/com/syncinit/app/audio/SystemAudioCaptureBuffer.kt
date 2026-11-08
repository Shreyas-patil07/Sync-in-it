// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/** Bounded rolling PCM handoff between MediaProjection capture and the Host's shared producer. */
internal object SystemAudioCaptureBuffer {
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE
    const val CHUNK_FRAMES = 960
    const val PRE_ROLL_FRAMES = SAMPLE_RATE_HZ * 2
    private const val CAPACITY_SECONDS = 20
    private const val CAPACITY_FRAMES = SAMPLE_RATE_HZ * CAPACITY_SECONDS
    private const val READ_TIMEOUT_MS = 3_000L
    private const val START_TIMEOUT_MS = 8_000L

    private val lock = ReentrantLock()
    private val updated = lock.newCondition()
    private val ring = ByteArray(CAPACITY_FRAMES * BYTES_PER_FRAME)
    private var writeFrame = 0L
    private var active = false
    private var failure: String? = null

    @Volatile
    var statusListener: ((String) -> Unit)? = null

    fun isActive(): Boolean = lock.withLock { active && failure == null }

    fun begin() {
        lock.withLock {
            ring.fill(0)
            writeFrame = 0L
            failure = null
            active = true
            updated.signalAll()
        }
        report("System-audio capture service is active · waiting for eligible app playback.")
    }

    fun append(pcm16Stereo: ByteArray) {
        require(pcm16Stereo.isNotEmpty() && pcm16Stereo.size % BYTES_PER_FRAME == 0) {
            "captured PCM must contain complete stereo frames"
        }
        lock.withLock {
            if (!active || failure != null) return
            val byteCount = pcm16Stereo.size
            val startByte = ((writeFrame % CAPACITY_FRAMES) * BYTES_PER_FRAME).toInt()
            val firstPart = min(byteCount, ring.size - startByte)
            System.arraycopy(pcm16Stereo, 0, ring, startByte, firstPart)
            if (firstPart < byteCount) {
                System.arraycopy(pcm16Stereo, firstPart, ring, 0, byteCount - firstPart)
            }
            writeFrame += byteCount / BYTES_PER_FRAME
            updated.signalAll()
        }
    }

    fun fail(reason: String) {
        lock.withLock {
            failure = reason
            active = false
            updated.signalAll()
        }
        report("System-audio capture failed: $reason")
    }

    fun stop(reason: String) {
        lock.withLock {
            active = false
            if (failure == null) failure = reason
            updated.signalAll()
        }
        report("System-audio capture stopped: $reason")
    }

    fun awaitRecentCursor(frameCount: Int): Long {
        require(frameCount in 1..CAPACITY_FRAMES)
        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        return lock.withLock {
            while (writeFrame < frameCount && failure == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                updated.await(remaining, TimeUnit.MILLISECONDS)
            }
            if (writeFrame < frameCount) {
                throw IOException(failure ?: "Not enough system-audio history for the two-second pre-roll")
            }
            writeFrame - frameCount
        }
    }

    fun readFrames(startFrame: Long, frameCount: Int): ByteArray {
        require(startFrame >= 0L && frameCount > 0)
        val endFrame = startFrame + frameCount
        val deadline = SystemClock.elapsedRealtime() + READ_TIMEOUT_MS
        return lock.withLock {
            while (writeFrame < endFrame && active && failure == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                updated.await(remaining, TimeUnit.MILLISECONDS)
            }
            val oldestFrame = (writeFrame - CAPACITY_FRAMES).coerceAtLeast(0L)
            if (startFrame < oldestFrame) {
                throw IOException("System-audio capture fell behind the ${CAPACITY_SECONDS}-second rolling buffer")
            }
            if (writeFrame < endFrame) {
                throw IOException(failure ?: "Timed out waiting for captured system-audio PCM")
            }
            val output = ByteArray(frameCount * BYTES_PER_FRAME)
            val startByte = ((startFrame % CAPACITY_FRAMES) * BYTES_PER_FRAME).toInt()
            val firstPart = min(output.size, ring.size - startByte)
            System.arraycopy(ring, startByte, output, 0, firstPart)
            if (firstPart < output.size) {
                System.arraycopy(ring, 0, output, firstPart, output.size - firstPart)
            }
            output
        }
    }

    private fun report(message: String) {
        statusListener?.invoke(message)
    }
}

/** Sequential 48 kHz stereo PCM reader over the rolling Android playback-capture buffer. */
internal class SystemAudioPcmSource : Closeable {
    private var closed = false
    val startCaptureFrame: Long = SystemAudioCaptureBuffer.awaitRecentCursor(
        SystemAudioCaptureBuffer.PRE_ROLL_FRAMES
    )
    private var nextCaptureFrame = startCaptureFrame

    @Synchronized
    fun readFrames(frameCount: Int): ByteArray {
        check(!closed) { "system-audio source is closed" }
        val frames = SystemAudioCaptureBuffer.readFrames(nextCaptureFrame, frameCount)
        nextCaptureFrame += frameCount
        return frames
    }

    @Synchronized
    override fun close() {
        closed = true
    }
}
