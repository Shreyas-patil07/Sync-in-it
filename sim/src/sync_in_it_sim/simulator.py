# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import random

from .estimator import ClockOffsetEstimator
from .models import ClockModel, NetworkProfile, SyncSample
from .network import SimulatedNetwork


class ReceiverSimulation:
    def __init__(
        self,
        *,
        receiver_clock: ClockModel,
        network_profile: NetworkProfile,
        seed: int = 7,
        duration_s: float = 60.0,
        join_at_s: float = 0.0,
    ) -> None:
        if duration_s <= 0:
            raise ValueError("duration_s must be positive")
        self.receiver_clock = receiver_clock
        self.profile = network_profile
        self.rng = random.Random(seed)
        self.network = SimulatedNetwork(network_profile, self.rng)
        if join_at_s < 0 or join_at_s >= duration_s:
            raise ValueError("join_at_s must be within the simulation duration")
        self.duration_ns = round(duration_s * 1_000_000_000)
        self.join_at_ns = round(join_at_s * 1_000_000_000)
        self.samples: list[SyncSample] = []

    def _probe(self, true_send_ns: int) -> None:
        if self.network.packet_lost():
            return

        forward_ns = self.network.sample_delay_ns(forward=True)
        reverse_ns = self.network.sample_delay_ns(forward=False)
        processing_ns = self.network.processing_ns

        t1 = self.receiver_clock.read_ns(true_send_ns)
        t2_true = true_send_ns + forward_ns
        t2 = t2_true
        t3_true = t2_true + processing_ns
        t3 = t3_true
        t4_true = t3_true + reverse_ns
        t4 = self.receiver_clock.read_ns(t4_true)

        self.samples.append(
            SyncSample(
                t1_receiver_send_ns=t1,
                t2_master_receive_ns=t2,
                t3_master_send_ns=t3,
                t4_receiver_receive_ns=t4,
                received_at_true_ns=t4_true,
            )
        )

    def run(self) -> dict[str, float | int | None]:
        # 20-request join burst, then 2-second steady-state probes.
        probe_times = [self.join_at_ns + index * 50_000_000 for index in range(20)]
        next_probe = self.join_at_ns + 1_000_000_000
        while next_probe <= self.duration_ns:
            probe_times.append(next_probe)
            next_probe += 2_000_000_000

        for send_time_ns in probe_times:
            if send_time_ns <= self.duration_ns:
                self._probe(send_time_ns)

        if not self.samples:
            raise RuntimeError("simulation produced no successful time-sync samples")

        estimator = ClockOffsetEstimator()
        result = estimator.estimate(
            self.samples,
            now_true_ns=self.duration_ns,
            receiver_now_ns=self.receiver_clock.read_ns(self.duration_ns),
        )
        true_offset_at_end = -(
            self.receiver_clock.offset_ns
            + self.duration_ns * self.receiver_clock.rate_error_ppm / 1_000_000.0
        )
        offset_error_ms = (result.offset_ns - true_offset_at_end) / 1_000_000.0
        skew_error_ppm = (
            result.receiver_skew_ppm - self.receiver_clock.rate_error_ppm
            if result.receiver_skew_ppm is not None
            else None
        )
        return {
            "samples_sent": len(probe_times),
            "samples_received": len(self.samples),
            "offset_estimate_ms": result.offset_ns / 1_000_000.0,
            "offset_error_ms": offset_error_ms,
            "receiver_skew_estimate_ppm": result.receiver_skew_ppm,
            "skew_error_ppm": skew_error_ppm,
            "rtt_p50_ms": result.rtt_p50_ms,
            "fit_residual_rms_ms": result.residual_rms_ms,
            "samples_used": result.samples_used,
        }
