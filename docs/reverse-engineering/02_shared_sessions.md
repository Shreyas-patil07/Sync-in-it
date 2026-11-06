# Shared Listening Sessions — Product Briefs

**Catalogue IDs:** 12–20 · **Research date:** 7 November 2026  
These products primarily coordinate playback state/social sessions; they are not automatically equivalent to multi-phone audio distribution.

## 12 — Spotify Jam

- **Workflow:** Collaborative listening session with host/participant controls and queue interaction.
- **Synchronization boundary:** Spotify coordinates shared playback. Exact device clock protocol is proprietary and not documented in the supplied sources.
- **Important distinction:** Participants can receive their own licensed stream; shared track position/transport state is not the same as shipping one PCM stream to each phone.
- **REA focus:** Public UX, invitation/participant permissions, queue conflicts, late join and device handoff; do not probe private APIs or protected streams.
- **Source:** [Spotify support](https://support.spotify.com/article/jam/)

## 13 — Apple SharePlay

- **Workflow:** Synchronized activity over FaceTime or supported Messages sessions across Apple devices and compatible apps.
- **Synchronization boundary:** OS-level shared activity/media session; underlying timing and media transport internals are not public in the referenced user guide.
- **Strengths:** Deep OS integration and app-level shared activity semantics.
- **REA focus:** Supported app/device matrix, join/leave, pause/seek conflict policy, interruption and handoff.
- **Sources:** [iPhone guide](https://support.apple.com/guide/iphone/shareplay-watch-listen-play-iphb657eb791/ios), [Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/shareplay/)

## 14 — Stationhead

- **Workflow:** Social live-radio/broadcaster experience on mobile and web. Listeners use linked streaming-service accounts.
- **Synchronization boundary:** Live show/session coordination rather than a phone speaker array receiving identical raw PCM.
- **Strengths:** Broadcaster/listener social model and live session UX.
- **Unknowns:** Internal listener synchronization, live content mixing, provider timing and retention.
- **Source:** [Stationhead FAQ](https://stationhead.com/faqs)

## 15 — Eclipse Music

- **Workflow:** Personal library with shared Jam sessions and device handoff across Windows, Android, Apple devices and web.
- **Synchronization boundary:** Shared Jam state and handoff; exact clock and media transport are not exposed.
- **Strengths:** Multi-platform library, shared session and handoff UX.
- **Unknowns:** Room state ownership, playback drift correction, late join and conflict behaviour.
- **REA focus:** Switch playback device mid-session; simultaneous controls; track changes and reconnect.
- **Source:** [Official site](https://www.eclipsemusic.app/)

## 16 — Rave

- **Workflow:** Cross-platform synchronized watch/listen parties for supported services and content sources.
- **Synchronization boundary:** Shared playback state for supported sources; internal player/transport architecture remains proprietary.
- **Strengths:** Broad social session workflow and multi-platform availability.
- **Unknowns:** Exact supported source matrix by region/version, drift correction, media licensing and event ordering.
- **REA focus:** Compare play/pause/seek propagation, late join, source-specific restrictions and interruption/recovery.
- **Sources:** [FAQ](https://rave.io/faq), [press](https://rave.io/press)

## 17 — mJam

- **Workflow:** Shared listening session on iPhone and iPad.
- **Synchronization boundary:** App Store description claims host-controlled synchronized playback and precision time alignment; no implementation details are disclosed.
- **Confidence caveat:** The listing's timing claim is not an independent measurement.
- **REA focus:** Verify current availability/version; measure start skew/drift; test host departure and concurrent commands.
- **Source:** [App Store](https://apps.apple.com/in/app/mjam/id6759928590)

## 18 — Segno: Listen Together

- **Workflow:** Peer/shared audio session on iPhone and iPad; listing also describes voice mode ducking music.
- **Synchronization boundary:** Store listing claims sub-second sync and automatic drift correction; implementation is opaque.
- **Confidence caveat:** Claims need repeatable testing; listing text alone cannot reveal algorithm.
- **REA focus:** Measure start skew and drift; test voice/music ducking, peer loss and route changes.
- **Source:** [App Store](https://apps.apple.com/us/app/segno-listen-together/id6758889759)

## 19 — Discord Spotify Listen Along

- **Workflow:** Discord presence/invitation UI allows friends to join Spotify listening.
- **Synchronization boundary:** Discord exposes social presence and invite affordances; Spotify owns playback/session coordination.
- **Strengths:** Presence integration and social discovery.
- **Unknowns:** Private Spotify timing mechanism, availability constraints and account/plan restrictions.
- **REA focus:** Inspect only public user-facing flows; test presence state, join/leave and provider interruption.
- **Sources:** [Discord support](https://support.discord.com/hc/en-us/articles/115003966072-Listening-Along-with-Spotify), [Spotify integration help](https://support.spotify.com/article/discord-and-spotify/)

## 20 — Sound Room

- **Workflow:** iOS/iPadOS shared listening Jam associated with Subsonic-compatible music servers.
- **Synchronization boundary:** Jam server coordinates membership, shared queue and track/transport state; each listener streams from their own compatible server account.
- **Strengths:** Clean separation of room coordination from each listener's media source.
- **Unknowns:** Jam protocol, timing accuracy, server state persistence, reconnect and conflicting controls.
- **REA focus:** Change track/position from multiple participants; late join; server loss; compare actual playback positions.
- **Source:** [Official site](https://www.soundroom.in/)

## Cross-entry takeaway

These systems are references for collaboration, queue semantics, participant permissions and provider-aware playback state. They should inform a future Sync In It control/source layer only after PCM timing and acoustic alignment are reliable. They are not evidence that Sync In It needs cloud accounts or provider playback.
