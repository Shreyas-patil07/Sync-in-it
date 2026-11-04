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

std::optional<Nanoseconds> DacClockEstimate::local_time_ns_at_frame(
    std::int64_t frame_position) const {
    if (!std::isfinite(nanoseconds_per_frame) || nanoseconds_per_frame <= 0.0) {
        return std::nullopt;
    }
    const long double delta_frames =
        static_cast<long double>(frame_position) - static_cast<long double>(frame_origin);
    const long double mapped =
        static_cast<long double>(time_origin_ns) +
        delta_frames * static_cast<long double>(nanoseconds_per_frame);
    if (!std::isfinite(mapped) ||
        mapped < static_cast<long double>(std::numeric_limits<Nanoseconds>::min()) ||
        mapped > static_cast<long double>(std::numeric_limits<Nanoseconds>::max())) {
        return std::nullopt;
    }
    return static_cast<Nanoseconds>(std::llround(mapped));
}

std::optional<std::int64_t> DacClockEstimate::frame_position_at_local_time_ns(
    Nanoseconds local_time_ns) const {
    if (!std::isfinite(nanoseconds_per_frame) || nanoseconds_per_frame <= 0.0) {
        return std::nullopt;
    }
    const long double delta_ns =
        static_cast<long double>(local_time_ns) - static_cast<long double>(time_origin_ns);
    const long double mapped =
        static_cast<long double>(frame_origin) +
        delta_ns / static_cast<long double>(nanoseconds_per_frame);
    if (!std::isfinite(mapped) ||
        mapped < static_cast<long double>(std::numeric_limits<std::int64_t>::min()) ||
        mapped > static_cast<long double>(std::numeric_limits<std::int64_t>::max())) {
        return std::nullopt;
    }
    return static_cast<std::int64_t>(std::llround(mapped));
}

DacClockMapper::DacClockMapper(
    std::int64_t sample_rate_hz,
    Nanoseconds window_ns,
    std::size_t maximum_samples,
    std::size_t minimum_samples,
    Nanoseconds minimum_span_ns,
    double maximum_rate_error_ppm)
    : sample_rate_hz_(sample_rate_hz),
      window_ns_(window_ns),
      maximum_samples_(maximum_samples),
      minimum_samples_(minimum_samples),
      minimum_span_ns_(minimum_span_ns),
      maximum_rate_error_ppm_(maximum_rate_error_ppm) {
    if (sample_rate_hz_ <= 0 || window_ns_ <= 0 ||
        minimum_samples_ < 2U || maximum_samples_ < minimum_samples_ ||
        minimum_span_ns_ <= 0 || minimum_span_ns_ > window_ns_ ||
        !std::isfinite(maximum_rate_error_ppm_) || maximum_rate_error_ppm_ <= 0.0) {
        throw std::invalid_argument("invalid DAC clock mapper configuration");
    }
    samples_.reserve(maximum_samples_);
}

bool DacClockMapper::add_sample(
    std::int64_t frame_position,
    Nanoseconds timestamp_ns) {
    if (frame_position < 0 || timestamp_ns <= 0) {
        ++rejected_samples_;
        return false;
    }
    if (!samples_.empty()) {
        const auto& previous = samples_.back();
        if (frame_position <= previous.frame_position ||
            timestamp_ns <= previous.timestamp_ns) {
            ++rejected_samples_;
            return false;
        }
        const long double frame_delta =
            static_cast<long double>(frame_position) -
            static_cast<long double>(previous.frame_position);
        const long double time_delta =
            static_cast<long double>(timestamp_ns) -
            static_cast<long double>(previous.timestamp_ns);
        const long double observed_rate_hz = frame_delta * 1'000'000'000.0L / time_delta;
        const long double observed_error_ppm =
            (observed_rate_hz / static_cast<long double>(sample_rate_hz_) - 1.0L) * 1'000'000.0L;
        constexpr long double kMaximumIntervalDiscontinuityPpm = 25'000.0L;
        if (!std::isfinite(observed_error_ppm) ||
            std::abs(observed_error_ppm) > kMaximumIntervalDiscontinuityPpm) {
            ++rejected_samples_;
            return false;
        }
    }

    samples_.push_back({frame_position, timestamp_ns});
    while (!samples_.empty() &&
           static_cast<long double>(timestamp_ns) -
               static_cast<long double>(samples_.front().timestamp_ns) >
                   static_cast<long double>(window_ns_)) {
        samples_.erase(samples_.begin());
    }
    while (samples_.size() > maximum_samples_) {
        samples_.erase(samples_.begin());
    }
    return true;
}

std::optional<DacClockEstimate> DacClockMapper::estimate() const {
    if (samples_.size() < minimum_samples_) return std::nullopt;

    const auto& first = samples_.front();
    const auto& last = samples_.back();
    const Nanoseconds span_ns = last.timestamp_ns - first.timestamp_ns;
    if (span_ns < minimum_span_ns_) return std::nullopt;

    const long double count = static_cast<long double>(samples_.size());
    long double sum_x = 0.0L;
    long double sum_y = 0.0L;
    long double sum_xx = 0.0L;
    long double sum_xy = 0.0L;

    for (const auto& sample : samples_) {
        const long double x =
            static_cast<long double>(sample.frame_position) -
            static_cast<long double>(first.frame_position);
        const long double y =
            static_cast<long double>(sample.timestamp_ns) -
            static_cast<long double>(first.timestamp_ns);
        sum_x += x;
        sum_y += y;
        sum_xx += x * x;
        sum_xy += x * y;
    }

    const long double denominator = count * sum_xx - sum_x * sum_x;
    if (!std::isfinite(denominator) || denominator <= 0.0L) return std::nullopt;

    const long double slope_ns_per_frame =
        (count * sum_xy - sum_x * sum_y) / denominator;
    if (!std::isfinite(slope_ns_per_frame) || slope_ns_per_frame <= 0.0L) {
        return std::nullopt;
    }

    const long double intercept_ns = (sum_y - slope_ns_per_frame * sum_x) / count;
    const long double actual_rate_hz = 1'000'000'000.0L / slope_ns_per_frame;
    const long double rate_error_ppm =
        (actual_rate_hz / static_cast<long double>(sample_rate_hz_) - 1.0L) * 1'000'000.0L;
    if (!std::isfinite(rate_error_ppm) ||
        std::abs(rate_error_ppm) > static_cast<long double>(maximum_rate_error_ppm_)) {
        return std::nullopt;
    }

    long double residual_sum_squares = 0.0L;
    for (const auto& sample : samples_) {
        const long double x =
            static_cast<long double>(sample.frame_position) -
            static_cast<long double>(first.frame_position);
        const long double y =
            static_cast<long double>(sample.timestamp_ns) -
            static_cast<long double>(first.timestamp_ns);
        const long double residual = y - (intercept_ns + slope_ns_per_frame * x);
        residual_sum_squares += residual * residual;
    }
    const long double residual_rms_ns =
        std::sqrt(residual_sum_squares / count);
    const long double time_origin =
        static_cast<long double>(first.timestamp_ns) + intercept_ns;
    if (!std::isfinite(time_origin) ||
        time_origin < static_cast<long double>(std::numeric_limits<Nanoseconds>::min()) ||
        time_origin > static_cast<long double>(std::numeric_limits<Nanoseconds>::max())) {
        return std::nullopt;
    }

    return DacClockEstimate{
        static_cast<double>(slope_ns_per_frame),
        static_cast<double>(rate_error_ppm),
        static_cast<double>(residual_rms_ns),
        samples_.size(),
        span_ns,
        first.frame_position,
        static_cast<Nanoseconds>(std::llround(time_origin))};
}

std::size_t DacClockMapper::samples_seen() const noexcept {
    return samples_.size();
}

Nanoseconds DacClockMapper::sample_span_ns() const noexcept {
    if (samples_.size() < 2U) return 0;
    return samples_.back().timestamp_ns - samples_.front().timestamp_ns;
}

void DacClockMapper::reset() {
    samples_.clear();
}

std::uint64_t DacClockMapper::rejected_samples() const noexcept {
    return rejected_samples_;
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
