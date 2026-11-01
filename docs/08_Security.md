# Security and Privacy — Sync In It

**Document status:** Draft security baseline  
**Version:** 1.0  
**Last updated:** 1 November 2026

## 1. Security objective

Sync In It is designed for a private local Wi-Fi session without cloud services. The security objective for the prototype is to prevent casual unauthorized joining, limit accidental information exposure and handle untrusted network input safely.

A private router and session token do not by themselves provide end-to-end confidentiality or robust protection against an attacker already on the LAN. The current protocol draft sends token-authenticated traffic without transport encryption. That is a known limitation, not a secure-production design.

## 2. Assets

| Asset | Why it matters |
|---|---|
| Session token | Allows a receiver to join the session |
| Session control | Can start, pause, mute or resynchronize receivers |
| Audio stream | User-selected media may be private or copyrighted |
| Device metadata | Model/platform information can identify a device class |
| Calibration data | Describes a device's output behavior and physical setup |
| Diagnostic logs | Can reveal network addresses, timing and device information |
| Microphone input | Used for acoustic calibration and must not be captured unexpectedly |

## 3. Threat model

Potential threats include:
- A nearby person on the same Wi-Fi attempts to join.
- A participant shares the QR/token with an unintended person.
- A malicious or buggy receiver sends malformed frames.
- An attacker on the LAN observes or alters unencrypted traffic.
- A receiver sends excessive data or attempts to exhaust master buffers.
- A compromised phone reads local logs or calibration settings.
- A user unintentionally grants microphone or playback-capture permission.

Out of scope for the initial prototype:
- Defense against a fully compromised Android/iOS operating system.
- Protection against physical access to an unlocked phone.
- Enterprise-grade device management.
- A formal cryptographic security certification.

## 4. Current prototype controls

### 4.1 Network isolation

- Use a dedicated WPA2/WPA3-protected router with a non-default password.
- Disable client isolation only because peer-to-master communication requires it; do not bridge the test network to an untrusted LAN.
- Avoid public Wi-Fi and open hotspots.
- Internet access is not required; disable WAN connectivity when practical.
- Restrict access to the session ports to the private network.

### 4.2 Session invitation

- Generate a cryptographically secure random 16-byte token for each session.
- Put the token in the QR payload and verify it during HELLO.
- Do not reuse tokens across sessions.
- Do not write the token to application logs or diagnostic exports.
- Invalidate the token when the session ends.
- Limit receiver count and reject invalid tokens before allocating large buffers.

### 4.3 Transport limitations

The current draft sends the session token and audio/control messages over plain TCP/UDP on the local network. WPA2/WPA3 protects the Wi-Fi link against outsiders but does not provide application-level confidentiality or integrity between participating LAN devices.

This is acceptable only as a controlled prototype assumption. Before use on an untrusted or shared network, add authenticated key establishment and encryption/integrity protection for control, audio and time-sync channels. A token alone does not prevent replay or traffic observation.

## 5. Input validation and resource protection

Every network-facing parser must:
- Validate the TCP frame length before allocation or copy.
- Reject lengths below the minimum header and above the configured maximum.
- Validate every variable-length field against remaining frame bytes.
- Validate sample counts, channels, codec IDs and payload lengths.
- Validate session ID and receiver ID on every relevant packet.
- Reject malformed timestamp values and impossible state transitions.
- Handle unknown messages safely according to versioning rules.
- Apply rate limits to connection attempts, invalid tokens and control messages.
- Enforce per-receiver and global queue/buffer limits.
- Use bounded send queues and disconnect persistently abusive peers.
- Avoid trusting device model strings or other receiver-supplied metadata.

The audio callback must never parse network input directly.

## 6. Authorization model

- The master is the authority for session state.
- A receiver with a valid session token may join within the capacity limit.
- Only the master may issue group-wide timeline anchors, mute/unmute, compensation and volume changes in the current protocol.
- Receiver reports are untrusted observations; the master validates ranges and freshness before acting on them.
- Receiver IDs are assigned by the master and must be tied to the authenticated control connection.
- A receiver must not be able to claim another receiver's ID on the audio socket.

## 7. Permissions and privacy

### Microphone
Microphone input is used only for the explicit acoustic-calibration workflow. Show a visible calibration state, request permission in context and stop capture immediately when calibration completes or is cancelled.

### Playback capture
Android system-audio capture requires user approval and is subject to source-app policy. Do not attempt to bypass capture restrictions. If capture is denied, offer local-file playback.

### Camera
Camera access is required only for QR scanning. Provide a manual connection alternative.

### Diagnostics
- Do not include audio recordings by default.
- Avoid storing names or contacts.
- Treat device model, network address and timestamps as potentially sensitive diagnostic metadata.
- Make exports user-initiated.
- Explain what is included before export.
- Apply retention limits to temporary logs.
- Redact tokens, secrets and private media paths.

## 8. Local storage

- Store preferences in platform-appropriate private app storage.
- Do not store session tokens beyond session lifetime by default.
- Do not store Wi-Fi passwords.
- Keep calibration profiles scoped to device model, audio route and physical layout context.
- Do not persist raw microphone recordings unless the user explicitly chooses a diagnostic recording feature in a future version.
- Validate imported/exported diagnostic files before parsing them.

## 9. Operational security

- Display the session as active while the master is hosting.
- Provide an explicit End Session action.
- Close sockets and invalidate the session token at shutdown.
- Release microphone, playback capture and wake/Wi-Fi locks when no longer needed.
- Stop or mute output safely after master loss or severe synchronization failure.
- Do not leave background capture active after the UI indicates the session has ended.

## 10. Future hardening roadmap

Before broader or untrusted deployment:
1. Define a threat model for hostile LAN participants.
2. Add authenticated key exchange using a reviewed cryptographic library.
3. Encrypt and authenticate control and audio traffic.
4. Authenticate time-sync packets or bind them to an authenticated session key.
5. Add replay protection and sequence validation.
6. Add robust session revocation and receiver eviction.
7. Security-review parser boundaries and fuzz all binary decoders.
8. Review Android exported components, intents, deep links and permissions.
9. Review iOS URL handling, local-network permission and data protection.
10. Define a privacy policy for any diagnostic data collected or shared.

Do not invent a custom encryption algorithm. Use established libraries and protocols.

## 11. Security acceptance checklist

- [ ] Token is generated from a cryptographically secure source.
- [ ] Token is unique per session and not logged.
- [ ] Invalid token is rejected before resource-intensive setup.
- [ ] Oversized and truncated frames are rejected.
- [ ] Per-receiver buffers and queues are bounded.
- [ ] Unknown message types cannot desynchronize the parser.
- [ ] Receiver IDs are bound to their authenticated connections.
- [ ] Session shutdown invalidates credentials and releases resources.
- [ ] Microphone and playback capture are visibly active only when expected.
- [ ] Diagnostic export excludes tokens and audio by default.
- [ ] The lack of transport encryption is clearly disclosed for prototype builds.

## 12. Source documents

The initial security assumptions are based on sections 2, 8 and 15 of [docs/System Structure.md](../docs_original/System%20Structure.md). The controls in this document strengthen and clarify that baseline; they do not claim that the current wire protocol is encrypted.
