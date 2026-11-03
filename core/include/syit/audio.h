// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "syit/protocol.h"
#include "syit/types.h"

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

namespace syit {

inline constexpr std::uint8_t kAudioChunkMessageType = 0x80U;
inline constexpr std::uint8_t kAudioCodecPcm16 = 0U;
inline constexpr std::uint8_t kAudioChunkFlagGap = 0x01U;
inline constexpr std::uint8_t kAudioChunkFlagSilence = 0x02U;
inline constexpr std::uint8_t kAudioChunkKnownFlags =
    kAudioChunkFlagGap | kAudioChunkFlagSilence;
inline constexpr std::uint8_t kAudioV1Channels = 2U;
inline constexpr std::uint16_t kAudioV1ChunkFrames = 960U;
inline constexpr std::uint32_t kAudioV1SampleRateHz = 48'000U;
inline constexpr std::size_t kAudioChunkHeaderSize = 21U;

struct AudioChunk {
    std::uint32_t session_id{};
    std::uint32_t sequence{};
    SampleIndex sample_index{};
    std::uint16_t frames{};
    std::uint8_t codec{kAudioCodecPcm16};
    std::uint8_t channels{kAudioV1Channels};
    std::uint8_t flags{};
    std::vector<std::uint8_t> payload;
};

Frame encode_audio_chunk(const AudioChunk& chunk, std::uint8_t outer_flags = 0U);
AudioChunk decode_audio_chunk(const Frame& frame);

enum class JitterInsertResult {
    Inserted,
    PartialLate,
    Duplicate,
    TooLate,
    OutOfWindow,
    InvalidChunk,
};

struct JitterReadResult {
    std::size_t frames_read{};
    std::size_t missing_frames{};
};

// Sample-indexed, bounded PCM16 ring buffer. Calls are short and mutex-protected;
// invoke read() from a dedicated AudioTrack writer thread, never a real-time callback.
class PcmJitterBuffer {
public:
    PcmJitterBuffer(std::size_t capacity_frames, std::uint8_t channels = kAudioV1Channels);

    void reset(SampleIndex first_sample_index);
    void seek(SampleIndex first_sample_index);
    JitterInsertResult insert(const AudioChunk& chunk);
    JitterReadResult read(
        SampleIndex first_sample_index,
        std::size_t frames,
        std::int16_t* output,
        std::size_t output_samples);

    std::size_t contiguous_buffered_frames() const;
    std::size_t capacity_frames() const noexcept { return capacity_frames_; }
    std::uint8_t channels() const noexcept { return channels_; }
    SampleIndex next_sample_index() const;
    std::uint64_t missing_frames_total() const;

private:
    struct Slot {
        SampleIndex sample_index{-1};
        bool occupied{false};
    };

    std::size_t slot_for(SampleIndex sample_index) const noexcept;
    static std::int16_t decode_pcm16(const std::uint8_t* bytes) noexcept;

    const std::size_t capacity_frames_;
    const std::uint8_t channels_;
    mutable std::mutex mutex_;
    std::vector<Slot> slots_;
    std::vector<std::int16_t> samples_;
    SampleIndex next_sample_index_{0};
    std::uint64_t missing_frames_total_{0};
    bool initialized_{false};
};

}  // namespace syit
