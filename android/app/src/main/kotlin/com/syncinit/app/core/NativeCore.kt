// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.core

/** JNI entry points for the portable C++ session core. */
object NativeCore {
    init {
        System.loadLibrary("syncinit_jni")
    }

    external fun nativeCoreVersion(): Int

    external fun nativeCreatePcmBuffer(capacityFrames: Long, channels: Int, sessionId: Long): Long
    external fun nativeInsertPcmFrame(handle: Long, encodedFrame: ByteArray): Int
    external fun nativePcmBufferStats(handle: Long): LongArray?
    external fun nativeReadPcmBuffer(handle: Long, output: ShortArray): Long
    external fun nativeClosePcmBuffer(handle: Long)

    external fun nativeCreateDacClock(sampleRateHz: Int): Long
    external fun nativeAddDacTimestamp(handle: Long, framePosition: Long, timestampNs: Long): Boolean
    external fun nativeDacClockSnapshot(handle: Long): DoubleArray?
    external fun nativeResetDacClock(handle: Long)
    external fun nativeCloseDacClock(handle: Long)
    external fun nativeSeekPcmBuffer(handle: Long, firstSampleIndex: Long)

    external fun nativeEncodePcmChunkFrame(
        sessionId: Long,
        sequence: Long,
        sampleIndex: Long,
        frames: Int,
        channels: Int,
        pcm16LittleEndian: ByteArray
    ): ByteArray?

    external fun nativeEncodeHelloFrame(token: ByteArray, platform: Int): ByteArray?
    external fun nativeDecodeWelcomeFrame(encodedFrame: ByteArray): LongArray?
    external fun nativeEncodeAudioHelloFrame(sessionId: Long, receiverId: Int): ByteArray?
    external fun nativeOnHelloFrame(handle: Long, encodedFrame: ByteArray): Int
    external fun nativeOnAudioHelloFrame(handle: Long, encodedFrame: ByteArray): Int

    external fun nativeEncodeAnchorFrame(
        kind: Int,
        masterTimeNs: Long,
        sampleIndex: Long,
        playing: Boolean,
        crossfadeMs: Int
    ): ByteArray?

    external fun nativeDecodeAnchorFrame(encodedFrame: ByteArray): LongArray?

    external fun nativeEncodeReadyFrame(
        bufferedMs: Long,
        syncStable: Boolean,
        residualStdUs: Long
    ): ByteArray?

    external fun nativeOnReadyFrame(handle: Long, encodedFrame: ByteArray): Int
    external fun nativeTimeSyncResponseFrame(
        handle: Long,
        packet: ByteArray,
        t2Ns: Long,
        t3Ns: Long
    ): ByteArray?

    external fun nativeCreateTimeSyncClient(sessionId: Long, receiverId: Int): Long
    external fun nativeMakeTimeSyncRequest(handle: Long, t1Ns: Long): ByteArray?
    external fun nativeExpireTimeSyncRequests(handle: Long, earlierThanNs: Long)
    external fun nativeAcceptTimeSyncResponse(
        handle: Long,
        packet: ByteArray,
        t4Ns: Long
    ): DoubleArray?
    external fun nativeLastTimeSyncExchange(handle: Long): LongArray?
    external fun nativeCloseTimeSyncClient(handle: Long)

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
