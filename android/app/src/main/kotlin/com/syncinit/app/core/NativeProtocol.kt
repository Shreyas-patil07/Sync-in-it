package com.syncinit.app.core

import com.syncinit.transport.WireFrame

data class PlaybackAnchor(
    val kind: Int,
    val masterTimeNs: Long,
    val sampleIndex: Long,
    val playing: Boolean,
    val crossfadeMs: Int
)

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
    const val READY_TYPE = 0x04
    const val ANCHOR_TYPE = 0x06

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

    fun encodeAnchorFrame(
        kind: Int,
        masterTimeNs: Long,
        sampleIndex: Long,
        playing: Boolean,
        crossfadeMs: Int = 0
    ): ByteArray = checkNotNull(
        NativeCore.nativeEncodeAnchorFrame(
            kind, masterTimeNs, sampleIndex, playing, crossfadeMs
        )
    ) { "native ANCHOR encoder returned no frame" }

    fun decodeAnchorFrame(frame: WireFrame): PlaybackAnchor {
        require(frame.messageType == ANCHOR_TYPE) { "expected an ANCHOR frame" }
        require(frame.flags == 0) { "ANCHOR frame flags must be zero" }
        val values = checkNotNull(
            NativeCore.nativeDecodeAnchorFrame(frame.toEncodedBytes())
        ) { "native ANCHOR decoder returned no data" }
        check(values.size == 5) { "native ANCHOR decoder returned an invalid field count" }
        val anchor = PlaybackAnchor(
            kind = values[0].toInt(),
            masterTimeNs = values[1],
            sampleIndex = values[2],
            playing = values[3] != 0L,
            crossfadeMs = values[4].toInt()
        )
        require(anchor.kind in 0..4) { "ANCHOR kind is outside the supported range" }
        require(anchor.masterTimeNs >= 0L && anchor.sampleIndex >= 0L) {
            "ANCHOR timestamps and sample index must be non-negative"
        }
        require(anchor.crossfadeMs in 0..65_535)
        return anchor
    }

    fun encodeReadyFrame(
        bufferedMs: Long,
        syncStable: Boolean,
        residualStdUs: Long
    ): ByteArray = checkNotNull(
        NativeCore.nativeEncodeReadyFrame(bufferedMs, syncStable, residualStdUs)
    ) { "native READY encoder returned no frame" }
}
