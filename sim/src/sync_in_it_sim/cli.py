# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import argparse
import json

from .scenarios import run_scenarios


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Sync In It M0 clock-sync scenarios")
    parser.add_argument("--duration", type=float, default=60.0, help="simulation duration in seconds")
    parser.add_argument("--seed", type=int, default=7, help="deterministic random seed")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = parser.parse_args()

    results = run_scenarios(duration_s=args.duration, seed=args.seed)
    if args.json:
        print(json.dumps(results, indent=2, sort_keys=True))
        return

    print(f"Sync In It M0 simulation — {args.duration:g}s, seed {args.seed}")
    print(
        f"{'Scenario':<24} {'RX/Samp':>7} {'Offset err (ms)':>15} {'Skew (ppm)':>11} "
        f"{'Conv (s)':>9} {'Slip/min':>9} {'Resync':>7} {'Playout p95/max (ms)':>21}"
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
        p95 = result.get("playout_error_p95_ms")
        maximum = result.get("playout_error_max_ms")
        p95_text = "n/a" if p95 is None or maximum is None else f"{p95:.3f}/{maximum:.3f}"
        receiver_count = result.get("receiver_count", result["samples_received"])
        print(
            f"{name:<24} {receiver_count:>4} {result['offset_error_ms']:>15.3f} {skew_text:>11} "
            f"{convergence_text:>9} {slips_text:>9} {resync_text:>7} {p95_text:>21}"
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
