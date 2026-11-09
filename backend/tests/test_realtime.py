# SPDX-License-Identifier: Apache-2.0
from fastapi.testclient import TestClient

from app.main import create_app


def test_websocket_presence_state_commands_and_acknowledgements(tmp_path):
    client = TestClient(create_app(tmp_path / "realtime.sqlite3"))
    host_response = client.post(
        "/api/v1/sessions",
        json={"host_name": "Host", "protocol_version": 1, "max_speakers": 2},
    )
    assert host_response.status_code == 201
    host = host_response.json()
    session_id = host["session_id"]
    ws_url = f"/api/v1/sessions/{session_id}/ws"

    with client.websocket_connect(ws_url) as host_ws:
        host_ws.send_json({"type": "authenticate", "token": host["host_token"]})
        authenticated = host_ws.receive_json()
        assert authenticated["type"] == "authenticated"
        assert authenticated["role"] == "host"
        snapshot = host_ws.receive_json()
        assert snapshot["type"] == "session.snapshot"
        assert snapshot["devices"] == []

        joined = client.post(
            "/api/v1/sessions/join",
            json={
                "join_code": host["join_code"],
                "device_name": "Speaker A",
                "platform": "android",
                "protocol_version": 1,
            },
        )
        assert joined.status_code == 201
        speaker_credentials = joined.json()
        device_id = speaker_credentials["device_id"]

        join_event = host_ws.receive_json()
        assert join_event["type"] == "device.joined"
        assert join_event["device"]["device_id"] == device_id

        with client.websocket_connect(ws_url) as speaker_ws:
            speaker_ws.send_json(
                {"type": "authenticate", "token": speaker_credentials["device_token"]}
            )
            speaker_auth = speaker_ws.receive_json()
            assert speaker_auth["type"] == "authenticated"
            assert speaker_auth["role"] == "speaker"
            assert speaker_auth["device_id"] == device_id
            speaker_snapshot = speaker_ws.receive_json()
            assert speaker_snapshot["type"] == "session.snapshot"
            assert speaker_snapshot["host_connected"] is True

            connected_event = host_ws.receive_json()
            assert connected_event["type"] == "device.connected"
            assert connected_event["device_id"] == device_id

            speaker_ws.send_json(
                {
                    "type": "device.state",
                    "state": "syncing",
                    "detail": "clock acquisition",
                }
            )
            state_ack = speaker_ws.receive_json()
            assert state_ack["type"] == "device.state.ack"
            assert state_ack["state"] == "syncing"
            state_event = host_ws.receive_json()
            assert state_event["type"] == "device.state_changed"
            assert state_event["device_id"] == device_id
            assert state_event["state"] == "syncing"

            host_ws.send_json(
                {
                    "type": "control.command",
                    "command": "request_clock_sync",
                    "command_id": "clock-check-1",
                    "target_device_ids": [device_id],
                }
            )
            command = speaker_ws.receive_json()
            assert command == {
                "type": "control.command",
                "command_id": "clock-check-1",
                "command": "request_clock_sync",
                "issued_at": command["issued_at"],
            }
            command_result = host_ws.receive_json()
            assert command_result["type"] == "control.command.result"
            assert command_result["command_id"] == "clock-check-1"
            assert command_result["delivered_count"] == 1
            assert command_result["accepted"] is True

            speaker_ws.send_json(
                {
                    "type": "command.ack",
                    "command_id": "clock-check-1",
                    "accepted": True,
                }
            )
            ack_event = host_ws.receive_json()
            assert ack_event["type"] == "command.ack"
            assert ack_event["device_id"] == device_id
            assert ack_event["command_id"] == "clock-check-1"
            assert ack_event["accepted"] is True

            speaker_ws.send_json({"type": "heartbeat"})
            heartbeat_ack = speaker_ws.receive_json()
            assert heartbeat_ack["type"] == "heartbeat.ack"
            assert heartbeat_ack["server_time"] > 0

        disconnected_event = host_ws.receive_json()
        assert disconnected_event["type"] == "device.disconnected"
        assert disconnected_event["device_id"] == device_id


def test_websocket_rejects_invalid_credentials(tmp_path):
    client = TestClient(create_app(tmp_path / "auth.sqlite3"))
    session = client.post("/api/v1/sessions", json={}).json()
    ws_url = f"/api/v1/sessions/{session['session_id']}/ws"

    from starlette.websockets import WebSocketDisconnect

    with client.websocket_connect(ws_url) as websocket:
        websocket.send_json({"type": "authenticate", "token": "x" * 40})
        try:
            websocket.receive_json()
        except WebSocketDisconnect as error:
            assert error.code == 4401
        else:
            raise AssertionError("Invalid WebSocket credentials were accepted")


def test_speaker_cannot_issue_host_commands(tmp_path):
    client = TestClient(create_app(tmp_path / "roles.sqlite3"))
    host = client.post("/api/v1/sessions", json={}).json()
    speaker = client.post(
        "/api/v1/sessions/join",
        json={"join_code": host["join_code"], "device_name": "Speaker"},
    ).json()
    ws_url = f"/api/v1/sessions/{host['session_id']}/ws"

    with client.websocket_connect(ws_url) as websocket:
        websocket.send_json({"type": "authenticate", "token": speaker["device_token"]})
        websocket.receive_json()
        websocket.receive_json()
        websocket.send_json(
            {"type": "control.command", "command": "request_diagnostics"}
        )
        error = websocket.receive_json()
        assert error == {
            "type": "protocol.error",
            "code": "unsupported_message_or_role",
        }
