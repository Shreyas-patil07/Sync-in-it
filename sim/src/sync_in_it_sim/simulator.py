# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import math
import random

from .estimator import ClockOffsetEstimator
from .models import ClockModel, EstimatorResult, NetworkProfile, SyncSample
from .network import SimulatedNetwork
from .timing import DacClockModel, DriftAction, DriftController, SAMPLE_RATE_HZ


class ReceiverSimulation:
    """Deterministic clock/network model with a simplified 20 ms playout loop."""

    def __init__(
        self,
        *,
        receiver_clock: ClockModel,
        network_profile: NetworkProfile,
        seed: int = 7,
        duration_s: float = 60.0,
        join_at_s: float = 0.0,
        dac_clock_rate_error_ppm: float = 0.0,
    ) -> None:
        if not math.isfinite(duration_s) or duration_s <= 0:
            raise ValueError("duration_s must be finite and positive")
        if not math.isfinite(join_at_s) or join_at_s < 0 or join_at_s >= duration_s:
            raise ValueError("join_at_s must be finite and within the simulation duration")
        if not math.isfinite(dac_clock_rate_error_ppm) or dac_clock_rate_error_ppm <= -1_000_000:
            raise ValueError("dac_clock_rate_error_ppm must be finite and greater than -1,000,000")
        self.receiver_clock = receiver_clock
        self.profile = network_profile
        self.rng = random.Random(seed)
        self.network = SimulatedNetwork(network_profile, self.rng)
        self.duration_ns = round(duration_s * 1_000_000_000)
        self.join_at_ns = round(join_at_s * 1_000_000_000)
        self.dac_clock_rate_error_ppm = dac_clock_rate_error_ppm
        self.samples: list[SyncSample] = []
        self._estimate_history: list[tuple[int, float, float | None]] = []
        self._estimator = ClockOffsetEstimator()

    def _probe(self, true_send_ns: int) -> SyncSample | None:
        if self.network.packet_lost():
            return None

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

        return SyncSample(
            t1_receiver_send_ns=t1,
            t2_master_receive_ns=t2,
            t3_master_send_ns=t3,
            t4_receiver_receive_ns=t4,
            received_at_true_ns=t4_true,
        )

    def _true_offset_ns(self, true_time_ns: int) -> float:
        return -(
            self.receiver_clock.offset_ns
            + true_time_ns * self.receiver_clock.rate_error_ppm / 1_000_000.0
        )

    def _record_estimate(self, sample: SyncSample) -> None:
        estimate = self._estimator.estimate(
            self.samples,
            now_true_ns=sample.received_at_true_ns,
            receiver_now_ns=self.receiver_clock.read_ns(sample.received_at_true_ns),
        )
        offset_error_ms = (estimate.offset_ns - self._true_offset_ns(sample.received_at_true_ns)) / 1_000_000.0
        skew_error_ppm = (
            estimate.receiver_skew_ppm - self.receiver_clock.rate_error_ppm
            if estimate.receiver_skew_ppm is not None
            else None
        )
        self._estimate_history.append((sample.received_at_true_ns, offset_error_ms, skew_error_ppm))

    def _convergence_time_s(self) -> float | None:
        """Require three consecutive estimates within 1 ms and 5 ppm."""
        consecutive = 0
        for timestamp_ns, offset_error_ms, skew_error_ppm in self._estimate_history:
            stable = (
                skew_error_ppm is not None
                and abs(offset_error_ms) <= 1.0
                and abs(skew_error_ppm) <= 5.0
            )
            consecutive = consecutive + 1 if stable else 0
            if consecutive == 3:
                return (timestamp_ns - self.join_at_ns) / 1_000_000_000.0
        return None

    @staticmethod
    def _percentile(values: list[float], percentile: float) -> float:
        if not values:
            return 0.0
        ordered = sorted(values)
        index = max(0, math.ceil(percentile * len(ordered)) - 1)
        return ordered[index]

    def _playout_metrics(
        self,
        *,
        estimate: EstimatorResult,
        offset_error_end_ms: float,
        skew_error_ppm: float | None,
    ) -> dict[str, float | int | None]:
        if estimate.receiver_skew_ppm is None:
            return {
                "dac_clock_rate_error_ppm": self.dac_clock_rate_error_ppm,
                "effective_playout_rate_error_ppm": None,
                "playout_error_p95_ms": None,
                "playout_error_max_ms": None,
                "playout_error_final_ms": None,
                "slips_per_minute": None,
                "hard_resync_count": None,
                "slip_count": None,
            }

        # The fitted receiver-clock rate and DAC rate jointly determine frame
        # production relative to the master timeline in this simplified model.
        effective_rate_error_ppm = estimate.receiver_skew_ppm + self.dac_clock_rate_error_ppm
        playback_start_ns = min(self.duration_ns, self.join_at_ns + 1_000_000_000)
        playback_duration_ns = self.duration_ns - playback_start_ns
        if playback_duration_ns <= 0:
            return {
                "dac_clock_rate_error_ppm": self.dac_clock_rate_error_ppm,
                "effective_playout_rate_error_ppm": effective_rate_error_ppm,
                "playout_error_p95_ms": 0.0,
                "playout_error_max_ms": 0.0,
                "playout_error_final_ms": 0.0,
                "slips_per_minute": 0.0,
                "hard_resync_count": 0,
                "slip_count": 0,
            }

        # Back-project the fitted end offset to the scheduled playback start.
        skew_error = skew_error_ppm or 0.0
        elapsed_after_start_s = (self.duration_ns - playback_start_ns) / 1_000_000_000.0
        offset_error_start_ms = offset_error_end_ms + skew_error * elapsed_after_start_s / 1_000.0
        initial_error_frames = offset_error_start_ms * SAMPLE_RATE_HZ / 1_000.0

        dac_clock = DacClockModel(rate_error_ppm=effective_rate_error_ppm)
        controller = DriftController()
        correction_frames = 0
        slip_count = 0
        hard_resync_count = 0
        errors_ms: list[float] = []
        tick_ns = 20_000_000
        elapsed_ns = tick_ns

        while elapsed_ns <= playback_duration_ns:
            expected_frames = round(elapsed_ns * SAMPLE_RATE_HZ / 1_000_000_000)
            actual_frames = dac_clock.frame_at(elapsed_ns)
            drift_frames = actual_frames - expected_frames
            error_frames = initial_error_frames - drift_frames - correction_frames
            rounded_error = round(error_frames)
            errors_ms.append(abs(error_frames) * 1_000.0 / SAMPLE_RATE_HZ)
            decision = controller.decide(rounded_error)

            if decision.action == DriftAction.DROP_ONE_FRAME:
                correction_frames += 1
                slip_count += 1
            elif decision.action == DriftAction.DUPLICATE_ONE_FRAME:
                correction_frames -= 1
                slip_count += 1
            elif decision.action == DriftAction.HARD_RESYNC:
                correction_frames = initial_error_frames - drift_frames
                hard_resync_count += 1

            elapsed_ns += tick_ns

        final_expected = round(playback_duration_ns * SAMPLE_RATE_HZ / 1_000_000_000)
        final_actual = dac_clock.frame_at(playback_duration_ns)
        final_drift = final_actual - final_expected
        final_error_frames = initial_error_frames - final_drift - correction_frames
        playback_duration_s = playback_duration_ns / 1_000_000_000.0

        return {
            "dac_clock_rate_error_ppm": self.dac_clock_rate_error_ppm,
            "effective_playout_rate_error_ppm": effective_rate_error_ppm,
            "playout_error_p95_ms": self._percentile(errors_ms, 0.95),
            "playout_error_max_ms": max(errors_ms, default=0.0),
            "playout_error_final_ms": abs(final_error_frames) * 1_000.0 / SAMPLE_RATE_HZ,
            "slips_per_minute": slip_count / playback_duration_s * 60.0,
            "hard_resync_count": hard_resync_count,
            "slip_count": slip_count,
        }

    def run(self) -> dict[str, float | int | bool | None]:
        # 20-request join burst, then 2-second steady-state probes.
        probe_times = [self.join_at_ns + index * 50_000_000 for index in range(20)]
        next_probe = self.join_at_ns + 1_000_000_000
        while next_probe <= self.duration_ns:
            probe_times.append(next_probe)
            next_probe += 2_000_000_000
        probe_times = [send_time_ns for send_time_ns in probe_times if send_time_ns <= self.duration_ns]

        for send_time_ns in probe_times:
            sample = self._probe(send_time_ns)
            if sample is not None:
                self.samples.append(sample)
                self._record_estimate(sample)

        if not self.samples:
            raise RuntimeError("simulation produced no successful time-sync samples")

        result = self._estimator.estimate(
            self.samples,
            now_true_ns=self.duration_ns,
            receiver_now_ns=self.receiver_clock.read_ns(self.duration_ns),
        )
        true_offset_at_end = self._true_offset_ns(self.duration_ns)
        offset_error_ms = (result.offset_ns - true_offset_at_end) / 1_000_000.0
        skew_error_ppm = (
            result.receiver_skew_ppm - self.receiver_clock.rate_error_ppm
            if result.receiver_skew_ppm is not None
            else None
        )
        convergence_time_s = self._convergence_time_s()
        tracking_metrics = self._playout_metrics(
            estimate=result,
            offset_error_end_ms=offset_error_ms,
            skew_error_ppm=skew_error_ppm,
        )
        return {
            "samples_sent": len(probe_times),
            "samples_received": len(self.samples),
            "samples_lost": len(probe_times) - len(self.samples),
            "offset_estimate_ms": result.offset_ns / 1_000_000.0,
            "offset_error_ms": offset_error_ms,
            "receiver_skew_estimate_ppm": result.receiver_skew_ppm,
            "skew_error_ppm": skew_error_ppm,
            "convergence_time_s": convergence_time_s,
            "estimator_converged": convergence_time_s is not None,
            "rtt_p50_ms": result.rtt_p50_ms,
            "fit_residual_rms_ms": result.residual_rms_ms,
            "samples_used": result.samples_used,
            **tracking_metrics,
        }
