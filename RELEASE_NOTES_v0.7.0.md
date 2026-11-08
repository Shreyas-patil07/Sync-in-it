# Sync In It Android RTP Experimental Preview v0.7.0

**Release type:** Engineering prerelease — experimental RTP/UDP transport
**Platform:** Android Host + Android Speakers
**Build:** Signed, updateable Android debug APK (`com.syncinit.app.debug`)
**Previous preview:** v0.6.9

## What's new

### Feature-flagged RTP/UDP diagnostic audio

- Enables the Android RTP media path at build time for this experimental release.
- Keeps TCP for HELLO/WELCOME admission, AUDIO_HELLO admission, clock probes, READY, common ANCHOR and session control.
- Sends diagnostic PCM16 audio over unicast UDP on port `47803`; UDP clock synchronization remains on port `47802`.
- Uses the 48 kHz stereo PCM16 RTP profile (payload type 96). Each 20 ms source chunk is split into four 5 ms packets, each carrying 240 stereo frames / 960 payload bytes.
- Reconstructs RTP timestamped sample ranges into the existing native sample-indexed PCM buffer.
- Retains the existing TCP-based app build path when built with the RTP Gradle property disabled.

## Install and run the RTP test

1. Install **this exact v0.7.0 RTP Experimental APK on the Host and every Speaker**. Do not mix v0.6.9 or a TCP-only build into the same session.
2. Connect all phones to the same trusted private Wi-Fi network. The access point must permit client-to-client TCP, UDP and local-network discovery.
3. Keep both apps in the foreground. Start one Host and use the diagnostic 1 kHz test signal as the source.
4. Join from one Speaker and let the session run for at least 60 seconds.
5. Export the Network debugger report from both devices. Review the reports for sensitive local-network/device metadata before sharing them.

Expected transport observations include:
- Speaker: `RTP_UDP_REGISTRATION_ACCEPTED`
- Host: `HOST_RTP_UDP_PEER_REGISTERED`
- Host: `HOST_AUDIO_DELIVERY transport=RTP_UDP`
- Speaker: `SPEAKER_AUDIO_INGRESS transport=RTP_UDP`
- Approximately 200 RTP datagrams/second for one Speaker under steady source traffic, before network loss/reordering.

## Validation status and hard limitations

- The RTP implementation passed the Android/JNI build, Kotlin socket transport tests and native/simulator CI associated with PR #85 before merge.
- **Physical RTP delivery, audible output, READY/ANCHOR completion and acoustic synchronization have not yet been demonstrated.** This release is intended to collect that evidence.
- The existing strict clock-readiness gate remains unchanged at residual RMS **below 0.25 ms**, together with the existing sample/span and stable-streak requirements. A successful UDP registration or full PCM buffer does not satisfy this gate. Do not weaken the threshold to force playback.
- RTP packet counts do not prove that decoded PCM is non-silent, audible or synchronized.
- Opus encode/decode, periodic RTCP Sender Reports, populated compound RTCP Receiver Reports with SDES/CNAME, RTP-to-NTP mapping, DAC presentation mapping, drift correction and acoustic validation are not implemented in this slice.
- The current four-datagram send pattern is bursty (four 5 ms RTP ranges emitted for each 20 ms source chunk); it is not yet a paced 5 ms sender.
- Use this APK for controlled testing on a trusted private LAN only. The session token remains visible in DNS-SD metadata on the LAN; this preview does not provide transport encryption.

## Rollback

To return to the TCP-only path, install the latest TCP-only preview or build the app without `-PsyncinitRtpAudioEnabled=true`. Both Host and Speakers must use matching transport builds.

## Diagnostic report checklist

Preserve:
- Exact APK version and device Android versions.
- Host and Speaker diagnostic exports from the same session.
- Wi-Fi topology and audio route.
- Registration outcome, RTP receive/send rates, sequence gaps, reorder/duplicate counts and buffer missing-frame metrics.
- Clock residual and stable streak, READY/ANCHOR events, and whether the 1 kHz signal was physically audible.

A successful build or CI run is not a synchronized-playback acceptance result.
