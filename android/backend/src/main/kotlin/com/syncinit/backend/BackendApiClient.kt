// SPDX-License-Identifier: Apache-2.0
package com.syncinit.backend

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Synchronous REST client plus an asynchronous WebSocket event channel.
 *
 * Call REST methods from an IO thread. The client deliberately performs no automatic
 * retries because session creation and join are not idempotent operations.
 */
class BackendApiClient(
    baseUrl: String,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    private val base: HttpUrl = baseUrl.trim().toHttpUrlOrNullChecked()

    fun createSession(
        hostName: String = "Host",
        protocolVersion: Int = 1,
        maxSpeakers: Int = 8
    ): BackendSessionCreated {
        require(hostName.isNotBlank() && hostName.length <= 48)
        require(protocolVersion in 1..255)
        require(maxSpeakers in 1..8)

        val payload = JsonObject().apply {
            addProperty("host_name", hostName)
            addProperty("protocol_version", protocolVersion)
            addProperty("max_speakers", maxSpeakers)
        }
        val body = executeJson(
            jsonRequest("POST", endpoint("api", "v1", "sessions"), payload),
            expectedCode = 201
        )
        return BackendSessionCreated(
            sessionId = body.requiredString("session_id"),
            joinCode = body.requiredString("join_code"),
            hostToken = body.requiredString("host_token"),
            hostName = body.requiredString("host_name"),
            protocolVersion = body.requiredInt("protocol_version"),
            maxSpeakers = body.requiredInt("max_speakers"),
            state = body.requiredString("state"),
            createdAt = body.requiredDouble("created_at"),
            expiresAt = body.requiredDouble("expires_at")
        )
    }

    fun joinSession(
        joinCode: String,
        deviceName: String,
        platform: BackendPlatform = BackendPlatform.ANDROID,
        protocolVersion: Int = 1
    ): BackendDeviceJoined {
        val normalizedCode = joinCode.trim().uppercase()
        require(normalizedCode.matches(Regex("[A-HJ-NP-Z2-9]{8}"))) {
            "join code must contain eight valid characters"
        }
        require(deviceName.isNotBlank() && deviceName.length <= 48)
        require(protocolVersion in 1..255)

        val payload = JsonObject().apply {
            addProperty("join_code", normalizedCode)
            addProperty("device_name", deviceName)
            addProperty("platform", platform.wireValue)
            addProperty("protocol_version", protocolVersion)
        }
        val body = executeJson(
            jsonRequest("POST", endpoint("api", "v1", "sessions", "join"), payload),
            expectedCode = 201
        )
        return BackendDeviceJoined(
            sessionId = body.requiredString("session_id"),
            deviceId = body.requiredString("device_id"),
            deviceToken = body.requiredString("device_token"),
            state = body.requiredString("state"),
            heartbeatIntervalSeconds = body.requiredInt("heartbeat_interval_seconds")
        )
    }

    fun createLiveKitToken(sessionId: String, bearerToken: String): BackendLiveKitCredentials {
        val body = executeJson(
            authorizedRequest("POST", sessionPath(sessionId, "livekit-token"), bearerToken),
            expectedCode = 200
        )
        return BackendLiveKitCredentials(
            serverUrl = body.requiredString("server_url"),
            participantToken = body.requiredString("participant_token"),
            roomName = body.requiredString("room_name"),
            role = body.requiredString("role"),
            sampleRateHz = body.requiredInt("sample_rate_hz"),
            channels = body.requiredInt("channels"),
            chunkFrames = body.requiredInt("chunk_frames")
        )
    }

    fun estimateServerClockOffsetNs(
        sessionId: String,
        bearerToken: String,
        sampleCount: Int = 9
    ): Long {
        require(sampleCount in 3..9) { "sample count must be between 3 and 9" }
        val samples = ArrayList<Pair<Long, Long>>(sampleCount)
        repeat(sampleCount) {
            val requestStartedNs = System.nanoTime()
            val body = executeJson(
                authorizedRequest("GET", sessionPath(sessionId, "clock"), bearerToken),
                expectedCode = 200
            )
            val responseReceivedNs = System.nanoTime()
            val serverTimeNs = body.requiredLong("server_monotonic_ns")
            val roundTripNs = responseReceivedNs - requestStartedNs
            val localMidpointNs = requestStartedNs + roundTripNs / 2L
            samples += (serverTimeNs - localMidpointNs) to roundTripNs
        }
        val bestOffsets = samples
            .sortedBy { it.second }
            .take(sampleCount / 2 + 1)
            .map { it.first }
            .sorted()
        return bestOffsets[bestOffsets.size / 2]
    }

    fun scheduleSynchronizedPlayback(
        sessionId: String,
        hostToken: String
    ): BackendPlaybackSchedule {
        val body = executeJson(
            authorizedJsonRequest(
                "POST",
                sessionPath(sessionId, "playback", "schedule"),
                hostToken,
                JsonObject()
            ),
            expectedCode = 200
        )
        return BackendPlaybackSchedule(
            startAtServerMonotonicNs = body.requiredLong("start_at_server_monotonic_ns"),
            speakerCount = body.requiredInt("speaker_count")
        )
    }

    fun openHostMedia(
        sessionId: String,
        hostToken: String,
        listener: BackendHostMediaListener
    ): BackendHostMediaConnection {
        validateToken(hostToken)
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        val connection = BackendHostMediaConnection(hostToken, listener)
        val request = Request.Builder()
            .url(endpoint("api", "v1", "sessions", sessionId, "media", "host"))
            .build()
        val webSocket = httpClient.newWebSocket(request, connection.webSocketListener())
        connection.attach(webSocket)
        return connection
    }

    fun getSession(sessionId: String, hostToken: String): BackendSessionSnapshot {
        val body = executeJson(
            authorizedRequest("GET", sessionPath(sessionId), hostToken),
            expectedCode = 200
        )
        return BackendSessionSnapshot(
            sessionId = body.requiredString("session_id"),
            joinCode = body.requiredString("join_code"),
            hostName = body.requiredString("host_name"),
            protocolVersion = body.requiredInt("protocol_version"),
            maxSpeakers = body.requiredInt("max_speakers"),
            speakerCount = body.requiredInt("speaker_count"),
            state = body.requiredString("state"),
            createdAt = body.requiredDouble("created_at"),
            expiresAt = body.requiredDouble("expires_at")
        )
    }

    fun listDevices(sessionId: String, hostToken: String): List<BackendDevice> {
        val body = executeJson(
            authorizedRequest("GET", sessionPath(sessionId, "devices"), hostToken),
            expectedCode = 200
        )
        val devices = body.getAsJsonArray("devices")
            ?: throw IOException("Backend response is missing devices")
        return devices.map(::parseDevice)
    }

    fun heartbeat(sessionId: String, deviceToken: String): BackendHeartbeat {
        val body = executeJson(
            authorizedRequest(
                "POST",
                sessionPath(sessionId, "devices", "me", "heartbeat"),
                deviceToken
            ),
            expectedCode = 200
        )
        return BackendHeartbeat(
            deviceId = body.requiredString("device_id"),
            serverTime = body.requiredDouble("server_time")
        )
    }

    fun updateDeviceState(
        sessionId: String,
        deviceToken: String,
        state: BackendDeviceState,
        detail: String? = null
    ) {
        val payload = JsonObject().apply {
            addProperty("state", state.wireValue)
            detail?.let { addProperty("detail", it.take(240)) }
        }
        executeJson(
            authorizedJsonRequest(
                "PATCH",
                sessionPath(sessionId, "devices", "me", "state"),
                deviceToken,
                payload
            ),
            expectedCode = 200
        )
    }

    fun removeDevice(sessionId: String, hostToken: String, deviceId: String) {
        executeNoContent(
            authorizedRequest(
                "DELETE",
                sessionPath(sessionId, "devices", deviceId),
                hostToken
            )
        )
    }

    fun leaveSession(sessionId: String, deviceToken: String) {
        executeNoContent(
            authorizedRequest("DELETE", sessionPath(sessionId, "devices", "me"), deviceToken)
        )
    }

    fun closeSession(sessionId: String, hostToken: String) {
        executeNoContent(
            authorizedRequest("DELETE", sessionPath(sessionId), hostToken)
        )
    }

    /**
     * Opens the session WebSocket. The bearer token is sent in the first WebSocket frame,
     * never in the URL, query string or request headers.
     */
    fun openSessionEvents(
        sessionId: String,
        bearerToken: String,
        listener: BackendEventListener
    ): BackendEventConnection {
        validateToken(bearerToken)
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        val connection = BackendEventConnection()
        val request = Request.Builder()
            .url(endpoint("api", "v1", "sessions", sessionId, "ws"))
            .build()

        val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JsonObject().apply {
                    addProperty("type", "authenticate")
                    addProperty("token", bearerToken)
                }
                if (!webSocket.send(GSON.toJson(auth))) {
                    listener.onFailure(IOException("WebSocket rejected the authentication frame"))
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JsonParser.parseString(text)
                    if (!event.isJsonObject) throw IOException("Backend WebSocket event must be a JSON object")
                    val payload = event.asJsonObject
                    val type = payload.get("type")?.takeUnless { it.isJsonNull }?.asString
                        ?: throw IOException("Backend WebSocket event is missing type")
                    listener.onEvent(BackendEvent(type, payload))
                } catch (error: Exception) {
                    listener.onFailure(IOException("Invalid backend WebSocket event", error))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code?.let { " (HTTP $it)" }.orEmpty()
                listener.onFailure(IOException("Backend WebSocket failed$status", t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }
        })
        connection.attach(webSocket)
        return connection
    }

    private fun sessionPath(sessionId: String, vararg suffix: String): HttpUrl {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        return endpoint("api", "v1", "sessions", sessionId, *suffix)
    }

    private fun endpoint(vararg segments: String): HttpUrl =
        base.newBuilder().encodedPath("/").apply {
            segments.forEach(::addPathSegment)
        }.build()

    private fun authorizedRequest(method: String, url: HttpUrl, token: String): Request {
        validateToken(token)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .method(method, if (method == "POST" || method == "PATCH") EMPTY_BODY else null)
            .build()
    }

    private fun authorizedJsonRequest(
        method: String,
        url: HttpUrl,
        token: String,
        payload: JsonObject
    ): Request {
        validateToken(token)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .method(method, GSON.toJson(payload).toRequestBody(JSON))
            .build()
    }

    private fun jsonRequest(method: String, url: HttpUrl, payload: JsonObject): Request =
        Request.Builder()
            .url(url)
            .method(method, GSON.toJson(payload).toRequestBody(JSON))
            .build()

    private fun executeJson(request: Request, expectedCode: Int): JsonObject =
        httpClient.newCall(request).execute().use { response ->
            if (response.code != expectedCode) throw apiError(response.code, response.body?.string())
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
            if (parsed == null || !parsed.isJsonObject) {
                throw IOException("Backend returned an invalid JSON object")
            }
            parsed.asJsonObject
        }

    private fun executeNoContent(request: Request) {
        httpClient.newCall(request).execute().use { response ->
            if (response.code != 204) throw apiError(response.code, response.body?.string())
        }
    }

    private fun apiError(statusCode: Int, rawBody: String?): BackendApiException {
        val code = runCatching {
            rawBody?.let(JsonParser::parseString)?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("detail")?.asString
        }.getOrNull()?.take(120) ?: "request_failed"
        return BackendApiException(statusCode, code)
    }

    private fun parseDevice(element: JsonElement): BackendDevice {
        if (!element.isJsonObject) throw IOException("Backend device entry must be an object")
        val item = element.asJsonObject
        return BackendDevice(
            deviceId = item.requiredString("device_id"),
            deviceName = item.requiredString("device_name"),
            platform = BackendPlatform.entries.firstOrNull {
                it.wireValue == item.optionalString("platform")
            },
            state = BackendDeviceState.entries.firstOrNull {
                it.wireValue == item.optionalString("state")
            },
            stateDetail = item.optionalString("state_detail"),
            joinedAt = item.requiredDouble("joined_at"),
            lastSeenAt = item.requiredDouble("last_seen_at"),
            online = item.optionalBoolean("online"),
            transportConnected = item.optionalBoolean("transport_connected")
        )
    }

    private fun validateToken(token: String) {
        require(token.length in 32..256 && token.none(Char::isWhitespace)) {
            "bearer token must contain 32–256 non-whitespace characters"
        }
    }

    private companion object {
        val GSON = Gson()
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(JSON)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private fun String.toHttpUrlOrNullChecked(): HttpUrl {
    val parsed = toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Backend base URL must be a valid HTTP or HTTPS URL")
    require(parsed.scheme == "http" || parsed.scheme == "https")
    require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
        "Backend base URL must be an origin without a path, query or fragment"
    }
    return parsed
}

private fun JsonObject.requiredString(name: String): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString
        ?: throw IOException("Backend response is missing $name")

private fun JsonObject.requiredInt(name: String): Int =
    get(name)?.takeUnless { it.isJsonNull }?.asInt
        ?: throw IOException("Backend response is missing $name")

private fun JsonObject.requiredDouble(name: String): Double =
    get(name)?.takeUnless { it.isJsonNull }?.asDouble
        ?: throw IOException("Backend response is missing $name")

private fun JsonObject.requiredLong(name: String): Long =
    get(name)?.takeUnless { it.isJsonNull }?.asLong
        ?: throw IOException("Backend response is missing $name")

private fun JsonObject.optionalString(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.optionalBoolean(name: String): Boolean =
    get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
