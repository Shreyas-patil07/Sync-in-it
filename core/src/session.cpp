// SPDX-License-Identifier: Apache-2.0
#include "syit/session.h"

#include <algorithm>
#include <utility>

namespace syit {

ReceiverSession::ReceiverSession(ReceiverSessionConfig config)
    : config_(std::move(config)), welcome_(config_.welcome) {
    welcome_.result = 0U;
    welcome_.receiver_id = config_.receiver_id;
    welcome_.session_id = config_.session_id;
}

bool ReceiverSession::token_matches(
    const std::array<std::uint8_t, 16>& candidate) const noexcept {
    std::uint8_t difference = 0U;
    for (std::size_t i = 0U; i < config_.token.size(); ++i) {
        difference = static_cast<std::uint8_t>(difference | (config_.token[i] ^ candidate[i]));
    }
    return difference == 0U;
}

bool ReceiverSession::platform_supported(std::uint8_t platform) const noexcept {
    return std::find(config_.supported_platforms.begin(),
                     config_.supported_platforms.end(), platform) !=
           config_.supported_platforms.end();
}

ReceiverSessionResult ReceiverSession::on_hello(const Hello& hello) {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    if (state_ != ReceiverSessionState::AwaitingHello) return ReceiverSessionResult::WrongState;

    if (hello.proto_version != config_.protocol_version) {
        close();
        return ReceiverSessionResult::UnsupportedProtocolVersion;
    }
    if (!token_matches(hello.token)) {
        close();
        return ReceiverSessionResult::InvalidToken;
    }
    if (!platform_supported(hello.platform)) {
        close();
        return ReceiverSessionResult::UnsupportedPlatform;
    }

    state_ = ReceiverSessionState::AwaitingAudioHello;
    return ReceiverSessionResult::Accepted;
}

std::optional<Welcome> ReceiverSession::welcome_message() const {
    if (state_ == ReceiverSessionState::AwaitingHello ||
        state_ == ReceiverSessionState::Closed) {
        return std::nullopt;
    }
    return welcome_;
}

ReceiverSessionResult ReceiverSession::on_audio_hello(const AudioHello& hello) {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    if (state_ != ReceiverSessionState::AwaitingAudioHello) return ReceiverSessionResult::WrongState;

    if (hello.session_id != config_.session_id) {
        close();
        return ReceiverSessionResult::WrongSession;
    }
    if (hello.receiver_id != config_.receiver_id) {
        close();
        return ReceiverSessionResult::WrongReceiver;
    }

    state_ = ReceiverSessionState::Synchronizing;
    return ReceiverSessionResult::Accepted;
}

ReceiverSessionResult ReceiverSession::on_ready(const Ready& ready) {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    // READY is retried by the Speaker until it receives the coordinated ANCHOR.
    // Treat repeats as idempotent while READY so a lost/transiently rejected first
    // readiness report cannot strand the Host waiting forever.
    if (state_ != ReceiverSessionState::Synchronizing &&
        state_ != ReceiverSessionState::Ready) {
        return ReceiverSessionResult::WrongState;
    }
    if (!ready.sync_stable) return ReceiverSessionResult::ClockNotStable;
    if (ready.buffered_ms < config_.minimum_buffered_ms) {
        return ReceiverSessionResult::BufferInsufficient;
    }
    if (ready.residual_std_us > config_.maximum_residual_std_us) {
        return ReceiverSessionResult::ClockResidualTooHigh;
    }

    state_ = ReceiverSessionState::Ready;
    return ReceiverSessionResult::Accepted;
}

ReceiverSessionResult ReceiverSession::arm() {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    if (state_ != ReceiverSessionState::Ready) return ReceiverSessionResult::WrongState;
    state_ = ReceiverSessionState::Armed;
    return ReceiverSessionResult::Accepted;
}

ReceiverSessionResult ReceiverSession::on_anchor(const Anchor& anchor) {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    if (anchor.kind > 4U || anchor.master_time_ns < 0 || anchor.sample_idx < 0) {
        return ReceiverSessionResult::InvalidAnchor;
    }

    switch (anchor.kind) {
        case 0U:  // start
            if (state_ != ReceiverSessionState::Armed || !anchor.playing) {
                return ReceiverSessionResult::WrongState;
            }
            state_ = ReceiverSessionState::Playing;
            return ReceiverSessionResult::Accepted;

        case 1U:  // pause
            if (state_ != ReceiverSessionState::Playing || anchor.playing) {
                return ReceiverSessionResult::WrongState;
            }
            state_ = ReceiverSessionState::Paused;
            return ReceiverSessionResult::Accepted;

        case 2U:  // resume
            if (state_ != ReceiverSessionState::Paused || !anchor.playing) {
                return ReceiverSessionResult::WrongState;
            }
            state_ = ReceiverSessionState::Playing;
            return ReceiverSessionResult::Accepted;

        case 3U:  // seek
            if ((state_ == ReceiverSessionState::Playing && anchor.playing) ||
                (state_ == ReceiverSessionState::Paused && !anchor.playing)) {
                return ReceiverSessionResult::Accepted;
            }
            return ReceiverSessionResult::WrongState;

        case 4U:  // coordinated delay change
            if ((state_ == ReceiverSessionState::Armed && !anchor.playing) ||
                (state_ == ReceiverSessionState::Playing && anchor.playing) ||
                (state_ == ReceiverSessionState::Paused && !anchor.playing)) {
                return ReceiverSessionResult::Accepted;
            }
            return ReceiverSessionResult::WrongState;

        default:
            return ReceiverSessionResult::InvalidAnchor;
    }
}

ReceiverSessionResult ReceiverSession::mark_degraded() {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    if (state_ != ReceiverSessionState::Synchronizing &&
        state_ != ReceiverSessionState::Ready &&
        state_ != ReceiverSessionState::Armed &&
        state_ != ReceiverSessionState::Playing &&
        state_ != ReceiverSessionState::Paused) {
        return ReceiverSessionResult::WrongState;
    }
    state_ = ReceiverSessionState::Degraded;
    return ReceiverSessionResult::Accepted;
}

ReceiverSessionResult ReceiverSession::begin_resynchronization() {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    if (state_ != ReceiverSessionState::Degraded) return ReceiverSessionResult::WrongState;
    state_ = ReceiverSessionState::Synchronizing;
    return ReceiverSessionResult::Accepted;
}

ReceiverSessionResult ReceiverSession::on_leave() {
    if (state_ == ReceiverSessionState::Closed) return ReceiverSessionResult::Closed;
    close();
    return ReceiverSessionResult::Accepted;
}

}  // namespace syit
