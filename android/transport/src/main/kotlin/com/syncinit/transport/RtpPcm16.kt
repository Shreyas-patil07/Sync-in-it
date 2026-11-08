// SPDX-License-Identifier: Apache-2.0
package com.syncinit.transport

/**
 * Diagnostic PCM16 RTP profile used before Opus is introduced.
 *
 * A 20 ms, 48 kHz, stereo PCM16 source chunk is 3,840 bytes and cannot fit
 * inside the 1,188-byte payload budget. This profile emits four 5 ms packets.
 * The receiver feeds each decoded sample range directly into the existing
 * sample-indexed PCM jitter buffer, which performs sample-range reordering.
 */
object RtpPcm16Packetizer {
    const val PAYLOAD_TYPE = 96
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val SOURCE_CHUNK_FRAMES = 960
    const val PACKET_FRAMES = 240
    const val PACKETS_PER_SOURCE_CHUNK = SOURCE_CHUNK_FRAMES / PACKET_FRAMES
    const val PACKET_PAYLOAD_BYTES = PACKET_FRAMES * CHANNELS * BYTES_PER_SAMPLE

    private const val UINT16_MASK = 0xffff
    private const val UINT32_MASK = 0xffff_ffffL

    init {
        require(PACKETS_PER_SOURCE_CHUNK * PACKET_FRAMES == SOURCE_CHUNK_FRAMES)
        require(PACKET_PAYLOAD_BYTES <= RtpPacketCodec.MAX_PAYLOAD_SIZE)
    }

    /**
     * Splits exactly one 20 ms source chunk into four MTU-safe RTP packets.
     *
     * RTP sequence numbers count packets, not source chunks. RTP timestamps
     * count 48 kHz sample frames per channel and therefore advance by 240.
     */
    fun packetize(
        pcm16LittleEndian: ByteArray,
        sampleIndex: Long,
        firstSequenceNumber: Int,
        ssrc: Long
    ): List<RtpPacket> {
        require(sampleIndex >= 0L) { "PCM sample index must be non-negative" }
        require(firstSequenceNumber in 0..UINT16_MASK) { "RTP sequence number must be uint16" }
        require(ssrc in 0L..UINT32_MASK) { "RTP SSRC must be uint32" }
        require(pcm16LittleEndian.size == SOURCE_CHUNK_FRAMES * CHANNELS * BYTES_PER_SAMPLE) {
            "diagnostic PCM source chunk must contain exactly $SOURCE_CHUNK_FRAMES stereo frames"
        }

        return List(PACKETS_PER_SOURCE_CHUNK) { packetIndex ->
            val frameOffset = packetIndex * PACKET_FRAMES
            val byteOffset = frameOffset * CHANNELS * BYTES_PER_SAMPLE
            val payload = pcm16LittleEndian.copyOfRange(
                byteOffset,
                byteOffset + PACKET_PAYLOAD_BYTES
            )
            RtpPacket(
                payloadType = PAYLOAD_TYPE,
                sequenceNumber = (firstSequenceNumber + packetIndex) and UINT16_MASK,
                timestamp = (sampleIndex + frameOffset.toLong()) and UINT32_MASK,
                ssrc = ssrc,
                payload = payload
            )
        }
    }
}

data class DecodedRtpPcm16Packet(
    val extendedSequenceNumber: Long,
    val sampleIndex: Long,
    val frames: Int,
    val pcm16LittleEndian: ByteArray
)

/**
 * Validates the diagnostic PCM RTP profile and extends wrapping RTP counters.
 *
 * Sequence numbers are used for packet-level diagnostics. The returned sample
 * index is the ordering key for the existing native PCM jitter buffer; packets
 * may be inserted out of arrival order without changing the media timeline.
 *
 * RTP has a 32-bit timestamp, so a stream that is first observed only after a
 * complete 24.86-hour wrap needs an initial extended sample index from session
 * signaling. The current preview starts the RTP stream at sample index zero.
 */
class RtpPcm16Decoder(
    private val expectedSsrc: Long,
    private val channels: Int = RtpPcm16Packetizer.CHANNELS
) {
    private var highestExtendedSequence: Long? = null
    private var highestExtendedTimestamp: Long? = null

    init {
        require(expectedSsrc in 0L..0xffff_ffffL) { "expected SSRC must be uint32" }
        require(channels == RtpPcm16Packetizer.CHANNELS) {
            "diagnostic RTP PCM profile currently requires stereo"
        }
    }

    @Synchronized
    fun decode(packet: RtpPacket): DecodedRtpPcm16Packet {
        if (packet.payloadType != RtpPcm16Packetizer.PAYLOAD_TYPE) {
            throw FrameProtocolException("unexpected RTP payload type for diagnostic PCM16")
        }
        if (packet.ssrc != expectedSsrc) {
            throw FrameProtocolException("RTP SSRC does not match the admitted session")
        }
        if (packet.payload.size != RtpPcm16Packetizer.PACKET_PAYLOAD_BYTES) {
            throw FrameProtocolException("diagnostic PCM RTP payload must contain exactly 240 stereo frames")
        }

        val sequence = unwrap(
            packet.sequenceNumber.toLong(),
            highestExtendedSequence,
            bits = 16
        )
        val sampleIndex = unwrap(
            packet.timestamp,
            highestExtendedTimestamp,
            bits = 32
        )
        if (highestExtendedSequence == null || sequence > highestExtendedSequence!!) {
            highestExtendedSequence = sequence
        }
        if (highestExtendedTimestamp == null || sampleIndex > highestExtendedTimestamp!!) {
            highestExtendedTimestamp = sampleIndex
        }

        return DecodedRtpPcm16Packet(
            extendedSequenceNumber = sequence,
            sampleIndex = sampleIndex,
            frames = RtpPcm16Packetizer.PACKET_FRAMES,
            pcm16LittleEndian = packet.payload.copyOf()
        )
    }

    private fun unwrap(rawValue: Long, reference: Long?, bits: Int): Long {
        if (reference == null) return rawValue
        val modulus = 1L shl bits
        val mask = modulus - 1L
        val halfRange = modulus / 2L
        var candidate = (reference and mask.inv()) or rawValue
        if (candidate - reference > halfRange) {
            candidate -= modulus
        } else if (reference - candidate > halfRange) {
            candidate += modulus
        }
        return candidate
    }
}
