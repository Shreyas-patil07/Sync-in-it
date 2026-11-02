# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import math
from typing import Any


ACCURACY_SCENARIOS = (
    "ideal",
    "fast_clock_30ppm",
    "slow_clock_40ppm",
    "high_jitter",
    "packet_loss",
    "burst_loss",
    "rtt_spikes",
    "combined_impairment",
)
READY_SCENARIOS = ("ideal", "fast_clock_30ppm", "slow_clock_40ppm")
EXPECTED_NOT_READY_SCENARIOS = (
    "high_jitter",
    "packet_loss",
    "burst_loss",
    "rtt_spikes",
    "combined_impairment",
    "asymmetric_delay",
    "late_join",
)
DEGRADED_SCENARIOS = ("asymmetric_delay", "severe_asymmetric_delay")

# Provisional, deterministic simulator-only acceptance limits.
MAX_GROUND_TRUTH_CONVERGENCE_S = 50.0
MAX_ABS_OFFSET_ERROR_MS = 1.0
MAX_PLAYOUT_P95_MS = 1.0
MIN_SELECTED_SAMPLES = 8
MIN_SELECTED_SAMPLE_SPAN_S = 10.0
MAX_FIT_RESIDUAL_RMS_MS = 0.25


def _number(result: dict[str, Any], key: str) -> float | None:
    value = result.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def _check(
    checks: list[dict[str, Any]],
    name: str,
    passed: bool,
    observed: Any,
    expected: str,
) -> None:
    checks.append(
        {
            "name": name,
            "passed": bool(passed),
            "observed": observed,
            "expected": expected,
        }
    )


def _fit_quality_checks(
    checks: list[dict[str, Any]],
    scenario_name: str,
    result: dict[str, Any],
) -> None:
    samples_used = _number(result, "samples_used")
    sample_span = _number(result, "selected_sample_span_s")
    residual = _number(result, "fit_residual_rms_ms")
    _check(
        checks,
        f"{scenario_name}.selected_samples",
        samples_used is not None and samples_used >= MIN_SELECTED_SAMPLES,
        samples_used,
        f">={MIN_SELECTED_SAMPLES}",
    )
    _check(
        checks,
        f"{scenario_name}.selected_sample_span",
        sample_span is not None and sample_span >= MIN_SELECTED_SAMPLE_SPAN_S,
        sample_span,
        f">={MIN_SELECTED_SAMPLE_SPAN_S:.1f} s",
    )
    _check(
        checks,
        f"{scenario_name}.fit_residual",
        residual is not None and residual < MAX_FIT_RESIDUAL_RMS_MS,
        residual,
        f"<{MAX_FIT_RESIDUAL_RMS_MS:.2f} ms",
    )


def evaluate_m0_acceptance(results: dict[str, Any]) -> dict[str, Any]:
    """Evaluate the deterministic 60 s / seed-7 M0 simulator acceptance suite.

    The gate distinguishes estimator accuracy from readiness. Noisy scenarios
    may converge accurately while remaining not-ready under the strict 0.25 ms
    operational fit gate. This is deliberate fail-closed behavior.
    """
    checks: list[dict[str, Any]] = []

    for scenario_name in ACCURACY_SCENARIOS:
        result = results.get(scenario_name)
        if not isinstance(result, dict):
            _check(checks, f"{scenario_name}.present", False, None, "scenario result exists")
            continue

        converged = result.get("estimator_converged") is True
        convergence_s = _number(result, "convergence_time_s")
        offset_error = _number(result, "offset_error_ms")
        playout_p95 = _number(result, "playout_error_p95_ms")
        hard_resyncs = _number(result, "hard_resync_count")

        _check(
            checks,
            f"{scenario_name}.ground_truth_convergence",
            converged and convergence_s is not None and convergence_s <= MAX_GROUND_TRUTH_CONVERGENCE_S,
            {"converged": converged, "time_s": convergence_s},
            f"three consecutive updates within 1 ms offset / 5 ppm skew within {MAX_GROUND_TRUTH_CONVERGENCE_S:.0f} s",
        )
        _check(
            checks,
            f"{scenario_name}.final_offset_error",
            offset_error is not None and abs(offset_error) <= MAX_ABS_OFFSET_ERROR_MS,
            offset_error,
            f"absolute error <={MAX_ABS_OFFSET_ERROR_MS:.1f} ms",
        )
        _check(
            checks,
            f"{scenario_name}.frame_slip_playout_p95",
            playout_p95 is not None and playout_p95 < MAX_PLAYOUT_P95_MS,
            playout_p95,
            f"<{MAX_PLAYOUT_P95_MS:.1f} ms modeled p95 phase error",
        )
        _check(
            checks,
            f"{scenario_name}.unexpected_hard_resync",
            hard_resyncs == 0,
            hard_resyncs,
            "0",
        )

    for scenario_name in READY_SCENARIOS:
        result = results.get(scenario_name)
        if not isinstance(result, dict):
            _check(checks, f"{scenario_name}.present_for_readiness", False, None, "scenario result exists")
            continue
        _fit_quality_checks(checks, scenario_name, result)
        stable = result.get("acquisition_stable_at_end") is True
        _check(
            checks,
            f"{scenario_name}.operational_ready",
            stable,
            result.get("acquisition_stable_at_end"),
            "three stable updates; >=8 samples, >=10 s span, residual RMS <0.25 ms",
        )

    for scenario_name in EXPECTED_NOT_READY_SCENARIOS:
        result = results.get(scenario_name)
        if not isinstance(result, dict):
            _check(checks, f"{scenario_name}.present_for_degraded_readiness", False, None, "scenario result exists")
            continue
        stable = result.get("acquisition_stable_at_end") is True
        residual = _number(result, "fit_residual_rms_ms")
        _check(
            checks,
            f"{scenario_name}.fails_closed",
            not stable and residual is not None and residual >= MAX_FIT_RESIDUAL_RMS_MS,
            {"ready": stable, "residual_rms_ms": residual},
            "not ready while final fit residual RMS is >=0.25 ms",
        )

    moderate = results.get("asymmetric_delay")
    if isinstance(moderate, dict):
        converged = moderate.get("estimator_converged") is True
        offset_error = _number(moderate, "offset_error_ms")
        hard_resyncs = _number(moderate, "hard_resync_count")
        _check(
            checks,
            "asymmetric_delay.bias_not_misreported_as_convergence",
            not converged,
            converged,
            "false; asymmetric path bias must not satisfy ground-truth convergence",
        )
        _check(
            checks,
            "asymmetric_delay.bias_detected",
            offset_error is not None and abs(offset_error) > 1.0,
            offset_error,
            "absolute offset error >1.0 ms and visible as degraded",
        )
        _check(
            checks,
            "asymmetric_delay.no_unnecessary_hard_resync",
            hard_resyncs == 0,
            hard_resyncs,
            "0 while modeled bias remains below the 5 ms hard-resync threshold",
        )
    else:
        _check(checks, "asymmetric_delay.present", False, None, "scenario result exists")

    severe = results.get("severe_asymmetric_delay")
    if isinstance(severe, dict):
        converged = severe.get("estimator_converged") is True
        offset_error = _number(severe, "offset_error_ms")
        hard_resyncs = _number(severe, "hard_resync_count")
        _check(
            checks,
            "severe_asymmetric_delay.bias_not_misreported_as_convergence",
            not converged,
            converged,
            "false; systematic path bias must not satisfy ground-truth convergence",
        )
        _check(
            checks,
            "severe_asymmetric_delay.bias_detected",
            offset_error is not None and abs(offset_error) > 5.0,
            offset_error,
            "absolute offset error >5.0 ms",
        )
        _check(
            checks,
            "severe_asymmetric_delay.hard_resync_triggered",
            hard_resyncs is not None and hard_resyncs >= 1,
            hard_resyncs,
            ">=1 because modeled bias exceeds the 5 ms hard-resync threshold",
        )
    else:
        _check(checks, "severe_asymmetric_delay.present", False, None, "scenario result exists")

    late_join = results.get("late_join")
    if isinstance(late_join, dict):
        sent = _number(late_join, "samples_sent")
        burst = _number(late_join, "join_burst_probe_count")
        offset_error = _number(late_join, "offset_error_ms")
        _check(
            checks,
            "late_join.initial_burst",
            burst == 20,
            burst,
            "exactly 20 join probes",
        )
        _check(
            checks,
            "late_join.post_join_probing",
            sent is not None and burst is not None and sent > burst,
            {"sent": sent, "join_burst": burst},
            "at least one post-burst probe after the receiver joins",
        )
        _check(
            checks,
            "late_join.final_offset_error",
            offset_error is not None and abs(offset_error) <= 1.0,
            offset_error,
            "absolute error <=1.0 ms",
        )
    else:
        _check(checks, "late_join.present", False, None, "scenario result exists")

    fanout = results.get("ten_receiver_fanout")
    if isinstance(fanout, dict):
        max_offset_error = _number(fanout, "max_abs_offset_error_ms")
        converged_receivers = _number(fanout, "converged_receivers")
        _check(
            checks,
            "ten_receiver_fanout.ground_truth_convergence",
            converged_receivers is not None and converged_receivers >= 8,
            converged_receivers,
            "at least 8 of 10 independent paths meet ground-truth convergence",
        )
        _check(
            checks,
            "ten_receiver_fanout.maximum_offset_error",
            max_offset_error is not None and max_offset_error <= 1.0,
            max_offset_error,
            "maximum absolute final offset error <=1.0 ms",
        )
    else:
        _check(checks, "ten_receiver_fanout.present", False, None, "fan-out result exists")

    acquisition = results.get("acquisition_schedule_comparison")
    comparisons = acquisition.get("profile_comparisons") if isinstance(acquisition, dict) else None
    for profile in ("fast_clock_30ppm", "high_jitter", "packet_loss"):
        comparison = comparisons.get(profile) if isinstance(comparisons, dict) else None
        fixed = _number(comparison, "baseline_convergence_time_s") if isinstance(comparison, dict) else None
        adaptive = _number(comparison, "adaptive_convergence_time_s") if isinstance(comparison, dict) else None
        _check(
            checks,
            f"acquisition_ab.{profile}.adaptive_faster",
            fixed is not None and adaptive is not None and adaptive < fixed,
            {"fixed_s": fixed, "adaptive_s": adaptive},
            "adaptive ground-truth convergence time < fixed cadence",
        )

    failures = [item for item in checks if not item["passed"]]
    return {
        "passed": not failures,
        "check_count": len(checks),
        "passed_count": len(checks) - len(failures),
        "failed_count": len(failures),
        "checks": checks,
        "limitations": [
            "These are deterministic simulator acceptance gates, not physical-device acceptance results.",
            "The simulator's ground-truth diagnostics are unavailable at runtime and are used only for validation.",
            "The ten-receiver fan-out uses independent simulated paths; shared Wi-Fi contention is evaluated separately.",
            "The drift-controller metrics do not render PCM or measure acoustic output.",
        ],
    }
