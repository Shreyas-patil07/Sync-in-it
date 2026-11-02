// SPDX-License-Identifier: Apache-2.0
#include "syit/protocol.h"

#include <algorithm>
#include <limits>
#include <stdexcept>
#include <type_traits>

namespace syit {
namespace {

template <typename UInt>
void append_le(std::vector<std::uint8_t>& out, UInt value, std::size_t count) {
    static_assert(std::is_unsigned<UInt>::value, "UInt must be unsigned");
    for (std::size_t i = 0; i < count; ++i) {
        out.push_back(static_cast<std::uint8_t>(value & static_cast<UInt>(0xffU)));
        value = static_cast<UInt>(value >> 8U);
    }
}

template <typename UInt>
UInt read_le(const std::vector<std::uint8_t>& bytes, std::size_t offset, std::size_t count) {
    static_assert(std::is_unsigned<UInt>::value, "UInt must be unsigned");
    if (offset > bytes.size() || count > bytes.size() - offset || count > sizeof(UInt)) {
        throw std::invalid_argument("truncated little-endian field");
    }
    std::uint64_t value = 0;
    for (std::size_t i = 0; i < count; ++i) {
        value |= static_cast<std::uint64_t>(bytes[offset + i]) << (8U * i);
    }
    return static_cast<UInt>(value);
}

std::int64_t read_i64(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
    const std::uint64_t raw = read_le<std::uint64_t>(bytes, offset, 8);
    if (raw <= static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) {
        return static_cast<std::int64_t>(raw);
    }
    return -1 - static_cast<std::int64_t>(~raw);
}

void append_i64(std::vector<std::uint8_t>& out, std::int64_t value) {
    append_le<std::uint64_t>(out, static_cast<std::uint64_t>(value), 8);
}

}  // namespace

std::vector<std::uint8_t> encode_frame(const Frame& frame) {
    const std::size_t length = 2U + frame.payload.size();
    if (length > kMaxFrameSize) {
        throw std::invalid_argument("frame exceeds maximum size");
    }
    std::vector<std::uint8_t> out;
    out.reserve(4U + length);
    append_le<std::uint32_t>(out, static_cast<std::uint32_t>(length), 4);
    out.push_back(frame.message_type);
    out.push_back(frame.flags);
    out.insert(out.end(), frame.payload.begin(), frame.payload.end());
    return out;
}

Frame decode_frame(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() < 4U) {
        throw std::invalid_argument("truncated frame length prefix");
    }
    const std::uint32_t length = read_le<std::uint32_t>(bytes, 0, 4);
    if (length < 2U) {
        throw std::invalid_argument("frame length is smaller than type and flags");
    }
    if (length > kMaxFrameSize) {
        throw std::invalid_argument("frame exceeds maximum size");
    }
    if (bytes.size() != 4U + static_cast<std::size_t>(length)) {
        throw std::invalid_argument("frame is truncated or contains trailing bytes");
    }
    return {bytes[4], bytes[5], std::vector<std::uint8_t>(bytes.begin() + 6, bytes.end())};
}


std::vector<Frame> FrameStreamDecoder::feed(const std::uint8_t* data, std::size_t size) {
    if (data == nullptr && size != 0U) {
        throw std::invalid_argument("non-empty stream input has a null data pointer");
    }

    std::vector<Frame> frames;
    std::size_t offset = 0U;
    while (offset < size) {
        if (!expected_size_.has_value()) {
            const std::size_t needed = 4U - pending_.size();
            const std::size_t take = std::min(needed, size - offset);
            if (take != 0U) {
                pending_.insert(pending_.end(), data + offset, data + offset + take);
                offset += take;
            }
            if (pending_.size() < 4U) {
                break;
            }

            const std::uint32_t frame_length = read_le<std::uint32_t>(pending_, 0U, 4U);
            if (frame_length < 2U) {
                reset();
                throw std::invalid_argument("frame length is smaller than type and flags");
            }
            if (frame_length > kMaxFrameSize) {
                reset();
                throw std::invalid_argument("frame exceeds maximum size");
            }
            expected_size_ = 4U + static_cast<std::size_t>(frame_length);
        }

        const std::size_t remaining = *expected_size_ - pending_.size();
        const std::size_t take = std::min(remaining, size - offset);
        if (take != 0U) {
            pending_.insert(pending_.end(), data + offset, data + offset + take);
            offset += take;
        }

        if (pending_.size() == *expected_size_) {
            frames.push_back(decode_frame(pending_));
            pending_.clear();
            expected_size_.reset();
        } else {
            break;
        }
    }
    return frames;
}

void FrameStreamDecoder::reset() noexcept {
    pending_.clear();
    expected_size_.reset();
}

std::vector<std::uint8_t> encode_time_sync_request(const TimeSyncPacket& packet) {
    if (packet.packet_type != 1U) {
        throw std::invalid_argument("request packet type must be 1");
    }
    std::vector<std::uint8_t> out{'S', 'T', packet.version, 1U};
    append_le<std::uint32_t>(out, packet.session_id, 4);
    append_le<std::uint16_t>(out, packet.receiver_id, 2);
    append_le<std::uint32_t>(out, packet.sequence, 4);
    append_i64(out, packet.t1_ns);
    out.resize(kTimeSyncPacketSize, 0U);
    return out;
}

std::vector<std::uint8_t> encode_time_sync_response(const TimeSyncPacket& packet) {
    if (packet.packet_type != 2U || !packet.t2_ns.has_value() || !packet.t3_ns.has_value()) {
        throw std::invalid_argument("response requires packet type 2 and t2/t3");
    }
    std::vector<std::uint8_t> out{'S', 'T', packet.version, 2U};
    append_le<std::uint32_t>(out, packet.session_id, 4);
    append_le<std::uint16_t>(out, packet.receiver_id, 2);
    append_le<std::uint32_t>(out, packet.sequence, 4);
    append_i64(out, packet.t1_ns);
    append_i64(out, *packet.t2_ns);
    append_i64(out, *packet.t3_ns);
    return out;
}

TimeSyncPacket decode_time_sync_packet(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() != kTimeSyncPacketSize) {
        throw std::invalid_argument("time-sync packet must be exactly 38 bytes");
    }
    if (bytes[0] != static_cast<std::uint8_t>('S') ||
        bytes[1] != static_cast<std::uint8_t>('T')) {
        throw std::invalid_argument("invalid time-sync magic");
    }
    const auto version = bytes[2];
    const auto type = bytes[3];
    if (version != 1U) {
        throw std::invalid_argument("unsupported time-sync version");
    }
    TimeSyncPacket packet;
    packet.packet_type = type;
    packet.version = version;
    packet.session_id = read_le<std::uint32_t>(bytes, 4, 4);
    packet.receiver_id = read_le<std::uint16_t>(bytes, 8, 2);
    packet.sequence = read_le<std::uint32_t>(bytes, 10, 4);
    packet.t1_ns = read_i64(bytes, 14);
    if (type == 1U) {
        packet.t2_ns.reset();
        packet.t3_ns.reset();
    } else if (type == 2U) {
        packet.t2_ns = read_i64(bytes, 22);
        packet.t3_ns = read_i64(bytes, 30);
    } else {
        throw std::invalid_argument("unknown time-sync packet type");
    }
    return packet;
}

}  // namespace syit
