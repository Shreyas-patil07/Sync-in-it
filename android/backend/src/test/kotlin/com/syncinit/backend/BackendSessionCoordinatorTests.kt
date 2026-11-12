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
    coordinatorReconnectsAfterTransientServerClose()
    coordinatorStopsAfterTerminalServerClose()
    speakerCoordinatorFallsBackToRestHeartbeat()
    speakerCoordinatorReconnectsWhenHeartbeatAcknowledgementsStop()
    speakerCoordinatorStaysConnectedWhenHeartbeatAcknowledgementsArrive()
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


private fun coordinatorReconnectsAfterTransientServerClose() {
    val server = MockWebServer()
    server.start()
    val authenticationCount = java.util.concurrent.atomic.AtomicInteger(0)
    val authenticatedTwice = CountDownLatch(2)
    val snapshots = CountDownLatch(1)
    val serverListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JsonParser.parseString(text).asJsonObject
            if (message.get("type")?.asString != "authenticate") return
            val count = authenticationCount.incrementAndGet()
            webSocket.send("""{"type":"authenticated","role":"host"}""")
            authenticatedTwice.countDown()
            if (count == 1) {
                // Let the authenticated event reach the client before simulating a transient
                // server restart; this keeps the test focused on reconnect behavior.
                Thread({
                    try {
                        Thread.sleep(150)
                        webSocket.close(1012, "simulated-server-restart")
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }, "syncinit-test-server-restart").apply {
                    isDaemon = true
                    start()
                }
            } else {
                webSocket.send(
                    """{"type":"session.snapshot","session_id":"session-1","state":"active","devices":[]}"""
                )
                snapshots.countDown()
            }
        }
    }

    try {
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        }
        val coordinator = BackendSessionCoordinator(
            api = BackendApiClient(server.url("/").toString()),
            sessionId = "session-1",
            bearerToken = "h".repeat(43),
            role = BackendSessionRole.HOST,
            listener = object : BackendSessionCoordinatorListener {
                override fun onConnectionStateChanged(state: BackendConnectionState) = Unit
                override fun onEvent(event: BackendEvent) = Unit
                override fun onFailure(error: Throwable) = Unit
            }
        )
        coordinator.start()

        expectCoordinator(authenticatedTwice.await(6, TimeUnit.SECONDS), "coordinator reconnects after transient server close")
        expectCoordinator(snapshots.await(3, TimeUnit.SECONDS), "reconnected host receives a fresh session snapshot")
        expectCoordinator(authenticationCount.get() == 2, "coordinator establishes exactly two authenticated connections")
        coordinator.close()
    } finally {
        server.shutdown()
    }
}

private fun coordinatorStopsAfterTerminalServerClose() {
    val server = MockWebServer()
    server.start()
    val failed = CountDownLatch(1)
    val authenticationCount = java.util.concurrent.atomic.AtomicInteger(0)
    val serverListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JsonParser.parseString(text).asJsonObject
            if (message.get("type")?.asString != "authenticate") return
            authenticationCount.incrementAndGet()
            webSocket.send("""{"type":"authenticated","role":"host"}""")
            webSocket.close(4000, "session-closed")
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
                override fun onConnectionStateChanged(state: BackendConnectionState) {
                    if (state == BackendConnectionState.FAILED) failed.countDown()
                }

                override fun onEvent(event: BackendEvent) = Unit
                override fun onFailure(error: Throwable) = Unit
            }
        )
        coordinator.start()

        expectCoordinator(failed.await(3, TimeUnit.SECONDS), "terminal close moves coordinator to failed state")
        expectCoordinator(server.takeRequest(1500, TimeUnit.MILLISECONDS) == null, "terminal close does not trigger reconnect")
        expectCoordinator(authenticationCount.get() == 1, "terminal close is authenticated only once")
        coordinator.close()
    } finally {
        server.shutdown()
    }
}

private fun speakerCoordinatorStaysConnectedWhenHeartbeatAcknowledgementsArrive() {
    val server = MockWebServer()
    server.start()
    val authenticated = CountDownLatch(1)
    val heartbeatAcks = CountDownLatch(2)
    val reconnectedWait = AtomicReference(false)
    val serverListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JsonParser.parseString(text).asJsonObject
            when (message.get("type")?.asString) {
                "authenticate" -> {
                    webSocket.send("""{"type":"authenticated","role":"speaker"}""")
                    authenticated.countDown()
                }
                "heartbeat" -> {
                    webSocket.send("""{"type":"heartbeat.ack","server_time":1000.0}""")
                    heartbeatAcks.countDown()
                }
            }
        }
    }

    try {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val coordinator = BackendSessionCoordinator(
            api = BackendApiClient(server.url("/").toString()),
            sessionId = "session-1",
            bearerToken = "s".repeat(43),
            role = BackendSessionRole.SPEAKER,
            heartbeatIntervalSeconds = 1,
            listener = object : BackendSessionCoordinatorListener {
                override fun onConnectionStateChanged(state: BackendConnectionState) {
                    if (state == BackendConnectionState.RECONNECT_WAIT) reconnectedWait.set(true)
                }

                override fun onEvent(event: BackendEvent) = Unit
                override fun onFailure(error: Throwable) = Unit
            }
        )
        coordinator.start()

        expectCoordinator(authenticated.await(3, TimeUnit.SECONDS), "Speaker authenticates before heartbeat ACK test")
        expectCoordinator(
            heartbeatAcks.await(5, TimeUnit.SECONDS),
            "Speaker receives heartbeat acknowledgements from the backend"
        )
        expectCoordinator(
            !reconnectedWait.get(),
            "valid heartbeat acknowledgements keep the backend connection connected"
        )
        coordinator.close()
    } finally {
        server.shutdown()
    }
}

private fun speakerCoordinatorReconnectsWhenHeartbeatAcknowledgementsStop() {
    val server = MockWebServer()
    server.start()
    val authenticated = CountDownLatch(1)
    val reconnecting = CountDownLatch(1)
    val serverListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JsonParser.parseString(text).asJsonObject
            if (message.get("type")?.asString == "authenticate") {
                webSocket.send("""{"type":"authenticated","role":"speaker"}""")
                authenticated.countDown()
            }
            // Deliberately withhold heartbeat.ack to simulate a half-open WebSocket.
        }
    }

    try {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val coordinator = BackendSessionCoordinator(
            api = BackendApiClient(server.url("/").toString()),
            sessionId = "session-1",
            bearerToken = "s".repeat(43),
            role = BackendSessionRole.SPEAKER,
            heartbeatIntervalSeconds = 1,
            listener = object : BackendSessionCoordinatorListener {
                override fun onConnectionStateChanged(state: BackendConnectionState) {
                    if (state == BackendConnectionState.RECONNECT_WAIT) reconnecting.countDown()
                }

                override fun onEvent(event: BackendEvent) = Unit
                override fun onFailure(error: Throwable) = Unit
            }
        )
        coordinator.start()

        expectCoordinator(
            authenticated.await(3, TimeUnit.SECONDS),
            "Speaker coordinator authenticates before testing heartbeat acknowledgements"
        )
        expectCoordinator(
            reconnecting.await(6, TimeUnit.SECONDS),
            "missing WebSocket heartbeat acknowledgements force a reconnect"
        )
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
