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
        std::vector<std::uint8_t>(38U, 0U),
        [] { auto v = std::vector<std::uint8_t>(38U, 0U); v[0] = 0x53U; v[1] = 0x54U; v[2] = 2U; v[3] = 1U; return v; }(),
        [] { auto v = std::vector<std::uint8_t>(38U, 0U); v[0] = 0x53U; v[1] = 0x54U; v[2] = 1U; v[3] = 3U; return v; }()
    };
    for (std::size_t i = 0; i < bad.size(); ++i) {
        bool threw = false;
        try { (void)syit::decode_time_sync_packet(bad[i]); }
        catch (const std::invalid_argument&) { threw = true; }
        expect(threw, "invalid UDP packet " + std::to_string(i) + " rejected");
    }
}



bool same_frame(const syit::Frame& lhs, const syit::Frame& rhs) {
    return lhs.message_type == rhs.message_type &&
           lhs.flags == rhs.flags &&
           lhs.payload == rhs.payload;
}

void test_stream_framer_every_split_point() {
    const syit::Frame expected{0x42U, 0x03U, {1U, 2U, 3U, 4U, 5U, 6U}};
    const auto wire = syit::encode_frame(expected);
    for (std::size_t split = 0U; split <= wire.size(); ++split) {
        syit::FrameStreamDecoder decoder;
        auto frames = decoder.feed(wire.data(), split);
        const auto remainder = decoder.feed(wire.data() + split, wire.size() - split);
        frames.insert(frames.end(), remainder.begin(), remainder.end());
        expect(frames.size() == 1U, "split-point framing emits exactly one frame");
        if (frames.size() == 1U) {
            expect(same_frame(frames.front(), expected), "split-point framing preserves frame");
        }
        expect(decoder.buffered_bytes() == 0U, "split-point framing leaves no buffered bytes");
    }
}

void test_stream_framer_bytewise_and_coalesced_reads() {
    const std::vector<syit::Frame> expected = {
        {0x01U, 0x00U, {1U, 2U}},
        {0x7fU, 0x80U, {3U, 4U, 5U}},
        {0x80U, 0x00U, {6U}}
    };
    std::vector<std::uint8_t> wire;
    for (const auto& frame : expected) {
        const auto encoded = syit::encode_frame(frame);
        wire.insert(wire.end(), encoded.begin(), encoded.end());
    }

    syit::FrameStreamDecoder decoder;
    std::vector<syit::Frame> frames;
    for (const auto byte : wire) {
        const auto decoded = decoder.feed(&byte, 1U);
        frames.insert(frames.end(), decoded.begin(), decoded.end());
    }
    expect(frames.size() == expected.size(), "bytewise reads emit every coalesced frame");
    for (std::size_t i = 0U; i < expected.size() && i < frames.size(); ++i) {
        expect(same_frame(frames[i], expected[i]), "bytewise reads preserve frame order");
    }
    expect(decoder.buffered_bytes() == 0U, "bytewise reads leave no buffered bytes");

    const auto first = syit::encode_frame(expected[0]);
    const auto second = syit::encode_frame(expected[1]);
    std::vector<std::uint8_t> combined = first;
    combined.insert(combined.end(), second.begin(), second.end());
    const auto decoded = decoder.feed(combined);
    expect(decoded.size() == 2U, "one read may contain multiple complete frames");
}

void test_stream_framer_aggregate_input_and_maximum_frame() {
    const std::vector<std::uint8_t> payload(40'000U, 0x5aU);
    const auto first = syit::encode_frame(syit::Frame{0x40U, 0U, payload});
    const auto second = syit::encode_frame(syit::Frame{0x41U, 0U, payload});
    std::vector<std::uint8_t> combined = first;
    combined.insert(combined.end(), second.begin(), second.end());
    expect(combined.size() > syit::kMaxFrameSize + 4U,
           "aggregate input may exceed the per-frame maximum");
    syit::FrameStreamDecoder decoder;
    const auto frames = decoder.feed(combined);
    expect(frames.size() == 2U, "large aggregate input emits both bounded frames");
    if (frames.size() == 2U) {
        expect(frames[0].payload == payload && frames[1].payload == payload,
               "large aggregate input preserves payloads");
    }

    const std::vector<std::uint8_t> max_payload(syit::kMaxFrameSize - 2U, 0x33U);
    const auto max_wire = syit::encode_frame(syit::Frame{0x55U, 0U, max_payload});
    expect(decoder.feed(max_wire.data(), 4U).empty(), "maximum frame waits for its body");
    expect(decoder.buffered_bytes() == 4U, "partial maximum frame retains only its header");
    const auto completed = decoder.feed(max_wire.data() + 4U, max_wire.size() - 4U);
    expect(completed.size() == 1U, "maximum legal frame is accepted");
    if (completed.size() == 1U) {
        expect(completed.front().payload.size() == max_payload.size(),
               "maximum legal frame payload is intact");
    }
    expect(decoder.buffered_bytes() == 0U, "completed maximum frame releases parser state");
}

void test_stream_framer_rejects_invalid_lengths_and_recovers() {
    syit::FrameStreamDecoder decoder;
    const std::vector<std::vector<std::uint8_t>> invalid = {
        {1U, 0U, 0U, 0U},
        {1U, 0U, 1U, 0U}
    };
    for (const auto& header : invalid) {
        bool threw = false;
        try { (void)decoder.feed(header); }
        catch (const std::invalid_argument&) { threw = true; }
        expect(threw, "invalid stream frame length rejected");
        expect(decoder.buffered_bytes() == 0U, "invalid header clears partial parser state");
    }

    const auto valid = syit::encode_frame(syit::Frame{0x22U, 0U, {7U, 8U}});
    const auto recovered = decoder.feed(valid);
    expect(recovered.size() == 1U, "stream decoder can be reused after reset");
    if (recovered.size() == 1U) {
        expect(recovered.front().message_type == 0x22U, "recovered frame has expected type");
    }
}

}  // namespace

int main() {
    test_frame_vectors();
    test_invalid_frames();
    test_time_sync_vectors();
    test_invalid_time_sync_packets();
    test_stream_framer_every_split_point();
    test_stream_framer_bytewise_and_coalesced_reads();
    test_stream_framer_aggregate_input_and_maximum_frame();
    test_stream_framer_rejects_invalid_lengths_and_recovers();
    if (failures != 0) {
        std::cerr << failures << " C++ protocol assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "C++17 protocol codec: all shared wire vectors passed\n";
    return EXIT_SUCCESS;
}
