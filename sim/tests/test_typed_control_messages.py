# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import json
from pathlib import Path
import unittest

from sync_in_it_sim.wire import Frame, ProtocolError
from sync_in_it_sim.messages import (
    Anchor, CalibAck, CalibChirp, ErrorMessage, Hello, Mute, Report, Volume,
    decode_control_message, encode_control_message,
)

VECTOR_PATH = Path(__file__).resolve().parents[2] / "protocol" / "vectors" / "wire_protocol.json"


class TypedControlMessageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.vectors = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))

    def test_all_control_messages_round_trip_shared_vectors(self):
        for item in self.vectors["control_frames"]:
            with self.subTest(name=item["name"]):
                frame = Frame(item["type"], item["flags"], bytes.fromhex(item["payload_hex"]))
                decoded = decode_control_message(frame)
                self.assertEqual(encode_control_message(decoded, frame.flags), frame)

    def test_invalid_control_payload_vectors_are_rejected(self):
        for item in self.vectors["invalid_control_payloads"]:
            with self.subTest(name=item["name"]):
                frame = Frame(item["type"], 0, bytes.fromhex(item["payload_hex"]))
                with self.assertRaises(ProtocolError):
                    decode_control_message(frame)

    def test_utf8_model_and_detail_are_preserved(self):
        model = Hello(0x0100, bytes(range(16)), 1, 0x0102, 5, "Pixel 7a — receiver")
        self.assertEqual(decode_control_message(encode_control_message(model)), model)
        error = ErrorMessage(7, "network timeout — receiver")
        self.assertEqual(decode_control_message(encode_control_message(error)), error)

    def test_encoder_rejects_protocol_constraints(self):
        with self.assertRaises(ProtocolError):
            encode_control_message(Hello(0x0100, b"short", 1, 1, 0, "device"))
        with self.assertRaises(ProtocolError):
            encode_control_message(Hello(0x0100, bytes(16), 1, 1, 0, "x" * 256))
        with self.assertRaises(ProtocolError):
            encode_control_message(Anchor(5, 0, 0, True, 0))
        with self.assertRaises(ProtocolError):
            encode_control_message(Mute(2, 0))
        with self.assertRaises(ProtocolError):
            encode_control_message(Volume(101, 0))
        with self.assertRaises(ProtocolError):
            encode_control_message(CalibAck(-1, 0))

    def test_outer_flags_are_not_confused_with_audio_payload_flags(self):
        msg = Mute(True, 123)
        encoded = encode_control_message(msg, outer_flags=0xA5)
        self.assertEqual(encoded.flags, 0xA5)
        self.assertEqual(decode_control_message(encoded), msg)

    def test_non_control_types_are_rejected(self):
        with self.assertRaises(ProtocolError):
            decode_control_message(Frame(0x80, 0, b""))


if __name__ == "__main__":
    unittest.main()
