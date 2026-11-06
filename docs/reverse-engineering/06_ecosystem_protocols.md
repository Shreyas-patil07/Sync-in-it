# Multi-Room Speaker Ecosystems and Protocols — Product Briefs

**Catalogue IDs:** 32–36 · **Research date:** 7 November 2026

## 32 — AirPlay 2

- **Workflow:** Apple devices send audio/video to compatible receivers and speaker groups.
- **Synchronization boundary:** Apple-managed multi-room playback; low-level timing/protocol details are not fully public in the cited product materials.
- **Strengths:** Mature ecosystem integration and managed receiver behaviour.
- **Unknowns:** Proprietary group clock, transport and device calibration.
- **REA focus:** Public-supported route/group semantics, endpoint add/remove, route switching and acoustic performance on actual compatible hardware.
- **Sources:** [AirPlay](https://www.apple.com/airplay/), [Apple user guide](https://support.apple.com/guide/iphone/stream-audio-and-video-to-other-devices-iph0f8b4d4b8/ios)

## 33 — Google Cast / Speaker Groups

- **Workflow:** Controller apps select compatible Chromecast, Nest and Cast receivers/groups.
- **Synchronization boundary:** Cast ecosystem coordinates group playback; sender/receiver media-status messages keep controller state aligned. Exact speaker-group timing internals are only partly public.
- **Strengths:** Clear sender/receiver application model and public audio integration docs.
- **Unknowns:** Group clock and audio scheduling details not exhaustively exposed by the referenced sources.
- **REA focus:** Group creation/removal, media status, reconnect and sender handoff; do not conflate status event timing with acoustic arrival.
- **Sources:** [Google Home speaker groups](https://support.google.com/googlehome/answer/10012636), [Cast audio docs](https://developers.google.com/cast/docs/audio)

## 34 — Sonos

- **Workflow:** Sonos controller manages purpose-built network speakers and grouped playback.
- **Synchronization boundary:** Sonos players coordinate group playback; controller is primarily a control surface.
- **Strengths:** Public developer docs describe components, interfaces, groups and playback sessions.
- **Unknowns:** Proprietary timing and internal network/audio mechanisms are not fully public.
- **REA focus:** Inspect documented group/session semantics, coordinator role, group transitions, member removal and source changes.
- **Sources:** [How Sonos works](https://docs.sonos.com/docs/how-sonos-works), [groups](https://docs.sonos.com/reference/groups), [components](https://docs.sonos.com/docs/components-and-interfaces), [playback sessions](https://docs.sonos.com/docs/playback-sessions)

## 35 — WiiM Multi-room

- **Workflow:** WiiM network audio devices controlled by WiiM Home and supported third-party apps/sources.
- **Synchronization boundary:** Vendor-managed local multi-room playback; official docs state control/audio communications remain on the LAN and are encrypted.
- **Strengths:** Local network topology and security model are described at product level.
- **Unknowns:** Precise clock model, correction loop, measured skew and per-route output delay.
- **REA focus:** Map local source/control paths, router outage, group membership, route handoff and acoustic start/drift.
- **Sources:** [Multi-room architecture](https://faq.wiimhome.com/en/support/solutions/articles/72000658994-introducing-wiim-multi-room-management-system), [related FAQ](https://faq.wiimhome.com/en/support/solutions/articles/72000659116)

## 36 — Amazon Alexa Multi-Room Music

- **Workflow:** Cloud-managed Echo and supported Alexa-compatible speaker groups configured through Alexa.
- **Synchronization boundary:** Amazon-managed group playback; exact clock and transport internals are not public.
- **Strengths:** Familiar group setup and service-managed multi-room capability.
- **Unknowns:** Internal network/cloud boundary, group clock, failure handling and exact device timing.
- **REA focus:** Public setup/compatibility; group member loss, Wi-Fi interruption, voice commands and device rejoin.
- **Sources:** [Amazon support](https://digprjsurvey.amazon.co.uk/csad/help/node/GZ5U38E9GGBBWEM8), [Amazon overview](https://www.aboutamazon.co.uk/news/devices/alexa-multi-room-music-set-up)

## Cross-entry takeaway

These are useful for understanding managed receiver ecosystems, grouping semantics, output-route integration and controller/player boundaries. They are not direct replacements for arbitrary Android phones as endpoints, and private timing internals must not be guessed from product behaviour.
