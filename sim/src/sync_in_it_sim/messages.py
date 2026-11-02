# SPDX-License-Identifier: Apache-2.0
"""Typed control payload codecs for Sync In It protocol v1."""
from __future__ import annotations

from dataclasses import dataclass
import struct
from typing import TypeAlias

from .wire import Frame, ProtocolError

HELLO = 0x01
WELCOME = 0x02
AUDIO_HELLO = 0x03
READY = 0x04
REPORT = 0x05
ANCHOR = 0x06
MUTE = 0x07
SET_COMP = 0x08
RESYNC = 0x09
LEAVE = 0x0A
KEEPALIVE = 0x0B
ERROR = 0x0C
VOLUME = 0x0D
CALIB_CHIRP = 0x20
CALIB_ACK = 0x21


@dataclass(frozen=True, slots=True)
class Hello:
    proto_version: int
    token: bytes
    platform: int
    app_version: int
    capabilities: int
    model: str


@dataclass(frozen=True, slots=True)
class Welcome:
    result: int
    receiver_id: int
    session_id: int
    sample_rate_hz: int
    channels: int
    chunk_frames: int
    codec: int
    audio_port: int
    time_port: int
    startup_lead_ms: int


@dataclass(frozen=True, slots=True)
class AudioHello:
    session_id: int
    receiver_id: int


@dataclass(frozen=True, slots=True)
class Ready:
    buffered_ms: int
    sync_stable: bool
    residual_std_us: int


@dataclass(frozen=True, slots=True)
class Report:
    state: int
    buffered_ms: int
    skew_ppm_x10: int
    residual_std_us: int
    rtt_median_us: int
    output_latency_est_us: int
    err_mean_us: int
    err_max_us: int
    slips: int
    hard_resyncs: int
    underruns: int
    gap_frames: int


@dataclass(frozen=True, slots=True)
class Anchor:
    kind: int
    master_time_ns: int
    sample_idx: int
    playing: bool
    crossfade_ms: int


@dataclass(frozen=True, slots=True)
class Mute:
    mute: bool
    at_master_time_ns: int


@dataclass(frozen=True, slots=True)
class SetComp:
    compensation_ns: int


@dataclass(frozen=True, slots=True)
class Resync:
    reason: int
    resume_from_sample_idx: int


@dataclass(frozen=True, slots=True)
class Leave:
    reason: int


@dataclass(frozen=True, slots=True)
class Keepalive:
    counter: int


@dataclass(frozen=True, slots=True)
class ErrorMessage:
    code: int
    detail: str


@dataclass(frozen=True, slots=True)
class Volume:
    gain: int
    at_master_time_ns: int


@dataclass(frozen=True, slots=True)
class CalibChirp:
    chirp_id: int
    play_at_master_time_ns: int
    f0_hz: int
    f1_hz: int
    duration_ms: int
    gain: int


@dataclass(frozen=True, slots=True)
class CalibAck:
    chirp_id: int
    status: int


ControlMessage: TypeAlias = (
    Hello | Welcome | AudioHello | Ready | Report | Anchor | Mute | SetComp
    | Resync | Leave | Keepalive | ErrorMessage | Volume | CalibChirp | CalibAck
)


def _pack(fmt: str, *values: int) -> bytes:
    try:
        return struct.pack(fmt, *values)
    except struct.error as exc:
        raise ProtocolError(str(exc)) from exc


def _unpack_exact(fmt: str, payload: bytes) -> tuple:
    expected = struct.calcsize(fmt)
    if len(payload) != expected:
        raise ProtocolError(f"payload length is {len(payload)}, expected {expected}")
    return struct.unpack(fmt, payload)


def _bool_byte(value: object, field: str) -> int:
    if value is True:
        return 1
    if value is False:
        return 0
    if type(value) is int and value in (0, 1):
        return value
    raise ProtocolError(f"{field} must be boolean")


def _read_bool(value: int, field: str) -> bool:
    if value not in (0, 1):
        raise ProtocolError(f"{field} must be 0 or 1")
    return bool(value)


def _encode_text(value: str, field: str) -> bytes:
    if not isinstance(value, str):
        raise ProtocolError(f"{field} must be a string")
    try:
        encoded = value.encode("utf-8", errors="strict")
    except UnicodeEncodeError as exc:
        raise ProtocolError(f"{field} must be valid UTF-8") from exc
    if len(encoded) > 255:
        raise ProtocolError(f"{field} exceeds 255 UTF-8 bytes")
    return bytes((len(encoded),)) + encoded


def _decode_text(payload: bytes, offset: int, field: str) -> tuple[str, int]:
    if offset >= len(payload):
        raise ProtocolError(f"missing {field} length")
    size = payload[offset]
    offset += 1
    if size > len(payload) - offset:
        raise ProtocolError(f"{field} length exceeds remaining payload")
    try:
        value = payload[offset:offset + size].decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ProtocolError(f"{field} is not valid UTF-8") from exc
    return value, offset + size


def encode_control_message(message: ControlMessage, outer_flags: int = 0) -> Frame:
    if not 0 <= outer_flags <= 0xFF:
        raise ProtocolError("outer flags must fit uint8")
    if isinstance(message, Hello):
        if not isinstance(message.token, bytes) or len(message.token) != 16:
            raise ProtocolError("HELLO token must contain exactly 16 bytes")
        text = _encode_text(message.model, "model")
        payload = _pack("<H", message.proto_version) + message.token
        payload += _pack("<BHI", message.platform, message.app_version, message.capabilities)
        payload += text
        kind = HELLO
    elif isinstance(message, Welcome):
        payload = _pack("<BHIIBH BHHH".replace(" ", ""),
                        message.result, message.receiver_id, message.session_id,
                        message.sample_rate_hz, message.channels, message.chunk_frames,
                        message.codec, message.audio_port, message.time_port, message.startup_lead_ms)
        kind = WELCOME
    elif isinstance(message, AudioHello):
        payload = _pack("<IH", message.session_id, message.receiver_id)
        kind = AUDIO_HELLO
    elif isinstance(message, Ready):
        payload = _pack("<IBI", message.buffered_ms, _bool_byte(message.sync_stable, "sync_stable"),
                        message.residual_std_us)
        kind = READY
    elif isinstance(message, Report):
        payload = _pack("<B I h I I i i I I H H I".replace(" ", ""),
                        message.state, message.buffered_ms, message.skew_ppm_x10,
                        message.residual_std_us, message.rtt_median_us,
                        message.output_latency_est_us, message.err_mean_us,
                        message.err_max_us, message.slips, message.hard_resyncs,
                        message.underruns, message.gap_frames)
        kind = REPORT
    elif isinstance(message, Anchor):
        if message.kind not in range(5):
            raise ProtocolError("ANCHOR kind must be in the range 0..4")
        payload = _pack("<BqqBH", message.kind, message.master_time_ns, message.sample_idx,
                        _bool_byte(message.playing, "playing"), message.crossfade_ms)
        kind = ANCHOR
    elif isinstance(message, Mute):
        payload = _pack("<Bq", _bool_byte(message.mute, "mute"), message.at_master_time_ns)
        kind = MUTE
    elif isinstance(message, SetComp):
        payload = _pack("<i", message.compensation_ns)
        kind = SET_COMP
    elif isinstance(message, Resync):
        payload = _pack("<Bq", message.reason, message.resume_from_sample_idx)
        kind = RESYNC
    elif isinstance(message, Leave):
        payload = _pack("<B", message.reason)
        kind = LEAVE
    elif isinstance(message, Keepalive):
        payload = _pack("<I", message.counter)
        kind = KEEPALIVE
    elif isinstance(message, ErrorMessage):
        payload = _pack("<H", message.code) + _encode_text(message.detail, "detail")
        kind = ERROR
    elif isinstance(message, Volume):
        if not 0 <= message.gain <= 100:
            raise ProtocolError("VOLUME gain must be between 0 and 100")
        payload = _pack("<Bq", message.gain, message.at_master_time_ns)
        kind = VOLUME
    elif isinstance(message, CalibChirp):
        payload = _pack("<HqIIHB", message.chirp_id, message.play_at_master_time_ns,
                        message.f0_hz, message.f1_hz, message.duration_ms, message.gain)
        kind = CALIB_CHIRP
    elif isinstance(message, CalibAck):
        payload = _pack("<HB", message.chirp_id, message.status)
        kind = CALIB_ACK
    else:
        raise ProtocolError(f"unsupported control message type: {type(message).__name__}")
    return Frame(kind, outer_flags, payload)


def decode_control_message(frame: Frame) -> ControlMessage:
    if not isinstance(frame, Frame):
        raise ProtocolError("expected a Frame")
    p = frame.payload
    t = frame.message_type
    if t == HELLO:
        if len(p) < 26:
            raise ProtocolError("HELLO payload is truncated")
        proto, = _unpack_exact("<H", p[:2])
        token = p[2:18]
        platform, app, caps = _unpack_exact("<BHI", p[18:25])
        model, end = _decode_text(p, 25, "model")
        if end != len(p):
            raise ProtocolError("HELLO has trailing payload bytes")
        return Hello(proto, token, platform, app, caps, model)
    if t == WELCOME:
        v = _unpack_exact("<BHIIBHBHHH", p)
        return Welcome(*v)
    if t == AUDIO_HELLO:
        return AudioHello(*_unpack_exact("<IH", p))
    if t == READY:
        buffered, stable, residual = _unpack_exact("<IBI", p)
        return Ready(buffered, _read_bool(stable, "sync_stable"), residual)
    if t == REPORT:
        return Report(*_unpack_exact("<BIhIIiiIIHHI", p))
    if t == ANCHOR:
        kind, master_time, sample_idx, playing, crossfade = _unpack_exact("<BqqBH", p)
        if kind not in range(5):
            raise ProtocolError("ANCHOR kind must be in the range 0..4")
        return Anchor(kind, master_time, sample_idx, _read_bool(playing, "playing"), crossfade)
    if t == MUTE:
        mute, at_time = _unpack_exact("<Bq", p)
        return Mute(_read_bool(mute, "mute"), at_time)
    if t == SET_COMP:
        return SetComp(*_unpack_exact("<i", p))
    if t == RESYNC:
        return Resync(*_unpack_exact("<Bq", p))
    if t == LEAVE:
        return Leave(*_unpack_exact("<B", p))
    if t == KEEPALIVE:
        return Keepalive(*_unpack_exact("<I", p))
    if t == ERROR:
        if len(p) < 3:
            raise ProtocolError("ERROR payload is truncated")
        code, = _unpack_exact("<H", p[:2])
        detail, end = _decode_text(p, 2, "detail")
        if end != len(p):
            raise ProtocolError("ERROR has trailing payload bytes")
        return ErrorMessage(code, detail)
    if t == VOLUME:
        gain, at_time = _unpack_exact("<Bq", p)
        if gain > 100:
            raise ProtocolError("VOLUME gain must be between 0 and 100")
        return Volume(gain, at_time)
    if t == CALIB_CHIRP:
        return CalibChirp(*_unpack_exact("<HqIIHB", p))
    if t == CALIB_ACK:
        return CalibAck(*_unpack_exact("<HB", p))
    raise ProtocolError(f"unknown control message type 0x{t:02x}")
