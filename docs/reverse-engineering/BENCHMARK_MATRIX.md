# Audio Synchronization Benchmark Matrix

Use this matrix for two purposes: (1) permitted public-surface research of competitor products and (2) repeatable physical validation of Sync In It. Keep the two result sets separate.

## 1. Test dimensions

| Dimension | Scenarios |
|---|---|
| Topology | Same Wi-Fi AP; Host hotspot; 2.4 GHz vs 5 GHz; guest/isolated network; routed subnets where supported |
| Group size | 2, 3, 5 and 8 endpoints, respecting each product's documented limit |
| Media/source | Local file; generated PCM test stream; internet radio; provider playback where supported/licensed |
| Output route | Built-in speaker; wired headphones; Bluetooth speaker |
| Lifecycle | Foreground; app switch; screen-off only where supported; interruption/call; reconnect; late join |
| Network | Baseline; +20 ms and +50 ms delay; jitter; 1–5% packet loss; bandwidth cap; temporary outage |
| Duration | Initial start; 5 min; 30 min; 60 min, subject to lifecycle support |
| Device mix | Same model; different Android versions; different manufacturers; Android/desktop/browser where product supports them |

## 2. Metrics to collect

| Metric | Definition / collection |
|---|---|
| Join-to-ready | Time from join attempt to readiness accepted by host |
| Start skew | Maximum minus minimum microphone-measured acoustic start time |
| Clock offset | Estimated receiver-vs-host monotonic offset |
| Clock uncertainty | Residual/dispersion/fit uncertainty; record estimator definition, not just a label |
| RTT distribution | Per-probe values; report p50, p95 and range |
| Drift | Playback-position or acoustic separation after 1/5/30/60 minutes |
| Queue pressure | Queue depth, high-water, queued/written/dropped chunks and slow writes |
| Ingress continuity | Frames/sec, missing frames, jitter-buffer fill and underruns |
| Recovery time | Time to return within target after loss/roam/reconnect |
| Dropout ratio | Underrun/dropped audio time divided by total playback time |
| Resource use | CPU, memory, battery and thermal state |
| Network cost | Bandwidth per endpoint and total, packet rate and retransmission where measurable |

Never report a single "latency" value without defining whether it is RTT, command propagation, buffer delay, digital start offset, DAC presentation delay or acoustic arrival skew.

## 3. Capture metadata

Every run should record:

- Product/app version, build number and commit if available.
- Host and receiver device models, OS versions and audio route.
- Network AP/router model, band/channel, topology, RSSI if available, and WAN dependency.
- Room size, device position/distance and microphone placement.
- Codec, sample rate, channels, bitrate and buffer size if exposed.
- Number of participants, source type and session duration.
- Start/end timestamps and timezone.
- Whether the capture is vendor telemetry, app log, network trace, synthetic test or acoustic recording.

Do not store/share authentication tokens, private messages, account data or private music files. Use clean test accounts and devices you own.

## 4. Sync In It staged acceptance

The current code-level READY gate is not the same as the final acoustic acceptance criterion.

### Stage A — Transport and PCM

- Host discovery and admission work for the declared network.
- Control and audio channels bind to the same participant.
- Speaker ingress stays near the producer cadence.
- Missing PCM frames, queue overflows and slow writes are counted.
- The PCM buffer reaches the configured pre-roll without silent gaps.

### Stage B — Clock and scheduled group start

- Existing acquisition requirements are met: at least 8 selected samples, at least 10 seconds selected span, residual RMS strictly below 0.25 ms and 3 consecutive stable updates.
- The Speaker sends READY; the Host accepts it.
- The Host sends a future ANCHOR and the Speaker receives/maps it before the deadline.
- The test records the actual scheduled target and local mapping.

### Stage C — Physical output

- Record receiver-specific acoustic markers from all devices in one capture.
- Report start skew and p50/p95/max pairwise arrival difference.
- Confirm whether the next project decision gate, p95 absolute skew ≤10 ms, is met.
- Re-run with different output routes, especially Bluetooth, before claiming route-independent performance.

### Stage D — Drift and resilience

- Measure acoustic/position drift at 1, 5, 30 and 60 minutes.
- Inject controlled delay/jitter/loss and temporary outages.
- Test reconnect, late join, Host loss, Wi-Fi roam and output-route changes.
- Record time to recover, dropouts and any correction artifacts.

Long/background tests must not be run until the project foreground-service and wake-lock lifecycle is implemented and validated.

## 5. Competitor comparison discipline

For each product:
1. Confirm app/service identity, current version and device/region availability.
2. Separate what is explicitly documented from what is inferred.
3. Record supported synchronization class (audio distribution vs state coordination vs receiver group).
4. Use identical topology, output route, placement and microphone method when measuring acoustic performance.
5. Do not compare vendor marketing claims with independently measured competitor results as if they were equivalent.
