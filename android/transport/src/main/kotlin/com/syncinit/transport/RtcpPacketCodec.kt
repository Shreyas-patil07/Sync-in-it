// SPDX-License-Identifier: Apache-2.0
package com.syncinit.transport

/** RTCP sender report (RFC 3550) for mapping an RTP media timestamp to an NTP wall-clock instant. */
data class RtcpSenderReport(
    val ssrc: Long,
    val ntpSeconds: Long,
    val ntpFraction: Long,
    val rtpTimestamp: Long,
    val packetCount: Long,
    val octetCount: Long
)

/** Minimal RTCP receiver report with no report blocks; loss/jitter blocks are a later layer. */
data class RtcpReceiverReport(val ssrc: Long)

object RtcpPacketCodec {
    const val VERSION = 2
    const val SENDER_REPORT_TYPE = 200
    const val RECEIVER_REPORT_TYPE = 201
    const val SENDER_REPORT_SIZE = 28
    const val RECEIVER_REPORT_SIZE = 8

    fun encodeSenderReport(report: RtcpSenderReport): ByteArray {
        require(report.ssrc in 0L..0xffff_ffffL) { "RTCP SSRC must be uint32" }
        require(report.ntpSeconds in 0L..0xffff_ffffL) { "NTP seconds must be uint32" }
        require(report.ntpFraction in 0L..0xffff_ffffL) { "NTP fraction must be uint32" }
        require(report.rtpTimestamp in 0L..0xffff_ffffL) { "RTP timestamp must be uint32" }
        require(report.packetCount in 0L..0xffff_ffffL) { "RTCP packet count must be uint32" }
        require(report.octetCount in 0L..0xffff_ffffL) { "RTCP octet count must be uint32" }
        return ByteArray(SENDER_REPORT_SIZE).apply {
            this[0] = 0x80.toByte()
            this[1] = SENDER_REPORT_TYPE.toByte()
            writeU16(this, 2, SENDER_REPORT_SIZE / 4 - 1)
            writeU32(this, 4, report.ssrc)
            writeU32(this, 8, report.ntpSeconds)
            writeU32(this, 12, report.ntpFraction)
            writeU32(this, 16, report.rtpTimestamp)
            writeU32(this, 20, report.packetCount)
            writeU32(this, 24, report.octetCount)
        }
    }

    fun decodeSenderReport(bytes: ByteArray): RtcpSenderReport {
        validateHeader(bytes, SENDER_REPORT_TYPE, SENDER_REPORT_SIZE)
        if ((bytes[0].toInt() and 0x1f) != 0) throw FrameProtocolException("RTCP sender report report-blocks are not supported by this profile")
        return RtcpSenderReport(
            ssrc = readU32(bytes, 4),
            ntpSeconds = readU32(bytes, 8),
            ntpFraction = readU32(bytes, 12),
            rtpTimestamp = readU32(bytes, 16),
            packetCount = readU32(bytes, 20),
            octetCount = readU32(bytes, 24)
        )
    }

    fun encodeReceiverReport(report: RtcpReceiverReport): ByteArray {
        require(report.ssrc in 0L..0xffff_ffffL) { "RTCP SSRC must be uint32" }
        return ByteArray(RECEIVER_REPORT_SIZE).apply {
            this[0] = 0x80.toByte()
            this[1] = RECEIVER_REPORT_TYPE.toByte()
            writeU16(this, 2, RECEIVER_REPORT_SIZE / 4 - 1)
            writeU32(this, 4, report.ssrc)
        }
    }

    fun decodeReceiverReport(bytes: ByteArray): RtcpReceiverReport {
        validateHeader(bytes, RECEIVER_REPORT_TYPE, RECEIVER_REPORT_SIZE)
        if ((bytes[0].toInt() and 0x1f) != 0) throw FrameProtocolException("RTCP receiver report blocks are not supported by this profile")
        return RtcpReceiverReport(readU32(bytes, 4))
    }

    private fun validateHeader(bytes: ByteArray, packetType: Int, expectedSize: Int) {
        if (bytes.size != expectedSize) throw FrameProtocolException("RTCP packet must be exactly $expectedSize bytes for this profile")
        val first = bytes[0].toInt() and 0xff
        if ((first ushr 6) != VERSION) throw FrameProtocolException("unsupported RTCP version")
        if ((first and 0x20) != 0) throw FrameProtocolException("RTCP padding is not supported by this profile")
        if ((bytes[1].toInt() and 0xff) != packetType) throw FrameProtocolException("unexpected RTCP packet type")
        if (readU16(bytes, 2) != expectedSize / 4 - 1) throw FrameProtocolException("RTCP length field does not match packet size")
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


data class ReceivedRtcpDatagram(
    val bytes: ByteArray,
    val source: java.net.InetSocketAddress,
    val receivedAtNs: Long
)

/** Dedicated RTCP UDP socket; packet semantics are validated by RtcpPacketCodec. */
class RtcpUdpSocket(
    bindAddress: java.net.InetAddress,
    localPort: Int = 0,
    receiveTimeoutMs: Int = 0
) : java.io.Closeable {
    private val socket = java.net.DatagramSocket(null)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        require(localPort in 0..65_535)
        require(receiveTimeoutMs >= 0)
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(bindAddress, localPort))
        socket.soTimeout = receiveTimeoutMs
    }

    val localPort: Int get() = socket.localPort

    fun sendSenderReport(report: RtcpSenderReport, destination: java.net.InetSocketAddress) =
        send(RtcpPacketCodec.encodeSenderReport(report), destination)

    fun sendReceiverReport(report: RtcpReceiverReport, destination: java.net.InetSocketAddress) =
        send(RtcpPacketCodec.encodeReceiverReport(report), destination)

    fun send(bytes: ByteArray, destination: java.net.InetSocketAddress) {
        check(!closed.get()) { "RTCP UDP socket is closed" }
        require(!destination.isUnresolved && destination.port in 1..65_535)
        val packet = when ((bytes.getOrNull(1)?.toInt() ?: -1) and 0xff) {
            RtcpPacketCodec.SENDER_REPORT_TYPE -> RtcpPacketCodec.decodeSenderReport(bytes)
            RtcpPacketCodec.RECEIVER_REPORT_TYPE -> RtcpPacketCodec.decodeReceiverReport(bytes)
            else -> throw IllegalArgumentException("unsupported RTCP packet type")
        }
        @Suppress("UNUSED_VARIABLE") val validated = packet
        socket.send(java.net.DatagramPacket(bytes, bytes.size, destination))
    }

    fun receive(): ReceivedRtcpDatagram {
        check(!closed.get()) { "RTCP UDP socket is closed" }
        val buffer = ByteArray(RtcpPacketCodec.SENDER_REPORT_SIZE + 1)
        val packet = java.net.DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        val receivedAtNs = System.nanoTime()
        val bytes = buffer.copyOf(packet.length)
        when ((bytes.getOrNull(1)?.toInt() ?: -1) and 0xff) {
            RtcpPacketCodec.SENDER_REPORT_TYPE -> RtcpPacketCodec.decodeSenderReport(bytes)
            RtcpPacketCodec.RECEIVER_REPORT_TYPE -> RtcpPacketCodec.decodeReceiverReport(bytes)
            else -> throw FrameProtocolException("unsupported RTCP packet type")
        }
        return ReceivedRtcpDatagram(bytes, java.net.InetSocketAddress(packet.address, packet.port), receivedAtNs)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}
