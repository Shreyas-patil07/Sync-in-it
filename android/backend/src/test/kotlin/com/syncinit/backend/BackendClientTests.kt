// SPDX-License-Identifier: Apache-2.0
package com.syncinit.backend

import com.google.gson.JsonParser
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun expect(value: Boolean, message: String) {
    check(value) { message }
}

private fun createSessionUsesExpectedContract() {
    val server = MockWebServer()
    server.start()
    try {
        server.enqueue(
            MockResponse.Builder().code(201).body(
                """
                {
                  "session_id":"session-123",
                  "join_code":"ABCD2345",
                  "host_token":"hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh",
                  "host_name":"Living room",
                  "protocol_version":1,
                  "max_speakers":8,
                  "state":"active",
                  "created_at":1000.0,
                  "expires_at":8200.0
                }
                """.trimIndent()
            ).build()
        )

        val client = BackendApiClient(server.url("/").toString())
        val session = client.createSession(hostName = "Living room", maxSpeakers = 8)
        val request = server.takeRequest(3, TimeUnit.SECONDS)
            ?: error("create-session request was not received")
        val body = JsonParser.parseString(request.body!!.utf8()).asJsonObject

        expect(request.method == "POST", "session creation uses POST")
        expect(request.path == "/api/v1/sessions", "session creation uses the v1 endpoint")
        expect(body.get("host_name").asString == "Living room", "host name is serialized")
        expect(body.get("protocol_version").asInt == 1, "protocol version is serialized")
        expect(body.get("max_speakers").asInt == 8, "speaker limit is serialized")
        expect(session.joinCode == "ABCD2345", "session response is decoded")
    } finally {
        server.close()
    }
}

private fun joinSendsNormalizedCodeAndPlatform() {
    val server = MockWebServer()
    server.start()
    try {
        server.enqueue(
            MockResponse.Builder().code(201).body(
                """
                {
                  "session_id":"session-123",
                  "device_id":"device-456",
                  "device_token":"ddddddddddddddddddddddddddddddddddddddddddd",
                  "state":"joining",
                  "heartbeat_interval_seconds":5
                }
                """.trimIndent()
            ).build()
        )

        val joined = BackendApiClient(server.url("/").toString()).joinSession(
            joinCode = "abcd2345",
            deviceName = "Speaker One",
            platform = BackendPlatform.ANDROID
        )
        val request = server.takeRequest(3, TimeUnit.SECONDS)
            ?: error("join request was not received")
        val body = JsonParser.parseString(request.body!!.utf8()).asJsonObject

        expect(request.path == "/api/v1/sessions/join", "join uses the v1 endpoint")
        expect(body.get("join_code").asString == "ABCD2345", "join code is normalized")
        expect(body.get("platform").asString == "android", "platform is serialized")
        expect(joined.deviceId == "device-456", "device response is decoded")
    } finally {
        server.close()
    }
}

private fun hostAuthorizationAndConflictArePreserved() {
    val server = MockWebServer()
    server.start()
    try {
        server.enqueue(
            MockResponse.Builder().code(409)
                .body("""{"detail":"protocol_version_mismatch"}""")
                .build()
        )
        val token = "h".repeat(43)
        var apiError: BackendApiException? = null
        try {
            BackendApiClient(server.url("/").toString()).getSession("session-123", token)
        } catch (caught: BackendApiException) {
            apiError = caught
        }
        val request = server.takeRequest(3, TimeUnit.SECONDS)
            ?: error("session request was not received")

        expect(request.headers["Authorization"] == "Bearer $token", "host bearer token stays in the authorization header")
        expect(request.path == "/api/v1/sessions/session-123", "session path is correctly encoded")
        expect(apiError?.statusCode == 409, "HTTP conflict status is preserved")
        expect(apiError?.errorCode == "protocol_version_mismatch", "backend detail code is preserved")
    } finally {
        server.close()
    }
}

private fun websocketAuthenticatesInFirstFrameAndSendsCommands() {
    val server = MockWebServer()
    server.start()
    val received = ArrayBlockingQueue<String>(4)
    val authenticated = CountDownLatch(1)
    val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("""{"type":"authenticated","role":"host"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.offer(text)
        }
    }

    try {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(serverListener).build())
        val events = ArrayBlockingQueue<BackendEvent>(2)
        val failure = ArrayBlockingQueue<Throwable>(1)
        val token = "s".repeat(43)
        val connection = BackendApiClient(server.url("/").toString()).openSessionEvents(
            sessionId = "session-123",
            bearerToken = token,
            listener = object : BackendEventListener {
                override fun onEvent(event: BackendEvent) {
                    events.offer(event)
                    if (event.type == "authenticated") authenticated.countDown()
                }

                override fun onFailure(error: Throwable) {
                    failure.offer(error)
                }
            }
        )

        val request = server.takeRequest(3, TimeUnit.SECONDS)
            ?: error("WebSocket upgrade request was not received")
        val authFrame = received.poll(3, TimeUnit.SECONDS)
            ?: error("WebSocket authentication frame was not received")

        expect(request.path == "/api/v1/sessions/session-123/ws", "WebSocket uses the session event route")
        expect(request.path!!.contains(token).not(), "bearer token is not put in the URL")
        val auth = JsonParser.parseString(authFrame).asJsonObject
        expect(auth.get("type").asString == "authenticate", "authentication is the first client frame")
        expect(auth.get("token").asString == token, "authentication token is sent in the frame")
        expect(authenticated.await(3, TimeUnit.SECONDS), "server events reach the client listener")

        expect(
            connection.sendControlCommand(BackendControlCommand.REQUEST_CLOCK_SYNC, listOf("device-1")),
            "host control command is queued"
        )
        val commandFrame = received.poll(3, TimeUnit.SECONDS)
            ?: error("control command was not received")
        val command = JsonParser.parseString(commandFrame).asJsonObject
        expect(command.get("type").asString == "control.command", "command uses the backend event schema")
        expect(command.get("command").asString == "request_clock_sync", "clock-sync command is serialized")
        expect(command.getAsJsonArray("target_device_ids").first().asString == "device-1", "target device is serialized")
        failure.poll()?.let { throw it }
        connection.close()
    } finally {
        server.close()
    }
}

fun main() {
    createSessionUsesExpectedContract()
    joinSendsNormalizedCodeAndPlatform()
    hostAuthorizationAndConflictArePreserved()
    websocketAuthenticatesInFirstFrameAndSendsCommands()
    println("Backend client contract tests passed.")
}
