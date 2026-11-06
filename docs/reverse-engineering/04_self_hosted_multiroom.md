# Self-Hosted Multi-Room Audio — Product Briefs

**Catalogue IDs:** 25–28 · **Research date:** 7 November 2026

## 25 — Snapcast

- **Workflow:** Multi-room audio distribution layer. Existing sources (MPD, Mopidy, pipes, ALSA capture, TCP or process stdout) feed Snapserver; Snapclients decode and play the common stream.
- **Documented architecture:** Server reads PCM chunks, encodes PCM/FLAC/Vorbis/Opus and timestamps chunks. Client handshake includes Hello, server settings, codec header and wire chunks. Clients periodically send Time requests; protocol estimates client/server offset from round-trip timestamps under a symmetric-path assumption. Receivers buffer decoded chunks and schedule playout against server time.
- **Drift correction:** The brief describes small playback-rate corrections by slightly speeding/slowing with sample removal/duplication. Official docs report typical deviation below 0.2 ms; treat that as documented vendor/project information, not a guaranteed Android/Bluetooth result.
- **Strengths:** Open source, binary protocol documentation, multiple codecs, platform audio backends and inspectable timing model.
- **Unknowns / constraints:** Wi-Fi/Bluetooth behaviour, output-latency calibration, drift tuning under mobile audio stacks and scaling/security need own testing. Requires a server and compatible clients.
- **REA focus:** Trace protocol and timing code at a fixed commit; validate offset math; test asymmetric delay/jitter/loss; compare codec/buffer choices; acoustic loopback; reconnect/source changes.
- **Sources:** [GitHub](https://github.com/snapcast/snapcast), [binary protocol](https://github.com/snapcast/snapcast/blob/develop/doc/binary_protocol.md)

## 26 — Music Assistant

- **Workflow:** Self-hosted music server/controller with provider and player abstractions, commonly hosted on a Raspberry Pi, NAS, PC or Home Assistant.
- **Synchronization boundary:** Depends on player/backend. Supports synchronized groups including Snapcast and other group-capable players.
- **Strengths:** Provider abstraction, library/metadata, source selection, player integrations and controller UX.
- **Unknowns:** Group timing depends on backend; do not attribute Snapcast timing behaviour to every Music Assistant player.
- **REA focus:** Inspect player/provider interfaces, group membership, source switching, queue ownership and Snapcast integration.
- **Sources:** [Documentation](https://www.music-assistant.io/documentation/), [Snapcast provider](https://www.music-assistant.io/player-support/snapcast/), [GitHub organisation](https://github.com/music-assistant)

## 27 — Lyrion Music Server

- **Workflow:** Open-source server for a broad range of physical and virtual synchronized players.
- **Synchronization boundary:** Server-managed player groups. Public CLI exposes sync/unsync and player group membership.
- **Strengths:** Mature player ecosystem, explicit group operations and public CLI/source.
- **Unknowns:** Exact behaviour varies by player, audio backend and network route; current deployment/support needs validation.
- **REA focus:** Trace group and CLI commands; late join/unsync; queue state; client reconnect and mixed player types.
- **Sources:** [Server overview](https://lyrion.org/reference/lyrion-music-server/), [CLI introduction](https://lyrion.org/reference/cli/introduction/), [player sync commands](https://lyrion.org/reference/cli/players/), [source](https://github.com/LMS-Community/slimserver)

## 28 — Squeezelite

- **Workflow:** Headless synchronized audio player client for multiple operating systems and distributions.
- **Synchronization boundary:** Uses Lyrion Music Server's native player synchronization feature.
- **Strengths:** Lightweight player endpoint with public documentation and source.
- **Unknowns:** Platform-specific audio output behaviour and latency differ; exact timing must be measured per backend.
- **REA focus:** Inspect server/client interactions, buffer and output backend; compare playback start and drift across endpoint classes.
- **Sources:** [Documentation](https://lyrion.org/players-and-controllers/squeezelite/), [GitHub](https://github.com/ralph-irving/squeezelite), [Lyrion player CLI](https://lyrion.org/reference/cli/players/)

## Cross-entry takeaway

Snapcast is the primary source-level timing reference for Sync In It's current distribution architecture. Music Assistant and Lyrion are better references for future media-source/provider/player abstraction and shared queues. Keep those layers separate: a sophisticated library controller does not solve receiver clock or DAC timing by itself.
