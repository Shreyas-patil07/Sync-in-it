# Android backend client

This JVM module is the Android app's optional client for the FastAPI coordination service.

## Supported API

- Create and join a backend session.
- Read session and Speaker state.
- Send Speaker heartbeat and coarse state updates.
- Remove a Speaker or close a backend session.
- Open the authenticated session WebSocket.
- Send heartbeat, device-state, Host control-command and Speaker acknowledgement frames.
- Receive typed event envelopes while retaining the JSON payload for forward-compatible event fields.

Call synchronous REST methods from an IO thread. The WebSocket API is asynchronous. Bearer tokens are sent in HTTP Authorization headers or in the first WebSocket authentication frame; the client never places tokens in URLs or logs them.

## Architecture boundary

This client is a coordination sidecar, not the media transport. The Render deployment or a LAN-hosted API may be used when configured, but it is not required for local playback. The direct Android Host/Speaker protocol remains responsible for admission to the audio session, four-timestamp clock probes, READY, ANCHOR, PCM delivery and presentation deadlines. The backend WebSocket must carry only presence, coarse state and non-real-time operational commands such as `request_clock_sync`; it must not carry PCM, precision clock timestamps, READY or ANCHOR.

The client does not automatically retry session creation or join because those operations are not idempotent. Reconcile state with `getSession` / `listDevices` after reconnect. The server's WebSocket event fan-out and pending commands are process-local and are not durable.

## Verification

From the repository root:

```bash
gradle -p android :backend:check
gradle -p android :app:assembleDebug :transport:check :backend:check
```

MockWebServer contract tests cover session creation, join serialization, bearer-token placement, API conflict propagation, WebSocket first-frame authentication and Host command serialization.
