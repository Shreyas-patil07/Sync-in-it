// SPDX-License-Identifier: Apache-2.0
#include "syit/estimator.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <optional>
#include <stdexcept>
#include <string>
#include <system_error>
#include <vector>

namespace {

constexpr syit::Nanoseconds kEstimatorWindowNs = 60'000'000'000LL;
constexpr syit::Nanoseconds kMinimumSpanNs = 10'000'000'000LL;
constexpr double kResidualGateMs = 0.25;

struct HostTimestamp {
    std::int64_t app_t2_ns{};
    std::int64_t kernel_t2_ns{};
    std::int64_t mapping_uncertainty_ns{};
};

struct Probe {
    std::uint64_t sequence{};
    syit::SyncSample application{};
    syit::SyncSample kernel{};
    std::int64_t host_capture_lag_ns{};
    std::int64_t speaker_capture_lag_ns{};
    std::int64_t host_mapping_uncertainty_ns{};
    std::int64_t speaker_mapping_uncertainty_ns{};
};

struct FitSnapshot {
    syit::EstimatorResult fit{};
    std::size_t stable_streak{};
};

struct HoldoutMetrics {
    std::size_t count{};
    double rms_ms{};
    double median_abs_ms{};
    double p95_abs_ms{};
};

std::optional<std::int64_t> field(const std::string& line, const std::string& key) {
    const std::string marker = key + "=";
    const auto position = line.find(marker);
    if (position == std::string::npos) return std::nullopt;
    const auto begin = position + marker.size();
    auto end = begin;
    if (end < line.size() && (line[end] == '-' || line[end] == '+')) ++end;
    while (end < line.size() && line[end] >= '0' && line[end] <= '9') ++end;
    if (end == begin || (end == begin + 1 &&
        (line[begin] == '-' || line[begin] == '+'))) return std::nullopt;
    std::int64_t value{};
    const auto parsed = std::from_chars(line.data() + begin, line.data() + end, value);
    if (parsed.ec != std::errc{} || parsed.ptr != line.data() + end) return std::nullopt;
    return value;
}

std::string option_value(int argc, char** argv, const std::string& option) {
    for (int index = 1; index + 1 < argc; ++index) {
        if (std::string(argv[index]) == option) return argv[index + 1];
    }
    return {};
}

syit::Nanoseconds midpoint_floor(syit::Nanoseconds left, syit::Nanoseconds right) {
    const auto whole = left / 2 + right / 2;
    const auto remainder = left % 2 + right % 2;
    const auto correction = remainder >= 0
        ? remainder / 2
        : -((-remainder + 1) / 2);
    return whole + correction;
}

std::optional<std::int64_t> welcome_value(
    const std::string& path,
    const std::string& attempt,
    const std::string& key) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open Speaker log: " + path);
    const std::string marker = "[DIAG " + attempt + "] WELCOME_ACCEPTED";
    std::string line;
    while (std::getline(input, line)) {
        if (line.find(marker) == std::string::npos) continue;
        return field(line, key);
    }
    return std::nullopt;
}

std::map<std::uint64_t, HostTimestamp> read_host_timestamps(
    const std::string& path,
    std::int64_t session_id,
    std::int64_t receiver_id) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open Host log: " + path);
    std::map<std::uint64_t, HostTimestamp> result;
    std::string line;
    while (std::getline(input, line)) {
        if (line.find("DIAG HOST_UDP_PROBE ") == std::string::npos) continue;
        const auto session = field(line, "sessionId");
        const auto receiver = field(line, "receiverId");
        const auto sequence = field(line, "sequence");
        const auto app_t2 = field(line, "appT2Ns");
        const auto kernel_t2 = field(line, "kernelT2Ns");
        const auto uncertainty = field(line, "kernelT2MappingUncertaintyNs");
        if (!session || !receiver || !sequence || !app_t2 || !kernel_t2 ||
            !uncertainty || *session != session_id || *receiver != receiver_id || *sequence < 0) {
            continue;
        }
        result[static_cast<std::uint64_t>(*sequence)] = {
            *app_t2, *kernel_t2, *uncertainty
        };
    }
    return result;
}

std::vector<Probe> read_probes(
    const std::string& path,
    const std::string& attempt,
    const std::map<std::uint64_t, HostTimestamp>& host_timestamps) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open Speaker log: " + path);
    const std::string marker = "[DIAG " + attempt + "] CLOCK_PROBE ";
    std::vector<Probe> result;
    std::string line;
    while (std::getline(input, line)) {
        if (line.find(marker) == std::string::npos) continue;
        const auto sequence = field(line, "sequence");
        const auto t1 = field(line, "t1Ns");
        const auto t2 = field(line, "t2Ns");
        const auto t3 = field(line, "t3Ns");
        const auto t4 = field(line, "t4Ns");
        const auto app_t4 = field(line, "appT4Ns");
        const auto kernel_t4 = field(line, "kernelT4Ns");
        const auto speaker_uncertainty = field(line, "kernelT4MappingUncertaintyNs");
        if (!sequence || !t1 || !t2 || !t3 || !t4 ||
            !kernel_t4 || !speaker_uncertainty || *sequence < 0) {
            continue;
        }
        const auto host = host_timestamps.find(static_cast<std::uint64_t>(*sequence));
        if (host == host_timestamps.end() ||
            (host->second.app_t2_ns != *t2 && host->second.kernel_t2_ns != *t2)) {
            continue;
        }

        // Live and historical logs use application T2/T4 for the production estimator.
        // Kernel T2/T4 are retained as side-channel measurements for offline comparison.
        const auto application_t4 = app_t4.value_or(*t4);
        Probe probe;
        probe.sequence = static_cast<std::uint64_t>(*sequence);
        probe.application = {
            *t1, host->second.app_t2_ns, *t3, application_t4, application_t4};
        probe.kernel = {
            *t1, host->second.kernel_t2_ns, *t3, *kernel_t4, *kernel_t4};
        probe.host_capture_lag_ns =
            host->second.app_t2_ns - host->second.kernel_t2_ns;
        probe.speaker_capture_lag_ns = application_t4 - *kernel_t4;
        probe.host_mapping_uncertainty_ns = host->second.mapping_uncertainty_ns;
        probe.speaker_mapping_uncertainty_ns = *speaker_uncertainty;
        result.push_back(probe);
    }
    std::stable_sort(result.begin(), result.end(), [](const auto& left, const auto& right) {
        return left.application.receiver_receive_ns < right.application.receiver_receive_ns;
    });
    return result;
}

std::vector<syit::SyncSample> samples_for(const std::vector<Probe>& probes, bool kernel) {
    std::vector<syit::SyncSample> result;
    result.reserve(probes.size());
    for (const auto& probe : probes) result.push_back(kernel ? probe.kernel : probe.application);
    return result;
}

FitSnapshot replay_live_policy(const std::vector<syit::SyncSample>& samples) {
    std::vector<syit::SyncSample> recent;
    std::size_t stable_streak = 0;
    syit::EstimatorResult last_fit{};
    bool has_fit = false;
    for (const auto& sample : samples) {
        recent.push_back(sample);
        recent.erase(
            std::remove_if(recent.begin(), recent.end(), [&](const auto& item) {
                return item.received_at_true_ns > sample.received_at_true_ns ||
                    static_cast<long double>(sample.received_at_true_ns) -
                        item.received_at_true_ns > static_cast<long double>(kEstimatorWindowNs);
            }),
            recent.end());
        last_fit = syit::estimate_clock_offset(
            recent, sample.received_at_true_ns, sample.receiver_receive_ns);
        has_fit = true;
        const bool stable = last_fit.receiver_skew_ppm.has_value() &&
            last_fit.samples_used >= 8U &&
            last_fit.selected_sample_span_ns >= kMinimumSpanNs &&
            last_fit.residual_rms_ms < kResidualGateMs;
        stable_streak = stable ? std::min<std::size_t>(stable_streak + 1U, 3U) : 0U;
    }
    if (!has_fit) throw std::runtime_error("no replayable probes");
    return {last_fit, stable_streak};
}

double percentile(std::vector<double> values, double fraction) {
    if (values.empty()) return 0.0;
    std::sort(values.begin(), values.end());
    const auto index = static_cast<std::size_t>(
        std::ceil(fraction * static_cast<double>(values.size())) - 1.0);
    return values[std::min(index, values.size() - 1U)];
}

HoldoutMetrics holdout(
    const std::vector<syit::SyncSample>& samples,
    const std::vector<std::int64_t>& rtts) {
    HoldoutMetrics metrics;
    if (samples.size() < 10U) return metrics;

    const auto split = std::max<std::size_t>(
        8U, static_cast<std::size_t>(std::floor(static_cast<double>(samples.size()) * 0.7)));
    if (split >= samples.size()) return metrics;

    std::vector<double> all_rtts;
    all_rtts.reserve(rtts.size());
    for (const auto rtt : rtts) all_rtts.push_back(static_cast<double>(rtt));
    const auto low_rtt_cutoff = percentile(all_rtts, 0.25);

    std::vector<syit::SyncSample> training(
        samples.begin(), samples.begin() + static_cast<std::ptrdiff_t>(split));
    const auto training_end = training.back().received_at_true_ns;
    std::vector<double> errors_ms;
    for (std::size_t index = split; index < samples.size(); ++index) {
        if (static_cast<double>(rtts[index]) > low_rtt_cutoff) continue;
        const auto prediction_time = midpoint_floor(
            samples[index].receiver_send_ns, samples[index].receiver_receive_ns);
        const auto fit = syit::estimate_clock_offset(training, training_end, prediction_time);
        const auto actual = syit::calculate_four_timestamp(samples[index]).offset_ns;
        errors_ms.push_back(std::abs(fit.offset_ns - static_cast<double>(actual)) / 1'000'000.0);
    }
    if (errors_ms.empty()) return metrics;

    double sum_squares = 0.0;
    for (const auto error : errors_ms) sum_squares += error * error;
    metrics.count = errors_ms.size();
    metrics.rms_ms = std::sqrt(sum_squares / static_cast<double>(errors_ms.size()));
    metrics.median_abs_ms = percentile(errors_ms, 0.50);
    metrics.p95_abs_ms = percentile(errors_ms, 0.95);
    return metrics;
}

void print_fit(const std::string& label, const FitSnapshot& snapshot) {
    const auto& fit = snapshot.fit;
    std::cout << label
              << " residualRmsMs=" << fit.residual_rms_ms
              << " offsetMs=" << fit.offset_ns / 1'000'000.0
              << " skewPpm=";
    if (fit.receiver_skew_ppm) std::cout << *fit.receiver_skew_ppm;
    else std::cout << "unavailable";
    std::cout << " samplesUsed=" << fit.samples_used
              << " samplesSeen=" << fit.samples_seen
              << " selectedSpanMs=" << fit.selected_sample_span_ns / 1'000'000.0
              << " stableStreak=" << snapshot.stable_streak << '\n';
}

void print_holdout(const std::string& label, const HoldoutMetrics& metrics) {
    std::cout << label << " count=" << metrics.count
              << " rmsAbsOffsetErrorMs=" << metrics.rms_ms
              << " medianAbsOffsetErrorMs=" << metrics.median_abs_ms
              << " p95AbsOffsetErrorMs=" << metrics.p95_abs_ms << '\n';
}

void print_distribution(const std::string& label, const std::vector<double>& values) {
    if (values.empty()) {
        std::cout << label << " count=0\n";
        return;
    }
    std::cout << label << " count=" << values.size()
              << " median=" << percentile(values, 0.50)
              << " p95=" << percentile(values, 0.95)
              << " max=" << *std::max_element(values.begin(), values.end()) << '\n';
}

void write_csv(const std::string& path, const std::vector<Probe>& probes) {
    std::ofstream output(path);
    if (!output) throw std::runtime_error("cannot write CSV: " + path);
    output << "sequence,appRttMs,appOffsetMs,kernelRttMs,kernelOffsetMs,"
              "hostCaptureLagUs,speakerCaptureLagUs,"
              "hostMappingUncertaintyNs,speakerMappingUncertaintyNs\n";
    for (const auto& probe : probes) {
        const auto app = syit::calculate_four_timestamp(probe.application);
        const auto kernel = syit::calculate_four_timestamp(probe.kernel);
        output << probe.sequence << ','
               << static_cast<double>(app.rtt_ns) / 1'000'000.0 << ','
               << static_cast<double>(app.offset_ns) / 1'000'000.0 << ','
               << static_cast<double>(kernel.rtt_ns) / 1'000'000.0 << ','
               << static_cast<double>(kernel.offset_ns) / 1'000'000.0 << ','
               << static_cast<double>(probe.host_capture_lag_ns) / 1'000.0 << ','
               << static_cast<double>(probe.speaker_capture_lag_ns) / 1'000.0 << ','
               << probe.host_mapping_uncertainty_ns << ','
               << probe.speaker_mapping_uncertainty_ns << '\n';
    }
}

int run(int argc, char** argv) {
    const auto speaker_path = option_value(argc, argv, "--speaker-log");
    const auto host_path = option_value(argc, argv, "--host-log");
    const auto attempt = option_value(argc, argv, "--attempt");
    const auto csv_path = option_value(argc, argv, "--csv");
    if (speaker_path.empty() || host_path.empty() || attempt.empty()) {
        std::cerr << "Usage: sync_in_it_clock_probe_replay --speaker-log FILE "
                     "--host-log FILE --attempt ID [--csv FILE]\n";
        return 2;
    }

    const auto session = welcome_value(speaker_path, attempt, "sessionId");
    const auto receiver = welcome_value(speaker_path, attempt, "receiverId");
    if (!session || !receiver) {
        throw std::runtime_error("selected Speaker attempt has no WELCOME_ACCEPTED sessionId/receiverId");
    }

    const auto host_timestamps = read_host_timestamps(host_path, *session, *receiver);
    const auto probes = read_probes(speaker_path, attempt, host_timestamps);
    if (probes.empty()) {
        throw std::runtime_error(
            "no paired CLOCK_PROBE/HOST_UDP_PROBE records with kernel timestamps; "
            "check attempt ID, timestampMode and matching diagnostic exports");
    }

    const auto app_samples = samples_for(probes, false);
    const auto kernel_samples = samples_for(probes, true);
    std::vector<std::int64_t> app_rtts;
    std::vector<std::int64_t> kernel_rtts;
    std::vector<double> host_lags_us;
    std::vector<double> speaker_lags_us;
    std::vector<double> host_mapping_uncertainty_ns;
    std::vector<double> speaker_mapping_uncertainty_ns;
    app_rtts.reserve(probes.size());
    kernel_rtts.reserve(probes.size());
    for (const auto& probe : probes) {
        app_rtts.push_back(syit::calculate_four_timestamp(probe.application).rtt_ns);
        kernel_rtts.push_back(syit::calculate_four_timestamp(probe.kernel).rtt_ns);
        host_lags_us.push_back(static_cast<double>(probe.host_capture_lag_ns) / 1'000.0);
        speaker_lags_us.push_back(static_cast<double>(probe.speaker_capture_lag_ns) / 1'000.0);
        host_mapping_uncertainty_ns.push_back(static_cast<double>(probe.host_mapping_uncertainty_ns));
        speaker_mapping_uncertainty_ns.push_back(static_cast<double>(probe.speaker_mapping_uncertainty_ns));
    }

    std::cout << std::fixed << std::setprecision(6);
    std::cout << "attempt=" << attempt
              << " hostSessionId=" << *session
              << " receiverId=" << *receiver
              << " matchedKernelProbes=" << probes.size()
              << " hostKernelRecords=" << host_timestamps.size() << '\n';
    print_fit("applicationFit", replay_live_policy(app_samples));
    print_fit("kernelReceiveFit", replay_live_policy(kernel_samples));
    print_holdout("applicationHoldout", holdout(app_samples, app_rtts));
    print_holdout("kernelReceiveHoldout", holdout(kernel_samples, kernel_rtts));
    print_distribution("hostT2CaptureLagUs", host_lags_us);
    print_distribution("speakerT4CaptureLagUs", speaker_lags_us);
    print_distribution("hostT2MappingUncertaintyNs", host_mapping_uncertainty_ns);
    print_distribution("speakerT4MappingUncertaintyNs", speaker_mapping_uncertainty_ns);
    if (!csv_path.empty()) write_csv(csv_path, probes);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        return run(argc, argv);
    } catch (const std::exception& error) {
        std::cerr << "clock probe replay failed: " << error.what() << '\n';
        return 1;
    }
}
