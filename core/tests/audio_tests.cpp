// SPDX-License-Identifier: Apache-2.0
#include "syit/audio.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

syit::AudioChunk pcm_chunk(
    std::uint32_t session,
    std::uint32_t sequence,
    syit::SampleIndex start,
    std::uint16_t frames,
    std::uint8_t channels = 2U) {
    syit::AudioChunk chunk;
    chunk.session_id = session;
    chunk.sequence = sequence;
    chunk.sample_index = start;
    chunk.frames = frames;
    chunk.codec = syit::kAudioCodecPcm16;
    chunk.channels = channels;
    chunk.payload.resize(static_cast<std::size_t>(frames) * channels * 2U);
    for (std::size_t i = 0U; i < chunk.payload.size() / 2U; ++i) {
        const auto value = static_cast<std::uint16_t>(i + 1U);
        chunk.payload[i * 2U] = static_cast<std::uint8_t>(value & 0xffU);
        chunk.payload[i * 2U + 1U] = static_cast<std::uint8_t>(value >> 8U);
    }
    return chunk;
}

void test_audio_chunk_round_trip() {
    const auto input = pcm_chunk(0x12345678U, 77U, 960LL, 3U);
    const auto frame = syit::encode_audio_chunk(input);
    expect(frame.message_type == syit::kAudioChunkMessageType, "audio codec uses type 0x80");
    expect(frame.flags == 0U, "audio outer flags remain zero");
    expect(frame.payload.size() == syit::kAudioChunkHeaderSize + input.payload.size(),
           "audio codec emits 21-byte typed header plus PCM payload");

    const auto decoded = syit::decode_audio_chunk(frame);
    expect(decoded.session_id == input.session_id, "audio session ID round-trips");
    expect(decoded.sequence == input.sequence, "audio sequence round-trips");
    expect(decoded.sample_index == input.sample_index, "audio sample index round-trips");
    expect(decoded.frames == input.frames, "audio frame count round-trips");
    expect(decoded.codec == input.codec && decoded.channels == input.channels,
           "audio codec and channels round-trip");
    expect(decoded.flags == input.flags && decoded.payload == input.payload,
           "audio flags and PCM payload round-trip");
}

void test_signed_pcm16_and_reserved_flags() {
    syit::AudioChunk chunk;
    chunk.session_id = 9U;
    chunk.sample_index = 0LL;
    chunk.frames = 1U;
    chunk.channels = 2U;
    chunk.flags = 0x80U;  // Reserved minor-version extension bit; ignore in v1.
    chunk.payload = {0xffU, 0xffU, 0x00U, 0x80U};

    syit::PcmJitterBuffer buffer(4U, 2U);
    buffer.reset(0LL);
    expect(buffer.insert(chunk) == syit::JitterInsertResult::Inserted,
           "reserved chunk flags do not break forward-compatible PCM decode");
    std::array<std::int16_t, 2> output{};
    const auto result = buffer.read(0LL, 1U, output.data(), output.size());
    expect(result.missing_frames == 0U, "signed PCM frame is available");
    expect(output[0] == -1 && output[1] == -32'768,
           "PCM16 little-endian values decode across the signed boundary");
}

void test_silence_and_gap_chunks() {
    syit::AudioChunk silence;
    silence.session_id = 2U;
    silence.sample_index = 120U;
    silence.frames = 960U;
    silence.channels = 2U;
    silence.flags = static_cast<std::uint8_t>(
        syit::kAudioChunkFlagGap | syit::kAudioChunkFlagSilence);
    const auto decoded = syit::decode_audio_chunk(syit::encode_audio_chunk(silence));
    expect(decoded.payload.empty(), "silence chunks do not transmit PCM payload");
    expect((decoded.flags & syit::kAudioChunkFlagSilence) != 0U, "silence flag round-trips");
    expect((decoded.flags & syit::kAudioChunkFlagGap) != 0U, "gap flag round-trips");
}

void test_invalid_audio_frames_rejected() {
    const auto valid = syit::encode_audio_chunk(pcm_chunk(1U, 1U, 0LL, 2U));
    auto wrong_type = valid;
    wrong_type.message_type = 0x7fU;
    bool rejected = false;
    try { (void)syit::decode_audio_chunk(wrong_type); }
    catch (const std::invalid_argument&) { rejected = true; }
    expect(rejected, "non-audio message type rejected");

    auto short_payload = valid;
    short_payload.payload.pop_back();
    rejected = false;
    try { (void)syit::decode_audio_chunk(short_payload); }
    catch (const std::invalid_argument&) { rejected = true; }
    expect(rejected, "truncated PCM payload rejected");

    auto bad_outer_flags = valid;
    bad_outer_flags.flags = 1U;
    rejected = false;
    try { (void)syit::decode_audio_chunk(bad_outer_flags); }
    catch (const std::invalid_argument&) { rejected = true; }
    expect(rejected, "nonzero outer flags rejected");

    auto bad_channel = pcm_chunk(1U, 1U, 0LL, 1U);
    bad_channel.channels = 3U;
    rejected = false;
    try { (void)syit::encode_audio_chunk(bad_channel); }
    catch (const std::invalid_argument&) { rejected = true; }
    expect(rejected, "unsupported PCM channel count rejected");
}

void test_jitter_buffer_orders_chunks_by_sample_index() {
    syit::PcmJitterBuffer buffer(16U, 2U);
    buffer.reset(0LL);
    auto later = pcm_chunk(3U, 2U, 4LL, 2U);
    auto earlier = pcm_chunk(3U, 1U, 0LL, 4U);
    expect(buffer.insert(later) == syit::JitterInsertResult::Inserted,
           "jitter buffer accepts a future chunk");
    expect(buffer.contiguous_buffered_frames() == 0U,
           "future chunk does not count as contiguous prebuffer");
    expect(buffer.insert(earlier) == syit::JitterInsertResult::Inserted,
           "jitter buffer accepts earlier out-of-order chunk");
    expect(buffer.contiguous_buffered_frames() == 6U,
           "out-of-order arrivals form a contiguous six-frame range");

    std::array<std::int16_t, 12> output{};
    const auto result = buffer.read(0LL, 6U, output.data(), output.size());
    expect(result.frames_read == 6U && result.missing_frames == 0U,
           "jitter buffer drains all contiguous frames without gaps");
    expect(output[0] == 1 && output[1] == 2 && output[8] == 1 && output[9] == 2,
           "jitter buffer preserves sample payload values");
    expect(buffer.next_sample_index() == 6LL, "jitter buffer advances sample cursor");
}

void test_jitter_buffer_duplicate_late_and_window_rules() {
    syit::PcmJitterBuffer buffer(8U, 2U);
    buffer.reset(10LL);
    const auto chunk = pcm_chunk(1U, 1U, 10LL, 4U);
    expect(buffer.insert(chunk) == syit::JitterInsertResult::Inserted,
           "first chunk accepted");
    expect(buffer.insert(chunk) == syit::JitterInsertResult::Duplicate,
           "duplicate chunk does not overwrite buffered frames");
    expect(buffer.insert(pcm_chunk(1U, 2U, 8LL, 2U)) == syit::JitterInsertResult::TooLate,
           "entirely late chunk dropped");
    expect(buffer.insert(pcm_chunk(1U, 3U, 16LL, 4U)) == syit::JitterInsertResult::OutOfWindow,
           "chunk beyond bounded buffer rejected");

    std::array<std::int16_t, 8> output{};
    const auto result = buffer.read(10LL, 4U, output.data(), output.size());
    expect(result.missing_frames == 0U, "accepted chunk remains intact after rejected arrivals");
}

void test_jitter_buffer_partial_late_and_underrun_silence() {
    syit::PcmJitterBuffer buffer(8U, 2U);
    buffer.reset(2LL);
    const auto late = pcm_chunk(1U, 1U, 0LL, 4U);
    expect(buffer.insert(late) == syit::JitterInsertResult::PartialLate,
           "partially late chunk retains only its future samples");

    std::array<std::int16_t, 8> output{};
    const auto result = buffer.read(2LL, 4U, output.data(), output.size());
    expect(result.missing_frames == 2U, "missing samples are counted as underruns");
    expect(output[0] == 5 && output[1] == 6 && output[2] == 7 && output[3] == 8,
           "future part of partially late chunk is preserved");
    expect(output[4] == 0 && output[5] == 0 && output[6] == 0 && output[7] == 0,
           "underrun frames are filled with silence");
    expect(buffer.missing_frames_total() == 2U, "cumulative missing frame counter increments");

    syit::AudioChunk silence;
    silence.sample_index = 6LL;
    silence.frames = 2U;
    silence.channels = 2U;
    silence.flags = syit::kAudioChunkFlagSilence;
    expect(buffer.insert(silence) == syit::JitterInsertResult::Inserted,
           "explicit silence chunk accepted");
    std::array<std::int16_t, 4> silence_output{1, 1, 1, 1};
    const auto silence_result = buffer.read(6LL, 2U, silence_output.data(), silence_output.size());
    expect(silence_result.missing_frames == 0U, "explicit silence is not an underrun");
    expect(silence_output[0] == 0 && silence_output[1] == 0,
           "explicit silence chunk renders zero-valued PCM");
}

}  // namespace

int main() {
    test_audio_chunk_round_trip();
    test_signed_pcm16_and_reserved_flags();
    test_silence_and_gap_chunks();
    test_invalid_audio_frames_rejected();
    test_jitter_buffer_orders_chunks_by_sample_index();
    test_jitter_buffer_duplicate_late_and_window_rules();
    test_jitter_buffer_partial_late_and_underrun_silence();
    if (failures != 0) {
        std::cerr << failures << " audio assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "C++17 PCM audio codec and jitter buffer: all checks passed\n";
    return EXIT_SUCCESS;
}
