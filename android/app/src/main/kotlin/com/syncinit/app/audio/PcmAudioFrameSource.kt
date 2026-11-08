// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import com.syncinit.transport.FrameProtocolException
import com.syncinit.transport.RtpPcm16Decoder
import com.syncinit.transport.RtpPcm16Packetizer
import com.syncinit.transport.RtpUdpSocket
import com.syncinit.transport.TcpFrameConnection
import java.io.Closeable
import java.net.InetAddress

/** Supplies validated, native-protocol PCM chunks to the shared sample-indexed buffer. */
interface PcmAudioFrameSource : Closeable {
    val transportName: String

    /** Returns one complete encoded PCM chunk, or null on a clean end of stream. */
    fun readEncodedPcmChunk(): ByteArray?
}

class TcpPcmAudioFrameSource(
    private val connection: TcpFrameConnection
) : PcmAudioFrameSource {
    override val transportName: String = "TCP"

    override fun readEncodedPcmChunk(): ByteArray? {
        val frame = connection.readFrame() ?: return null
        if (frame.messageType != 0x80 || frame.flags != 0) {
            throw FrameProtocolException("unexpected frame on the TCP audio channel")
        }
        return frame.toEncodedBytes()
    }

    override fun close() = connection.close()
}

/**
 * Receives the project's diagnostic PCM16 RTP payload and converts each 5 ms
 * packet into the existing native PCM chunk envelope. The native jitter buffer
 * then orders data by the extended RTP sample timestamp, not packet arrival.
 */
class RtpPcmAudioFrameSource(
    private val socket: RtpUdpSocket,
    private val expectedHostAddress: InetAddress,
    private val expectedHostPort: Int,
    private val sessionId: Long,
    private val receiverId: Int,
    private val channels: Int = RtpPcm16Packetizer.CHANNELS
) : PcmAudioFrameSource {
    override val transportName: String = "RTP_UDP"
    private val decoder = RtpPcm16Decoder(expectedSsrc = sessionId, channels = channels)

    init {
        require(expectedHostPort in 1..65_535)
        require(sessionId in 0L..0xffff_ffffL)
        require(receiverId in 1..0xffff)
        require(channels == RtpPcm16Packetizer.CHANNELS)
    }

    override fun readEncodedPcmChunk(): ByteArray {
        while (true) {
            val datagram = socket.receive()
            if (datagram.source.address != expectedHostAddress ||
                datagram.source.port != expectedHostPort
            ) {
                continue
            }
            if (datagram.packet.payloadType != RtpPcm16Packetizer.PAYLOAD_TYPE) {
                continue
            }

            val decoded = decoder.decode(datagram.packet)
            return NativePcmJitterBuffer.encodeChunk(
                sessionId = sessionId,
                sequence = decoded.extendedSequenceNumber and 0xffff_ffffL,
                sampleIndex = decoded.sampleIndex,
                frames = decoded.frames,
                channels = channels,
                pcm16LittleEndian = decoded.pcm16LittleEndian
            )
        }
    }

    override fun close() = socket.close()
}
