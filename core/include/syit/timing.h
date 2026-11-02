// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "syit/types.h"

#include <cstdint>

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

class DriftController {
public:
    explicit DriftController(std::int64_t dead_band_frames = 5, std::int64_t hard_resync_frames = 240);
    DriftDecision decide(std::int64_t expected_minus_actual_frames) const;

private:
    std::int64_t dead_band_frames_;
    std::int64_t hard_resync_frames_;
};

}  // namespace syit
