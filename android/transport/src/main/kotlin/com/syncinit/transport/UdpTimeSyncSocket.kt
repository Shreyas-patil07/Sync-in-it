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

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}
