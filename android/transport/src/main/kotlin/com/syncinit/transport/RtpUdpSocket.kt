// SPDX-License-Identifier: Apache-2.0
package com.syncinit.transport

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP v2 packet using the fixed 12-byte header (no CSRC list, extension or padding).
 * Payload bytes are codec-agnostic; this first transport slice does not imply Opus support.
 */
data class RtpPacket(
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val marker: Boolean = false,
    val payload: ByteArray
)

object RtpPacketCodec {
    const val HEADER_SIZE = 12
    const val MAX_DATAGRAM_SIZE = 1_200
    const val MAX_PAYLOAD_SIZE = MAX_DATAGRAM_SIZE - HEADER_SIZE
    const val VERSION = 2

    fun encode(packet: RtpPacket): ByteArray {
        require(packet.payloadType in 0..127) { "RTP payload type must be in 0..127" }
        require(packet.sequenceNumber in 0..0xffff) { "RTP sequence number must be uint16" }
        require(packet.timestamp in 0L..0xffff_ffffL) { "RTP timestamp must be uint32" }
        require(packet.ssrc in 0L..0xffff_ffffL) { "RTP SSRC must be uint32" }
        require(packet.payload.size <= MAX_PAYLOAD_SIZE) {
            "RTP payload exceeds the $MAX_PAYLOAD_SIZE-byte safe payload limit"
        }

        val bytes = ByteArray(HEADER_SIZE + packet.payload.size)
        bytes[0] = (VERSION shl 6).toByte()
        bytes[1] = ((if (packet.marker) 0x80 else 0) or packet.payloadType).toByte()
        writeU16(bytes, 2, packet.sequenceNumber)
        writeU32(bytes, 4, packet.timestamp)
        writeU32(bytes, 8, packet.ssrc)
        packet.payload.copyInto(bytes, HEADER_SIZE)
        return bytes
    }

    fun decode(bytes: ByteArray): RtpPacket {
        if (bytes.size < HEADER_SIZE) throw FrameProtocolException("RTP datagram is shorter than the fixed header")
        if (bytes.size > MAX_DATAGRAM_SIZE) throw FrameProtocolException("RTP datagram exceeds the safe 1200-byte limit")
        val first = bytes[0].toInt() and 0xff
        if ((first ushr 6) != VERSION) throw FrameProtocolException("unsupported RTP version")
        if ((first and 0x3f) != 0) {
            throw FrameProtocolException("RTP CSRC, extension and padding fields are not supported by this packet profile")
        }
        val second = bytes[1].toInt() and 0xff
        return RtpPacket(
            payloadType = second and 0x7f,
            marker = (second and 0x80) != 0,
            sequenceNumber = readU16(bytes, 2),
            timestamp = readU32(bytes, 4),
            ssrc = readU32(bytes, 8),
            payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
        )
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
}

data class ReceivedRtpPacket(
    val packet: RtpPacket,
    val source: InetSocketAddress,
    val receivedAtNs: Long
)

/** Unconnected UDP socket for RTP; caller owns peer/session/SSRC validation. */
class RtpUdpSocket(
    bindAddress: InetAddress,
    localPort: Int = 0,
    receiveTimeoutMs: Int = 0,
    datagramSocket: DatagramSocket? = null
) : Closeable {
    private val socket = datagramSocket ?: DatagramSocket(null)
    private val closed = AtomicBoolean(false)

    init {
        require(localPort in 0..65_535) { "localPort must be between 0 and 65535" }
        require(receiveTimeoutMs >= 0) { "receiveTimeoutMs must be zero or positive" }
        require(!socket.isBound && !socket.isConnected) { "injected RTP socket must be unbound and unconnected" }
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, localPort))
        socket.soTimeout = receiveTimeoutMs
    }

    val localPort: Int get() = socket.localPort
    val localAddress: String get() = socket.localAddress?.hostAddress ?: "unknown"
    val isBound: Boolean get() = socket.isBound && !socket.isClosed

    @Throws(IOException::class)
    fun send(packet: RtpPacket, destination: InetSocketAddress) {
        check(!closed.get()) { "RTP UDP socket is closed" }
        require(!destination.isUnresolved) { "RTP destination must be resolved" }
        require(destination.port in 1..65_535) { "RTP destination port must be between 1 and 65535" }
        val bytes = RtpPacketCodec.encode(packet)
        socket.send(DatagramPacket(bytes, bytes.size, destination))
    }

    @Throws(IOException::class)
    fun receive(): ReceivedRtpPacket {
        check(!closed.get()) { "RTP UDP socket is closed" }
        val buffer = ByteArray(RtpPacketCodec.MAX_DATAGRAM_SIZE + 1)
        val datagram = DatagramPacket(buffer, buffer.size)
        socket.receive(datagram)
        val receivedAtNs = System.nanoTime()
        if (datagram.length > RtpPacketCodec.MAX_DATAGRAM_SIZE) {
            throw FrameProtocolException("RTP datagram exceeds the safe 1200-byte limit")
        }
        val source = InetSocketAddress(datagram.address, datagram.port)
        return ReceivedRtpPacket(
            packet = RtpPacketCodec.decode(buffer.copyOf(datagram.length)),
            source = source,
            receivedAtNs = receivedAtNs
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}
