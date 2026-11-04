// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import android.media.AudioTimestamp
import android.media.AudioTrack
import com.syncinit.app.core.NativeCore
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samples AudioTrack's presented-frame timestamps away from the output writer.
 *
 * AudioTimestamp.nanoTime uses the System.nanoTime monotonic timebase. Android exposes
 * AudioTrack framePosition in wrapping 32-bit units, so this poller unwraps it before
 * passing samples to the portable native regression. The mapping is digital output
 * telemetry, not microphone measurement of acoustic sound arrival.
 */
internal class DacClockTimestampPoller(
    private val sampleRateHz: Int,
    private val onStatus: (String) -> Unit
) : Closeable {
    companion object {
        private const val WARMUP_POLL_INTERVAL_MS = 1_000L
        private const val STEADY_POLL_INTERVAL_MS = 10_000L
        private const val REPORT_INTERVAL_NS = 10_000_000_000L
        private const val REQUIRED_ADVANCING_SAMPLES = 3
        private const val UNAVAILABLE_BEFORE_SLOW_POLL = 10
        private const val FRAME_POSITION_MASK = 0xffff_ffffL
        private const val HALF_FRAME_POSITION_RANGE = 0x7fff_ffffL
    }

    private val closed = AtomicBoolean(false)
    private val handle: Long
    @Volatile private var worker: Thread? = null

    init {
        require(sampleRateHz in 8_000..192_000)
        handle = NativeCore.nativeCreateDacClock(sampleRateHz)
        check(handle != 0L) { "native DAC clock mapper allocation failed" }
    }

    fun start(audioTrack: AudioTrack) {
        check(!closed.get()) { "DAC timestamp poller is closed" }
        check(worker == null) { "DAC timestamp poller has already started" }
        val thread = Thread({ poll(audioTrack) }, "syncinit-dac-timestamp-poller").apply {
            isDaemon = true
        }
        worker = thread
        thread.start()
    }

    private fun poll(audioTrack: AudioTrack) {
        val timestamp = AudioTimestamp()
        var previousRawFramePosition: Long? = null
        var previousTimestampNs: Long? = null
        var unwrappedFramePosition = 0L
        var advancingSamples = 0
        var unavailableInARow = 0
        var unavailableTotal = 0L
        var steadyCadence = false
        var lastReportNs = System.nanoTime()

        while (!closed.get()) {
            try {
                if (audioTrack.getTimestamp(timestamp)) {
                    unavailableInARow = 0
                    val rawFramePosition = timestamp.framePosition and FRAME_POSITION_MASK
                    val previousRaw = previousRawFramePosition
                    val previousTime = previousTimestampNs

                    if (previousRaw == null || previousTime == null) {
                        unwrappedFramePosition = rawFramePosition
                        previousRawFramePosition = rawFramePosition
                        previousTimestampNs = timestamp.nanoTime
                        if (NativeCore.nativeAddDacTimestamp(
                                handle,
                                unwrappedFramePosition,
                                timestamp.nanoTime
                            )
                        ) {
                            advancingSamples = 1
                        }
                    } else {
                        val frameDelta = (rawFramePosition - previousRaw) and FRAME_POSITION_MASK
                        val clockMovedBackwards = timestamp.nanoTime <= previousTime
                        val frameCounterReset = frameDelta > HALF_FRAME_POSITION_RANGE

                        if (clockMovedBackwards || frameCounterReset) {
                            NativeCore.nativeResetDacClock(handle)
                            unwrappedFramePosition = rawFramePosition
                            previousRawFramePosition = rawFramePosition
                            previousTimestampNs = timestamp.nanoTime
                            advancingSamples = if (NativeCore.nativeAddDacTimestamp(
                                    handle,
                                    unwrappedFramePosition,
                                    timestamp.nanoTime
                                )
                            ) 1 else 0
                            steadyCadence = false
                        } else {
                            if (frameDelta > 0L) {
                                unwrappedFramePosition += frameDelta
                                if (NativeCore.nativeAddDacTimestamp(
                                        handle,
                                        unwrappedFramePosition,
                                        timestamp.nanoTime
                                    )
                                ) {
                                    advancingSamples += 1
                                }
                                previousRawFramePosition = rawFramePosition
                            }
                            previousTimestampNs = timestamp.nanoTime
                        }
                    }

                    if (advancingSamples >= REQUIRED_ADVANCING_SAMPLES) {
                        steadyCadence = true
                    }
                } else {
                    unavailableTotal += 1L
                    unavailableInARow += 1
                    if (unavailableInARow >= UNAVAILABLE_BEFORE_SLOW_POLL) {
                        steadyCadence = true
                    }
                }

                val nowNs = System.nanoTime()
                if (nowNs - lastReportNs >= REPORT_INTERVAL_NS) {
                    report(unavailableTotal)
                    lastReportNs = nowNs
                }

                Thread.sleep(
                    if (steadyCadence) STEADY_POLL_INTERVAL_MS else WARMUP_POLL_INTERVAL_MS
                )
            } catch (_: InterruptedException) {
                if (!closed.get()) Thread.currentThread().interrupt()
                break
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus(
                        "DAC timestamp polling stopped · ${error.message ?: error.javaClass.simpleName}."
                    )
                }
                break
            }
        }
    }

    private fun report(unavailable: Long) {
        val values = NativeCore.nativeDacClockSnapshot(handle) ?: return
        if (values.size != 9) {
            onStatus("DAC clock diagnostics unavailable · invalid native snapshot.")
            return
        }

        val rejected = values[8].toLong()
        if (values[0] == 1.0) {
            val rateHz = sampleRateHz * (1.0 + values[1] / 1_000_000.0)
            onStatus(
                String.format(
                    Locale.US,
                    "DAC output clock mapped · %.2f Hz (%+.1f ppm) · residual %.1f µs · " +
                        "%d samples / %.1f s · rejected %d · unavailable %d",
                    rateHz,
                    values[1],
                    values[2],
                    values[3].toInt(),
                    values[4] / 1_000_000_000.0,
                    rejected,
                    unavailable
                )
            )
        } else {
            onStatus(
                String.format(
                    Locale.US,
                    "DAC timestamp acquisition · %d samples / %.1f s · " +
                        "rejected %d · unavailable %d · waiting for a stable fit",
                    values[3].toInt(),
                    values[4] / 1_000_000_000.0,
                    rejected,
                    unavailable
                )
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val thread = worker
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        NativeCore.nativeCloseDacClock(handle)
    }
}
