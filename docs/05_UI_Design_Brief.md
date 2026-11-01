# UI Design Brief — Sync In It

**Document status:** Draft design brief  
**Version:** 1.0  
**Last updated:** 1 November 2026

## 1. Design objective

The interface should make a technically complex synchronization system understandable without hiding its operational state. The user should quickly know whether a phone is connected, clock-synchronized, buffered, ready, playing or degraded.

The interface is a control surface for a real-time system. Accuracy and clear failure communication matter more than decoration.

## 2. UX principles

1. **State first:** connection and synchronization state must be immediately visible.
2. **Honest status:** never show READY when only the network connection succeeded.
3. **One obvious primary action:** the master should have a clear next action at each stage.
4. **Progressive disclosure:** ordinary users see simple status; diagnostics expose detailed timing data.
5. **Safe failure:** an unsynchronized receiver is muted, not allowed to create an echo.
6. **Explain permissions in context:** show why local-network, microphone, capture or camera access is needed before prompting.
7. **No false precision:** show measured timing with units and confidence; do not imply guaranteed zero-ms synchronization.

## 3. Information architecture

### 3.1 Android master

| Screen | Primary content | Primary action |
|---|---|---|
| Role selection | Create session / Join session | Select role |
| Lobby | QR code, session status, receiver list, network state | Admit/manage receivers |
| Source selection | Local file, supported live capture, capture limitation | Select source |
| Preparing | Buffer and clock readiness per receiver | Wait or remove unready receiver |
| Player | Playback controls, group health, session delay | Play/Pause/Resume |
| Calibration | Placement instructions, measurement progress, result table | Start calibration |
| Diagnostics | Timing, network, buffer and event data | Export diagnostics |

### 3.2 Android and iOS receiver

| Screen | Primary content | Primary action |
|---|---|---|
| Join | QR scanner and manual connection alternative | Join |
| Status | Master connection, sync state, buffer, audio route | Retry/reconnect |
| Settings | Channel, local volume, manual offset | Apply setting |
| Diagnostics | Clock, RTT, drift, gaps, underruns | Export diagnostics |

## 4. Screen requirements

### 4.1 Master lobby

Must show:
- Session active/inactive.
- Join QR code and short manual connection instructions.
- Connected receiver count.
- One row per receiver with name/model, platform and current state.
- Sync quality indicator with a textual label.
- Last report time and network health warning.
- Remove receiver and end-session actions.

Do not put raw nanosecond values in the primary lobby. Reserve detailed values for diagnostics.

### 4.2 Receiver list item

Each row should include:
- Device label, model and platform.
- State: CONNECTING, SYNCING, BUFFERING, READY, PLAYING, DEGRADED or MUTED.
- A compact buffer indicator.
- A compact sync-quality indicator.
- A clear error or warning reason where applicable.

Use text labels in addition to color so the UI remains understandable with color-vision deficiency and accessibility settings.

### 4.3 Preparing screen

Display per receiver:
- Clock synchronization status.
- Buffer fill in milliseconds or seconds.
- Required buffer threshold.
- READY or a blocking reason.
- Estimated preparation only when enough measurement data exists.

The master must not start a group merely because most receiver rows are green unless the admission policy explicitly excludes the remaining receivers.

### 4.4 Player screen

Display:
- Source title and source type.
- Play/pause/resume controls appropriate to source mode.
- Shared timeline status.
- Group delay for live capture.
- Active receiver count.
- Degraded/muted receiver summary.
- Calibration status and a route to recalibrate.

Live-capture mode must not show file seek controls unless seek behavior is actually implemented and valid for that source.

### 4.5 Calibration screen

Explain:
- Where the master microphone must be placed.
- That each receiver emits a short test chirp.
- That the result applies only to current device placement and listening position.
- That the area should be quiet during measurement.

Show each receiver's measured delay, confidence/status, compensation and manual adjustment. Provide repeat, reset and undo actions. Never silently apply a low-confidence measurement.

### 4.6 Diagnostics screen

Provide two levels:

**Summary**
- Clock stable/unstable.
- RTT and buffer.
- Current drift/error band.
- Underrun and resync counts.
- Connection state.

**Advanced**
- Offset estimate and residual.
- Skew in ppm.
- RTT minimum/median.
- DAC estimated rate.
- Error mean/max.
- Gap frames, slips, hard resyncs and underruns.
- Event timeline.

All values must show units and clear labels.

## 5. Sync quality presentation

The current design proposes these indicative UI bands:

| Presentation | Indicative condition |
|---|---|
| Good | Estimated residual and drift error under about 2 ms |
| Fair | About 2–5 ms |
| Poor | Above 5 ms or state DEGRADED |
| Unavailable | MUTED or disconnected |

These bands are UX indicators only; they are not substitutes for T1/T2/T3 acoustic acceptance testing. Show that the value is an estimate, not a guaranteed acoustic-skew measurement.

## 6. Visual direction

- Functional, clean and restrained.
- Strong typography hierarchy and large primary controls.
- High contrast for state labels and diagnostics.
- Consistent spacing and touch targets suitable for one-handed phone use.
- Neutral surfaces with a limited semantic color set for status.
- Avoid heavy animation while playback is active.
- Keep the QR code large and unobstructed.
- Use small, persistent state chips rather than repeated full-screen alerts.

The final palette, typography and iconography can be selected during UI design. This brief does not mandate a specific brand color.

## 7. Accessibility and usability

- Minimum comfortable touch target sizes.
- Support system font scaling.
- Text labels accompany status colors.
- Screen-reader labels for QR, state indicators, sliders and controls.
- Units are always visible for timing values.
- Warnings explain the problem and the action the user can take.
- Do not rely exclusively on vibration, sound or color to communicate status.

## 8. Notifications and interruptions

- Android master uses a foreground-service notification while hosting.
- Notification content should identify active session state and provide a safe return-to-app action.
- Handle incoming calls, audio focus loss and route changes explicitly.
- On iOS, handle audio interruptions and background-audio lifecycle.
- Do not display modal dialogs from audio callbacks; post state events to the UI thread.

## 9. Design exclusions

- No account/profile/settings backend.
- No social feed, chat or cloud session history.
- No complex equalizer or music-library interface in the initial release.
- No UI promise of perfect synchronization.
- No automatic microphone recording outside the calibration workflow.

## 10. Source documents

Screen and state requirements originate from section 13 of [docs/System Structure.md](../docs_original/System%20Structure.md). The role model and session purpose originate from [docs/Sync Engine.md](../docs_original/Sync%20Engine.md).
