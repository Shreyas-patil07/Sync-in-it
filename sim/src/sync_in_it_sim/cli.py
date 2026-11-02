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
    print(f"{'Scenario':<24} {'RX':>4} {'Offset err (ms)':>16} {'Skew est (ppm)':>15} {'RTT p50 (ms)':>13}")
    for name, result in results.items():
        skew = result["receiver_skew_estimate_ppm"]
        skew_text = "n/a" if skew is None else f"{skew:,.2f}"
        receiver_count = result.get("receiver_count", result["samples_received"])
        print(
            f"{name:<24} {receiver_count:>4} "
            f"{result['offset_error_ms']:>16.3f} {skew_text:>15} "
            f"{result['rtt_p50_ms']:>13.3f}"
        )


if __name__ == "__main__":
    main()
