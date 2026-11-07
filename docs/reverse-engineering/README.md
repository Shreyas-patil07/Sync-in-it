# Audio Synchronization Reverse-Engineering Research

**Research snapshot:** 7 November 2026  
**Catalogue:** 38 products/systems across six architectural categories, plus related link-resolver/collaborative-jukebox entries.

This section consolidates the supplied research bundle into the Sync In It repository. It is a reference for architecture comparison, competitor benchmarking and engineering decisions—not evidence that any vendor's private implementation has been inspected.

## Scope and method

Each product brief separates public/documented behaviour from architectural inference and unknowns. Open-source systems and published protocols receive deeper source-oriented treatment. Closed products are described from public product pages, help documentation and store listings; internal algorithms, private APIs, database schemas and proprietary source code are not claimed.

No accounts were accessed, private APIs probed, DRM bypassed, or non-public code/personal data obtained. Vendor claims such as "zero lag", "perfect sync" or sub-100 ms are not accepted as measured performance unless a reproducible benchmark is available.

## Contents

| Document | Purpose |
|---|---|
| [Product catalogue](PRODUCT_CATALOGUE.md) | All 38 entries, category, synchronization model, confidence and primary links |
| [Architecture comparison](ARCHITECTURE_COMPARISON.md) | Six distinct system models and cross-product design lessons |
| [Sync In It recommendations](SYNC_IN_IT_RECOMMENDATIONS.md) | What to borrow, what not to copy, and engineering priorities mapped to this repository |
| [Benchmark matrix](BENCHMARK_MATRIX.md) | Repeatable competitor and in-house validation plan |
| [Sources](SOURCES.md) | Official repositories, protocols, product pages and vendor documentation |
| [Research notes](RESEARCH_NOTES.md) | Scope clarifications, verification caveats and research limitations |

## Detailed product briefs

The 38 original product entries are represented as condensed grouped briefs. Each entry preserves its public workflow, described synchronization model, strengths/constraints, material unknowns, suggested REA focus and source links:

1. [Multiple-phone audio distribution](01_multi_phone_audio.md) — Unison, Boomi, SoundSeeder, SoundSync, SyncWave, UniTune, WaveSync, SMusic, LekSync, AmpMe and Sync Music.
2. [Shared listening sessions](02_shared_sessions.md) — Spotify Jam, Apple SharePlay, Stationhead, Eclipse, Rave, mJam, Segno, Discord Listen Along and Sound Room.
3. [Browser-based rooms](03_browser_social.md) — listenw.us, ListenTogether, Together and Popmelon.
4. [Self-hosted multi-room systems](04_self_hosted_multiroom.md) — Snapcast, Music Assistant, Lyrion Music Server and Squeezelite.
5. [Low-latency audio networking](05_low_latency_network_audio.md) — SonoBus, Jamulus and Airfoil.
6. [Speaker ecosystems and protocols](06_ecosystem_protocols.md) — AirPlay 2, Google Cast Groups, Sonos, WiiM and Alexa Multi-Room Music.
7. [Related products that are not equivalent to synchronized audio](07_related_but_not_sync.md) — PlayOver and JukeboxDuo.

## Architecture taxonomy

Do not compare unlike systems using one "sync latency" number:

- **Audio distribution:** endpoints receive audio frames/chunks and schedule sample playout.
- **Playback-state coordination:** participants align track/queue/playback state while each client may obtain its own media stream.
- **Speaker ecosystem:** a vendor-managed receiver group owns buffering, timing and playback.
- **Self-hosted library/controller:** a server/provider layer manages media, queues and supported players.
- **Low-latency performance audio:** live bidirectional musician audio; optimizing round-trip latency is not the same as aligning multi-room song playback.
- **Link resolver / adjacent collaboration:** may help users share music or a queue without distributing synchronized audio.

See [Architecture comparison](ARCHITECTURE_COMPARISON.md) for details and [Benchmark matrix](BENCHMARK_MATRIX.md) for comparable measurements.

## Relevance to Sync In It

The current Android implementation is primarily **host-to-client PCM audio distribution**: separate control/audio transport, a shared clock estimate, sample-indexed buffering, READY/ANCHOR scheduling and AudioTrack output. The most directly relevant technical reference in this catalogue is Snapcast because its source and binary protocol expose the timing/chunk model. Phone-array products such as Unison, Boomi and SoundSeeder are more useful for UX and calibration comparison; Together is a reference for shared room state, typed events and late join, not raw PCM distribution.

The current physical test record remains authoritative for Sync In It's measured behaviour: [Test Results](../Test_Results.md). Competitor claims must not be substituted for our device measurements.


## Current execution priority — 7 November 2026

The final upgrade brief is consolidated into the [Implementation Plan](../07_Implementation.md), with the clock-readiness inconsistency tracked in [ADR-024](../ADR/Architecture_Decisions.md#adr-024--clock-readiness-criterion-and-replay-parity). The next task is per-probe + Host-correlated evidence and offline replay, followed by the controlled clock A/B matrix and one-Host/one-Speaker acoustic validation. The current <0.25 ms Android device gate is not to be relaxed until the criterion is derived from replay evidence and the T2 acoustic budget. See [Current Implementation Status](../11_Current_Implementation_Status.md) for what is implemented versus physically validated.


## Maintenance rules

1. Recheck product availability and platform support on target devices before acting on this research.
2. Preserve the research date and source links when updating claims.
3. Label vendor statements, source-backed facts, inference and unknowns distinctly.
4. Do not turn a marketing claim into an engineering acceptance criterion.
5. Add measured results to the project's own test log; keep competitor REA results separate from Sync In It validation.
