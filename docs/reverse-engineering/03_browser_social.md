# Browser-Based Synchronized Rooms — Product Briefs

**Catalogue IDs:** 21–24 · **Research date:** 7 November 2026

## 21 — listenw.us

- **Workflow:** Browser-based social listening rooms, with installable mobile-browser/PWA and Windows desktop app experiences described on the official site.
- **Synchronization boundary:** Website claims server-side synchronized playback; details and accuracy have not been independently verified.
- **Strengths:** Browser-first room entry and broad endpoint reach.
- **Unknowns:** Source/player embedding model, server clock, latency compensation, browser background limits and event ordering.
- **REA focus:** Compare desktop/mobile browser, autoplay restrictions, tab suspension, late join and seek conflict.
- **Sources:** [Site](https://listenw.us/), [how it works](https://listenw.us/how-it-works), [about](https://listenw.us/about)

## 22 — ListenTogether for YouTube Music

- **Workflow:** Browser extension and room synchronization for Chrome, Firefox and Edge; mobile app support should be independently verified.
- **Synchronization boundary:** Public FAQ says WebSockets broadcast playback actions and compensate for network latency.
- **Strengths:** Explicit realtime control plane and browser-extension integration.
- **Unknowns:** Exact drift math, authoritative room state, conflict resolution, snapshots and browser lifecycle recovery.
- **REA focus:** Simultaneous play/pause/seek, late join, reconnect, suspended tabs and different YouTube player states.
- **Source:** [Official site/FAQ](https://listentogether.now/)

## 23 — Together (chtnnh/together)

- **Workflow:** Open-source mobile-first room app for synchronized YouTube playback, shared requests/DJ queue, chat, vote-to-skip, moderation, audio-only listening and playlist import.
- **Documented architecture:** Next.js web app; Cloudflare Worker + Durable Objects for realtime WebSocket rooms; Supabase Postgres for rooms/settings/playlists; shared event protocol and Zod schemas; Drizzle DB layer and track resolver. Drift correction and late-join sync are described.
- **Strengths:** Public code; explicit realtime boundary; typed event protocol; roles/queue semantics; unit, Playwright E2E and visual tests.
- **Unknowns:** Production deployment configuration, exact synchronization math, worker-memory vs database snapshot authority, hibernation/reconnect semantics and event ordering guarantees require source-level trace.
- **REA focus:** Inspect services/realtime and shared packages; run unit/E2E tests; inject jitter; restart Durable Objects; test concurrent seek, late join, background tab and queue transitions.
- **Sources:** [GitHub](https://github.com/chtnnh/together), [site](https://together.chtnnhfoundation.org/)

## 24 — Popmelon

- **Workflow:** Browser extension watch/listen party for supported desktop Chromium browsers.
- **Synchronization boundary:** Shared party controls and synchronized playback are described; protocol is private.
- **Strengths:** Browser-level integration across supported sites.
- **Unknowns:** Exact state model, source compatibility, drift correction, media restrictions and reconnect behaviour.
- **REA focus:** Test supported browsers, control propagation, late join, tab sleep and content-specific limitations.
- **Source:** [Official site](https://popmelon.app/)

## Cross-entry takeaway

The strongest lessons are about room actors, sequenced events, snapshots, queue ownership, late-join state and browser lifecycle. Together is the best code-inspectable reference here for a web control plane, but it is not a raw PCM speaker-distribution architecture.
