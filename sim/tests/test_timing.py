# SPDX-License-Identifier: Apache-2.0
import unittest

from sync_in_it_sim.timing import (
    DacClockModel,
    DriftAction,
    DriftController,
    TimelineAnchor,
    expected_sample_index,
)


class TimingTests(unittest.TestCase):
    def test_timeline_maps_master_time_to_sample_index(self):
        anchor = TimelineAnchor(master_time_ns=1_000_000_000, sample_index=0)
        self.assertEqual(expected_sample_index(2_000_000_000, anchor), 48_000)

    def test_paused_timeline_holds_sample_index(self):
        anchor = TimelineAnchor(master_time_ns=1_000_000_000, sample_index=1234, playing=False)
        self.assertEqual(expected_sample_index(9_000_000_000, anchor), 1234)

    def test_dac_clock_round_trip(self):
        dac = DacClockModel(rate_error_ppm=40, time_origin_ns=1_000_000_000)
        frame = dac.frame_at(3_000_000_000)
        self.assertAlmostEqual(dac.local_time_at(frame), 3_000_000_000, delta=20_834)

    def test_drift_controller_thresholds(self):
        controller = DriftController()
        self.assertEqual(controller.decide(5).action, DriftAction.NONE)
        self.assertEqual(controller.decide(6).action, DriftAction.DROP_ONE_FRAME)
        self.assertEqual(controller.decide(-6).action, DriftAction.DUPLICATE_ONE_FRAME)
        self.assertEqual(controller.decide(241).action, DriftAction.HARD_RESYNC)


if __name__ == "__main__":
    unittest.main()
