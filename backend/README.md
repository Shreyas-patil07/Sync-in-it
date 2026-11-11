# Sync In It Cloud Backend

**Runtime:** Python 3.11+ · FastAPI · SQLite · WebSockets · LiveKit Python RTC SDK  
**Deployment:** Render web service  
**Role:** Session/authentication control plane plus authenticated Host PCM ingress to LiveKit Cloud.

## Cloud media architecture

The backend creates a room identity for each Sync In It session and issues role-scoped LiveKit participant credentials. The Android Host sends binary PCM frames to the authenticated WebSocket ingress. The backend publishes them to LiveKit through the Python RTC SDK. Android Speakers connect to the LiveKit room and subscribe through WebRTC.

The backend is not a transparent byte relay between phone clients: it terminates the Host WebSocket and publishes frames into the LiveKit room. Phones do not need to share a Wi-Fi network.

## Required environment variables

Set these on the Render backend service:

| Variable | Value |
|---|---|
| LIVEKIT_URL | LiveKit project URL, normally wss://... |
| LIVEKIT_API_KEY | LiveKit server API key |
| LIVEKIT_API_SECRET | LiveKit server API secret |

All three are required for LiveKit token issuance and Host media publishing. The API secret must remain server-side. Do not log it, return it to the Android app, put it in a QR invitation or commit it to Git.

## Run locally

    cd backend
    python -m venv .venv
    python -m pip install -e ".[test]"
    uvicorn app.main:app --host 0.0.0.0 --port 8788

Health and OpenAPI:

- GET or HEAD /healthz
- /docs

## API and WebSocket routes

| Method | Route | Purpose | Authentication |
|---|---|---|---|
| GET, HEAD | /healthz | Health check | None |
| POST | /api/v1/sessions | Create session, Host bearer token and join code | None |
| POST | /api/v1/sessions/join | Join with 8-character code, issue Speaker token | Join code + rate limit |
| POST | /api/v1/sessions/{id}/livekit-token | Issue role-scoped LiveKit room credentials | Host or Speaker bearer token |
| WS | /api/v1/sessions/{id}/media/host | Receive Host 48 kHz stereo PCM16 frames and publish to LiveKit | Host bearer token in first auth frame |
| WS | /api/v1/sessions/{id}/ws | Presence, state and operational events | Host/Speaker bearer token in first auth frame |
| GET | /api/v1/sessions/{id} | Session snapshot | Host bearer token |
| GET | /api/v1/sessions/{id}/devices | Device list | Host bearer token |
| POST | /api/v1/sessions/{id}/devices/me/heartbeat | Speaker presence heartbeat | Speaker bearer token |
| PATCH | /api/v1/sessions/{id}/devices/me/state | Coarse Speaker state | Speaker bearer token |
| DELETE | /api/v1/sessions/{id}/devices/me | Speaker leaves session | Speaker bearer token |
| DELETE | /api/v1/sessions/{id} | Close session | Host bearer token |

The Host media WebSocket accepts binary frames of exactly 3,840 bytes (960 samples/channel × 2 channels × 2 bytes/sample). The backend publishes each 20 ms frame as 48 kHz stereo PCM using livekit.rtc.AudioSource. The backend limits a session to one active Host media socket.

The WebSocket authentication token is sent in the first text frame, never in the URL. LiveKit participant JWTs are minted server-side with role-specific grants: Host can publish; Speakers can subscribe but cannot publish.

## Tests

    python -m pip install -e ".[test]"
    python -m pytest

The LiveKit tests validate token grants and the Host media-ingress contract using a fake publisher. They do not contact your real LiveKit project, Render deployment or Android devices.

## Render operational limits

The backend keeps active WebSocket registrations and LiveKit publisher objects in process memory. A Render instance restart disconnects active media ingress and requires clients to reconnect. SQLite session storage on a Render Free instance is ephemeral; it must not be treated as durable history or production persistence. The session implementation currently uses a fixed expiry window (two hours). Multi-instance WebSocket fan-out and durable session persistence are not implemented.

A successful /healthz check proves only that FastAPI is responding. It does not prove LiveKit credentials, token signing, Host PCM publishing, Speaker subscription or audio playback. Verify these separately.

## Security and deployment

- Keep LIVEKIT_API_SECRET only in Render environment variables.
- Do not include raw backend bearer tokens or LiveKit JWTs in QR codes, URLs, logs or repository files.
- Review and rotate credentials if they are ever exposed.
- Use HTTPS/WSS for all public service endpoints.
- Confirm the actual LiveKit project URL and credentials from LiveKit Cloud; masked environment values cannot be validated from a screenshot.
