# v0.9.0 Stable Release Readiness

**Decision:** <strong>NO-GO — do not publish v0.9.0 as a stable release yet.</strong>  
**Audit date:** 12 November 2026  
**Audited main commit:** `9f46f905ada3fe654aeec1053c1e620034e63d67`  
**Cloud migration:** merged through [PR #94](https://github.com/Shreyas-patil07/Sync-in-it/pull/94)  
**Latest published APK:** [v0.8.0](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.8.0) — previous direct-LAN implementation  
**v0.9.0 tag:** not present at audit time

This checklist separates a deployable cloud-transport preview from a stable release of the original synchronized-speaker product. A successful Android build or healthy backend is not sufficient release evidence.

## Evidence observed

| Area | Observation | Gate |
|---|---|---|
| Main integration | Cloud transport merged to main at `9f46f905` | Passed |
| Render deploy | Build and deploy for that commit succeeded; deploy status was `live` | Passed |
| Backend process liveness | Observed `HEAD /healthz` requests returned HTTP 200 | Passed — liveness only |
| Main CI | Android app/JNI, Backend API, Kotlin transport and M0 workflows were still queued at latest audit | Pending |
| LiveKit environment and token issuance | No live token-issuance or room-connection evidence captured | Not verified |
| Host PCM publishing and Speaker subscription | Unit tests use a fake publisher; no live end-to-end evidence captured | Not verified |
| Physical cross-network playback | No two-phone test evidence captured | Not verified |
| Acoustic synchronization | Current cloud path uses standard WebRTC playout and removed the prior clock/ANCHOR pipeline | Not implemented |
| Stable Android signing | Release-prep branch adds a production signing config; not merged or exercised with production secrets | Pending |
| Stable release publishing | Release-prep branch adds an explicit stable channel and manual sign-off; not merged or exercised | Pending |

## Release blockers

### 1. Product behavior and synchronization

The cloud migration is an audio-delivery architecture, not a synchronized-playback implementation. It removes the former sample-indexed jitter buffer, clock estimator, READY/ANCHOR group start and custom AudioTrack scheduler. Shared clock mapping, timestamped playout, drift correction, output-latency compensation and acoustic calibration are absent.

Before a stable release can claim the original Sync In It goal, restore or redesign this timing pipeline for the cloud transport and demonstrate measured multi-device acoustic skew against an explicitly adopted acceptance target. Until then, any release must be clearly scoped and marketed as cloud audio transport, not synchronized speakers.

### 2. Automated validation

All workflows must complete successfully on the exact release commit:

- Android app/JNI build and APK signature verification.
- Backend API test matrix on Python 3.11, 3.12 and 3.13.
- M0 simulator/native core matrix.
- Any applicable Kotlin transport checks.

The latest main-commit runs were queued during this audit. No passing result should be inferred from a queued run.

### 3. Live Render and LiveKit integration

Run and capture a real integration test with the production Render service and LiveKit project:

1. Create a Host session and obtain its QR invitation.
2. Join from a Speaker using the invitation; confirm the returned role is subscribe-only.
3. Confirm Host token scope allows publishing and Speaker token scope does not.
4. Start Host PCM ingress and verify a LiveKit audio track is published.
5. Subscribe from at least one Speaker and confirm audible output.
6. Verify malformed frame sizes, expired credentials, duplicate Host ingress and unauthorized role operations are rejected safely.
7. Exercise session close and reconnect behavior.

The current fake-publisher unit tests do not replace this integration test. Never include secret values or bearer tokens in test artifacts.

### 4. Physical network and reliability validation

Use at least two physical Android devices on separate internet connections (for example, one on mobile data and one on Wi-Fi). Test local-file playback and eligible playback capture, background/foreground transitions, permission denial, network loss/recovery, Host disconnect, Speaker rejoin and session expiry. Record device/Android versions, network type, timestamps, logs and pass/fail results.

For a stable service, resolve or explicitly accept the current persistence risks: Render Free local SQLite is ephemeral, and active WebSocket/publisher state is held in process memory. A restart can interrupt active sessions; multi-instance coordination and durable session/event storage are not implemented.

### 5. Production APK and release workflow

The publisher on `main` currently:

- Builds `:app:assembleDebug`, not `:app:assembleRelease`.
- Uses the preview signing key.
- Produces the debug application ID `com.syncinit.app.debug`.
- Always invokes `gh release create --prerelease`.

The release-preparation branch adds a separate stable lane that:

- Builds the `release` variant with application ID `com.syncinit.app`.
- Uses a dedicated production keystore and protected GitHub Actions secrets, separate from preview signing.
- Verifies the APK signature and certificate fingerprint.
- Confirms the app version name and monotonically increasing versionCode.
- Creates a non-prerelease GitHub release only after the required validation gates are signed off.

The new lane fails closed unless these GitHub Actions secrets are present: `SYNCINIT_RELEASE_KEYSTORE_BASE64` (base64-encoded production JKS), `SYNCINIT_RELEASE_KEYSTORE_PASSWORD`, `SYNCINIT_RELEASE_KEY_ALIAS` and `SYNCINIT_RELEASE_KEY_PASSWORD`. The operator must also enter the explicit `ALL_RELEASE_GATES_PASSED` acknowledgement, and the release notes must no longer identify the release as a preview or no-go. Preview tags use `vX.Y.Z-preview.N` or `vX.Y.Z-rc.N`; the unsuffixed `vX.Y.Z` tag is reserved for stable publication. The lane remains unmerged and untested with the production keystore; it does not remove the product, integration or physical-test blockers.

Do not reuse or expose signing keys in workflow logs, repository files or this checklist.

## Go/no-go checklist

A stable release is eligible only when every required item is evidenced on the exact release candidate:

- [ ] Product scope is explicit: synchronized-speaker behavior is implemented and measured, or the product is deliberately renamed/re-scoped as non-synchronized cloud audio distribution.
- [ ] All applicable GitHub Actions workflows pass on the release commit.
- [ ] Live Render + LiveKit token, publish and subscribe test passes.
- [ ] Two-device, separate-network playback test passes.
- [ ] Reconnect, permission, session-expiry and restart behavior are tested and documented.
- [ ] Persistence and instance-restart risks are resolved or formally accepted for the stated stable scope.
- [ ] Production-signed release APK is built and signature verified.
- [ ] Release notes accurately state supported functionality and remaining limitations.
- [ ] Stable release is created as a non-prerelease only after sign-off.

## Recommended release sequence

1. Complete CI for the current merged commit; investigate queue saturation or any failing job.
2. Validate real LiveKit credentials, role-scoped tokens and Host-to-Speaker audio.
3. Run physical cross-network and reconnect tests; capture evidence.
4. Decide whether v0.9.0 is a cloud-audio transport preview or a stable release of the synchronized-speaker product. The latter requires the synchronization architecture and acoustic measurements to be restored.
5. Merge the release-preparation changes after review, configure the four production signing secrets, and exercise the stable lane with a non-production test tag before the final version tag.
6. Update the v0.9.0 release notes to remove preview/no-go wording only after the evidence is complete, then publish the unsuffixed stable tag only after every applicable gate is green.

**Current recommendation:** keep v0.9.0 unpublished as stable. A preview build may be issued only with explicit cloud-audio-transport scope and prominent synchronization limitations.
