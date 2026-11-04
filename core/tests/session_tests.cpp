// SPDX-License-Identifier: Apache-2.0
#include "syit/session.h"

#include <array>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {
int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

syit::ReceiverSessionConfig make_config() {
    syit::ReceiverSessionConfig config;
    for (std::size_t i = 0U; i < config.token.size(); ++i) {
        config.token[i] = static_cast<std::uint8_t>(i + 1U);
    }
    config.session_id = 0x89abcdefU;
    config.receiver_id = 0x1234U;
    config.minimum_buffered_ms = 500U;
    config.maximum_residual_std_us = 250U;
    config.welcome.sample_rate_hz = 48'000U;
    config.welcome.channels = 2U;
    config.welcome.chunk_frames = 960U;
    config.welcome.codec = 0U;
    config.welcome.audio_port = 47801U;
    config.welcome.time_port = 47802U;
    config.welcome.startup_lead_ms = 1'000U;
    return config;
}

syit::Hello make_hello(const syit::ReceiverSessionConfig& config) {
    syit::Hello hello;
    hello.proto_version = config.protocol_version;
    hello.token = config.token;
    hello.platform = 1U;
    hello.model = "Pixel test device";
    return hello;
}

syit::Ready ready_message() {
    return syit::Ready{1'000U, true, 100U};
}

syit::Anchor anchor(std::uint8_t kind, bool playing, std::int64_t sample = 0) {
    return syit::Anchor{kind, 1'000'000'000LL, sample, playing, 0U};
}

void join_to_sync(syit::ReceiverSession& session,
                  const syit::ReceiverSessionConfig& config) {
    expect(session.on_hello(make_hello(config)) == syit::ReceiverSessionResult::Accepted,
           "valid HELLO accepted");
    expect(session.state() == syit::ReceiverSessionState::AwaitingAudioHello,
           "HELLO advances to AUDIO_HELLO state");
    expect(session.on_audio_hello({config.session_id, config.receiver_id}) ==
               syit::ReceiverSessionResult::Accepted,
           "matching AUDIO_HELLO accepted");
    expect(session.state() == syit::ReceiverSessionState::Synchronizing,
           "AUDIO_HELLO advances to synchronizing");
}

void test_successful_handshake_and_playback_lifecycle() {
    const auto config = make_config();
    syit::ReceiverSession session(config);

    expect(session.state() == syit::ReceiverSessionState::AwaitingHello,
           "new receiver awaits HELLO");
    expect(!session.welcome_message().has_value(), "WELCOME unavailable before authentication");
    expect(session.on_hello(make_hello(config)) == syit::ReceiverSessionResult::Accepted,
           "valid HELLO accepted");
    const auto welcome = session.welcome_message();
    expect(welcome.has_value(), "WELCOME available after HELLO");
    if (welcome.has_value()) {
        expect(welcome->result == 0U, "WELCOME reports success");
        expect(welcome->session_id == config.session_id, "WELCOME session ID is assigned session");
        expect(welcome->receiver_id == config.receiver_id, "WELCOME receiver ID is assigned receiver");
        expect(welcome->audio_port == config.welcome.audio_port, "WELCOME preserves configured audio port");
    }

    expect(session.on_audio_hello({config.session_id, config.receiver_id}) ==
               syit::ReceiverSessionResult::Accepted,
           "matching AUDIO_HELLO accepted");
    expect(session.on_ready(ready_message()) == syit::ReceiverSessionResult::Accepted,
           "stable synchronized receiver with enough buffer becomes READY");
    expect(session.arm() == syit::ReceiverSessionResult::Accepted, "READY receiver can be armed");
    expect(session.on_anchor(anchor(0U, true)) == syit::ReceiverSessionResult::Accepted,
           "start anchor transitions ARMED to PLAYING");
    expect(session.state() == syit::ReceiverSessionState::Playing, "receiver is playing");

    expect(session.on_anchor(anchor(1U, false, 960)) == syit::ReceiverSessionResult::Accepted,
           "pause anchor transitions PLAYING to PAUSED");
    expect(session.state() == syit::ReceiverSessionState::Paused, "receiver is paused");
    expect(session.on_anchor(anchor(2U, true, 960)) == syit::ReceiverSessionResult::Accepted,
           "resume anchor transitions PAUSED to PLAYING");
    expect(session.on_anchor(anchor(3U, true, 1'920)) == syit::ReceiverSessionResult::Accepted,
           "seek anchor preserves PLAYING state");
    expect(session.on_anchor(anchor(4U, true, 1'920)) == syit::ReceiverSessionResult::Accepted,
           "delay-change anchor accepted during playback");

    expect(session.on_leave() == syit::ReceiverSessionResult::Accepted, "LEAVE closes receiver session");
    expect(session.state() == syit::ReceiverSessionState::Closed, "LEAVE is terminal");
    expect(session.on_ready(ready_message()) == syit::ReceiverSessionResult::Closed,
           "closed session rejects later messages");
    expect(!session.welcome_message().has_value(), "closed session does not expose WELCOME");
}

void test_multiple_receivers_share_session_but_keep_independent_state() {
    auto first_config = make_config();
    auto second_config = first_config;
    second_config.receiver_id = static_cast<std::uint16_t>(first_config.receiver_id + 1U);

    syit::ReceiverSession first(first_config);
    syit::ReceiverSession second(second_config);

    expect(first_config.session_id == second_config.session_id,
           "receivers share one host session ID");
    expect(first_config.receiver_id != second_config.receiver_id,
           "each receiver receives a unique receiver ID");
    join_to_sync(first, first_config);
    join_to_sync(second, second_config);

    const auto first_welcome = first.welcome_message();
    const auto second_welcome = second.welcome_message();
    expect(first_welcome.has_value() && second_welcome.has_value(),
           "both admitted receivers receive WELCOME");
    if (first_welcome.has_value() && second_welcome.has_value()) {
        expect(first_welcome->session_id == second_welcome->session_id,
               "WELCOME frames identify the same shared host session");
        expect(first_welcome->receiver_id != second_welcome->receiver_id,
               "WELCOME frames carry distinct receiver identities");
    }

    expect(first.on_ready(ready_message()) == syit::ReceiverSessionResult::Accepted,
           "first receiver can become READY independently");
    expect(first.state() == syit::ReceiverSessionState::Ready,
           "first receiver advances to READY");
    expect(second.state() == syit::ReceiverSessionState::Synchronizing,
           "first receiver READY does not advance the second receiver");

    expect(second.on_ready(ready_message()) == syit::ReceiverSessionResult::Accepted,
           "second receiver can become READY independently");
    expect(second.state() == syit::ReceiverSessionState::Ready,
           "second receiver advances to READY");

    expect(first.on_leave() == syit::ReceiverSessionResult::Accepted,
           "first receiver can leave independently");
    expect(first.state() == syit::ReceiverSessionState::Closed,
           "first receiver is closed after LEAVE");
    expect(second.state() == syit::ReceiverSessionState::Ready,
           "first receiver leaving does not close the second receiver");
}

void test_authentication_failures_close_session() {
    {
        const auto config = make_config();
        syit::ReceiverSession session(config);
        auto hello = make_hello(config);
        hello.token[0] ^= 0x01U;
        expect(session.on_hello(hello) == syit::ReceiverSessionResult::InvalidToken,
               "invalid token is rejected");
        expect(session.state() == syit::ReceiverSessionState::Closed,
               "invalid token closes the session before setup");
    }
    {
        const auto config = make_config();
        syit::ReceiverSession session(config);
        auto hello = make_hello(config);
        ++hello.proto_version;
        expect(session.on_hello(hello) == syit::ReceiverSessionResult::UnsupportedProtocolVersion,
               "incompatible protocol version is rejected");
        expect(session.state() == syit::ReceiverSessionState::Closed,
               "protocol mismatch closes the session");
    }
    {
        const auto config = make_config();
        syit::ReceiverSession session(config);
        auto hello = make_hello(config);
        hello.platform = 99U;
        expect(session.on_hello(hello) == syit::ReceiverSessionResult::UnsupportedPlatform,
               "unsupported platform is rejected");
        expect(session.state() == syit::ReceiverSessionState::Closed,
               "unsupported platform closes the session");
    }
}

void test_identity_binding_and_order() {
    {
        const auto config = make_config();
        syit::ReceiverSession session(config);
        expect(session.on_audio_hello({config.session_id, config.receiver_id}) ==
                   syit::ReceiverSessionResult::WrongState,
               "AUDIO_HELLO before HELLO is rejected without advancing");
        expect(session.state() == syit::ReceiverSessionState::AwaitingHello,
               "out-of-order AUDIO_HELLO does not change state");
    }
    {
        const auto config = make_config();
        syit::ReceiverSession session(config);
        expect(session.on_hello(make_hello(config)) == syit::ReceiverSessionResult::Accepted,
               "HELLO accepted before identity test");
        expect(session.on_audio_hello({config.session_id + 1U, config.receiver_id}) ==
                   syit::ReceiverSessionResult::WrongSession,
               "wrong AUDIO_HELLO session is rejected");
        expect(session.state() == syit::ReceiverSessionState::Closed,
               "wrong session closes connection");
    }
    {
        const auto config = make_config();
        syit::ReceiverSession session(config);
        expect(session.on_hello(make_hello(config)) == syit::ReceiverSessionResult::Accepted,
               "HELLO accepted before receiver identity test");
        expect(session.on_audio_hello({config.session_id,
                                       static_cast<std::uint16_t>(config.receiver_id + 1U)}) ==
                   syit::ReceiverSessionResult::WrongReceiver,
               "wrong AUDIO_HELLO receiver is rejected");
        expect(session.state() == syit::ReceiverSessionState::Closed,
               "wrong receiver closes connection");
    }
}

void test_readiness_gates_and_recovery() {
    const auto config = make_config();
    syit::ReceiverSession session(config);
    join_to_sync(session, config);

    auto ready = ready_message();
    ready.sync_stable = false;
    expect(session.on_ready(ready) == syit::ReceiverSessionResult::ClockNotStable,
           "unstable clock cannot become READY");
    expect(session.state() == syit::ReceiverSessionState::Synchronizing,
           "unstable clock remains in synchronization");

    ready = ready_message();
    ready.buffered_ms = config.minimum_buffered_ms - 1U;
    expect(session.on_ready(ready) == syit::ReceiverSessionResult::BufferInsufficient,
           "undersized buffer cannot become READY");

    ready = ready_message();
    ready.residual_std_us = config.maximum_residual_std_us + 1U;
    expect(session.on_ready(ready) == syit::ReceiverSessionResult::ClockResidualTooHigh,
           "residual above configured limit cannot become READY");

    expect(session.on_ready(ready_message()) == syit::ReceiverSessionResult::Accepted,
           "receiver becomes READY after all gates pass");
    expect(session.on_ready(ready_message()) == syit::ReceiverSessionResult::Accepted,
           "duplicate READY is idempotently accepted while waiting for the group anchor");
    expect(session.state() == syit::ReceiverSessionState::Ready,
           "duplicate READY keeps receiver in READY");
    expect(session.mark_degraded() == syit::ReceiverSessionResult::Accepted,
           "ready receiver can become DEGRADED");
    expect(session.begin_resynchronization() == syit::ReceiverSessionResult::Accepted,
           "degraded receiver can restart synchronization");
    expect(session.state() == syit::ReceiverSessionState::Synchronizing,
           "resynchronization requires fresh readiness checks");
    expect(session.arm() == syit::ReceiverSessionResult::WrongState,
           "receiver cannot arm before fresh READY");
    expect(session.on_ready(ready_message()) == syit::ReceiverSessionResult::Accepted,
           "fresh readiness checks restore READY");
    expect(session.arm() == syit::ReceiverSessionResult::Accepted,
           "recovered receiver can arm");
}

void test_invalid_and_out_of_order_anchors() {
    const auto config = make_config();
    syit::ReceiverSession session(config);
    join_to_sync(session, config);

    expect(session.on_anchor(anchor(0U, true)) == syit::ReceiverSessionResult::WrongState,
           "START is rejected before READY/ARMED");
    expect(session.on_ready(ready_message()) == syit::ReceiverSessionResult::Accepted,
           "receiver ready for anchor validation");
    expect(session.on_anchor(anchor(0U, true)) == syit::ReceiverSessionResult::WrongState,
           "START is rejected before arm");
    expect(session.arm() == syit::ReceiverSessionResult::Accepted, "receiver arms");

    auto bad = anchor(0U, false);
    expect(session.on_anchor(bad) == syit::ReceiverSessionResult::WrongState,
           "START requires playing=true");
    bad = anchor(5U, true);
    expect(session.on_anchor(bad) == syit::ReceiverSessionResult::InvalidAnchor,
           "unknown anchor kind rejected");
    bad = anchor(0U, true, -1);
    expect(session.on_anchor(bad) == syit::ReceiverSessionResult::InvalidAnchor,
           "negative sample index rejected");
    bad.master_time_ns = -1;
    bad.sample_idx = 0;
    expect(session.on_anchor(bad) == syit::ReceiverSessionResult::InvalidAnchor,
           "negative master time rejected");

    expect(session.on_anchor(anchor(0U, true)) == syit::ReceiverSessionResult::Accepted,
           "valid start anchor accepted");
    expect(session.on_anchor(anchor(2U, true)) == syit::ReceiverSessionResult::WrongState,
           "RESUME is rejected when not paused");
    expect(session.on_anchor(anchor(1U, false)) == syit::ReceiverSessionResult::Accepted,
           "receiver can pause after start");
    expect(session.on_anchor(anchor(1U, false)) == syit::ReceiverSessionResult::WrongState,
           "duplicate PAUSE is rejected");
}

void test_degradation_and_close_are_state_bounded() {
    const auto config = make_config();
    syit::ReceiverSession session(config);
    expect(session.mark_degraded() == syit::ReceiverSessionResult::WrongState,
           "unjoined session cannot be marked degraded");
    expect(session.begin_resynchronization() == syit::ReceiverSessionResult::WrongState,
           "only degraded session can resynchronize");
    session.close();
    expect(session.on_hello(make_hello(config)) == syit::ReceiverSessionResult::Closed,
           "explicit close is terminal");
    expect(session.on_leave() == syit::ReceiverSessionResult::Closed,
           "duplicate LEAVE reports closed");
}

}  // namespace

int main() {
    test_successful_handshake_and_playback_lifecycle();
    test_multiple_receivers_share_session_but_keep_independent_state();
    test_authentication_failures_close_session();
    test_identity_binding_and_order();
    test_readiness_gates_and_recovery();
    test_invalid_and_out_of_order_anchors();
    test_degradation_and_close_are_state_bounded();
    if (failures != 0) {
        std::cerr << failures << " receiver session assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "C++17 receiver session state machine: all assertions passed\n";
    return EXIT_SUCCESS;
}
