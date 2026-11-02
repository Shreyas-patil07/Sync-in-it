# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from dataclasses import dataclass
import math


@dataclass(slots=True)
class ResamplerRateController:
    """Bounded feed-forward rate correction with a proportional phase trim.

    Positive phase error means the receiver is behind and must consume the
    source timeline faster. Positive measured clock-rate error means the
    receiver output clock is running fast and must be compensated negatively.
    """

    sample_rate_hz: int = 48_000
    phase_correction_horizon_s: float = 4.0
    max_rate_adjustment_ppm: float = 500.0
    max_slew_ppm_per_second: float = 1_000.0
    current_adjustment_ppm: float = 0.0

    def __post_init__(self) -> None:
        if self.sample_rate_hz <= 0:
            raise ValueError("sample_rate_hz must be positive")
        for name in (
            "phase_correction_horizon_s",
            "max_rate_adjustment_ppm",
            "max_slew_ppm_per_second",
            "current_adjustment_ppm",
        ):
            value = getattr(self, name)
            if not math.isfinite(value):
                raise ValueError(f"{name} must be finite")
        if self.phase_correction_horizon_s <= 0:
            raise ValueError("phase_correction_horizon_s must be positive")
        if self.max_rate_adjustment_ppm <= 0 or self.max_slew_ppm_per_second <= 0:
            raise ValueError("rate and slew limits must be positive")
        if abs(self.current_adjustment_ppm) > self.max_rate_adjustment_ppm:
            raise ValueError("current adjustment exceeds the configured rate limit")

    def reset(self) -> None:
        self.current_adjustment_ppm = 0.0

    def update(
        self,
        *,
        phase_error_frames: float,
        measured_rate_error_ppm: float,
        dt_s: float,
    ) -> float:
        if not math.isfinite(phase_error_frames):
            raise ValueError("phase_error_frames must be finite")
        if not math.isfinite(measured_rate_error_ppm):
            raise ValueError("measured_rate_error_ppm must be finite")
        if not math.isfinite(dt_s) or dt_s <= 0:
            raise ValueError("dt_s must be finite and positive")

        phase_trim_ppm = (
            phase_error_frames
            / (self.sample_rate_hz * self.phase_correction_horizon_s)
            * 1_000_000.0
        )
        target_ppm = -measured_rate_error_ppm + phase_trim_ppm
        target_ppm = max(
            -self.max_rate_adjustment_ppm,
            min(self.max_rate_adjustment_ppm, target_ppm),
        )

        max_delta_ppm = self.max_slew_ppm_per_second * dt_s
        delta_ppm = max(
            -max_delta_ppm,
            min(max_delta_ppm, target_ppm - self.current_adjustment_ppm),
        )
        self.current_adjustment_ppm += delta_ppm
        return self.current_adjustment_ppm


def simulate_bounded_resampling(
    *,
    initial_error_frames: float,
    actual_rate_error_ppm: float,
    measured_rate_error_ppm: float,
    duration_s: float,
    sample_rate_hz: int = 48_000,
    tick_s: float = 0.020,
    phase_correction_horizon_s: float = 4.0,
    max_rate_adjustment_ppm: float = 500.0,
    max_slew_ppm_per_second: float = 1_000.0,
    hard_resync_frames: float = 240.0,
) -> dict[str, float | int]:
    """Run a simplified fixed-tick phase/rate simulation.

    This models control behavior only. It does not render/interpolate PCM and
    cannot estimate audible artifacts, CPU cost, or real DAC timestamp quality.
    """
    values = (
        initial_error_frames,
        actual_rate_error_ppm,
        measured_rate_error_ppm,
        duration_s,
        tick_s,
        phase_correction_horizon_s,
        max_rate_adjustment_ppm,
        max_slew_ppm_per_second,
        hard_resync_frames,
    )
    if not all(math.isfinite(value) for value in values):
        raise ValueError("simulation inputs must be finite")
    if duration_s < 0 or tick_s <= 0 or sample_rate_hz <= 0:
        raise ValueError("duration must be non-negative and rates must be positive")
    if hard_resync_frames <= 0:
        raise ValueError("hard_resync_frames must be positive")

    controller = ResamplerRateController(
        sample_rate_hz=sample_rate_hz,
        phase_correction_horizon_s=phase_correction_horizon_s,
        max_rate_adjustment_ppm=max_rate_adjustment_ppm,
        max_slew_ppm_per_second=max_slew_ppm_per_second,
    )
    phase_error_frames = float(initial_error_frames)
    errors_ms: list[float] = []
    adjustments_ppm: list[float] = []
    hard_resync_count = 0
    tick_count = math.floor(duration_s / tick_s + 1e-12)
    frames_per_tick = sample_rate_hz * tick_s

    for _ in range(tick_count):
        errors_ms.append(abs(phase_error_frames) * 1_000.0 / sample_rate_hz)
        if abs(phase_error_frames) > hard_resync_frames:
            phase_error_frames = 0.0
            hard_resync_count += 1
            controller.reset()

        adjustment = controller.update(
            phase_error_frames=phase_error_frames,
            measured_rate_error_ppm=measured_rate_error_ppm,
            dt_s=tick_s,
        )
        adjustments_ppm.append(adjustment)
        phase_error_frames -= (
            actual_rate_error_ppm + adjustment
        ) * frames_per_tick / 1_000_000.0

    ordered = sorted(errors_ms)
    p95_index = max(0, math.ceil(0.95 * len(ordered)) - 1)
    mean_square = (
        math.fsum(value * value for value in adjustments_ppm) / len(adjustments_ppm)
        if adjustments_ppm else 0.0
    )
    saturated = sum(
        abs(value) >= max_rate_adjustment_ppm - 1e-9
        for value in adjustments_ppm
    )
    return {
        "playout_error_p95_ms": ordered[p95_index] if ordered else 0.0,
        "playout_error_max_ms": max(errors_ms, default=0.0),
        "playout_error_final_ms": abs(phase_error_frames) * 1_000.0 / sample_rate_hz,
        "rate_adjustment_rms_ppm": math.sqrt(mean_square),
        "rate_adjustment_max_abs_ppm": max((abs(value) for value in adjustments_ppm), default=0.0),
        "rate_saturated_pct": saturated * 100.0 / len(adjustments_ppm) if adjustments_ppm else 0.0,
        "hard_resync_count": hard_resync_count,
    }
