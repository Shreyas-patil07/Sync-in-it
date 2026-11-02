// SPDX-License-Identifier: Apache-2.0
#include "wire_golden_vectors.h"
#include "syit/protocol.h"

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

void test_frame_vectors() {
    for (const auto& item : wire_golden::kFrames) {
        const auto decoded = syit::decode_frame(item.bytes);
        expect(syit::encode_frame(decoded) == item.bytes, std::string(item.name) + ": round trip");
        expect(decoded.message_type == item.bytes[4], std::string(item.name) + ": message type");
        expect(decoded.flags == item.bytes[5], std::string(item.name) + ": flags");
    }
}

void test_invalid_frames() {
    for (const auto& item : wire_golden::kInvalidFrames) {
        bool threw = false;
        try { (void)syit::decode_frame(item.bytes); }
        catch (const std::invalid_argument&) { threw = true; }
        expect(threw, std::string(item.name) + ": malformed frame rejected");
    }
    bool threw = false;
    try { (void)syit::encode_frame(syit::Frame{1U, 0U, std::vector<std::uint8_t>(65'535U)}); }
    catch (const std::invalid_argument&) { threw = true; }
    expect(threw, "oversized encoded frame rejected");
}

void test_time_sync_vectors() {
    for (const auto& item : wire_golden::kTimeSync) {
        syit::TimeSyncPacket packet;
        packet.packet_type = item.type;
        packet.version = 1U;
        packet.session_id = item.session_id;
        packet.receiver_id = item.receiver_id;
        packet.sequence = item.sequence;
        packet.t1_ns = item.t1_ns;
        if (item.type == 2U) { packet.t2_ns = item.t2_ns; packet.t3_ns = item.t3_ns; }
        const auto encoded = item.type == 1U
            ? syit::encode_time_sync_request(packet)
            : syit::encode_time_sync_response(packet);
        expect(encoded == item.bytes, std::string(item.name) + ": UDP bytes");
        const auto decoded = syit::decode_time_sync_packet(item.bytes);
        expect(decoded.packet_type == item.type, std::string(item.name) + ": type");
        expect(decoded.session_id == item.session_id, std::string(item.name) + ": session ID");
        expect(decoded.receiver_id == item.receiver_id, std::string(item.name) + ": receiver ID");
        expect(decoded.sequence == item.sequence, std::string(item.name) + ": sequence");
        expect(decoded.t1_ns == item.t1_ns, std::string(item.name) + ": t1");
        if (item.type == 2U) {
            expect(decoded.t2_ns == item.t2_ns, std::string(item.name) + ": t2");
            expect(decoded.t3_ns == item.t3_ns, std::string(item.name) + ": t3");
        }
    }
}

void test_invalid_time_sync_packets() {
    const std::vector<std::vector<std::uint8_t>> bad = {
        {}, std::vector<std::uint8_t>(37U), std::vector<std::uint8_t>(39U),
        std::vector<std::uint8_t>(38U, 0U)
    };
    for (std::size_t i = 0; i < bad.size(); ++i) {
        bool threw = false;
        try { (void)syit::decode_time_sync_packet(bad[i]); }
        catch (const std::invalid_argument&) { threw = true; }
        expect(threw, "invalid UDP packet " + std::to_string(i) + " rejected");
    }
}

}  // namespace

int main() {
    test_frame_vectors();
    test_invalid_frames();
    test_time_sync_vectors();
    test_invalid_time_sync_packets();
    if (failures != 0) {
        std::cerr << failures << " C++ protocol assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "C++17 protocol codec: all shared wire vectors passed\n";
    return EXIT_SUCCESS;
}
