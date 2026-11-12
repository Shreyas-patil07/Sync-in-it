// SPDX-License-Identifier: Apache-2.0
package com.syncinit.backend

import com.google.gson.JsonObject

enum class BackendPlatform(val wireValue: String) {
    ANDROID("android"),
    IOS("ios"),
    OTHER("other")
}

enum class BackendDeviceState(val wireValue: String) {
    JOINING("joining"),
    SYNCING("syncing"),
    BUFFERING("buffering"),
    READY("ready"),
    PLAYING("playing"),
    DEGRADED("degraded"),
    DISCONNECTED("disconnected")
}

enum class BackendControlCommand(val wireValue: String) {
    REFRESH_STATUS("refresh_status"),
    REQUEST_DIAGNOSTICS("request_diagnostics"),
    REQUEST_CLOCK_SYNC("request_clock_sync")
}

data class BackendSessionCreated(
    val sessionId: String,
    val joinCode: String,
    val hostToken: String,
    val hostName: String,
    val protocolVersion: Int,
    val maxSpeakers: Int,
    val state: String,
    val createdAt: Double,
    val expiresAt: Double
)

data class BackendDeviceJoined(
    val sessionId: String,
    val deviceId: String,
    val deviceToken: String,
    val state: String,
    val heartbeatIntervalSeconds: Int
)

data class BackendSessionSnapshot(
    val sessionId: String,
    val joinCode: String,
    val hostName: String,
    val protocolVersion: Int,
    val maxSpeakers: Int,
    val speakerCount: Int,
    val state: String,
    val createdAt: Double,
    val expiresAt: Double
)

data class BackendDevice(
    val deviceId: String,
    val deviceName: String,
    val platform: BackendPlatform?,
    val state: BackendDeviceState?,
    val stateDetail: String?,
    val joinedAt: Double,
    val lastSeenAt: Double,
    val online: Boolean,
    val transportConnected: Boolean
)

data class BackendHeartbeat(
    val deviceId: String,
    val serverTime: Double
)

data class BackendPlaybackSchedule(
    val startAtServerMonotonicNs: Long,
    val speakerCount: Int
)

/** A validated server event. Payload retains all event fields for forward compatibility. */
data class BackendEvent(
    val type: String,
    val payload: JsonObject
) {
    fun longField(name: String): Long? = runCatching {
        payload.get(name)?.takeUnless { it.isJsonNull }?.asLong
    }.getOrNull()
}

interface BackendEventListener {
    fun onEvent(event: BackendEvent)
    fun onFailure(error: Throwable)
    fun onClosed(code: Int, reason: String) {}
}

class BackendApiException(
    val statusCode: Int,
    val errorCode: String
) : java.io.IOException("Backend API returned HTTP $statusCode: $errorCode")
