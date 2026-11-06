# SPDX-License-Identifier: Apache-2.0
"""Generate mono WAV references for the receiver-specific acoustic markers."""

from __future__ import annotations

import argparse
import array
import json
import sys
import wave
from pathlib import Path

from tools.clicktrack.marker import (
    MARKER_DURATION_MS,
    SAMPLE_RATE_HZ,
    marker_frequency_hz,
    marker_template,
)


def write_reference(path: Path, receiver_id: int, duration_seconds: int) -> None:
    template = marker_template(receiver_id)
    silence = array.array("h", [0]) * SAMPLE_RATE_HZ
    marker = array.array("h", template)
    if sys.byteorder != "little":
        silence.byteswap()
        marker.byteswap()

    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE_HZ)
        for _ in range(duration_seconds):
            output.writeframesraw(marker.tobytes())
            output.writeframesraw(silence[:SAMPLE_RATE_HZ - len(marker)].tobytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("recordings/reference"))
    parser.add_argument("--receiver-ids", type=int, nargs="+", default=[1, 2])
    parser.add_argument("--duration-seconds", type=int, default=120)
    args = parser.parse_args()

    if args.duration_seconds < 2:
        parser.error("--duration-seconds must be at least 2")
    if len(set(args.receiver_ids)) != len(args.receiver_ids):
        parser.error("--receiver-ids must be unique")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "sample_rate_hz": SAMPLE_RATE_HZ,
        "channels": 1,
        "sample_format": "PCM16 little-endian",
        "duration_seconds": args.duration_seconds,
        "marker_duration_ms": MARKER_DURATION_MS,
        "marker_every_seconds": 1,
        "receivers": [],
    }

    for receiver_id in args.receiver_ids:
        frequency = marker_frequency_hz(receiver_id)
        filename = "speaker-{}-{}Hz.wav".format(receiver_id, frequency)
        write_reference(args.output_dir / filename, receiver_id, args.duration_seconds)
        manifest["receivers"].append({
            "receiver_id": receiver_id,
            "marker_frequency_hz": frequency,
            "reference_file": filename,
        })

    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Wrote {} receiver references to {}".format(len(args.receiver_ids), args.output_dir))
    print("Manifest: {}".format(manifest_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
