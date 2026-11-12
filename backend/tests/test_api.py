# SPDX-License-Identifier: Apache-2.0
import time

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


def test_session_clock_requires_valid_session_credentials(tmp_path):
    client = TestClient(create_app(tmp_path / "clock.sqlite3"))
    host = client.post("/api/v1/sessions", json={}).json()
    joined = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Speaker"},
    ).json()

    host_clock = client.get(
        f"/api/v1/sessions/{host['session_id']}/clock",
        headers={"Authorization": f"Bearer {host['host_token']}"},
    )
    speaker_clock = client.get(
        f"/api/v1/sessions/{host['session_id']}/clock",
        headers={"Authorization": f"Bearer {joined['device_token']}"},
    )
    invalid_clock = client.get(
        f"/api/v1/sessions/{host['session_id']}/clock",
        headers={"Authorization": "Bearer " + "x" * 40},
    )

    assert host_clock.status_code == 200
    assert host_clock.json()["server_monotonic_ns"] > 0
    assert speaker_clock.status_code == 200
    assert speaker_clock.json()["server_monotonic_ns"] > 0
    assert invalid_clock.status_code == 401


def test_playback_schedule_requires_all_connected_speakers_ready(tmp_path):
    client = TestClient(create_app(tmp_path / "playback-schedule.sqlite3"))
    host = client.post(
        "/api/v1/sessions",
        json={"host_name": "Host", "max_speakers": 2},
    ).json()
    joined = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Speaker"},
    ).json()
    host_headers = {"Authorization": f"Bearer {host['host_token']}"}
    speaker_headers = {"Authorization": f"Bearer {joined['device_token']}"}
    schedule_url = f"/api/v1/sessions/{host['session_id']}/playback/schedule"

    with client.websocket_connect(
        f"/api/v1/sessions/{host['session_id']}/ws"
    ) as speaker_socket:
        speaker_socket.send_json({"type": "authenticate", "token": joined["device_token"]})
        assert speaker_socket.receive_json()["type"] == "authenticated"
        assert speaker_socket.receive_json()["type"] == "session.snapshot"

        not_ready = client.post(schedule_url, headers=host_headers)
        assert not_ready.status_code == 409
        assert not_ready.json()["detail"] == "speakers_not_ready"

        with client.websocket_connect(
            f"/api/v1/sessions/{host['session_id']}/ws"
        ) as host_socket:
            host_socket.send_json({"type": "authenticate", "token": host["host_token"]})
            assert host_socket.receive_json()["type"] == "authenticated"
            assert host_socket.receive_json()["type"] == "session.snapshot"
            assert speaker_socket.receive_json()["type"] == "session.host_connected"

            ready = client.patch(
                f"/api/v1/sessions/{host['session_id']}/devices/me/state",
                headers=speaker_headers,
                json={"state": "ready", "detail": "synchronized PCM sink ready"},
            )
            assert ready.status_code == 200

            schedule_requested_at_ns = time.monotonic_ns()
            scheduled = client.post(schedule_url, headers=host_headers)
            assert scheduled.status_code == 200
            payload = scheduled.json()
            assert payload["speaker_count"] == 1
            assert payload["start_at_server_monotonic_ns"] >= schedule_requested_at_ns + 2_400_000_000

            event = speaker_socket.receive_json()
            assert event["type"] == "playback.scheduled"
            assert event["start_at_server_monotonic_ns"] == payload["start_at_server_monotonic_ns"]
