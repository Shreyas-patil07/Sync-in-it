// SPDX-License-Identifier: Apache-2.0
#include "syit/timing.h"

#include <cmath>
#include <limits>
#include <stdexcept>

namespace syit {
namespace {

double round_ties_to_even(double value) {
    if (!std::isfinite(value)) {
        throw std::invalid_argument("cannot round a non-finite value");
    }
    const double lower = std::floor(value);
    const double fraction = value - lower;
    double rounded = lower;
    if (fraction > 0.5) {
        rounded = lower + 1.0;
    } else if (fraction == 0.5) {
        const double parity = std::fmod(std::abs(lower), 2.0);
        rounded = parity == 0.0 ? lower : lower + 1.0;
    }
    if (rounded < static_cast<double>(std::numeric_limits<std::int64_t>::min()) ||
        rounded > static_cast<double>(std::numeric_limits<std::int64_t>::max())) {
        throw std::overflow_error("rounded result is outside int64 range");
    }
    return rounded;
}

double effective_rate(std::int64_t sample_rate_hz, double rate_error_ppm) {
    if (sample_rate_hz <= 0 || !std::isfinite(rate_error_ppm) || rate_error_ppm <= -1'000'000.0) {
        throw std::invalid_argument("invalid DAC clock rate");
    }
    return static_cast<double>(sample_rate_hz) * (1.0 + rate_error_ppm / 1'000'000.0);
}

std::int64_t checked_add(std::int64_t left, std::int64_t right) {
    if ((right > 0 && left > std::numeric_limits<std::int64_t>::max() - right) ||
        (right < 0 && left < std::numeric_limits<std::int64_t>::min() - right)) {
        throw std::overflow_error("int64 addition overflow");
    }
    return left + right;
}

std::int64_t checked_subtract(std::int64_t left, std::int64_t right) {
    if ((right < 0 && left > std::numeric_limits<std::int64_t>::max() + right) ||
        (right > 0 && left < std::numeric_limits<std::int64_t>::min() + right)) {
        throw std::overflow_error("int64 subtraction overflow");
    }
    return left - right;
}

std::int64_t checked_round_to_i64(double value) {
    const double rounded = round_ties_to_even(value);
    if (rounded < static_cast<double>(std::numeric_limits<std::int64_t>::min()) ||
        rounded >= -static_cast<double>(std::numeric_limits<std::int64_t>::min())) {
        throw std::overflow_error("rounded result is outside int64 range");
    }
    return static_cast<std::int64_t>(rounded);
}

}  // namespace

SampleIndex expected_sample_index(
    Nanoseconds master_time_ns,
    const TimelineAnchor& anchor,
    std::int64_t sample_rate_hz) {
    if (sample_rate_hz <= 0) {
        throw std::invalid_argument("sample_rate_hz must be positive");
    }
    if (!anchor.playing) {
        return anchor.sample_index;
    }
    const double elapsed_ns = static_cast<double>(checked_subtract(master_time_ns, anchor.master_time_ns));
    const double frames = elapsed_ns * static_cast<double>(sample_rate_hz) / 1'000'000'000.0;
    return checked_add(anchor.sample_index, checked_round_to_i64(frames));
}

DacClockModel::DacClockModel(
    std::int64_t sample_rate_hz,
    double rate_error_ppm,
    std::int64_t frame_origin,
    Nanoseconds time_origin_ns)
    : sample_rate_hz_(sample_rate_hz),
      rate_error_ppm_(rate_error_ppm),
      frame_origin_(frame_origin),
      time_origin_ns_(time_origin_ns) {
    (void)effective_rate(sample_rate_hz_, rate_error_ppm_);
}

std::int64_t DacClockModel::frame_at(Nanoseconds local_time_ns) const {
    const double elapsed_ns = static_cast<double>(checked_subtract(local_time_ns, time_origin_ns_));
    const double frames = elapsed_ns * effective_rate(sample_rate_hz_, rate_error_ppm_) / 1'000'000'000.0;
    return checked_add(frame_origin_, checked_round_to_i64(frames));
}

Nanoseconds DacClockModel::local_time_at(std::int64_t frame_position) const {
    const double elapsed_frames = static_cast<double>(checked_subtract(frame_position, frame_origin_));
    const double elapsed_ns = elapsed_frames * 1'000'000'000.0 / effective_rate(sample_rate_hz_, rate_error_ppm_);
    const auto rounded_ns = checked_round_to_i64(elapsed_ns);
    if ((rounded_ns > 0 && time_origin_ns_ > std::numeric_limits<Nanoseconds>::max() - rounded_ns) ||
        (rounded_ns < 0 && time_origin_ns_ < std::numeric_limits<Nanoseconds>::min() - rounded_ns)) {
        throw std::overflow_error("local timestamp is outside int64 range");
    }
    return checked_add(time_origin_ns_, rounded_ns);
}

DriftController::DriftController(std::int64_t dead_band_frames, std::int64_t hard_resync_frames)
    : dead_band_frames_(dead_band_frames), hard_resync_frames_(hard_resync_frames) {
    if (dead_band_frames_ < 0 || hard_resync_frames_ <= dead_band_frames_) {
        throw std::invalid_argument("invalid drift thresholds");
    }
}

DriftDecision DriftController::decide(std::int64_t error) const {
    const auto magnitude = error == std::numeric_limits<std::int64_t>::min()
        ? std::numeric_limits<std::int64_t>::max()
        : std::abs(error);
    DriftAction action = DriftAction::None;
    if (magnitude <= dead_band_frames_) {
        action = DriftAction::None;
    } else if (magnitude > hard_resync_frames_) {
        action = DriftAction::HardResync;
    } else if (error > 0) {
        action = DriftAction::DropOneFrame;
    } else {
        action = DriftAction::DuplicateOneFrame;
    }
    return {action, error};
}

}  // namespace syit
