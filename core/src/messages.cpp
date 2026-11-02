// SPDX-License-Identifier: Apache-2.0
#include "syit/messages.h"

#include <limits>
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace syit {
namespace {

class Writer {
public:
    template <typename UInt>
    void unsigned_value(UInt value, std::size_t width) {
        static_assert(std::is_unsigned<UInt>::value, "unsigned integer required");
        for (std::size_t i = 0; i < width; ++i) {
            bytes_.push_back(static_cast<std::uint8_t>(value & static_cast<UInt>(0xffU)));
            value = static_cast<UInt>(value >> 8U);
        }
    }

    void signed16(std::int16_t value) { unsigned_value<std::uint16_t>(static_cast<std::uint16_t>(value), 2); }
    void signed32(std::int32_t value) { unsigned_value<std::uint32_t>(static_cast<std::uint32_t>(value), 4); }
    void signed64(std::int64_t value) { unsigned_value<std::uint64_t>(static_cast<std::uint64_t>(value), 8); }

    void raw(const std::uint8_t* data, std::size_t size) {
        bytes_.insert(bytes_.end(), data, data + size);
    }

    void text8(const std::string& value, const char* field) {
        if (value.size() > 255U) {
            throw std::invalid_argument(std::string(field) + " exceeds 255 UTF-8 bytes");
        }
        if (!valid_utf8(value)) {
            throw std::invalid_argument(std::string(field) + " must be valid UTF-8");
        }
        unsigned_value<std::uint8_t>(static_cast<std::uint8_t>(value.size()), 1);
        raw(reinterpret_cast<const std::uint8_t*>(value.data()), value.size());
    }

    std::vector<std::uint8_t> take() && { return std::move(bytes_); }

private:
    static bool valid_utf8(const std::string& value) {
        const auto* p = reinterpret_cast<const unsigned char*>(value.data());
        std::size_t i = 0;
        while (i < value.size()) {
            const unsigned char c = p[i];
            if (c <= 0x7fU) { ++i; continue; }
            if (c >= 0xc2U && c <= 0xdfU) {
                if (i + 1 >= value.size() || (p[i + 1] & 0xc0U) != 0x80U) return false;
                i += 2; continue;
            }
            if (c >= 0xe0U && c <= 0xefU) {
                if (i + 2 >= value.size()) return false;
                const unsigned char c1 = p[i + 1], c2 = p[i + 2];
                if ((c1 & 0xc0U) != 0x80U || (c2 & 0xc0U) != 0x80U) return false;
                if ((c == 0xe0U && c1 < 0xa0U) || (c == 0xedU && c1 >= 0xa0U)) return false;
                i += 3; continue;
            }
            if (c >= 0xf0U && c <= 0xf4U) {
                if (i + 3 >= value.size()) return false;
                const unsigned char c1 = p[i + 1], c2 = p[i + 2], c3 = p[i + 3];
                if ((c1 & 0xc0U) != 0x80U || (c2 & 0xc0U) != 0x80U || (c3 & 0xc0U) != 0x80U) return false;
                if ((c == 0xf0U && c1 < 0x90U) || (c == 0xf4U && c1 >= 0x90U)) return false;
                i += 4; continue;
            }
            return false;
        }
        return true;
    }

    std::vector<std::uint8_t> bytes_;
};

class Reader {
public:
    explicit Reader(const std::vector<std::uint8_t>& bytes) : bytes_(bytes) {}

    template <typename UInt>
    UInt unsigned_value(std::size_t width) {
        static_assert(std::is_unsigned<UInt>::value, "unsigned integer required");
        require(width);
        std::uint64_t value = 0;
        for (std::size_t i = 0; i < width; ++i) {
            value |= static_cast<std::uint64_t>(bytes_[position_ + i]) << (8U * i);
        }
        position_ += width;
        return static_cast<UInt>(value);
    }

    std::int16_t signed16() {
        const auto raw = unsigned_value<std::uint16_t>(2);
        if (raw <= static_cast<std::uint16_t>(std::numeric_limits<std::int16_t>::max())) return static_cast<std::int16_t>(raw);
        return static_cast<std::int16_t>(-1 - static_cast<std::int32_t>(static_cast<std::uint16_t>(~raw)));
    }

    std::int32_t signed32() {
        const auto raw = unsigned_value<std::uint32_t>(4);
        if (raw <= static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max())) return static_cast<std::int32_t>(raw);
        return static_cast<std::int32_t>(-1 - static_cast<std::int64_t>(static_cast<std::uint32_t>(~raw)));
    }

    std::int64_t signed64() {
        const auto raw = unsigned_value<std::uint64_t>(8);
        if (raw <= static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) return static_cast<std::int64_t>(raw);
        return -1 - static_cast<std::int64_t>(~raw);
    }

    bool boolean(const char* field) {
        const auto value = unsigned_value<std::uint8_t>(1);
        if (value > 1U) throw std::invalid_argument(std::string(field) + " must be 0 or 1");
        return value != 0U;
    }

    std::string text8(const char* field) {
        const auto size = static_cast<std::size_t>(unsigned_value<std::uint8_t>(1));
        require(size);
        std::string value(bytes_.begin() + static_cast<std::ptrdiff_t>(position_),
                          bytes_.begin() + static_cast<std::ptrdiff_t>(position_ + size));
        position_ += size;
        if (!valid_utf8(value)) throw std::invalid_argument(std::string(field) + " must be valid UTF-8");
        return value;
    }

    void finish() const {
        if (position_ != bytes_.size()) throw std::invalid_argument("control payload has trailing bytes");
    }

private:
    void require(std::size_t size) const {
        if (position_ > bytes_.size() || size > bytes_.size() - position_) {
            throw std::invalid_argument("truncated control payload");
        }
    }

    static bool valid_utf8(const std::string& value) {
        const auto* p = reinterpret_cast<const unsigned char*>(value.data());
        std::size_t i = 0;
        while (i < value.size()) {
            const unsigned char c = p[i];
            if (c <= 0x7fU) { ++i; continue; }
            if (c >= 0xc2U && c <= 0xdfU) {
                if (i + 1 >= value.size() || (p[i + 1] & 0xc0U) != 0x80U) return false;
                i += 2; continue;
            }
            if (c >= 0xe0U && c <= 0xefU) {
                if (i + 2 >= value.size()) return false;
                const unsigned char c1 = p[i + 1], c2 = p[i + 2];
                if ((c1 & 0xc0U) != 0x80U || (c2 & 0xc0U) != 0x80U) return false;
                if ((c == 0xe0U && c1 < 0xa0U) || (c == 0xedU && c1 >= 0xa0U)) return false;
                i += 3; continue;
            }
            if (c >= 0xf0U && c <= 0xf4U) {
                if (i + 3 >= value.size()) return false;
                const unsigned char c1 = p[i + 1], c2 = p[i + 2], c3 = p[i + 3];
                if ((c1 & 0xc0U) != 0x80U || (c2 & 0xc0U) != 0x80U || (c3 & 0xc0U) != 0x80U) return false;
                if ((c == 0xf0U && c1 < 0x90U) || (c == 0xf4U && c1 >= 0x90U)) return false;
                i += 4; continue;
            }
            return false;
        }
        return true;
    }

    const std::vector<std::uint8_t>& bytes_;
    std::size_t position_{0};
};

std::uint8_t message_type(const ControlMessage& message) {
    return std::visit([](const auto& value) -> std::uint8_t {
        using T = typename std::decay<decltype(value)>::type;
        if constexpr (std::is_same<T, Hello>::value) return kHelloMessageType;
        else if constexpr (std::is_same<T, Welcome>::value) return kWelcomeMessageType;
        else if constexpr (std::is_same<T, AudioHello>::value) return kAudioHelloMessageType;
        else if constexpr (std::is_same<T, Ready>::value) return kReadyMessageType;
        else if constexpr (std::is_same<T, Report>::value) return kReportMessageType;
        else if constexpr (std::is_same<T, Anchor>::value) return kAnchorMessageType;
        else if constexpr (std::is_same<T, Mute>::value) return kMuteMessageType;
        else if constexpr (std::is_same<T, SetComp>::value) return kSetCompMessageType;
        else if constexpr (std::is_same<T, Resync>::value) return kResyncMessageType;
        else if constexpr (std::is_same<T, Leave>::value) return kLeaveMessageType;
        else if constexpr (std::is_same<T, Keepalive>::value) return kKeepaliveMessageType;
        else if constexpr (std::is_same<T, ErrorMessage>::value) return kErrorMessageType;
        else if constexpr (std::is_same<T, Volume>::value) return kVolumeMessageType;
        else if constexpr (std::is_same<T, CalibChirp>::value) return kCalibChirpMessageType;
        else return kCalibAckMessageType;
    }, message);
}

}  // namespace

Frame encode_control_message(const ControlMessage& message, std::uint8_t outer_flags) {
    Writer w;
    std::visit([&w](const auto& value) {
        using T = typename std::decay<decltype(value)>::type;
        if constexpr (std::is_same<T, Hello>::value) {
            w.unsigned_value<std::uint16_t>(value.proto_version, 2);
            w.raw(value.token.data(), value.token.size());
            w.unsigned_value<std::uint8_t>(value.platform, 1);
            w.unsigned_value<std::uint16_t>(value.app_version, 2);
            w.unsigned_value<std::uint32_t>(value.capabilities, 4);
            w.text8(value.model, "HELLO model");
        } else if constexpr (std::is_same<T, Welcome>::value) {
            w.unsigned_value<std::uint8_t>(value.result, 1);
            w.unsigned_value<std::uint16_t>(value.receiver_id, 2);
            w.unsigned_value<std::uint32_t>(value.session_id, 4);
            w.unsigned_value<std::uint32_t>(value.sample_rate_hz, 4);
            w.unsigned_value<std::uint8_t>(value.channels, 1);
            w.unsigned_value<std::uint16_t>(value.chunk_frames, 2);
            w.unsigned_value<std::uint8_t>(value.codec, 1);
            w.unsigned_value<std::uint16_t>(value.audio_port, 2);
            w.unsigned_value<std::uint16_t>(value.time_port, 2);
            w.unsigned_value<std::uint16_t>(value.startup_lead_ms, 2);
        } else if constexpr (std::is_same<T, AudioHello>::value) {
            w.unsigned_value<std::uint32_t>(value.session_id, 4);
            w.unsigned_value<std::uint16_t>(value.receiver_id, 2);
        } else if constexpr (std::is_same<T, Ready>::value) {
            w.unsigned_value<std::uint32_t>(value.buffered_ms, 4);
            w.unsigned_value<std::uint8_t>(value.sync_stable ? 1U : 0U, 1);
            w.unsigned_value<std::uint32_t>(value.residual_std_us, 4);
        } else if constexpr (std::is_same<T, Report>::value) {
            w.unsigned_value<std::uint8_t>(value.state, 1);
            w.unsigned_value<std::uint32_t>(value.buffered_ms, 4);
            w.signed16(value.skew_ppm_x10);
            w.unsigned_value<std::uint32_t>(value.residual_std_us, 4);
            w.unsigned_value<std::uint32_t>(value.rtt_median_us, 4);
            w.signed32(value.output_latency_est_us);
            w.signed32(value.err_mean_us);
            w.unsigned_value<std::uint32_t>(value.err_max_us, 4);
            w.unsigned_value<std::uint32_t>(value.slips, 4);
            w.unsigned_value<std::uint16_t>(value.hard_resyncs, 2);
            w.unsigned_value<std::uint16_t>(value.underruns, 2);
            w.unsigned_value<std::uint32_t>(value.gap_frames, 4);
        } else if constexpr (std::is_same<T, Anchor>::value) {
            if (value.kind > 4U) throw std::invalid_argument("ANCHOR kind must be in the range 0..4");
            w.unsigned_value<std::uint8_t>(value.kind, 1);
            w.signed64(value.master_time_ns);
            w.signed64(value.sample_idx);
            w.unsigned_value<std::uint8_t>(value.playing ? 1U : 0U, 1);
            w.unsigned_value<std::uint16_t>(value.crossfade_ms, 2);
        } else if constexpr (std::is_same<T, Mute>::value) {
            w.unsigned_value<std::uint8_t>(value.mute ? 1U : 0U, 1);
            w.signed64(value.at_master_time_ns);
        } else if constexpr (std::is_same<T, SetComp>::value) {
            w.signed32(value.compensation_ns);
        } else if constexpr (std::is_same<T, Resync>::value) {
            w.unsigned_value<std::uint8_t>(value.reason, 1);
            w.signed64(value.resume_from_sample_idx);
        } else if constexpr (std::is_same<T, Leave>::value) {
            w.unsigned_value<std::uint8_t>(value.reason, 1);
        } else if constexpr (std::is_same<T, Keepalive>::value) {
            w.unsigned_value<std::uint32_t>(value.counter, 4);
        } else if constexpr (std::is_same<T, ErrorMessage>::value) {
            w.unsigned_value<std::uint16_t>(value.code, 2);
            w.text8(value.detail, "ERROR detail");
        } else if constexpr (std::is_same<T, Volume>::value) {
            if (value.gain > 100U) throw std::invalid_argument("VOLUME gain must be between 0 and 100");
            w.unsigned_value<std::uint8_t>(value.gain, 1);
            w.signed64(value.at_master_time_ns);
        } else if constexpr (std::is_same<T, CalibChirp>::value) {
            w.unsigned_value<std::uint16_t>(value.chirp_id, 2);
            w.signed64(value.play_at_master_time_ns);
            w.unsigned_value<std::uint32_t>(value.f0_hz, 4);
            w.unsigned_value<std::uint32_t>(value.f1_hz, 4);
            w.unsigned_value<std::uint16_t>(value.duration_ms, 2);
            w.unsigned_value<std::uint8_t>(value.gain, 1);
        } else if constexpr (std::is_same<T, CalibAck>::value) {
            w.unsigned_value<std::uint16_t>(value.chirp_id, 2);
            w.unsigned_value<std::uint8_t>(value.status, 1);
        }
    }, message);
    return Frame{message_type(message), outer_flags, std::move(w).take()};
}

ControlMessage decode_control_message(const Frame& frame) {
    Reader r(frame.payload);
    switch (frame.message_type) {
        case kHelloMessageType: {
            Hello m;
            m.proto_version = r.unsigned_value<std::uint16_t>(2);
            for (auto& b : m.token) b = r.unsigned_value<std::uint8_t>(1);
            m.platform = r.unsigned_value<std::uint8_t>(1);
            m.app_version = r.unsigned_value<std::uint16_t>(2);
            m.capabilities = r.unsigned_value<std::uint32_t>(4);
            m.model = r.text8("HELLO model");
            r.finish();
            return m;
        }
        case kWelcomeMessageType: {
            Welcome m;
            m.result = r.unsigned_value<std::uint8_t>(1);
            m.receiver_id = r.unsigned_value<std::uint16_t>(2);
            m.session_id = r.unsigned_value<std::uint32_t>(4);
            m.sample_rate_hz = r.unsigned_value<std::uint32_t>(4);
            m.channels = r.unsigned_value<std::uint8_t>(1);
            m.chunk_frames = r.unsigned_value<std::uint16_t>(2);
            m.codec = r.unsigned_value<std::uint8_t>(1);
            m.audio_port = r.unsigned_value<std::uint16_t>(2);
            m.time_port = r.unsigned_value<std::uint16_t>(2);
            m.startup_lead_ms = r.unsigned_value<std::uint16_t>(2);
            r.finish();
            return m;
        }
        case kAudioHelloMessageType: {
            AudioHello m{r.unsigned_value<std::uint32_t>(4), r.unsigned_value<std::uint16_t>(2)};
            r.finish(); return m;
        }
        case kReadyMessageType: {
            Ready m;
            m.buffered_ms = r.unsigned_value<std::uint32_t>(4);
            m.sync_stable = r.boolean("READY syncStable");
            m.residual_std_us = r.unsigned_value<std::uint32_t>(4);
            r.finish(); return m;
        }
        case kReportMessageType: {
            Report m;
            m.state = r.unsigned_value<std::uint8_t>(1);
            m.buffered_ms = r.unsigned_value<std::uint32_t>(4);
            m.skew_ppm_x10 = r.signed16();
            m.residual_std_us = r.unsigned_value<std::uint32_t>(4);
            m.rtt_median_us = r.unsigned_value<std::uint32_t>(4);
            m.output_latency_est_us = r.signed32();
            m.err_mean_us = r.signed32();
            m.err_max_us = r.unsigned_value<std::uint32_t>(4);
            m.slips = r.unsigned_value<std::uint32_t>(4);
            m.hard_resyncs = r.unsigned_value<std::uint16_t>(2);
            m.underruns = r.unsigned_value<std::uint16_t>(2);
            m.gap_frames = r.unsigned_value<std::uint32_t>(4);
            r.finish(); return m;
        }
        case kAnchorMessageType: {
            Anchor m;
            m.kind = r.unsigned_value<std::uint8_t>(1);
            if (m.kind > 4U) throw std::invalid_argument("ANCHOR kind must be in the range 0..4");
            m.master_time_ns = r.signed64();
            m.sample_idx = r.signed64();
            m.playing = r.boolean("ANCHOR playing");
            m.crossfade_ms = r.unsigned_value<std::uint16_t>(2);
            r.finish(); return m;
        }
        case kMuteMessageType: {
            Mute m{r.boolean("MUTE mute"), r.signed64()};
            r.finish(); return m;
        }
        case kSetCompMessageType: {
            SetComp m{r.signed32()};
            r.finish(); return m;
        }
        case kResyncMessageType: {
            Resync m{r.unsigned_value<std::uint8_t>(1), r.signed64()};
            r.finish(); return m;
        }
        case kLeaveMessageType: {
            Leave m{r.unsigned_value<std::uint8_t>(1)};
            r.finish(); return m;
        }
        case kKeepaliveMessageType: {
            Keepalive m{r.unsigned_value<std::uint32_t>(4)};
            r.finish(); return m;
        }
        case kErrorMessageType: {
            ErrorMessage m;
            m.code = r.unsigned_value<std::uint16_t>(2);
            m.detail = r.text8("ERROR detail");
            r.finish(); return m;
        }
        case kVolumeMessageType: {
            Volume m;
            m.gain = r.unsigned_value<std::uint8_t>(1);
            if (m.gain > 100U) throw std::invalid_argument("VOLUME gain must be between 0 and 100");
            m.at_master_time_ns = r.signed64();
            r.finish(); return m;
        }
        case kCalibChirpMessageType: {
            CalibChirp m;
            m.chirp_id = r.unsigned_value<std::uint16_t>(2);
            m.play_at_master_time_ns = r.signed64();
            m.f0_hz = r.unsigned_value<std::uint32_t>(4);
            m.f1_hz = r.unsigned_value<std::uint32_t>(4);
            m.duration_ms = r.unsigned_value<std::uint16_t>(2);
            m.gain = r.unsigned_value<std::uint8_t>(1);
            r.finish(); return m;
        }
        case kCalibAckMessageType: {
            CalibAck m{r.unsigned_value<std::uint16_t>(2), r.unsigned_value<std::uint8_t>(1)};
            r.finish(); return m;
        }
        default:
            throw std::invalid_argument("unknown control message type");
    }
}

}  // namespace syit
