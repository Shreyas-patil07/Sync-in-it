package com.syncinit.app.core

import java.io.Closeable

enum class ReceiverSessionState {
    AwaitingHello,
    AwaitingAudioHello,
    Synchronizing,
    Ready,
    Armed,
    Playing,
    Paused,
    Degraded,
    Closed
}

enum class ReceiverSessionResult {
    Accepted,
    WrongState,
    UnsupportedProtocolVersion,
    InvalidToken,
    UnsupportedPlatform,
    WrongSession,
    WrongReceiver,
    ClockNotStable,
    BufferInsufficient,
    ClockResidualTooHigh,
    InvalidAnchor,
    Closed
}

data class ReceiverSessionConfig(
    val sessionId: Long,
    val receiverId: Int,
    val token: ByteArray,
    val minimumBufferedMs: Long = 3_000L,
    val maximumResidualStdUs: Long = 250L,
    val audioPort: Int = 47_801,
    val timePort: Int = 47_802,
    val startupLeadMs: Int = 1_000
) {
    init {
        require(sessionId in 0L..0xffff_ffffL)
        require(receiverId in 0..0xffff)
        require(token.size == 16) { "session token must contain exactly 16 bytes" }
        require(minimumBufferedMs in 0L..0xffff_ffffL)
        require(maximumResidualStdUs in 0L..0xffff_ffffL)
        require(audioPort in 1..65_535)
        require(timePort in 1..65_535)
        require(startupLeadMs in 0..65_535)
    }
}

/** A thread-safe Kotlin facade over one native master-side ReceiverSession. */
class NativeReceiverSession private constructor(private var nativeHandle: Long) : Closeable {
    @Synchronized
    fun onHelloFrame(encodedFrame: ByteArray): ReceiverSessionResult {
        checkOpen()
        require(encodedFrame.size in 6..(65_536 + 4)) { "encoded HELLO frame size is invalid" }
        return result(NativeCore.nativeOnHelloFrame(nativeHandle, encodedFrame.copyOf()))
    }

    @Synchronized
    fun onAudioHelloFrame(encodedFrame: ByteArray): ReceiverSessionResult {
        checkOpen()
        require(encodedFrame.size in 6..(65_536 + 4)) { "encoded AUDIO_HELLO frame size is invalid" }
        return result(NativeCore.nativeOnAudioHelloFrame(nativeHandle, encodedFrame.copyOf()))
    }

    @Synchronized
    fun onReadyFrame(encodedFrame: ByteArray): ReceiverSessionResult {
        checkOpen()
        require(encodedFrame.size in 6..(65_536 + 4)) { "encoded READY frame size is invalid" }
        return result(NativeCore.nativeOnReadyFrame(nativeHandle, encodedFrame.copyOf()))
    }

    @Synchronized
    fun timeSyncResponse(packet: ByteArray, t2Ns: Long, t3Ns: Long): ByteArray? {
        checkOpen()
        require(packet.size == 38) { "time-sync request must be exactly 38 bytes" }
        require(t2Ns >= 0L && t3Ns >= t2Ns) { "time-sync server timestamps are invalid" }
        return NativeCore.nativeTimeSyncResponseFrame(
            nativeHandle, packet.copyOf(), t2Ns, t3Ns
        )
    }

    @Synchronized
    fun onHello(protocolVersion: Int, token: ByteArray, platform: Int): ReceiverSessionResult {
        checkOpen()
        require(token.size == 16) { "session token must contain exactly 16 bytes" }
        require(protocolVersion in 0..65_535)
        require(platform in 0..255)
        return result(NativeCore.nativeOnHello(nativeHandle, protocolVersion, token, platform))
    }

    @Synchronized
    fun welcomeFrame(): ByteArray? {
        checkOpen()
        return NativeCore.nativeWelcomeFrame(nativeHandle)
    }

    @Synchronized
    fun onAudioHello(sessionId: Long, receiverId: Int): ReceiverSessionResult {
        checkOpen()
        require(sessionId in 0L..0xffff_ffffL)
        require(receiverId in 0..0xffff)
        return result(NativeCore.nativeOnAudioHello(nativeHandle, sessionId, receiverId))
    }

    @Synchronized
    fun onReady(bufferedMs: Long, syncStable: Boolean, residualStdUs: Long): ReceiverSessionResult {
        checkOpen()
        require(bufferedMs in 0L..0xffff_ffffL)
        require(residualStdUs in 0L..0xffff_ffffL)
        return result(NativeCore.nativeOnReady(nativeHandle, bufferedMs, syncStable, residualStdUs))
    }

    @Synchronized
    fun arm(): ReceiverSessionResult {
        checkOpen()
        return result(NativeCore.nativeArm(nativeHandle))
    }

    @Synchronized
    fun onAnchor(
        kind: Int,
        masterTimeNs: Long,
        sampleIndex: Long,
        playing: Boolean,
        crossfadeMs: Int = 0
    ): ReceiverSessionResult {
        checkOpen()
        require(kind in 0..255)
        require(crossfadeMs in 0..65_535)
        return result(
            NativeCore.nativeOnAnchor(
                nativeHandle, kind, masterTimeNs, sampleIndex, playing, crossfadeMs
            )
        )
    }

    @Synchronized
    fun markDegraded(): ReceiverSessionResult {
        checkOpen()
        return result(NativeCore.nativeMarkDegraded(nativeHandle))
    }

    @Synchronized
    fun beginResynchronization(): ReceiverSessionResult {
        checkOpen()
        return result(NativeCore.nativeBeginResynchronization(nativeHandle))
    }

    @Synchronized
    fun state(): ReceiverSessionState {
        checkOpen()
        val value = NativeCore.nativeState(nativeHandle)
        return ReceiverSessionState.entries.getOrNull(value)
            ?: error("Unknown native receiver state: $value")
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            NativeCore.nativeCloseSession(handle)
        }
    }

    private fun checkOpen() {
        check(nativeHandle != 0L) { "native receiver session is closed" }
    }

    private fun result(value: Int): ReceiverSessionResult =
        ReceiverSessionResult.entries.getOrNull(value)
            ?: error("Unknown native receiver result: $value")

    companion object {
        fun create(config: ReceiverSessionConfig): NativeReceiverSession {
            val handle = NativeCore.nativeCreateSession(
                config.sessionId,
                config.receiverId,
                config.token.copyOf(),
                config.minimumBufferedMs,
                config.maximumResidualStdUs,
                config.audioPort,
                config.timePort,
                config.startupLeadMs
            )
            check(handle != 0L) { "native receiver session allocation failed" }
            return NativeReceiverSession(handle)
        }
    }
}
