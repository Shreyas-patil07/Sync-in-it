// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace syit {

inline constexpr std::uint32_t kMaxFrameSize = 65'536;
inline constexpr std::size_t kTimeSyncPacketSize = 38;

struct Frame {
    std::uint8_t message_type{};
    std::uint8_t flags{};
    std::vector<std::uint8_t> payload;
};

struct TimeSyncPacket {
    std::uint8_t packet_type{};
    std::uint8_t version{1};
    std::uint32_t session_id{};
    std::uint16_t receiver_id{};
    std::uint32_t sequence{};
    std::int64_t t1_ns{};
    std::optional<std::int64_t> t2_ns;
    std::optional<std::int64_t> t3_ns;
};

std::vector<std::uint8_t> encode_frame(const Frame& frame);
Frame decode_frame(const std::vector<std::uint8_t>& bytes);
std::vector<std::uint8_t> encode_time_sync_request(const TimeSyncPacket& packet);
std::vector<std::uint8_t> encode_time_sync_response(const TimeSyncPacket& packet);
TimeSyncPacket decode_time_sync_packet(const std::vector<std::uint8_t>& bytes);


// Incremental TCP parser. Each instance retains no more than one incomplete frame.
class FrameStreamDecoder {
public:
    std::vector<Frame> feed(const std::uint8_t* data, std::size_t size);
    std::vector<Frame> feed(const std::vector<std::uint8_t>& bytes) {
        return feed(bytes.data(), bytes.size());
    }
    void reset() noexcept;
    std::size_t buffered_bytes() const noexcept { return pending_.size(); }

private:
    std::vector<std::uint8_t> pending_;
    std::optional<std::size_t> expected_size_;
};

}  // namespace syit
