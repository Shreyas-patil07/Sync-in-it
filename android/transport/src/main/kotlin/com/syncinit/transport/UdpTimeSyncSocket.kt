// SPDX-License-Identifier: Apache-2.0
package com.syncinit.transport

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** UDP envelope is fixed at 38 bytes by the protocol v1 time-sync codec. */
data class ReceivedTimeSyncDatagram(val bytes: ByteArray, val source: InetSocketAddress, val receivedAtNs: Long)
/**
 * Reads the v1 UDP time-sync sequence for cross-device diagnostic correlation.
 * The wire codec stores the unsigned 32-bit sequence at byte offset 10, little-endian.
 * Packet validation remains owned by the shared protocol core.
 */
object UdpTimeSyncPacketMetadata {
    fun sequence(packet: ByteArray): Long {
        validateEnvelope(packet)
        return (packet[10].toLong() and 0xffL) or
            ((packet[11].toLong() and 0xffL) shl 8) or
            ((packet[12].toLong() and 0xffL) shl 16) or
            ((packet[13].toLong() and 0xffL) shl 24)
    }

    /** Replaces the provisional T3 in a v1 response with the monotonic socket-send timestamp. */
    fun stampResponseT3(packet: ByteArray, t3Ns: Long) {
        validateEnvelope(packet)
        require((packet[3].toInt() and 0xff) == RESPONSE_TYPE) {
            "time-sync packet is not a response"
        }
        val t2Ns = readLongLittleEndian(packet, T2_OFFSET)
        require(t3Ns >= t2Ns) { "response T3 must not precede T2" }
        for (index in 0 until LONG_BYTES) {
            packet[T3_OFFSET + index] = (t3Ns ushr (index * 8)).toByte()
        }
    }

    private fun validateEnvelope(packet: ByteArray) {
        require(packet.size == UdpTimeSyncClient.PACKET_SIZE) {
            "time-sync packet must be exactly ${UdpTimeSyncClient.PACKET_SIZE} bytes"
        }
        require(packet[0] == 'S'.code.toByte() && packet[1] == 'T'.code.toByte()) {
            "invalid time-sync packet magic"
        }
        require((packet[2].toInt() and 0xff) == 1) { "unsupported time-sync packet version" }
    }

    private fun readLongLittleEndian(packet: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until LONG_BYTES) {
            value = value or ((packet[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        return value
    }

    private companion object {
        const val RESPONSE_TYPE = 2
        const val T2_OFFSET = 22
        const val T3_OFFSET = 30
        const val LONG_BYTES = 8
    }
}

data class TimeSyncResponseSendTiming(val t3Ns: Long, val sendElapsedNs: Long)

data class ReceivedTimeSyncResponse(val bytes: ByteArray, val receivedAtNs: Long)

class UdpTimeSyncClient(
    masterAddress: InetAddress,
    masterPort: Int,
    localPort: Int = 0,
    receiveTimeoutMs: Int = 1_000,
    datagramSocket: DatagramSocket? = null
) : Closeable {
    private val socket = datagramSocket ?: DatagramSocket(null)
    private val closed = AtomicBoolean(false)

    init {
        require(masterPort in 1..65_535) { "masterPort must be between 1 and 65535" }
        require(localPort in 0..65_535) { "localPort must be between 0 and 65535" }
        require(receiveTimeoutMs > 0) { "receiveTimeoutMs must be positive" }
        require(!socket.isBound && !socket.isConnected) {
            "injected UDP socket must be unbound and unconnected"
        }
        socket.bind(InetSocketAddress(localPort))
        socket.soTimeout = receiveTimeoutMs
        // Connected UDP filters datagrams to the configured master IP and port.
        socket.connect(InetSocketAddress(masterAddress, masterPort))
    }

    val localPort: Int get() = socket.localPort
    val localAddress: String get() = socket.localAddress?.hostAddress ?: "unknown"
    val remoteAddress: String get() = socket.inetAddress?.hostAddress ?: "unknown"
    val remotePort: Int get() = socket.port
    val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    @Throws(IOException::class)
    fun send(request: ByteArray) {
        check(!closed.get()) { "UDP time-sync client is closed" }
        require(request.size == PACKET_SIZE) { "time-sync request must be exactly $PACKET_SIZE bytes" }
        socket.send(DatagramPacket(request, request.size))
    }

    @Throws(IOException::class, SocketTimeoutException::class)
    fun receive(): ByteArray = receiveTimed().bytes

    /** Captures t4 immediately after DatagramSocket.receive returns. */
    @Throws(IOException::class, SocketTimeoutException::class)
    fun receiveTimed(): ReceivedTimeSyncResponse {
        check(!closed.get()) { "UDP time-sync client is closed" }
        val buffer = ByteArray(PACKET_SIZE + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        val receivedAtNs = System.nanoTime()
        if (packet.length != PACKET_SIZE) {
            throw FrameProtocolException("time-sync response must be exactly $PACKET_SIZE bytes")
        }
        return ReceivedTimeSyncResponse(buffer.copyOf(PACKET_SIZE), receivedAtNs)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    companion object { const val PACKET_SIZE = 38 }
}

/** UDP listener for the Android master. Invalid datagram sizes are surfaced to the caller. */
class UdpTimeSyncServer(
    bindAddress: InetAddress,
    port: Int,
    receiveTimeoutMs: Int = 0
) : Closeable {
    private val socket = DatagramSocket(null)
    private val closed = AtomicBoolean(false)

    init {
        require(port in 0..65_535) { "port must be between 0 and 65535" }
        require(receiveTimeoutMs >= 0) { "receiveTimeoutMs must be zero or positive" }
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        socket.soTimeout = receiveTimeoutMs
    }

    val localPort: Int get() = socket.localPort
    val localAddress: String get() = socket.localAddress?.hostAddress ?: "unknown"
    val isBound: Boolean get() = socket.isBound && !socket.isClosed

    @Throws(IOException::class, SocketTimeoutException::class)
    fun receive(): ReceivedTimeSyncDatagram {
        check(!closed.get()) { "UDP time-sync server is closed" }
        val buffer = ByteArray(UdpTimeSyncClient.PACKET_SIZE + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        val receivedAtNs = System.nanoTime()
        if (packet.length != UdpTimeSyncClient.PACKET_SIZE) {
            throw FrameProtocolException("time-sync datagram must be exactly ${UdpTimeSyncClient.PACKET_SIZE} bytes")
        }
        val source = InetSocketAddress(packet.address, packet.port)
        return ReceivedTimeSyncDatagram(buffer.copyOf(UdpTimeSyncClient.PACKET_SIZE), source, receivedAtNs)
    }

    @Throws(IOException::class)
    fun sendTo(response: ByteArray, destination: InetSocketAddress) {
        check(!closed.get()) { "UDP time-sync server is closed" }
        require(response.size == UdpTimeSyncClient.PACKET_SIZE) {
            "time-sync response must be exactly ${UdpTimeSyncClient.PACKET_SIZE} bytes"
        }
        require(!destination.isUnresolved) { "destination must be resolved" }
        require(destination.port in 1..65_535) { "destination port must be between 1 and 65535" }
        socket.send(DatagramPacket(response, response.size, destination))
    }

    /**
     * Stamps T3 after the datagram packet is prepared and immediately before socket.send().
     * The provisional response must already contain valid T1/T2/session/receiver fields.
     */
    @Throws(IOException::class)
    fun sendTimeSyncResponse(
        response: ByteArray,
        destination: InetSocketAddress
    ): TimeSyncResponseSendTiming {
        check(!closed.get()) { "UDP time-sync server is closed" }
        require(response.size == UdpTimeSyncClient.PACKET_SIZE) {
            "time-sync response must be exactly ${UdpTimeSyncClient.PACKET_SIZE} bytes"
        }
        require(!destination.isUnresolved) { "destination must be resolved" }
        require(destination.port in 1..65_535) { "destination port must be between 1 and 65535" }

        val packet = DatagramPacket(response, response.size, destination)
        val t3Ns = System.nanoTime()
        UdpTimeSyncPacketMetadata.stampResponseT3(response, t3Ns)
        socket.send(packet)
        return TimeSyncResponseSendTiming(t3Ns, System.nanoTime() - t3Ns)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}
