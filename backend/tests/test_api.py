# SPDX-License-Identifier: Apache-2.0
import base64
import json

from fastapi.testclient import TestClient

from app.main import create_app


def test_session_lifecycle_and_device_auth(tmp_path):
    client = TestClient(create_app(tmp_path / "test.sqlite3"))

    created = client.post(
        "/api/v1/sessions",
        json={"host_name": "Host A", "protocol_version": 1, "max_speakers": 2},
    )
    assert created.status_code == 201
    host = created.json()
    assert len(host["join_code"]) == 8
    assert len(host["host_token"]) >= 32

    joined = client.post(
        "/api/v1/sessions/join",
        json={
            "join_code": host["join_code"].lower(),
            "device_name": "Speaker A",
            "platform": "android",
            "protocol_version": 1,
        },
    )
    assert joined.status_code == 201
    device = joined.json()
    assert device["session_id"] == host["session_id"]

    host_headers = {"Authorization": f"Bearer {host['host_token']}"}
    device_headers = {"Authorization": f"Bearer {device['device_token']}"}
    listed = client.get(
        f"/api/v1/sessions/{host['session_id']}/devices",
        headers=host_headers,
    )
    assert listed.status_code == 200
    assert listed.json()["devices"][0]["device_name"] == "Speaker A"
    assert listed.json()["devices"][0]["online"] is True

    state = client.patch(
        f"/api/v1/sessions/{host['session_id']}/devices/me/state",
        headers=device_headers,
        json={"state": "syncing", "detail": "clock acquisition"},
    )
    assert state.status_code == 200
    assert state.json()["state"] == "syncing"

    heartbeat = client.post(
        f"/api/v1/sessions/{host['session_id']}/devices/me/heartbeat",
        headers=device_headers,
    )
    assert heartbeat.status_code == 200

    assert client.get(
        f"/api/v1/sessions/{host['session_id']}/devices",
        headers=device_headers,
    ).status_code == 401


def test_speaker_can_leave_own_session(tmp_path):
    client = TestClient(create_app(tmp_path / "test.sqlite3"))
    host = client.post("/api/v1/sessions", json={"max_speakers": 1}).json()
    joined = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Speaker A"},
    )
    assert joined.status_code == 201
    device = joined.json()
    device_headers = {"Authorization": f"Bearer {device['device_token']}"}
    host_headers = {"Authorization": f"Bearer {host['host_token']}"}
    devices_url = f"/api/v1/sessions/{host['session_id']}/devices"

    assert len(client.get(devices_url, headers=host_headers).json()["devices"]) == 1
    left = client.delete(
        f"/api/v1/sessions/{host['session_id']}/devices/me",
        headers=device_headers,
    )
    assert left.status_code == 204
    assert client.get(devices_url, headers=host_headers).json()["devices"] == []
    assert client.delete(
        f"/api/v1/sessions/{host['session_id']}/devices/me",
        headers=device_headers,
    ).status_code == 401


def test_protocol_mismatch_and_capacity_are_rejected(tmp_path):
    client = TestClient(create_app(tmp_path / "test.sqlite3"))
    host = client.post(
        "/api/v1/sessions",
        json={"max_speakers": 1, "protocol_version": 2},
    ).json()

    mismatch = client.post(
        "/api/v1/sessions/join",
        json={
            "join_code": host["join_code"],
            "device_name": "Wrong protocol",
            "protocol_version": 1,
        },
    )
    assert mismatch.status_code == 409
    assert mismatch.json()["detail"] == "protocol_version_mismatch"

    first = client.post(
        "/api/v1/sessions/join",
        json={
            "join_code": host["join_code"],
            "device_name": "Speaker 1",
            "protocol_version": 2,
        },
    )
    assert first.status_code == 201

    full = client.post(
        "/api/v1/sessions/join",
        json={
            "join_code": host["join_code"],
            "device_name": "Speaker 2",
            "protocol_version": 2,
        },
    )
    assert full.status_code == 409
    assert full.json()["detail"] == "session_full"


def test_host_can_close_session_and_tokens_are_not_returned_again(tmp_path):
    client = TestClient(create_app(tmp_path / "test.sqlite3"))
    host = client.post("/api/v1/sessions", json={}).json()
    headers = {"Authorization": f"Bearer {host['host_token']}"}

    closed = client.delete(
        f"/api/v1/sessions/{host['session_id']}",
        headers=headers,
    )
    assert closed.status_code == 204

    joined = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Late Speaker"},
    )
    assert joined.status_code == 404


def test_health_supports_get_and_head(tmp_path):
    client = TestClient(create_app(tmp_path / "test.sqlite3"))

    get_response = client.get("/healthz")
    assert get_response.status_code == 200
    assert get_response.json()["status"] == "ok"

    head_response = client.head("/healthz")
    assert head_response.status_code == 200
    assert head_response.content == b""


def test_unauthorized_session_access_is_rejected(tmp_path):
    client = TestClient(create_app(tmp_path / "test.sqlite3"))
    assert client.get("/api/v1/sessions/anything").status_code == 401



def _jwt_claims(token: str) -> dict:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def test_livekit_tokens_are_role_scoped_and_secret_stays_server_side(tmp_path, monkeypatch):
    monkeypatch.setenv("LIVEKIT_URL", "https://sync-in-it.example.livekit.cloud")
    monkeypatch.setenv("LIVEKIT_API_KEY", "test-api-key")
    monkeypatch.setenv("LIVEKIT_API_SECRET", "test-api-secret-never-return-this")
    client = TestClient(create_app(tmp_path / "livekit.sqlite3"))

    host = client.post(
        "/api/v1/sessions",
        json={"host_name": "Host A", "max_speakers": 2},
    ).json()
    joined = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Speaker A"},
    ).json()
    session_id = host["session_id"]

    host_response = client.post(
        f"/api/v1/sessions/{session_id}/livekit-token",
        headers={"Authorization": f"Bearer {host['host_token']}"},
    )
    speaker_response = client.post(
        f"/api/v1/sessions/{session_id}/livekit-token",
        headers={"Authorization": f"Bearer {joined['device_token']}"},
    )

    assert host_response.status_code == 200
    assert speaker_response.status_code == 200
    host_body = host_response.json()
    speaker_body = speaker_response.json()
    expected_room = f"syncinit-{session_id}"
    assert host_body["server_url"] == "wss://sync-in-it.example.livekit.cloud"
    assert host_body["room_name"] == expected_room
    assert speaker_body["room_name"] == expected_room
    assert "test-api-secret-never-return-this" not in host_response.text
    assert "test-api-secret-never-return-this" not in speaker_response.text

    host_claims = _jwt_claims(host_body["participant_token"])
    speaker_claims = _jwt_claims(speaker_body["participant_token"])
    assert host_claims["video"]["roomJoin"] is True
    assert host_claims["video"]["canPublish"] is True
    assert host_claims["video"]["canSubscribe"] is False
    assert speaker_claims["video"]["roomJoin"] is True
    assert speaker_claims["video"]["canPublish"] is False
    assert speaker_claims["video"]["canSubscribe"] is True
    assert host_claims["video"]["room"] == expected_room
    assert speaker_claims["video"]["room"] == expected_room


def test_livekit_token_requires_session_credentials_and_configuration(tmp_path, monkeypatch):
    client = TestClient(create_app(tmp_path / "livekit-auth.sqlite3"))
    host = client.post("/api/v1/sessions", json={}).json()
    endpoint = f"/api/v1/sessions/{host['session_id']}/livekit-token"

    assert client.post(endpoint).status_code == 401
    response = client.post(
        endpoint,
        headers={"Authorization": f"Bearer {host['host_token']}"},
    )
    assert response.status_code == 503
    assert response.json()["detail"] == "livekit_not_configured"


def test_livekit_media_ingress_rejects_speaker_credentials(tmp_path, monkeypatch):
    client = TestClient(create_app(tmp_path / "livekit-media-auth.sqlite3"))
    host = client.post("/api/v1/sessions", json={}).json()
    speaker = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Speaker A"},
    ).json()

    with client.websocket_connect(
        f"/api/v1/sessions/{host['session_id']}/media/host"
    ) as websocket:
        websocket.send_text(json.dumps({
            "type": "authenticate",
            "token": speaker["device_token"],
        }))
        closed = websocket.receive()
        assert closed["type"] == "websocket.close"
        assert closed["code"] == 4401
