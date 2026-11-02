# SPDX-License-Identifier: Apache-2.0
import unittest

from sync_in_it_sim.lan import SharedWifiConfig, SharedWifiSimulation
from sync_in_it_sim.models import ClockModel


class SharedWifiSimulationTests(unittest.TestCase):
    def test_config_rejects_invalid_rates_and_queue_limits(self):
        with self.assertRaises(ValueError):
            SharedWifiConfig(phy_rate_mbps=0)
        with self.assertRaises(ValueError):
            SharedWifiConfig(retry_probability=1.0)
        with self.assertRaises(ValueError):
            SharedWifiConfig(max_queue_packets=8, control_reserve_packets=8)

    def test_single_receiver_delivers_audio_and_time_sync(self):
        result = SharedWifiSimulation(receiver_count=1, duration_s=4, seed=11).run()
        self.assertEqual(result["audio_chunks_generated"], 200)
        self.assertGreater(result["audio_chunks_delivered"], 0)
        self.assertGreater(result["probe_count"], 20)
        self.assertGreater(result["probe_success_count"], 0)
        self.assertLess(result["audio_chunk_loss_pct"], 1.0)

    def test_run_is_deterministic(self):
        kwargs = {
            "receiver_count": 3,
            "duration_s": 5,
            "seed": 17,
            "config": SharedWifiConfig(phy_rate_mbps=36, retry_probability=0.05),
        }
        self.assertEqual(SharedWifiSimulation(**kwargs).run(), SharedWifiSimulation(**kwargs).run())

    def test_control_packets_receive_priority_over_waiting_audio(self):
        result = SharedWifiSimulation(
            receiver_count=6, duration_s=8, seed=23,
            config=SharedWifiConfig(
                phy_rate_mbps=24, retry_probability=0.03,
                max_queue_packets=128, control_reserve_packets=24,
            ),
        ).run()
        self.assertGreater(result["audio_queue_wait_p95_ms"], 0)
        self.assertLess(result["control_queue_wait_p95_ms"], result["audio_queue_wait_p95_ms"])
        self.assertEqual(result["queue_drops_control"], 0)

    def test_overloaded_medium_reports_audio_deadline_misses(self):
        result = SharedWifiSimulation(
            receiver_count=10, duration_s=8, seed=31,
            config=SharedWifiConfig(
                phy_rate_mbps=12, retry_probability=0.08,
                max_queue_packets=96, control_reserve_packets=24,
            ),
        ).run()
        self.assertGreater(result["medium_utilization_pct"], 90.0)
        self.assertGreater(result["audio_deadline_miss_pct"] + result["audio_chunk_loss_pct"], 0.0)

    def test_adaptive_probe_scheduler_backs_off_after_stable_acquisition(self):
        result = SharedWifiSimulation(
            receiver_count=1,
            duration_s=24,
            seed=53,
            config=SharedWifiConfig(phy_rate_mbps=72, retry_probability=0.0),
            adaptive_probes=True,
        ).run()
        self.assertEqual(result["acquisition_stable_receivers"], 1)
        self.assertGreater(result["probe_fast_retry_count"], 0)
        self.assertGreater(result["probe_steady_count"], 0)

    def test_adaptive_sync_increases_probe_load_without_changing_audio_workload(self):
        clocks = [
            ClockModel(offset_ns=-1_000_000, rate_error_ppm=30),
            ClockModel(offset_ns=1_000_000, rate_error_ppm=27),
        ]
        kwargs = {
            "receiver_count": 2, "duration_s": 12, "seed": 41,
            "config": SharedWifiConfig(phy_rate_mbps=36, retry_probability=0.01),
            "receiver_clocks": clocks,
        }
        fixed = SharedWifiSimulation(**kwargs, adaptive_probes=False).run()
        adaptive = SharedWifiSimulation(**kwargs, adaptive_probes=True).run()
        self.assertGreater(adaptive["probe_count"], fixed["probe_count"])
        self.assertEqual(adaptive["audio_chunks_generated"], fixed["audio_chunks_generated"])
        self.assertEqual(adaptive["audio_segments_sent"], fixed["audio_segments_sent"])


if __name__ == "__main__":
    unittest.main()
