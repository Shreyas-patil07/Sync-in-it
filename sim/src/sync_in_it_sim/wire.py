# SPDX-License-Identifier: Apache-2.0
"""Platform-neutral v1 frame and UDP time-sync codecs; no socket I/O."""
from __future__ import annotations
from dataclasses import dataclass
import struct

MAX_FRAME_SIZE = 65_536
UDP_PACKET_SIZE = 38
UDP_MAGIC = b"ST"
UDP_VERSION = 1


class ProtocolError(ValueError):
    """Malformed or unsupported wire data."""


@dataclass(frozen=True, slots=True)
class Frame:
    message_type: int
    flags: int
    payload: bytes


@dataclass(frozen=True, slots=True)
class TimeSyncPacket:
    packet_type: int
    version: int
    session_id: int
    receiver_id: int
    sequence: int
    t1_ns: int
    t2_ns: int | None = None
    t3_ns: int | None = None


def _pack(fmt: str, *values: int) -> bytes:
    try:
        return struct.pack(fmt, *values)
    except struct.error as exc:
        raise ProtocolError(str(exc)) from exc


def encode_frame(message_type: int, flags: int, payload: bytes = b"") -> bytes:
    if not 0 <= message_type <= 0xFF or not 0 <= flags <= 0xFF:
        raise ProtocolError("message type and flags must fit uint8")
    if not isinstance(payload, bytes):
        raise ProtocolError("payload must be bytes")
    frame_length = 2 + len(payload)
    if frame_length > MAX_FRAME_SIZE:
        raise ProtocolError("frame exceeds maximum size")
    return _pack("<I", frame_length) + bytes((message_type, flags)) + payload


def decode_frame(data: bytes) -> Frame:
    if not isinstance(data, bytes) or len(data) < 4:
        raise ProtocolError("truncated frame length prefix")
    frame_length, = struct.unpack_from("<I", data)
    if frame_length < 2:
        raise ProtocolError("frame length is smaller than type and flags")
    if frame_length > MAX_FRAME_SIZE:
        raise ProtocolError("frame exceeds maximum size")
    if len(data) != 4 + frame_length:
        raise ProtocolError("frame is truncated or contains trailing bytes")
    return Frame(data[4], data[5], data[6:])


def encode_time_sync_request(packet: TimeSyncPacket) -> bytes:
    if packet.packet_type != 1:
        raise ProtocolError("request packet_type must be 1")
    header = _pack("<2sBBIHIq", UDP_MAGIC, packet.version, 1, packet.session_id,
                   packet.receiver_id, packet.sequence, packet.t1_ns)
    return header + bytes(UDP_PACKET_SIZE - len(header))


def encode_time_sync_response(packet: TimeSyncPacket) -> bytes:
    if packet.packet_type != 2 or packet.t2_ns is None or packet.t3_ns is None:
        raise ProtocolError("response requires packet_type 2, t2 and t3")
    return _pack("<2sBBIHIqqq", UDP_MAGIC, packet.version, 2, packet.session_id,
                 packet.receiver_id, packet.sequence, packet.t1_ns, packet.t2_ns, packet.t3_ns)


def decode_time_sync_packet(data: bytes) -> TimeSyncPacket:
    if not isinstance(data, bytes) or len(data) != UDP_PACKET_SIZE:
        raise ProtocolError("time-sync packet must be exactly 38 bytes")
    magic, version, packet_type, session_id, receiver_id, sequence, t1_ns = struct.unpack_from(
        "<2sBBIHIq", data
    )
    if magic != UDP_MAGIC:
        raise ProtocolError("invalid time-sync magic")
    if version != UDP_VERSION:
        raise ProtocolError("unsupported time-sync version")
    if packet_type == 1:
        return TimeSyncPacket(packet_type, version, session_id, receiver_id, sequence, t1_ns)
    if packet_type == 2:
        _, _, _, _, _, _, _, t2_ns, t3_ns = struct.unpack("<2sBBIHIqqq", data)
        return TimeSyncPacket(packet_type, version, session_id, receiver_id, sequence,
                              t1_ns, t2_ns, t3_ns)
    raise ProtocolError("unknown time-sync packet type")
