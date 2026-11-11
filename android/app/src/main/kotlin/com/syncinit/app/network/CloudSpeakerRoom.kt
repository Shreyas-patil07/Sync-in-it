// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/** Cloud WebRTC receiver. LiveKit automatically plays subscribed remote audio tracks. */
class CloudSpeakerRoom(
    context: Context,
    private val serverUrl: String,
    private val participantToken: String,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val room: Room = LiveKit.create(context.applicationContext)
    private val closed = AtomicBoolean(false)
    @Volatile private var connectionThread: Thread? = null

    fun connect() {
        check(!closed.get()) { "cloud Speaker room is closed" }
        check(connectionThread == null) { "cloud Speaker room has already started" }
        val thread = Thread({
            try {
                onStatus("Connecting Speaker to LiveKit over the internet…")
                runBlocking {
                    room.connect(serverUrl, participantToken)
                }
                if (!closed.get()) {
                    onStatus("Speaker connected to LiveKit. Waiting for the Host audio track.")
                }
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus("LiveKit Speaker connection failed: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }, "syncinit-livekit-speaker").apply {
            isDaemon = true
        }
        connectionThread = thread
        thread.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { room.disconnect() }
        connectionThread?.interrupt()
        connectionThread = null
    }
}
