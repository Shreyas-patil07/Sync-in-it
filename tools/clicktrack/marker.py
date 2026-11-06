# SPDX-License-Identifier: Apache-2.0
"""Shared receiver-specific acoustic marker waveform for two-phone skew tests."""

from __future__ import annotations

import math

SAMPLE_RATE_HZ = 48_000
MARKER_DURATION_MS = 20
MARKER_FRAMES = SAMPLE_RATE_HZ * MARKER_DURATION_MS // 1_000
BASE_FREQUENCY_HZ = 1_500
FREQUENCY_STEP_HZ = 750
MARKER_AMPLITUDE = 2_500
MAX_SPEAKERS = 8


def marker_frequency_hz(receiver_id: int) -> int:
    if not 1 <= receiver_id <= MAX_SPEAKERS:
        raise ValueError("receiver_id must be between 1 and {}".format(MAX_SPEAKERS))
    return BASE_FREQUENCY_HZ + (receiver_id - 1) * FREQUENCY_STEP_HZ


def marker_template(receiver_id: int) -> list[int]:
    """Return one 20 ms Hann-windowed PCM16 marker for a Host-assigned receiver ID."""
    frequency = marker_frequency_hz(receiver_id)
    result: list[int] = []
    denominator = MARKER_FRAMES - 1
    for offset in range(MARKER_FRAMES):
        envelope = 0.5 - 0.5 * math.cos(2.0 * math.pi * offset / denominator)
        phase = 2.0 * math.pi * frequency * offset / SAMPLE_RATE_HZ
        result.append(round(MARKER_AMPLITUDE * envelope * math.sin(phase)))
    return result
