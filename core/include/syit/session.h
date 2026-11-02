// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "syit/messages.h"

#include <array>
#include <cstdint>
#include <optional>
#include <vector>

namespace syit {

enum class ReceiverSessionState : std::uint8_t {
    AwaitingHello,
    AwaitingAudioHello,
    Synchronizing,
    Ready,
    Armed,
    Playing,
    Paused,
    Degraded,
    Closed
};

enum class ReceiverSessionResult : std::uint8_t {
    Accepted,
    WrongState,
    UnsupportedProtocolVersion,
    InvalidToken,
    UnsupportedPlatform,
    WrongSession,
    WrongReceiver,
    ClockNotStable,
    BufferInsufficient,
    ClockResidualTooHigh,
    InvalidAnchor,
    Closed
};

struct ReceiverSessionConfig {
    std::uint16_t protocol_version{1U};
    std::array<std::uint8_t, 16> token{};
    std::uint32_t session_id{};
    std::uint16_t receiver_id{};
    std::uint32_t minimum_buffered_ms{};
    std::uint32_t maximum_residual_std_us{UINT32_MAX};
    std::vector<std::uint8_t> supported_platforms{1U, 2U};
    Welcome welcome{};
};

// Master-side state for one authenticated receiver control connection.
// Keep each instance on one network/control thread.
class ReceiverSession {
public:
    explicit ReceiverSession(ReceiverSessionConfig config);

    ReceiverSessionState state() const noexcept { return state_; }

    // A successful HELLO advances to AwaitingAudioHello. The caller sends
    // welcome_message() on that same authenticated control connection.
    ReceiverSessionResult on_hello(const Hello& hello);
    std::optional<Welcome> welcome_message() const;

    // AUDIO_HELLO binds the receiver's audio connection to the assigned IDs.
    ReceiverSessionResult on_audio_hello(const AudioHello& hello);

    // READY is accepted only after time sync and buffering meet configured gates.
    ReceiverSessionResult on_ready(const Ready& ready);

    ReceiverSessionResult arm();
    ReceiverSessionResult on_anchor(const Anchor& anchor);

    // Degraded receivers must complete a fresh sync/buffer gate before re-arming.
    ReceiverSessionResult mark_degraded();
    ReceiverSessionResult begin_resynchronization();
    ReceiverSessionResult on_leave();

    void close() noexcept { state_ = ReceiverSessionState::Closed; }

private:
    bool token_matches(const std::array<std::uint8_t, 16>& candidate) const noexcept;
    bool platform_supported(std::uint8_t platform) const noexcept;

    ReceiverSessionConfig config_;
    Welcome welcome_;
    ReceiverSessionState state_{ReceiverSessionState::AwaitingHello};
};

}  // namespace syit
