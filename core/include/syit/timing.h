// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "syit/types.h"

#include <cstdint>
#include <optional>
#include <vector>

namespace syit {

inline constexpr std::int64_t kSampleRateHz = 48'000;

struct TimelineAnchor {
    Nanoseconds master_time_ns{};
    SampleIndex sample_index{};
    bool playing{true};
};

SampleIndex expected_sample_index(
    Nanoseconds master_time_ns,
    const TimelineAnchor& anchor,
    std::int64_t sample_rate_hz = kSampleRateHz);

class DacClockModel {
public:
    explicit DacClockModel(
        std::int64_t sample_rate_hz = kSampleRateHz,
        double rate_error_ppm = 0.0,
        std::int64_t frame_origin = 0,
        Nanoseconds time_origin_ns = 0);

    std::int64_t frame_at(Nanoseconds local_time_ns) const;
    Nanoseconds local_time_at(std::int64_t frame_position) const;

private:
    std::int64_t sample_rate_hz_;
    double rate_error_ppm_;
    std::int64_t frame_origin_;
    Nanoseconds time_origin_ns_;
};

struct DacClockSample {
    std::int64_t frame_position{};
    Nanoseconds timestamp_ns{};
};

struct DacClockEstimate {
    double nanoseconds_per_frame{};
    double rate_error_ppm{};
    double residual_rms_ns{};
    std::size_t samples_used{};
    Nanoseconds sample_span_ns{};
    std::int64_t frame_origin{};
    Nanoseconds time_origin_ns{};

    std::optional<Nanoseconds> local_time_ns_at_frame(std::int64_t frame_position) const;
    std::optional<std::int64_t> frame_position_at_local_time_ns(Nanoseconds local_time_ns) const;
};

/** Fits the output frame clock to monotonic presentation timestamps. */
class DacClockMapper {
public:
    explicit DacClockMapper(
        std::int64_t sample_rate_hz = kSampleRateHz,
        Nanoseconds window_ns = 30'000'000'000LL,
        std::size_t maximum_samples = 256U,
        std::size_t minimum_samples = 8U,
        Nanoseconds minimum_span_ns = 2'000'000'000LL,
        double maximum_rate_error_ppm = 5'000.0);

    bool add_sample(std::int64_t frame_position, Nanoseconds timestamp_ns);
    std::optional<DacClockEstimate> estimate() const;
    std::size_t samples_seen() const noexcept;
    Nanoseconds sample_span_ns() const noexcept;
    void reset();
    std::uint64_t rejected_samples() const noexcept;

private:
    std::int64_t sample_rate_hz_;
    Nanoseconds window_ns_;
    std::size_t maximum_samples_;
    std::size_t minimum_samples_;
    Nanoseconds minimum_span_ns_;
    double maximum_rate_error_ppm_;
    std::vector<DacClockSample> samples_;
    std::uint64_t rejected_samples_{};
};

class DriftController {
public:
    explicit DriftController(std::int64_t dead_band_frames = 5, std::int64_t hard_resync_frames = 240);
    DriftDecision decide(std::int64_t expected_minus_actual_frames) const;

private:
    std::int64_t dead_band_frames_;
    std::int64_t hard_resync_frames_;
};

}  // namespace syit
