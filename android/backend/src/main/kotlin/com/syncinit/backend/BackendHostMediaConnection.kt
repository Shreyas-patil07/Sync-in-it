// SPDX-License-Identifier: Apache-2.0
package com.syncinit.backend

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class BackendLiveKitCredentials(
    val serverUrl: String,
    val participantToken: String,
    val roomName: String,
    val role: String,
    val sampleRateHz: Int,
    val channels: Int,
    val chunkFrames: Int
)

interface BackendHostMediaListener {
    fun onReady()
    fun onStats(framesReceived: Long)
    fun onFailure(error: Throwable)
    fun onClosed(code: Int, reason: String) {}
}

/** Binary PCM uplink to the authenticated FastAPI-to-LiveKit publisher bridge. */
class BackendHostMediaConnection internal constructor(
    private val hostToken: String,
    private val listener: BackendHostMediaListener
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>(null)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var isReady = false

    internal fun attach(webSocket: WebSocket) {
        socket = webSocket
        if (closed.get()) webSocket.cancel()
    }

    internal fun webSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val auth = JsonObject().apply {
                addProperty("type", "authenticate")
                addProperty("token", hostToken)
            }
            if (!webSocket.send(auth.toString())) {
                fail(IOException("Host media authentication frame was rejected"))
                webSocket.cancel()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val message = JsonParser.parseString(text).asJsonObject
                when (message.get("type")?.asString) {
                    "media.ready" -> {
                        isReady = true
                        ready.countDown()
                        listener.onReady()
                    }
                    "media.stats" -> listener.onStats(
                        message.get("frames_received")?.asLong ?: 0L
                    )
                    "protocol.error" -> fail(
                        IOException("Backend media protocol error: ${message.get("code")?.asString ?: "unknown"}")
                    )
                }
            } catch (error: Exception) {
                fail(IOException("Invalid backend media response", error))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code?.let { " (HTTP $it)" }.orEmpty()
            fail(IOException("Backend media WebSocket failed$status", t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ready.countDown()
            listener.onClosed(code, reason)
        }
    }

    fun awaitReady(timeout: Long, unit: TimeUnit): Boolean {
        if (!ready.await(timeout, unit)) {
            throw IOException("Timed out waiting for the cloud media publisher")
        }
        failure.get()?.let { throw IOException("Cloud media publisher failed", it) }
        if (!isReady) throw IOException("Backend closed before the cloud media publisher became ready")
        return true
    }

    fun sendPcmChunk(pcm16LittleEndian: ByteArray): Boolean {
        check(isReady && !closed.get()) { "cloud media publisher is not ready" }
        require(pcm16LittleEndian.size == PCM_CHUNK_BYTES) {
            "PCM chunk must contain exactly $PCM_CHUNK_BYTES bytes"
        }
        val activeSocket = socket ?: return false
        if (activeSocket.queueSize() > MAX_QUEUED_BYTES) return false
        return activeSocket.send(pcm16LittleEndian.toByteString())
    }

    private fun fail(error: Throwable) {
        if (failure.compareAndSet(null, error)) {
            ready.countDown()
            listener.onFailure(error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.countDown()
        socket?.close(1000, "host-media-stopped")
        socket = null
    }

    private companion object {
        const val PCM_CHUNK_BYTES = 3_840
        const val MAX_QUEUED_BYTES = 4L * 1024L * 1024L
    }
}
