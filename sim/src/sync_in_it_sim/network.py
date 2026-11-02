# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import random

from .models import NetworkProfile


class SimulatedNetwork:
    def __init__(self, profile: NetworkProfile, rng: random.Random) -> None:
        self.profile = profile
        self.rng = rng
        self._burst_packets_remaining = 0

    def sample_delay_ns(self, *, forward: bool) -> int:
        mean = self.profile.forward_mean_ms if forward else self.profile.reverse_mean_ms
        delay_ms = max(0.0, self.rng.gauss(mean, self.profile.jitter_std_ms))
        if self.rng.random() < self.profile.spike_probability:
            delay_ms += self.profile.spike_extra_ms
        return round(delay_ms * 1_000_000)

    def packet_lost(self) -> bool:
        if self._burst_packets_remaining > 0:
            self._burst_packets_remaining -= 1
            return True

        if self.rng.random() < self.profile.loss_probability:
            return True

        if self.rng.random() < self.profile.burst_loss_probability:
            burst_length = self.rng.randint(
                self.profile.burst_loss_min_packets,
                self.profile.burst_loss_max_packets,
            )
            self._burst_packets_remaining = burst_length - 1
            return True

        return False

    @property
    def processing_ns(self) -> int:
        return round(self.profile.processing_ms * 1_000_000)
