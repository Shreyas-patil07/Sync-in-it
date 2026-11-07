// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import android.annotation.TargetApi
import android.net.Network
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructCmsghdr
import android.system.StructMsghdr
import android.system.StructTimeval
import com.syncinit.app.core.NativeCore
import com.syncinit.transport.FrameProtocolException
import com.syncinit.transport.KernelReceiveTimestamp
import com.syncinit.transport.ReceivedTimeSyncDatagram
import com.syncinit.transport.ReceivedTimeSyncResponse
import com.syncinit.transport.TimeSyncResponseSendTiming
import com.syncinit.transport.UdpTimeSyncClient
import com.syncinit.transport.UdpTimeSyncPacketMetadata
import com.syncinit.transport.UdpTimeSyncServer
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal interface TimeSyncUdpClient : Closeable {
    val localAddress: String
    val localPort: Int
    val remoteAddress: String
    val remotePort: Int
    val isConnected: Boolean
    val timestampMode: String
    fun send(request: ByteArray)
    fun receiveTimed(timeoutMs: Int): ReceivedTimeSyncResponse
}

internal interface TimeSyncUdpServer : Closeable {
    val localAddress: String
    val localPort: Int
    val isBound: Boolean
    val timestampMode: String
    fun receive(): ReceivedTimeSyncDatagram
    fun sendTimeSyncResponse(response: ByteArray, destination: InetSocketAddress): TimeSyncResponseSendTiming
}

internal fun openTimeSyncUdpClient(
    masterAddress: InetAddress,
    masterPort: Int,
    receiveTimeoutMs: Int,
    network: Network
): TimeSyncUdpClient {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            return KernelTimeSyncUdpClient(masterAddress, masterPort, receiveTimeoutMs, network)
        } catch (error: Exception) {
            return JavaTimeSyncUdpClient(
                masterAddress, masterPort, receiveTimeoutMs, network,
                "java-fallback:" + error.javaClass.simpleName
            )
        }
    }
    return JavaTimeSyncUdpClient(masterAddress, masterPort, receiveTimeoutMs, network, "java-api-fallback")
}

internal fun openTimeSyncUdpServer(
    bindAddress: InetAddress,
    port: Int,
    receiveTimeoutMs: Int
): TimeSyncUdpServer {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            return KernelTimeSyncUdpServer(bindAddress, port, receiveTimeoutMs)
        } catch (error: Exception) {
            return JavaTimeSyncUdpServer(
                UdpTimeSyncServer(bindAddress, port, receiveTimeoutMs),
                "java-fallback:" + error.javaClass.simpleName
            )
        }
    }
    return JavaTimeSyncUdpServer(
        UdpTimeSyncServer(bindAddress, port, receiveTimeoutMs),
        "java-api-fallback"
    )
}

private class JavaTimeSyncUdpClient(
    masterAddress: InetAddress,
    masterPort: Int,
    receiveTimeoutMs: Int,
    network: Network,
    override val timestampMode: String
) : TimeSyncUdpClient {
    private val socket = DatagramSocket(null)
    private val delegate: UdpTimeSyncClient

    init {
        network.bindSocket(socket)
        delegate = UdpTimeSyncClient(
            masterAddress = masterAddress,
            masterPort = masterPort,
            receiveTimeoutMs = receiveTimeoutMs,
            datagramSocket = socket
        )
    }

    override val localAddress: String get() = delegate.localAddress
    override val localPort: Int get() = delegate.localPort
    override val remoteAddress: String get() = delegate.remoteAddress
    override val remotePort: Int get() = delegate.remotePort
    override val isConnected: Boolean get() = delegate.isConnected
    override fun send(request: ByteArray) = delegate.send(request)
    override fun receiveTimed(timeoutMs: Int): ReceivedTimeSyncResponse = delegate.receiveTimed(timeoutMs)
    override fun close() = delegate.close()
}

private class JavaTimeSyncUdpServer(
    private val delegate: UdpTimeSyncServer,
    override val timestampMode: String = "java-post-recv"
) : TimeSyncUdpServer {
    override val localAddress: String get() = delegate.localAddress
    override val localPort: Int get() = delegate.localPort
    override val isBound: Boolean get() = delegate.isBound
    override fun receive(): ReceivedTimeSyncDatagram = delegate.receive()
    override fun sendTimeSyncResponse(
        response: ByteArray,
        destination: InetSocketAddress
    ): TimeSyncResponseSendTiming = delegate.sendTimeSyncResponse(response, destination)
    override fun close() = delegate.close()
}

@TargetApi(Build.VERSION_CODES.S)
private class KernelTimeSyncUdpClient(
    masterAddress: InetAddress,
    masterPort: Int,
    receiveTimeoutMs: Int,
    network: Network
) : TimeSyncUdpClient {
    private val socket = KernelTimestampUdpSocket.openClient(
        masterAddress, masterPort, receiveTimeoutMs, network
    )
    override val localAddress: String get() = socket.localAddress
    override val localPort: Int get() = socket.localPort
    override val remoteAddress: String get() = socket.remoteAddress
    override val remotePort: Int get() = socket.remotePort
    override val isConnected: Boolean get() = socket.isOpen
    override val timestampMode: String = "kernel-so-timestampns-old"

    override fun send(request: ByteArray) = socket.sendTo(request)

    override fun receiveTimed(timeoutMs: Int): ReceivedTimeSyncResponse {
        val received = socket.receive(timeoutMs)
        return ReceivedTimeSyncResponse(
            bytes = received.bytes,
            receivedAtNs = received.receivedAtNs,
            kernelTimestamp = received.kernelTimestamp
        )
    }

    override fun close() = socket.close()
}

@TargetApi(Build.VERSION_CODES.S)
private class KernelTimeSyncUdpServer(
    bindAddress: InetAddress,
    port: Int,
    receiveTimeoutMs: Int
) : TimeSyncUdpServer {
    private val socket = KernelTimestampUdpSocket.openServer(bindAddress, port, receiveTimeoutMs)
    override val localAddress: String get() = socket.localAddress
    override val localPort: Int get() = socket.localPort
    override val isBound: Boolean get() = socket.isOpen
    override val timestampMode: String = "kernel-so-timestampns-old"

    override fun receive(): ReceivedTimeSyncDatagram {
        val received = socket.receive(null)
        val source = received.source
            ?: throw IOException("kernel UDP receive did not return a source address")
        return ReceivedTimeSyncDatagram(
            bytes = received.bytes,
            source = source,
            receivedAtNs = received.receivedAtNs,
            kernelTimestamp = received.kernelTimestamp
        )
    }

    override fun sendTimeSyncResponse(
        response: ByteArray,
        destination: InetSocketAddress
    ): TimeSyncResponseSendTiming {
        val t3Ns = System.nanoTime()
        UdpTimeSyncPacketMetadata.stampResponseT3(response, t3Ns)
        socket.sendTo(response, destination)
        return TimeSyncResponseSendTiming(t3Ns, System.nanoTime() - t3Ns)
    }

    override fun close() = socket.close()
}

private data class KernelReceivedDatagram(
    val bytes: ByteArray,
    val source: InetSocketAddress?,
    val receivedAtNs: Long,
    val kernelTimestamp: KernelReceiveTimestamp?
)

@TargetApi(Build.VERSION_CODES.S)
private class KernelTimestampUdpSocket private constructor(
    private val descriptor: FileDescriptor,
    private val peer: InetSocketAddress?
) : Closeable {
    private val closed = AtomicBoolean(false)
    val isOpen: Boolean get() = !closed.get() && descriptor.valid()
    private val localSocketAddress: InetSocketAddress
        get() = Os.getsockname(descriptor) as InetSocketAddress
    val localAddress: String get() = localSocketAddress.address.hostAddress ?: "unknown"
    val localPort: Int get() = localSocketAddress.port
    val remoteAddress: String get() = peer?.address?.hostAddress ?: "unknown"
    val remotePort: Int get() = peer?.port ?: 0

    fun sendTo(payload: ByteArray) {
        val destination = checkNotNull(peer) { "kernel UDP client has no peer" }
        sendTo(payload, destination)
    }

    fun sendTo(payload: ByteArray, destination: InetSocketAddress) {
        check(isOpen) { "kernel UDP socket is closed" }
        require(!destination.isUnresolved) { "UDP destination must be resolved" }
        try {
            val sent = Os.sendto(
                descriptor, payload, 0, payload.size, 0,
                destination.address, destination.port
            )
            if (sent != payload.size) {
                throw IOException("partial UDP datagram send: " + sent + "/" + payload.size + " bytes")
            }
        } catch (error: ErrnoException) {
            throw IOException("kernel UDP send failed: errno=" + error.errno, error)
        }
    }

    fun receive(timeoutMs: Int?): KernelReceivedDatagram {
        check(isOpen) { "kernel UDP socket is closed" }
        if (timeoutMs != null) setReceiveTimeout(timeoutMs)

        val payload = ByteBuffer.allocate(UdpTimeSyncClient.PACKET_SIZE + 1)
        val message = StructMsghdr(null, arrayOf(payload), null, 0)
        val byteCount = try {
            Os.recvmsg(descriptor, message, 0)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EAGAIN) {
                throw SocketTimeoutException("kernel UDP receive timed out")
            }
            throw IOException("kernel UDP receive failed: errno=" + error.errno, error)
        }
        val receivedAtNs = System.nanoTime()
        if (byteCount != UdpTimeSyncClient.PACKET_SIZE) {
            throw FrameProtocolException(
                "time-sync datagram must be exactly " + UdpTimeSyncClient.PACKET_SIZE + " bytes"
            )
        }

        val source = (message.msg_name as? InetSocketAddress) ?: peer
        val kernelTimestamp = extractKernelTimestamp(message.msg_control)
        return KernelReceivedDatagram(
            bytes = payload.array().copyOf(byteCount),
            source = source,
            receivedAtNs = receivedAtNs,
            kernelTimestamp = kernelTimestamp
        )
    }

    private fun extractKernelTimestamp(control: Array<StructCmsghdr>?): KernelReceiveTimestamp? {
        val ancillary = control?.firstOrNull {
            it.cmsg_level == OsConstants.SOL_SOCKET && it.cmsg_type == SO_TIMESTAMPNS_OLD
        } ?: return null
        if (ancillary.cmsg_data.size < 16) return null

        val timestampBuffer = ByteBuffer.wrap(ancillary.cmsg_data).order(ByteOrder.nativeOrder())
        val seconds = timestampBuffer.long
        val nanoseconds = timestampBuffer.long
        if (seconds < 0L || nanoseconds !in 0L..999_999_999L) return null
        val realtimeNs = seconds * NANOS_PER_SECOND + nanoseconds

        // SO_TIMESTAMPNS uses CLOCK_REALTIME. Bracket the conversion into System.nanoTime's
        // monotonic domain so the timestamp basis and conversion uncertainty are explicit.
        val monotonicBeforeNs = System.nanoTime()
        val realtimeNowNs = NativeCore.nativeClockRealtimeNs()
        val monotonicAfterNs = System.nanoTime()
        if (realtimeNowNs <= 0L || monotonicAfterNs < monotonicBeforeNs) return null
        val monotonicMidpointNs = monotonicBeforeNs +
            (monotonicAfterNs - monotonicBeforeNs) / 2L
        return KernelReceiveTimestamp(
            realtimeNs = realtimeNs,
            monotonicNs = realtimeNs + (monotonicMidpointNs - realtimeNowNs),
            mappingUncertaintyNs = (monotonicAfterNs - monotonicBeforeNs + 1L) / 2L
        )
    }

    private fun setReceiveTimeout(timeoutMs: Int) {
        require(timeoutMs > 0) { "receive timeout must be positive" }
        try {
            Os.setsockoptTimeval(
                descriptor,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(timeoutMs.toLong())
            )
        } catch (error: ErrnoException) {
            throw IOException("could not configure kernel UDP receive timeout: errno=" + error.errno, error)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
            }
        }
    }

    companion object {
        // The Android app packages arm64-v8a and x86_64, both using the legacy 64-bit timespec ABI.
        private const val SO_TIMESTAMPNS_OLD = 35
        private const val NANOS_PER_SECOND = 1_000_000_000L

        fun openClient(
            masterAddress: InetAddress,
            masterPort: Int,
            receiveTimeoutMs: Int,
            network: Network
        ): KernelTimestampUdpSocket {
            val descriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, 0)
            try {
                require(masterPort in 1..65_535)
                require(receiveTimeoutMs > 0)
                network.bindSocket(descriptor)
                Os.setsockoptInt(descriptor, OsConstants.SOL_SOCKET, SO_TIMESTAMPNS_OLD, 1)
                Os.bind(descriptor, InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 0)
                Os.connect(descriptor, masterAddress, masterPort)
                val socket = KernelTimestampUdpSocket(
                    descriptor, InetSocketAddress(masterAddress, masterPort)
                )
                socket.setReceiveTimeout(receiveTimeoutMs)
                return socket
            } catch (error: Exception) {
                try { Os.close(descriptor) } catch (_: ErrnoException) { }
                throw error
            }
        }

        fun openServer(
            bindAddress: InetAddress,
            port: Int,
            receiveTimeoutMs: Int
        ): KernelTimestampUdpSocket {
            val descriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, 0)
            try {
                require(port in 0..65_535)
                Os.setsockoptInt(descriptor, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                Os.setsockoptInt(descriptor, OsConstants.SOL_SOCKET, SO_TIMESTAMPNS_OLD, 1)
                Os.bind(descriptor, bindAddress, port)
                val socket = KernelTimestampUdpSocket(descriptor, null)
                if (receiveTimeoutMs > 0) socket.setReceiveTimeout(receiveTimeoutMs)
                return socket
            } catch (error: Exception) {
                try { Os.close(descriptor) } catch (_: ErrnoException) { }
                throw error
            }
        }
    }
}
