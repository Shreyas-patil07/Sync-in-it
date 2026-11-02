// SPDX-License-Identifier: Apache-2.0
#include "syit/estimator.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

namespace syit {
namespace {

Nanoseconds checked_add(Nanoseconds left, Nanoseconds right) {
    if ((right > 0 && left > std::numeric_limits<Nanoseconds>::max() - right) ||
        (right < 0 && left < std::numeric_limits<Nanoseconds>::min() - right)) {
        throw std::overflow_error("nanosecond addition overflow");
    }
    return left + right;
}

Nanoseconds checked_subtract(Nanoseconds left, Nanoseconds right) {
    if ((right < 0 && left > std::numeric_limits<Nanoseconds>::max() + right) ||
        (right > 0 && left < std::numeric_limits<Nanoseconds>::min() + right)) {
        throw std::overflow_error("nanosecond subtraction overflow");
    }
    return left - right;
}

Nanoseconds floor_half(Nanoseconds value) {
    const Nanoseconds quotient = value / 2;
    return value < 0 && value % 2 != 0 ? quotient - 1 : quotient;
}

Nanoseconds midpoint_floor(Nanoseconds left, Nanoseconds right) {
    const Nanoseconds whole = left / 2 + right / 2;
    const Nanoseconds remainder = left % 2 + right % 2;
    const Nanoseconds correction = remainder >= 0
        ? remainder / 2
        : -((-remainder + 1) / 2);
    return checked_add(whole, correction);
}

struct SelectedSample {
    const SyncSample* sample{};
    Nanoseconds rtt_ns{};
    Nanoseconds offset_ns{};
    Nanoseconds midpoint_ns{};
};

}  // namespace

FourTimestampResult calculate_four_timestamp(const SyncSample& sample) {
    const auto receiver_elapsed = checked_subtract(sample.receiver_receive_ns, sample.receiver_send_ns);
    const auto master_elapsed = checked_subtract(sample.master_send_ns, sample.master_receive_ns);
    const auto rtt = checked_subtract(receiver_elapsed, master_elapsed);

    const auto outbound = checked_subtract(sample.master_receive_ns, sample.receiver_send_ns);
    const auto inbound = checked_subtract(sample.master_send_ns, sample.receiver_receive_ns);
    const auto offset = floor_half(checked_add(outbound, inbound));
    return {rtt, offset};
}

EstimatorResult estimate_clock_offset(
    const std::vector<SyncSample>& samples,
    Nanoseconds now_true_ns,
    Nanoseconds receiver_now_ns,
    const EstimatorConfig& config) {
    if (samples.empty()) {
        throw std::invalid_argument("at least one sync sample is required");
    }
    if (config.window_ns <= 0) {
        throw std::invalid_argument("window_ns must be positive");
    }
    if (!std::isfinite(config.low_rtt_fraction) ||
        config.low_rtt_fraction <= 0.0 || config.low_rtt_fraction > 1.0) {
        throw std::invalid_argument("low_rtt_fraction must be in (0, 1]");
    }
    if (config.minimum_skew_samples < 2) {
        throw std::invalid_argument("minimum_skew_samples must be at least 2");
    }

    std::vector<SelectedSample> recent;
    recent.reserve(samples.size());
    for (const auto& sample : samples) {
        const long double age = static_cast<long double>(now_true_ns) - sample.received_at_true_ns;
        if (age < 0.0L || age > static_cast<long double>(config.window_ns)) {
            continue;
        }
        const auto values = calculate_four_timestamp(sample);
        if (values.rtt_ns < 0) {
            continue;
        }
        recent.push_back({
            &sample,
            values.rtt_ns,
            values.offset_ns,
            midpoint_floor(sample.receiver_send_ns, sample.receiver_receive_ns),
        });
    }
    if (recent.empty()) {
        throw std::invalid_argument("no valid samples inside the estimator window");
    }

    std::stable_sort(recent.begin(), recent.end(), [](const auto& left, const auto& right) {
        return left.rtt_ns < right.rtt_ns;
    });
    const auto selected_count = std::max<std::size_t>(
        1,
        static_cast<std::size_t>(std::ceil(
            static_cast<double>(recent.size()) * config.low_rtt_fraction)));
    const std::size_t samples_seen = recent.size();
    recent.resize(std::min(selected_count, recent.size()));

    double x_mean = 0.0;
    double y_mean = 0.0;
    for (const auto& item : recent) {
        x_mean += static_cast<double>(item.midpoint_ns);
        y_mean += static_cast<double>(item.offset_ns);
    }
    x_mean /= static_cast<double>(recent.size());
    y_mean /= static_cast<double>(recent.size());

    double denominator = 0.0;
    double numerator = 0.0;
    for (const auto& item : recent) {
        const double x_delta = static_cast<double>(item.midpoint_ns) - x_mean;
        const double y_delta = static_cast<double>(item.offset_ns) - y_mean;
        denominator += x_delta * x_delta;
        numerator += x_delta * y_delta;
    }

    double slope = 0.0;
    if (recent.size() >= config.minimum_skew_samples && denominator > 0.0) {
        slope = numerator / denominator;
    }

    const double offset_now = y_mean + slope * (static_cast<double>(receiver_now_ns) - x_mean);
    double residual_sum_squares = 0.0;
    std::vector<double> rtts_ms;
    rtts_ms.reserve(recent.size());
    for (const auto& item : recent) {
        const double predicted = y_mean + slope * (static_cast<double>(item.midpoint_ns) - x_mean);
        const double residual = static_cast<double>(item.offset_ns) - predicted;
        residual_sum_squares += residual * residual;
        rtts_ms.push_back(static_cast<double>(item.rtt_ns) / 1'000'000.0);
    }

    std::sort(rtts_ms.begin(), rtts_ms.end());
    const auto middle = rtts_ms.size() / 2;
    const double rtt_median = rtts_ms.size() % 2 == 0
        ? (rtts_ms[middle - 1] + rtts_ms[middle]) * 0.5
        : rtts_ms[middle];

    std::optional<double> skew;
    if (recent.size() >= config.minimum_skew_samples) {
        skew = -slope * 1'000'000.0;
    }

    const auto [minimum_midpoint, maximum_midpoint] = std::minmax_element(
        recent.begin(), recent.end(), [](const auto& left, const auto& right) {
            return left.midpoint_ns < right.midpoint_ns;
        });
    const auto selected_sample_span_ns = checked_subtract(
        maximum_midpoint->midpoint_ns, minimum_midpoint->midpoint_ns);

    return {
        offset_now,
        skew,
        rtt_median,
        std::sqrt(residual_sum_squares / static_cast<double>(recent.size())) / 1'000'000.0,
        recent.size(),
        samples_seen,
        selected_sample_span_ns,
    };
}

}  // namespace syit
