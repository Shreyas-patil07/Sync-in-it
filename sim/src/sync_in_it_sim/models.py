# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from dataclasses import dataclass
import math


@dataclass(frozen=True, slots=True)
class ClockModel:
    """A monotonic clock expressed against simulated true time."""

    offset_ns: int = 0
    rate_error_ppm: float = 0.0

    def __post_init__(self) -> None:
        if not math.isfinite(self.rate_error_ppm) or self.rate_error_ppm <= -1_000_000:
            raise ValueError("rate_error_ppm must be finite and greater than -1,000,000")

    def read_ns(self, true_time_ns: int) -> int:
        rate = 1.0 + self.rate_error_ppm / 1_000_000.0
        return round(true_time_ns * rate + self.offset_ns)


@dataclass(frozen=True, slots=True)
class NetworkProfile:
    forward_mean_ms: float = 2.0
    reverse_mean_ms: float = 2.0
    jitter_std_ms: float = 0.4
    spike_probability: float = 0.0
    spike_extra_ms: float = 20.0
    loss_probability: float = 0.0
    burst_loss_probability: float = 0.0
    burst_loss_min_packets: int = 2
    burst_loss_max_packets: int = 5
    processing_ms: float = 0.2

    def __post_init__(self) -> None:
        for name in ("forward_mean_ms", "reverse_mean_ms", "jitter_std_ms", "spike_extra_ms", "processing_ms"):
            value = getattr(self, name)
            if not math.isfinite(value) or value < 0:
                raise ValueError(f"{name} must be finite and non-negative")
        for name in ("spike_probability", "loss_probability", "burst_loss_probability"):
            if not 0.0 <= getattr(self, name) <= 1.0:
                raise ValueError(f"{name} must be between 0 and 1")
        if self.burst_loss_min_packets < 1:
            raise ValueError("burst_loss_min_packets must be at least 1")
        if self.burst_loss_max_packets < self.burst_loss_min_packets:
            raise ValueError("burst_loss_max_packets must be >= burst_loss_min_packets")


@dataclass(frozen=True, slots=True)
class SyncSample:
    t1_receiver_send_ns: int
    t2_master_receive_ns: int
    t3_master_send_ns: int
    t4_receiver_receive_ns: int
    received_at_true_ns: int

    @property
    def rtt_ns(self) -> int:
        return (self.t4_receiver_receive_ns - self.t1_receiver_send_ns) - (
            self.t3_master_send_ns - self.t2_master_receive_ns
        )

    @property
    def offset_ns(self) -> int:
        """Master-minus-receiver offset; master_time = receiver_time + offset."""
        return (
            (self.t2_master_receive_ns - self.t1_receiver_send_ns)
            + (self.t3_master_send_ns - self.t4_receiver_receive_ns)
        ) // 2

    @property
    def receiver_midpoint_ns(self) -> int:
        return (self.t1_receiver_send_ns + self.t4_receiver_receive_ns) // 2


@dataclass(frozen=True, slots=True)
class EstimatorResult:
    offset_ns: float
    receiver_skew_ppm: float | None
    rtt_p50_ms: float
    residual_rms_ms: float
    samples_used: int
    samples_seen: int
    selected_sample_span_ns: int
