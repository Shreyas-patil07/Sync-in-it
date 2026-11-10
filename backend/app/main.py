# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response, WebSocket, WebSocketDisconnect, status
from pydantic import BaseModel, Field, ValidationError

ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
SESSION_TTL_SECONDS = 2 * 60 * 60
JOIN_LIMIT = 20
JOIN_WINDOW_SECONDS = 60
MAX_SPEAKERS_HARD_LIMIT = 8
MAX_WS_MESSAGE_BYTES = 16 * 1024
DEVICE_STATES = {
    "joining",
    "syncing",
    "buffering",
    "ready",
    "playing",
    "degraded",
    "disconnected",
}
CONTROL_COMMANDS = {"refresh_status", "request_diagnostics", "request_clock_sync"}


class CreateSessionRequest(BaseModel):
    host_name: str = Field(default="Host", min_length=1, max_length=48)
    protocol_version: int = Field(default=1, ge=1, le=255)
    max_speakers: int = Field(default=8, ge=1, le=MAX_SPEAKERS_HARD_LIMIT)


class JoinSessionRequest(BaseModel):
    join_code: str = Field(min_length=8, max_length=8)
    device_name: str = Field(min_length=1, max_length=48)
    platform: Literal["android", "ios", "other"] = "android"
    protocol_version: int = Field(default=1, ge=1, le=255)


class DeviceStateRequest(BaseModel):
    state: Literal[
        "joining", "syncing", "buffering", "ready",
        "playing", "degraded", "disconnected"
    ]
    detail: str | None = Field(default=None, max_length=240)


class WsAuthenticate(BaseModel):
    type: Literal["authenticate"]
    token: str = Field(min_length=32, max_length=256)


class WsDeviceState(BaseModel):
    type: Literal["device.state"]
    state: Literal[
        "joining", "syncing", "buffering", "ready",
        "playing", "degraded", "disconnected"
    ]
    detail: str | None = Field(default=None, max_length=240)


class WsControlCommand(BaseModel):
    type: Literal["control.command"]
    command: Literal["refresh_status", "request_diagnostics", "request_clock_sync"]
    command_id: str | None = Field(default=None, min_length=1, max_length=64)
    target_device_ids: list[str] | None = Field(default=None, max_length=8)


class WsCommandAck(BaseModel):
    type: Literal["command.ack"]
    command_id: str = Field(min_length=1, max_length=64)
    accepted: bool
    detail: str | None = Field(default=None, max_length=240)


class ConnectionManager:
    """One authenticated live connection per Host or Speaker in this process."""

    def __init__(self):
        self._connections: dict[str, dict[str, tuple[WebSocket, str]]] = {}
        self._pending_commands: dict[str, dict[str, tuple[set[str], float]]] = {}
        self._lock = asyncio.Lock()

    async def register(
        self, session_id: str, client_id: str, role: str, websocket: WebSocket
    ) -> WebSocket | None:
        async with self._lock:
            room = self._connections.setdefault(session_id, {})
            previous = room.get(client_id)
            room[client_id] = (websocket, role)
            return previous[0] if previous and previous[0] is not websocket else None

    async def unregister(
        self, session_id: str, client_id: str, websocket: WebSocket
    ) -> bool:
        async with self._lock:
            room = self._connections.get(session_id)
            current = room.get(client_id) if room else None
            if not current or current[0] is not websocket:
                return False
            room.pop(client_id, None)
            pending = self._pending_commands.get(session_id, {})
            for command_id, (targets, _) in list(pending.items()):
                targets.discard(client_id)
                if not targets:
                    pending.pop(command_id, None)
            if not pending:
                self._pending_commands.pop(session_id, None)
            if not room:
                self._connections.pop(session_id, None)
            return True

    async def send_to(
        self, session_id: str, client_id: str, message: dict
    ) -> bool:
        async with self._lock:
            current = self._connections.get(session_id, {}).get(client_id)
        if not current:
            return False
        try:
            await current[0].send_json(message)
            return True
        except (RuntimeError, WebSocketDisconnect):
            return False

    async def send_host(self, session_id: str, message: dict) -> bool:
        return await self.send_to(session_id, "host", message)

    async def send_device(
        self, session_id: str, device_id: str, message: dict
    ) -> bool:
        return await self.send_to(session_id, device_id, message)

    async def send_speakers(
        self,
        session_id: str,
        message: dict,
        target_device_ids: list[str] | None = None,
    ) -> list[str]:
        async with self._lock:
            room = dict(self._connections.get(session_id, {}))
        targets = {
            client_id: socket
            for client_id, (socket, role) in room.items()
            if role == "speaker"
            and (target_device_ids is None or client_id in target_device_ids)
        }
        delivered: list[str] = []
        for device_id, websocket in targets.items():
            try:
                await websocket.send_json(message)
                delivered.append(device_id)
            except (RuntimeError, WebSocketDisconnect):
                continue
        return delivered

    async def connected_devices(self, session_id: str) -> set[str]:
        async with self._lock:
            return {
                client_id
                for client_id, (_, role) in self._connections.get(session_id, {}).items()
                if role == "speaker"
            }

    async def register_pending_command(
        self, session_id: str, command_id: str, device_ids: set[str]
    ) -> bool:
        if not device_ids:
            return True
        now = time.monotonic()
        async with self._lock:
            pending = self._pending_commands.setdefault(session_id, {})
            expired = [
                key for key, (_, deadline) in pending.items() if deadline <= now
            ]
            for key in expired:
                pending.pop(key, None)
            if command_id in pending or len(pending) >= 128:
                return False
            pending[command_id] = (set(device_ids), now + 60)
            return True

    async def acknowledge_command(
        self, session_id: str, command_id: str, device_id: str
    ) -> bool:
        now = time.monotonic()
        async with self._lock:
            pending = self._pending_commands.get(session_id, {})
            expired = [
                key for key, (_, deadline) in pending.items() if deadline <= now
            ]
            for key in expired:
                pending.pop(key, None)
            item = pending.get(command_id)
            if not item or device_id not in item[0]:
                return False
            item[0].remove(device_id)
            if not item[0]:
                pending.pop(command_id, None)
            return True

    async def close_device(self, session_id: str, device_id: str, message: dict):
        async with self._lock:
            current = self._connections.get(session_id, {}).get(device_id)
        if not current:
            return
        try:
            await current[0].send_json(message)
            await current[0].close(code=4003, reason="device-removed")
        except (RuntimeError, WebSocketDisconnect):
            return

    async def is_host_connected(self, session_id: str) -> bool:
        async with self._lock:
            return "host" in self._connections.get(session_id, {})

    async def close_session(self, session_id: str, message: dict):
        async with self._lock:
            room = dict(self._connections.pop(session_id, {}))
            self._pending_commands.pop(session_id, None)
        for websocket, _ in room.values():
            try:
                await websocket.send_json(message)
                await websocket.close(code=4000, reason="session-closed")
            except (RuntimeError, WebSocketDisconnect):
                continue


class SessionDatabase:
    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.initialize()

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        if self.path != ":memory:":
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("PRAGMA synchronous = NORMAL")
        try:
            yield connection
        finally:
            connection.close()

    def initialize(self):
        with self.connect() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    join_code TEXT NOT NULL UNIQUE,
                    host_name TEXT NOT NULL,
                    host_token_hash TEXT NOT NULL,
                    protocol_version INTEGER NOT NULL,
                    max_speakers INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('active', 'closed')),
                    created_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                );

                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    platform TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    state TEXT NOT NULL,
                    state_detail TEXT,
                    joined_at REAL NOT NULL,
                    last_seen_at REAL NOT NULL,
                    UNIQUE(session_id, name, id)
                );

                CREATE INDEX IF NOT EXISTS idx_devices_session
                    ON devices(session_id, joined_at);
                """
            )


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def make_join_code() -> str:
    return "".join(secrets.choice(ROOM_ALPHABET) for _ in range(8))


def public_device(row: sqlite3.Row, transport_connected: bool = False) -> dict:
    now = time.time()
    return {
        "device_id": row["id"],
        "device_name": row["name"],
        "platform": row["platform"],
        "state": row["state"],
        "state_detail": row["state_detail"],
        "joined_at": row["joined_at"],
        "last_seen_at": row["last_seen_at"],
        "online": transport_connected or now - row["last_seen_at"] <= 15,
        "transport_connected": transport_connected,
    }


def create_app(database_path: str | Path | None = None) -> FastAPI:
    db_path = database_path or os.getenv(
        "SYNCINIT_BACKEND_DB", "data/sync-in-it.sqlite3"
    )
    database = SessionDatabase(db_path)
    manager = ConnectionManager()
    join_attempts: dict[str, list[float]] = {}

    app = FastAPI(
        title="Sync In It LAN API",
        version="0.2.0",
        description=(
            "LAN-local session and device coordination. Audio and clock-sync "
            "packets remain on the direct Host/Speaker data paths."
        ),
    )
    app.state.database = database
    app.state.connections = manager

    def bearer_token(authorization: Annotated[str | None, Header()] = None) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="bearer_token_required",
                headers={"WWW-Authenticate": "Bearer"},
            )
        token = authorization[7:].strip()
        if len(token) < 32 or len(token) > 256:
            raise HTTPException(status_code=401, detail="invalid_token")
        return token

    def require_host(session_id: str, token: str = Depends(bearer_token)):
        with database.connect() as db:
            row = db.execute(
                "SELECT * FROM sessions WHERE id = ? AND host_token_hash = ?",
                (session_id, token_hash(token)),
            ).fetchone()
        if not row or row["state"] != "active" or row["expires_at"] <= time.time():
            raise HTTPException(status_code=401, detail="invalid_host_session")
        return row

    def require_device(session_id: str, token: str = Depends(bearer_token)):
        with database.connect() as db:
            row = db.execute(
                """
                SELECT devices.*, sessions.state AS session_state,
                       sessions.expires_at AS session_expires_at
                FROM devices JOIN sessions ON sessions.id = devices.session_id
                WHERE devices.session_id = ? AND devices.token_hash = ?
                """,
                (session_id, token_hash(token)),
            ).fetchone()
        if (
            not row
            or row["session_state"] != "active"
            or row["session_expires_at"] <= time.time()
        ):
            raise HTTPException(status_code=401, detail="invalid_device_session")
        return row

    def websocket_identity(session_id: str, token: str):
        digest = token_hash(token)
        with database.connect() as db:
            host = db.execute(
                "SELECT * FROM sessions WHERE id = ? AND host_token_hash = ?",
                (session_id, digest),
            ).fetchone()
            if host and host["state"] == "active" and host["expires_at"] > time.time():
                return {"role": "host", "client_id": "host", "device": None}

            device = db.execute(
                """
                SELECT devices.*, sessions.state AS session_state,
                       sessions.expires_at AS session_expires_at
                FROM devices JOIN sessions ON sessions.id = devices.session_id
                WHERE devices.session_id = ? AND devices.token_hash = ?
                """,
                (session_id, digest),
            ).fetchone()
        if (
            device
            and device["session_state"] == "active"
            and device["session_expires_at"] > time.time()
        ):
            return {"role": "speaker", "client_id": device["id"], "device": device}
        return None

    @app.get("/healthz")
    def health():
        return {"status": "ok", "service": "sync-in-it-lan-api", "api_version": "v1"}

    @app.head("/healthz", include_in_schema=False)
    def health_head():
        return Response(status_code=status.HTTP_200_OK)

    @app.post("/api/v1/sessions", status_code=201)
    def create_session(payload: CreateSessionRequest):
        now = time.time()
        session_id = str(uuid.uuid4())
        host_token = secrets.token_urlsafe(32)
        with database.connect() as db:
            for _ in range(5):
                join_code = make_join_code()
                try:
                    db.execute(
                        """
                        INSERT INTO sessions (
                            id, join_code, host_name, host_token_hash,
                            protocol_version, max_speakers, state, created_at, expires_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)
                        """,
                        (
                            session_id,
                            join_code,
                            payload.host_name,
                            token_hash(host_token),
                            payload.protocol_version,
                            payload.max_speakers,
                            now,
                            now + SESSION_TTL_SECONDS,
                        ),
                    )
                    break
                except sqlite3.IntegrityError:
                    continue
            else:
                raise HTTPException(status_code=503, detail="join_code_generation_failed")

        return {
            "session_id": session_id,
            "join_code": join_code,
            "host_token": host_token,
            "host_name": payload.host_name,
            "protocol_version": payload.protocol_version,
            "max_speakers": payload.max_speakers,
            "state": "active",
            "created_at": now,
            "expires_at": now + SESSION_TTL_SECONDS,
        }

    @app.post("/api/v1/sessions/join", status_code=201)
    async def join_session(payload: JoinSessionRequest, request: Request):
        ip = request.client.host if request.client else "unknown"
        now = time.time()
        recent = [
            attempt
            for attempt in join_attempts.get(ip, [])
            if now - attempt < JOIN_WINDOW_SECONDS
        ]
        if len(recent) >= JOIN_LIMIT:
            join_attempts[ip] = recent
            raise HTTPException(status_code=429, detail="join_rate_limited")
        recent.append(now)
        join_attempts[ip] = recent

        join_code = payload.join_code.upper()
        if any(character not in ROOM_ALPHABET for character in join_code):
            raise HTTPException(status_code=404, detail="session_not_found")

        device_id = str(uuid.uuid4())
        device_token = secrets.token_urlsafe(32)
        with database.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            session = db.execute(
                "SELECT * FROM sessions WHERE join_code = ?",
                (join_code,),
            ).fetchone()
            if (
                not session
                or session["state"] != "active"
                or session["expires_at"] <= now
            ):
                db.rollback()
                raise HTTPException(status_code=404, detail="session_not_found")
            if payload.protocol_version != session["protocol_version"]:
                db.rollback()
                raise HTTPException(status_code=409, detail="protocol_version_mismatch")
            count = db.execute(
                "SELECT COUNT(*) FROM devices WHERE session_id = ?",
                (session["id"],),
            ).fetchone()[0]
            if count >= session["max_speakers"]:
                db.rollback()
                raise HTTPException(status_code=409, detail="session_full")
            db.execute(
                """
                INSERT INTO devices (
                    id, session_id, name, platform, token_hash, state,
                    joined_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, 'joining', ?, ?)
                """,
                (
                    device_id,
                    session["id"],
                    payload.device_name,
                    payload.platform,
                    token_hash(device_token),
                    now,
                    now,
                ),
            )
            db.commit()

        await manager.send_host(
            session["id"],
            {
                "type": "device.joined",
                "device": {
                    "device_id": device_id,
                    "device_name": payload.device_name,
                    "platform": payload.platform,
                    "state": "joining",
                    "joined_at": now,
                    "transport_connected": False,
                },
                "occurred_at": now,
            },
        )
        return {
            "session_id": session["id"],
            "device_id": device_id,
            "device_token": device_token,
            "state": "joining",
            "heartbeat_interval_seconds": 5,
        }

    @app.get("/api/v1/sessions/{session_id}")
    def get_session(session_id: str, host=Depends(require_host)):
        with database.connect() as db:
            count = db.execute(
                "SELECT COUNT(*) FROM devices WHERE session_id = ?",
                (session_id,),
            ).fetchone()[0]
        return {
            "session_id": host["id"],
            "join_code": host["join_code"],
            "host_name": host["host_name"],
            "protocol_version": host["protocol_version"],
            "max_speakers": host["max_speakers"],
            "speaker_count": count,
            "state": host["state"],
            "created_at": host["created_at"],
            "expires_at": host["expires_at"],
        }

    @app.get("/api/v1/sessions/{session_id}/devices")
    async def list_devices(session_id: str, host=Depends(require_host)):
        connected = await manager.connected_devices(session_id)
        with database.connect() as db:
            rows = db.execute(
                "SELECT * FROM devices WHERE session_id = ? ORDER BY joined_at",
                (session_id,),
            ).fetchall()
        return {
            "devices": [
                public_device(row, row["id"] in connected) for row in rows
            ]
        }

    @app.post("/api/v1/sessions/{session_id}/devices/me/heartbeat")
    def heartbeat(session_id: str, device=Depends(require_device)):
        now = time.time()
        with database.connect() as db:
            db.execute(
                "UPDATE devices SET last_seen_at = ? WHERE id = ?",
                (now, device["id"]),
            )
        return {"ok": True, "server_time": now, "device_id": device["id"]}

    @app.patch("/api/v1/sessions/{session_id}/devices/me/state")
    async def update_device_state(
        session_id: str,
        payload: DeviceStateRequest,
        device=Depends(require_device),
    ):
        now = time.time()
        with database.connect() as db:
            db.execute(
                """
                UPDATE devices
                SET state = ?, state_detail = ?, last_seen_at = ?
                WHERE id = ?
                """,
                (payload.state, payload.detail, now, device["id"]),
            )
        await manager.send_host(
            session_id,
            {
                "type": "device.state_changed",
                "device_id": device["id"],
                "state": payload.state,
                "detail": payload.detail,
                "occurred_at": now,
            },
        )
        return {"ok": True, "device_id": device["id"], "state": payload.state}

    @app.delete("/api/v1/sessions/{session_id}", status_code=204)
    async def close_session(session_id: str, host=Depends(require_host)):
        now = time.time()
        with database.connect() as db:
            db.execute(
                "UPDATE sessions SET state = 'closed' WHERE id = ?",
                (session_id,),
            )
        await manager.close_session(
            session_id, {"type": "session.closed", "occurred_at": now}
        )
        return None

    @app.delete("/api/v1/sessions/{session_id}/devices/me", status_code=204)
    async def leave_session(session_id: str, device=Depends(require_device)):
        device_id = device["id"]
        with database.connect() as db:
            cursor = db.execute(
                "DELETE FROM devices WHERE session_id = ? AND id = ?",
                (session_id, device_id),
            )
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="device_not_found")
        await manager.close_device(
            session_id, device_id, {"type": "device.left", "device_id": device_id}
        )
        await manager.send_host(
            session_id,
            {
                "type": "device.disconnected",
                "device_id": device_id,
                "occurred_at": time.time(),
            },
        )
        return None

    @app.delete("/api/v1/sessions/{session_id}/devices/{device_id}", status_code=204)
    async def remove_device(session_id: str, device_id: str, host=Depends(require_host)):
        with database.connect() as db:
            cursor = db.execute(
                "DELETE FROM devices WHERE session_id = ? AND id = ?",
                (session_id, device_id),
            )
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="device_not_found")
        await manager.close_device(
            session_id, device_id, {"type": "device.removed", "device_id": device_id}
        )
        return None

    @app.websocket("/api/v1/sessions/{session_id}/ws")
    async def session_events(websocket: WebSocket, session_id: str):
        await websocket.accept()
        identity = None
        client_id = None
        registered = False
        try:
            try:
                raw_auth = await asyncio.wait_for(websocket.receive_text(), timeout=10)
                if len(raw_auth.encode("utf-8")) > MAX_WS_MESSAGE_BYTES:
                    await websocket.close(code=4400, reason="message-too-large")
                    return
                auth_message = WsAuthenticate.model_validate(json.loads(raw_auth))
            except asyncio.TimeoutError:
                await websocket.close(code=4408, reason="authentication-timeout")
                return
            except (json.JSONDecodeError, ValidationError, ValueError):
                await websocket.close(code=4400, reason="invalid-authentication-message")
                return

            identity = websocket_identity(session_id, auth_message.token)
            if not identity:
                await websocket.close(code=4401, reason="invalid-session-credentials")
                return

            client_id = identity["client_id"]
            previous = await manager.register(
                session_id, client_id, identity["role"], websocket
            )
            registered = True
            if previous:
                try:
                    await previous.close(code=4001, reason="connection-replaced")
                except RuntimeError:
                    pass

            if identity["role"] == "speaker":
                now = time.time()
                with database.connect() as db:
                    db.execute(
                        "UPDATE devices SET last_seen_at = ? WHERE id = ?",
                        (now, client_id),
                    )

            await websocket.send_json(
                {
                    "type": "authenticated",
                    "session_id": session_id,
                    "role": identity["role"],
                    "device_id": client_id if identity["role"] == "speaker" else None,
                    "server_time": time.time(),
                }
            )

            if identity["role"] == "host":
                with database.connect() as db:
                    rows = db.execute(
                        "SELECT * FROM devices WHERE session_id = ? ORDER BY joined_at",
                        (session_id,),
                    ).fetchall()
                connected = await manager.connected_devices(session_id)
                await websocket.send_json(
                    {
                        "type": "session.snapshot",
                        "session_id": session_id,
                        "state": "active",
                        "devices": [
                            public_device(row, row["id"] in connected) for row in rows
                        ],
                    }
                )
                await manager.send_speakers(
                    session_id,
                    {"type": "session.host_connected", "occurred_at": time.time()},
                )
            else:
                await websocket.send_json(
                    {
                        "type": "session.snapshot",
                        "session_id": session_id,
                        "host_connected": await manager.is_host_connected(session_id),
                    }
                )
                await manager.send_host(
                    session_id,
                    {
                        "type": "device.connected",
                        "device_id": client_id,
                        "occurred_at": time.time(),
                    },
                )

            while True:
                raw = await websocket.receive_text()
                if len(raw.encode("utf-8")) > MAX_WS_MESSAGE_BYTES:
                    await websocket.close(code=4409, reason="message-too-large")
                    break
                try:
                    message = json.loads(raw)
                    if not isinstance(message, dict):
                        raise ValueError("message-must-be-object")
                except (json.JSONDecodeError, ValueError):
                    await websocket.send_json(
                        {"type": "protocol.error", "code": "invalid_json"}
                    )
                    continue

                message_type = message.get("type")
                if message_type == "heartbeat" and identity["role"] == "speaker":
                    now = time.time()
                    with database.connect() as db:
                        db.execute(
                            "UPDATE devices SET last_seen_at = ? WHERE id = ?",
                            (now, client_id),
                        )
                    await websocket.send_json(
                        {"type": "heartbeat.ack", "server_time": now}
                    )
                    continue

                if message_type == "device.state" and identity["role"] == "speaker":
                    try:
                        state_message = WsDeviceState.model_validate(message)
                    except ValidationError:
                        await websocket.send_json(
                            {"type": "protocol.error", "code": "invalid_device_state"}
                        )
                        continue
                    now = time.time()
                    with database.connect() as db:
                        db.execute(
                            """
                            UPDATE devices
                            SET state = ?, state_detail = ?, last_seen_at = ?
                            WHERE id = ? AND session_id = ?
                            """,
                            (
                                state_message.state,
                                state_message.detail,
                                now,
                                client_id,
                                session_id,
                            ),
                        )
                    await manager.send_host(
                        session_id,
                        {
                            "type": "device.state_changed",
                            "device_id": client_id,
                            "state": state_message.state,
                            "detail": state_message.detail,
                            "occurred_at": now,
                        },
                    )
                    await websocket.send_json(
                        {
                            "type": "device.state.ack",
                            "state": state_message.state,
                            "server_time": now,
                        }
                    )
                    continue

                if message_type == "control.command" and identity["role"] == "host":
                    try:
                        command = WsControlCommand.model_validate(message)
                    except ValidationError:
                        await websocket.send_json(
                            {"type": "protocol.error", "code": "invalid_command"}
                        )
                        continue
                    command_id = command.command_id or str(uuid.uuid4())
                    issued_at = time.time()
                    event = {
                        "type": "control.command",
                        "command_id": command_id,
                        "command": command.command,
                        "issued_at": issued_at,
                    }
                    connected = await manager.connected_devices(session_id)
                    targets = (
                        connected
                        if command.target_device_ids is None
                        else connected.intersection(command.target_device_ids)
                    )
                    if targets and not await manager.register_pending_command(
                        session_id, command_id, targets
                    ):
                        await websocket.send_json(
                            {
                                "type": "protocol.error",
                                "code": "command_id_unavailable",
                                "command_id": command_id,
                            }
                        )
                        continue
                    delivered = await manager.send_speakers(
                        session_id, event, sorted(targets)
                    )
                    await websocket.send_json(
                        {
                            "type": "control.command.result",
                            "command_id": command_id,
                            "command": command.command,
                            "delivered_device_ids": delivered,
                            "delivered_count": len(delivered),
                            "accepted": len(delivered) > 0,
                        }
                    )
                    continue

                if message_type == "command.ack" and identity["role"] == "speaker":
                    try:
                        ack = WsCommandAck.model_validate(message)
                    except ValidationError:
                        await websocket.send_json(
                            {"type": "protocol.error", "code": "invalid_command_ack"}
                        )
                        continue
                    if not await manager.acknowledge_command(
                        session_id, ack.command_id, client_id
                    ):
                        await websocket.send_json(
                            {"type": "protocol.error", "code": "unknown_or_expired_command"}
                        )
                        continue
                    await manager.send_host(
                        session_id,
                        {
                            "type": "command.ack",
                            "device_id": client_id,
                            "command_id": ack.command_id,
                            "accepted": ack.accepted,
                            "detail": ack.detail,
                            "occurred_at": time.time(),
                        },
                    )
                    continue

                await websocket.send_json(
                    {"type": "protocol.error", "code": "unsupported_message_or_role"}
                )

        except WebSocketDisconnect:
            pass
        except (RuntimeError, json.JSONDecodeError):
            pass
        finally:
            if registered and identity and client_id:
                removed = await manager.unregister(session_id, client_id, websocket)
                if removed:
                    with database.connect() as db:
                        session = db.execute(
                            "SELECT state FROM sessions WHERE id = ?", (session_id,)
                        ).fetchone()
                    device_still_registered = True
                    if identity["role"] == "speaker":
                        with database.connect() as db:
                            device_still_registered = db.execute(
                                "SELECT 1 FROM devices WHERE id = ? AND session_id = ?",
                                (client_id, session_id),
                            ).fetchone() is not None
                    if session and session["state"] == "active":
                        now = time.time()
                        if identity["role"] == "host":
                            await manager.send_speakers(
                                session_id,
                                {"type": "session.host_disconnected", "occurred_at": now},
                            )
                        elif device_still_registered:
                            await manager.send_host(
                                session_id,
                                {
                                    "type": "device.disconnected",
                                    "device_id": client_id,
                                    "occurred_at": now,
                                },
                            )

    return app


app = create_app()
