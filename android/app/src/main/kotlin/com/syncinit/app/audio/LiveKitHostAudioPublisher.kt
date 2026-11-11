// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import android.content.Context
import android.net.Uri
import com.syncinit.app.PreviewConfig
import com.syncinit.backend.BackendApiClient
import com.syncinit.backend.BackendMediaStreamConnection
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/**
 * Streams the existing Host PCM source to the authenticated backend ingress.
 *
 * The backend owns the LiveKit publisher credential and performs SFU publication.
 * Android sends only 20 ms PCM16 frames over WSS; no LAN listener or peer address is used.
 */
class LiveKitHostAudioPublisher(
    context: Context,
    private val api: BackendApiClient,
    private val sessionId: String,
    private val hostToken: String,
    private val audioFileUri: Uri?,
    private val systemAudioCaptureEnabled: Boolean,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Exception?>(null)
    @Volatile private var mediaConnection: BackendMediaStreamConnection? = null
    @Volatile private var worker: Thread? = null

    fun start(timeoutMs: Long = 120_000L) {
        check(!closed.get()) { "Cloud audio publisher is closed" }
        check(worker == null) { "Cloud audio publisher has already started" }
        val thread = Thread(::runPublisher, "syncinit-livekit-host-publisher").apply {
            isDaemon = true
        }
        worker = thread
        thread.start()
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw IOException("Timed out waiting for LiveKit cloud audio ingress")
        }
        failure.get()?.let { throw it }
        check(!closed.get()) { "Cloud audio publisher stopped before becoming ready" }
    }

    private fun runPublisher() {
        var fileSource: LocalAudioFilePcmSource? = null
        var systemSource: SystemAudioPcmSource? = null
        try {
            val connection = api.openHostMediaStream(sessionId, hostToken, onStatus)
            mediaConnection = connection
            connection.awaitReady(120_000L)

            when {
                audioFileUri != null -> {
                    fileSource = LocalAudioFilePcmSource(appContext, audioFileUri)
                    onStatus("Cloud audio source ready · local file decoded to 48 kHz stereo PCM.")
                }
                systemAudioCaptureEnabled -> {
                    systemSource = SystemAudioPcmSource()
                    onStatus("Cloud audio source ready · Android playback capture is feeding LiveKit.")
                }
                else -> onStatus("Cloud audio source ready · diagnostic test tone is feeding LiveKit.")
            }

            ready.countDown()
            var sequence = 0L
            var nextDeadlineNs = System.nanoTime()
            while (!closed.get()) {
                val pcm = when {
                    fileSource != null -> fileSource.readFrames(PreviewConfig.CHUNK_FRAMES)
                    systemSource != null -> systemSource.readFrames(PreviewConfig.CHUNK_FRAMES)
                    else -> createDiagnosticChunk(sequence)
                }
                connection.sendPcmFrame(pcm)
                sequence = (sequence + 1L) and 0xffff_ffffL
                nextDeadlineNs += PreviewConfig.CHUNK_PERIOD_NS
                val remainingNs = nextDeadlineNs - System.nanoTime()
                if (remainingNs > 0L) {
                    Thread.sleep(
                        remainingNs / 1_000_000L,
                        (remainingNs % 1_000_000L).toInt()
                    )
                } else {
                    nextDeadlineNs = System.nanoTime()
                }
            }
        } catch (error: InterruptedException) {
            if (!closed.get()) {
                Thread.currentThread().interrupt()
                failure.compareAndSet(null, IOException("Cloud audio publisher was interrupted", error))
                onStatus("Cloud audio publisher interrupted.")
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                failure.compareAndSet(null, error)
                onStatus("Cloud audio publishing failed: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            ready.countDown()
            fileSource?.close()
            systemSource?.close()
            mediaConnection?.close()
            mediaConnection = null
        }
    }

    private fun createDiagnosticChunk(sequence: Long): ByteArray {
        val output = ByteBuffer
            .allocate(PreviewConfig.CHUNK_FRAMES * PreviewConfig.CHANNELS * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val firstSample = sequence * PreviewConfig.CHUNK_FRAMES
        repeat(PreviewConfig.CHUNK_FRAMES) { frame ->
            val positionInSecond = (firstSample + frame) % PreviewConfig.SAMPLE_RATE_HZ
            val sample = if (positionInSecond < 1_440L) {
                (sin(2.0 * PI * 1_000.0 * positionInSecond / PreviewConfig.SAMPLE_RATE_HZ) * 6_000.0)
                    .toInt()
                    .toShort()
            } else 0
            output.putShort(sample)
            output.putShort(sample)
        }
        return output.array()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mediaConnection?.close()
        worker?.interrupt()
        ready.countDown()
    }
}
