// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "syit/protocol.h"

#include <array>
#include <cstdint>
#include <string>
#include <variant>

namespace syit {

struct Hello {
    std::uint16_t proto_version{};
    std::array<std::uint8_t, 16> token{};
    std::uint8_t platform{};
    std::uint16_t app_version{};
    std::uint32_t capabilities{};
    std::string model;
};

struct Welcome {
    std::uint8_t result{};
    std::uint16_t receiver_id{};
    std::uint32_t session_id{};
    std::uint32_t sample_rate_hz{};
    std::uint8_t channels{};
    std::uint16_t chunk_frames{};
    std::uint8_t codec{};
    std::uint16_t audio_port{};
    std::uint16_t time_port{};
    std::uint16_t startup_lead_ms{};
};

struct AudioHello { std::uint32_t session_id{}; std::uint16_t receiver_id{}; };
struct Ready { std::uint32_t buffered_ms{}; bool sync_stable{}; std::uint32_t residual_std_us{}; };

struct Report {
    std::uint8_t state{};
    std::uint32_t buffered_ms{};
    std::int16_t skew_ppm_x10{};
    std::uint32_t residual_std_us{};
    std::uint32_t rtt_median_us{};
    std::int32_t output_latency_est_us{};
    std::int32_t err_mean_us{};
    std::uint32_t err_max_us{};
    std::uint32_t slips{};
    std::uint16_t hard_resyncs{};
    std::uint16_t underruns{};
    std::uint32_t gap_frames{};
};

struct Anchor {
    std::uint8_t kind{};
    std::int64_t master_time_ns{};
    std::int64_t sample_idx{};
    bool playing{};
    std::uint16_t crossfade_ms{};
};

struct Mute { bool mute{}; std::int64_t at_master_time_ns{}; };
struct SetComp { std::int32_t compensation_ns{}; };
struct Resync { std::uint8_t reason{}; std::int64_t resume_from_sample_idx{}; };
struct Leave { std::uint8_t reason{}; };
struct Keepalive { std::uint32_t counter{}; };
struct ErrorMessage { std::uint16_t code{}; std::string detail; };
struct Volume { std::uint8_t gain{}; std::int64_t at_master_time_ns{}; };

struct CalibChirp {
    std::uint16_t chirp_id{};
    std::int64_t play_at_master_time_ns{};
    std::uint32_t f0_hz{};
    std::uint32_t f1_hz{};
    std::uint16_t duration_ms{};
    std::uint8_t gain{};
};

struct CalibAck { std::uint16_t chirp_id{}; std::uint8_t status{}; };

using ControlMessage = std::variant<
    Hello, Welcome, AudioHello, Ready, Report, Anchor, Mute, SetComp,
    Resync, Leave, Keepalive, ErrorMessage, Volume, CalibChirp, CalibAck>;

inline constexpr std::uint8_t kHelloMessageType = 0x01;
inline constexpr std::uint8_t kWelcomeMessageType = 0x02;
inline constexpr std::uint8_t kAudioHelloMessageType = 0x03;
inline constexpr std::uint8_t kReadyMessageType = 0x04;
inline constexpr std::uint8_t kReportMessageType = 0x05;
inline constexpr std::uint8_t kAnchorMessageType = 0x06;
inline constexpr std::uint8_t kMuteMessageType = 0x07;
inline constexpr std::uint8_t kSetCompMessageType = 0x08;
inline constexpr std::uint8_t kResyncMessageType = 0x09;
inline constexpr std::uint8_t kLeaveMessageType = 0x0A;
inline constexpr std::uint8_t kKeepaliveMessageType = 0x0B;
inline constexpr std::uint8_t kErrorMessageType = 0x0C;
inline constexpr std::uint8_t kVolumeMessageType = 0x0D;
inline constexpr std::uint8_t kCalibChirpMessageType = 0x20;
inline constexpr std::uint8_t kCalibAckMessageType = 0x21;

Frame encode_control_message(const ControlMessage& message, std::uint8_t outer_flags = 0);
ControlMessage decode_control_message(const Frame& frame);

}  // namespace syit
