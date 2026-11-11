// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import android.content.Context
import android.net.Uri
import com.syncinit.app.PreviewConfig
import com.syncinit.app.audio.LocalAudioFilePcmSource
import com.syncinit.app.audio.SystemAudioPcmSource
import com.syncinit.backend.BackendApiClient
import com.syncinit.backend.BackendHostMediaConnection
import com.syncinit.backend.BackendHostMediaListener
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/** Streams the selected Host source to the cloud media ingress; it binds no local sockets. */
class CloudHostSession(
    context: Context,
    private val api: BackendApiClient,
    private val sessionId: String,
    private val hostToken: String,
    private val onStatus: (String) -> Unit,
    private val audioFileUri: Uri? = null,
    private val audioFileLabel: String? = null,
    private val systemAudioCaptureEnabled: Boolean = false
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val startupReady = CountDownLatch(1)
    private val startupFailure = AtomicReference<Throwable?>(null)
    @Volatile private var mediaConnection: BackendHostMediaConnection? = null
    @Volatile private var fileSource: LocalAudioFilePcmSource? = null
    @Volatile private var systemSource: SystemAudioPcmSource? = null
    @Volatile private var worker: Thread? = null

    fun startAndAwaitReady() {
        check(!closed.get()) { "cloud Host session is closed" }
        check(worker == null) { "cloud Host session has already started" }

        val connection = api.openHostMedia(
            sessionId = sessionId,
            hostToken = hostToken,
            listener = object : BackendHostMediaListener {
                override fun onReady() {
                    onStatus("Cloud media publisher connected to LiveKit.")
                }

                override fun onStats(framesReceived: Long) {
                    onStatus("Cloud media uplink · frames accepted: $framesReceived.")
                }

                override fun onFailure(error: Throwable) {
                    startupFailure.compareAndSet(null, error)
                    startupReady.countDown()
                    onStatus("Cloud media connection failed: ${error.message ?: error.javaClass.simpleName}")
                }

                override fun onClosed(code: Int, reason: String) {
                    if (!closed.get()) {
                        startupFailure.compareAndSet(null, IOException("Cloud media connection closed ($code): $reason"))
                        startupReady.countDown()
                        onStatus("Cloud media connection closed ($code): $reason")
                    }
                }
            }
        )
        mediaConnection = connection

        val thread = Thread({
            try {
                onStatus("Connecting Host to the cloud media relay…")
                connection.awaitReady(45, TimeUnit.SECONDS)
                if (closed.get()) return@Thread

                val source = when {
                    audioFileUri != null -> LocalAudioFilePcmSource(appContext, audioFileUri).also {
                        fileSource = it
                        onStatus("Cloud audio source ready · local file: ${audioFileLabel ?: "selected audio"} (loops at end-of-file).")
                    }
                    systemAudioCaptureEnabled -> SystemAudioPcmSource().also {
                        systemSource = it
                        onStatus("Cloud audio source ready · Android app playback capture.")
                    }
                    else -> {
                        onStatus("Cloud audio source ready · diagnostic 1 kHz test signal.")
                        null
                    }
                }

                startupReady.countDown()
                var sampleIndex = 0L
                var chunkNumber = 0L
                var nextDeadlineNs = System.nanoTime()
                while (!closed.get()) {
                    val pcm = source?.readFrames(PreviewConfig.CHUNK_FRAMES)
                        ?: createDiagnosticChunk(sampleIndex)
                    if (!connection.sendPcmChunk(pcm)) {
                        throw IOException("Cloud media uplink is backpressured or disconnected")
                    }

                    sampleIndex += PreviewConfig.CHUNK_FRAMES
                    chunkNumber += 1L
                    nextDeadlineNs += PreviewConfig.CHUNK_PERIOD_NS
                    val remainingNs = nextDeadlineNs - System.nanoTime()
                    if (remainingNs > 0L) {
                        TimeUnit.NANOSECONDS.sleep(remainingNs)
                    } else if (remainingNs < -200_000_000L) {
                        onStatus("Cloud audio source is behind real time; resetting the send deadline.")
                        nextDeadlineNs = System.nanoTime()
                    }
                    if (chunkNumber % 250L == 0L) {
                        onStatus("Cloud media upload active · elapsed audio ${chunkNumber / 50L}s.")
                    }
                }
            } catch (error: InterruptedException) {
                if (!closed.get()) {
                    Thread.currentThread().interrupt()
                    onStatus("Cloud media upload interrupted.")
                }
            } catch (error: Exception) {
                if (!closed.get()) {
                    startupFailure.compareAndSet(null, error)
                    startupReady.countDown()
                    onStatus("Cloud Host media failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                closeSources()
            }
        }, "syncinit-cloud-host-media").apply {
            isDaemon = true
        }
        worker = thread
        thread.start()
        if (!startupReady.await(50, TimeUnit.SECONDS)) {
            throw IOException("Timed out waiting for the Host cloud media connection")
        }
        startupFailure.get()?.let { throw IOException("Host cloud media connection failed", it) }
        check(!closed.get()) { "Host cloud media session closed during startup" }
    }

    private fun createDiagnosticChunk(firstSampleIndex: Long): ByteArray {
        val output = ByteBuffer
            .allocate(PreviewConfig.CHUNK_FRAMES * PreviewConfig.CHANNELS * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(PreviewConfig.CHUNK_FRAMES) { frame ->
            val sampleIndex = firstSampleIndex + frame
            val sample = (
                sin(2.0 * PI * 1_000.0 * sampleIndex / PreviewConfig.SAMPLE_RATE_HZ) * 2_500.0
            ).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            repeat(PreviewConfig.CHANNELS) { output.putShort(sample) }
        }
        return output.array()
    }

    private fun closeSources() {
        runCatching { fileSource?.close() }
        fileSource = null
        runCatching { systemSource?.close() }
        systemSource = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mediaConnection?.close()
        mediaConnection = null
        worker?.interrupt()
        worker = null
        closeSources()
    }
}
