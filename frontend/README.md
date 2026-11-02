# Sync In It Frontend

A responsive project information and downloads frontend for Sync In It. Built with React, TypeScript and Vite, using the visual language of the Dex project with Sync In It's cyan/blue/purple identity.

## Local development

Requirements: Node.js 20+ and npm.

```bash
cd frontend
npm install
npm run dev
```

Open the local URL printed by Vite.

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

The Vercel configuration at `frontend/vercel.json` serves the app entry point for `/downloads` while preserving the requested browser path, allowing the client-side route to render.

## Pages

- `/` — project overview, problem, system flow, architecture, synchronization targets and roadmap.
- `/downloads` — Android/iOS pre-release status, source repository and GitHub Releases link.

## Release policy

The project is currently in the design/research phase. The download page deliberately does not expose fictitious APK/IPA links. Publish actual binaries as GitHub Releases before linking them from this page.

## Brand asset

The frontend references the repository logo at `/images/sync_in_it.png`, copied into the frontend public assets during deployment setup. Keep the image transparent and preserve the original asset.
