# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

from dataclasses import dataclass, field
import heapq
import math
import random
import statistics
from typing import Any

from .estimator import ClockOffsetEstimator
from .models import ClockModel, SyncSample

SAMPLE_RATE_HZ = 48_000
CHUNK_FRAMES = 960
AUDIO_CHUNK_BYTES = CHUNK_FRAMES * 2 * 2
CHUNK_INTERVAL_NS = 20_000_000
JOIN_PROBES = 20
JOIN_INTERVAL_NS = 50_000_000
FIRST_RETRY_NS = 1_000_000_000
FAST_RETRY_NS = 500_000_000
SLOW_RETRY_NS = 2_000_000_000
STABLE_SPAN_NS = 10_000_000_000
STABLE_RESIDUAL_MS = 0.25


@dataclass(frozen=True, slots=True)
class SharedWifiConfig:
    """One half-duplex AP channel; parameters are controlled model inputs."""
    phy_rate_mbps: float = 72.0
    tcp_payload_bytes: int = 1460
    mac_overhead_bytes: int = 110
    preamble_us: float = 50.0
    ack_us: float = 30.0
    contention_window_us: float = 67.5
    router_forward_us: float = 40.0
    master_processing_us: float = 200.0
    retry_probability: float = 0.02
    max_retries: int = 3
    max_queue_packets: int = 256
    control_reserve_packets: int = 32
    playback_delay_s: float = 3.0
    jitter_buffer_capacity_s: float = 8.0

    def __post_init__(self) -> None:
        if not math.isfinite(self.phy_rate_mbps) or self.phy_rate_mbps <= 0:
            raise ValueError("phy_rate_mbps must be finite and positive")
        for name in ("preamble_us", "ack_us", "contention_window_us", "router_forward_us", "master_processing_us"):
            value = getattr(self, name)
            if not math.isfinite(value) or value < 0:
                raise ValueError(f"{name} must be finite and non-negative")
        if self.tcp_payload_bytes < 1 or self.mac_overhead_bytes < 0:
            raise ValueError("invalid frame size")
        if not 0 <= self.retry_probability < 1 or self.max_retries < 0:
            raise ValueError("invalid retry configuration")
        if self.max_queue_packets < 2 or not 0 <= self.control_reserve_packets < self.max_queue_packets:
            raise ValueError("invalid queue capacity/reserve")
        if not math.isfinite(self.playback_delay_s) or self.playback_delay_s <= 0:
            raise ValueError("playback_delay_s must be positive")
        if not math.isfinite(self.jitter_buffer_capacity_s) or self.jitter_buffer_capacity_s < self.playback_delay_s:
            raise ValueError("jitter buffer capacity must be >= playback delay")


@dataclass(slots=True)
class _Packet:
    kind: str
    receiver: int
    key: tuple[int, ...]
    size: int
    sequence: int = -1
    t1: int = 0
    t2: int = 0
    t3: int = 0
    chunk_key: tuple[int, int] | None = None


@dataclass(slots=True)
class _Chunk:
    generated_ns: int
    remaining: int
    failed: bool = False
    last_finish_ns: int = 0


@dataclass(slots=True)
class _Receiver:
    clock: ClockModel
    samples: list[SyncSample] = field(default_factory=list)
    stable_streak: int = 0
    stable_at_ns: int | None = None
    sent: int = 0
    received: int = 0
    fast: int = 0
    steady: int = 0


class SharedWifiSimulation:
    """Discrete-event LAN model with shared airtime and priority control queue.

    Each unicast packet traverses client->AP and AP->client over one shared
    medium. Waiting clock-sync packets outrank waiting audio packets; a frame
    already in transmission is not preempted. This is not a full 802.11 MAC.
    """

    def __init__(
        self, *, receiver_count: int, config: SharedWifiConfig | None = None,
        seed: int = 7, duration_s: float = 60.0, adaptive_probes: bool = True,
        receiver_clocks: list[ClockModel] | None = None,
    ) -> None:
        if not 1 <= receiver_count <= 16:
            raise ValueError("receiver_count must be between 1 and 16")
        if not math.isfinite(duration_s) or duration_s <= 0:
            raise ValueError("duration_s must be finite and positive")
        if receiver_clocks is not None and len(receiver_clocks) != receiver_count:
            raise ValueError("receiver_clocks length must equal receiver_count")
        self.count = receiver_count
        self.cfg = config or SharedWifiConfig()
        self.seed = seed
        self.duration_s = duration_s
        self.duration_ns = round(duration_s * 1_000_000_000)
        self.adaptive = adaptive_probes
        clocks = receiver_clocks or [
            ClockModel(offset_ns=round((i - (receiver_count - 1) / 2) * 1_000_000), rate_error_ppm=30.0 - i * 3.0)
            for i in range(receiver_count)
        ]
        self.rx = [_Receiver(clock) for clock in clocks]

        self.events: list[tuple[int, int, str, Any]] = []
        self.event_order = 0
        self.air_queue: list[tuple[int, int, int, int, bool, int, _Packet, int]] = []
        self.air_order = 0
        self.air_busy = False
        self.chunks: dict[tuple[int, int], _Chunk] = {}
        self.audio_latency: list[float] = []
        self.audio_queue_wait: list[float] = []
        self.control_queue_wait: list[float] = []
        self.control_rtt: list[float] = []
        self.airtime_ns = 0
        self.audio_chunks_generated = self.audio_chunks_delivered = 0
        self.audio_chunks_dropped = self.audio_chunks_late = self.audio_chunks_over = 0
        self.audio_segments_sent = self.audio_segments_delivered = self.audio_segments_dropped = 0
        self.request_failures = self.response_failures = 0
        self.queue_drops_audio = self.queue_drops_control = 0
        self.retries_audio = self.retries_control = 0
        self.air_packets_started = self.max_queue_depth = 0
        self.last_event_ns = 0

    def _push(self, at_ns: int, kind: str, payload: Any) -> None:
        self.event_order += 1
        heapq.heappush(self.events, (at_ns, self.event_order, kind, payload))

    def _rng(self, packet: _Packet, hop: int) -> random.Random:
        value = self.seed & 0xFFFFFFFFFFFFFFFF
        for part in (*packet.key, hop):
            value ^= (part + 0x9E3779B97F4A7C15) & 0xFFFFFFFFFFFFFFFF
            value = (value * 0xBF58476D1CE4E5B9) & 0xFFFFFFFFFFFFFFFF
            value ^= value >> 30
        return random.Random(value)

    def _hop_service(self, packet: _Packet, hop: int) -> tuple[int, bool, int]:
        rng = self._rng(packet, hop)
        data_us = (packet.size + self.cfg.mac_overhead_bytes) * 8.0 / self.cfg.phy_rate_mbps
        total_ns = retries = 0
        for _ in range(self.cfg.max_retries + 1):
            attempt_us = self.cfg.preamble_us + self.cfg.ack_us + rng.uniform(0, self.cfg.contention_window_us) + data_us
            total_ns += round(attempt_us * 1_000)
            if rng.random() >= self.cfg.retry_probability:
                return total_ns, True, retries
            retries += 1
        return total_ns, False, retries

    def _enqueue_hop(self, packet: _Packet, hop: int, arrival_ns: int) -> None:
        service_ns, succeeds, retries = self._hop_service(packet, hop)
        priority = 0 if packet.kind.startswith("probe") else 1
        limit = self.cfg.max_queue_packets - self.cfg.control_reserve_packets if priority else self.cfg.max_queue_packets
        if self.air_busy or self.air_queue:
            if len(self.air_queue) >= limit:
                if priority:
                    self.queue_drops_audio += 1
                else:
                    self.queue_drops_control += 1
                self._packet_failed(packet, arrival_ns)
                return
            self.air_order += 1
            heapq.heappush(self.air_queue, (priority, self.air_order, arrival_ns, service_ns, succeeds, retries, packet, hop))
            self.max_queue_depth = max(self.max_queue_depth, len(self.air_queue))
        else:
            self._start_air((priority, 0, arrival_ns, service_ns, succeeds, retries, packet, hop), arrival_ns)

    def _start_air(self, item: tuple[int, int, int, int, bool, int, _Packet, int], now_ns: int) -> None:
        _, _, arrival, service, succeeds, retries, packet, _ = item
        wait_ms = max(0, now_ns - arrival) / 1_000_000.0
        if packet.kind.startswith("probe"):
            self.control_queue_wait.append(wait_ms)
            self.retries_control += retries
        else:
            self.audio_queue_wait.append(wait_ms)
            self.retries_audio += retries
        finish = now_ns + service
        if now_ns < self.duration_ns:
            self.airtime_ns += max(0, min(finish, self.duration_ns) - now_ns)
        self.air_packets_started += 1
        self.air_busy = True
        self._push(finish, "air_complete", (packet, item[7], succeeds))

    def _start_next_air(self, now_ns: int) -> None:
        if self.air_queue:
            self._start_air(heapq.heappop(self.air_queue), now_ns)
        else:
            self.air_busy = False

    def _probe_interval(self, receiver: int) -> int:
        return SLOW_RETRY_NS if (not self.adaptive or self.rx[receiver].stable_streak >= 3) else FAST_RETRY_NS

    def _schedule_probe(self, receiver: int, sequence: int, at_ns: int) -> None:
        next_ns = at_ns + self._probe_interval(receiver)
        if next_ns <= self.duration_ns:
            self._push(next_ns, "probe_start", (receiver, sequence))

    def _packet_failed(self, packet: _Packet, at_ns: int) -> None:
        if packet.kind == "audio":
            self._finish_audio_segment(packet, False, at_ns)
        else:
            if packet.kind == "probe_request":
                self.request_failures += 1
            else:
                self.response_failures += 1
            if packet.sequence >= JOIN_PROBES:
                self._schedule_probe(packet.receiver, packet.sequence + 1, at_ns)

    def _packet_delivered(self, packet: _Packet, at_ns: int) -> None:
        if packet.kind == "audio":
            self._finish_audio_segment(packet, True, at_ns)
            return
        if packet.kind == "probe_request":
            t2 = at_ns
            t3 = t2 + round(self.cfg.master_processing_us * 1_000)
            response = _Packet(
                "probe_response", packet.receiver, (*packet.key, 1), 64,
                packet.sequence, packet.t1, t2, t3,
            )
            self._push(t3, "send_response", response)
            return

        clock = self.rx[packet.receiver].clock
        sample = SyncSample(
            t1_receiver_send_ns=packet.t1,
            t2_master_receive_ns=packet.t2,
            t3_master_send_ns=packet.t3,
            t4_receiver_receive_ns=clock.read_ns(at_ns),
            received_at_true_ns=at_ns,
        )
        state = self.rx[packet.receiver]
        state.samples.append(sample)
        state.received += 1
        self.control_rtt.append(sample.rtt_ns / 1_000_000.0)
        try:
            fit = ClockOffsetEstimator().estimate(
                state.samples, now_true_ns=at_ns, receiver_now_ns=clock.read_ns(at_ns)
            )
            stable = (
                fit.receiver_skew_ppm is not None
                and fit.samples_used >= 8
                and fit.selected_sample_span_ns >= STABLE_SPAN_NS
                and fit.residual_rms_ms < STABLE_RESIDUAL_MS
            )
        except ValueError:
            stable = False
        state.stable_streak = state.stable_streak + 1 if stable else 0
        if state.stable_streak >= 3 and state.stable_at_ns is None:
            state.stable_at_ns = at_ns
        self._schedule_probe(packet.receiver, packet.sequence + 1, at_ns)

    def _finish_audio_segment(self, packet: _Packet, succeeds: bool, at_ns: int) -> None:
        if packet.chunk_key is None:
            raise RuntimeError("audio packet missing chunk reference")
        chunk = self.chunks[packet.chunk_key]
        chunk.remaining -= 1
        if succeeds:
            self.audio_segments_delivered += 1
            chunk.last_finish_ns = max(chunk.last_finish_ns, at_ns)
        else:
            self.audio_segments_dropped += 1
            chunk.failed = True
        if chunk.remaining:
            return
        if chunk.failed:
            self.audio_chunks_dropped += 1
            return
        self.audio_chunks_delivered += 1
        delay_ns = chunk.last_finish_ns - chunk.generated_ns
        delay_ms = delay_ns / 1_000_000.0
        self.audio_latency.append(delay_ms)
        if delay_ns > self.cfg.playback_delay_s * 1_000_000_000:
            self.audio_chunks_late += 1
        if delay_ns > self.cfg.jitter_buffer_capacity_s * 1_000_000_000:
            self.audio_chunks_over += 1

    def _handle_audio_generation(self, receiver: int, chunk_index: int, at_ns: int) -> None:
        sizes = []
        remaining = AUDIO_CHUNK_BYTES
        while remaining:
            size = min(remaining, self.cfg.tcp_payload_bytes)
            sizes.append(size)
            remaining -= size
        key = (receiver, chunk_index)
        self.chunks[key] = _Chunk(at_ns, len(sizes))
        self.audio_chunks_generated += 1
        for segment, size in enumerate(sizes):
            self.audio_segments_sent += 1
            packet = _Packet("audio", receiver, (1, receiver, chunk_index, segment), size, chunk_key=key)
            self._enqueue_hop(packet, 0, at_ns)

    def _handle_probe_start(self, receiver: int, sequence: int, at_ns: int) -> None:
        state = self.rx[receiver]
        state.sent += 1
        if sequence >= JOIN_PROBES:
            if self.adaptive and state.stable_streak < 3:
                state.fast += 1
            else:
                state.steady += 1
        packet = _Packet(
            "probe_request", receiver, (2, receiver, sequence, 0), 64,
            sequence=sequence, t1=state.clock.read_ns(at_ns),
        )
        self._enqueue_hop(packet, 0, at_ns)

    def _handle_air_complete(self, packet: _Packet, hop: int, succeeds: bool, at_ns: int) -> None:
        self.air_busy = False
        if not succeeds:
            self._packet_failed(packet, at_ns)
        elif hop == 0:
            self._push(at_ns + round(self.cfg.router_forward_us * 1_000), "forward_packet", packet)
        else:
            self._packet_delivered(packet, at_ns)
        self._start_next_air(at_ns)

    def _initialize(self) -> None:
        chunks = math.ceil(self.duration_ns / CHUNK_INTERVAL_NS)
        for index in range(chunks):
            at_ns = index * CHUNK_INTERVAL_NS
            if at_ns >= self.duration_ns:
                break
            for receiver in range(self.count):
                self._push(at_ns, "audio_generation", (receiver, index, at_ns))
        for receiver in range(self.count):
            for sequence in range(JOIN_PROBES):
                self._push(sequence * JOIN_INTERVAL_NS, "probe_start", (receiver, sequence))
            self._push(FIRST_RETRY_NS, "probe_start", (receiver, JOIN_PROBES))

    @staticmethod
    def _percentile(values: list[float], percentile: float) -> float | None:
        if not values:
            return None
        ordered = sorted(values)
        return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]

    def run(self) -> dict[str, float | int | bool | None]:
        self._initialize()
        while self.events:
            at_ns, _, kind, payload = heapq.heappop(self.events)
            self.last_event_ns = at_ns
            if kind == "audio_generation":
                self._handle_audio_generation(*payload)
            elif kind == "probe_start":
                self._handle_probe_start(*payload, at_ns)
            elif kind == "forward_packet":
                self._enqueue_hop(payload, 1, at_ns)
            elif kind == "send_response":
                self._enqueue_hop(payload, 0, at_ns)
            elif kind == "air_complete":
                self._handle_air_complete(*payload, at_ns)
            else:
                raise RuntimeError(f"unknown LAN event: {kind}")

        stable = [r.stable_at_ns / 1e9 for r in self.rx if r.stable_at_ns is not None]
        delivered = self.audio_chunks_delivered
        return {
            "receiver_count": self.count,
            "adaptive_probes": self.adaptive,
            "phy_rate_mbps": self.cfg.phy_rate_mbps,
            "retry_probability": self.cfg.retry_probability,
            "audio_chunks_generated": self.audio_chunks_generated,
            "audio_chunks_delivered": delivered,
            "audio_chunks_dropped": self.audio_chunks_dropped,
            "audio_chunks_late": self.audio_chunks_late,
            "audio_chunks_over_buffer": self.audio_chunks_over,
            "audio_chunk_loss_pct": self.audio_chunks_dropped / max(1, self.audio_chunks_generated) * 100.0,
            "audio_deadline_miss_pct": self.audio_chunks_late / max(1, delivered) * 100.0,
            "audio_buffer_overrun_pct": self.audio_chunks_over / max(1, delivered) * 100.0,
            "audio_latency_p50_ms": statistics.median(self.audio_latency) if self.audio_latency else None,
            "audio_latency_p95_ms": self._percentile(self.audio_latency, 0.95),
            "audio_latency_max_ms": max(self.audio_latency) if self.audio_latency else None,
            "audio_queue_wait_p95_ms": self._percentile(self.audio_queue_wait, 0.95),
            "audio_payload_goodput_mbps": delivered * AUDIO_CHUNK_BYTES * 8 / self.duration_s / 1e6,
            "audio_segments_sent": self.audio_segments_sent,
            "audio_segments_delivered": self.audio_segments_delivered,
            "audio_segments_dropped": self.audio_segments_dropped,
            "probe_count": sum(r.sent for r in self.rx),
            "probe_success_count": sum(r.received for r in self.rx),
            "probe_failure_count": self.request_failures + self.response_failures,
            "probe_fast_retry_count": sum(r.fast for r in self.rx),
            "probe_steady_count": sum(r.steady for r in self.rx),
            "control_rtt_p50_ms": statistics.median(self.control_rtt) if self.control_rtt else None,
            "control_rtt_p95_ms": self._percentile(self.control_rtt, 0.95),
            "control_rtt_max_ms": max(self.control_rtt) if self.control_rtt else None,
            "control_queue_wait_p95_ms": self._percentile(self.control_queue_wait, 0.95),
            "control_request_failures": self.request_failures,
            "control_response_failures": self.response_failures,
            "queue_drops_audio": self.queue_drops_audio,
            "queue_drops_control": self.queue_drops_control,
            "mac_retries_audio": self.retries_audio,
            "mac_retries_control": self.retries_control,
            "max_queue_depth": self.max_queue_depth,
            "air_packets_started": self.air_packets_started,
            "medium_utilization_pct": self.airtime_ns / self.duration_ns * 100.0,
            "acquisition_stable_receivers": len(stable),
            "mean_acquisition_stable_at_s": statistics.fmean(stable) if stable else None,
            "simulation_drain_complete_at_s": self.last_event_ns / 1e9,
        }


def _delta(a: float | int | None, b: float | int | None) -> float | None:
    return None if a is None or b is None else float(a) - float(b)


def run_shared_wifi_scenarios(
    *, duration_s: float = 60.0, seed: int = 7,
) -> dict[str, dict[str, dict[str, float | int | bool | None]]]:
    definitions = {
        "3_receivers_72mbps": (3, 72.0, 0.01),
        "6_receivers_72mbps": (6, 72.0, 0.02),
        "10_receivers_72mbps": (10, 72.0, 0.02),
        "10_receivers_36mbps": (10, 36.0, 0.03),
        "10_receivers_24mbps": (10, 24.0, 0.05),
    }
    results = {}
    for index, (name, (count, rate, retry)) in enumerate(definitions.items()):
        config = SharedWifiConfig(phy_rate_mbps=rate, retry_probability=retry)
        clocks = [
            ClockModel(offset_ns=round((i - (count - 1) / 2) * 1_000_000), rate_error_ppm=30.0 - i * 3.0)
            for i in range(count)
        ]
        fixed = SharedWifiSimulation(
            receiver_count=count, config=config, seed=seed + index, duration_s=duration_s,
            adaptive_probes=False, receiver_clocks=clocks,
        ).run()
        adaptive = SharedWifiSimulation(
            receiver_count=count, config=config, seed=seed + index, duration_s=duration_s,
            adaptive_probes=True, receiver_clocks=clocks,
        ).run()
        results[name] = {
            "fixed": fixed,
            "adaptive": adaptive,
            "delta": {
                "probe_count": int(adaptive["probe_count"]) - int(fixed["probe_count"]),
                "audio_latency_p95_ms": _delta(adaptive["audio_latency_p95_ms"], fixed["audio_latency_p95_ms"]),
                "audio_deadline_miss_pct": _delta(adaptive["audio_deadline_miss_pct"], fixed["audio_deadline_miss_pct"]),
                "audio_chunk_loss_pct": _delta(adaptive["audio_chunk_loss_pct"], fixed["audio_chunk_loss_pct"]),
                "control_rtt_p95_ms": _delta(adaptive["control_rtt_p95_ms"], fixed["control_rtt_p95_ms"]),
                "medium_utilization_pct": _delta(adaptive["medium_utilization_pct"], fixed["medium_utilization_pct"]),
            },
        }
    return results
