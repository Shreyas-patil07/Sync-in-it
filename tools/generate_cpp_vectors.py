# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import argparse
import json
from pathlib import Path


def cpp_string(value: str) -> str:
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def cpp_double(value: float | int) -> str:
    return f"{float(value):.17g}"


def sample_initializer(sample: dict[str, int]) -> str:
    return (
        "{" + ", ".join(str(sample[key]) for key in (
            "t1_receiver_send_ns",
            "t2_master_receive_ns",
            "t3_master_send_ns",
            "t4_receiver_receive_ns",
            "received_at_true_ns",
        )) + "}"
    )


def generate(data: dict) -> str:
    lines = [
        "// Generated from protocol/vectors/m0_timing.json. Do not edit.",
        "// SPDX-License-Identifier: Apache-2.0",
        "#pragma once",
        "#include \"syit/types.h\"",
        "#include \"syit/timing.h\"",
        "#include <cstdint>",
        "#include <vector>",
        "namespace golden {",
        "struct FourTimestampCase { const char* name; syit::SyncSample sample; std::int64_t expected_rtt_ns; std::int64_t expected_offset_ns; };",
        "struct EstimatorCase { const char* name; std::vector<syit::SyncSample> samples; std::int64_t now_true_ns; std::int64_t receiver_now_ns; syit::EstimatorConfig config; double expected_offset_ns; bool has_expected_skew; double expected_skew_ppm; double expected_rtt_p50_ms; double expected_residual_rms_ms; std::size_t expected_samples_used; std::size_t expected_samples_seen; std::int64_t expected_selected_sample_span_ns; };",
        "struct TimelineCase { const char* name; std::int64_t master_time_ns; syit::TimelineAnchor anchor; std::int64_t sample_rate_hz; std::int64_t expected_sample_index; };",
        "struct DacClockCase { const char* name; std::int64_t sample_rate_hz; double rate_error_ppm; std::int64_t frame_origin; std::int64_t time_origin_ns; std::int64_t local_time_ns; std::int64_t expected_frame_position; std::int64_t frame_position; std::int64_t expected_local_time_ns; };",
        "struct DriftCase { std::int64_t error_frames; syit::DriftAction expected_action; std::int64_t expected_error_frames; };",
        "inline const std::vector<FourTimestampCase> kFourTimestampCases = {",
    ]
    for case in data["four_timestamp_cases"]:
        sample = {
            "t1_receiver_send_ns": case["t1_receiver_send_ns"],
            "t2_master_receive_ns": case["t2_master_receive_ns"],
            "t3_master_send_ns": case["t3_master_send_ns"],
            "t4_receiver_receive_ns": case["t4_receiver_receive_ns"],
            "received_at_true_ns": case["t4_receiver_receive_ns"],
        }
        lines.append(
            "{" + ", ".join((
                cpp_string(case["name"]),
                sample_initializer(sample),
                str(case["expected_rtt_ns"]),
                str(case["expected_offset_ns"]),
            )) + "},"
        )
    lines.append("};")
    lines.append("inline const std::vector<EstimatorCase> kEstimatorCases = {")
    for case in data["estimator_cases"]:
        samples = ", ".join(sample_initializer(item) for item in case["samples"])
        expected = case["expected"]
        has_skew = expected["receiver_skew_ppm"] is not None
        skew = cpp_double(expected["receiver_skew_ppm"] or 0.0)
        values = [
            cpp_string(case["name"]),
            "{" + samples + "}",
            str(case["now_true_ns"]),
            str(case["receiver_now_ns"]),
            "{" + ", ".join((
                str(case["window_ns"]),
                cpp_double(case["low_rtt_fraction"]),
                str(case["minimum_skew_samples"]),
            )) + "}",
            cpp_double(expected["offset_ns"]),
            "true" if has_skew else "false",
            skew,
            cpp_double(expected["rtt_p50_ms"]),
            cpp_double(expected["residual_rms_ms"]),
            str(expected["samples_used"]),
            str(expected["samples_seen"]),
            str(expected["selected_sample_span_ns"]),
        ]
        lines.append("{" + ", ".join(values) + "},")
    lines.append("};")
    lines.append("inline const std::vector<TimelineCase> kTimelineCases = {")
    for case in data["timeline_cases"]:
        values = [
            cpp_string(case["name"]),
            str(case["master_time_ns"]),
            "{" + ", ".join((
                str(case["anchor_master_time_ns"]),
                str(case["anchor_sample_index"]),
                "true" if case["playing"] else "false",
            )) + "}",
            str(case["sample_rate_hz"]),
            str(case["expected_sample_index"]),
        ]
        lines.append("{" + ", ".join(values) + "},")
    lines.append("};")
    lines.append("inline const std::vector<DacClockCase> kDacClockCases = {")
    for case in data["dac_clock_cases"]:
        values = [
            cpp_string(case["name"]),
            str(case["sample_rate_hz"]),
            cpp_double(case["rate_error_ppm"]),
            str(case["frame_origin"]),
            str(case["time_origin_ns"]),
            str(case["local_time_ns"]),
            str(case["expected_frame_position"]),
            str(case["frame_position"]),
            str(case["expected_local_time_ns"]),
        ]
        lines.append("{" + ", ".join(values) + "},")
    lines.append("};")
    action_map = {
        "none": "None",
        "drop_one_frame": "DropOneFrame",
        "duplicate_one_frame": "DuplicateOneFrame",
        "hard_resync": "HardResync",
    }
    lines.append("inline const std::vector<DriftCase> kDriftCases = {")
    for case in data["drift_controller_cases"]:
        values = [
            str(case["error_frames"]),
            "syit::DriftAction::" + action_map[case["expected_action"]],
            str(case["expected_error_frames"]),
        ]
        lines.append("{" + ", ".join(values) + "},")
    lines.extend(["};", "}  // namespace golden", ""])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    data = json.loads(args.input.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise SystemExit("unsupported golden-vector schema version")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generate(data), encoding="utf-8")


if __name__ == "__main__":
    main()
