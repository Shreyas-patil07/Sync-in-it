# Multiple-Phone Audio Distribution — Product Briefs

**Catalogue IDs:** 01–11 · **Research date:** 7 November 2026  
These are concise public-surface briefs. The exact private algorithms of closed products are not known.

## 01 — Unison

- **Workflow:** Nearby Android phones form a speaker array. One phone hosts; others join by name or QR; the host selects the track.
- **Documented model:** Local host-clock scheduling. Developer describes NTP-style clock alignment and per-device playback calibration. Devices play locally at a scheduled wall-clock target rather than relying on a single cloud-mixed stream.
- **Strengths:** Simple local workflow, QR join, per-device trim and background/lock-screen controls.
- **Unknowns:** Media transfer method, exact clock protocol, sample-rate correction, drift algorithm and stored calibration format. Publisher no-cloud statements are not an independent audit.
- **REA focus:** Compare 2/4/6-device start skew and 30-minute drift; Bluetooth route; screen-off/Doze; Wi-Fi loss/rejoin; persisted calibration.
- **Source:** [Google Play](https://play.google.com/store/apps/details?id=com.prajwol.unison)

## 02 — Boomi: Every Phone, One Sound

- **Workflow:** Host creates a room and shares a QR or short code. Other phones join; selected music is transferred with verification and played across devices.
- **Documented model:** Local clock alignment, automatic drift correction and per-device millisecond trim. Listing describes a device dashboard with latency, battery and synchronization health.
- **Strengths:** File-transfer verification avoids requiring every phone to start with an identical file; useful exposed diagnostics and local-only positioning.
- **Unknowns:** File-transfer protocol/checksum, LAN encryption/authentication, clock estimator, correction strategy (resampling, time-stretch, insert/drop) and crash recovery. Premium/device caps may apply.
- **REA focus:** Corrupt/interrupted transfer and hash mismatch; Wi-Fi vs hotspot; mixed Android versions; compare dashboard with acoustic capture; route changes and short packet interruption.
- **Source:** [Google Play](https://play.google.com/store/apps/details?id=com.nevsoft.boomi)

## 03 — SoundSeeder

- **Workflow:** SoundSeeder Player streams to Speaker clients on the same Wi-Fi; Android and desktop receiver support and microphone/AUX sources are documented.
- **Documented model:** LAN discovery with player/speaker roles and manual per-speaker output offset. Help describes offset adjustment in 10 ms steps over roughly −400 to +400 ms, saved for future connections.
- **Strengths:** Mature cross-platform receiver model, channel assignment (stereo/left/right/mono), multiple sources and persistent manual latency compensation.
- **Unknowns:** Clock protocol, packet format, buffers, codecs/transports, automatic drift correction and LAN security.
- **REA focus:** Check offset repeatability/persistence; 2–5 clients; segmented network/manual IP; Bluetooth; 1-hour drift; jitter/dropouts under load.
- **Sources:** [Product site](https://soundseeder.com/), [Speaker mode](https://soundseeder.com/help/speaker-mode/), [sync playback](https://soundseeder.com/help/sync-playback/), [speaker management](https://soundseeder.com/help/speaker-management/)

## 04 — SoundSync: Multi Phone Speaker

- **Workflow:** Connect phones over Wi-Fi, hotspot or Wi-Fi Direct. Public listing mentions multiple endpoints, audio/video, file sharing, client queue requests, remote volume, EQ and desktop/Linux/Raspberry Pi speaker clients.
- **Documented model:** Synchronized local playback is described, but the exact clock and transport model is not public.
- **Strengths:** Broad source/control feature surface and non-phone receiver options.
- **Unknowns:** Whether source media is streamed or copied, clock model, drift control, permissions, encryption and endpoint limits under real conditions.
- **REA focus:** Compare Wi-Fi/hotspot/Wi-Fi Direct; inspect data movement; test client queue conflicts; 2/5/10 endpoints where allowed; record acoustic skew.
- **Source:** [Google Play](https://play.google.com/store/apps/details?id=com.starstudio.soundsync.music.sync)

## 05 — SyncWave: Sync Music

- **Workflow:** Nearby-device audio session with screen-sharing features. Listing gives Android 8+ and says audio streaming requires Android 10+.
- **Documented model:** Host-to-nearby-device connection; vendor says connection delays are measured and compensated.
- **Strengths:** Explicit mention of delay compensation and cross-device screen/media workflow.
- **Unknowns:** Clock domain, audio packetization, whether delay measurement is per receiver, drift correction, buffering and network fallback.
- **REA focus:** Capture observable join/start events, change network conditions, compare audio start on multiple Android versions and test background restrictions.
- **Source:** [Google Play](https://play.google.com/store/apps/details?id=com.komsomol.syncwave)

## 06 — UniTune (unitune.io)

- **Workflow:** Host selects music and nearby participants join on the same network for shared playback.
- **Documented model:** Shared host-led playback workflow; detailed timing implementation is sparse.
- **Strengths:** Nearby host-and-stream concept is close to the local phone-array use case.
- **Unknowns:** Platform listing availability, audio source/transfer, clock synchronization, buffering, drift correction and security.
- **REA focus:** Verify current app availability and device support first; then map host/join, source and receiver behaviour on a controlled LAN.
- **Sources:** [Site](https://unitune.io/), [how it works](https://unitune.io/how-it-works/)

## 07 — WaveSync

- **Workflow:** Android host streams over local Wi-Fi to nearby devices.
- **Documented model:** Listing claims low-latency synchronized playback but does not publish the clock algorithm.
- **Strengths:** Direct local-Wi-Fi phone-array comparison.
- **Unknowns:** Media path, protocol, offset estimation, jitter buffer, drift correction and independent timing results.
- **REA focus:** Confirm app identity/version, then measure start skew, long-run drift, reconnect and network sensitivity.
- **Source:** [Google Play](https://play.google.com/store/apps/details?id=com.kunano.wavesynch)

## 08 — SMusic: Listen Music Together

- **Workflow:** Online/offline shared sessions; Wi-Fi Direct is explicitly mentioned in the listing.
- **Documented model:** Session-based sharing; exact timing method is not published.
- **Strengths:** Offline mode and Wi-Fi Direct are potentially useful in router-free environments.
- **Unknowns:** Online relay/cloud usage, actual collected data, media transport, synchronization and host failover.
- **REA focus:** Test offline Wi-Fi Direct separately from online mode; capture egress; verify QR invitation controls; inspect permissions and screen-off behaviour.
- **Source:** [Google Play](https://play.google.com/store/apps/details?id=com.smusic.sync)

## 09 — LekSync

- **Workflow:** Hybrid local-hotspot and online-room modes; Android host with browser/PWA receivers across phones, tablets and computers.
- **Documented model:** Vendor claims timestamp scheduling, Opus, WebRTC online mode, encryption and sub-100 ms synchronization.
- **Strengths:** Hybrid LAN/WAN operation and browser endpoints make it an interesting mixed-device reference.
- **Unknowns:** Signaling backend, STUN/TURN policy, key exchange, jitter-buffer algorithm, timestamp clock domain, resampling and reconnect semantics. Performance claims are not independently verified.
- **REA focus:** Determine when TURN is used; inspect permitted SDP/ICE/network metadata; test 2/5 devices, p50/p95 acoustic skew, Bluetooth and browser lifecycle.
- **Sources:** [Site](https://leksync.in/), [about](https://leksync.in/about/)

## 10 — AmpMe

- **Workflow:** Social parties across phones; users can connect each phone to a separate Bluetooth speaker, with chat/reactions and party discovery.
- **Documented model:** Local device playback coordinated through a party; FAQ describes manual/user-facing synchronization adjustment. It is not documented as raw host-to-client PCM.
- **Strengths:** Social party layer, cross-platform experience and Bluetooth-per-phone arrangement.
- **Unknowns:** Current content integrations, exact sync API, server/event model, anti-drift correction, offline semantics and retention. Some FAQ OS statements may be stale.
- **REA focus:** Verify current builds/integrations; Android+iOS party; microphone start-skew measurement; Bluetooth routes; interruptions and background behaviour.
- **Sources:** [FAQ](https://get.ampme.com/faq?locale=en_US), [site](https://ampme.com/)

## 11 — Sync Music (com.eb.sync_music)

- **Workflow:** Identity/current listing could not be authoritatively confirmed in this research pass.
- **Documented model:** Unknown; do not infer from similarly named apps.
- **Unknowns:** Publisher, current package identity, version, platforms, host/client design, media path, clock, privacy and pricing.
- **REA focus:** Resolve official Play Store identity on-device before installation. Do not use unrelated APK mirrors.
- **Source:** [Google Play URL](https://play.google.com/store/apps/details?id=com.eb.sync_music)

## Cross-entry takeaway

For phone-speaker products, compare host/join friction, local-only claims, calibration controls, endpoint health visibility, media transfer integrity, Android lifecycle, Bluetooth behaviour and measured acoustic drift. Store listings and vendor descriptions do not reveal private timing internals.
