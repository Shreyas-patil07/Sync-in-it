# SPDX-License-Identifier: Apache-2.0
import unittest

from sync_in_it_sim.estimator import ClockOffsetEstimator
from sync_in_it_sim.models import ClockModel, NetworkProfile, SyncSample
from sync_in_it_sim.simulator import ReceiverSimulation


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
        self.assertEqual(result["samples_sent"], 35)
        self.assertLess(abs(result["offset_error_ms"]), 1.0)

    def test_estimator_rejects_empty_samples(self):
        with self.assertRaises(ValueError):
            ClockOffsetEstimator().estimate([], now_true_ns=0)


if __name__ == "__main__":
    unittest.main()
