// SPDX-License-Identifier: Apache-2.0
package com.syncinit.backend

import com.google.gson.JsonObject
import com.google.gson.Gson
import okhttp3.WebSocket
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated session event channel.
 *
 * This channel is for presence, coarse state and operational commands only.
 * Host PCM uses the dedicated authenticated media WebSocket and LiveKit; media is never
 * sent through this event channel.
 */
class BackendEventConnection internal constructor() : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null

    internal fun attach(webSocket: WebSocket) {
        socket = webSocket
        if (closed.get()) webSocket.cancel()
    }

    fun heartbeat(): Boolean = send(JsonObject().apply {
        addProperty("type", "heartbeat")
    })

    fun updateState(state: BackendDeviceState, detail: String? = null): Boolean =
        send(JsonObject().apply {
            addProperty("type", "device.state")
            addProperty("state", state.wireValue)
            detail?.let { addProperty("detail", it.take(240)) }
        })

    fun sendControlCommand(
        command: BackendControlCommand,
        targetDeviceIds: List<String>? = null,
        commandId: String? = null
    ): Boolean = send(JsonObject().apply {
        addProperty("type", "control.command")
        addProperty("command", command.wireValue)
        commandId?.takeIf { it.isNotBlank() }?.let { addProperty("command_id", it.take(64)) }
        targetDeviceIds?.let { targets ->
            require(targets.size <= 8) { "at most eight target devices are supported" }
            add("target_device_ids", Gson().toJsonTree(targets.distinct()))
        }
    })

    fun acknowledgeCommand(
        commandId: String,
        accepted: Boolean,
        detail: String? = null
    ): Boolean {
        require(commandId.isNotBlank() && commandId.length <= 64) {
            "command ID must contain 1–64 characters"
        }
        return send(JsonObject().apply {
            addProperty("type", "command.ack")
            addProperty("command_id", commandId)
            addProperty("accepted", accepted)
            detail?.let { addProperty("detail", it.take(240)) }
        })
    }

    private fun send(message: JsonObject): Boolean {
        if (closed.get()) return false
        val encoded = Gson().toJson(message)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "backend WebSocket message exceeds 16 KiB"
        }
        return socket?.send(encoded) == true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "client-closed")
    }

    private companion object {
        const val MAX_MESSAGE_BYTES = 16 * 1024
    }
}
