# SPDX-License-Identifier: Apache-2.0
import json

from fastapi.testclient import TestClient
from livekit import api

from app.livekit_media import PCM_CHUNK_BYTES
from app.main import create_app


def test_livekit_tokens_are_role_scoped(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVEKIT_URL", "wss://syncinit.livekit.cloud")
    monkeypatch.setenv("LIVEKIT_API_KEY", "test-api-key")
    monkeypatch.setenv("LIVEKIT_API_SECRET", "test-api-secret")
    client = TestClient(create_app(tmp_path / "livekit.sqlite3"))

    host = client.post(
        "/api/v1/sessions",
        json={"host_name": "Host", "max_speakers": 2},
    ).json()
    host_headers = {"Authorization": f"Bearer {host['host_token']}"}
    host_response = client.post(
        f"/api/v1/sessions/{host['session_id']}/livekit-token",
        headers=host_headers,
    )
    assert host_response.status_code == 200
    host_payload = host_response.json()
    assert host_payload["server_url"] == "wss://syncinit.livekit.cloud"
    assert host_payload["room_name"] == f"syncinit-{host['session_id']}"
    host_claims = api.TokenVerifier("test-api-key", "test-api-secret").verify(
        host_payload["participant_token"]
    )
    assert host_claims.video.room == host_payload["room_name"]
    assert host_claims.video.room_join is True
    assert host_claims.video.can_publish is True
    assert host_claims.video.can_subscribe is True

    joined = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Speaker"},
    ).json()
    speaker_headers = {"Authorization": f"Bearer {joined['device_token']}"}
    speaker_response = client.post(
        f"/api/v1/sessions/{host['session_id']}/livekit-token",
        headers=speaker_headers,
    )
    assert speaker_response.status_code == 200
    speaker_claims = api.TokenVerifier("test-api-key", "test-api-secret").verify(
        speaker_response.json()["participant_token"]
    )
    assert speaker_claims.video.can_publish is False
    assert speaker_claims.video.can_subscribe is True


def test_livekit_token_requires_valid_session_credentials(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVEKIT_URL", "wss://syncinit.livekit.cloud")
    monkeypatch.setenv("LIVEKIT_API_KEY", "test-api-key")
    monkeypatch.setenv("LIVEKIT_API_SECRET", "test-api-secret")
    client = TestClient(create_app(tmp_path / "livekit-auth.sqlite3"))

    response = client.post(
        "/api/v1/sessions/not-a-session/livekit-token",
        headers={"Authorization": "Bearer " + "x" * 40},
    )
    assert response.status_code == 401


def test_livekit_token_reports_missing_server_configuration(tmp_path, monkeypatch):
    monkeypatch.delenv("LIVEKIT_URL", raising=False)
    monkeypatch.delenv("LIVEKIT_API_KEY", raising=False)
    monkeypatch.delenv("LIVEKIT_API_SECRET", raising=False)
    client = TestClient(create_app(tmp_path / "livekit-config.sqlite3"))

    host = client.post("/api/v1/sessions", json={}).json()
    response = client.post(
        f"/api/v1/sessions/{host['session_id']}/livekit-token",
        headers={"Authorization": f"Bearer {host['host_token']}"},
    )
    assert response.status_code == 503
    assert response.json()["detail"] == "livekit_not_configured"


def test_host_media_ingress_publishes_fixed_pcm_frames(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVEKIT_URL", "wss://syncinit.livekit.cloud")
    monkeypatch.setenv("LIVEKIT_API_KEY", "test-api-key")
    monkeypatch.setenv("LIVEKIT_API_SECRET", "test-api-secret")
    published = []

    class FakePublisher:
        def __init__(self, room_name: str, identity: str):
            assert room_name.startswith("syncinit-")
            assert identity.startswith("media-")

        async def start(self):
            return None

        async def publish(self, pcm: bytes):
            published.append(pcm)

        async def close(self):
            return None

    monkeypatch.setattr("app.main.LiveKitPcmPublisher", FakePublisher)
    client = TestClient(create_app(tmp_path / "media-ingress.sqlite3"))
    host = client.post("/api/v1/sessions", json={}).json()

    with client.websocket_connect(
        f"/api/v1/sessions/{host['session_id']}/media/host"
    ) as socket:
        socket.send_text(json.dumps({"type": "authenticate", "token": host["host_token"]}))
        ready = socket.receive_json()
        assert ready["type"] == "media.ready"
        assert ready["chunk_bytes"] == PCM_CHUNK_BYTES
        pcm = bytes(PCM_CHUNK_BYTES)
        socket.send_bytes(pcm)
        socket.close()

    assert published == [bytes(PCM_CHUNK_BYTES)]
