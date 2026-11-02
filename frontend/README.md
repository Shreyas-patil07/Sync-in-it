# Sync In It Frontend

The public website for Sync In It. The homepage introduces the product through a logo-led hero, benefit-focused sections, a simple explanation of the intended experience, current development status and FAQs. The downloads page clearly states that no public app builds are available yet.

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

M0, the simulator and timing core, is in progress. The Android application and physical device playback prototype have not been implemented. There are no public APK or iOS builds.

The site does not claim measured acoustic synchronization results. Engineering targets still require physical device testing.

## Brand assets

The website uses the original project logo at `/images/sync_in_it.png`. Preserve the original asset and its aspect ratio.
