# SPDX-License-Identifier: Apache-2.0
"""Measure relative acoustic arrival skew from a 48 kHz PCM16 Audacity WAV export."""

from __future__ import annotations

import argparse
import array
import csv
import json
import math
import statistics
import sys
import wave
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence

from tools.clicktrack.marker import (
    MARKER_DURATION_MS,
    SAMPLE_RATE_HZ,
    marker_frequency_hz,
    marker_template,
)


@dataclass(frozen=True)
class MarkerEvent:
    receiver_id: int
    sample_index: float
    time_seconds: float
    peak_score: float


@dataclass(frozen=True)
class MatchedEvent:
    event_index: int
    receiver_a_time_seconds: float
    receiver_b_time_seconds: float
    signed_skew_ms: float


def read_pcm16_mono(path: Path) -> array.array:
    with wave.open(str(path), "rb") as audio:
        channels = audio.getnchannels()
        sample_width = audio.getsampwidth()
        sample_rate = audio.getframerate()
        compression = audio.getcomptype()
        frame_count = audio.getnframes()
        if channels not in (1, 2):
            raise ValueError("WAV must be mono or stereo; found {} channels".format(channels))
        if sample_width != 2:
            raise ValueError("WAV must be signed 16-bit PCM; found {} bytes/sample".format(sample_width))
        if sample_rate != SAMPLE_RATE_HZ:
            raise ValueError("WAV must be {} Hz; found {} Hz".format(SAMPLE_RATE_HZ, sample_rate))
        if compression != "NONE":
            raise ValueError("WAV must be uncompressed PCM")
        raw = audio.readframes(frame_count)

    interleaved = array.array("h")
    interleaved.frombytes(raw)
    if sys.byteorder != "little":
        interleaved.byteswap()

    if channels == 1:
        return interleaved

    mono = array.array("h")
    mono.extend(
        max(-32768, min(32767, (interleaved[index] + interleaved[index + 1]) // 2))
        for index in range(0, len(interleaved), 2)
    )
    return mono


def sliding_tone_scores(
    samples: Sequence[int],
    frequency_hz: int,
    sample_rate_hz: int = SAMPLE_RATE_HZ,
    hop_samples: int = 240,
) -> list[tuple[int, float]]:
    """Compute a sliding 20 ms complex tone coefficient in O(samples) time."""
    window_frames = sample_rate_hz * MARKER_DURATION_MS // 1_000
    angular_step = 2.0 * math.pi * frequency_hz / sample_rate_hz
    step_cos = math.cos(angular_step)
    step_sin = math.sin(angular_step)
    rotation_cos = math.cos(angular_step * window_frames)
    rotation_sin = math.sin(angular_step * window_frames)

    ring = array.array("h", [0]) * window_frames
    phase_cos = 1.0
    phase_sin = 0.0
    in_phase = 0.0
    quadrature = 0.0
    scores: list[tuple[int, float]] = []

    for sample_index, sample in enumerate(samples):
        position = sample_index % window_frames
        if sample_index >= window_frames:
            old_sample = ring[position]
            old_cos = phase_cos * rotation_cos + phase_sin * rotation_sin
            old_sin = phase_sin * rotation_cos - phase_cos * rotation_sin
            in_phase -= old_sample * old_cos
            quadrature -= old_sample * old_sin

        ring[position] = sample
        in_phase += sample * phase_cos
        quadrature += sample * phase_sin

        if sample_index >= window_frames - 1:
            window_start = sample_index - window_frames + 1
            if window_start % hop_samples == 0:
                amplitude = 2.0 * math.hypot(in_phase, quadrature) / window_frames
                scores.append((window_start, amplitude))

        next_cos = phase_cos * step_cos - phase_sin * step_sin
        next_sin = phase_sin * step_cos + phase_cos * step_sin
        phase_cos, phase_sin = next_cos, next_sin
        if sample_index % 4096 == 4095:
            magnitude = math.hypot(phase_cos, phase_sin)
            phase_cos /= magnitude
            phase_sin /= magnitude

    return scores


def refine_marker_start(
    samples: Sequence[int],
    approximate_start: int,
    template: Sequence[int],
    radius_samples: int = 480,
    step_samples: int = 12,
) -> tuple[float, float]:
    """Refine one coarse hit with a short-window matched-filter search."""
    first = max(0, approximate_start - radius_samples)
    last = min(len(samples) - len(template), approximate_start + radius_samples)
    best_index = first
    best_score = -1.0

    for candidate in range(first, last + 1, step_samples):
        correlation = 0.0
        for offset, reference in enumerate(template):
            correlation += samples[candidate + offset] * reference
        score = abs(correlation)
        if score > best_score:
            best_score = score
            best_index = candidate

    return float(best_index), best_score


def detect_markers(
    samples: Sequence[int],
    receiver_id: int,
    hop_samples: int = 240,
) -> list[MarkerEvent]:
    frequency = marker_frequency_hz(receiver_id)
    scores = sliding_tone_scores(samples, frequency, hop_samples=hop_samples)
    if len(scores) < 3:
        return []

    amplitudes = [score for _, score in scores]
    baseline = statistics.median(amplitudes)
    deviations = [abs(score - baseline) for score in amplitudes]
    noise_mad = statistics.median(deviations)
    maximum = max(amplitudes)
    threshold = max(180.0, baseline + 8.0 * noise_mad, maximum * 0.25)
    template = marker_template(receiver_id)

    candidates: list[tuple[int, float]] = []
    for index in range(1, len(scores) - 1):
        start, score = scores[index]
        if score >= threshold and score >= scores[index - 1][1] and score >= scores[index + 1][1]:
            candidates.append((start, score))

    # Collapse repeated local maxima from one 20 ms marker to one event.
    selected: list[tuple[int, float]] = []
    minimum_separation = SAMPLE_RATE_HZ // 2
    for candidate in candidates:
        if selected and candidate[0] - selected[-1][0] < minimum_separation:
            if candidate[1] > selected[-1][1]:
                selected[-1] = candidate
        else:
            selected.append(candidate)

    events: list[MarkerEvent] = []
    for coarse_start, _ in selected:
        refined_start, peak_score = refine_marker_start(samples, coarse_start, template)
        events.append(MarkerEvent(
            receiver_id=receiver_id,
            sample_index=refined_start,
            time_seconds=refined_start / SAMPLE_RATE_HZ,
            peak_score=peak_score,
        ))
    return events


def match_events(
    events_a: Sequence[MarkerEvent],
    events_b: Sequence[MarkerEvent],
    maximum_pair_delta_seconds: float = 0.4,
) -> list[MatchedEvent]:
    matched: list[MatchedEvent] = []
    index_a = 0
    index_b = 0

    while index_a < len(events_a) and index_b < len(events_b):
        time_a = events_a[index_a].time_seconds
        time_b = events_b[index_b].time_seconds
        delta = time_b - time_a

        if abs(delta) <= maximum_pair_delta_seconds:
            matched.append(MatchedEvent(
                event_index=len(matched) + 1,
                receiver_a_time_seconds=time_a,
                receiver_b_time_seconds=time_b,
                signed_skew_ms=delta * 1_000.0,
            ))
            index_a += 1
            index_b += 1
        elif delta < 0:
            index_b += 1
        else:
            index_a += 1

    return matched


def percentile(values: Sequence[float], percentile_value: float) -> float:
    if not values:
        raise ValueError("cannot calculate percentile of an empty set")
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile_value
    lower = int(math.floor(position))
    upper = int(math.ceil(position))
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def drift_slope_ppm(events: Sequence[MatchedEvent]) -> float | None:
    if len(events) < 2:
        return None
    x_values = [
        (event.receiver_a_time_seconds + event.receiver_b_time_seconds) / 2.0
        for event in events
    ]
    y_values = [event.signed_skew_ms / 1_000.0 for event in events]
    mean_x = statistics.mean(x_values)
    mean_y = statistics.mean(y_values)
    variance = sum((value - mean_x) ** 2 for value in x_values)
    if variance == 0.0:
        return None
    covariance = sum(
        (x_value - mean_x) * (y_value - mean_y)
        for x_value, y_value in zip(x_values, y_values)
    )
    return covariance / variance * 1_000_000.0


def analyze_samples(
    samples: Sequence[int],
    receiver_ids: Sequence[int] = (1, 2),
) -> dict:
    if len(receiver_ids) != 2 or receiver_ids[0] == receiver_ids[1]:
        raise ValueError("exactly two distinct receiver IDs are required")

    receiver_a, receiver_b = receiver_ids
    events_a = detect_markers(samples, receiver_a)
    events_b = detect_markers(samples, receiver_b)
    matched = match_events(events_a, events_b)
    if not matched:
        raise ValueError(
            "no matching marker events detected; check receiver IDs, recording level and WAV format"
        )

    signed = [event.signed_skew_ms for event in matched]
    absolute = [abs(value) for value in signed]
    return {
        "sample_rate_hz": SAMPLE_RATE_HZ,
        "receiver_a": {
            "receiver_id": receiver_a,
            "marker_frequency_hz": marker_frequency_hz(receiver_a),
            "detected_events": len(events_a),
        },
        "receiver_b": {
            "receiver_id": receiver_b,
            "marker_frequency_hz": marker_frequency_hz(receiver_b),
            "detected_events": len(events_b),
        },
        "matched_events": len(matched),
        "skew_definition": "receiver B arrival time minus receiver A arrival time",
        "mean_signed_skew_ms": statistics.mean(signed),
        "median_signed_skew_ms": statistics.median(signed),
        "p95_absolute_skew_ms": percentile(absolute, 0.95),
        "maximum_absolute_skew_ms": max(absolute),
        "drift_slope_ppm": drift_slope_ppm(matched),
        "events": [asdict(event) for event in matched],
    }


def write_outputs(report: dict, output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "skew-report.json"
    csv_path = output_dir / "skew-events.csv"
    json_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    with csv_path.open("w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=[
            "event_index",
            "receiver_a_time_seconds",
            "receiver_b_time_seconds",
            "signed_skew_ms",
        ])
        writer.writeheader()
        writer.writerows(report["events"])

    print("Matched events: {}".format(report["matched_events"]))
    print("Mean signed skew (B - A): {:+.3f} ms".format(report["mean_signed_skew_ms"]))
    print("Median signed skew (B - A): {:+.3f} ms".format(report["median_signed_skew_ms"]))
    print("95th percentile absolute skew: {:.3f} ms".format(report["p95_absolute_skew_ms"]))
    print("Maximum absolute skew: {:.3f} ms".format(report["maximum_absolute_skew_ms"]))
    if report["drift_slope_ppm"] is not None:
        print("Relative drift slope: {:+.2f} ppm".format(report["drift_slope_ppm"]))
    print("JSON report: {}".format(json_path))
    print("CSV events: {}".format(csv_path))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Audacity-exported 48 kHz PCM16 WAV")
    parser.add_argument("--receiver-ids", type=int, nargs=2, default=[1, 2], metavar=("A", "B"))
    parser.add_argument("--output-dir", type=Path, default=Path("test-results/acoustic-skew"))
    args = parser.parse_args()

    samples = read_pcm16_mono(args.input)
    if len(samples) < SAMPLE_RATE_HZ * 2:
        parser.error("recording must contain at least two seconds of audio")

    report = analyze_samples(samples, args.receiver_ids)
    write_outputs(report, args.output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
