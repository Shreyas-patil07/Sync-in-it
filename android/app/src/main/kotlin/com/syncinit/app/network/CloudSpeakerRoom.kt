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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Cloud WebRTC receiver that confirms a remote audio track is actually subscribed. */
class CloudSpeakerRoom(
    context: Context,
    private val serverUrl: String,
    private val participantToken: String,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val room: Room = LiveKit.create(
        context.applicationContext,
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(audioOutputType = AudioType.MediaAudioType())
        )
    )
    private val closed = AtomicBoolean(false)
    private val audioTrackSubscribed = CountDownLatch(1)
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventJob = eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
        room.events.collect { event ->
            when (event) {
                is RoomEvent.TrackSubscribed -> {
                    if (event.track is RemoteAudioTrack) {
                        onStatus("LiveKit remote audio track subscribed · cloud media is arriving.")
                        audioTrackSubscribed.countDown()
                    }
                }
                is RoomEvent.TrackSubscriptionFailed -> {
                    onStatus(
                        "LiveKit could not subscribe to an audio track: " +
                            (event.exception.message ?: event.exception.javaClass.simpleName)
                    )
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
            onStatus("LiveKit room connected · waiting for the Host audio track…")
            if (!audioTrackSubscribed.await(25, TimeUnit.SECONDS)) {
                throw IOException(
                    "LiveKit room connected, but no remote audio track was subscribed within 25 seconds. " +
                        "Check that the Host publisher is still streaming."
                )
            }
            check(!closed.get()) { "cloud Speaker room closed while waiting for audio" }
            onStatus("Host audio track subscribed. Receiving cloud audio through WebRTC.")
        } catch (error: Exception) {
            if (!closed.get()) {
                onStatus("LiveKit Speaker connection failed: ${error.message ?: error.javaClass.simpleName}")
            }
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        eventJob.cancel()
        eventScope.cancel()
        runCatching { room.disconnect() }
    }
}
