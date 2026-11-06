# Research Notes and Limitations

**Research date:** 7 November 2026

- The bundle contains 38 individual product/system briefs: all 36 entries from the supplied catalogue, plus PlayOver and JukeboxDuo as distinct related products.
- Sound Room and ListenTogether are already represented once each in the original catalogue; they are not duplicated.
- Sync Music (package com.eb.sync_music) remains unverified in this research pass. Its identity and current availability should be confirmed on-device before investigation.
- WaveSync and SMusic had current Google Play listing pages in the research results. Store presence verifies the listing, not the truth of marketing/performance claims.
- Product availability and supported capabilities can change. Recheck store pages and installability on target devices before relying on them.
- Proprietary product briefs are based on public product pages, help material and store listings. Their private implementation details are unknown unless a source explicitly documents them.
- Architectural inferences are identified as such. Do not describe them as confirmed private algorithms.
- Deeper source-oriented notes are included for publicly documented/open systems such as Snapcast, Together, Music Assistant, Lyrion/Squeezelite, SonoBus and Jamulus.
- No claims are made about private databases, proprietary source code, hidden API endpoints, private production telemetry or internal machine-learning models.
- The research does not claim that all entries provide the same kind of synchronization. Audio distribution, playback-state coordination, receiver ecosystems, live performance audio and link resolution are different problem classes.
- Vendor phrases such as "zero lag", "perfect sync", "sub-100 ms" or "same millisecond" remain unverified marketing claims unless reproduced with a documented independent benchmark.

## Confidence scale used

- **Very high / High:** official technical documentation, a public protocol or source repository supports the stated behaviour/architecture. Exact end-to-end timing may still be unmeasured.
- **Medium:** an official product/help/store page describes features, but implementation or timing internals are not public.
- **Low–medium:** product workflow is public but technical details are sparse or performance evidence is limited.
- **Low:** product identity/current availability is uncertain or only a bare listing/name is available.

## Method boundary

This is public-surface reverse engineering. The research did not access user accounts, probe private APIs, bypass DRM, inspect non-public code, or collect personal data. For closed products, the briefs record observable behaviour and a permitted REA plan rather than inventing internal details.
