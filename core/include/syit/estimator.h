// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "syit/types.h"

namespace syit {

FourTimestampResult calculate_four_timestamp(const SyncSample& sample);
EstimatorResult estimate_clock_offset(
    const std::vector<SyncSample>& samples,
    Nanoseconds now_true_ns,
    Nanoseconds receiver_now_ns,
    const EstimatorConfig& config = {});

}  // namespace syit
