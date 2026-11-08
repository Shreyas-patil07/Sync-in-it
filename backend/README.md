# Sync In It LAN Backend API

**Status:** Foundation / API v1  
**Runtime:** Python 3.11+ / FastAPI / SQLite  
**Purpose:** Session metadata, admission, device presence and health state for the local-network system.

## Architecture boundary

This is a **LAN-local control-plane service**, not an audio server.

- Audio remains direct Host → Speaker over the existing TCP or experimental RTP/UDP data plane.
- Clock probes and time-critical playback control remain on the existing direct device protocol. Do not route clock samples through HTTP.
- The API manages session identity, join admission, device credentials, presence and coarse device state.
- It can run on a laptop or small LAN host for development and integration. The Android Host must remain capable of operating without this service; embedding the API into Android is a separate platform-integration task.
- No cloud account, remote database or internet connection is required by the service itself.

This is deliberately additive. It does not replace the Android Host's current TCP HELLO/WELCOME admission, UDP clock exchange, READY/ANCHOR or audio transport yet.

## Start locally

```bash
cd backend
python -m venv .venv
# Windows PowerShell: .venv\Scripts\Activate.ps1
# Linux/macOS: source .venv/bin/activate
python -m pip install -e ".[test]"
uvicorn app.main:app --host 0.0.0.0 --port 8788
```

- Health: `GET http://127.0.0.1:8788/healthz`
- OpenAPI: `http://127.0.0.1:8788/docs`
- SQLite defaults to `backend/data/sync-in-it.sqlite3`; set `SYNCINIT_BACKEND_DB` to change it.
- Bind to a trusted private LAN only. Do not expose this preview service directly to the public internet.

## API v1

| Method | Endpoint | Purpose | Authentication |
|---|---|---|---|
| GET | `/healthz` | Liveness | None |
| POST | `/api/v1/sessions` | Create session and issue Host token | None |
| POST | `/api/v1/sessions/join` | Join by 8-character code and issue device token | Join code + rate limit |
| GET | `/api/v1/sessions/{id}` | Session snapshot | Host bearer token |
| GET | `/api/v1/sessions/{id}/devices` | Device/presence list | Host bearer token |
| POST | `/api/v1/sessions/{id}/devices/me/heartbeat` | Refresh presence | Device bearer token |
| PATCH | `/api/v1/sessions/{id}/devices/me/state` | Report coarse device state | Device bearer token |
| DELETE | `/api/v1/sessions/{id}/devices/{device_id}` | Remove a device | Host bearer token |
| DELETE | `/api/v1/sessions/{id}` | Close session | Host bearer token |

Tokens are generated with a cryptographically secure random source. Only SHA-256 token hashes are stored in SQLite; the raw Host/device token is returned once at creation/join. Device count is enforced transactionally and protocol-version mismatch is rejected.

## Example

Create:
```http
POST /api/v1/sessions
Content-Type: application/json

{"host_name":"Living-room Host","protocol_version":1,"max_speakers":8}
```

Join:
```http
POST /api/v1/sessions/join
Content-Type: application/json

{"join_code":"ABCD2345","device_name":"Speaker 1","platform":"android","protocol_version":1}
```

## Not implemented yet

- Android/iOS client integration.
- Event push/WebSocket subscriptions and command delivery.
- Session invitation QR payload generation.
- Durable audit/event history and schema migrations.
- TLS / authenticated encryption for untrusted networks.
- Deployment automation and operational monitoring.
- A server-side media relay (intentionally out of scope).

**Security:** session codes are for discovery, not transport encryption. Use only on a trusted private LAN until the authentication and encryption design is hardened. Do not put bearer tokens in logs, URLs or QR codes that can be shared publicly.
