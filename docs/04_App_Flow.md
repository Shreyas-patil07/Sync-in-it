# Application Flow — Sync In It

**Document status:** Draft UX and state-flow specification  
**Version:** 1.0  
**Last updated:** 1 November 2026

## 1. Scope

This document defines the user-visible flows and the relationship between interface actions and session state. It covers the Android master, Android receiver and iOS receiver. It does not define visual styling; see [05_UI_Design_Brief.md](05_UI_Design_Brief.md).

## 2. Entry and role selection

### 2.1 Android

On launch, the user chooses:
- **Create session / Master**
- **Join session / Receiver**

The app may remember the last role, but must not silently start hosting or join a previous session.

### 2.2 iOS

The current plan supports receiver mode only. The app opens directly to Join.

## 3. Master flow

### 3.1 Create session

1. User selects Create session.
2. App checks local-network and required runtime permissions.
3. Master service starts and opens the control, audio and time-sync endpoints.
4. App generates a session ID and 128-bit random token.
5. Lobby displays the QR code, connection state and receiver list.
6. Master remains in LOBBY until playback setup or shutdown.

Failure handling:
- If the local network is unavailable, show a clear network setup instruction.
- If a port cannot be bound, report the failure and do not show a usable QR code.
- If the service cannot remain active, explain the required foreground-service setting.

### 3.2 Admit receivers

1. Receiver scans the QR code.
2. Master receives HELLO over control TCP.
3. Master checks protocol major version, token and capacity.
4. Master returns WELCOME or a structured error.
5. Receiver opens the audio connection and begins time synchronization.
6. Lobby shows CONNECTING, SYNCING, BUFFERING and READY states as reported.
7. Master can remove a receiver or close the session.

A receiver is not READY merely because its socket connected.

### 3.3 Choose source

Master opens the source screen and selects one of:
- Local audio file.
- Supported live capture from another application (permission and app policy dependent).

For live capture, the user must approve the OS capture flow. If capture is denied or the source does not permit capture, show a specific explanation and offer file playback. Do not imply that every app's audio can be captured.

### 3.4 Prepare and start

1. User presses Play.
2. Master starts producing or decoding PCM and streams it to admitted receivers.
3. Each receiver stabilizes its clock mapping and fills its jitter buffer.
4. Master shows receiver readiness and any blockers.
5. When the admission policy is satisfied, master computes one future deadline T0.
6. Master sends the start anchor to every admitted receiver.
7. Receivers arm their output and wait silently until T0.
8. Master player shows PLAYING after the timeline start.

A receiver that is not ready must be excluded from that start or admitted later. It must not play immediately on packet arrival.

### 3.5 Playback controls

**Pause (file mode):**
1. Master selects a future pause time.
2. Master sends a pause anchor with the sample position at that time.
3. Receivers stop at the same timeline point.

**Resume:**
1. Master selects a future resume time.
2. Master sends a resume anchor using the paused sample position.
3. Receivers arm and resume together.

**Seek:**
1. Master selects a new file position.
2. Master distributes the required audio and waits for buffer readiness.
3. Master sends a future seek anchor with a short crossfade.
4. Receivers switch at that anchor.

Live capture is not equivalent to file pause/seek. If the source app pauses, capture may produce silence while the live timeline continues. The UI must not expose file-only controls as if they had guaranteed semantics for live capture.

### 3.6 Calibration

Preconditions:
- Session is stable.
- Program audio is stopped or paused.
- Master microphone is placed at the selected listening position.
- Receivers are placed where they will be used.

Flow:
1. User opens Calibration.
2. App explains microphone placement and asks for microphone permission.
3. Master schedules a chirp from each receiver.
4. Receiver acknowledges and renders the chirp at its scheduled time.
5. Master records the chirp and estimates acoustic arrival.
6. Repeated measurements are checked for confidence and consistency.
7. Master calculates compensation values and sends SET_COMP.
8. UI shows per-device result, confidence and warnings.
9. User can accept, repeat, reset or fine-tune with a manual offset.

Calibration is associated with the current physical layout; it must be invalidated or explicitly rechecked after a device moves.

### 3.7 End session

1. Master sends a pause/stop anchor as appropriate.
2. Master sends LEAVE/close notifications.
3. Capture, sockets, locks and foreground service are stopped.
4. Receiver apps return to DISCONNECTED.
5. User may export diagnostics before discarding the in-memory session.

## 4. Receiver flow

### 4.1 Join

1. User selects Join.
2. User scans QR or enters IP, port and session code manually.
3. App opens control TCP and sends HELLO.
4. After WELCOME, app opens audio TCP.
5. App starts the UDP time-sync burst.
6. App displays SYNCING until the estimator is stable.
7. App receives audio and fills the jitter buffer.
8. App sends READY when both time and buffer requirements are met.

### 4.2 Armed and playing

- READY means the receiver is prepared, not yet audible.
- On ANCHOR start, receiver enters ARMED.
- Receiver outputs silence until the scheduled sample becomes due.
- At the target time, it enters PLAYING.
- The receiver continuously updates DAC mapping, drift and buffer metrics.

### 4.3 Receiver settings

The receiver may expose:
- Local output volume.
- Mono/left/right channel selection.
- Manual timing offset.
- Diagnostics and log export.

It must not expose an independent playback-delay control that breaks the shared timeline. Timing compensation is applied through the defined master-controlled compensation value plus a local manual adjustment policy.

### 4.4 Disconnect and rejoin

If connection is lost:
1. Receiver keeps playing buffered audio only while it can do so safely.
2. On buffer exhaustion or unsafe timing, receiver fades/mutes and displays Master lost or Reconnecting.
3. Receiver retries or asks the user to rescan.
4. It reconnects, re-estimates time and requests the appropriate catch-up point.
5. It re-enters only at a future coordinated boundary.

## 5. State models

### 5.1 Master session states

| State | User-visible meaning |
|---|---|
| IDLE | No active session |
| LOBBY | QR and receiver list shown |
| PREPARING | Source selected; buffers and readiness being checked |
| PLAYING | Shared timeline is running |
| PAUSED | Shared timeline is paused |
| CALIBRATING | Calibration sequence is active |
| ENDED | Session is closed |

### 5.2 Receiver states

| State | User-visible meaning | Typical next state |
|---|---|---|
| DISCONNECTED | Not connected | CONNECTING |
| CONNECTING | Opening session connections | SYNCING |
| SYNCING | Estimating master-time relationship | BUFFERING |
| BUFFERING | Receiving and storing audio | READY |
| READY | Prepared; waiting for anchor | ARMED |
| ARMED | Waiting for scheduled start | PLAYING |
| PLAYING | Rendering synchronized audio | DEGRADED or RESYNCING |
| DEGRADED | Playing, but timing quality is outside the preferred band | PLAYING or MUTED |
| RESYNCING | Recovering its sample position/timeline | PLAYING or BUFFERING |
| MUTED | Silent because safe synchronization cannot be maintained | SYNCING or BUFFERING |

## 6. Error and interruption flows

| Condition | UI response | Required behavior |
|---|---|---|
| Wrong token | Explain invalid session code | Reject join |
| Protocol mismatch | Explain incompatible app versions | Reject join cleanly |
| Session full | Explain capacity reached | Do not disrupt current group |
| Clock unstable | Show diagnostics and retry option | Do not play |
| Capture denied | Explain source restriction | Offer local-file mode |
| Network lost | Show reconnecting state | Drain buffer safely, then mute |
| Audio route changed | Show audio device change | Rebuild output mapping and resync |
| Microphone denied | Explain calibration unavailable | Offer manual offset |
| Calibration confidence low | Show per-device warning | Do not apply unreliable result automatically |
| Master stops | Show Master lost | Stop cleanly after buffer policy |

## 7. Event ordering rules

- A network connection is not equivalent to a ready receiver.
- READY is not equivalent to PLAYING.
- The master anchor is the authority for start, pause, resume and seek.
- Arrival time of an audio packet never starts playback.
- Late join must not rewrite the timeline for healthy receivers.
- Calibration is applied only after measurement passes confidence checks.
- All transitions must have a timeout, failure state and diagnostic event.

## 8. Source documents

The current state machine and operating procedures are derived from sections 7 and 10 of [docs/System Structure.md](../docs_original/System%20Structure.md). The high-level system behavior is in [docs/Sync Engine.md](../docs_original/Sync%20Engine.md).
