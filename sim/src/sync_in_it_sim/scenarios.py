# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import statistics

from .models import ClockModel, NetworkProfile
from .simulator import ReceiverSimulation


SCENARIOS = {
    "ideal": (ClockModel(), NetworkProfile()),
    "fast_clock_30ppm": (ClockModel(rate_error_ppm=30), NetworkProfile()),
    "slow_clock_40ppm": (ClockModel(rate_error_ppm=-40), NetworkProfile()),
    "high_jitter": (ClockModel(offset_ns=12_000_000, rate_error_ppm=25), NetworkProfile(jitter_std_ms=2.5)),
    "asymmetric_delay": (ClockModel(offset_ns=-8_000_000, rate_error_ppm=-20), NetworkProfile(forward_mean_ms=1.0, reverse_mean_ms=7.0, jitter_std_ms=0.5)),
    "severe_asymmetric_delay": (ClockModel(offset_ns=2_000_000, rate_error_ppm=10), NetworkProfile(forward_mean_ms=1.0, reverse_mean_ms=15.0, jitter_std_ms=0.2)),
    "packet_loss": (ClockModel(offset_ns=5_000_000, rate_error_ppm=30), NetworkProfile(loss_probability=0.25, jitter_std_ms=0.8)),
    "burst_loss": (
        ClockModel(offset_ns=4_000_000, rate_error_ppm=-25),
        NetworkProfile(jitter_std_ms=0.8, burst_loss_probability=0.08, burst_loss_min_packets=3, burst_loss_max_packets=6),
    ),
    "rtt_spikes": (ClockModel(offset_ns=5_000_000, rate_error_ppm=30), NetworkProfile(spike_probability=0.12, spike_extra_ms=35.0)),
    "combined_impairment": (ClockModel(offset_ns=15_000_000, rate_error_ppm=30), NetworkProfile(forward_mean_ms=2.0, reverse_mean_ms=4.5, jitter_std_ms=2.0, spike_probability=0.1, spike_extra_ms=25.0, loss_probability=0.12, burst_loss_probability=0.04)),
    "late_join": (ClockModel(offset_ns=-6_000_000, rate_error_ppm=30), NetworkProfile(jitter_std_ms=0.7)),
}

DAC_RATE_ERRORS_PPM = {
    "ideal": 0.0,
    "fast_clock_30ppm": 35.0,
    "slow_clock_40ppm": -30.0,
    "high_jitter": 20.0,
    "asymmetric_delay": -25.0,
    "severe_asymmetric_delay": -10.0,
    "packet_loss": 30.0,
    "burst_loss": -35.0,
    "rtt_spikes": 25.0,
    "combined_impairment": 40.0,
    "late_join": 35.0,
}


def run_scenarios(*, duration_s: float = 60.0, seed: int = 7) -> dict[str, dict[str, float | int | bool | None]]:
    results: dict[str, dict[str, float | int | bool | None]] = {}
    for index, (name, (clock, network)) in enumerate(SCENARIOS.items()):
        simulation = ReceiverSimulation(
            receiver_clock=clock,
            network_profile=network,
            seed=seed + index,
            duration_s=duration_s,
            join_at_s=duration_s / 2 if name == "late_join" else 0.0,
            dac_clock_rate_error_ppm=DAC_RATE_ERRORS_PPM[name],
        )
        results[name] = simulation.run()

    # Ten isolated receiver paths exercise estimator/group fan-out. This is not
    # a model of Wi-Fi airtime contention; that requires packet-level LAN tests.
    fanout_results = []
    for receiver_index in range(10):
        clock = ClockModel(offset_ns=(receiver_index - 4) * 2_000_000, rate_error_ppm=30 - receiver_index * 2)
        network = NetworkProfile(
            forward_mean_ms=2.0 + receiver_index * 0.08,
            reverse_mean_ms=2.0 + receiver_index * 0.08,
            jitter_std_ms=0.7,
            loss_probability=0.03,
        )
        fanout_results.append(
            ReceiverSimulation(
                receiver_clock=clock,
                network_profile=network,
                seed=seed + 100 + receiver_index,
                duration_s=duration_s,
                dac_clock_rate_error_ppm=20 - receiver_index * 4,
            ).run()
        )

    converged_times = [float(item["convergence_time_s"]) for item in fanout_results if item["convergence_time_s"] is not None]
    results["ten_receiver_fanout"] = {
        "receiver_count": 10,
        "samples_sent": sum(int(item["samples_sent"]) for item in fanout_results),
        "samples_received": sum(int(item["samples_received"]) for item in fanout_results),
        "samples_lost": sum(int(item["samples_lost"]) for item in fanout_results),
        "offset_error_ms": statistics.fmean(abs(float(item["offset_error_ms"])) for item in fanout_results),
        "max_abs_offset_error_ms": max(abs(float(item["offset_error_ms"])) for item in fanout_results),
        "receiver_skew_estimate_ppm": statistics.fmean(float(item["receiver_skew_estimate_ppm"]) for item in fanout_results if item["receiver_skew_estimate_ppm"] is not None),
        "rtt_p50_ms": statistics.fmean(float(item["rtt_p50_ms"]) for item in fanout_results),
        "samples_used": sum(int(item["samples_used"]) for item in fanout_results),
        "converged_receivers": len(converged_times),
        "convergence_time_s": statistics.fmean(converged_times) if converged_times else None,
        "slips_per_minute": statistics.fmean(float(item["slips_per_minute"]) for item in fanout_results if item["slips_per_minute"] is not None),
        "playout_error_p95_ms": statistics.fmean(float(item["playout_error_p95_ms"]) for item in fanout_results if item["playout_error_p95_ms"] is not None),
        "playout_error_max_ms": max(float(item["playout_error_max_ms"]) for item in fanout_results if item["playout_error_max_ms"] is not None),
        "hard_resync_count": sum(int(item["hard_resync_count"]) for item in fanout_results if item["hard_resync_count"] is not None),
        "slip_count": sum(int(item["slip_count"]) for item in fanout_results if item["slip_count"] is not None),
    }
    return results
