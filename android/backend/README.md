# Android backend client

This JVM module is the Android app's optional client for the FastAPI coordination service.

## Supported API

- Create and join a backend session.
- Read session and Speaker state.
- Send Speaker heartbeat and coarse state updates.
- Remove a Speaker or close a backend session.
- Open the authenticated session WebSocket.
- Manage connection lifecycle with bounded exponential reconnect and server-snapshot reconciliation.
- Keep Speaker presence current through WebSocket heartbeats or REST fallback while reconnecting.
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


## Session lifecycle

`BackendSessionCoordinator` owns one Host or Speaker WebSocket. It retries transient failures with bounded exponential backoff, stops retrying after terminal server close codes (session closed, device removed or invalid credentials), and forwards the `session.snapshot` sent after successful authentication so consumers can reconcile events missed during a disconnect. Speaker coordinators send WebSocket heartbeats while authenticated and use the REST heartbeat endpoint while reconnecting. Host presence is represented by its live WebSocket connection.

The coordinator is still a library component; the launcher lifecycle is not yet connected to it. Consumers must close the coordinator when the local session ends. A backend disconnect must not stop the direct-LAN audio session.
