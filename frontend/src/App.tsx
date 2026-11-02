import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';

const REPO = 'https://github.com/Shreyas-patil07/Sync-in-it';
const DOCS = REPO + '/tree/main/docs';
const RELEASES = REPO + '/releases';
const LATEST_RELEASE = REPO + '/releases/tag/v0.4.0-preview.1';
const ANDROID_APK = REPO + '/releases/download/v0.4.0-preview.1/SyncInIt-Android-preview-v0.4.0.apk';
const ANDROID_CHECKSUMS = REPO + '/releases/download/v0.4.0-preview.1/SHA256SUMS.txt';

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
  const go = (next: NavigablePage) => navigate(next);

  return <>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <header className="site-header">
      <div className="brand-island">
        <Brand compact />
      </div>
    </header>
    <nav id="primary-navigation" className="nav-links action-island" aria-label="Primary navigation">
      <a href="/downloads" onClick={(event) => { event.preventDefault(); go('downloads'); }} aria-current={page === 'downloads' ? 'page' : undefined}>Downloads</a>
      <a className="nav-source" href={REPO} target="_blank" rel="noreferrer">GitHub <span aria-hidden="true">↗</span></a>
    </nav>
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
    <div className="hero-layout shell">
      <div className="hero-brand-lockup">
        <img src="/images/sync_in_it.png" alt="Sync In It logo" />
        <span>sync<span>in</span>it</span>
        <small>ONE TIMELINE · MANY RECEIVERS</small>
      </div>
      <div className="hero-copy">
        <Eyebrow>OFFLINE AUDIO · BUILT AROUND ONE SHARED CLOCK</Eyebrow>
        <h1 id="hero-title">Many phones.<br /><span className="hero-gradient">One shared moment.</span></h1>
        <p className="hero-lede">The Android preview can now discover nearby hosts, join a session over local Wi-Fi and acquire a shared clock. Synchronized audio playback is the next stage.</p>
        <div className="hero-actions">
          <a className="button button-primary" href="#how-it-works">Discover how it works <Arrow /></a>
          <a className="button button-text" href={REPO} target="_blank" rel="noreferrer">Explore the project <Arrow diagonal /></a>
        </div>
        <div className="hero-status"><span className="status-point" /><span>Android preview v0.4.0 available</span><span className="status-separator">·</span><span>Audio playback in development</span></div>
      </div>
    </div>
  </section>;
}

const highlights = [
  { icon: '♫', tone: 'cyan', title: 'Find a nearby Host.', text: 'Android hosts advertise over local Wi-Fi. Nearby Android receivers can discover a session and tap to join.' },
  { icon: '◷', tone: 'violet', title: 'Join and align clocks.', text: 'A two-channel TCP handshake admits the receiver. UDP four-timestamp exchanges feed the shared clock estimator.' },
  { icon: '⌁', tone: 'magenta', title: 'Designed to work offline.', text: 'Discovery and session transport stay on a private local Wi-Fi network. No internet or cloud service is required.' },
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
  { number: '01', title: 'Start hosting', text: 'An Android Host opens its control and audio TCP listeners, then advertises the session on the local network.' },
  { number: '02', title: 'Find and join', text: 'Nearby Android receivers discover the Host with mDNS and join through the two-channel TCP handshake.' },
  { number: '03', title: 'Acquire the clock', text: 'The receiver runs UDP four-timestamp sync and waits for a stable estimate. Playback remains blocked until audio buffering is implemented.' },
];

function HowItWorks() {
  return <section className="section process-section" id="how-it-works">
    <div className="shell">
      <div className="section-heading" data-reveal>
        <Eyebrow>MADE TO FEEL SIMPLE</Eyebrow>
        <h2>Three steps.<br /><span className="heading-gradient">One shared clock.</span></h2>
        <p>The v0.4.0 Android preview covers nearby discovery, session admission and clock acquisition. Audio playback remains in development.</p>
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
        <h2>The Android preview is here.<br />Playback is next.</h2>
        <p>M0 simulator and timing work continue, while M1 now has an Android v0.4.0 engineering preview for nearby discovery, TCP admission and UDP clock acquisition. Audio chunks, PCM rendering and scheduled playback are not implemented yet.</p>
        <a className="button button-light" href={LATEST_RELEASE} target="_blank" rel="noreferrer">Get Android preview v0.4.0 <Arrow diagonal /></a>
      </div>
      <div className="status-card">
        <div className="status-card-top"><span>PROJECT STATUS</span><span className="status-live-dot" /> ACTIVE DEVELOPMENT</div>
        <div className="status-current">
          <span className="status-milestone">M1</span>
          <div><b>Android LAN + clock acquisition</b><small>PREVIEW · M1 IN PROGRESS</small></div>
        </div>
        <div className="status-next">
          <span className="status-milestone">M2a</span>
          <div><b>PCM codec + jitter buffer</b><small>OPEN PR #28 · NOT MERGED</small></div>
        </div>
        <div className="status-card-foot">No audio streaming or synchronized playback yet.</div>
      </div>
    </div>
  </section>;
}

const questions = [
  { question: 'Does Sync In It need an internet connection?', answer: 'No. The preview uses a private local Wi-Fi network. The access point must allow mDNS/multicast discovery and device-to-device traffic; guest networks with client isolation may block it.' },
  { question: 'Which phones work with the current preview?', answer: 'The v0.4.0 preview is Android-only: one Android Host and an Android receiver. An iOS receiver is planned, but iOS networking and playback are not implemented.' },
  { question: 'Can I download and use the app now?', answer: 'An Android engineering preview is available as v0.4.0-preview.1. It demonstrates nearby host discovery, TCP session admission and UDP clock acquisition. It does not stream or play audio and is not a synchronized-speaker release.' },
  { question: 'What does the current Android preview actually do?', answer: 'It advertises and discovers nearby Host sessions, completes a two-channel TCP admission handshake and runs four-timestamp UDP clock acquisition. The receiver remains in Synchronizing because the audio buffer is still empty.' },
  { question: 'How many phones will it support?', answer: 'The product target remains 6–10 phones in total. The current Android Host UI admits one receiver; group admission and multi-receiver playback have not been implemented or validated.' },
  { question: 'Is the preview safe on any Wi-Fi network?', answer: 'Use only a trusted private network. The prototype includes its 16-byte session token in the mDNS service record, where other devices on the same LAN can read it. This is not transport encryption or cryptographic peer authentication.' },
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
      <p>Get the Android engineering preview, review what it includes and follow the work toward audio playback.</p>
      <div className="closing-actions">
        <a className="button button-light" href={LATEST_RELEASE} target="_blank" rel="noreferrer">Get Android preview v0.4.0 <Arrow diagonal /></a>
        <a className="button button-outline-light" href={DOCS} target="_blank" rel="noreferrer">Read the docs</a>
      </div>
    </div>
    <img className="closing-logo" src="/images/sync_in_it.png" alt="" aria-hidden="true" />
  </section>;
}

function Footer({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <footer className="site-footer">
    <div className="shell footer-main">
      <div className="footer-brand"><Brand compact /><p>Local audio. Shared time.<br />Android preview v0.4.0 · playback in development.</p></div>
      <div className="footer-columns">
        <div><span>RESOURCES</span><a href={DOCS} target="_blank" rel="noreferrer">Documentation</a><a href={REPO + '/blob/main/LICENSE'} target="_blank" rel="noreferrer">Code license</a><a href={REPO + '/blob/main/LICENSE-DOCS'} target="_blank" rel="noreferrer">Documentation license</a></div>
        <div><span>PROJECT</span><a href="/downloads" onClick={(event) => { event.preventDefault(); navigate('downloads'); }}>Downloads</a><a href={RELEASES} target="_blank" rel="noreferrer">Releases</a><a href={REPO} target="_blank" rel="noreferrer">GitHub ↗</a></div>
      </div>
    </div>
    <div className="shell footer-bottom"><span>© 2026 Sync In It</span><span>M0 / M1 in progress. No synchronized audio playback yet.</span></div>
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
      <Eyebrow>DOWNLOADS / ANDROID PREVIEW</Eyebrow>
      <h1>Android preview is here.<br /><span className="hero-gradient">Playback comes next.</span></h1>
      <p>Version 0.4.0-preview.1 is available for Android. It demonstrates nearby Host discovery, TCP session admission and UDP clock acquisition. Audio streaming and synchronized playback are not implemented yet.</p>
      <div className="release-status"><span className="status-point" /><div><b>v0.4.0-preview.1</b><small>Android engineering preview · arm64-v8a and x86_64</small></div></div>
    </section>
    <section className="downloads-content shell">
      <div className="downloads-primary">
        <span className="downloads-label">ANDROID PREVIEW / v0.4.0</span>
        <h2>Download the Android engineering preview.</h2>
        <p>Install on Android devices connected to a trusted private Wi-Fi network. The session token is visible to other devices browsing that LAN; this preview does not include cryptographic transport protection.</p>
        <a className="button button-primary" href={ANDROID_APK} target="_blank" rel="noreferrer">Download Android APK <Arrow diagonal /></a>
        <a className="inline-link" href={ANDROID_CHECKSUMS} target="_blank" rel="noreferrer">Verify SHA-256 checksums <Arrow diagonal /></a>
      </div>
      <div className="downloads-secondary">
        <article><span>01 / SOURCE</span><h3>See the implementation.</h3><p>Explore the Python simulator, C++ protocol and session core, Android Host/receiver shell, TCP/UDP transport and JNI clock acquisition.</p><a className="inline-link" href={REPO} target="_blank" rel="noreferrer">Browse the source <Arrow diagonal /></a></article>
        <article><span>02 / RELEASE NOTES</span><h3>Review preview v0.4.0.</h3><p>See the LAN reliability fixes, supported ABIs and validation status. The release includes the APK and SHA-256 checksum file; audio playback is not included.</p><a className="inline-link" href={LATEST_RELEASE} target="_blank" rel="noreferrer">View v0.4.0 release notes <Arrow diagonal /></a></article>
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
