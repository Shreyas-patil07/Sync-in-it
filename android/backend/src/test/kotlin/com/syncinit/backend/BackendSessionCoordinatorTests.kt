// SPDX-License-Identifier: Apache-2.0
package com.syncinit.backend

import com.google.gson.JsonParser
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun expectCoordinator(value: Boolean, message: String) {
    check(value) { message }
}

fun runBackendSessionCoordinatorTests() {
    hostCoordinatorForwardsAuthenticatedSnapshot()
    speakerCoordinatorFallsBackToRestHeartbeat()
}

private fun hostCoordinatorForwardsAuthenticatedSnapshot() {
    val server = MockWebServer()
    server.start()
    val authenticated = CountDownLatch(1)
    val snapshot = CountDownLatch(1)
    val state = AtomicReference(BackendConnectionState.STOPPED)
    val serverListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JsonParser.parseString(text).asJsonObject
            if (message.get("type")?.asString == "authenticate") {
                webSocket.send("""{"type":"authenticated","role":"host"}""")
                webSocket.send(
                    """{"type":"session.snapshot","session_id":"session-1","state":"active","devices":[]}"""
                )
            }
        }
    }

    try {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val coordinator = BackendSessionCoordinator(
            api = BackendApiClient(server.url("/").toString()),
            sessionId = "session-1",
            bearerToken = "h".repeat(43),
            role = BackendSessionRole.HOST,
            listener = object : BackendSessionCoordinatorListener {
                override fun onConnectionStateChanged(next: BackendConnectionState) {
                    state.set(next)
                    if (next == BackendConnectionState.CONNECTED) authenticated.countDown()
                }

                override fun onEvent(event: BackendEvent) {
                    if (event.type == "session.snapshot") snapshot.countDown()
                }

                override fun onFailure(error: Throwable) {
                    throw AssertionError("Host coordinator failed", error)
                }
            }
        )
        coordinator.start()

        expectCoordinator(authenticated.await(3, TimeUnit.SECONDS), "host coordinator reaches authenticated state")
        expectCoordinator(snapshot.await(3, TimeUnit.SECONDS), "host receives reconciliation snapshot after authentication")
        expectCoordinator(state.get() == BackendConnectionState.CONNECTED, "host coordinator reports connected")
        coordinator.close()
    } finally {
        server.shutdown()
    }
}

private fun speakerCoordinatorFallsBackToRestHeartbeat() {
    val server = MockWebServer()
    server.start()
    val token = "s".repeat(43)
    val serverListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Deliberately withhold the authenticated event to simulate a channel not yet ready.
        }
    }

    try {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"server_time":1000.0,"device_id":"device-1"}"""
            )
        )
        val coordinator = BackendSessionCoordinator(
            api = BackendApiClient(server.url("/").toString()),
            sessionId = "session-1",
            bearerToken = token,
            role = BackendSessionRole.SPEAKER,
            heartbeatIntervalSeconds = 1,
            listener = object : BackendSessionCoordinatorListener {
                override fun onConnectionStateChanged(state: BackendConnectionState) = Unit
                override fun onEvent(event: BackendEvent) = Unit
                override fun onFailure(error: Throwable) {
                    throw AssertionError("Speaker heartbeat fallback failed", error)
                }
            }
        )
        coordinator.start()

        val upgrade = server.takeRequest(3, TimeUnit.SECONDS)
            ?: error("WebSocket upgrade was not requested")
        val heartbeat = server.takeRequest(4, TimeUnit.SECONDS)
            ?: error("REST heartbeat fallback was not requested")

        expectCoordinator(upgrade.method == "GET", "Speaker first opens its WebSocket")
        expectCoordinator(
            heartbeat.path == "/api/v1/sessions/session-1/devices/me/heartbeat",
            "Speaker sends REST heartbeat while WebSocket is not authenticated"
        )
        expectCoordinator(
            heartbeat.getHeader("Authorization") == "Bearer $token",
            "REST heartbeat uses the device bearer token"
        )
        coordinator.close()
    } finally {
        server.shutdown()
    }
}
