# SPDX-License-Identifier: Apache-2.0
import unittest

from sync_in_it_sim.estimator import ClockOffsetEstimator
from sync_in_it_sim.models import ClockModel, NetworkProfile, SyncSample
from sync_in_it_sim.simulator import ReceiverSimulation
from sync_in_it_sim.scenarios import run_scenarios


class EstimatorTests(unittest.TestCase):
    def test_four_timestamp_offset_formula(self):
        sample = SyncSample(
            t1_receiver_send_ns=110_000_000,
            t2_master_receive_ns=102_000_000,
            t3_master_send_ns=102_200_000,
            t4_receiver_receive_ns=114_200_000,
            received_at_true_ns=114_200_000,
        )
        self.assertEqual(sample.rtt_ns, 4_000_000)
        self.assertEqual(sample.offset_ns, -10_000_000)

    def test_ideal_simulation_converges(self):
        result = ReceiverSimulation(
            receiver_clock=ClockModel(offset_ns=8_000_000, rate_error_ppm=0),
            network_profile=NetworkProfile(jitter_std_ms=0),
            duration_s=60,
        ).run()
        self.assertLess(abs(result["offset_error_ms"]), 0.01)

    def test_fast_clock_skew_sign_and_magnitude(self):
        result = ReceiverSimulation(
            receiver_clock=ClockModel(offset_ns=3_000_000, rate_error_ppm=30),
            network_profile=NetworkProfile(jitter_std_ms=0.05),
            duration_s=60,
        ).run()
        self.assertIsNotNone(result["receiver_skew_estimate_ppm"])
        self.assertAlmostEqual(result["receiver_skew_estimate_ppm"], 30, delta=5)

    def test_late_join_converges_using_samples_after_join(self):
        result = ReceiverSimulation(
            receiver_clock=ClockModel(offset_ns=4_000_000, rate_error_ppm=30),
            network_profile=NetworkProfile(jitter_std_ms=0.1),
            duration_s=60,
            join_at_s=30,
        ).run()
        self.assertEqual(result["join_burst_probe_count"], 20)
        self.assertGreater(result["fast_retry_probe_count"], 0)
        self.assertGreater(result["samples_sent"], 35)
        self.assertLess(abs(result["offset_error_ms"]), 1.0)

    def test_estimator_rejects_empty_samples(self):
        with self.assertRaises(ValueError):
            ClockOffsetEstimator().estimate([], now_true_ns=0)

    def test_fast_clock_reports_convergence_and_playout_metrics(self):
        result = ReceiverSimulation(
            receiver_clock=ClockModel(offset_ns=3_000_000, rate_error_ppm=30),
            network_profile=NetworkProfile(jitter_std_ms=0.05),
            duration_s=60,
            dac_clock_rate_error_ppm=35,
        ).run()
        self.assertTrue(result["estimator_converged"])
        self.assertIsNotNone(result["convergence_time_s"])
        self.assertGreater(result["slip_count"], 0)
        self.assertGreater(result["slips_per_minute"], 0)
        self.assertLess(abs(result["offset_error_ms"]), 1.0)
        self.assertLess(result["playout_error_p95_ms"], 1.0)
        self.assertLess(result["playout_error_max_ms"], 1.0)
        self.assertIsInstance(result["hard_resync_count"], int)

    def test_documented_fast_clock_scenario_meets_one_ms_simulator_gate(self):
        result = run_scenarios(seed=7)["fast_clock_30ppm"]
        self.assertTrue(result["estimator_converged"])
        self.assertLess(abs(result["offset_error_ms"]), 1.0)
        self.assertLess(result["playout_error_p95_ms"], 1.0)
        self.assertLess(result["playout_error_max_ms"], 1.0)

    def test_severe_asymmetry_exercises_hard_resync_metric(self):
        result = ReceiverSimulation(
            receiver_clock=ClockModel(offset_ns=2_000_000, rate_error_ppm=10),
            network_profile=NetworkProfile(forward_mean_ms=1.0, reverse_mean_ms=15.0, jitter_std_ms=0.2),
            duration_s=60,
        ).run()
        self.assertGreater(abs(result["offset_error_ms"]), 5.0)
        self.assertGreater(result["hard_resync_count"], 0)

    def test_combined_scenario_is_deterministic(self):
        first = ReceiverSimulation(
            receiver_clock=ClockModel(rate_error_ppm=-25),
            network_profile=NetworkProfile(burst_loss_probability=0.08, burst_loss_min_packets=3, burst_loss_max_packets=6),
            seed=71,
            duration_s=60,
        ).run()
        second = ReceiverSimulation(
            receiver_clock=ClockModel(rate_error_ppm=-25),
            network_profile=NetworkProfile(burst_loss_probability=0.08, burst_loss_min_packets=3, burst_loss_max_packets=6),
            seed=71,
            duration_s=60,
        ).run()
        self.assertEqual(first, second)
        self.assertGreater(first["samples_lost"], 0)

    def test_adaptive_acquisition_reaches_stability_faster_than_fixed_cadence(self):
        kwargs = {
            "receiver_clock": ClockModel(rate_error_ppm=30),
            "network_profile": NetworkProfile(),
            "seed": 8,
            "duration_s": 60,
        }
        adaptive = ReceiverSimulation(**kwargs, adaptive_probe_schedule=True).run()
        fixed = ReceiverSimulation(**kwargs, adaptive_probe_schedule=False).run()

        self.assertTrue(adaptive["acquisition_stable_at_end"])
        self.assertLess(adaptive["fit_residual_rms_ms"], 0.25)
        self.assertLess(adaptive["convergence_time_s"], fixed["convergence_time_s"])
        self.assertGreater(adaptive["fast_retry_probe_count"], 0)
        self.assertGreater(adaptive["slow_probe_count"], 0)
        self.assertGreater(adaptive["samples_sent"], fixed["samples_sent"])

    def test_adaptive_acquisition_is_deterministic(self):
        kwargs = {
            "receiver_clock": ClockModel(rate_error_ppm=-40),
            "network_profile": NetworkProfile(jitter_std_ms=0.8),
            "seed": 29,
            "duration_s": 45,
        }
        first = ReceiverSimulation(**kwargs).run()
        second = ReceiverSimulation(**kwargs).run()
        self.assertEqual(first, second)

    def test_fixed_and_adaptive_schedules_share_network_events_at_common_times(self):
        kwargs = {
            "receiver_clock": ClockModel(rate_error_ppm=30),
            "network_profile": NetworkProfile(jitter_std_ms=1.5, loss_probability=0.1),
            "seed": 101,
            "duration_s": 60,
        }
        adaptive = ReceiverSimulation(**kwargs, adaptive_probe_schedule=True)
        fixed = ReceiverSimulation(**kwargs, adaptive_probe_schedule=False)
        self.assertEqual(adaptive._probe(3_000_000_000), fixed._probe(3_000_000_000))


if __name__ == "__main__":

    unittest.main()
