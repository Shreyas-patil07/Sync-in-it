# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import array
import math
import sys
import tempfile
import unittest
import wave
from pathlib import Path

from tools.analyze.analyze import analyze_samples, read_pcm16_mono
from tools.clicktrack.marker import MARKER_FRAMES, SAMPLE_RATE_HZ, marker_template


class AcousticMeasurementTests(unittest.TestCase):
    def make_recording(self, skew_samples: int, duration_seconds: int = 8) -> array.array:
        samples = array.array("h", [0]) * (SAMPLE_RATE_HZ * duration_seconds)
        marker_a = marker_template(1)
        marker_b = marker_template(2)

        for second in range(1, duration_seconds - 1):
            start_a = second * SAMPLE_RATE_HZ
            start_b = start_a + skew_samples
            for offset in range(MARKER_FRAMES):
                index_a = start_a + offset
                index_b = start_b + offset
                samples[index_a] = max(-32768, min(32767, samples[index_a] + marker_a[offset]))
                samples[index_b] = max(-32768, min(32767, samples[index_b] + marker_b[offset]))
        return samples

    def test_recovers_positive_relative_skew(self) -> None:
        samples = self.make_recording(round(0.008 * SAMPLE_RATE_HZ))
        report = analyze_samples(samples, (1, 2))
        self.assertGreaterEqual(report["matched_events"], 5)
        self.assertAlmostEqual(report["mean_signed_skew_ms"], 8.0, delta=0.7)
        self.assertLess(report["p95_absolute_skew_ms"], 9.0)

    def test_recovers_negative_relative_skew(self) -> None:
        samples = self.make_recording(-round(0.006 * SAMPLE_RATE_HZ))
        report = analyze_samples(samples, (1, 2))
        self.assertLess(report["mean_signed_skew_ms"], 0)
        self.assertAlmostEqual(report["mean_signed_skew_ms"], -6.0, delta=0.7)

    def test_android_marker_constants_match_reference(self) -> None:
        root = Path(__file__).resolve().parents[2]
        config_path = root / "android/app/src/main/kotlin/com/syncinit/app/PreviewConfig.kt"
        config = config_path.read_text(encoding="utf-8")
        expected = {
            "SAMPLE_RATE_HZ": SAMPLE_RATE_HZ,
            "ACOUSTIC_MARKER_DURATION_MS": 20,
            "ACOUSTIC_MARKER_FRAMES": MARKER_FRAMES,
            "ACOUSTIC_MARKER_BASE_HZ": 1_500,
            "ACOUSTIC_MARKER_STEP_HZ": 750,
            "ACOUSTIC_MARKER_AMPLITUDE": 2_500,
            "MAX_SPEAKERS": 8,
        }
        import re
        for key, value in expected.items():
            match = re.search(r"const val " + key + r" = ([0-9_]+)", config)
            self.assertIsNotNone(match, "Missing PreviewConfig constant " + key)
            self.assertEqual(int(match.group(1).replace("_", "")), value, key)

    def test_pcm16_wav_reader_downmixes_stereo(self) -> None
        samples = self.make_recording(round(0.005 * SAMPLE_RATE_HZ))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stereo.wav"
            interleaved = array.array("h")
            for sample in samples:
                interleaved.extend((sample, sample))
            if sys.byteorder != "little":
                interleaved.byteswap()
            with wave.open(str(path), "wb") as output:
                output.setnchannels(2)
                output.setsampwidth(2)
                output.setframerate(SAMPLE_RATE_HZ)
                output.writeframes(interleaved.tobytes())

            loaded = read_pcm16_mono(path)
            report = analyze_samples(loaded, (1, 2))
            self.assertAlmostEqual(report["mean_signed_skew_ms"], 5.0, delta=0.7)


if __name__ == "__main__":
    unittest.main()
