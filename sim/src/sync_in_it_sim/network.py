# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import random

from .models import NetworkProfile


class SimulatedNetwork:
    def __init__(self, profile: NetworkProfile, rng: random.Random) -> None:
        self.profile = profile
        self.rng = rng

    def sample_delay_ns(self, *, forward: bool) -> int:
        mean = self.profile.forward_mean_ms if forward else self.profile.reverse_mean_ms
        delay_ms = max(0.0, self.rng.gauss(mean, self.profile.jitter_std_ms))
        if self.rng.random() < self.profile.spike_probability:
            delay_ms += self.profile.spike_extra_ms
        return round(delay_ms * 1_000_000)

    def packet_lost(self) -> bool:
        return self.rng.random() < self.profile.loss_probability

    @property
    def processing_ns(self) -> int:
        return round(self.profile.processing_ms * 1_000_000)
