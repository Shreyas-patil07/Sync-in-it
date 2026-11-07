// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.core

import com.syncinit.transport.TcpFrameConnection
import com.syncinit.transport.TcpFrameServer
import com.syncinit.transport.UdpTimeSyncClient
import com.syncinit.transport.UdpTimeSyncServer
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises both TCP channels and the JNI state machine over device-local loopback. */
object NetworkHandshakeSmokeTest {
    fun run(): String {
        val loopback = InetAddress.getByName("127.0.0.1")
        val token = ByteArray(16) { (0x30 + it).toByte() }
        val sessionId = 0x24681357L
        val receiverId = 11
        val failure = ArrayBlockingQueue<Throwable>(1)

        TcpFrameServer(loopback, 0, acceptTimeoutMs = 5_000, connectionReadTimeoutMs = 5_000).use { controlServer ->
            TcpFrameServer(loopback, 0, acceptTimeoutMs = 5_000, connectionReadTimeoutMs = 5_000).use { audioServer ->
                NativeReceiverSession.create(
                    ReceiverSessionConfig(
                        sessionId = sessionId,
                        receiverId = receiverId,
                        token = token,
                        minimumBufferedMs = 500L,
                        audioPort = audioServer.localPort
                    )
                ).use { nativeSession ->
                    val master = Thread {
                        try {
                            controlServer.accept().use { control ->
                                val hello = checkNotNull(control.readFrame()) { "loopback HELLO was missing" }
                                check(nativeSession.onHelloFrame(hello.toEncodedBytes()) == ReceiverSessionResult.Accepted) {
                                    "loopback native HELLO admission failed"
                                }
                                control.writeEncodedFrame(checkNotNull(nativeSession.welcomeFrame()))

                                audioServer.accept().use { audio ->
                                    val audioHello = checkNotNull(audio.readFrame()) {
                                        "loopback AUDIO_HELLO was missing"
                                    }
                                    check(
                                        nativeSession.onAudioHelloFrame(audioHello.toEncodedBytes()) ==
                                            ReceiverSessionResult.Accepted
                                    ) { "loopback native AUDIO_HELLO admission failed" }
                                }
                            }
                        } catch (error: Throwable) {
                            failure.offer(error)
                        }
                    }.apply {
                        name = "syncinit-loopback-master"
                        isDaemon = true
                        start()
                    }

                    TcpFrameConnection.connect(
                        "127.0.0.1", controlServer.localPort,
                        connectTimeoutMs = 5_000, readTimeoutMs = 5_000
                    ).use { control ->
                        control.writeEncodedFrame(NativeProtocol.encodeHelloFrame(token))
                        val welcomeFrame = checkNotNull(control.readFrame()) { "loopback WELCOME was missing" }
                        val welcome = NativeProtocol.decodeWelcomeFrame(welcomeFrame)
                        check(welcome.sessionId == sessionId && welcome.receiverId == receiverId) {
                            "loopback WELCOME identity mismatch"
                        }
                        TcpFrameConnection.connect(
                            "127.0.0.1", welcome.audioPort,
                            connectTimeoutMs = 5_000, readTimeoutMs = 5_000
                        ).use { audio ->
                            audio.writeEncodedFrame(NativeProtocol.encodeAudioHelloFrame(welcome))
                        }
                    }

                    master.join(5_000)
                    check(!master.isAlive) { "loopback master handshake did not finish" }
                    failure.poll(1, TimeUnit.SECONDS)?.let { throw it }
                    check(nativeSession.state() == ReceiverSessionState.Synchronizing) {
                        "receiver did not enter Synchronizing after both TCP channels"
                    }

                    UdpTimeSyncServer(loopback, 0, receiveTimeoutMs = 5_000).use { timeServer ->
                        UdpTimeSyncClient(loopback, timeServer.localPort, receiveTimeoutMs = 5_000).use { timeClient ->
                            NativeTimeSyncClient.create(sessionId, receiverId).use { nativeClock ->
                                val t1Ns = System.nanoTime()
                                val request = nativeClock.makeRequest(t1Ns)
                                timeClient.send(request)

                                val incoming = timeServer.receive()
                                val response = checkNotNull(
                                    nativeSession.timeSyncResponse(
                                        incoming.bytes,
                                        incoming.receivedAtNs
                                    )
                                ) { "native UDP responder rejected the loopback request" }
                                timeServer.sendTimeSyncResponse(response, incoming.source)

                                val received = timeClient.receiveTimed()
                                val measurement = checkNotNull(
                                    nativeClock.acceptResponse(received.bytes, received.receivedAtNs)
                                ) { "native UDP client rejected the loopback response" }
                                check(measurement.samplesSeen == 1 && measurement.samplesUsed == 1) {
                                    "native estimator did not retain the accepted time-sync sample"
                                }
                                check(!measurement.acquisitionStable && measurement.stableStreak == 0) {
                                    "one time-sync sample must not satisfy the stability gate"
                                }
                            }
                        }
                    }
                }
            }
        }

        return "two-channel TCP + JNI + UDP time-sync loopback passed"
    }
}
