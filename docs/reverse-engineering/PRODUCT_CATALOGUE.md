# Product Catalogue

**Research date:** 7 November 2026  
**Entries:** 38  
**Confidence:** confidence refers to public evidence for the described workflow/model, not measured synchronization quality.

| ID | Product | Class | Publicly described synchronization model | Confidence / primary source |
|---:|---|---|---|---|
| 01 | Unison | Multi-phone audio distribution | Local host-clock scheduling; developer describes NTP-style clock alignment and per-device playback calibration. | Medium — [Google Play](https://play.google.com/store/apps/details?id=com.prajwol.unison) |
| 02 | Boomi | Multi-phone audio distribution | Local clock alignment, automatic drift correction, per-device millisecond trim and verified file transfer claims. | Medium — [Google Play](https://play.google.com/store/apps/details?id=com.nevsoft.boomi) |
| 03 | SoundSeeder | Multi-device audio distribution | LAN discovery, synchronized player/speaker roles and saved manual per-speaker offset calibration. | High for documented workflow; internals unknown — [site](https://soundseeder.com/), [sync help](https://soundseeder.com/help/sync-playback/) |
| 04 | SoundSync | Multi-phone audio distribution | Synchronized local playback; exact clock and transport model not published. | Medium — [Google Play](https://play.google.com/store/apps/details?id=com.starstudio.soundsync.music.sync) |
| 05 | SyncWave | Multi-phone audio distribution + screen sharing | Host-to-nearby-device session; listing says connection delays are measured and compensated. | Medium — [Google Play](https://play.google.com/store/apps/details?id=com.komsomol.syncwave) |
| 06 | UniTune (unitune.io) | Multi-phone audio distribution | Host selects music; participants join the same network and share playback; timing details sparse. | Low–medium — [site](https://unitune.io/), [workflow](https://unitune.io/how-it-works/) |
| 07 | WaveSync | Multi-phone audio distribution | Host streams over local Wi-Fi; low-latency sync is a product claim, clock algorithm not public. | Medium — [Google Play](https://play.google.com/store/apps/details?id=com.kunano.wavesynch) |
| 08 | SMusic | Multi-phone audio distribution | Online/offline shared session; Wi-Fi Direct is mentioned; exact timing method unknown. | Medium — [Google Play](https://play.google.com/store/apps/details?id=com.smusic.sync) |
| 09 | LekSync | Hybrid local + internet multi-phone audio | Vendor describes timestamp scheduling, Opus, WebRTC online mode and sub-100 ms sync; independent tests absent. | Medium — [site](https://leksync.in/), [about](https://leksync.in/about/) |
| 10 | AmpMe | Social multi-phone audio party | Multi-device party playback with user-facing/manual sync adjustment. | Medium — [FAQ](https://get.ampme.com/faq?locale=en_US) |
| 11 | Sync Music (com.eb.sync_music) | Multi-phone audio distribution | No current synchronization model independently established. | Low / unverified — [Google Play listing](https://play.google.com/store/apps/details?id=com.eb.sync_music) |
| 12 | Spotify Jam | Licensed streaming shared session | Spotify-coordinated shared playback and collaborative queue; internal clock protocol is proprietary. | High for UX; internal protocol unknown — [support](https://support.spotify.com/article/jam/) |
| 13 | Apple SharePlay | OS-level shared media session | System-level synchronized activity over FaceTime or supported Messages activities. | High for UX; internal timing private — [Apple guide](https://support.apple.com/guide/iphone/shareplay-watch-listen-play-iphb657eb791/ios) |
| 14 | Stationhead | Social live-radio/broadcaster platform | Listeners follow a live show while using linked streaming-service accounts; not a phone speaker array. | High for workflow; internals unknown — [FAQ](https://stationhead.com/faqs) |
| 15 | Eclipse Music | Personal library + shared Jam + device handoff | Shared Jam state and device handoff; exact timing protocol not disclosed. | Medium — [site](https://www.eclipsemusic.app/) |
| 16 | Rave | Cross-platform synchronized watch/listen party | Shared playback-state synchronization for supported services/content; exact mechanism proprietary. | High for UX; internals unknown — [FAQ](https://rave.io/faq) |
| 17 | mJam | Shared listening session | Host-controlled synchronized playback; listing makes precision-alignment claims without exposing implementation. | Low–medium — [App Store](https://apps.apple.com/in/app/mjam/id6759928590) |
| 18 | Segno | Peer/shared audio session | Listing claims sub-second sync and automatic drift correction; voice mode ducks music. | Low–medium — [App Store](https://apps.apple.com/us/app/segno-listen-together/id6758889759) |
| 19 | Discord Spotify Listen Along | Presence + shared playback invitation | Discord provides presence/invite UI; Spotify handles playback coordination. | High for UX — [Discord support](https://support.discord.com/hc/en-us/articles/115003966072-Listening-Along-with-Spotify) |
| 20 | Sound Room | Self-hosted music library + synchronized Jam | Jam server coordinates membership/queue/track state; each listener streams from their own Subsonic-compatible account. | High for documented boundary — [site](https://www.soundroom.in/) |
| 21 | listenw.us | Browser-based social listening rooms | Website claims server-side synchronized playback; timing accuracy not independently verified. | Medium — [how it works](https://listenw.us/how-it-works) |
| 22 | ListenTogether for YouTube Music | Browser extension + room sync | FAQ describes WebSocket playback-action broadcast and network-latency compensation. | Medium–high for documented control model — [site](https://listentogether.now/) |
| 23 | Together | Open-source browser watch/listen party | WebSocket room state, playback drift correction and late-join synchronization. | High — [GitHub](https://github.com/chtnnh/together) |
| 24 | Popmelon | Browser extension watch/listen party | Shared party controls and synchronized playback across supported sites; protocol private. | Medium — [site](https://popmelon.app/) |
| 25 | Snapcast | Open-source synchronized audio distribution | Client/server clock-offset estimation, timestamped PCM chunks, buffered scheduled playout and sample-level drift correction. | Very high — [GitHub](https://github.com/snapcast/snapcast), [protocol](https://github.com/snapcast/snapcast/blob/develop/doc/binary_protocol.md) |
| 26 | Music Assistant | Open-source music server/provider/player abstraction | Player-provider dependent; supports synchronized groups including Snapcast and compatible players. | High — [docs](https://www.music-assistant.io/documentation/), [Snapcast provider](https://www.music-assistant.io/player-support/snapcast/) |
| 27 | Lyrion Music Server | Open-source server for synchronized players | Server-managed player groups; public CLI exposes sync/unsync and group-membership queries. | High — [server overview](https://lyrion.org/reference/lyrion-music-server/), [player CLI](https://lyrion.org/reference/cli/players/) |
| 28 | Squeezelite | Headless synchronized audio player client | Uses Lyrion Music Server's native player synchronization. | High — [docs](https://lyrion.org/players-and-controllers/squeezelite/), [source](https://github.com/ralph-irving/squeezelite) |
| 29 | SonoBus | Peer-to-peer low-latency audio networking | Live audio transport with configurable jitter buffers; not a fixed-song multi-room sync product. | Very high for high-level architecture — [GitHub](https://github.com/sonosaurus/sonobus) |
| 30 | Jamulus | Low-latency online musical collaboration | Client/server live audio mixing with jitter buffers; optimizes performance networking, not multi-room song playback. | Very high for high-level architecture — [FAQ](https://jamulus.app/wiki/FAQ) |
| 31 | Airfoil / Airfoil Satellite | Desktop audio routing to remote outputs | Compensates for differing output transport delays by delaying local playback to the slowest endpoint. | High for documented product behaviour — [manual](https://www.rogueamoeba.com/support/manuals/airfoil/), [latency](https://rogueamoeba.com/support/knowledgebase/?showArticle=Airfoil-AudioLatency) |
| 32 | AirPlay 2 | Proprietary network-audio ecosystem | Apple-managed multi-room playback synchronization; low-level protocol not fully public. | High for ecosystem capabilities — [Apple](https://www.apple.com/airplay/) |
| 33 | Google Cast / Speaker Groups | Receiver-based network media ecosystem | Cast ecosystem coordinates group playback; sender/receiver media-status messages align controller state. | High for Cast app model; internals partly private — [groups](https://support.google.com/googlehome/answer/10012636), [audio docs](https://developers.google.com/cast/docs/audio) |
| 34 | Sonos | Proprietary multi-room speaker ecosystem | Sonos players synchronize group playback; controller acts primarily as control surface. | High — [architecture](https://docs.sonos.com/docs/how-sonos-works), [groups](https://docs.sonos.com/reference/groups) |
| 35 | WiiM Multi-room | Local-LAN speaker ecosystem | Vendor-managed local multi-room playback; docs state control/audio communications remain on LAN and are encrypted. | High for topology; precise timing unknown — [architecture](https://faq.wiimhome.com/en/support/solutions/articles/72000658994-introducing-wiim-multi-room-management-system) |
| 36 | Amazon Alexa Multi-Room Music | Cloud-managed smart-speaker group | Amazon-managed group playback; internal clock and transport details are not public. | High for setup/product boundary — [support](https://digprjsurvey.amazon.co.uk/csad/help/node/GZ5U38E9GGBBWEM8) |
| 37 | PlayOver | Cross-platform music-link converter | No synchronized playback; converts links between music services. | Medium — [site](https://playover.app/) |
| 38 | JukeboxDuo | Browser collaborative jukebox | Shared room clock and synchronized playback are claimed; exact drift algorithm is not public. | Medium — [features](https://jukeboxduo.com/features), [about](https://jukeboxduo.com/about) |

## Reading this catalogue

- **Category** tells you the product family; it is not a performance rating.
- **Confidence** measures how well the research could substantiate the described workflow/architecture from public evidence.
- **Synchronization model** summarizes the public surface, not the hidden implementation.
- The listed public sources do not independently validate marketing performance claims.

See [Architecture Comparison](ARCHITECTURE_COMPARISON.md), [Research Notes](RESEARCH_NOTES.md) and [Sources](SOURCES.md).
