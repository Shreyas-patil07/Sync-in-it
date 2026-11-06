# Sync In It Frontend

Public project website and Android preview downloads page. The site is intentionally explicit about the difference between implementation progress, CI verification and physical-device evidence.

## Current content source

The public site presents the current Android engineering preview, its multi-Speaker implementation path and the latest physical READY-gate failure. Keep release version, supported scope and validation claims aligned with [the canonical implementation status](../docs/11_Current_Implementation_Status.md).

- Latest public pre-release: [v0.5.7-preview.1](https://github.com/Shreyas-patil07/Sync-in-it/releases/tag/v0.5.7-preview.1).
- Published APK: `SyncInIt-Android-preview-v0.5.7.apk`.
- The published APK predates the READY/audio diagnostic instrumentation merged in PR #49.
- The latest supplied physical run reached discovery, TCP admission and UDP response, but no Speaker reached READY. Synchronized multi-phone playback is not a verified result.
- Receiver-specific markers and a WAV analyzer are in branch `measurement/two-phone-skew-tooling`; the public v0.5.7 APK does not include them.
- No phone skew measurement is recorded yet. The first measurement is screen-on/foreground and approximately two minutes; longer runs are blocked until the foreground service/wake-lock lifecycle exists.
- Android-only; the current Host code admits up to eight Speakers. The source is a generated 1 kHz timing-click stream, not user-selected music or system-audio capture.
- The current-main diagnostic instrumentation needs a fresh successful Android/JNI build before it can be published as a new APK.

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
