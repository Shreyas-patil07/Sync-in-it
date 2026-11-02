# Sync In It Frontend

The public website for Sync In It. The homepage introduces the product through a logo-led hero, benefit-focused sections, the current Android preview capabilities, development status and FAQs. The downloads page links to the Android engineering preview v0.4.0-preview.1 and its SHA-256 checksums.

Built with React, TypeScript and Vite. The interface uses a lightweight custom CSS system and the original project logo, with no additional component or animation dependency.

## Local development

Requirements: Node.js 20+ and npm.

```bash
cd frontend
npm install
npm run dev
```

## Production build

```bash
npm run build
npm run preview
```

The production output is generated in `frontend/dist/`.

## Deployment

Deploy the `frontend/` directory as the project root in Vercel or another static host.

- Build command: `npm run build`
- Output directory: `dist`
- Install command: `npm install`

The Vercel rewrite sends client-side routes to the app entry point. Static assets remain served from the public directory.

## Pages

- `/` — brand-led landing page, product benefits, intended usage flow, project status and FAQ.
- `/downloads` — current release state, source repository and GitHub Releases.
- Unknown routes — branded 404 view.

## Project status

M0 simulator/timing work and M1 Android integration remain in progress. The Android v0.4.0-preview.1 engineering release supports nearby Host discovery over mDNS, two-channel TCP session admission and UDP four-timestamp clock acquisition through the shared C++ core.

The preview does not implement audio chunk streaming, PCM buffering/rendering, scheduled playback, a foreground playback service or acoustic synchronization. The receiver remains in Synchronizing while its reported audio buffer is empty. Physical two-phone admission and sustained clock synchronization still require retesting; no measured synchronized-playback result is claimed.

The current M2a PCM16 codec and bounded jitter-buffer work is in open PR #28 and is not part of the main-branch preview. The current Android Host UI admits one receiver; multi-receiver group operation is not validated. iOS networking and playback are not implemented.

The session token is currently visible in the local DNS-SD service record. The preview should be used only on a trusted private Wi-Fi network.

## Brand assets

The website uses the original project logo at `/images/sync_in_it.png`. Preserve the original asset and its aspect ratio.
