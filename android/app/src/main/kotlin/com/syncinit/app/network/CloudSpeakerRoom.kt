// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.livekit.android.events.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Cloud WebRTC receiver with a shared-clock, locally scheduled PCM output. */
class CloudSpeakerRoom(
    context: Context,
    private val serverUrl: String,
    private val participantToken: String,
    initialServerMinusLocalClockOffsetNs: Long,
    private val refreshServerMinusLocalClockOffsetNs: () -> Long,
    private val onStatus: (String) -> Unit,
    private val onPlaybackStarted: () -> Unit,
    private val onPlaybackDegraded: (String) -> Unit,
    private val onBufferReady: () -> Unit
) : Closeable {
    private val room: Room = LiveKit.create(
        context.applicationContext,
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType())
        )
    )
    private val closed = AtomicBoolean(false)
    private val audioTrackSubscribed = CountDownLatch(1)
    private val trackSetupFailure = AtomicReference<Throwable?>(null)
    private val clockOffsetNs = AtomicLong(initialServerMinusLocalClockOffsetNs)
    private val clockSyncExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "syncinit-speaker-clock-sync").apply { isDaemon = true }
    }
    private val player = SynchronizedPcmPlayer(
        serverMinusLocalClockOffsetNs = clockOffsetNs,
        onStatus = onStatus,
        onPlaybackStarted = onPlaybackStarted,
        onPlaybackDegraded = onPlaybackDegraded,
        onBufferReady = onBufferReady
    )
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventJob = eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
        room.events.collect { event ->
            when (event) {
                is RoomEvent.TrackSubscribed -> {
                    val track = event.track as? RemoteAudioTrack ?: return@collect
                    try {
                        player.attach(track)
                        onStatus("LiveKit PCM sink attached · waiting for synchronized Host start.")
                        audioTrackSubscribed.countDown()
                    } catch (error: Exception) {
                        if (audioTrackSubscribed.count > 0L) {
                            trackSetupFailure.compareAndSet(null, error)
                            audioTrackSubscribed.countDown()
                        } else {
                            val detail =
                                "LiveKit replaced the audio track during reconnect; restart the Host session to preserve sample-zero alignment."
                            onStatus(detail)
                            onPlaybackDegraded(detail)
                        }
                    }
                }
                is RoomEvent.TrackSubscriptionFailed -> {
                    val detail = "LiveKit could not subscribe to an audio track: " +
                        (event.exception.message ?: event.exception.javaClass.simpleName)
                    onStatus(detail)
                    if (audioTrackSubscribed.count == 0L) onPlaybackDegraded(detail)
                }
                is RoomEvent.Reconnecting -> {
                    onStatus("LiveKit network interruption · attempting automatic reconnection.")
                }
                is RoomEvent.Reconnected -> {
                    onStatus("LiveKit reconnected · validating audio-track continuity.")
                }
                is RoomEvent.Disconnected -> {
                    if (!closed.get()) {
                        val detail = "LiveKit disconnected: " +
                            (event.error?.message ?: event.reason.toString())
                        onStatus(detail)
                        onPlaybackDegraded(detail)
                    }
                }
                is RoomEvent.FailedToConnect -> {
                    val detail = "LiveKit failed to connect: " +
                        (event.error.message ?: event.error.javaClass.simpleName)
                    onStatus(detail)
                    if (audioTrackSubscribed.count > 0L) {
                        trackSetupFailure.compareAndSet(null, IOException(detail, event.error))
                        audioTrackSubscribed.countDown()
                    } else {
                        onPlaybackDegraded(detail)
                    }
                }
                else -> Unit
            }
        }
    }

    fun connectAndAwait() {
        check(!closed.get()) { "cloud Speaker room is closed" }
        try {
            onStatus("Connecting Speaker to LiveKit over the internet…")
            runBlocking { room.connect(serverUrl, participantToken) }
            check(!closed.get()) { "cloud Speaker room closed during connection" }
            room.setSpeakerMute(true)
            onStatus("LiveKit room connected · preparing synchronized PCM output…")
            if (!audioTrackSubscribed.await(25, TimeUnit.SECONDS)) {
                throw IOException(
                    "LiveKit room connected, but no remote audio track was subscribed within 25 seconds. " +
                        "Check that the Host publisher is still streaming."
                )
            }
            check(!closed.get()) { "cloud Speaker room closed while waiting for audio" }
            trackSetupFailure.get()?.let {
                throw IOException("Could not prepare synchronized audio output", it)
            }

            // Recalibrate after the LiveKit path is established. The initial sample set is
            // taken before room connection, so a second measurement reduces setup-path bias.
            runCatching { refreshServerMinusLocalClockOffsetNs() }
                .onSuccess { refreshedOffset ->
                    clockOffsetNs.set(refreshedOffset)
                    onStatus("Shared clock recalibrated after LiveKit connection.")
                }
                .onFailure {
                    onStatus("Post-connect clock calibration missed · retaining the initial estimate.")
                }

            clockSyncExecutor.scheduleAtFixedRate({
                if (closed.get()) return@scheduleAtFixedRate
                runCatching { refreshServerMinusLocalClockOffsetNs() }
                    .onSuccess(clockOffsetNs::set)
                    .onFailure {
                        onStatus("Clock refresh missed · keeping the last synchronized clock estimate.")
                    }
            }, CLOCK_REFRESH_INTERVAL_SECONDS, CLOCK_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS)
            onStatus("Speaker connected · waiting for Host audio pre-roll and PCM buffering.")
        } catch (error: Exception) {
            if (!closed.get()) {
                onStatus("LiveKit Speaker connection failed: " + (error.message ?: error.javaClass.simpleName))
            }
            throw error
        }
    }

    fun schedulePlayback(serverStartAtNs: Long) {
        if (closed.get()) return
        runCatching { player.schedule(serverStartAtNs) }
            .onFailure {
                val detail = it.message ?: it.javaClass.simpleName
                onStatus("Could not arm synchronized playback: " + detail)
                onPlaybackDegraded(detail)
            }
    }

    fun cancelScheduledPlayback() {
        player.cancelScheduledPlayback()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clockSyncExecutor.shutdownNow()
        player.close()
        eventJob.cancel()
        eventScope.cancel()
        runCatching { room.disconnect() }
    }

    private companion object {
        const val CLOCK_REFRESH_INTERVAL_SECONDS = 5L
    }
}
