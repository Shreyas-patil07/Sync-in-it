package com.syncinit.app.core

import com.syncinit.transport.WireFrame

data class WelcomeParameters(
    val result: Int,
    val receiverId: Int,
    val sessionId: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val chunkFrames: Int,
    val codec: Int,
    val audioPort: Int,
    val timePort: Int,
    val startupLeadMs: Int
)

/**
 * Control-message payloads are encoded and decoded by the shared C++ protocol implementation.
 * Kotlin only transports complete frames; it does not duplicate the wire payload schema.
 */
object NativeProtocol {
    const val ANDROID_PLATFORM = 1
    const val HELLO_TYPE = 0x01
    const val WELCOME_TYPE = 0x02
    const val AUDIO_HELLO_TYPE = 0x03

    fun encodeHelloFrame(token: ByteArray): ByteArray {
        require(token.size == 16) { "session token must contain exactly 16 bytes" }
        return checkNotNull(
            NativeCore.nativeEncodeHelloFrame(token.copyOf(), ANDROID_PLATFORM)
        ) { "native HELLO encoder returned no frame" }
    }

    fun decodeWelcomeFrame(frame: WireFrame): WelcomeParameters {
        require(frame.messageType == WELCOME_TYPE) { "expected a WELCOME frame" }
        require(frame.flags == 0) { "WELCOME frame flags must be zero" }
        val values = checkNotNull(
            NativeCore.nativeDecodeWelcomeFrame(frame.toEncodedBytes())
        ) { "native WELCOME decoder returned no data" }
        check(values.size == 10) { "native WELCOME decoder returned an invalid field count" }
        val welcome = WelcomeParameters(
            result = values[0].toInt(),
            receiverId = values[1].toInt(),
            sessionId = values[2],
            sampleRateHz = values[3].toInt(),
            channels = values[4].toInt(),
            chunkFrames = values[5].toInt(),
            codec = values[6].toInt(),
            audioPort = values[7].toInt(),
            timePort = values[8].toInt(),
            startupLeadMs = values[9].toInt()
        )
        check(welcome.result == 0) { "master rejected HELLO with result ${welcome.result}" }
        require(welcome.receiverId in 0..65_535)
        require(welcome.sessionId in 0L..0xffff_ffffL)
        require(welcome.audioPort in 1..65_535)
        require(welcome.timePort in 1..65_535)
        return welcome
    }

    fun encodeAudioHelloFrame(welcome: WelcomeParameters): ByteArray =
        checkNotNull(
            NativeCore.nativeEncodeAudioHelloFrame(welcome.sessionId, welcome.receiverId)
        ) { "native AUDIO_HELLO encoder returned no frame" }
}
