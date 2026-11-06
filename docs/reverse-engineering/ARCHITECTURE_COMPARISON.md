# Cross-Product Audio Architecture Comparison

This comparison is derived from the supplied 7 November 2026 reverse-engineering bundle. It distinguishes systems by what they synchronize and what crosses the network. The categories are not interchangeable.

## 1. The six architecture models

### A. Host-to-client audio distribution

A host or server captures/reads media, packetizes audio, and sends it to receivers. Receivers buffer, decode and schedule output. To stay synchronized, they need a common timeline and drift correction. Snapcast publicly documents the strongest source-inspectable version of this model; several Android products advertise variants but do not disclose their protocol.

**Advantages:** one media source; works with locally owned files; can create a speaker array from ordinary devices.  
**Costs:** bandwidth scales with endpoints; host availability matters; Bluetooth/output latency must be calibrated; audio buffering drives the latency/reliability tradeoff.

**Representative systems:** Snapcast, SoundSeeder, SoundSync, LekSync, Unison, Boomi and other phone-speaker products.

### B. Playback-state coordination

A room service shares track identity, queue and transport commands. Each participant's app independently plays content from a provider or local file.

**Advantages:** low control-plane bandwidth; content licensing can stay with the provider; no raw audio uplink from host.  
**Costs:** participants need compatible apps/accounts/content access; provider ads, track availability and player behaviour complicate exact alignment.

**Representative systems:** Spotify Jam, Apple SharePlay, Discord Spotify Listen Along, ListenTogether, Together and listenw.us.

### C. Speaker ecosystems

Receivers are smart network players. The controller selects a group and gives it a media target. The receiver ecosystem owns buffering, group clocks and audio playback.

**Advantages:** purpose-built hardware and persistent device identities; no phone must remain the audio source.  
**Costs:** hardware/protocol lock-in; proprietary group timing; less suitable for arbitrary phones as endpoints.

**Representative systems:** AirPlay 2, Google Cast Speaker Groups, Sonos, WiiM and Alexa Multi-Room Music.

### D. Central library/controller

A server maintains a library and routes it to compatible players. Music Assistant and Lyrion/Squeezelite are strong references when the user owns or controls the collection and playback devices.

**Representative systems:** Music Assistant, Lyrion Music Server and Squeezelite. Snapcast may be used as an output backend in this wider system.

### E. Low-latency performance audio

Each performer sends live audio to a server or peer group. Jitter buffers and network topology are optimized for low round-trip time, not for making multiple phones output the same pre-recorded beat.

**Representative systems:** Jamulus and SonoBus.

### F. Link resolver / adjacent collaboration

A link resolver maps a music identifier or link between services. It does not synchronize playback. Collaborative jukebox products can coordinate a queue and claim shared playback, but their timing model must be separately verified.

**Representative systems:** PlayOver (link resolver); JukeboxDuo (browser collaborative jukebox; exact drift algorithm not public).

## 2. What actually moves over the network

| Model | Synchronized state | Typical network payload |
|---|---|---|
| Audio distribution | Audio samples and playout timeline | Encoded/PCM audio chunks plus timestamps, clock probes and control |
| Playback-state coordination | Track, queue, play/pause/seek and target position | Small control events, room membership, identifiers/URLs; media often comes from each participant's provider/player |
| Speaker ecosystem | Group playback across managed receivers | Media source/metadata and control messages; receiver ecosystem handles media and timing |
| Library/controller | Shared library, queue and player group | Metadata, control state and possibly server-to-endpoint audio |
| Live performance audio | Real-time bidirectional audio | Audio packets, jitter-buffer and mixer/control data |
| Link resolver | No playback state | Track identifiers and cross-service links |

**Critical warning:** do not compare latency figures across these classes as though they measure the same thing. A shared Spotify Jam session can align playback positions while each device decodes its own licensed stream; Snapcast transmits audio chunks from a server; Jamulus optimizes live-performance round-trip latency.

## 3. System-design lessons from the research

- Keep **session control** and **audio transport** as separate subsystems.
- Represent playback as a tuple of content ID, playback epoch, target position, target server time, play state and sequence number rather than broadcasting an unqualified "play now".
- Estimate clock offset with repeated probes; reject or down-weight high-RTT observations; retain uncertainty rather than exposing only one hard-coded offset.
- Schedule playback at a future server timestamp so endpoints have time to prebuffer.
- Measure output latency separately from network latency. Bluetooth can dominate end-to-end error.
- Size jitter buffers from observed packet jitter and make buffer/queue state observable.
- Correct small drift gradually (for example, controlled resampling or time-stretch); use seek/rebuffer for larger errors. Exact policy is implementation-specific and must be measured.
- Persist stable device/output calibration separately from temporary network clock offset.
- Make host failover, late join, reconnect, queue revision and concurrent-control policy explicit.
- Never claim "perfect sync" from command timestamps alone; measure acoustic output.

## 4. Reference shared-session data model

The supplied comparison proposes a minimal data model for state-coordinated rooms. It is not a drop-in schema for the current PCM protocol.

    Room
      room_id, owner_id, visibility, password_hash, created_at, expires_at,
      playback_epoch, queue_revision, current_item_id, playback_state,
      position_at_anchor_ms, server_anchor_time_ms

    Participant
      participant_id, room_id, display_name, role, joined_at, last_seen_at,
      device_capabilities, clock_offset_ms, clock_uncertainty_ms,
      output_latency_ms, calibration_ms, connection_state

    QueueItem
      item_id, room_id, revision, order_key, provider, provider_item_id,
      resolved_media_id, requested_by, status

    PlaybackEvent
      event_id, room_id, sequence_no, actor_id, event_type,
      target_position_ms, target_server_time_ms, created_at

    SyncSample
      participant_id, sampled_at, rtt_ms, offset_ms, uncertainty_ms,
      estimated_output_latency_ms, drift_ppm

    TelemetryEvent (privacy-minimized)
      event_id, room_id, participant_id pseudonym, event_type,
      item_id, queue_revision, latency_bucket, app_version, created_at

Do not store raw audio, full OAuth tokens, private messages or precise location as part of a synchronization data model unless the product genuinely requires it.

## 5. Benchmark definitions

| Metric | Definition |
|---|---|
| Start skew | Maximum minus minimum acoustic start time across endpoints |
| Position drift | Absolute difference between endpoint playback position and reference timeline after a specified duration |
| Recovery time | Seconds required to return within tolerance after network interruption/device wake |
| Dropout ratio | Dropped/underrun audio time divided by total playback time |
| Join-to-audio time | Time from late-join event to audible synchronized output |
| Control-plane latency | User command to all participants observing the new state |

Always record device model, OS, audio route, codec, Wi-Fi band, AP, WAN topology, bitrate, room size and acoustic recording method.

## 6. Source-backed references

- [Snapcast repository](https://github.com/snapcast/snapcast) and [binary protocol](https://github.com/snapcast/snapcast/blob/develop/doc/binary_protocol.md).
- [Together repository](https://github.com/chtnnh/together), for a public room/event architecture.
- [Music Assistant documentation](https://www.music-assistant.io/documentation/) and [Snapcast provider](https://www.music-assistant.io/player-support/snapcast/).
- [Lyrion player synchronization CLI](https://lyrion.org/reference/cli/players/).
- [Sonos architecture](https://docs.sonos.com/docs/how-sonos-works) and [group API](https://docs.sonos.com/reference/groups).
- [Google Cast audio documentation](https://developers.google.com/cast/docs/audio).
- [Airfoil latency documentation](https://rogueamoeba.com/support/knowledgebase/?showArticle=Airfoil-AudioLatency).

For the complete source list and product-level confidence notes, see [Sources](SOURCES.md) and [Product Catalogue](PRODUCT_CATALOGUE.md).
