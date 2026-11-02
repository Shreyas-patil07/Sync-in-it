# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from collections.abc import Sequence
import math
import statistics

from .models import EstimatorResult, SyncSample


class ClockOffsetEstimator:
    """Lowest-RTT filtered offset estimator with a linear skew fit."""

    def __init__(
        self,
        *,
        window_ns: int = 60_000_000_000,
        low_rtt_fraction: float = 0.25,
        minimum_skew_samples: int = 8,
    ) -> None:
        if window_ns <= 0:
            raise ValueError("window_ns must be positive")
        if not 0 < low_rtt_fraction <= 1:
            raise ValueError("low_rtt_fraction must be in (0, 1]")
        if minimum_skew_samples < 2:
            raise ValueError("minimum_skew_samples must be at least 2")
        self.window_ns = window_ns
        self.low_rtt_fraction = low_rtt_fraction
        self.minimum_skew_samples = minimum_skew_samples

    def estimate(
        self,
        samples: Sequence[SyncSample],
        *,
        now_true_ns: int,
        receiver_now_ns: int | None = None,
        receiver_clock_rate_ppm: float = 0.0,
    ) -> EstimatorResult:
        if not samples:
            raise ValueError("at least one sync sample is required")

        recent = [
            sample
            for sample in samples
            if 0 <= now_true_ns - sample.received_at_true_ns <= self.window_ns
            and sample.rtt_ns >= 0
        ]
        if not recent:
            raise ValueError("no valid samples inside the estimator window")

        selected_count = max(1, math.ceil(len(recent) * self.low_rtt_fraction))
        selected = sorted(recent, key=lambda sample: sample.rtt_ns)[:selected_count]
        x = [float(sample.receiver_midpoint_ns) for sample in selected]
        y = [float(sample.offset_ns) for sample in selected]
        x_mean = statistics.fmean(x)
        y_mean = statistics.fmean(y)
        denominator = sum((value - x_mean) ** 2 for value in x)

        slope = 0.0
        if len(selected) >= self.minimum_skew_samples and denominator > 0:
            slope = sum(
                (x_i - x_mean) * (y_i - y_mean)
                for x_i, y_i in zip(x, y, strict=True)
            ) / denominator

        if receiver_now_ns is None:
            receiver_now_ns = round(now_true_ns * (1 + receiver_clock_rate_ppm / 1_000_000.0))
        offset_now_ns = y_mean + slope * (receiver_now_ns - x_mean)
        residuals = [y_i - (y_mean + slope * (x_i - x_mean)) for x_i, y_i in zip(x, y, strict=True)]
        residual_rms_ns = math.sqrt(statistics.fmean(value * value for value in residuals))
        rtts_ms = [sample.rtt_ns / 1_000_000.0 for sample in selected]

        estimated_skew = -slope * 1_000_000.0 if len(selected) >= self.minimum_skew_samples else None
        return EstimatorResult(
            offset_ns=offset_now_ns,
            receiver_skew_ppm=estimated_skew,
            rtt_p50_ms=statistics.median(rtts_ms),
            residual_rms_ms=residual_rms_ns / 1_000_000.0,
            samples_used=len(selected),
            samples_seen=len(recent),
        )
