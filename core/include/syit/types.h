// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace syit {

using Nanoseconds = std::int64_t;
using SampleIndex = std::int64_t;

struct SyncSample {
    Nanoseconds receiver_send_ns{};
    Nanoseconds master_receive_ns{};
    Nanoseconds master_send_ns{};
    Nanoseconds receiver_receive_ns{};
    Nanoseconds received_at_true_ns{};
};

struct FourTimestampResult {
    Nanoseconds rtt_ns{};
    Nanoseconds offset_ns{};
};

struct EstimatorConfig {
    Nanoseconds window_ns{60'000'000'000LL};
    double low_rtt_fraction{0.25};
    std::size_t minimum_skew_samples{8};
};

struct EstimatorResult {
    double offset_ns{};
    std::optional<double> receiver_skew_ppm;
    double rtt_p50_ms{};
    double residual_rms_ms{};
    std::size_t samples_used{};
    std::size_t samples_seen{};
};

enum class DriftAction {
    None,
    DropOneFrame,
    DuplicateOneFrame,
    HardResync,
};

struct DriftDecision {
    DriftAction action{DriftAction::None};
    std::int64_t error_frames{};
};

}  // namespace syit
