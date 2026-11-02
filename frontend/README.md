# Sync In It Frontend

The public project website for Sync In It. It explains the offline multi phone audio synchronization model, publishes the engineering status, links to project documentation and keeps unreleased application builds clearly unavailable.

Built with React, TypeScript and Vite. Styling uses the project CSS system without an additional component or animation dependency.

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

The Vercel rewrite sends client side routes to the app entry point. Static assets remain served from the public directory.

## Pages

- `/` — product story, timing model, workflow, architecture, engineering targets, roadmap and FAQ.
- `/downloads` — current release state, source repository and GitHub Releases.
- Unknown routes — branded 404 view.

## Project status

M0, the simulator and timing core, is in progress. The Android application and physical device playback prototype have not been implemented. There are no public APK or iOS builds.

The site does not claim measured acoustic synchronization results. T1, T2 and T3 are engineering acceptance goals that still require physical device testing.

## Brand asset

The site uses the repository logo at `/images/sync_in_it.png`. Preserve the original asset and its aspect ratio.
