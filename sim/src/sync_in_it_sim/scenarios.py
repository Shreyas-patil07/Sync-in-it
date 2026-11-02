# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from .models import ClockModel, NetworkProfile
from .simulator import ReceiverSimulation


SCENARIOS = {
    "ideal": (ClockModel(), NetworkProfile()),
    "fast_clock_30ppm": (ClockModel(rate_error_ppm=30), NetworkProfile()),
    "slow_clock_40ppm": (ClockModel(rate_error_ppm=-40), NetworkProfile()),
    "high_jitter": (ClockModel(offset_ns=12_000_000, rate_error_ppm=25), NetworkProfile(jitter_std_ms=2.5)),
    "asymmetric_delay": (ClockModel(offset_ns=-8_000_000, rate_error_ppm=-20), NetworkProfile(forward_mean_ms=1.0, reverse_mean_ms=7.0, jitter_std_ms=0.5)),
    "packet_loss": (ClockModel(offset_ns=5_000_000, rate_error_ppm=30), NetworkProfile(loss_probability=0.25, jitter_std_ms=0.8)),
    "rtt_spikes": (ClockModel(offset_ns=5_000_000, rate_error_ppm=30), NetworkProfile(spike_probability=0.12, spike_extra_ms=35.0)),
    "combined_impairment": (ClockModel(offset_ns=15_000_000, rate_error_ppm=30), NetworkProfile(forward_mean_ms=2.0, reverse_mean_ms=4.5, jitter_std_ms=2.0, spike_probability=0.1, spike_extra_ms=25.0, loss_probability=0.12)),
    "late_join": (ClockModel(offset_ns=-6_000_000, rate_error_ppm=30), NetworkProfile(jitter_std_ms=0.7)),
}


def run_scenarios(*, duration_s: float = 60.0, seed: int = 7) -> dict[str, dict[str, float | int | None]]:
    results: dict[str, dict[str, float | int | None]] = {}
    for index, (name, (clock, network)) in enumerate(SCENARIOS.items()):
        simulation = ReceiverSimulation(
            receiver_clock=clock,
            network_profile=network,
            seed=seed + index,
            duration_s=duration_s,
            join_at_s=duration_s / 2 if name == "late_join" else 0.0,
        )
        results[name] = simulation.run()

    # Ten isolated receiver paths exercise estimator/group fan-out. This is not
    # a model of Wi-Fi airtime contention; that requires packet-level LAN tests.
    import statistics

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
            ).run()
        )

    results["ten_receiver_fanout"] = {
        "receiver_count": 10,
        "samples_sent": sum(int(item["samples_sent"]) for item in fanout_results),
        "samples_received": sum(int(item["samples_received"]) for item in fanout_results),
        "offset_error_ms": statistics.fmean(abs(float(item["offset_error_ms"])) for item in fanout_results),
        "max_abs_offset_error_ms": max(abs(float(item["offset_error_ms"])) for item in fanout_results),
        "receiver_skew_estimate_ppm": statistics.fmean(float(item["receiver_skew_estimate_ppm"]) for item in fanout_results if item["receiver_skew_estimate_ppm"] is not None),
        "rtt_p50_ms": statistics.fmean(float(item["rtt_p50_ms"]) for item in fanout_results),
        "samples_used": sum(int(item["samples_used"]) for item in fanout_results),
    }
    return results
