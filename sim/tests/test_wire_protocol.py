# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations
import json
from pathlib import Path
import unittest

from sync_in_it_sim.wire import (
    Frame, FrameStreamDecoder, ProtocolError, TimeSyncPacket, decode_frame, decode_time_sync_packet,
    encode_frame, encode_time_sync_request, encode_time_sync_response,
)

VECTOR_PATH = Path(__file__).resolve().parents[2] / "protocol" / "vectors" / "wire_protocol.json"


class WireProtocolVectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.vectors = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
        if cls.vectors.get("schema_version") != 1:
            raise RuntimeError("unsupported wire-protocol vector schema")

    def _assert_frame_vectors(self, cases):
        for item in cases:
            with self.subTest(name=item["name"]):
                payload = bytes.fromhex(item["payload_hex"])
                expected = bytes.fromhex(item["wire_hex"])
                self.assertEqual(encode_frame(item["type"], item["flags"], payload), expected)
                self.assertEqual(decode_frame(expected), Frame(item["type"], item["flags"], payload))

    def test_all_control_frames(self):
        self.assertEqual(len(self.vectors["control_frames"]), 15)
        self._assert_frame_vectors(self.vectors["control_frames"])

    def test_audio_pcm_gap_and_silence_frames(self):
        self._assert_frame_vectors(self.vectors["audio_frames"])
        payload = bytes.fromhex(self.vectors["audio_frames"][0]["payload_hex"])
        self.assertEqual(int.from_bytes(payload[16:18], "little"), 960)
        self.assertEqual(payload[20], 1)
        self.assertEqual(len(payload[21:]), 3840)
        silence = bytes.fromhex(self.vectors["audio_frames"][1]["payload_hex"])
        self.assertEqual(silence[20], 2)
        self.assertEqual(len(silence), 21)

    def test_unknown_safely_framed_message_is_preserved(self):
        self._assert_frame_vectors(self.vectors["unknown_frames"])

    def test_invalid_frames_are_rejected(self):
        for item in self.vectors["invalid_frames"]:
            with self.subTest(name=item["name"]), self.assertRaises(ProtocolError):
                decode_frame(bytes.fromhex(item["wire_hex"]))

    def test_oversized_encoder_payload_is_rejected(self):
        with self.assertRaises(ProtocolError):
            encode_frame(1, 0, bytes(65_535))

    def test_udp_request_and_response_vectors(self):
        for item in self.vectors["time_sync_packets"]:
            with self.subTest(name=item["name"]):
                expected = bytes.fromhex(item["wire_hex"])
                packet = TimeSyncPacket(item["type"], 1, item["session_id"], item["receiver_id"],
                                         item["sequence"], item["t1_ns"],
                                         item["t2_ns"] or None, item["t3_ns"] or None)
                encoded = encode_time_sync_request(packet) if item["type"] == 1 else encode_time_sync_response(packet)
                self.assertEqual(encoded, expected)
                decoded = decode_time_sync_packet(expected)
                self.assertEqual((decoded.packet_type, decoded.session_id, decoded.receiver_id,
                                  decoded.sequence, decoded.t1_ns),
                                 (item["type"], item["session_id"], item["receiver_id"],
                                  item["sequence"], item["t1_ns"]))
                if item["type"] == 2:
                    self.assertEqual((decoded.t2_ns, decoded.t3_ns), (item["t2_ns"], item["t3_ns"]))

    def test_invalid_udp_packets_are_rejected(self):
        bad_packets = (b"", bytes(37), bytes(39), b"XX" + bytes(36), bytes((0x53, 0x54, 2, 1)) + bytes(34), bytes((0x53, 0x54, 1, 3)) + bytes(34))
        for packet in bad_packets:
            with self.subTest(prefix=packet[:4]), self.assertRaises(ProtocolError):
                decode_time_sync_packet(packet)


    def test_incremental_framer_handles_every_split_point(self):
        expected = Frame(0x42, 0x03, b"fragmented-payload")
        wire = encode_frame(expected.message_type, expected.flags, expected.payload)
        for split in range(len(wire) + 1):
            with self.subTest(split=split):
                decoder = FrameStreamDecoder()
                frames = decoder.feed(wire[:split])
                frames.extend(decoder.feed(wire[split:]))
                self.assertEqual(frames, [expected])
                self.assertEqual(decoder.buffered_bytes, 0)

    def test_incremental_framer_handles_bytewise_and_coalesced_reads(self):
        expected = [
            Frame(0x01, 0x00, b"first"),
            Frame(0x7F, 0x80, b"unknown-but-framed"),
            Frame(0x80, 0x00, b"audio"),
        ]
        wire = b"".join(encode_frame(frame.message_type, frame.flags, frame.payload)
                        for frame in expected)
        decoder = FrameStreamDecoder()
        frames = []
        for byte in wire:
            frames.extend(decoder.feed(bytes((byte,))))
        self.assertEqual(frames, expected)
        self.assertEqual(decoder.buffered_bytes, 0)

        decoder = FrameStreamDecoder()
        frames = decoder.feed(wire[:len(wire) - 3])
        self.assertEqual(frames, expected[:2])
        self.assertEqual(decoder.buffered_bytes, 3)
        frames.extend(decoder.feed(wire[len(wire) - 3:]))
        self.assertEqual(frames, expected)
        self.assertEqual(decoder.buffered_bytes, 0)

    def test_incremental_framer_accepts_aggregate_input_larger_than_frame_limit(self):
        payload = bytes(40_000)
        first = encode_frame(0x40, 0, payload)
        second = encode_frame(0x41, 0, payload)
        self.assertGreater(len(first) + len(second), MAX_FRAME_SIZE + 4)
        decoder = FrameStreamDecoder()
        frames = decoder.feed(first + second)
        self.assertEqual(len(frames), 2)
        self.assertEqual(frames[0].payload, payload)
        self.assertEqual(frames[1].payload, payload)

    def test_incremental_framer_accepts_maximum_frame_with_bounded_retention(self):
        payload = bytes(MAX_FRAME_SIZE - 2)
        wire = encode_frame(0x55, 0, payload)
        self.assertEqual(len(wire), MAX_FRAME_SIZE + 4)
        decoder = FrameStreamDecoder()
        self.assertEqual(decoder.feed(wire[:4]), [])
        self.assertEqual(decoder.buffered_bytes, 4)
        frames = decoder.feed(wire[4:])
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].payload, payload)
        self.assertEqual(decoder.buffered_bytes, 0)

    def test_incremental_framer_rejects_invalid_lengths_and_recovers_after_reset(self):
        valid = encode_frame(0x22, 0, b"reusable")
        for invalid_header in (
            (1).to_bytes(4, "little"),
            (MAX_FRAME_SIZE + 1).to_bytes(4, "little"),
        ):
            with self.subTest(length=int.from_bytes(invalid_header, "little")):
                decoder = FrameStreamDecoder()
                with self.assertRaises(ProtocolError):
                    decoder.feed(invalid_header)
                self.assertEqual(decoder.buffered_bytes, 0)
                self.assertEqual(decoder.feed(valid), [Frame(0x22, 0, b"reusable")])


if __name__ == "__main__":
    unittest.main()
