import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';

const REPO = 'https://github.com/Shreyas-patil07/Sync-in-it';
const DOCS = REPO + '/tree/main/docs';
const RELEASES = REPO + '/releases';

type Page = 'home' | 'downloads' | 'not-found';
type NavigablePage = Exclude<Page, 'not-found'>;

function resolvePage(): Page {
  if (window.location.pathname === '/') return 'home';
  if (window.location.pathname.replace(/\/$/, '') === '/downloads') return 'downloads';
  return 'not-found';
}

function usePage(): [Page, (page: NavigablePage) => void] {
  const [page, setPage] = useState<Page>(resolvePage);
  useEffect(() => {
    const onPopState = () => setPage(resolvePage());
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);
  const navigate = (next: NavigablePage) => {
    const path = next === 'downloads' ? '/downloads' : '/';
    if (window.location.pathname !== path) window.history.pushState({}, '', path);
    setPage(next);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };
  return [page, navigate];
}

function Brand({ compact = false }: { compact?: boolean }) {
  return <a className={compact ? 'brand brand-compact' : 'brand'} href="/" aria-label="Sync In It home">
    <img src="/images/sync_in_it.png" alt="" />
    <span>sync<span className="brand-accent">in</span>it</span>
  </a>;
}

function Header({ page, navigate }: { page: Page; navigate: (page: NavigablePage) => void }) {
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => {
    document.body.classList.toggle('menu-open', menuOpen);
    return () => document.body.classList.remove('menu-open');
  }, [menuOpen]);
  const go = (next: NavigablePage) => { navigate(next); setMenuOpen(false); };

  return <>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <header className="site-header">
      <div className="nav-island">
        <Brand compact />
        <nav id="primary-navigation" className={menuOpen ? 'nav-links nav-open' : 'nav-links'} aria-label="Primary navigation">
          <a href="/#system" onClick={() => setMenuOpen(false)}>The system</a>
          <a href="/#architecture" onClick={() => setMenuOpen(false)}>Architecture</a>
          <a href="/#roadmap" onClick={() => setMenuOpen(false)}>Roadmap</a>
          <a href="/downloads" onClick={(event) => { event.preventDefault(); go('downloads'); }} aria-current={page === 'downloads' ? 'page' : undefined}>Downloads</a>
          <a className="nav-source" href={REPO} target="_blank" rel="noreferrer">GitHub <span aria-hidden="true">↗</span></a>
        </nav>
        <button className={menuOpen ? 'menu-toggle is-open' : 'menu-toggle'} type="button"
          aria-label={menuOpen ? 'Close navigation' : 'Open navigation'}
          aria-controls="primary-navigation" aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}>
          <span /><span />
        </button>
      </div>
    </header>
  </>;
}

function Eyebrow({ children }: { children: ReactNode }) {
  return <p className="eyebrow"><span className="eyebrow-mark" />{children}</p>;
}
function Arrow({ diagonal = false }: { diagonal?: boolean }) {
  return <span className="arrow-symbol" aria-hidden="true">{diagonal ? '↗' : '→'}</span>;
}

function HeroDiagram() {
  return <div className="hero-visual" role="img" aria-label="Conceptual diagram of an Android Host coordinating multiple Speaker phones against one shared playback timeline">
    <div className="visual-topline"><span>SYNC MODEL / 01</span><span className="model-state"><i /> SYSTEM DESIGN</span></div>
    <div className="visual-heading"><span>ONE SHARED PLAYBACK TIMELINE</span><b>Every receiver aims for T₀.</b></div>
    <div className="device-network">
      <div className="device-host">
        <div className="device-screen"><span className="device-speaker" /><span className="device-wave">
          <i /><i /><i /><i /><i /><i /><i /><i /><i />
        </span></div>
        <span className="device-caption">ANDROID HOST</span><small>Source + clock</small>
      </div>
      <div className="network-lines" aria-hidden="true">
        <span className="line line-one" /><span className="line line-two" />
        <span className="line line-three" /><span className="line line-four" />
        <span className="network-core"><i /><i /><i /></span>
      </div>
      <div className="device-receivers">
        <div className="receiver-device"><div className="receiver-screen"><span /></div><div><b>01</b><small>ANDROID</small></div></div>
        <div className="receiver-device"><div className="receiver-screen"><span /></div><div><b>02</b><small>ANDROID</small></div></div>
        <div className="receiver-device receiver-planned"><div className="receiver-screen"><span /></div><div><b>03</b><small>iOS · PLANNED</small></div></div>
      </div>
    </div>
    <div className="timeline-visual">
      <div className="timeline-labels"><span>BUFFER FIRST</span><span>PLAYBACK DEADLINE</span></div>
      <div className="timeline-track"><div className="timeline-buffer" /><div className="timeline-deadline"><i /><b>T₀</b></div><div className="timeline-pulse" /></div>
      <div className="timeline-times"><span>Audio arrives ahead of time</span><b>Shared clock</b></div>
    </div>
    <div className="visual-foot">
      <span><i className="legend-dot legend-host" /> Host</span>
      <span><i className="legend-dot legend-receiver" /> Receiver</span>
      <span className="visual-foot-note">Conceptual system view, not a live session</span>
    </div>
  </div>;
}

function Hero() {
  return <section className="hero shell">
    <div className="hero-copy">
      <Eyebrow>OFFLINE AUDIO SYNCHRONIZATION <span className="eyebrow-divider">/</span> OPEN RESEARCH</Eyebrow>
      <h1>Many phones.<br /><span className="hero-gradient">One shared moment.</span></h1>
      <p className="hero-lede">Sync In It is building a way for ordinary phones to play the same audio together. One Android Host sends it ahead. Every Speaker follows one shared playback timeline.</p>
      <div className="hero-actions">
        <a className="button button-primary" href="#system">Explore the system <Arrow /></a>
        <a className="button button-text" href={REPO} target="_blank" rel="noreferrer">View the source <Arrow diagonal /></a>
      </div>
      <div className="hero-status"><span className="status-point" /><span>M0 simulator in progress</span><span className="status-separator">/</span><span>No public app build yet</span></div>
    </div>
    <HeroDiagram />
    <div className="hero-index" aria-hidden="true"><span>01</span><span>10</span></div>
  </section>;
}

function SpecRail() {
  return <section className="spec-rail" aria-label="Current system design baseline">
    <div className="shell spec-grid">
      <div className="spec-intro"><span>THE DESIGN BASELINE</span><b>Built around time, not arrival.</b></div>
      <div className="spec-item"><strong>48 <small>kHz</small></strong><span>PCM audio baseline</span></div>
      <div className="spec-item"><strong>960</strong><span>Frames per chunk</span></div>
      <div className="spec-item"><strong>20 <small>ms</small></strong><span>Chunk duration</span></div>
      <div className="spec-item spec-offline"><strong>Offline</strong><span>Private local Wi Fi</span></div>
    </div>
  </section>;
}

function TaglineReveal() {
  const words = ['Packets', 'arrive', 'at', 'different', 'times.', 'Playback', 'should', 'not.'];
  return <section className="tagline-section shell" aria-label="The central synchronization principle">
    <Eyebrow>THE PROBLEM IN ONE LINE</Eyebrow>
    <h2 className="tagline-copy">{words.map((word, index) =>
      <span className="tagline-word" data-word-reveal key={word + index}>{word}{index < words.length - 1 ? ' ' : ''}</span>)}
    </h2>
    <p className="tagline-note">Arrival is a network event. Playback is a clock decision.</p>
  </section>;
}

function TimingComparison() {
  return <section className="section timing-section" id="system">
    <div className="shell">
      <div className="section-intro section-intro-split" data-reveal>
        <div><Eyebrow>01 / WHY SYNCHRONIZATION IS HARD</Eyebrow><h2>Receiving together<br />is not playing together.</h2></div>
        <p>Every phone has its own operating system, audio pipeline and hardware clock. Starting sound as soon as data arrives creates timing differences that can become audible echoes.</p>
      </div>
      <div className="timing-comparison" data-reveal>
        <article className="timing-case timing-case-naive">
          <div className="case-heading"><span>WITHOUT A SHARED TIMELINE</span><b>Arrival decides</b></div>
          <p>Each phone starts when its audio reaches the device.</p>
          <div className="case-axis"><span>PACKET ARRIVAL</span><span>PLAYBACK</span></div>
          <div className="arrival-row"><b>Phone 01</b><div className="arrival-track"><i className="arrival-a" /><span /></div><small>Starts first</small></div>
          <div className="arrival-row"><b>Phone 02</b><div className="arrival-track"><i className="arrival-b" /><span /></div><small>Starts later</small></div>
          <div className="arrival-row"><b>Phone 03</b><div className="arrival-track"><i className="arrival-c" /><span /></div><small>Starts later</small></div>
          <div className="case-result"><span className="result-mark result-cross">×</span><span>Different start times can create echoes.</span></div>
        </article>
        <article className="timing-case timing-case-sync">
          <div className="case-heading"><span>WITH A SHARED TIMELINE</span><b>One deadline</b></div>
          <p>Audio is buffered early. Each phone waits for the same future time.</p>
          <div className="case-axis"><span>BUFFERED</span><span>PLAY AT T₀</span></div>
          <div className="sync-row"><b>Phone 01</b><div className="sync-track"><i /></div><small>T₀</small></div>
          <div className="sync-row"><b>Phone 02</b><div className="sync-track"><i /></div><small>T₀</small></div>
          <div className="sync-row"><b>Phone 03</b><div className="sync-track"><i /></div><small>T₀</small></div>
          <div className="case-result"><span className="result-mark result-check">✓</span><span>Every ready receiver targets one shared start.</span></div>
        </article>
      </div>
      <p className="section-footnote">Conceptual illustration of the scheduling model. It is not a measured timing result.</p>
    </div>
  </section>;
}

const steps = [
  { number: '01', title: 'Create a session', text: 'An Android phone becomes the Host, owns the audio source and establishes the session clock. Other phones join over the same private network.', detail: 'ANDROID HOST', code: 'SESSION + QR' },
  { number: '02', title: 'Align the clocks', text: 'Receivers exchange four timestamps with the Host. The estimator tracks clock offset and skew instead of assuming every phone runs at the same rate.', detail: 'TIME SYNCHRONIZATION', code: 'UDP · 4 TIMESTAMPS' },
  { number: '03', title: 'Prepare, then play', text: 'Audio is sent ahead and placed into sample indexed buffers. Once receivers are ready, the Host schedules a future playback deadline for the group.', detail: 'GROUP PLAYOUT', code: 'ONE FUTURE T₀' },
];

function HowItWorks() {
  return <section className="section workflow-section" id="how-it-works">
    <div className="shell">
      <div className="section-intro" data-reveal>
        <Eyebrow>02 / HOW IT WORKS</Eyebrow>
        <h2>Three steps between<br />a file and a shared moment.</h2>
        <p>The system separates audio delivery from time coordination. It does not depend on packet arrival to decide when a Speaker plays.</p>
      </div>
      <div className="workflow-list">
        {steps.map((step) => <article className="workflow-step" data-reveal key={step.number}>
          <span className="workflow-number">{step.number}</span>
          <div className="workflow-copy"><h3>{step.title}</h3><p>{step.text}</p></div>
          <div className="workflow-detail"><span>{step.detail}</span><b>{step.code}</b></div>
        </article>)}
      </div>
    </div>
  </section>;
}

const layers = [
  ['L1', 'Audio transport', 'Timestamped chunks over TCP'],
  ['L2', 'Clock distribution', 'UDP four timestamp exchange'],
  ['L3', 'Group playout', 'Shared timeline anchors'],
  ['L4', 'DAC clock mapping', 'Output frame timestamps'],
  ['L5', 'Drift correction', 'Bounded playout adjustment'],
  ['L6', 'Acoustic alignment', 'Chirp measurement and compensation'],
  ['L7', 'Resilience', 'Buffering, recovery and rejoin'],
];

function Architecture() {
  return <section className="section architecture-section" id="architecture">
    <div className="shell architecture-layout">
      <div className="architecture-copy" data-reveal>
        <Eyebrow>03 / UNDER THE HOOD</Eyebrow>
        <h2>A timing engine,<br />not a speaker clone.</h2>
        <p>The design separates network delivery, clock coordination and acoustic alignment. A portable C++17 core owns timing logic while native platform layers handle audio and lifecycle.</p>
        <div className="architecture-stack">
          <span><b>C++17</b> Shared timing core</span>
          <span><b>Kotlin</b> Android app layer</span>
          <span><b>Oboe / AAudio</b> Android audio</span>
          <span><b>Swift</b> Planned iOS receiver</span>
          <span><b>Python</b> M0 simulator</span>
        </div>
        <a className="inline-link" href={REPO + '/blob/main/docs/03_Architecture.md'} target="_blank" rel="noreferrer">Read the architecture document <Arrow diagonal /></a>
      </div>
      <div className="architecture-panel" data-reveal>
        <div className="architecture-panel-head"><span>THE SYSTEM STACK</span><span>DESIGN BASELINE</span></div>
        <div className="layer-list">
          {layers.map(([id, title, detail], index) => <div className={index === 2 ? 'layer-entry layer-emphasis' : 'layer-entry'} key={id}>
            <span>{id}</span><div><b>{title}</b><small>{detail}</small></div><i aria-hidden="true" />
          </div>)}
        </div>
        <div className="architecture-panel-foot"><span>01</span><div /><span>07 LAYERS</span></div>
      </div>
    </div>
  </section>;
}

const targets = [
  { tier: 'T1', value: '≤20', unit: 'ms', note: 'Initial practical target' },
  { tier: 'T2', value: '≤10', unit: 'ms', note: 'Strong mixed device target' },
  { tier: 'T3', value: '≤5', unit: 'ms', note: 'Stretch target at a chosen listening position' },
];

function Precision() {
  return <section className="section precision-section">
    <div className="shell precision-layout">
      <div className="precision-copy" data-reveal>
        <Eyebrow>04 / ENGINEERING TARGETS</Eyebrow>
        <h2>Precision is<br />a test result.</h2>
        <p>These are acoustic skew acceptance goals, not product guarantees. Every claim must be backed by a documented test on real devices at a defined listening position.</p>
        <div className="precision-note"><span>!</span><p>No physical device synchronization result is recorded yet. M0 results are simulated.</p></div>
      </div>
      <div className="target-list" data-reveal>
        {targets.map((target) => <div className="target-row" key={target.tier}>
          <span className="target-tier">{target.tier}</span>
          <strong>{target.value}<small>{target.unit}</small></strong>
          <span className="target-note">{target.note}</span>
        </div>)}
        <div className="target-method">REPORTING STANDARD <b>Mean · p95 · Maximum error</b></div>
      </div>
    </div>
  </section>;
}

const milestones = [
  { id: 'M0', title: 'Simulator and timing core', status: 'IN PROGRESS', description: 'Model clock offset, skew, network variation and playout drift. Python and C++ timing work is underway.' },
  { id: 'M1', title: 'Two physical phones', status: 'PLANNED', description: 'Validate clock synchronization between real Android devices.' },
  { id: 'M2', title: 'Scheduled playback', status: 'PLANNED', description: 'Start a shared click track against one future timeline deadline.' },
  { id: 'M3', title: 'Output timestamps', status: 'PLANNED', description: 'Map audio output time and add manual per Speaker offset.' },
  { id: 'M4', title: 'Drift control', status: 'PLANNED', description: 'Validate bounded drift through a 30 minute endurance run.' },
  { id: 'M5 to M7', title: 'Group and platform support', status: 'PLANNED', description: 'Add group reconnect, codec work and the iOS receiver.' },
  { id: 'M8 to M9', title: 'Acoustic calibration and field trial', status: 'PLANNED', description: 'Measure output arrival at a defined listening position and test the group in a train coach.' },
];

function Roadmap() {
  return <section className="section roadmap-section" id="roadmap">
    <div className="shell">
      <div className="section-intro section-intro-split" data-reveal>
        <div><Eyebrow>05 / DEVELOPMENT ROADMAP</Eyebrow><h2>Progress has to<br />be measurable.</h2></div>
        <p>A milestone is complete only when its simulated or physical acceptance gate passes. The current work is still at M0.</p>
      </div>
      <div className="roadmap-list" data-reveal>
        {milestones.map((milestone, index) => <article className={index === 0 ? 'roadmap-item roadmap-current' : 'roadmap-item'} key={milestone.id}>
          <span className="roadmap-id">{milestone.id}</span>
          <div className="roadmap-description"><h3>{milestone.title}</h3><p>{milestone.description}</p></div>
          <span className={index === 0 ? 'roadmap-state state-current' : 'roadmap-state'}><i />{milestone.status}</span>
        </article>)}
      </div>
      <div className="roadmap-source"><span>STATUS SOURCE</span><a href={REPO + '/blob/main/docs/10_Development_Log.md'} target="_blank" rel="noreferrer">Development log <Arrow diagonal /></a></div>
    </div>
  </section>;
}

const questions = [
  { question: 'Does Sync In It need an internet connection?', answer: 'No. The intended system runs over a private local Wi Fi network. The router provides the connection between phones; a public internet connection or cloud service is not required.' },
  { question: 'Which phone acts as the Host?', answer: 'An Android phone is the Host in the current architecture. It owns the session, audio source and master clock. iOS is planned as a Speaker only.' },
  { question: 'Can I download and use the app now?', answer: 'Not yet. The project is at M0, the simulator and timing core stage. The Android application and physical device playback prototype have not been implemented, and there are no public APK or iOS builds.' },
  { question: 'Why not start playback as soon as audio arrives?', answer: 'Network delivery time varies. The system sends audio ahead, buffers it by sample index and schedules playback against one future master timeline deadline.' },
  { question: 'Does it guarantee a specific synchronization error?', answer: 'No. T1, T2 and T3 are engineering acceptance goals. They require measurement on physical devices and at a defined listening position before any performance claim can be made.' },
  { question: 'How many phones will it support?', answer: 'The product target is 6 to 10 phones in total. The exact split between the Android Host and Speakers is still an open design decision.' },
  { question: 'Will Bluetooth speakers be supported?', answer: 'Bluetooth output is not part of the validated target. The system design treats Bluetooth as a source of additional delay that is difficult to measure reliably. Initial testing focuses on phone speakers.' },
  { question: 'Where can I follow the engineering work?', answer: 'The repository contains the product requirements, architecture, protocol proposal, decisions, simulator and development log. Start with the documentation index on GitHub.' },
];

function FAQ() {
  return <section className="section faq-section" id="faq">
    <div className="shell faq-layout">
      <div className="faq-copy" data-reveal>
        <Eyebrow>06 / QUESTIONS</Eyebrow>
        <h2>Clear answers.<br />No timing myths.</h2>
        <p>What is designed, what is being built and what still needs proof.</p>
        <a className="inline-link" href={DOCS} target="_blank" rel="noreferrer">Explore all project docs <Arrow diagonal /></a>
      </div>
      <div className="faq-list" data-reveal>
        {questions.map((item, index) => <details className="faq-item" key={item.question} open={index === 0}>
          <summary><span>{item.question}</span><i aria-hidden="true" /></summary>
          <p>{item.answer}</p>
        </details>)}
      </div>
    </div>
  </section>;
}

function ClosingCTA() {
  return <section className="closing-section shell" data-reveal>
    <div className="closing-content">
      <Eyebrow>OPEN DEVELOPMENT</Eyebrow>
      <h2>The timeline is the product.<br />The proof is the work.</h2>
      <p>Read the design, inspect the timing core and follow each milestone as it earns its way to completion.</p>
      <div className="closing-actions">
        <a className="button button-primary" href={DOCS} target="_blank" rel="noreferrer">Read the documentation <Arrow diagonal /></a>
        <a className="button button-text" href={REPO} target="_blank" rel="noreferrer">Open GitHub <Arrow diagonal /></a>
      </div>
    </div>
    <div className="closing-mark" aria-hidden="true"><img src="/images/sync_in_it.png" alt="" /><span>ONE TIMELINE<br />MANY RECEIVERS</span></div>
  </section>;
}

function Footer({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <footer className="site-footer">
    <div className="shell footer-main">
      <div className="footer-brand"><Brand compact /><p>Local audio. Shared time.<br />A research project in active development.</p></div>
      <div className="footer-columns">
        <div><span>EXPLORE</span><a href="/#system">The system</a><a href="/#architecture">Architecture</a><a href="/#roadmap">Roadmap</a></div>
        <div><span>RESOURCES</span><a href={DOCS} target="_blank" rel="noreferrer">Documentation</a><a href={REPO + '/blob/main/LICENSE'} target="_blank" rel="noreferrer">Code license</a><a href={REPO + '/blob/main/LICENSE-DOCS'} target="_blank" rel="noreferrer">Documentation license</a></div>
        <div><span>PROJECT</span><a href="/downloads" onClick={(event) => { event.preventDefault(); navigate('downloads'); }}>Downloads</a><a href={RELEASES} target="_blank" rel="noreferrer">Releases</a><a href={REPO} target="_blank" rel="noreferrer">GitHub ↗</a></div>
      </div>
    </div>
    <div className="shell footer-bottom"><span>© 2026 Sync In It</span><span>Research and development. No public app build yet.</span></div>
  </footer>;
}

function Home() {
  return <main id="main-content">
    <Hero /><SpecRail /><TaglineReveal /><TimingComparison /><HowItWorks />
    <Architecture /><Precision /><Roadmap /><FAQ /><ClosingCTA />
  </main>;
}

function Downloads({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <main id="main-content" className="downloads-page">
    <section className="downloads-hero shell">
      <Eyebrow>DOWNLOADS / PROJECT STATUS</Eyebrow>
      <h1>Built in the open.<br /><span className="hero-gradient">Not released yet.</span></h1>
      <p>The engineering work is underway, but there is no installable app today. The current milestone is the simulator and timing core.</p>
      <div className="release-status"><span className="status-point" /><div><b>M0 in progress</b><small>Android app and physical playback prototype not implemented</small></div></div>
    </section>
    <section className="downloads-content shell">
      <div className="downloads-primary">
        <span className="downloads-label">ANDROID / iOS BUILDS</span>
        <h2>No public binaries are available.</h2>
        <p>When a build is ready, it will appear here with its version, supported devices, release notes and checksum.</p>
        <span className="button button-disabled" aria-disabled="true">No app build to download</span>
      </div>
      <div className="downloads-secondary">
        <article><span>01 / SOURCE</span><h3>Inspect what exists today.</h3><p>The repository contains the simulator, timing core, protocol vectors and technical documentation.</p><a className="inline-link" href={REPO} target="_blank" rel="noreferrer">Browse the source <Arrow diagonal /></a></article>
        <article><span>02 / RELEASES</span><h3>Watch for a real build.</h3><p>Published Android or iOS packages will be listed with release notes and verification details.</p><a className="inline-link" href={RELEASES} target="_blank" rel="noreferrer">Open GitHub Releases <Arrow diagonal /></a></article>
      </div>
    </section>
    <div className="shell downloads-back"><a href="/" onClick={(event) => { event.preventDefault(); navigate('home'); }} className="inline-link"><span aria-hidden="true">←</span> Back to the project</a></div>
  </main>;
}

function NotFound({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <main id="main-content" className="not-found shell">
    <span className="not-found-code">404 / NOT FOUND</span>
    <h1>This page is<br /><span className="hero-gradient">out of sync.</span></h1>
    <p>The address does not match a page in the Sync In It project site.</p>
    <button className="button button-primary" onClick={() => navigate('home')}>Return to the overview <Arrow /></button>
  </main>;
}

export default function App() {
  const [page, navigate] = usePage();
  useEffect(() => {
    const titles: Record<Page, string> = {
      home: 'Sync In It — Many phones. One shared moment.',
      downloads: 'Downloads — Sync In It',
      'not-found': 'Page not found — Sync In It',
    };
    document.title = titles[page];
  }, [page]);

  useEffect(() => {
    const revealElements = document.querySelectorAll<HTMLElement>('[data-reveal], [data-word-reveal]');
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      revealElements.forEach((element) => element.classList.add('is-revealed'));
      return;
    }
    const observer = new IntersectionObserver((entries, activeObserver) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-revealed');
          activeObserver.unobserve(entry.target);
        }
      });
    }, { threshold: 0.16, rootMargin: '0px 0px -48px 0px' });
    revealElements.forEach((element) => observer.observe(element));
    return () => observer.disconnect();
  }, [page]);

  return <div className="app-shell">
    <Header page={page} navigate={navigate} />
    {page === 'home' && <Home />}
    {page === 'downloads' && <Downloads navigate={navigate} />}
    {page === 'not-found' && <NotFound navigate={navigate} />}
    <Footer navigate={navigate} />
  </div>;
}
