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
import com.syncinit.backend.BackendDeviceState
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
    private val onDeviceStateChanged: (BackendDeviceState, String) -> Unit
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
    private val connectionEstablished = AtomicBoolean(false)
    private val transportReady = AtomicBoolean(false)
    private val clockEstimateValid = AtomicBoolean(true)
    private val lastReportedDeviceState = AtomicReference<BackendDeviceState?>(null)
    private val clockSyncExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "syncinit-speaker-clock-sync").apply { isDaemon = true }
    }
    private val player = SynchronizedPcmPlayer(
        serverMinusLocalClockOffsetNs = clockOffsetNs,
        onStatus = onStatus,
        onPlaybackStarted = onPlaybackStarted,
        onPlaybackDegraded = onPlaybackDegraded
    )
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventJob = eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
        room.events.collect { event ->
            when (event) {
                is RoomEvent.Reconnecting -> {
                    transportReady.set(false)
                    clockEstimateValid.set(false)
                    val detail = "LiveKit connection interrupted · attempting automatic recovery."
                    onStatus(detail)
                    if (connectionEstablished.get()) {
                        if (player.hasPlaybackStarted() || player.hasScheduleArmed()) {
                            player.markTransportInterrupted(
                                "LiveKit transport was interrupted after playback was scheduled. Restart the synchronized session to restore alignment."
                            )
                            reportDeviceState(BackendDeviceState.DEGRADED, detail)
                        } else {
                            reportDeviceState(BackendDeviceState.SYNCING, detail)
                        }
                    }
                }
                is RoomEvent.Reconnected -> {
                    val detail = "LiveKit connection restored · refreshing the shared clock."
                    onStatus(detail)
                    if (player.hasPlaybackStarted() || player.hasScheduleArmed()) {
                        val playbackDetail =
                            "LiveKit transport was interrupted during playback. Stop and restart the synchronized session."
                        player.markTransportInterrupted(playbackDetail)
                        reportDeviceState(BackendDeviceState.DEGRADED, playbackDetail)
                    } else {
                        transportReady.set(player.hasAttachedTrack())
                        runCatching { clockSyncExecutor.execute(::refreshClockEstimate) }
                        publishReadyIfPossible()
                    }
                }
                is RoomEvent.Disconnected -> {
                    if (!closed.get()) {
                        val detail = "LiveKit room disconnected. Rejoin the Speaker before restarting playback."
                        if (player.hasPlaybackStarted() || player.hasScheduleArmed()) player.markTransportInterrupted(detail)
                        reportDeviceState(BackendDeviceState.DEGRADED, detail)
                        onStatus(detail)
                    }
                }
                is RoomEvent.TrackSubscribed -> {
                    val track = event.track as? RemoteAudioTrack ?: return@collect
                    try {
                        player.attach(track)
                        trackSetupFailure.set(null)
                        transportReady.set(true)
                        onStatus("LiveKit PCM sink attached · synchronized audio path is ready.")
                        audioTrackSubscribed.countDown()
                        publishReadyIfPossible()
                    } catch (error: Exception) {
                        trackSetupFailure.compareAndSet(null, error)
                        audioTrackSubscribed.countDown()
                        val detail = "Could not attach the LiveKit audio track: " +
                            (error.message ?: error.javaClass.simpleName)
                        onStatus(detail)
                        if (connectionEstablished.get()) {
                            reportDeviceState(BackendDeviceState.DEGRADED, detail)
                        }
                    }
                }
                is RoomEvent.TrackUnsubscribed -> {
                    val track = event.track as? RemoteAudioTrack ?: return@collect
                    player.detach(track)
                    transportReady.set(false)
                    val detail = "LiveKit audio track was interrupted · waiting for the Host track to return."
                    if (connectionEstablished.get()) {
                        if (player.hasPlaybackStarted() || player.hasScheduleArmed()) {
                            reportDeviceState(BackendDeviceState.DEGRADED, detail)
                        } else {
                            reportDeviceState(BackendDeviceState.SYNCING, detail)
                        }
                    }
                    onStatus(detail)
                }
                is RoomEvent.TrackStreamStateChanged -> {
                    when (event.streamState) {
                        io.livekit.android.room.track.Track.StreamState.PAUSED -> {
                            transportReady.set(false)
                            val detail = "LiveKit paused the audio stream because of network or bandwidth constraints."
                            if (player.hasPlaybackStarted() || player.hasScheduleArmed()) {
                                player.markTransportInterrupted(detail)
                                reportDeviceState(BackendDeviceState.DEGRADED, detail)
                            } else if (connectionEstablished.get()) {
                                reportDeviceState(BackendDeviceState.SYNCING, detail)
                            }
                            onStatus(detail)
                        }
                        io.livekit.android.room.track.Track.StreamState.ACTIVE -> {
                            transportReady.set(player.hasAttachedTrack())
                            onStatus("LiveKit audio stream resumed · refreshing synchronization state.")
                            if (connectionEstablished.get()) {
                                runCatching { clockSyncExecutor.execute(::refreshClockEstimate) }
                            }
                            publishReadyIfPossible()
                        }
                        else -> {
                            transportReady.set(false)
                            if (player.hasScheduleArmed() || player.hasPlaybackStarted()) {
                                val detail = "LiveKit audio stream state became unknown after scheduling. Restart synchronized playback."
                                player.markTransportInterrupted(detail)
                                reportDeviceState(BackendDeviceState.DEGRADED, detail)
                            } else if (connectionEstablished.get()) {
                                reportDeviceState(
                                    BackendDeviceState.SYNCING,
                                    "LiveKit audio stream state is unknown · waiting for an active stream."
                                )
                            }
                        }
                    }
                }
                is RoomEvent.TrackSubscriptionFailed -> {
                    val detail = "LiveKit audio subscription failed: " +
                        (event.exception.message ?: event.exception.javaClass.simpleName)
                    if (player.hasScheduleArmed() || player.hasPlaybackStarted()) {
                        player.markTransportInterrupted(detail)
                        reportDeviceState(BackendDeviceState.DEGRADED, detail)
                    } else if (!connectionEstablished.get()) {
                        trackSetupFailure.set(event.exception)
                    } else {
                        reportDeviceState(BackendDeviceState.SYNCING, detail)
                    }
                    onStatus(detail)
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
                val subscriptionError = trackSetupFailure.get()
                val detail = subscriptionError?.message
                    ?: "Check that the Host publisher is still streaming."
                throw IOException(
                    "LiveKit room connected, but no remote audio track was subscribed within 25 seconds. $detail",
                    subscriptionError
                )
            }
            check(!closed.get()) { "cloud Speaker room closed while waiting for audio" }
            trackSetupFailure.get()?.let {
                throw IOException("Could not prepare synchronized audio output", it)
            }
            connectionEstablished.set(true)
            clockSyncExecutor.scheduleAtFixedRate(
                ::refreshClockEstimate,
                CLOCK_REFRESH_INTERVAL_SECONDS,
                CLOCK_REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
            publishReadyIfPossible()
            onStatus("Speaker connected · verifying audio track and shared clock readiness.")
        } catch (error: Exception) {
            if (!closed.get()) {
                onStatus("LiveKit Speaker connection failed: " + (error.message ?: error.javaClass.simpleName))
            }
            throw error
        }
    }

    private fun refreshClockEstimate() {
        if (closed.get()) return
        runCatching { refreshServerMinusLocalClockOffsetNs() }
            .onSuccess {
                clockOffsetNs.set(it)
                clockEstimateValid.set(true)
                publishReadyIfPossible()
            }
            .onFailure {
                clockEstimateValid.set(false)
                val detail = "Shared clock refresh failed · waiting to re-synchronize before playback."
                onStatus(detail)
                if (connectionEstablished.get() && !player.hasPlaybackStarted()) {
                    reportDeviceState(BackendDeviceState.SYNCING, detail)
                }
            }
    }

    private fun publishReadyIfPossible() {
        if (connectionEstablished.get() &&
            transportReady.get() &&
            clockEstimateValid.get() &&
            !player.hasScheduleArmed() &&
            !player.hasPlaybackStarted()
        ) {
            reportDeviceState(
                BackendDeviceState.READY,
                "LiveKit audio track attached and shared clock refreshed"
            )
        }
    }

    private fun reportDeviceState(state: BackendDeviceState, detail: String) {
        val previous = lastReportedDeviceState.getAndSet(state)
        if (previous != state) onDeviceStateChanged(state, detail)
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
