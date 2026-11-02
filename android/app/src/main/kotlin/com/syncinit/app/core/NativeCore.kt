package com.syncinit.app.core

/** JNI entry points for the portable C++ session core. */
object NativeCore {
    init {
        System.loadLibrary("syncinit_jni")
    }

    external fun nativeCoreVersion(): Int

    external fun nativeEncodeHelloFrame(token: ByteArray, platform: Int): ByteArray?
    external fun nativeDecodeWelcomeFrame(encodedFrame: ByteArray): LongArray?
    external fun nativeEncodeAudioHelloFrame(sessionId: Long, receiverId: Int): ByteArray?
    external fun nativeOnHelloFrame(handle: Long, encodedFrame: ByteArray): Int
    external fun nativeOnAudioHelloFrame(handle: Long, encodedFrame: ByteArray): Int

    external fun nativeCreateSession(
        sessionId: Long,
        receiverId: Int,
        token: ByteArray,
        minimumBufferedMs: Long,
        maximumResidualStdUs: Long,
        audioPort: Int,
        timePort: Int,
        startupLeadMs: Int
    ): Long

    external fun nativeOnHello(
        handle: Long,
        protocolVersion: Int,
        token: ByteArray,
        platform: Int
    ): Int

    external fun nativeWelcomeFrame(handle: Long): ByteArray?

    external fun nativeOnAudioHello(handle: Long, sessionId: Long, receiverId: Int): Int

    external fun nativeOnReady(
        handle: Long,
        bufferedMs: Long,
        syncStable: Boolean,
        residualStdUs: Long
    ): Int

    external fun nativeArm(handle: Long): Int

    external fun nativeOnAnchor(
        handle: Long,
        kind: Int,
        masterTimeNs: Long,
        sampleIndex: Long,
        playing: Boolean,
        crossfadeMs: Int
    ): Int

    external fun nativeMarkDegraded(handle: Long): Int
    external fun nativeBeginResynchronization(handle: Long): Int
    external fun nativeState(handle: Long): Int
    external fun nativeCloseSession(handle: Long)
}
