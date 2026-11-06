// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.core

import com.syncinit.transport.WireFrame

/** Exercises the shared native wire codecs and session lifecycle without opening sockets or touching audio. */
object NativeSessionSmokeTest {
    fun run(): String {
        val sessionId = 0x13572468L
        val receiverId = 7
        val token = ByteArray(16) { (it + 1).toByte() }

        NativeReceiverSession.create(
            ReceiverSessionConfig(
                sessionId = sessionId,
                receiverId = receiverId,
                token = token,
                minimumBufferedMs = 500L,
                maximumResidualStdUs = 250L
            )
        ).use { session ->
            val helloWire = NativeProtocol.encodeHelloFrame(token)
            val helloFrame = WireFrame.fromEncodedBytes(helloWire)
            check(helloFrame.messageType == NativeProtocol.HELLO_TYPE) {
                "native HELLO encoder produced the wrong message type"
            }
            check(session.onHelloFrame(helloFrame.toEncodedBytes()) == ReceiverSessionResult.Accepted) {
                "native HELLO frame validation failed"
            }

            val welcomeWire = checkNotNull(session.welcomeFrame()) { "native WELCOME was not produced" }
            val welcomeFrame = WireFrame.fromEncodedBytes(welcomeWire)
            val welcome = NativeProtocol.decodeWelcomeFrame(welcomeFrame)
            check(welcome.receiverId == receiverId && welcome.sessionId == sessionId) {
                "native WELCOME identity fields did not round-trip"
            }
            check(welcome.audioPort == 47_801 && welcome.timePort == 47_802) {
                "native WELCOME transport ports did not round-trip"
            }

            val audioHelloWire = NativeProtocol.encodeAudioHelloFrame(welcome)
            val audioHelloFrame = WireFrame.fromEncodedBytes(audioHelloWire)
            check(audioHelloFrame.messageType == NativeProtocol.AUDIO_HELLO_TYPE) {
                "native AUDIO_HELLO encoder produced the wrong message type"
            }
            check(
                session.onAudioHelloFrame(audioHelloFrame.toEncodedBytes()) ==
                    ReceiverSessionResult.Accepted
            ) { "native AUDIO_HELLO frame validation failed" }
            check(
                session.onReady(bufferedMs = 1_000L, syncStable = true, residualStdUs = 100L) ==
                    ReceiverSessionResult.Accepted
            ) { "native READY gate failed" }
            check(session.arm() == ReceiverSessionResult.Accepted) {
                "native receiver could not be armed"
            }
            check(session.state() == ReceiverSessionState.Armed) {
                "native receiver did not enter ARMED"
            }
        }

        return "JNI protocol and session smoke test passed"
    }
}
