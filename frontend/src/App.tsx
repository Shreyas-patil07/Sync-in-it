// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';

const REPO = 'https://github.com/Shreyas-patil07/Sync-in-it';
const DOCS = REPO + '/tree/main/docs';
const STATUS_DOC = REPO + '/blob/main/docs/11_Current_Implementation_Status.md';
const RELEASES = REPO + '/releases';
const VERSION = 'v0.5.9-preview.1';
const LATEST_RELEASE = REPO + '/releases/tag/v0.5.9-preview.1';
const ANDROID_APK = REPO + '/releases/download/v0.5.9-preview.1/SyncInIt-Android-preview-v0.5.9.apk';
const ANDROID_CHECKSUMS = REPO + '/releases/download/v0.5.9-preview.1/SHA256SUMS.txt';
const ANDROID_CI = REPO + '/actions/workflows/publish-android-release.yml';
const DIAGNOSTICS_PR = REPO + '/pull/55';
const MEASUREMENT_DOC = REPO + '/blob/main/tools/README.md';

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
    <header className="site-header"><div className="brand-island"><Brand compact /></div></header>
    <nav id="primary-navigation" className="nav-links action-island" aria-label="Primary navigation">
      <a href="/" onClick={(event) => { event.preventDefault(); go('home'); }} aria-current={page === 'home' ? 'page' : undefined}>Overview</a>
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
        <small>ONE TIMELINE · MANY SPEAKERS</small>
      </div>
      <div className="hero-copy">
        <Eyebrow>OFFLINE AUDIO · ANDROID ENGINEERING PREVIEW</Eyebrow>
        <h1 id="hero-title">Many phones.<br /><span className="hero-gradient">One shared moment.</span></h1>
        <p className="hero-lede">Sync In It is building a local-network audio system around one shared clock. The Android preview implements multi-Speaker admission, PCM16 fan-out and a coordinated playback-start path. Preview v0.5.9 adds receiver hot-path backpressure mitigation and diagnostics; post-fix physical validation is still pending, so synchronized playback is not yet a verified result.</p>
        <div className="hero-actions">
          <a className="button button-primary" href="/downloads">Get Android preview <Arrow /></a>
          <a className="button button-text" href={STATUS_DOC} target="_blank" rel="noreferrer">Read current engineering status <Arrow diagonal /></a>
        </div>
        <div className="hero-status"><span className="status-point" /><span>{VERSION} released</span><span className="status-separator">·</span><span>Physical READY gate under investigation</span></div>
      </div>
    </div>
  </section>;
}

const highlights = [
  { icon: '⌁', tone: 'cyan', title: 'Discover and admit Speakers.', text: 'Android Hosts advertise on the local network. Up to eight Android Speakers can establish separate control and audio TCP channels.' },
  { icon: '♫', tone: 'violet', title: 'Deliver one shared test stream.', text: 'A 48 kHz stereo PCM16 timing-click stream is generated once, indexed by sample position and fanned out through bounded per-Speaker queues.' },
  { icon: '◷', tone: 'magenta', title: 'Coordinate a common start.', text: 'Speakers acquire the Host clock, buffer PCM and report READY. The Host then sends one future playback ANCHOR to the admitted group.' },
];

function Highlights() {
  return <section className="section highlights-section" id="experience">
    <div className="shell">
      <div className="section-heading" data-reveal>
        <Eyebrow>WHAT THE PREVIEW CONTAINS</Eyebrow>
        <h2>From discovery<br /><span className="heading-gradient">to a shared timeline.</span></h2>
        <p>The implementation has moved beyond a discovery-only shell. Its last mile—reliably reaching READY and proving synchronized sound on physical phones—is still being tested.</p>
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
  { number: '01', title: 'Host and discover', text: 'The Host opens control, audio and UDP listeners and advertises an mDNS service. Speakers discover it on the same Wi-Fi network.' },
  { number: '02', title: 'Join and prepare', text: 'Each Speaker completes HELLO/WELCOME and AUDIO_HELLO, receives indexed PCM chunks and exchanges UDP timestamps to estimate the Host clock.' },
  { number: '03', title: 'Reach READY, then start', text: 'The Speaker must have a stable clock and at least 3 seconds of contiguous PCM. The Host starts the cohort only after admission closes and all remaining Speakers are READY.' },
];

function HowItWorks() {
  return <section className="section process-section" id="how-it-works">
    <div className="shell">
      <div className="section-heading" data-reveal>
        <Eyebrow>THE CURRENT ANDROID FLOW</Eyebrow>
        <h2>Three stages.<br /><span className="heading-gradient">One playback deadline.</span></h2>
        <p>Audio packet arrival does not start playback. The Host selects a common future timestamp and sample index only after the readiness gate passes.</p>
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
        <Eyebrow>ENGINEERING STATUS · 6 NOVEMBER 2026</Eyebrow>
        <h2>The pipeline is implemented.<br />The READY gate is not passing yet.</h2>
        <p>The latest three-phone LAN run discovered the Host, accepted multiple control/audio connections and exchanged UDP time-sync packets. No Speaker reached READY before the 45-second startup deadline, so the Host removed the cohort. No successful multi-Speaker ANCHOR or acoustic-sync result has been demonstrated.</p>
        <a className="button button-light" href={STATUS_DOC} target="_blank" rel="noreferrer">Read the full status report <Arrow diagonal /></a>
      </div>
      <div className="status-card">
        <div className="status-card-top"><span>PROJECT STATUS</span><span className="status-live-dot" /> ACTIVE DEVELOPMENT</div>
        <div className="status-current">
          <span className="status-milestone">M2c</span>
          <div><b>Multi-Speaker PCM fan-out</b><small>IMPLEMENTED · PHYSICAL READY TEST FAILS</small></div>
        </div>
        <div className="status-next">
          <span className="status-milestone">M3a</span>
          <div><b>DAC output-clock diagnostics</b><small>IMPLEMENTED · DIAGNOSTIC ONLY</small></div>
        </div>
        <div className="status-card-foot">PR #53 merged the acoustic measurement path. The published v0.5.8 preview contains the READY/audio diagnostics and receiver-specific markers; synthetic analyzer tests pass CI, but real-phone acoustic measurement is still pending.</div>
        <div className="status-card-links">
          <a className="inline-link" href={ANDROID_CI} target="_blank" rel="noreferrer">Android build status <Arrow diagonal /></a>
          <a className="inline-link" href={DIAGNOSTICS_PR} target="_blank" rel="noreferrer">Diagnostics changes <Arrow diagonal /></a>
        </div>
      </div>
    </div>
  </section>;
}

const milestones = [
  { id: 'M0', title: 'Simulator and timing core', state: 'CI passing', detail: 'Python reference simulator, C++17 timing core, shared vectors and deterministic simulator gates.' },
  { id: 'M1', title: 'Android LAN and clock acquisition', state: 'Implemented · device validation open', detail: 'mDNS discovery, TCP control/audio admission and UDP four-timestamp exchange are present. The latest physical run did not reach the READY gate.' },
  { id: 'M2a–d', title: 'PCM and coordinated group start', state: 'Implemented · not physically proven', detail: 'PCM16 codec, native sample-indexed buffer, AudioTrack writer, multi-Speaker fan-out and shared future ANCHOR path. Generated 1 kHz test signal only.' },
  { id: 'M3a', title: 'DAC timestamp mapping', state: 'Diagnostics only', detail: 'AudioTrack presentation timestamps feed a rolling regression. The estimate does not yet drive drift correction or latency compensation.' },
  { id: 'TEST', title: 'Two-phone acoustic measurement', state: 'Tooling implemented · physical run pending', detail: 'Receiver-specific 20 ms markers, reference WAV generation and a PCM16/48 kHz analyzer. Synthetic offsets pass CI; no phone skew value exists yet.' },
  { id: 'M4+', title: 'Drift, recovery and calibration', state: 'Planned / incomplete', detail: 'Integrated drift controller, proven reconnect, microphone calibration, endurance and acoustic acceptance tests remain outstanding.' },
];

function Milestones() {
  return <section className="section milestones-section" id="milestones">
    <div className="shell">
      <div className="section-heading" data-reveal>
        <Eyebrow>IMPLEMENTED VS. VALIDATED</Eyebrow>
        <h2>Progress, without<br /><span className="heading-gradient">overstating the result.</span></h2>
        <p>Code completion, CI success and physical-device proof are tracked separately.</p>
      </div>
      <div className="milestone-grid">
        {milestones.map((item) => <article className="milestone-card" key={item.id} data-reveal>
          <span>{item.id}</span>
          <div><h3>{item.title}</h3><b>{item.state}</b><p>{item.detail}</p></div>
        </article>)}
      </div>
    </div>
  </section>;
}

function ValidationSnapshot() {
  return <section className="section validation-section" id="validation">
    <div className="shell">
      <div className="section-heading" data-reveal>
        <Eyebrow>LATEST PHYSICAL TEST</Eyebrow>
        <h2>What passed.<br /><span className="heading-gradient">What stopped.</span></h2>
        <p>Evidence from the most recent supplied Host log and screen, not a product-performance claim.</p>
      </div>
      <div className="evidence-grid">
        <article className="evidence-card evidence-pass"><span>OBSERVED WORKING</span><ul><li>mDNS discovery and Host resolution</li><li>Control TCP HELLO/WELCOME</li><li>Separate audio TCP AUDIO_HELLO</li><li>UDP peer pinning and repeated responses</li></ul></article>
        <article className="evidence-card evidence-blocked"><span>BLOCKING THE GROUP</span><ul><li>No Speaker READY event observed</li><li>45-second readiness deadline expired</li><li>No GROUP_START_SCHEDULED / ANCHOR</li><li>Post-removal UDP source rejection observed</li></ul></article>
      </div>
      <p className="validation-footnote">The Host log alone cannot distinguish an unstable clock from an insufficient PCM buffer. The v0.5.8 preview now includes Speaker-side READY_GATE_BLOCKED and Host-side HOST_AUDIO_DELIVERY diagnostics, plus the receiver-specific acoustic markers. The earlier v0.5.7 APK did not include them; no physical skew result is recorded yet.</p>
    </div>
  </section>;
}

const questions = [
  { question: 'Does Sync In It need internet?', answer: 'No. Session discovery and audio transport are designed for a private local Wi-Fi network. The router must allow multicast discovery and client-to-client traffic; internet access is not required.' },
  { question: 'Which platforms work today?', answer: 'The current app is Android-only for both Host and Speaker roles. An iOS Speaker is a future milestone; no iOS client is implemented.' },
  { question: 'Can I install the current preview?', answer: 'Yes. v0.5.9-preview.1 is the current signed Android engineering pre-release, with an APK and SHA-256 checksum. It includes the merged READY/audio diagnostics and acoustic markers. Install it over an earlier preview signed with the same persistent key; synchronized playback still has not been physically verified.' },
  { question: 'Does multi-Speaker playback work?', answer: 'The code implements admission for up to eight Speakers, PCM fan-out, a 20-second rolling pre-ANCHOR buffer and a common future playback anchor. The latest physical test connected multiple Speakers and exchanged UDP packets, but none reached READY. Do not treat synchronized group playback as verified.' },
  { question: 'Can I play Spotify or local music?', answer: 'Not in the current preview. The Host generates a 1 kHz timing-click test stream. User-selected music, local-file decoding and Android playback capture are not implemented in this preview.' },
  { question: 'What does DAC clock mapping mean?', answer: 'The app polls Android AudioTrack presentation timestamps and fits a rolling frame-position/time regression. This currently reports diagnostics only; it does not correct drift, compensate device output latency or measure sound arrival through a microphone.' },
  { question: 'Is the session secure on public Wi-Fi?', answer: 'No. The session token is currently advertised in DNS-SD metadata and can be visible to other devices on the same LAN. The token is not transport encryption. Use a trusted private network only.' },
  { question: 'Have the phones been measured yet?', answer: 'No. The latest supplied physical run failed before READY, so it never reached the common ANCHOR and cannot be used as an acoustic-skew measurement. This branch adds receiver-specific markers and a WAV analyzer; the synthetic analyzer tests pass, but PHONE-SKEW-001 is still pending.' },
  { question: 'Which APK contains the acoustic markers?', answer: 'The public v0.5.9-preview.1 APK includes the receiver-specific markers and analyzer toolchain. Both Speakers still need to reach READY and receive the common ANCHOR before a recording can measure skew. Keep the apps foregrounded and screens awake; no real-phone skew result is available yet.' },
  { question: 'What is the next engineering focus?', answer: 'Resolve the READY gate without loosening clock thresholds, install the marker-enabled measurement build, then record about two minutes with two Speakers at equal distance from a laptop microphone. Do not run long tests until a foreground playback service and wake-lock lifecycle exist.' },
];

function FAQ() {
  return <section className="section faq-section" id="faq">
    <div className="shell faq-layout">
      <div className="faq-copy" data-reveal>
        <Eyebrow>GOOD TO KNOW</Eyebrow>
        <h2>Questions?<br /><span className="heading-gradient">Clear answers.</span></h2>
        <p>What the current engineering preview does—and what it does not claim.</p>
        <a className="inline-link" href={DOCS} target="_blank" rel="noreferrer">Browse the documentation <Arrow diagonal /></a>
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
      <Eyebrow>FOLLOW THE ENGINEERING</Eyebrow>
      <h2>Build the shared timeline. Prove it on real devices.</h2>
      <p>Download the Android preview, review the implementation status and follow the work toward a measured multi-phone listening experience.</p>
      <div className="closing-actions">
        <a className="button button-light" href={LATEST_RELEASE} target="_blank" rel="noreferrer">View {VERSION} <Arrow diagonal /></a>
        <a className="button button-outline-light" href={STATUS_DOC} target="_blank" rel="noreferrer">Current status</a>
      </div>
    </div>
    <img className="closing-logo" src="/images/sync_in_it.png" alt="" aria-hidden="true" />
  </section>;
}

function Footer({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <footer className="site-footer">
    <div className="shell footer-main">
      <div className="footer-brand"><Brand compact /><p>Local audio. Shared time.<br />Android engineering preview {VERSION}.<br />Physical READY validation is in progress.</p></div>
      <div className="footer-columns">
        <div><span>RESOURCES</span><a href={STATUS_DOC} target="_blank" rel="noreferrer">Current implementation status</a><a href={DOCS} target="_blank" rel="noreferrer">Full documentation</a><a href={REPO + '/blob/main/LICENSE'} target="_blank" rel="noreferrer">Code license</a><a href={REPO + '/blob/main/LICENSE-DOCS'} target="_blank" rel="noreferrer">Documentation license</a></div>
        <div><span>PROJECT</span><a href="/downloads" onClick={(event) => { event.preventDefault(); navigate('downloads'); }}>Downloads</a><a href={RELEASES} target="_blank" rel="noreferrer">Releases</a><a href={REPO} target="_blank" rel="noreferrer">GitHub ↗</a></div>
      </div>
    </div>
    <div className="shell footer-bottom"><span>© 2026 Sync In It</span><span>M0 CI passing · Android READY gate under investigation · acoustic sync unverified</span></div>
  </footer>;
}

function Home() {
  return <main id="main-content">
    <Hero />
    <Highlights />
    <HowItWorks />
    <ProjectStatus />
    <Milestones />
    <ValidationSnapshot />
    <FAQ />
    <ClosingCTA />
  </main>;
}

function Downloads({ navigate }: { navigate: (page: NavigablePage) => void }) {
  return <main id="main-content" className="downloads-page">
    <section className="downloads-hero shell">
      <Eyebrow>DOWNLOADS / ANDROID PRE-RELEASE</Eyebrow>
      <h1>Android preview.<br /><span className="hero-gradient">Engineering build, not a sync guarantee.</span></h1>
      <p>{VERSION} contains the multi-Speaker networking, PCM test-stream and coordinated-start implementation. The latest supplied physical test did not pass the READY gate, so synchronized multi-phone playback remains unverified.</p>
      <div className="release-status"><span className="status-point" /><div><b>{VERSION}</b><small>Published GitHub pre-release · Android · signed APK + SHA-256</small></div></div>
    </section>
    <section className="downloads-content shell">
      <div className="downloads-primary">
        <span className="downloads-label">ANDROID PREVIEW / {VERSION}</span>
        <h2>Download the signed engineering preview.</h2>
        <p>Use the measurement-branch APK, not the public v0.5.7 release: the latter does not include READY_GATE_BLOCKED/HOST_AUDIO_DELIVERY or receiver-specific acoustic markers.</p>
        <a className="button button-primary" href={ANDROID_APK} target="_blank" rel="noreferrer">Download Android APK <Arrow diagonal /></a>
        <a className="inline-link" href={ANDROID_CHECKSUMS} target="_blank" rel="noreferrer">Verify SHA-256 checksum <Arrow diagonal /></a>
        <a className="inline-link" href={LATEST_RELEASE} target="_blank" rel="noreferrer">Read release notes <Arrow diagonal /></a>
        <div className="download-warning"><b>Important limitations</b><p>Generated 1 kHz test signal plus a receiver-specific acoustic marker in this branch only; Android only; up to eight Speakers. Multi-Speaker READY and acoustic synchronization have not been physically validated. Use a trusted LAN—the session token is visible in DNS-SD metadata. Keep the app foregrounded and screens awake; no foreground playback service/wake-lock exists.</p></div>
      </div>
      <div className="downloads-secondary">
        <article><span>01 / IMPLEMENTATION</span><h3>See what is in the repository.</h3><p>Python simulator, portable C++17 core, Android Kotlin/JNI client, PCM jitter buffer, TCP/UDP transport and DAC timestamp diagnostic path.</p><a className="inline-link" href={REPO} target="_blank" rel="noreferrer">Browse the source <Arrow diagonal /></a></article>
        <article><span>02 / MEASUREMENT</span><h3>Measure the phone output.</h3><p>Use the receiver-specific click markers and analyze a two-minute Audacity WAV. Synthetic tests pass; a physical run is still pending.</p><a className="inline-link" href={MEASUREMENT_DOC} target="_blank" rel="noreferrer">Read measurement instructions <Arrow diagonal /></a></article>
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
