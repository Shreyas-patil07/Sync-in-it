// SPDX-License-Identifier: Apache-2.0
#include "golden_vectors.h"
#include "syit/estimator.h"
#include "syit/timing.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

int failures = 0;

void expect(bool condition, const std::string& message) {
    if (!condition) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}

bool close(double actual, double expected, double tolerance) {
    return std::isfinite(actual) && std::abs(actual - expected) <= tolerance;
}

void test_four_timestamp_vectors() {
    for (const auto& item : golden::kFourTimestampCases) {
        const auto result = syit::calculate_four_timestamp(item.sample);
        expect(result.rtt_ns == item.expected_rtt_ns, std::string(item.name) + ": RTT");
        expect(result.offset_ns == item.expected_offset_ns, std::string(item.name) + ": offset");
    }
}

void test_estimator_vectors() {
    for (const auto& item : golden::kEstimatorCases) {
        const auto result = syit::estimate_clock_offset(
            item.samples, item.now_true_ns, item.receiver_now_ns, item.config);
        expect(close(result.offset_ns, item.expected_offset_ns, 0.01), std::string(item.name) + ": offset estimate");
        expect(result.receiver_skew_ppm.has_value() == item.has_expected_skew, std::string(item.name) + ": skew availability");
        if (item.has_expected_skew && result.receiver_skew_ppm.has_value()) {
            expect(close(*result.receiver_skew_ppm, item.expected_skew_ppm, 1e-8), std::string(item.name) + ": skew estimate");
        }
        expect(close(result.rtt_p50_ms, item.expected_rtt_p50_ms, 1e-9), std::string(item.name) + ": RTT median");
        expect(close(result.residual_rms_ms, item.expected_residual_rms_ms, 1e-9), std::string(item.name) + ": residual RMS");
        expect(result.samples_used == item.expected_samples_used, std::string(item.name) + ": samples used");
        expect(result.samples_seen == item.expected_samples_seen, std::string(item.name) + ": samples seen");
        expect(result.selected_sample_span_ns == item.expected_selected_sample_span_ns,
               std::string(item.name) + ": selected sample span");
    }
}

void test_timeline_vectors() {
    for (const auto& item : golden::kTimelineCases) {
        const auto result = syit::expected_sample_index(
            item.master_time_ns, item.anchor, item.sample_rate_hz);
        expect(result == item.expected_sample_index, std::string(item.name) + ": sample index");
    }
}

void test_dac_clock_vectors() {
    for (const auto& item : golden::kDacClockCases) {
        const syit::DacClockModel clock(
            item.sample_rate_hz, item.rate_error_ppm, item.frame_origin, item.time_origin_ns);
        expect(clock.frame_at(item.local_time_ns) == item.expected_frame_position,
               std::string(item.name) + ": frame_at");
        expect(clock.local_time_at(item.frame_position) == item.expected_local_time_ns,
               std::string(item.name) + ": local_time_at");
    }
}

void test_dac_clock_mapper_regression() {
    constexpr std::int64_t sample_rate_hz = 48'000;
    constexpr long double rate_error_ppm = 65.0L;
    constexpr syit::Nanoseconds base_time_ns = 1'700'000'000'000'000LL;
    syit::DacClockMapper mapper(sample_rate_hz);

    for (std::int64_t index = 0; index <= 120; ++index) {
        const auto elapsed_ns = index * 200'000'000LL;
        const long double ideal_frames =
            static_cast<long double>(elapsed_ns) *
            static_cast<long double>(sample_rate_hz) *
            (1.0L + rate_error_ppm / 1'000'000.0L) / 1'000'000'000.0L;
        const auto frame_position = static_cast<std::int64_t>(std::llround(ideal_frames));
        const std::int64_t noise_ns = (index % 5 - 2) * 10'000LL;
        expect(
            mapper.add_sample(frame_position, base_time_ns + elapsed_ns + noise_ns),
            "DAC mapper accepts increasing timestamp pairs");
    }

    const auto estimate = mapper.estimate();
    expect(estimate.has_value(), "DAC mapper becomes valid after sufficient samples and span");
    if (estimate.has_value()) {
        expect(close(estimate->rate_error_ppm, 65.0, 2.0), "DAC mapper estimates positive hardware rate error");
        expect(estimate->samples_used == 121U, "DAC mapper reports retained sample count");
        expect(estimate->sample_span_ns >= 20'000'000'000LL, "DAC mapper reports observation span");
        expect(estimate->residual_rms_ns < 25'000.0, "DAC mapper reports low residual for a clean clock");
        const auto frame_at_half =
            static_cast<std::int64_t>(std::llround(
                12.5L * static_cast<long double>(sample_rate_hz) *
                (1.0L + rate_error_ppm / 1'000'000.0L)));
        const auto mapped_time = estimate->local_time_ns_at_frame(frame_at_half);
        expect(mapped_time.has_value(), "DAC mapper maps frame positions to local monotonic time");
        if (mapped_time.has_value()) {
            expect(std::abs(*mapped_time - (base_time_ns + 12'500'000'000LL)) < 100'000LL,
                   "DAC frame-to-time map remains accurate at an interior point");
            const auto round_trip = estimate->frame_position_at_local_time_ns(*mapped_time);
            expect(round_trip.has_value(), "DAC mapper maps local time back to frame position");
            if (round_trip.has_value()) {
                expect(std::abs(*round_trip - frame_at_half) <= 1,
                       "DAC time-to-frame map reverses frame-to-time mapping");
            }
        }
    }

    const auto last_frame = static_cast<std::int64_t>(std::llround(
        24.0L * static_cast<long double>(sample_rate_hz) *
        (1.0L + rate_error_ppm / 1'000'000.0L)));
    expect(!mapper.add_sample(last_frame, base_time_ns + 24'200'000'000LL),
           "DAC mapper rejects a non-increasing frame position");
    expect(mapper.rejected_samples() == 1U, "DAC mapper counts rejected timestamp discontinuities");

    mapper.reset();
    expect(!mapper.estimate().has_value(), "DAC mapper reset invalidates the previous fit");
}

void test_dac_clock_mapper_sliding_window() {
    syit::DacClockMapper mapper(48'000, 5'000'000'000LL, 64U, 4U, 1'000'000'000LL);
    for (std::int64_t index = 0; index <= 100; ++index) {
        const auto time_ns = 1'000'000'000LL + index * 200'000'000LL;
        const auto frame_position = index * 9'600LL;
        expect(mapper.add_sample(frame_position, time_ns), "sliding DAC mapper accepts monotonic samples");
    }
    const auto estimate = mapper.estimate();
    expect(estimate.has_value(), "sliding DAC mapper retains a valid recent window");
    if (estimate.has_value()) {
        expect(estimate->sample_span_ns <= 5'000'000'000LL, "DAC regression does not exceed its configured time window");
        expect(estimate->samples_used <= 26U, "DAC regression prunes samples outside its time window");
        expect(close(estimate->rate_error_ppm, 0.0, 0.01), "sliding DAC regression preserves nominal sample rate");
    }
}

void test_invalid_inputs() {
    bool threw = false;
    try {
        (void)syit::estimate_clock_offset({}, 0, 0);
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    expect(threw, "empty estimator input rejected");

    threw = false;
    try {
        (void)syit::estimate_clock_offset(
            golden::kEstimatorCases.front().samples,
            golden::kEstimatorCases.front().now_true_ns,
            golden::kEstimatorCases.front().receiver_now_ns,
            syit::EstimatorConfig{60'000'000'000LL, 1.1, 8});
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    expect(threw, "invalid estimator fraction rejected");

    threw = false;
    try {
        (void)syit::expected_sample_index(0, {}, 0);
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    expect(threw, "invalid sample rate rejected");

    threw = false;
    try {
        (void)syit::DacClockModel(48'000, -1'000'000.0);
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    expect(threw, "non-positive DAC clock rate rejected");

    threw = false;
    try {
        (void)syit::DriftController(5, 5);
    } catch (const std::invalid_argument&) {
        threw = true;
    }
    expect(threw, "invalid drift thresholds rejected");
}

void test_drift_controller_vectors() {
    const syit::DriftController controller;
    for (const auto& item : golden::kDriftCases) {
        const auto result = controller.decide(item.error_frames);
        expect(result.action == item.expected_action, "drift action at " + std::to_string(item.error_frames));
        expect(result.error_frames == item.expected_error_frames, "drift error at " + std::to_string(item.error_frames));
    }
}

}  // namespace

int main() {
    test_four_timestamp_vectors();
    test_estimator_vectors();
    test_timeline_vectors();
    test_dac_clock_vectors();
    test_dac_clock_mapper_regression();
    test_dac_clock_mapper_sliding_window();
    test_drift_controller_vectors();
    test_invalid_inputs();
    if (failures != 0) {
        std::cerr << failures << " C++ golden-vector assertion(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "C++17 core: all shared M0 golden vectors passed\n";
    return EXIT_SUCCESS;
}
