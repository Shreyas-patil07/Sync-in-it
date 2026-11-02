// SPDX-License-Identifier: Apache-2.0
package com.syncinit.transport

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One length-prefixed TCP byte stream. Reads are blocking and must run off the audio callback. */
data class WireFrame(val messageType: Int, val flags: Int = 0, val payload: ByteArray = byteArrayOf()) {
    fun toEncodedBytes(maxFrameSize: Int = TcpFrameConnection.DEFAULT_MAX_FRAME_SIZE): ByteArray {
        require(messageType in 0..255) { "messageType must fit in one byte" }
        require(flags in 0..255) { "flags must fit in one byte" }
        val frameLength = payload.size.toLong() + 2L
        require(maxFrameSize in 2..TcpFrameConnection.DEFAULT_MAX_FRAME_SIZE)
        require(frameLength <= maxFrameSize.toLong()) { "TCP frame exceeds configured maximum of $maxFrameSize bytes" }
        val wire = ByteArray(4 + frameLength.toInt())
        for (index in 0 until 4) wire[index] = (frameLength ushr (8 * index)).toByte()
        wire[4] = messageType.toByte()
        wire[5] = flags.toByte()
        payload.copyInto(wire, destinationOffset = 6)
        return wire
    }

    companion object {
        fun fromEncodedBytes(
            encoded: ByteArray,
            maxFrameSize: Int = TcpFrameConnection.DEFAULT_MAX_FRAME_SIZE
        ): WireFrame {
            require(maxFrameSize in 2..TcpFrameConnection.DEFAULT_MAX_FRAME_SIZE)
            if (encoded.size < 6) throw FrameProtocolException("encoded TCP frame is shorter than its fixed header")
            val frameLength =
                (encoded[0].toLong() and 0xffL) or
                    ((encoded[1].toLong() and 0xffL) shl 8) or
                    ((encoded[2].toLong() and 0xffL) shl 16) or
                    ((encoded[3].toLong() and 0xffL) shl 24)
            if (frameLength < 2L) throw FrameProtocolException("TCP frame length is smaller than type and flags")
            if (frameLength > maxFrameSize.toLong()) {
                throw FrameProtocolException("TCP frame exceeds configured maximum of $maxFrameSize bytes")
            }
            if (frameLength != encoded.size.toLong() - 4L) {
                throw FrameProtocolException("encoded TCP frame length prefix does not match its byte count")
            }
            return WireFrame(
                messageType = encoded[4].toInt() and 0xff,
                flags = encoded[5].toInt() and 0xff,
                payload = encoded.copyOfRange(6, encoded.size)
            )
        }
    }
}

class FrameProtocolException(message: String) : IOException(message)

class TcpFrameConnection internal constructor(
    private val socket: Socket,
    private val maxFrameSize: Int
) : Closeable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get() || socket.isClosed

    /** Returns null only for a clean EOF between frames. A truncated frame closes the socket. */
    @Throws(IOException::class, SocketTimeoutException::class)
    fun readFrame(): WireFrame? {
        check(!isClosed) { "TCP connection is closed" }
        try {
            val first = input.read()
            if (first < 0) {
                close()
                return null
            }

            val prefix = ByteArray(4)
            prefix[0] = first.toByte()
            readFully(prefix, 1, 3)
            val frameLength = readUInt32Le(prefix)
            if (frameLength < 2L) {
                throw FrameProtocolException("TCP frame length is smaller than type and flags")
            }
            if (frameLength > maxFrameSize.toLong()) {
                throw FrameProtocolException("TCP frame exceeds configured maximum of $maxFrameSize bytes")
            }

            val body = ByteArray(frameLength.toInt())
            readFully(body, 0, body.size)
            return WireFrame(
                messageType = body[0].toInt() and 0xff,
                flags = body[1].toInt() and 0xff,
                payload = body.copyOfRange(2, body.size)
            )
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    /** Serializes writes so concurrent control callers cannot interleave frame bytes. */
    @Synchronized
    @Throws(IOException::class)
    fun writeFrame(frame: WireFrame) {
        check(!isClosed) { "TCP connection is closed" }
        require(frame.messageType in 0..255) { "messageType must fit in one byte" }
        require(frame.flags in 0..255) { "flags must fit in one byte" }
        val frameLength = frame.payload.size.toLong() + 2L
        require(frameLength <= maxFrameSize.toLong()) { "TCP frame exceeds configured maximum of $maxFrameSize bytes" }

        val wire = ByteArray(4 + frameLength.toInt())
        writeUInt32Le(wire, 0, frameLength)
        wire[4] = frame.messageType.toByte()
        wire[5] = frame.flags.toByte()
        frame.payload.copyInto(wire, destinationOffset = 6)
        try {
            output.write(wire)
            output.flush()
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    /** Writes a complete frame produced by the shared native protocol codec. */
    @Throws(IOException::class)
    fun writeEncodedFrame(encoded: ByteArray) {
        writeFrame(WireFrame.fromEncodedBytes(encoded, maxFrameSize))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    private fun readFully(target: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(target, offset + read, length - read)
            if (count < 0) throw EOFException("TCP stream ended inside a frame")
            if (count == 0) continue
            read += count
        }
    }

    companion object {
        const val DEFAULT_MAX_FRAME_SIZE = 65_536

        fun connect(
            host: String,
            port: Int,
            connectTimeoutMs: Int = 5_000,
            readTimeoutMs: Int = 0,
            maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE
        ): TcpFrameConnection {
            require(host.isNotBlank()) { "host must not be blank" }
            require(port in 1..65_535) { "port must be between 1 and 65535" }
            validateSocketOptions(connectTimeoutMs, readTimeoutMs, maxFrameSize)
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = readTimeoutMs
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                return TcpFrameConnection(socket, maxFrameSize)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        internal fun validateSocketOptions(connectTimeoutMs: Int, readTimeoutMs: Int, maxFrameSize: Int) {
            require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
            require(readTimeoutMs >= 0) { "readTimeoutMs must be zero (infinite) or positive" }
            require(maxFrameSize in 2..DEFAULT_MAX_FRAME_SIZE) { "maxFrameSize must be between 2 and 65536" }
        }

        internal fun accepted(socket: Socket, readTimeoutMs: Int, maxFrameSize: Int): TcpFrameConnection {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = readTimeoutMs
            return TcpFrameConnection(socket, maxFrameSize)
        }

        private fun readUInt32Le(bytes: ByteArray): Long =
            (bytes[0].toLong() and 0xffL) or
                ((bytes[1].toLong() and 0xffL) shl 8) or
                ((bytes[2].toLong() and 0xffL) shl 16) or
                ((bytes[3].toLong() and 0xffL) shl 24)

        private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 4) bytes[offset + index] = (value ushr (8 * index)).toByte()
        }
    }
}

/** Blocking TCP listener for the Android master; each accepted peer gets its own frame decoder. */
class TcpFrameServer(
    bindAddress: java.net.InetAddress,
    port: Int,
    backlog: Int = 50,
    acceptTimeoutMs: Int = 0,
    private val connectionReadTimeoutMs: Int = 0,
    private val maxFrameSize: Int = TcpFrameConnection.DEFAULT_MAX_FRAME_SIZE
) : Closeable {
    private val server = ServerSocket()
    private val closed = AtomicBoolean(false)

    val localPort: Int get() = server.localPort

    init {
        require(port in 0..65_535) { "port must be between 0 and 65535" }
        require(backlog > 0) { "backlog must be positive" }
        require(acceptTimeoutMs >= 0) { "acceptTimeoutMs must be zero or positive" }
        require(connectionReadTimeoutMs >= 0) { "connectionReadTimeoutMs must be zero or positive" }
        require(maxFrameSize in 2..TcpFrameConnection.DEFAULT_MAX_FRAME_SIZE) { "invalid maxFrameSize" }
        server.reuseAddress = true
        server.soTimeout = acceptTimeoutMs
        server.bind(InetSocketAddress(bindAddress, port), backlog)
    }

    @Throws(IOException::class, SocketTimeoutException::class)
    fun accept(): TcpFrameConnection {
        check(!closed.get()) { "TCP server is closed" }
        val accepted = server.accept()
        try {
            return TcpFrameConnection.accepted(accepted, connectionReadTimeoutMs, maxFrameSize)
        } catch (e: Exception) {
            accepted.close()
            throw e
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) server.close()
    }
}
