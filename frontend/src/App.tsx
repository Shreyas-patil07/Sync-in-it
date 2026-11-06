import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';

const REPO = 'https://github.com/Shreyas-patil07/Sync-in-it';
const RELEASE = REPO + '/releases/tag/v0.5.7-preview.1';
const APK = REPO + '/releases/download/v0.5.7-preview.1/SyncInIt-Android-preview-v0.5.7.apk';
const CHECKSUMS = REPO + '/releases/download/v0.5.7-preview.1/SHA256SUMS.txt';
const DOCS = REPO + '/tree/main/docs';

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

function Brand() {
  return <a className="brand" href="/" aria-label="Sync In It home">
    <img src="/images/sync_in_it.png" alt="" />
    <span>sync<span>in</span>it</span>
  </a>;
}

function Header({ page, navigate }: { page: Page; navigate: (page: NavigablePage) => void }) {
  return <>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <header className="site-header shell">
      <Brand />
      <nav aria-label="Primary navigation">
        <a href="/#how-it-works">How it works</a>
        <a href="/#status">Status</a>
        <a href="/downloads" aria-current={page === 'downloads' ? 'page' : undefined}
          onClick={(event) => { event.preventDefault(); navigate('downloads'); }}>Downloads</a>
        <a className="nav-github" href={REPO} target="_blank" rel="noreferrer">GitHub <span aria-hidden="true">↗</span></a>
      </nav>
    </header>
  </>;
}

function Eyebrow({ children }: { children: ReactNode }) {
  return <p className="eyebrow">{children}</p>;
}

function Arrow() {
  return <span aria-hidden="true">↗</span>;
}

function Hero() {
  return <section className="hero shell">
    <div className="hero-copy">
      <Eyebrow><span className="live-dot" /> ANDROID PREVIEW · v0.5.7</Eyebrow>
      <h1>One host.<br /><span>Up to eight speakers.</span></h1>
      <p className="hero-lede">Sync In It sends the same generated audio stream to Android phones on a local Wi-Fi network. Each receiver uses a shared timeline to schedule playback.</p>
      <div className="hero-actions">
        <a className="button button-primary" href={APK}>Download Android preview <Arrow /></a>
        <a className="text-link" href={RELEASE} target="_blank" rel="noreferrer">Read release notes <Arrow /></a>
      </div>
      <p className="hero-note">No internet connection required. A trusted Wi-Fi network with device-to-device traffic is required.</p>
    </div>
    <div className="hero-visual" aria-label="One host connected to multiple speaker devices">
      <div className="visual-top"><span>SESSION TOPOLOGY</span><span>01 / 08</span></div>
      <div className="topology">
        <div className="device host-device"><span className="device-icon">H</span><b>Host</b><small>Audio source</small></div>
        <div className="connection-lines"><i /><i /><i /><i /><i /><i /></div>
        <div className="speaker-row">
          {['01','02','03','04','05','06','07','08'].map((n) => <div className="speaker" key={n}><span>▰</span><small>{n}</small></div>)}
        </div>
        <div className="visual-caption"><span className="live-dot" /> Local network · shared session</div>
      </div>
      <div className="visual-bottom"><span>48 kHz · STEREO · PCM16</span><span>ANDROID</span></div>
    </div>
  </section>;
}

const capabilities = [
  { number: '01', title: 'Local discovery', text: 'A Host advertises a session over mDNS. Nearby Android devices can find it on the same Wi-Fi network.' },
  { number: '02', title: 'Clock coordination', text: 'Receivers estimate clock offset with four-timestamp UDP exchanges before joining the playback cohort.' },
  { number: '03', title: 'Shared audio stream', text: 'The Host fans out sample-indexed 48 kHz stereo PCM16 test audio through separate receiver connections.' },
];

function HowItWorks() {
  return <section className="section shell" id="how-it-works">
    <div className="section-intro">
      <Eyebrow>THE SYSTEM</Eyebrow>
      <h2>What happens when<br />devices join a session.</h2>
      <p>The app coordinates discovery, connection, buffering and a common playback start. These are separate stages, each with its own state and diagnostics.</p>
    </div>
    <div className="capability-grid">
      {capabilities.map((item) => <article className="capability" key={item.number}>
        <span className="number">{item.number}</span>
        <h3>{item.title}</h3>
        <p>{item.text}</p>
      </article>)}
    </div>
  </section>;
}

function Status() {
  return <section className="section status-section" id="status">
    <div className="shell status-layout">
      <div className="section-intro">
        <Eyebrow>BUILD STATUS · 06 NOV 2026</Eyebrow>
        <h2>The test path works.<br />Acoustic accuracy is still being measured.</h2>
        <p>Preview v0.5.7 includes coordinated multi-Speaker admission, pre-playback PCM buffering, a shared start anchor and DAC output-clock diagnostics.</p>
        <a className="text-link" href={RELEASE} target="_blank" rel="noreferrer">See everything in v0.5.7 <Arrow /></a>
      </div>
      <div className="status-details">
        <div className="status-row"><span className="status-index">01</span><div><b>Session discovery and admission</b><p>LAN discovery, TCP control/audio channels and up to eight Speaker devices.</p></div><span className="state state-done">Implemented</span></div>
        <div className="status-row"><span className="status-index">02</span><div><b>Coordinated test playback</b><p>PCM pre-roll, READY recovery and a shared scheduled playback anchor.</p></div><span className="state state-done">Implemented</span></div>
        <div className="status-row"><span className="status-index">03</span><div><b>DAC output-clock diagnostics</b><p>Measures Android output timestamps. The estimate is not yet used for drift correction.</p></div><span className="state state-progress">Diagnostics</span></div>
        <div className="status-row"><span className="status-index">04</span><div><b>Real-world synchronization</b><p>Physical multi-phone acoustic testing, Bluetooth latency and device calibration remain open.</p></div><span className="state state-pending">To validate</span></div>
      </div>
    </div>
  </section>;
}

const facts = [
  { q: 'Does it need internet?', a: 'No. Session discovery and audio transport run over a local Wi-Fi network. The access point must allow multicast discovery and traffic between connected devices.' },
  { q: 'How many phones can join?', a: 'The current Host cohort supports up to eight Speaker phones. The admission window is 15 seconds, followed by a 45-second readiness deadline.' },
  { q: 'Does it play my music?', a: 'Not yet. v0.5.7 uses a generated 1 kHz timing-click test signal. User-selected music and system audio are not supported.' },
  { q: 'Is synchronized playback verified?', a: 'The app schedules the test stream against a shared clock and collects Android output timestamps. This does not measure sound arrival at each speaker. Multi-phone acoustic accuracy has not been independently validated.' },
  { q: 'Can I install it over an earlier preview?', a: 'Yes. Install the APK as an update over a previous Sync In It preview signed with the same persistent preview key. Do not uninstall the previous build first.' },
  { q: 'Is it safe to use on public Wi-Fi?', a: 'Use a trusted private network. The preview session token is visible to devices browsing the same LAN; this build does not provide cryptographic transport protection.' },
];

function FAQ() {
  return <section className="section shell faq-section" id="faq">
    <div className="section-intro"><Eyebrow>FAQ</Eyebrow><h2>Before you install.</h2><p>Current behavior and limitations, without the guesswork.</p></div>
    <div className="faq-list">
      {facts.map((item, index) => <details key={item.q} open={index === 0}><summary>{item.q}<span>+</span></summary><p>{item.a}</p></details>)}
    </div>
  </section>;
}

function Downloads({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <main id="main-content" className="downloads-page shell">
    <Eyebrow>DOWNLOADS / ANDROID</Eyebrow>
    <h1>Sync In It <span>v0.5.7</span></h1>
    <p className="downloads-lede">Android engineering preview. One Host can send a generated test stream to up to eight Speaker phones on a local Wi-Fi network.</p>
    <div className="download-card">
      <div><span className="card-kicker">LATEST PREVIEW</span><h2>Android · v0.5.7-preview.1</h2><p>APK · arm64-v8a and x86_64</p></div>
      <a className="button button-primary" href={APK}>Download APK <Arrow /></a>
    </div>
    <div className="download-links"><a href={CHECKSUMS} target="_blank" rel="noreferrer">SHA-256 checksums <Arrow /></a><a href={RELEASE} target="_blank" rel="noreferrer">Release notes <Arrow /></a><a href={REPO} target="_blank" rel="noreferrer">Source code <Arrow /></a></div>
    <p className="install-note">Install over an earlier preview signed with the same persistent key. The app currently plays a generated timing-click signal, not user-selected audio. Physical acoustic synchronization remains unverified.</p>
    <button className="back-link" onClick={() => navigate('home')}>← Back to overview</button>
  </main>;
}

function Footer({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <footer className="site-footer">
    <div className="shell footer-inner">
      <div><Brand /><p>Local audio. Shared playback timeline.</p></div>
      <div className="footer-links"><a href={DOCS} target="_blank" rel="noreferrer">Documentation</a><a href={REPO + '/releases'} target="_blank" rel="noreferrer">Releases</a><a href="/downloads" onClick={(e) => { e.preventDefault(); navigate('downloads'); }}>Downloads</a><a href={REPO} target="_blank" rel="noreferrer">GitHub <Arrow /></a></div>
      <small>© 2026 Sync In It · Android engineering preview</small>
    </div>
  </footer>;
}

function NotFound({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <main id="main-content" className="downloads-page shell"><Eyebrow>404 / NOT FOUND</Eyebrow><h1>Page not found.</h1><button className="button button-primary" onClick={() => navigate('home')}>Back to overview</button></main>;
}

export default function App() {
  const [page, navigate] = usePage();
  useEffect(() => {
    document.title = page === 'downloads' ? 'Downloads · Sync In It' : page === 'not-found' ? 'Page not found · Sync In It' : 'Sync In It — Local audio, shared playback';
  }, [page]);
  return <div className="app-shell">
    <Header page={page} navigate={navigate} />
    {page === 'home' && <main id="main-content"><Hero /><HowItWorks /><Status /><FAQ /></main>}
    {page === 'downloads' && <Downloads navigate={navigate} />}
    {page === 'not-found' && <NotFound navigate={navigate} />}
    <Footer navigate={navigate} />
  </div>;
}
