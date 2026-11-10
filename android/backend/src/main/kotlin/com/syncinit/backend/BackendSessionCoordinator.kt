// SPDX-License-Identifier: Apache-2.0
package com.syncinit.backend

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class BackendSessionRole {
    HOST,
    SPEAKER
}

enum class BackendConnectionState {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECT_WAIT,
    FAILED
}

interface BackendSessionCoordinatorListener {
    fun onConnectionStateChanged(state: BackendConnectionState)
    fun onEvent(event: BackendEvent)
    fun onFailure(error: Throwable)
}

/**
 * Owns the lifecycle of one backend session WebSocket.
 *
 * It reconnects with bounded exponential backoff, relies on the server snapshot emitted after
 * authentication to reconcile missed events, and keeps Speaker presence fresh through REST
 * heartbeat fallback while the WebSocket is unavailable.
 *
 * This coordinator never carries media or precision timing data.
 */
class BackendSessionCoordinator(
    private val api: BackendApiClient,
    private val sessionId: String,
    private val bearerToken: String,
    private val role: BackendSessionRole,
    private val heartbeatIntervalSeconds: Int = 5,
    private val listener: BackendSessionCoordinatorListener
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val reconnectScheduled = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private val state = AtomicReference(BackendConnectionState.STOPPED)
    private val connection = AtomicReference<BackendEventConnection?>(null)
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { task ->
        Thread(task, "syncinit-backend-session").apply { isDaemon = true }
    }
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "syncinit-backend-heartbeat").apply { isDaemon = true }
    }

    init {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(bearerToken.length in 32..256 && bearerToken.none(Char::isWhitespace))
        require(heartbeatIntervalSeconds in 1..60)
    }

    fun start() {
        check(!closed.get()) { "backend session coordinator is closed" }
        check(started.compareAndSet(false, true)) { "backend session coordinator already started" }
        updateState(BackendConnectionState.CONNECTING)
        connect()
        if (role == BackendSessionRole.SPEAKER) {
            scheduler.scheduleAtFixedRate(
                ::sendHeartbeat,
                heartbeatIntervalSeconds.toLong(),
                heartbeatIntervalSeconds.toLong(),
                TimeUnit.SECONDS
            )
        }
    }

    fun sendControlCommand(
        command: BackendControlCommand,
        targetDeviceIds: List<String>? = null,
        commandId: String? = null
    ): Boolean {
        if (role != BackendSessionRole.HOST) return false
        return connection.get()?.sendControlCommand(command, targetDeviceIds, commandId) == true
    }

    fun updateSpeakerState(state: BackendDeviceState, detail: String? = null): Boolean {
        if (role != BackendSessionRole.SPEAKER) return false
        return connection.get()?.updateState(state, detail) == true
    }

    fun acknowledgeCommand(
        commandId: String,
        accepted: Boolean,
        detail: String? = null
    ): Boolean {
        if (role != BackendSessionRole.SPEAKER) return false
        return connection.get()?.acknowledgeCommand(commandId, accepted, detail) == true
    }

    fun currentState(): BackendConnectionState = state.get()

    private fun connect() {
        if (closed.get()) return
        val currentGeneration = generation.incrementAndGet()
        reconnectScheduled.set(false)
        updateState(BackendConnectionState.CONNECTING)

        try {
            val next = api.openSessionEvents(
                sessionId = sessionId,
                bearerToken = bearerToken,
                listener = object : BackendEventListener {
                    override fun onEvent(event: BackendEvent) {
                        if (!isCurrent(currentGeneration)) return
                        if (event.type == "authenticated") {
                            reconnectAttempt.set(0)
                            updateState(BackendConnectionState.CONNECTED)
                        }
                        safeCallback { listener.onEvent(event) }
                    }

                    override fun onFailure(error: Throwable) {
                        if (!isCurrent(currentGeneration)) return
                        safeCallback { listener.onFailure(error) }
                        scheduleReconnect(currentGeneration)
                    }

                    override fun onClosed(code: Int, reason: String) {
                        if (!isCurrent(currentGeneration)) return
                        if (code in FATAL_CLOSE_CODES) {
                            connection.getAndSet(null)?.close()
                            updateState(BackendConnectionState.FAILED)
                        } else {
                            scheduleReconnect(currentGeneration)
                        }
                    }
                }
            )
            if (isCurrent(currentGeneration)) {
                connection.getAndSet(next)?.close()
            } else {
                next.close()
            }
        } catch (error: Exception) {
            if (!isCurrent(currentGeneration)) return
            safeCallback { listener.onFailure(error) }
            scheduleReconnect(currentGeneration)
        }
    }

    private fun scheduleReconnect(failedGeneration: Long) {
        if (closed.get() || !isCurrent(failedGeneration)) return
        if (!reconnectScheduled.compareAndSet(false, true)) return

        connection.getAndSet(null)?.close()
        updateState(BackendConnectionState.RECONNECT_WAIT)
        val attempt = reconnectAttempt.getAndIncrement().coerceAtMost(MAX_BACKOFF_SHIFT)
        val delay = (BASE_RECONNECT_DELAY_MS * (1 shl attempt)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        scheduler.schedule({
            reconnectScheduled.set(false)
            if (!closed.get() && isCurrent(failedGeneration)) connect()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun sendHeartbeat() {
        if (closed.get() || role != BackendSessionRole.SPEAKER) return
        val activeConnection = connection.get()
        if (state.get() == BackendConnectionState.CONNECTED &&
            activeConnection?.heartbeat() == true
        ) return

        ioExecutor.execute {
            if (closed.get()) return@execute
            try {
                api.heartbeat(sessionId, bearerToken)
            } catch (error: Exception) {
                safeCallback { listener.onFailure(error) }
            }
        }
    }

    private fun isCurrent(candidate: Long): Boolean =
        !closed.get() && generation.get() == candidate

    private fun updateState(next: BackendConnectionState) {
        val previous = state.getAndSet(next)
        if (previous != next) safeCallback { listener.onConnectionStateChanged(next) }
    }

    private inline fun safeCallback(callback: () -> Unit) {
        try {
            callback()
        } catch (_: RuntimeException) {
            // A consumer callback must not terminate the network lifecycle worker.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        connection.getAndSet(null)?.close()
        updateState(BackendConnectionState.STOPPED)
        scheduler.shutdownNow()
        ioExecutor.shutdownNow()
    }

    private companion object {
        const val BASE_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val MAX_BACKOFF_SHIFT = 5
        val FATAL_CLOSE_CODES = setOf(4000, 4001, 4003, 4401)
    }
}
