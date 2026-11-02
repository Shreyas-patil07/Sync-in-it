# SPDX-License-Identifier: Apache-2.0
import unittest

from sync_in_it_sim.models import NetworkProfile
from sync_in_it_sim.network import SimulatedNetwork


class NetworkProfileTests(unittest.TestCase):
    def test_rejects_invalid_probabilities(self):
        with self.assertRaises(ValueError):
            NetworkProfile(loss_probability=1.1)
        with self.assertRaises(ValueError):
            NetworkProfile(burst_loss_probability=-0.1)

    def test_rejects_non_finite_delay(self):
        with self.assertRaises(ValueError):
            NetworkProfile(forward_mean_ms=float("inf"))
        with self.assertRaises(ValueError):
            NetworkProfile(jitter_std_ms=float("nan"))

    def test_rejects_negative_delay_or_jitter(self):
        with self.assertRaises(ValueError):
            NetworkProfile(jitter_std_ms=-0.1)
        with self.assertRaises(ValueError):
            NetworkProfile(processing_ms=-1.0)

    def test_rejects_invalid_burst_lengths(self):
        with self.assertRaises(ValueError):
            NetworkProfile(burst_loss_min_packets=0)
        with self.assertRaises(ValueError):
            NetworkProfile(burst_loss_min_packets=5, burst_loss_max_packets=4)

    def test_burst_loss_drops_a_contiguous_run(self):
        class OneBurstRandom:
            def __init__(self):
                self.values = iter([1.0, 0.0, 1.0, 1.0])

            def random(self):
                return next(self.values, 1.0)

            @staticmethod
            def randint(_minimum, _maximum):
                return 4

        network = SimulatedNetwork(
            NetworkProfile(
                burst_loss_probability=0.5,
                burst_loss_min_packets=4,
                burst_loss_max_packets=4,
            ),
            OneBurstRandom(),
        )
        self.assertEqual([network.packet_lost() for _ in range(6)], [True, True, True, True, False, False])


if __name__ == "__main__":
    unittest.main()
