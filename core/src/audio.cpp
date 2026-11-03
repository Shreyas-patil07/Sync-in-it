// SPDX-License-Identifier: Apache-2.0
#include "syit/audio.h"

#include <algorithm>
#include <limits>
#include <stdexcept>

namespace syit {
namespace {

void append_u16(std::vector<std::uint8_t>& out, std::uint16_t value) {
    out.push_back(static_cast<std::uint8_t>(value & 0xffU));
    out.push_back(static_cast<std::uint8_t>((value >> 8U) & 0xffU));
}
void append_u32(std::vector<std::uint8_t>& out, std::uint32_t value) {
    for (unsigned int i = 0U; i < 4U; ++i) {
        out.push_back(static_cast<std::uint8_t>((value >> (8U * i)) & 0xffU));
    }
}
void append_i64(std::vector<std::uint8_t>& out, std::int64_t value) {
    const auto raw = static_cast<std::uint64_t>(value);
    for (unsigned int i = 0U; i < 8U; ++i) {
        out.push_back(static_cast<std::uint8_t>((raw >> (8U * i)) & 0xffU));
    }
}
std::uint16_t read_u16(const std::vector<std::uint8_t>& data, std::size_t offset) {
    return static_cast<std::uint16_t>(data[offset]) |
        static_cast<std::uint16_t>(static_cast<std::uint16_t>(data[offset + 1U]) << 8U);
}
std::uint32_t read_u32(const std::vector<std::uint8_t>& data, std::size_t offset) {
    std::uint32_t value = 0U;
    for (unsigned int i = 0U; i < 4U; ++i) {
        value |= static_cast<std::uint32_t>(data[offset + i]) << (8U * i);
    }
    return value;
}
std::int64_t read_i64(const std::vector<std::uint8_t>& data, std::size_t offset) {
    std::uint64_t value = 0U;
    for (unsigned int i = 0U; i < 8U; ++i) {
        value |= static_cast<std::uint64_t>(data[offset + i]) << (8U * i);
    }
    if (value <= static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) {
        return static_cast<std::int64_t>(value);
    }
    return -1LL - static_cast<std::int64_t>(~value);
}

void validate_chunk(const AudioChunk& chunk) {
    if (chunk.sample_index < 0) throw std::invalid_argument("audio sample index must be non-negative");
    if (chunk.frames == 0U) throw std::invalid_argument("audio chunk must contain at least one frame");
    if (chunk.codec != kAudioCodecPcm16) throw std::invalid_argument("unsupported audio codec");
    if (chunk.channels == 0U || chunk.channels > 2U) {
        throw std::invalid_argument("PCM16 channel count must be one or two");
    }
    // Reserved chunk flag bits are ignored for forward-compatible minor extensions.
    const bool silence = (chunk.flags & kAudioChunkFlagSilence) != 0U;
    const auto expected = static_cast<std::size_t>(chunk.frames) *
        static_cast<std::size_t>(chunk.channels) * 2U;
    if (silence) {
        if (!chunk.payload.empty()) {
            throw std::invalid_argument("silence chunk payload must be empty");
        }
    } else if (chunk.payload.size() != expected) {
        throw std::invalid_argument("PCM16 payload length does not match frame and channel count");
    }
    if (chunk.payload.size() + kAudioChunkHeaderSize + 2U > kMaxFrameSize) {
        throw std::invalid_argument("audio chunk exceeds maximum TCP frame size");
    }
    if (chunk.sample_index > std::numeric_limits<SampleIndex>::max() -
            static_cast<SampleIndex>(chunk.frames)) {
        throw std::invalid_argument("audio sample index range overflows");
    }
}

}  // namespace

Frame encode_audio_chunk(const AudioChunk& chunk, std::uint8_t outer_flags) {
    if (outer_flags != 0U) throw std::invalid_argument("audio outer flags must be zero");
    validate_chunk(chunk);

    Frame frame;
    frame.message_type = kAudioChunkMessageType;
    frame.flags = outer_flags;
    frame.payload.reserve(kAudioChunkHeaderSize + chunk.payload.size());
    append_u32(frame.payload, chunk.session_id);
    append_u32(frame.payload, chunk.sequence);
    append_i64(frame.payload, chunk.sample_index);
    append_u16(frame.payload, chunk.frames);
    frame.payload.push_back(chunk.codec);
    frame.payload.push_back(chunk.channels);
    frame.payload.push_back(chunk.flags);
    frame.payload.insert(frame.payload.end(), chunk.payload.begin(), chunk.payload.end());
    return frame;
}

AudioChunk decode_audio_chunk(const Frame& frame) {
    if (frame.message_type != kAudioChunkMessageType) {
        throw std::invalid_argument("expected AUDIO_CHUNK frame");
    }
    if (frame.flags != 0U) throw std::invalid_argument("audio outer flags must be zero");
    if (frame.payload.size() < kAudioChunkHeaderSize) {
        throw std::invalid_argument("audio chunk payload is shorter than its fixed header");
    }

    AudioChunk chunk;
    chunk.session_id = read_u32(frame.payload, 0U);
    chunk.sequence = read_u32(frame.payload, 4U);
    chunk.sample_index = read_i64(frame.payload, 8U);
    chunk.frames = read_u16(frame.payload, 16U);
    chunk.codec = frame.payload[18U];
    chunk.channels = frame.payload[19U];
    chunk.flags = frame.payload[20U];
    chunk.payload.assign(
        frame.payload.begin() + static_cast<std::ptrdiff_t>(kAudioChunkHeaderSize),
        frame.payload.end());
    validate_chunk(chunk);
    return chunk;
}

PcmJitterBuffer::PcmJitterBuffer(std::size_t capacity_frames, std::uint8_t channels)
    : capacity_frames_(capacity_frames),
      channels_(channels),
      slots_(capacity_frames),
      samples_(capacity_frames * static_cast<std::size_t>(channels), 0) {
    if (capacity_frames == 0U) throw std::invalid_argument("jitter buffer capacity must be positive");
    if (channels == 0U || channels > 2U) throw std::invalid_argument("jitter buffer channels must be one or two");
    if (capacity_frames > static_cast<std::size_t>(std::numeric_limits<SampleIndex>::max())) {
        throw std::invalid_argument("jitter buffer capacity exceeds sample index range");
    }
}

void PcmJitterBuffer::reset(SampleIndex first_sample_index) {
    if (first_sample_index < 0) throw std::invalid_argument("initial sample index must be non-negative");
    if (first_sample_index > std::numeric_limits<SampleIndex>::max() -
            static_cast<SampleIndex>(capacity_frames_)) {
        throw std::invalid_argument("initial sample index leaves insufficient ring-buffer headroom");
    }
    std::lock_guard<std::mutex> lock(mutex_);
    std::fill(slots_.begin(), slots_.end(), Slot{});
    std::fill(samples_.begin(), samples_.end(), 0);
    next_sample_index_ = first_sample_index;
    missing_frames_total_ = 0U;
    initialized_ = true;
}

void PcmJitterBuffer::seek(SampleIndex first_sample_index) {
    if (first_sample_index < 0) {
        throw std::invalid_argument("seek sample index must be non-negative");
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) throw std::logic_error("jitter buffer must be initialized before seeking");
    if (first_sample_index < next_sample_index_) {
        throw std::invalid_argument("jitter buffer seek cannot move behind the read cursor");
    }

    for (auto& slot : slots_) {
        if (slot.occupied && slot.sample_index < first_sample_index) {
            slot.occupied = false;
        }
    }
    next_sample_index_ = first_sample_index;
}

std::size_t PcmJitterBuffer::slot_for(SampleIndex sample_index) const noexcept {
    return static_cast<std::size_t>(
        static_cast<std::uint64_t>(sample_index) % static_cast<std::uint64_t>(capacity_frames_));
}

std::int16_t PcmJitterBuffer::decode_pcm16(const std::uint8_t* bytes) noexcept {
    const auto raw = static_cast<std::uint16_t>(bytes[0]) |
        static_cast<std::uint16_t>(static_cast<std::uint16_t>(bytes[1]) << 8U);
    const auto signed_value = raw <= static_cast<std::uint16_t>(
        std::numeric_limits<std::int16_t>::max())
        ? static_cast<std::int32_t>(raw)
        : static_cast<std::int32_t>(raw) - 65'536;
    return static_cast<std::int16_t>(signed_value);
}

JitterInsertResult PcmJitterBuffer::insert(const AudioChunk& chunk) {
    try { validate_chunk(chunk); } catch (const std::invalid_argument&) {
        return JitterInsertResult::InvalidChunk;
    }
    if (chunk.channels != channels_) return JitterInsertResult::InvalidChunk;

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) return JitterInsertResult::InvalidChunk;
    if (chunk.sample_index < next_sample_index_) {
        const auto end = chunk.sample_index + static_cast<SampleIndex>(chunk.frames);
        if (end <= next_sample_index_) return JitterInsertResult::TooLate;
    }
    const auto end_index = chunk.sample_index + static_cast<SampleIndex>(chunk.frames);
    if (end_index - next_sample_index_ > static_cast<SampleIndex>(capacity_frames_)) {
        return JitterInsertResult::OutOfWindow;
    }

    const auto first_frame = static_cast<std::size_t>(
        std::max<SampleIndex>(0, next_sample_index_ - chunk.sample_index));
    bool inserted = false;
    bool duplicate = false;
    const bool silence = (chunk.flags & kAudioChunkFlagSilence) != 0U;

    for (std::size_t frame = first_frame; frame < static_cast<std::size_t>(chunk.frames); ++frame) {
        const auto sample_index = chunk.sample_index + static_cast<SampleIndex>(frame);
        const auto slot = slot_for(sample_index);
        if (slots_[slot].occupied && slots_[slot].sample_index == sample_index) {
            duplicate = true;
            continue;
        }

        slots_[slot].sample_index = sample_index;
        slots_[slot].occupied = true;
        for (std::size_t channel = 0U; channel < static_cast<std::size_t>(channels_); ++channel) {
            const auto destination = slot * static_cast<std::size_t>(channels_) + channel;
            if (silence) {
                samples_[destination] = 0;
            } else {
                const auto source = (frame * static_cast<std::size_t>(channels_) + channel) * 2U;
                samples_[destination] = decode_pcm16(chunk.payload.data() + source);
            }
        }
        inserted = true;
    }

    if (inserted && chunk.sample_index < next_sample_index_) return JitterInsertResult::PartialLate;
    if (inserted) return JitterInsertResult::Inserted;
    if (duplicate) return JitterInsertResult::Duplicate;
    return JitterInsertResult::TooLate;
}

JitterReadResult PcmJitterBuffer::read(
    SampleIndex first_sample_index,
    std::size_t frames,
    std::int16_t* output,
    std::size_t output_samples) {
    if (frames == 0U) return {};
    if (output == nullptr) throw std::invalid_argument("jitter buffer output must not be null");
    if (frames > std::numeric_limits<std::size_t>::max() / static_cast<std::size_t>(channels_) ||
        output_samples < frames * static_cast<std::size_t>(channels_)) {
        throw std::invalid_argument("jitter buffer output capacity is too small");
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) throw std::logic_error("jitter buffer must be reset before reading");
    if (first_sample_index != next_sample_index_) {
        throw std::invalid_argument("jitter buffer read must start at the next sample index");
    }
    if (first_sample_index > std::numeric_limits<SampleIndex>::max() -
            static_cast<SampleIndex>(frames)) {
        throw std::invalid_argument("jitter buffer read range overflows sample index");
    }

    std::size_t missing = 0U;
    for (std::size_t frame = 0U; frame < frames; ++frame) {
        const auto sample_index = first_sample_index + static_cast<SampleIndex>(frame);
        const auto slot = slot_for(sample_index);
        const auto output_offset = frame * static_cast<std::size_t>(channels_);
        if (slots_[slot].occupied && slots_[slot].sample_index == sample_index) {
            for (std::size_t channel = 0U; channel < static_cast<std::size_t>(channels_); ++channel) {
                output[output_offset + channel] =
                    samples_[slot * static_cast<std::size_t>(channels_) + channel];
            }
            slots_[slot].occupied = false;
        } else {
            for (std::size_t channel = 0U; channel < static_cast<std::size_t>(channels_); ++channel) {
                output[output_offset + channel] = 0;
            }
            ++missing;
        }
    }
    next_sample_index_ += static_cast<SampleIndex>(frames);
    missing_frames_total_ += static_cast<std::uint64_t>(missing);
    return {frames, missing};
}

std::size_t PcmJitterBuffer::contiguous_buffered_frames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) return 0U;
    std::size_t count = 0U;
    while (count < capacity_frames_) {
        const auto index = next_sample_index_ + static_cast<SampleIndex>(count);
        const auto slot = slot_for(index);
        if (!slots_[slot].occupied || slots_[slot].sample_index != index) break;
        ++count;
    }
    return count;
}

SampleIndex PcmJitterBuffer::next_sample_index() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return next_sample_index_;
}

std::uint64_t PcmJitterBuffer::missing_frames_total() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return missing_frames_total_;
}

}  // namespace syit
