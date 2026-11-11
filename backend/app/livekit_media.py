# SPDX-License-Identifier: Apache-2.0
"""LiveKit token issuance and server-side PCM publisher for Sync In It."""

from __future__ import annotations

import os
from datetime import timedelta

from livekit import api, rtc

SAMPLE_RATE_HZ = 48_000
CHANNELS = 2
CHUNK_FRAMES = 960
PCM_CHUNK_BYTES = CHUNK_FRAMES * CHANNELS * 2


class LiveKitNotConfigured(RuntimeError):
    pass


def livekit_settings() -> tuple[str, str, str]:
    server_url = os.getenv("LIVEKIT_URL", "").strip()
    api_key = os.getenv("LIVEKIT_API_KEY", "").strip()
    api_secret = os.getenv("LIVEKIT_API_SECRET", "").strip()
    if not server_url or not api_key or not api_secret:
        raise LiveKitNotConfigured("livekit_not_configured")
    if not server_url.startswith(("wss://", "ws://")):
        raise LiveKitNotConfigured("livekit_url_must_use_websocket_scheme")
    return server_url, api_key, api_secret


def create_participant_token(
    *,
    room_name: str,
    identity: str,
    role: str,
    ttl_seconds: int,
) -> str:
    _, api_key, api_secret = livekit_settings()
    if role not in {"host", "speaker"}:
        raise ValueError("unsupported_livekit_role")
    if ttl_seconds < 60:
        raise ValueError("livekit_token_ttl_too_short")

    grants = api.VideoGrants(
        room_join=True,
        room=room_name,
        can_publish=role == "host",
        can_subscribe=True,
        can_publish_data=role == "host",
    )
    return (
        api.AccessToken(api_key, api_secret)
        .with_identity(identity)
        .with_name("Sync In It Host" if role == "host" else "Sync In It Speaker")
        .with_ttl(timedelta(seconds=ttl_seconds))
        .with_grants(grants)
        .to_jwt()
    )


class LiveKitPcmPublisher:
    """One authenticated LiveKit publisher that accepts fixed 20 ms PCM16 frames."""

    def __init__(self, room_name: str, identity: str):
        server_url, api_key, api_secret = livekit_settings()
        self._server_url = server_url
        self._token = (
            api.AccessToken(api_key, api_secret)
            .with_identity(identity)
            .with_name("Sync In It Media Publisher")
            .with_ttl(timedelta(hours=2))
            .with_grants(
                api.VideoGrants(
                    room_join=True,
                    room=room_name,
                    can_publish=True,
                    can_subscribe=False,
                    can_publish_data=False,
                )
            )
            .to_jwt()
        )
        self._room = rtc.Room()
        self._source: rtc.AudioSource | None = None
        self._track: rtc.LocalAudioTrack | None = None
        self._started = False

    async def start(self) -> None:
        if self._started:
            raise RuntimeError("publisher_already_started")
        await self._room.connect(
            self._server_url,
            self._token,
            options=rtc.RoomOptions(auto_subscribe=False),
        )
        self._source = rtc.AudioSource(
            sample_rate=SAMPLE_RATE_HZ,
            num_channels=CHANNELS,
            queue_size_ms=100,
        )
        self._track = rtc.LocalAudioTrack.create_audio_track(
            "sync-in-it-audio",
            self._source,
        )
        await self._room.local_participant.publish_track(
            self._track,
            rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE),
        )
        self._started = True

    async def publish(self, pcm16le: bytes) -> None:
        if not self._started or self._source is None:
            raise RuntimeError("publisher_not_started")
        if len(pcm16le) != PCM_CHUNK_BYTES:
            raise ValueError("invalid_pcm_chunk_size")
        frame = rtc.AudioFrame(
            data=pcm16le,
            sample_rate=SAMPLE_RATE_HZ,
            num_channels=CHANNELS,
            samples_per_channel=CHUNK_FRAMES,
        )
        await self._source.capture_frame(frame)

    async def close(self) -> None:
        source, self._source = self._source, None
        track, self._track = self._track, None
        if track is not None:
            try:
                await self._room.local_participant.unpublish_track(track.sid)
            except Exception:
                pass
        if source is not None:
            try:
                await source.aclose()
            except Exception:
                pass
        if self._started:
            try:
                await self._room.disconnect()
            finally:
                self._started = False
