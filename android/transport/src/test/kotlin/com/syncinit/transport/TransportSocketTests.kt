// SPDX-License-Identifier: Apache-2.0
package com.syncinit.transport

import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.ServerSocket
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private fun expect(value: Boolean, message: String) {
    check(value) { message }
}

private fun frameWire(type: Int, flags: Int, payload: ByteArray): ByteArray {
    val length = payload.size + 2
    val wire = ByteArray(4 + length)
    for (i in 0 until 4) wire[i] = (length ushr (8 * i)).toByte()
    wire[4] = type.toByte()
    wire[5] = flags.toByte()
    payload.copyInto(wire, 6)
    return wire
}

private fun encodedFrameCodecRoundTrip() {
    val original = WireFrame(0x02, 0x01, byteArrayOf(0, 1, 2, 0xff.toByte()))
    val encoded = original.toEncodedBytes()
    val decoded = WireFrame.fromEncodedBytes(encoded)
    expect(decoded.messageType == original.messageType, "encoded frame preserves message type")
    expect(decoded.flags == original.flags, "encoded frame preserves flags")
    expect(decoded.payload.contentEquals(original.payload), "encoded frame preserves payload")

    var rejected = false
    try { WireFrame.fromEncodedBytes(encoded.copyOf(encoded.size - 1)) }
    catch (_: FrameProtocolException) { rejected = true }
    expect(rejected, "encoded frame rejects a length prefix/body mismatch")
}

private fun tcpRoundTrip() {
    val loopback = InetAddress.getByName("127.0.0.1")
    TcpFrameServer(loopback, 0, acceptTimeoutMs = 3_000, connectionReadTimeoutMs = 3_000).use { server ->
        expect(server.isListening, "TCP listener reports ready immediately after bind")
        val failure = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try {
                server.accept().use { peer ->
                    val first = peer.readFrame()
                    val second = peer.readFrame()
                    expect(first?.messageType == 1 && first.flags == 2 && first.payload.contentEquals(byteArrayOf(3, 4)), "first TCP frame decoded")
                    expect(second?.messageType == 5 && second.payload.contentEquals(byteArrayOf(6)), "second TCP frame decoded")
                    peer.writeFrame(WireFrame(9, 7, byteArrayOf(8, 9)))
                }
            } catch (t: Throwable) { failure.offer(t) }
        }
        worker.start()
        TcpFrameConnection.connect("127.0.0.1", server.localPort, connectTimeoutMs = 3_000, readTimeoutMs = 3_000).use { client ->
            expect(client.isConnected, "connected TCP socket exposes connected state")
            expect(client.remoteAddress.hostAddress == "127.0.0.1", "TCP diagnostics expose remote address")
            expect(client.remotePort == server.localPort, "TCP diagnostics expose remote port")
            expect(client.localAddress == "127.0.0.1", "TCP diagnostics expose selected local address")
            expect(client.localPort in 1..65_535, "TCP diagnostics expose ephemeral local port")
            expect(client.remoteEndpoint == "127.0.0.1:${server.localPort}", "TCP diagnostics format remote endpoint")
            expect(client.localEndpoint == "127.0.0.1:${client.localPort}", "TCP diagnostics format local endpoint")
            client.writeFrame(WireFrame(1, 2, byteArrayOf(3, 4)))
            client.writeFrame(WireFrame(5, 0, byteArrayOf(6)))
            val response = client.readFrame()
            expect(response?.messageType == 9 && response.flags == 7 && response.payload.contentEquals(byteArrayOf(8, 9)), "TCP response decoded")
        }
        worker.join(4_000)
        expect(!worker.isAlive, "TCP server worker completed")
        failure.poll()?.let { throw it }
    }
}

private fun tcpConnectFailureIncludesEndpointAndTiming() {
    val loopback = InetAddress.getByName("127.0.0.1")
    val unusedPort = ServerSocket(0, 1, loopback).use { it.localPort }
    var failure: IOException? = null
    try {
        TcpFrameConnection.connect("127.0.0.1", unusedPort, connectTimeoutMs = 1_000)
    } catch (error: IOException) {
        failure = error
    }
    val error = failure ?: throw AssertionError("connection to a closed loopback port must fail")
    expect(error.message.orEmpty().contains("target=127.0.0.1:$unusedPort"), "connect failure includes target endpoint")
    val localEndpoint = Regex("local=([^ ]+)").find(error.message.orEmpty())?.groupValues?.get(1)
    val localPort = localEndpoint?.substringAfterLast(':')?.toIntOrNull()
    expect(localPort in -1..65_535, "connect failure includes local endpoint (port -1 means the OS had not assigned one; actual=$localEndpoint)")
    expect(error.message.orEmpty().contains("elapsedMs="), "connect failure includes elapsed time")
    expect(error.cause is java.net.ConnectException, "connect failure preserves the operating-system exception")
}

private fun tcpCoalescedFramesSingleWrite() {
    val loopback = InetAddress.getByName("127.0.0.1")
    TcpFrameServer(loopback, 0, acceptTimeoutMs = 3_000, connectionReadTimeoutMs = 3_000).use { server ->
        val received = ArrayBlockingQueue<List<WireFrame>>(1)
        val failure = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try {
                server.accept().use { peer ->
                    received.offer(listOfNotNull(peer.readFrame(), peer.readFrame()))
                }
            } catch (t: Throwable) { failure.offer(t) }
        }
        worker.start()
        Socket("127.0.0.1", server.localPort).use { raw ->
            val coalesced = frameWire(0x11, 0x01, byteArrayOf(2, 3)) +
                frameWire(0x12, 0x02, byteArrayOf(4, 5, 6))
            raw.getOutputStream().write(coalesced)
            raw.getOutputStream().flush()
        }
        worker.join(4_000)
        failure.poll()?.let { throw it }
        val frames = received.poll(1, TimeUnit.SECONDS).orEmpty()
        expect(frames.size == 2, "two frames from one TCP write are separated")
        expect(frames.firstOrNull()?.messageType == 0x11 && frames.lastOrNull()?.messageType == 0x12,
            "coalesced TCP frame order is preserved")
    }
}

private fun tcpFragmentedRead() {
    val loopback = InetAddress.getByName("127.0.0.1")
    TcpFrameServer(loopback, 0, acceptTimeoutMs = 3_000, connectionReadTimeoutMs = 3_000).use { server ->
        val received = ArrayBlockingQueue<WireFrame>(1)
        val failure = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try { server.accept().use { peer -> peer.readFrame()?.let(received::offer) } }
            catch (t: Throwable) { failure.offer(t) }
        }
        worker.start()
        Socket("127.0.0.1", server.localPort).use { raw ->
            val wire = frameWire(0x42, 0x03, byteArrayOf(1, 2, 3, 4, 5))
            for (byte in wire) {
                raw.getOutputStream().write(byte.toInt())
                raw.getOutputStream().flush()
            }
        }
        worker.join(4_000)
        failure.poll()?.let { throw it }
        val result = received.poll(1, TimeUnit.SECONDS)
        expect(result?.messageType == 0x42 && result.flags == 0x03 && result.payload.contentEquals(byteArrayOf(1, 2, 3, 4, 5)), "fragmented TCP frame decoded")
    }
}

private fun tcpRejectsOversizedAndTruncatedFrames() {
    val loopback = InetAddress.getByName("127.0.0.1")
    TcpFrameServer(loopback, 0, acceptTimeoutMs = 3_000, connectionReadTimeoutMs = 3_000, maxFrameSize = 64).use { server ->
        val done = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try {
                server.accept().use { peer ->
                    try { peer.readFrame(); done.offer(AssertionError("oversized frame was accepted")) }
                    catch (expected: FrameProtocolException) { done.offer(expected) }
                }
            } catch (t: Throwable) { done.offer(t) }
        }
        worker.start()
        Socket("127.0.0.1", server.localPort).use { raw ->
            raw.getOutputStream().write(byteArrayOf(65, 0, 0, 0))
            raw.getOutputStream().flush()
        }
        worker.join(4_000)
        expect(done.poll(1, TimeUnit.SECONDS) is FrameProtocolException, "oversized TCP prefix rejected before body read")
    }

    TcpFrameServer(loopback, 0, acceptTimeoutMs = 3_000, connectionReadTimeoutMs = 3_000).use { server ->
        val done = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try {
                server.accept().use { peer ->
                    try { peer.readFrame(); done.offer(AssertionError("truncated frame was accepted")) }
                    catch (expected: EOFException) { done.offer(expected) }
                }
            } catch (t: Throwable) { done.offer(t) }
        }
        worker.start()
        Socket("127.0.0.1", server.localPort).use { raw ->
            raw.getOutputStream().write(byteArrayOf(5, 0, 0, 0, 1, 0))
            raw.getOutputStream().flush()
        }
        worker.join(4_000)
        expect(done.poll(1, TimeUnit.SECONDS) is EOFException, "truncated TCP frame rejected")
    }
}

private fun udpTimeSyncLoopback() {
    val loopback = InetAddress.getByName("127.0.0.1")
    UdpTimeSyncServer(loopback, 0, receiveTimeoutMs = 3_000).use { server ->
        expect(server.isBound, "UDP diagnostics expose bound server state")
        expect(server.localAddress == "127.0.0.1", "UDP diagnostics expose bound server address")
        val request = ByteArray(UdpTimeSyncClient.PACKET_SIZE) { it.toByte() }
        val response = ByteArray(UdpTimeSyncClient.PACKET_SIZE) { (255 - it).toByte() }
        val received = ArrayBlockingQueue<Boolean>(1)
        val failure = ArrayBlockingQueue<Throwable>(1)
        val worker = Thread {
            try {
                val incoming = server.receive()
                val matches = incoming.bytes.contentEquals(request) && incoming.receivedAtNs != 0L
                server.sendTo(response, incoming.source)
                received.offer(matches)
            } catch (t: Throwable) { failure.offer(t) }
        }
        worker.start()
        UdpTimeSyncClient(loopback, server.localPort, receiveTimeoutMs = 3_000).use { client ->
            expect(client.isConnected, "connected UDP socket exposes connected state")
            expect(client.localAddress == "127.0.0.1", "UDP diagnostics expose selected local address")
            expect(client.localPort in 1..65_535, "UDP diagnostics expose ephemeral local port")
            expect(client.remoteAddress == "127.0.0.1", "UDP diagnostics expose remote address")
            expect(client.remotePort == server.localPort, "UDP diagnostics expose remote port")
            client.send(request)
            val timedResponse = client.receiveTimed()
            expect(timedResponse.bytes.contentEquals(response), "UDP time-sync response round-trips")
            expect(timedResponse.receivedAtNs != 0L, "UDP client captures a monotonic receive timestamp")
        }
        worker.join(4_000)
        failure.poll()?.let { throw it }
        expect(received.poll(1, TimeUnit.SECONDS) == true, "UDP time-sync request reaches master")
    }

    var rejected = false
    try { UdpTimeSyncServer(loopback, 0).use { it.sendTo(ByteArray(37), java.net.InetSocketAddress(loopback, 12345)) } }
    catch (_: IllegalArgumentException) { rejected = true }
    expect(rejected, "UDP server rejects non-38-byte response")

    rejected = false
    try { UdpTimeSyncClient(loopback, 12345).use { it.send(ByteArray(39)) } }
    catch (_: IllegalArgumentException) { rejected = true }
    expect(rejected, "UDP client rejects non-38-byte request")
}

private fun udpRejectsMalformedDatagrams() {
    val loopback = InetAddress.getByName("127.0.0.1")
    UdpTimeSyncServer(loopback, 0, receiveTimeoutMs = 3_000).use { server ->
        DatagramSocket().use { sender ->
            val malformed = ByteArray(39)
            sender.send(DatagramPacket(malformed, malformed.size, loopback, server.localPort))
        }
        var rejected = false
        try { server.receive() } catch (_: FrameProtocolException) { rejected = true }
        expect(rejected, "UDP server rejects oversized datagram envelopes")
    }

    DatagramSocket(0, loopback).use { peer ->
        UdpTimeSyncClient(loopback, peer.localPort, receiveTimeoutMs = 3_000).use { client ->
            val malformed = ByteArray(37)
            peer.send(DatagramPacket(malformed, malformed.size, loopback, client.localPort))
            var rejected = false
            try { client.receive() } catch (_: FrameProtocolException) { rejected = true }
            expect(rejected, "UDP client rejects undersized response datagrams")
        }
    }
}

fun main() {
    encodedFrameCodecRoundTrip()
    tcpRoundTrip()
    tcpConnectFailureIncludesEndpointAndTiming()
    tcpCoalescedFramesSingleWrite()
    tcpFragmentedRead()
    tcpRejectsOversizedAndTruncatedFrames()
    udpTimeSyncLoopback()
    udpRejectsMalformedDatagrams()
    println("Kotlin TCP/UDP transport tests: all assertions passed")
}
