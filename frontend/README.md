# Sync In It Frontend

Public project website and Android preview downloads page. The site is intentionally explicit about the difference between implementation progress, CI verification and physical-device evidence.

## Current content source

The public site presents the Android engineering preview and its validation state. Keep release version, supported scope and validation claims aligned with [the canonical implementation status](../docs/11_Current_Implementation_Status.md) and [the chronological test log](../docs/Test_Results.md).

- Latest public pre-release: [v0.5.9-preview.1](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.5.9-preview.1).
- Published APK: `SyncInIt-Android-preview-v0.5.9.apk`.
- The APK includes PR #49 READY/audio diagnostics, PR #53 receiver-specific acoustic markers/analyzer tooling, and PR #55 cached PCM snapshots plus Speaker ingress and Host slow-write diagnostics.
- The latest post-PR #55 Speaker-side physical log (6 Nov 2026) reports PCM ingress mostly around 49–53 frames/sec, zero missing frames and a buffer up to approximately 19.94 seconds. Clock residual RMS remains around 0.412–0.557 ms, above the unchanged <0.25 ms gate; stable streak stayed 0 and READY was not demonstrated.
- The post-fix excerpt does not include matching Host queue/write diagnostics, an ANCHOR event or an acoustic recording. Therefore it does not establish Host writer health or synchronized playback.
- Receiver-specific acoustic markers and the WAV analyzer are available. Synthetic analyzer tests pass CI, but no physical phone-skew measurement is recorded. The first acoustic run must be screen-on/foreground and approximately two minutes; longer runs are blocked until the foreground-service/wake-lock lifecycle exists.
- Android-only; the current Host code admits up to eight Speakers. The source is a generated 1 kHz timing-click stream, not user-selected music or system-audio capture.
- The PR #55 merged-main Android/JNI build, transport checks and signature verification passed CI. Post-fix Speaker ingress was observed on-device; clock stability/READY failed and Host queue behavior remains unevaluated for that run. Synchronized playback remains unverified.

## Stack

- React 18 + TypeScript
- Vite 6
- Custom responsive CSS
- No external UI component or animation framework

## Local development

Requirements: Node.js 20+ and npm.

```bash
cd frontend
npm ci
npm run dev
```

## Production build

```bash
npm run build
npm run preview
```

Build output is generated in `frontend/dist/`.

## Deployment

Deploy the `frontend/` directory as the project root in Vercel or another static host.

- Install command: `npm install`
- Build command: `npm run build`
- Output directory: `dist`

The Vercel rewrite supports client-side navigation for `/` and `/downloads`. Keep download URLs pinned to an existing GitHub Release tag and verified asset filename.

## Pages

- `/` — product overview, implemented capability, milestone status, latest physical test evidence and FAQ.
- `/downloads` — current Android pre-release, APK/checksum links and limitations.
- Other paths — branded 404 view.

## Content maintenance rules

1. Update release constants and page copy whenever a new release is published.
2. Do not claim a milestone is validated merely because its code exists or CI passes.
3. Keep physical test evidence and CI status separate.
4. Never describe the current test signal as user music playback.
5. Keep the local-network token/security limitation visible.
6. Link engineering status claims to `docs/11_Current_Implementation_Status.md`.
