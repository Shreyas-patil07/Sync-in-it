package com.syncinit.app.core

/** Exercises the JNI session lifecycle locally without opening sockets or touching audio. */
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
            check(
                session.onHello(protocolVersion = 1, token = token, platform = 1) ==
                    ReceiverSessionResult.Accepted
            ) { "native HELLO validation failed" }

            val welcome = checkNotNull(session.welcomeFrame()) { "native WELCOME was not produced" }
            check(welcome.size >= 6 && (welcome[4].toInt() and 0xff) == 0x02) {
                "native WELCOME frame has an invalid message type"
            }

            check(
                session.onAudioHello(sessionId, receiverId) == ReceiverSessionResult.Accepted
            ) { "native AUDIO_HELLO validation failed" }
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

        return "JNI session smoke test passed"
    }
}
