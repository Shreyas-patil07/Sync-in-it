# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import json
from pathlib import Path
import unittest

from sync_in_it_sim.estimator import ClockOffsetEstimator
from sync_in_it_sim.models import SyncSample
from sync_in_it_sim.timing import (
    DacClockModel,
    DriftController,
    TimelineAnchor,
    expected_sample_index,
)


VECTOR_PATH = Path(__file__).resolve().parents[2] / "protocol" / "vectors" / "m0_timing.json"


class GoldenVectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.vectors = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
        if cls.vectors.get("schema_version") != 1:
            raise RuntimeError("unsupported M0 golden-vector schema")

    def test_four_timestamp_vectors(self):
        for item in self.vectors["four_timestamp_cases"]:
            with self.subTest(item=item["name"]):
                sample = SyncSample(
                    t1_receiver_send_ns=item["t1_receiver_send_ns"],
                    t2_master_receive_ns=item["t2_master_receive_ns"],
                    t3_master_send_ns=item["t3_master_send_ns"],
                    t4_receiver_receive_ns=item["t4_receiver_receive_ns"],
                    received_at_true_ns=item["t4_receiver_receive_ns"],
                )
                self.assertEqual(sample.rtt_ns, item["expected_rtt_ns"])
                self.assertEqual(sample.offset_ns, item["expected_offset_ns"])

    def test_estimator_vectors(self):
        for item in self.vectors["estimator_cases"]:
            with self.subTest(item=item["name"]):
                samples = [SyncSample(**sample) for sample in item["samples"]]
                estimator = ClockOffsetEstimator(
                    window_ns=item["window_ns"],
                    low_rtt_fraction=item["low_rtt_fraction"],
                    minimum_skew_samples=item["minimum_skew_samples"],
                )
                result = estimator.estimate(
                    samples,
                    now_true_ns=item["now_true_ns"],
                    receiver_now_ns=item["receiver_now_ns"],
                )
                expected = item["expected"]
                self.assertAlmostEqual(result.offset_ns, expected["offset_ns"], delta=1e-3)
                self.assertEqual(result.receiver_skew_ppm is not None, expected["receiver_skew_ppm"] is not None)
                if expected["receiver_skew_ppm"] is not None:
                    self.assertAlmostEqual(result.receiver_skew_ppm, expected["receiver_skew_ppm"], delta=1e-9)
                self.assertAlmostEqual(result.rtt_p50_ms, expected["rtt_p50_ms"], delta=1e-9)
                self.assertAlmostEqual(result.residual_rms_ms, expected["residual_rms_ms"], delta=1e-9)
                self.assertEqual(result.samples_used, expected["samples_used"])
                self.assertEqual(result.samples_seen, expected["samples_seen"])
                self.assertEqual(result.selected_sample_span_ns, expected["selected_sample_span_ns"])

    def test_timeline_vectors(self):
        for item in self.vectors["timeline_cases"]:
            with self.subTest(item=item["name"]):
                anchor = TimelineAnchor(
                    master_time_ns=item["anchor_master_time_ns"],
                    sample_index=item["anchor_sample_index"],
                    playing=item["playing"],
                )
                result = expected_sample_index(
                    item["master_time_ns"], anchor, item["sample_rate_hz"]
                )
                self.assertEqual(result, item["expected_sample_index"])

    def test_dac_clock_vectors(self):
        for item in self.vectors["dac_clock_cases"]:
            with self.subTest(item=item["name"]):
                clock = DacClockModel(
                    sample_rate_hz=item["sample_rate_hz"],
                    rate_error_ppm=item["rate_error_ppm"],
                    frame_origin=item["frame_origin"],
                    time_origin_ns=item["time_origin_ns"],
                )
                self.assertEqual(clock.frame_at(item["local_time_ns"]), item["expected_frame_position"])
                self.assertEqual(clock.local_time_at(item["frame_position"]), item["expected_local_time_ns"])

    def test_drift_controller_vectors(self):
        controller = DriftController()
        for item in self.vectors["drift_controller_cases"]:
            with self.subTest(item=item["error_frames"]):
                result = controller.decide(item["error_frames"])
                self.assertEqual(result.action.value, item["expected_action"])
                self.assertEqual(result.error_frames, item["expected_error_frames"])


if __name__ == "__main__":
    unittest.main()
