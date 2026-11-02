import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';

const REPO = 'https://github.com/Shreyas-patil07/Sync-in-it';
const DOCS = REPO + '/tree/main/docs';
const RELEASES = REPO + '/releases';

type Page = 'home' | 'downloads' | 'not-found';
type NavigablePage = Exclude<Page, 'not-found'>;

function resolvePage(): Page {
  if (window.location.pathname === '/') return 'home';
  if (window.location.pathname.replace(/\\/$/, '') === '/downloads') return 'downloads';
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
          <a href="/#experience" onClick={() => setMenuOpen(false)}>Experience</a>
          <a href="/#how-it-works" onClick={() => setMenuOpen(false)}>How it works</a>
          <a href="/#project-status" onClick={() => setMenuOpen(false)}>Development</a>
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

function Hero() {
  return <section className="hero" aria-labelledby="hero-title">
    <div className="hero-art-stage">
      <div className="hero-orb hero-orb-cyan" aria-hidden="true" />
      <div className="hero-orb hero-orb-violet" aria-hidden="true" />
      <div className="hero-orb hero-orb-magenta" aria-hidden="true" />
      <div className="hero-brand-lockup">
        <img src="/images/sync_in_it.png" alt="Sync In It logo" />
        <span>sync<span>in</span>it</span>
      </div>
      <span className="hero-art-caption">ONE TIMELINE · MANY RECEIVERS</span>
    </div>
    <div className="hero-copy shell">
      <Eyebrow>OFFLINE AUDIO · BUILT AROUND ONE SHARED CLOCK</Eyebrow>
      <h1 id="hero-title">Many phones.<br /><span className="hero-gradient">One shared moment.</span></h1>
      <p className="hero-lede">Bring the phones around you into one listening experience. Sync In It is building a way for ordinary phones to play the same audio together, guided by one shared playback timeline.</p>
      <div className="hero-actions">
        <a className="button button-primary" href="#how-it-works">Discover how it works <Arrow /></a>
        <a className="button button-text" href={REPO} target="_blank" rel="noreferrer">Explore the project <Arrow diagonal /></a>
      </div>
      <div className="hero-status"><span className="status-point" /><span>M0 development in progress</span><span className="status-separator">·</span><span>No public app build yet</span></div>
    </div>
  </section>;
}

const highlights = [
  { icon: '♫', tone: 'cyan', title: 'Your phones become the speakers.', text: 'One Android Host sends audio to nearby phones that join the session as Speakers.' },
  { icon: '◷', tone: 'violet', title: 'Everyone follows one moment.', text: 'Each receiver aims for the same playback time instead of starting whenever its audio arrives.' },
  { icon: '⌁', tone: 'magenta', title: 'Designed to work offline.', text: 'The system is designed for a private local Wi-Fi network, without cloud playback or an internet connection.' },
];

function Highlights() {
  return <section className="section highlights-section" id="experience">
    <div className="shell">
      <div className="section-heading" data-reveal>
        <Eyebrow>THE IDEA</Eyebrow>
        <h2>More phones.<br /><span className="heading-gradient">One listening experience.</span></h2>
        <p>Use the devices you already have to create a shared audio moment, wherever your group is together.</p>
      </div>
      <div className="highlight-grid">
        {highlights.map((item, index) => <article className="highlight-card" data-reveal key={item.title}>
          <span className={`highlight-icon highlight-${item.tone}`} aria-hidden="true">{item.icon}</span>
          <span className="highlight-number">0{index + 1}</span>
          <h3>{item.title}</h3>
          <p>{item.text}</p>
        </article>)}
      </div>
    </div>
  </section>;
}

const steps = [
  { number: '01', title: 'Start with one Host', text: 'An Android phone creates the session and becomes the source for the group.' },
  { number: '02', title: 'Bring phones together', text: 'Nearby phones join as Speakers and coordinate their clocks over the local network.' },
  { number: '03', title: 'Play at the same time', text: 'Audio is prepared ahead of time so every ready Speaker can target one shared playback moment.' },
];

function HowItWorks() {
  return <section className="section process-section" id="how-it-works">
    <div className="shell">
      <div className="section-heading" data-reveal>
        <Eyebrow>MADE TO FEEL SIMPLE</Eyebrow>
        <h2>Three steps.<br /><span className="heading-gradient">One shared rhythm.</span></h2>
        <p>The experience is designed to stay simple on the surface while the timing work happens underneath.</p>
      </div>
      <div className="process-grid">
        {steps.map((step) => <article className="process-card" data-reveal key={step.number}>
          <span className="process-number">{step.number}</span>
          <div className="process-symbol" aria-hidden="true">{step.number === '01' ? '◉' : step.number === '02' ? '⌘' : '♫'}</div>
          <h3>{step.title}</h3>
          <p>{step.text}</p>
        </article>)}
      </div>
    </div>
  </section>;
}

function ProjectStatus() {
  return <section className="section status-section" id="project-status">
    <div className="shell status-panel" data-reveal>
      <div className="status-copy">
        <Eyebrow>BUILT IN THE OPEN</Eyebrow>
        <h2>A real idea.<br />A work in progress.</h2>
        <p>Sync In It is currently at M0: building and testing the simulator and timing core. The Android app and physical playback prototype are not available yet.</p>
        <a className="button button-light" href={REPO + '/blob/main/docs/10_Development_Log.md'} target="_blank" rel="noreferrer">Follow the development log <Arrow diagonal /></a>
      </div>
      <div className="status-card">
        <div className="status-card-top"><span>PROJECT STATUS</span><span className="status-live-dot" /> ACTIVE DEVELOPMENT</div>
        <div className="status-current">
          <span className="status-milestone">M0</span>
          <div><b>Simulator & timing core</b><small>IN PROGRESS</small></div>
        </div>
        <div className="status-next">
          <span className="status-milestone">NEXT</span>
          <div><b>First physical phone tests</b><small>PLANNED · NOT STARTED</small></div>
        </div>
        <div className="status-card-foot">No public APK or iOS build yet.</div>
      </div>
    </div>
  </section>;
}

const questions = [
  { question: 'Does Sync In It need an internet connection?', answer: 'No. The intended system runs over a private local Wi-Fi network. A public internet connection or cloud service is not required.' },
  { question: 'Which phone acts as the Host?', answer: 'An Android phone is the Host in the current architecture. It owns the session, audio source and master clock. iOS is planned as a Speaker only.' },
  { question: 'Can I download and use the app now?', answer: 'Not yet. The simulator and timing core are in progress. The Android app and physical playback prototype have not been implemented, and there are no public APK or iOS builds.' },
  { question: 'Why not start playback as soon as audio arrives?', answer: 'Network delivery time varies. The system is designed to send audio ahead, buffer it and schedule playback against one future shared timeline.' },
  { question: 'How many phones will it support?', answer: 'The current product target is 6 to 10 phones in total. The exact split between the Android Host and Speaker devices is still being evaluated.' },
  { question: 'Will Bluetooth speakers be supported?', answer: 'Bluetooth output is not part of the validated target. Initial testing focuses on phone speakers because Bluetooth can introduce additional, difficult-to-measure delay.' },
];

function FAQ() {
  return <section className="section faq-section" id="faq">
    <div className="shell faq-layout">
      <div className="faq-copy" data-reveal>
        <Eyebrow>GOOD TO KNOW</Eyebrow>
        <h2>Questions?<br /><span className="heading-gradient">We have answers.</span></h2>
        <p>A few things worth knowing while Sync In It is being built.</p>
        <a className="inline-link" href={DOCS} target="_blank" rel="noreferrer">Read the project documentation <Arrow diagonal /></a>
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
      <Eyebrow>FOLLOW THE JOURNEY</Eyebrow>
      <h2>The next great listening moment starts with a shared clock.</h2>
      <p>Explore the design, follow the engineering work and see how the idea takes shape.</p>
      <div className="closing-actions">
        <a className="button button-light" href={REPO} target="_blank" rel="noreferrer">Visit Sync In It on GitHub <Arrow diagonal /></a>
        <a className="button button-outline-light" href={DOCS} target="_blank" rel="noreferrer">Read the docs</a>
      </div>
    </div>
    <img className="closing-logo" src="/images/sync_in_it.png" alt="" aria-hidden="true" />
  </section>;
}

function Footer({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <footer className="site-footer">
    <div className="shell footer-main">
      <div className="footer-brand"><Brand compact /><p>Local audio. Shared time.<br />A research project in active development.</p></div>
      <div className="footer-columns">
        <div><span>EXPLORE</span><a href="/#experience">The experience</a><a href="/#how-it-works">How it works</a><a href="/#project-status">Development</a></div>
        <div><span>RESOURCES</span><a href={DOCS} target="_blank" rel="noreferrer">Documentation</a><a href={REPO + '/blob/main/LICENSE'} target="_blank" rel="noreferrer">Code license</a><a href={REPO + '/blob/main/LICENSE-DOCS'} target="_blank" rel="noreferrer">Documentation license</a></div>
        <div><span>PROJECT</span><a href="/downloads" onClick={(event) => { event.preventDefault(); navigate('downloads'); }}>Downloads</a><a href={RELEASES} target="_blank" rel="noreferrer">Releases</a><a href={REPO} target="_blank" rel="noreferrer">GitHub ↗</a></div>
      </div>
    </div>
    <div className="shell footer-bottom"><span>© 2026 Sync In It</span><span>Research and development. No public app build yet.</span></div>
  </footer>;
}

function Home() {
  return <main id="main-content">
    <Hero />
    <Highlights />
    <HowItWorks />
    <ProjectStatus />
    <FAQ />
    <ClosingCTA />
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
    const revealElements = document.querySelectorAll<HTMLElement>('[data-reveal]');
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
