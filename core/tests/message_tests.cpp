// SPDX-License-Identifier: Apache-2.0
#include "wire_golden_vectors.h"
#include "syit/messages.h"

#include <array>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

bool is_control_type(std::uint8_t type) {
    switch (type) {
        case syit::kHelloMessageType:
        case syit::kWelcomeMessageType:
        case syit::kAudioHelloMessageType:
        case syit::kReadyMessageType:
        case syit::kReportMessageType:
        case syit::kAnchorMessageType:
        case syit::kMuteMessageType:
        case syit::kSetCompMessageType:
        case syit::kResyncMessageType:
        case syit::kLeaveMessageType:
        case syit::kKeepaliveMessageType:
        case syit::kErrorMessageType:
        case syit::kVolumeMessageType:
        case syit::kCalibChirpMessageType:
        case syit::kCalibAckMessageType:
            return true;
        default:
            return false;
    }
}

void test_valid_control_message_vectors() {
    std::size_t count = 0;
    for (const auto& item : wire_golden::kFrames) {
        const auto frame = syit::decode_frame(item.bytes);
        if (!is_control_type(frame.message_type)) continue;
        const auto decoded = syit::decode_control_message(frame);
        const auto encoded = syit::encode_control_message(decoded, frame.flags);
        expect(encoded.message_type == frame.message_type, std::string(item.name) + ": type");
        expect(encoded.flags == frame.flags, std::string(item.name) + ": outer flags");
        expect(encoded.payload == frame.payload, std::string(item.name) + ": payload bytes");
        ++count;
    }
    expect(count == 15U, "all 15 control message types covered");
}

void test_invalid_control_payload_vectors() {
    for (const auto& item : wire_golden::kInvalidControlPayloads) {
        bool threw = false;
        try {
            (void)syit::decode_control_message(syit::Frame{item.type, 0U, item.payload});
        } catch (const std::invalid_argument&) {
            threw = true;
        }
        expect(threw, std::string(item.name) + ": invalid payload rejected");
    }
}

void test_encoder_constraints() {
    syit::Hello hello;
    hello.token.fill(0U);
    hello.model = std::string(256U, 'x');
    bool threw = false;
    try { (void)syit::encode_control_message(hello); }
    catch (const std::invalid_argument&) { threw = true; }
    expect(threw, "HELLO model longer than 255 UTF-8 bytes rejected");

    syit::Anchor anchor;
    anchor.kind = 5U;
    threw = false;
    try { (void)syit::encode_control_message(anchor); }
    catch (const std::invalid_argument&) { threw = true; }
    expect(threw, "unknown ANCHOR kind rejected");

    syit::Volume volume;
    volume.gain = 101U;
    threw = false;
    try { (void)syit::encode_control_message(volume); }
    catch (const std::invalid_argument&) { threw = true; }
    expect(threw, "VOLUME gain above 100 rejected");

    syit::ErrorMessage error;
    error.detail = std::string(1U, static_cast<char>(0xff));
    threw = false;
    try { (void)syit::encode_control_message(error); }
    catch (const std::invalid_argument&) { threw = true; }
    expect(threw, "ERROR detail with invalid UTF-8 rejected");
}

}  // namespace

int main() {
    test_valid_control_message_vectors();
    test_invalid_control_payload_vectors();
    test_encoder_constraints();
    if (failures != 0) {
        std::cerr << failures << " typed control message assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "C++17 typed control codecs: all shared message vectors passed\n";
    return EXIT_SUCCESS;
}
