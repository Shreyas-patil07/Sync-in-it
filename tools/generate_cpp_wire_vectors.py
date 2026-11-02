# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations
import argparse
import json
from pathlib import Path


def byte_vector(hex_value: str) -> str:
    if len(hex_value) % 2:
        raise ValueError("hex input must contain complete bytes")
    return "{" + ", ".join(
        f"0x{hex_value[index:index + 2]}" for index in range(0, len(hex_value), 2)
    ) + "}"


def generate(data: dict) -> str:
    lines = [
        "// Generated from protocol/vectors/wire_protocol.json. Do not edit.",
        "// SPDX-License-Identifier: Apache-2.0",
        "#pragma once",
        "#include <cstdint>",
        "#include <vector>",
        "namespace wire_golden {",
        "struct ByteVector { const char* name; std::vector<std::uint8_t> bytes; };",
        "struct TimeSyncVector { const char* name; std::vector<std::uint8_t> bytes; std::uint8_t type; std::uint32_t session_id; std::uint16_t receiver_id; std::uint32_t sequence; std::int64_t t1_ns; std::int64_t t2_ns; std::int64_t t3_ns; };",
        "inline const std::vector<ByteVector> kFrames = {",
    ]
    for item in data["control_frames"] + data["audio_frames"] + data["unknown_frames"]:
        lines.append("{" + ", ".join((f'"{item["name"]}"', byte_vector(item["wire_hex"]))) + "},")
    lines.extend(["};", "inline const std::vector<ByteVector> kInvalidFrames = {"])
    for item in data["invalid_frames"]:
        lines.append("{" + ", ".join((f'"{item["name"]}"', byte_vector(item["wire_hex"]))) + "},")
    lines.extend(["};", "inline const std::vector<TimeSyncVector> kTimeSync = {"])
    for item in data["time_sync_packets"]:
        values = [
            f'"{item["name"]}"', byte_vector(item["wire_hex"]), str(item["type"]),
            str(item["session_id"]), str(item["receiver_id"]), str(item["sequence"]),
            str(item["t1_ns"]), str(item["t2_ns"]), str(item["t3_ns"]),
        ]
        lines.append("{" + ", ".join(values) + "},")
    lines.extend(["};", "}  // namespace wire_golden", ""])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    data = json.loads(args.input.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise SystemExit("unsupported wire-protocol vector schema")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generate(data), encoding="utf-8")


if __name__ == "__main__":
    main()
