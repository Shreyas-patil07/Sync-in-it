# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import argparse
import json

from .lan import run_shared_wifi_scenarios
from .scenarios import run_scenarios


def _print_lan_results(results: dict) -> None:
    print("Sync In It shared Wi-Fi contention model")
    print(
        f"{'Load case':<25} {'Policy':<9} {'Util %':>8} {'Audio p95':>11} "
        f"{'Late %':>9} {'Loss %':>9} {'Ctrl RTT p95':>14} {'Stable':>7} {'Fast/Slow':>11} {'Probes':>8} {'Q drop':>8}"
    )
    for name, comparison in results.items():
        for policy in ("fixed", "adaptive"):
            item = comparison[policy]
            print(
                f"{name:<25} {policy:<9} {item['medium_utilization_pct']:>8.1f} "
                f"{(item['audio_latency_p95_ms'] or 0.0):>11.2f} "
                f"{item['audio_deadline_miss_pct']:>9.3f} "
                f"{item['audio_chunk_loss_pct']:>9.3f} "
                f"{(item['control_rtt_p95_ms'] or 0.0):>14.2f} "
                f"{item['acquisition_stable_receivers']:>7} "
                f"{item['probe_fast_retry_count']}/{item['probe_steady_count']: <8} "
                f"{item['probe_count']:>8} "
                f"{item['queue_drops_audio'] + item['queue_drops_control']:>8}"
            )
        delta = comparison["delta"]
        print(
            f"  adaptive - fixed: audio p95 {delta['audio_latency_p95_ms']!s} ms, "
            f"deadline misses {delta['audio_deadline_miss_pct']!s} pp, "
            f"control RTT p95 {delta['control_rtt_p95_ms']!s} ms, "
            f"probes {delta['probe_count']:+d}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Sync In It M0 clock/LAN simulations")
    parser.add_argument("--duration", type=float, default=60.0, help="simulation duration in seconds")
    parser.add_argument("--seed", type=int, default=7, help="deterministic random seed")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    parser.add_argument("--lan", action="store_true", help="run shared Wi-Fi airtime/queue scenarios only")
    args = parser.parse_args()

    if args.lan:
        results = run_shared_wifi_scenarios(duration_s=args.duration, seed=args.seed)
    else:
        results = run_scenarios(duration_s=args.duration, seed=args.seed)

    if args.json:
        print(json.dumps(results, indent=2, sort_keys=True))
        return
    if args.lan:
        _print_lan_results(results)
        return

    print(f"Sync In It M0 simulation — {args.duration:g}s, seed {args.seed}")
    print(
        f"{'Scenario':<24} {'RX/Samp':>7} {'Offset err (ms)':>15} {'Skew (ppm)':>11} "
        f"{'Conv (s)':>9} {'Slip/min':>9} {'Slip p95/max':>15} "
        f"{'Resamp p95/max':>15} {'Rate RMS/peak':>15} {'Resync S/R':>12}"
    )
    comparison = results.get("acquisition_schedule_comparison")
    for name, result in results.items():
        if name == "acquisition_schedule_comparison":
            continue
        skew = result["receiver_skew_estimate_ppm"]
        skew_text = "n/a" if skew is None else f"{skew:,.2f}"
        convergence = result.get("convergence_time_s")
        convergence_text = "n/a" if convergence is None else f"{convergence:.1f}"
        slips = result.get("slips_per_minute")
        slips_text = "n/a" if slips is None else f"{slips:.1f}"
        resyncs = result.get("hard_resync_count")
        resync_text = "n/a" if resyncs is None else str(resyncs)
        slip_p95 = result.get("playout_error_p95_ms")
        slip_max = result.get("playout_error_max_ms")
        slip_text = "n/a" if slip_p95 is None or slip_max is None else f"{slip_p95:.3f}/{slip_max:.3f}"
        resample_p95 = result.get("resampler_playout_error_p95_ms")
        resample_max = result.get("resampler_playout_error_max_ms")
        resample_text = "n/a" if resample_p95 is None or resample_max is None else f"{resample_p95:.3f}/{resample_max:.3f}"
        rate_rms = result.get("resampler_rate_adjustment_rms_ppm")
        rate_peak = result.get("resampler_rate_adjustment_max_abs_ppm")
        rate_text = "n/a" if rate_rms is None or rate_peak is None else f"{rate_rms:.1f}/{rate_peak:.1f}"
        slip_resyncs = result.get("hard_resync_count")
        resampler_resyncs = result.get("resampler_hard_resync_count")
        resync_text = "n/a" if slip_resyncs is None or resampler_resyncs is None else f"{slip_resyncs}/{resampler_resyncs}"
        receiver_count = result.get("receiver_count", result["samples_received"])
        print(
            f"{name:<24} {receiver_count:>4} {result['offset_error_ms']:>15.3f} {skew_text:>11} "
            f"{convergence_text:>9} {slips_text:>9} {slip_text:>15} {resample_text:>15} "
            f"{rate_text:>15} {resync_text:>12}"
        )

    if comparison is not None:
        print("\nAcquisition cadence A/B (same profile, seed and duration):")
        for profile, metrics in comparison["profile_comparisons"].items():
            fixed = metrics["baseline_convergence_time_s"]
            adaptive = metrics["adaptive_convergence_time_s"]
            fixed_text = "n/a" if fixed is None else f"{fixed:.1f}s"
            adaptive_text = "n/a" if adaptive is None else f"{adaptive:.1f}s"
            print(
                f"  {profile:<20} convergence {fixed_text:>7} -> {adaptive_text:<7}; "
                f"probes {metrics['baseline_probe_count']} -> {metrics['adaptive_probe_count']} "
                f"(+{metrics['extra_probe_count']})"
            )


if __name__ == "__main__":
    main()
