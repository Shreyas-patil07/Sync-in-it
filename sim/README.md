# Sync In It — M0 timing and network simulator

A dependency-free Python reference model for clock synchronization, playout timing and shared-Wi-Fi load. It is not an audio player and does not claim that phone hardware has been validated.

## Run

From this directory:

\`\`\`bash
python -m pip install -e .
python -m unittest discover -s tests -v
sync-in-it-sim --duration 60 --seed 7
sync-in-it-sim --lan --duration 60 --seed 7
\`\`\`

For a no-install run on macOS/Linux:

\`\`\`bash
PYTHONPATH=src python -m unittest discover -s tests -v
PYTHONPATH=src python -m sync_in_it_sim --lan --duration 60 --seed 7
\`\`\`

For PowerShell:

\`\`\`powershell
$env:PYTHONPATH = "src"
python -m unittest discover -s tests -v
python -m sync_in_it_sim --lan --duration 60 --seed 7
\`\`\`

Add \`--json\` for a machine-readable report. The \`--lan\` mode runs shared airtime scenarios separately from the clock-only suite.

## Shared Wi-Fi model

- One half-duplex Wi-Fi channel and an access point forwarding unicast traffic.
- Each 20 ms stereo PCM16 chunk is 3,840 bytes and is split into 1,460-byte TCP payload segments by default.
- Each segment traverses client-to-AP and AP-to-client transmissions on the same shared airtime server.
- Each hop consumes payload airtime, MAC overhead, preamble, acknowledgement and random contention backoff; bounded MAC retries can occur.
- Router forwarding delay separates the hops. All receivers share the same transmission queue.
- Waiting clock-sync packets have strict priority over waiting audio packets. An in-flight frame is not preempted; queue space is reserved for control traffic.
- Adaptive probes run against timestamps and estimator samples generated inside the shared LAN model, not against an isolated pre-computed stability time.
- Reports include audio p95/max latency, three-second group-delay deadline misses, eight-second buffer-capacity overruns, chunk/segment loss, control RTT, per-class queue waits, medium utilization, retries, queue drops and estimator acquisition state.

The five cases compare fixed and adaptive probes under identical deterministic audio, clocks and per-packet retry outcomes: 3, 6 and 10 receivers at 72 Mbps, plus 10 receivers at 36 and 24 Mbps. PHY rates are scenario inputs, not measured throughput.

## Corrected 30-second CI benchmark (`--duration 30 --seed 7`)

The following numbers are from the corrected event scheduler. The join-burst response handler schedules follow-up probes only for post-join acquisition transactions; the 20 join probes do not spawn duplicate retry chains.

| Load | Policy | Airtime utilization | Audio p95 | Chunk loss* | Probes |
|---|---|---:|---:|---:|---:|
| 3 receivers, 72 Mbps | Fixed | 24.6% | 5.02 ms | 0% | 105 |
| 3 receivers, 72 Mbps | Adaptive | 24.7% | 5.03 ms | 0% | 174 |
| 6 receivers, 72 Mbps | Fixed | 49.6% | 10.00 ms | 0% | 210 |
| 6 receivers, 72 Mbps | Adaptive | 49.9% | 10.02 ms | 0% | 346 |
| 10 receivers, 72 Mbps | Fixed | 82.8% | 16.58 ms | 0% | 350 |
| 10 receivers, 72 Mbps | Adaptive | 83.2% | 16.68 ms | 0% | 571 |
| 10 receivers, 36 Mbps | Fixed | 100% | 195.59 ms | 26.953% | 350 |
| 10 receivers, 36 Mbps | Adaptive | 100% | 199.48 ms | 27.440% | 557 |
| 10 receivers, 24 Mbps | Fixed | 100% | 278.90 ms | 49.140% | 350 |
| 10 receivers, 24 Mbps | Adaptive | 100% | 281.44 ms | 49.387% | 558 |

All five load cases reported every receiver meeting the operational acquisition gate by the end of 30 seconds. No delivered chunk exceeded the 3-second playback deadline in this run. At 72 Mbps, adaptive probes added 0.1–0.4 percentage points of airtime and only a small audio-p95 increase. At 36/24 Mbps, the channel was already saturated under fixed probing; adaptive traffic added further queue pressure but did not materially change the saturation state.

*Chunk-loss percentages are modeled segment loss from MAC retry exhaustion or bounded queue overflow. They are not predictions of application-level loss over TCP, because TCP retransmission and head-of-line recovery are not modeled. The PHY rates and retry probabilities are assumptions, not physical measurements.



## Drift-correction comparison

The clock-only simulator reports two playout-control candidates on the same estimated initial phase error and effective rate mismatch:

- **Frame-slip baseline:** the existing dead band, one-frame drop/duplicate decisions and hard-resync threshold.
- **Bounded resampling candidate:** a 20 ms control loop with feed-forward compensation from a synthetic DAC/output-rate estimate, a proportional phase trim spread over a four-second horizon, a ±500 ppm adjustment limit and a 1,000 ppm/s slew limit. It retains the same 240-frame (5 ms) hard-resync gate.

The resampling candidate reports p95/maximum/final modeled phase error, RMS/peak rate adjustment, rate-limit saturation and hard-resync count. The baseline reports p95/maximum/final modeled phase error, frame slips per minute and hard-resync count.

**Interpretation limits:** the initial A/B uses an ideal rate estimate so it is an optimistic control-loop comparison, not a claim about achievable DAC measurement precision. Unit tests add a +2 ppm estimate bias to check sensitivity. Neither simulation renders PCM or models interpolation filters, transient artifacts, aliasing, callback scheduling, CPU/thermal cost or measured output timestamps. It cannot establish that resampling sounds better than frame slips. The candidate is not wired into the C++ core or Android audio path.

## Assumptions and limits

This is a controlled airtime/queueing approximation, not a full IEEE 802.11 implementation. It does not model CSMA/CA carrier sensing between independent transmitters, contention windows changing with retry count, OFDMA/MU-MIMO, aggregation/block-ACK, rate adaptation, hidden nodes, band/channel selection, TCP congestion control, TCP retransmission/head-of-line behavior, multicast or router-specific firmware queues. The two-hop unicast path is serialized over one shared medium, while real APs may schedule and aggregate frames differently.

MAC retry exhaustion is treated as segment loss; subsequent TCP retransmission is not modeled. Therefore chunk-loss figures indicate exhausted modeled MAC retries or queue overflow, not expected application-level loss of a real TCP stream. Late-chunk and queue-delay values are directional indicators, not predictions for a particular router.

The control-priority policy is an explicit approximation of the TRD requirement that control traffic must not sit behind a large audio transfer. The actual app must validate socket scheduling, DSCP/WMM behavior and bounded queue policies on physical hardware.

## Metric interpretation

- \`medium_utilization_pct\`: share of simulated time spent transmitting Wi-Fi frames; sustained values near 100% indicate little spare airtime.
- \`audio_latency_p95_ms\`: p95 time from chunk generation until all segments complete the two-hop path.
- \`audio_deadline_miss_pct\`: delivered chunks arriving later than the configured 3-second live group delay.
- \`audio_buffer_overrun_pct\`: delivered chunks arriving later than the 8-second jitter-buffer capacity.
- \`control_rtt_p95_ms\`: p95 four-timestamp probe RTT, excluding master processing.
- \`audio_queue_wait_p95_ms\` and \`control_queue_wait_p95_ms\`: p95 wait before each hop enters the shared airtime server.
- A/B deltas are adaptive minus fixed; positive audio latency/deadline deltas mean adaptive probing worsened that metric.

All results are synthetic. A ten-phone performance claim requires real AP/device measurements including router model, band/channel, negotiated link rates, retries, RSSI, interference, TCP receive timing and audio-buffer telemetry.
