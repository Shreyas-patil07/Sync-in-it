# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

SAMPLE_RATE_HZ = 48_000


@dataclass(frozen=True, slots=True)
class TimelineAnchor:
    master_time_ns: int
    sample_index: int
    playing: bool = True


def expected_sample_index(master_time_ns: int, anchor: TimelineAnchor, sample_rate_hz: int = SAMPLE_RATE_HZ) -> int:
    if sample_rate_hz <= 0:
        raise ValueError("sample_rate_hz must be positive")
    if not anchor.playing:
        return anchor.sample_index
    elapsed_ns = master_time_ns - anchor.master_time_ns
    return anchor.sample_index + round(elapsed_ns * sample_rate_hz / 1_000_000_000)


@dataclass(frozen=True, slots=True)
class DacClockModel:
    sample_rate_hz: int = SAMPLE_RATE_HZ
    rate_error_ppm: float = 0.0
    frame_origin: int = 0
    time_origin_ns: int = 0

    def frame_at(self, local_time_ns: int) -> int:
        effective_rate = self.sample_rate_hz * (1.0 + self.rate_error_ppm / 1_000_000.0)
        elapsed_ns = local_time_ns - self.time_origin_ns
        return self.frame_origin + round(elapsed_ns * effective_rate / 1_000_000_000)

    def local_time_at(self, frame_position: int) -> int:
        effective_rate = self.sample_rate_hz * (1.0 + self.rate_error_ppm / 1_000_000.0)
        elapsed_frames = frame_position - self.frame_origin
        return self.time_origin_ns + round(elapsed_frames * 1_000_000_000 / effective_rate)


class DriftAction(str, Enum):
    NONE = "none"
    DROP_ONE_FRAME = "drop_one_frame"
    DUPLICATE_ONE_FRAME = "duplicate_one_frame"
    HARD_RESYNC = "hard_resync"


@dataclass(frozen=True, slots=True)
class DriftDecision:
    action: DriftAction
    error_frames: int


class DriftController:
    """Initial M0 policy from the TRD: dead band, bounded slip, hard resync."""

    def __init__(self, dead_band_frames: int = 5, hard_resync_frames: int = 240) -> None:
        if dead_band_frames < 0 or hard_resync_frames <= dead_band_frames:
            raise ValueError("invalid drift thresholds")
        self.dead_band_frames = dead_band_frames
        self.hard_resync_frames = hard_resync_frames

    def decide(self, expected_minus_actual_frames: int) -> DriftDecision:
        error = int(expected_minus_actual_frames)
        magnitude = abs(error)
        if magnitude <= self.dead_band_frames:
            action = DriftAction.NONE
        elif magnitude > self.hard_resync_frames:
            action = DriftAction.HARD_RESYNC
        elif error > 0:
            action = DriftAction.DROP_ONE_FRAME
        else:
            action = DriftAction.DUPLICATE_ONE_FRAME
        return DriftDecision(action=action, error_frames=error)
