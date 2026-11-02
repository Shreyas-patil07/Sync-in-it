# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ClockModel:
    """A monotonic clock expressed against simulated true time."""

    offset_ns: int = 0
    rate_error_ppm: float = 0.0

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
    processing_ms: float = 0.2


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
