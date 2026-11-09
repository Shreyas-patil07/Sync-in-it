# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import hashlib
import os
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from pydantic import BaseModel, Field

ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
SESSION_TTL_SECONDS = 2 * 60 * 60
JOIN_LIMIT = 20
JOIN_WINDOW_SECONDS = 60
MAX_SPEAKERS_HARD_LIMIT = 8
DEVICE_STATES = {
    "joining",
    "syncing",
    "buffering",
    "ready",
    "playing",
    "degraded",
    "disconnected",
}


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


def public_device(row: sqlite3.Row) -> dict:
    now = time.time()
    return {
        "device_id": row["id"],
        "device_name": row["name"],
        "platform": row["platform"],
        "state": row["state"],
        "state_detail": row["state_detail"],
        "joined_at": row["joined_at"],
        "last_seen_at": row["last_seen_at"],
        "online": now - row["last_seen_at"] <= 15,
    }


def create_app(database_path: str | Path | None = None) -> FastAPI:
    db_path = database_path or os.getenv(
        "SYNCINIT_BACKEND_DB", "data/sync-in-it.sqlite3"
    )
    database = SessionDatabase(db_path)
    join_attempts: dict[str, list[float]] = {}

    app = FastAPI(
        title="Sync In It LAN API",
        version="0.1.0",
        description=(
            "LAN-local session and device coordination. Audio and clock-sync "
            "packets remain on the direct Host/Speaker data paths."
        ),
    )
    app.state.database = database

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

    @app.get("/healthz")
    def health():
        return {"status": "ok", "service": "sync-in-it-lan-api", "api_version": "v1"}

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
    def join_session(payload: JoinSessionRequest, request: Request):
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
    def list_devices(session_id: str, host=Depends(require_host)):
        with database.connect() as db:
            rows = db.execute(
                "SELECT * FROM devices WHERE session_id = ? ORDER BY joined_at",
                (session_id,),
            ).fetchall()
        return {"devices": [public_device(row) for row in rows]}

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
    def update_device_state(
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
        return {"ok": True, "device_id": device["id"], "state": payload.state}

    @app.delete("/api/v1/sessions/{session_id}", status_code=204)
    def close_session(session_id: str, host=Depends(require_host)):
        with database.connect() as db:
            db.execute(
                "UPDATE sessions SET state = 'closed' WHERE id = ?",
                (session_id,),
            )
        return None

    @app.delete("/api/v1/sessions/{session_id}/devices/{device_id}", status_code=204)
    def remove_device(session_id: str, device_id: str, host=Depends(require_host)):
        with database.connect() as db:
            cursor = db.execute(
                "DELETE FROM devices WHERE session_id = ? AND id = ?",
                (session_id, device_id),
            )
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="device_not_found")
        return None

    return app


app = create_app()
