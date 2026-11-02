# SPDX-License-Identifier: Apache-2.0
import unittest

from sync_in_it_sim.drift import ResamplerRateController, simulate_bounded_resampling


class ResamplerRateControllerTests(unittest.TestCase):
    def test_positive_phase_error_requests_faster_source_consumption(self):
        controller = ResamplerRateController()
        adjustment = controller.update(
            phase_error_frames=48,
            measured_rate_error_ppm=0,
            dt_s=0.020,
        )
        self.assertGreater(adjustment, 0)

    def test_fast_clock_is_compensated_with_negative_rate_adjustment(self):
        controller = ResamplerRateController()
        adjustment = controller.update(
            phase_error_frames=0,
            measured_rate_error_ppm=30,
            dt_s=0.020,
        )
        self.assertLess(adjustment, 0)
        self.assertAlmostEqual(adjustment, -20.0)

    def test_rate_and_slew_limits_are_enforced(self):
        controller = ResamplerRateController(max_rate_adjustment_ppm=100, max_slew_ppm_per_second=250)
        adjustment = controller.update(
            phase_error_frames=0,
            measured_rate_error_ppm=1_000,
            dt_s=0.020,
        )
        self.assertAlmostEqual(adjustment, -5.0)
        self.assertLessEqual(abs(adjustment), 100.0)

    def test_invalid_inputs_are_rejected(self):
        with self.assertRaises(ValueError):
            ResamplerRateController(phase_correction_horizon_s=0)
        controller = ResamplerRateController()
        with self.assertRaises(ValueError):
            controller.update(phase_error_frames=0, measured_rate_error_ppm=0, dt_s=0)

    def test_rate_correction_reduces_phase_error_without_frame_slips(self):
        result = simulate_bounded_resampling(
            initial_error_frames=2.4,
            actual_rate_error_ppm=30,
            measured_rate_error_ppm=30,
            duration_s=59,
        )
        self.assertLess(result["playout_error_p95_ms"], 0.04)
        self.assertLess(result["playout_error_final_ms"], 0.001)
        self.assertEqual(result["hard_resync_count"], 0)
        self.assertGreater(result["rate_adjustment_max_abs_ppm"], 20)

    def test_small_rate_estimation_bias_produces_bounded_residual(self):
        result = simulate_bounded_resampling(
            initial_error_frames=2.4,
            actual_rate_error_ppm=30,
            measured_rate_error_ppm=32,
            duration_s=59,
        )
        self.assertLess(result["playout_error_final_ms"], 0.02)
        self.assertLessEqual(result["rate_adjustment_max_abs_ppm"], 500)

    def test_large_initial_phase_error_uses_hard_resync_gate(self):
        result = simulate_bounded_resampling(
            initial_error_frames=300,
            actual_rate_error_ppm=30,
            measured_rate_error_ppm=30,
            duration_s=10,
        )
        self.assertEqual(result["hard_resync_count"], 1)


if __name__ == "__main__":
    unittest.main()
