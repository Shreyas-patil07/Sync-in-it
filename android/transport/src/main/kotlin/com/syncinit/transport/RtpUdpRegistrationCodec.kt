// SPDX-License-Identifier: Apache-2.0
package com.syncinit.transport

import java.security.MessageDigest

data class RtpUdpRegistrationMessage(
    val acknowledgement: Boolean,
    val sessionId: Long,
    val receiverId: Int,
    val token: ByteArray,
    val nonce: Long
)

/**
 * Small authenticated rendezvous message carried in a reserved RTP payload type.
 *
 * The receiver first authenticates on the existing TCP control channel. It then
 * sends this token-bound UDP message so the Host can learn the receiver's UDP
 * source endpoint without trusting an SSRC or accepting an arbitrary LAN peer.
 */
object RtpUdpRegistrationCodec {
    const val PAYLOAD_TYPE = 127
    const val TOKEN_BYTES = 16
    const val PAYLOAD_SIZE = 32

    private const val VERSION = 1
    private const val HELLO = 1
    private const val ACK = 2
    private val MAGIC = byteArrayOf(0x53, 0x59, 0x49, 0x54) // "SYIT"

    fun encodeHello(
        sessionId: Long,
        receiverId: Int,
        token: ByteArray,
        nonce: Long
    ): RtpPacket = encode(
        acknowledgement = false,
        sessionId = sessionId,
        receiverId = receiverId,
        token = token,
        nonce = nonce
    )

    fun encodeAcknowledgement(message: RtpUdpRegistrationMessage): RtpPacket {
        require(!message.acknowledgement) { "acknowledgement must be generated from a HELLO" }
        return encode(
            acknowledgement = true,
            sessionId = message.sessionId,
            receiverId = message.receiverId,
            token = message.token,
            nonce = message.nonce
        )
    }

    fun decode(packet: RtpPacket): RtpUdpRegistrationMessage {
        if (packet.payloadType != PAYLOAD_TYPE) {
            throw FrameProtocolException("unexpected RTP payload type for UDP registration")
        }
        if (packet.payload.size != PAYLOAD_SIZE) {
            throw FrameProtocolException("RTP UDP registration payload has an invalid size")
        }
        val payload = packet.payload
        if (!payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw FrameProtocolException("RTP UDP registration magic is invalid")
        }
        if ((payload[4].toInt() and 0xff) != VERSION) {
            throw FrameProtocolException("RTP UDP registration version is unsupported")
        }
        val acknowledgement = when (payload[5].toInt() and 0xff) {
            HELLO -> false
            ACK -> true
            else -> throw FrameProtocolException("RTP UDP registration message type is invalid")
        }
        val receiverId = readU16(payload, 6)
        val token = payload.copyOfRange(8, 24)
        val nonce = readU64(payload, 24)
        return RtpUdpRegistrationMessage(
            acknowledgement = acknowledgement,
            sessionId = packet.ssrc,
            receiverId = receiverId,
            token = token,
            nonce = nonce
        )
    }

    fun matchesAcknowledgement(
        packet: RtpPacket,
        sessionId: Long,
        receiverId: Int,
        token: ByteArray,
        nonce: Long
    ): Boolean {
        if (token.size != TOKEN_BYTES) return false
        val message = runCatching { decode(packet) }.getOrNull() ?: return false
        return message.acknowledgement &&
            message.sessionId == sessionId &&
            message.receiverId == receiverId &&
            message.nonce == nonce &&
            MessageDigest.isEqual(message.token, token)
    }

    private fun encode(
        acknowledgement: Boolean,
        sessionId: Long,
        receiverId: Int,
        token: ByteArray,
        nonce: Long
    ): RtpPacket {
        require(sessionId in 0L..0xffff_ffffL) { "session ID must be uint32" }
        require(receiverId in 1..0xffff) { "receiver ID must be uint16 and non-zero" }
        require(token.size == TOKEN_BYTES) { "session token must contain exactly 16 bytes" }
        val payload = ByteArray(PAYLOAD_SIZE)
        MAGIC.copyInto(payload, 0)
        payload[4] = VERSION.toByte()
        payload[5] = (if (acknowledgement) ACK else HELLO).toByte()
        writeU16(payload, 6, receiverId)
        token.copyInto(payload, 8)
        writeU64(payload, 24, nonce)
        return RtpPacket(
            payloadType = PAYLOAD_TYPE,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = sessionId,
            payload = payload
        )
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun writeU64(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) bytes[offset + index] = (value ushr (56 - index * 8)).toByte()
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
        return value
    }
}
