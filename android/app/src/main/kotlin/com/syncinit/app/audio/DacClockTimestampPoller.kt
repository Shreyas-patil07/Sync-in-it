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
 * AudioTimestamp.nanoTime uses the monotonic clock domain. The portable native mapper
 * fits frame position to that clock; this is digital output telemetry, not microphone
 * measurement of acoustic sound arrival.
 */
internal class DacClockTimestampPoller(
    private val sampleRateHz: Int,
    private val onStatus: (String) -> Unit
) : Closeable {
    companion object {
        private const val POLL_INTERVAL_MS = 200L
        private const val REPORT_INTERVAL_NS = 5_000_000_000L
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
        var unavailableTimestamps = 0L
        var rejectedTimestamps = 0L
        var lastReportNs = System.nanoTime()

        while (!closed.get()) {
            try {
                if (audioTrack.getTimestamp(timestamp)) {
                    if (!NativeCore.nativeAddDacTimestamp(
                            handle,
                            timestamp.framePosition,
                            timestamp.nanoTime
                        )
                    ) {
                        rejectedTimestamps += 1L
                    }
                } else {
                    unavailableTimestamps += 1L
                }

                val nowNs = System.nanoTime()
                if (nowNs - lastReportNs >= REPORT_INTERVAL_NS) {
                    report(unavailableTimestamps, rejectedTimestamps)
                    lastReportNs = nowNs
                }

                Thread.sleep(POLL_INTERVAL_MS)
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

    private fun report(unavailable: Long, rejected: Long) {
        val values = NativeCore.nativeDacClockSnapshot(handle) ?: return
        if (values.size != 9) {
            onStatus("DAC clock diagnostics unavailable · invalid native snapshot.")
            return
        }

        val rejectedTotal = values[8].toLong()
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
                    rejectedTotal,
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
                    rejectedTotal,
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
