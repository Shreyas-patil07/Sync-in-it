import { useEffect, useState } from 'react';
import {
  Activity, ArrowDown, ArrowDownToLine, ArrowRight, AudioLines, AudioWaveform,
  Clock3, Code2, Github, Headphones, Menu, Mic2, Network, Radio,
  ShieldCheck, Smartphone, Timer, Wifi, X
} from 'lucide-react';

const REPO = 'https://github.com/Shreyas-patil07/Sync-in-it';
const RELEASES = REPO + '/releases';
const DOCS = REPO + '/tree/main/docs';

type Page = 'home' | 'downloads';

function usePage(): [Page, (page: Page) => void] {
  const [page, setPage] = useState<Page>(() => window.location.pathname === '/downloads' ? 'downloads' : 'home');
  useEffect(() => {
    const handler = () => setPage(window.location.pathname === '/downloads' ? 'downloads' : 'home');
    window.addEventListener('popstate', handler);
    return () => window.removeEventListener('popstate', handler);
  }, []);
  const navigate = (next: Page) => {
    const path = next === 'downloads' ? '/downloads' : '/';
    if (path !== window.location.pathname) window.history.pushState({}, '', path);
    setPage(next);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };
  return [page, navigate];
}

const milestones: [string, string, string][] = [
  ['M0', 'Time & drift simulator', 'Model clock offsets, network jitter, packet loss and playout drift.'],
  ['M1', 'Two-phone clock sync', 'Validate four-timestamp synchronization on physical Android devices.'],
  ['M2', 'Scheduled playback', 'Start a shared click track against one future timeline deadline.'],
  ['M3', 'DAC mapping', 'Map audio output timestamps and add manual device compensation.'],
  ['M4', 'Drift correction', 'Hold alignment through a 30-minute endurance run.'],
  ['M5', 'Group control', 'Support 3–4 receivers, group readiness and reconnect.'],
  ['M6', 'Audio sources', 'Add Opus and supported Android system-audio capture.'],
  ['M7', 'iOS receiver', 'Bring the shared receiver protocol to iPhone.'],
  ['M8', 'Acoustic calibration', 'Measure speaker arrival delay at a defined listening position.'],
  ['M9', 'Scale & field trial', 'Validate a 6–10 phone group in a train-coach environment.'],
];

const features: [typeof Clock3, string, string][] = [
  [Clock3, 'One shared timeline', 'Every audio sample gets a target presentation time. Devices do not start when packets arrive.'],
  [Network, 'Offline by design', 'Phones communicate over a private local Wi-Fi network. No cloud, account or internet required.'],
  [AudioWaveform, 'Continuous drift control', 'Clock estimation, audio hardware timestamps and correction keep playback aligned over time.'],
  [Mic2, 'Acoustic calibration', 'Scheduled chirps estimate output delay and compensate for device differences at one listening point.'],
  [Radio, 'Cross-platform receivers', 'An Android master coordinates Android and iOS receivers through one platform-neutral protocol.'],
  [ShieldCheck, 'Fail-safe playback', 'A receiver that cannot keep the timeline is muted and resynchronized instead of creating an echo.'],
];

function Background() {
  return <div className="ambient" aria-hidden="true">
    <div className="ambient-orb orb-one" /><div className="ambient-orb orb-two" />
    <svg viewBox="0 0 1440 900" preserveAspectRatio="none">
      <path d="M-150 700 C 180 200, 440 180, 700 410 S 1180 760, 1590 220" />
      <path d="M-120 250 C 250 760, 540 720, 830 420 S 1220 170, 1570 680" />
      <path d="M-100 850 C 300 520, 520 280, 850 470 S 1300 800, 1550 120" />
    </svg>
    <div className="grid-fade" />
  </div>;
}

function Brand({ compact = false }: { compact?: boolean }) {
  return <a className={'brand' + (compact ? ' brand-compact' : '')} href="/" aria-label="Sync In It home">
    <img src="/images/sync_in_it.png" alt="" />
    <span>sync<span className="brand-accent">in</span>it</span>
  </a>;
}

function Header({ page, navigate }: { page: Page; navigate: (page: Page) => void }) {
  const [open, setOpen] = useState(false);
  const go = (next: Page) => { navigate(next); setOpen(false); };
  return <header className="topbar"><div className="container nav-inner">
    <Brand compact />
    <nav className={open ? 'nav-links nav-open' : 'nav-links'}>
      <button onClick={() => go('home')} className={page === 'home' ? 'nav-link active' : 'nav-link'}>Overview</button>
      <a href={DOCS} className="nav-link">Documentation <ArrowRight size={13} /></a>
      <button onClick={() => go('downloads')} className={page === 'downloads' ? 'nav-link active' : 'nav-link'}>Downloads</button>
      <a href={REPO} target="_blank" rel="noreferrer" className="nav-github"><Github size={16} /> GitHub</a>
    </nav>
    <button className="mobile-menu" onClick={() => setOpen(!open)} aria-label="Toggle navigation">{open ? <X /> : <Menu />}</button>
  </div></header>;
}

function SectionHeading({ tag, title, description }: { tag: string; title: string; description: string }) {
  return <div className="section-heading"><div className="section-tag">{tag}</div><h2>{title}</h2><p>{description}</p></div>;
}

function Hero({ navigate }: { navigate: (page: Page) => void }) {
  return <section className="hero container">
    <div className="hero-copy">
      <div className="eyebrow"><span className="eyebrow-dot" /> OFFLINE AUDIO SYNCHRONIZATION <span className="eyebrow-line" /></div>
      <h1>One sound.<br /><span className="gradient-text">Every phone.</span></h1>
      <p className="hero-lede">Turn a group of smartphones into one synchronized speaker system. No internet. No dedicated speakers. Just your phones, playing together.</p>
      <div className="hero-actions">
        <button className="button button-primary" onClick={() => navigate('downloads')}>Explore downloads <ArrowDownToLine size={17} /></button>
        <a className="button button-ghost" href={REPO} target="_blank" rel="noreferrer">View source <Github size={17} /></a>
      </div>
      <div className="hero-meta"><span><span className="live-dot" /> Design & research phase</span><i /> <span>Android master · Android + iOS receivers</span></div>
    </div>
    <div className="hero-art">
      <div className="art-halo halo-one" /><div className="art-halo halo-two" />
      <div className="art-orbit orbit-a" /><div className="art-orbit orbit-b" />
      <div className="art-core"><img src="/images/sync_in_it.png" alt="Sync In It audio synchronization emblem" /></div>
      <div className="floating-card card-left"><span className="mini-icon cyan"><Wifi size={16} /></span><span><b>LOCAL NETWORK</b><small>No internet needed</small></span></div>
      <div className="floating-card card-right"><span className="mini-icon purple"><AudioLines size={17} /></span><span><b>SHARED TIMELINE</b><small>One playback deadline</small></span></div>
      <div className="floating-card card-bottom"><span className="mini-icon orange"><Activity size={17} /></span><span><b>REAL-TIME CONTROL</b><small>Clock · drift · buffer</small></span></div>
    </div>
    <div className="scroll-cue"><ArrowDown size={15} /> SCROLL TO EXPLORE</div>
  </section>;
}

function StatStrip() {
  const stats = [['6–10', 'PHONE GROUP TARGET'], ['48 kHz', 'BASE AUDIO RATE'], ['20 ms', 'AUDIO CHUNK'], ['0', 'INTERNET REQUIRED']];
  return <div className="stat-strip"><div className="container stat-grid">{stats.map(([value, label]) => <div className="stat" key={label}><b>{value}</b><span>{label}</span></div>)}</div></div>;
}

function HowItWorks() {
  const steps: [typeof Smartphone, string, string][] = [
    [Smartphone, 'Create a session', 'One Android phone becomes the master, source and clock authority. It shares a secure join QR.'],
    [Wifi, 'Join the network', 'Other phones connect over the same private Wi-Fi router and synchronize their clocks.'],
    [Timer, 'Buffer & schedule', 'Audio arrives early. Every receiver prepares for one future playback deadline.'],
    [Headphones, 'Play together', 'Each phone follows the sample timeline, tracks its audio clock and corrects drift.'],
  ];
  return <section className="section container" id="how-it-works">
    <SectionHeading tag="THE SYSTEM" title="Not just synchronized starts." description="The hard part is keeping independent phones aligned while their clocks, audio pipelines and network conditions keep changing." />
    <div className="steps-grid">{steps.map(([Icon, title, text], i) => <article className="step-card" key={String(title)}><div className="step-top"><span>0{i + 1}</span><Icon size={19} /></div><h3>{String(title)}</h3><p>{String(text)}</p></article>)}</div>
    <div className="flow-band"><div className="flow-node master-node"><span className="node-icon"><Radio size={20} /></span><span><b>ANDROID MASTER</b><small>Source + clock + controller</small></span></div><div className="flow-lines"><span /><span /><span /></div><div className="flow-receivers">
      {['ANDROID', 'iOS', 'MORE PHONES'].map((name) => <div className="flow-node" key={name}><span className="node-icon"><Smartphone size={18} /></span><span><b>{name}</b><small>Receiver</small></span></div>)}
    </div></div>
  </section>;
}

function Features() {
  return <section className="section section-tint" id="features"><div className="container">
    <SectionHeading tag="ENGINEERED FOR REAL DEVICES" title="Seven layers. One experience." description="A timing system built around the real constraints of smartphones—not simply sending audio to several devices." />
    <div className="features-grid">{features.map(([Icon, title, text], i) => <article className="feature-card" key={String(title)}><div className={'feature-icon tone-' + (i % 4)}><Icon size={21} /></div><h3>{String(title)}</h3><p>{String(text)}</p><span className="feature-index">0{i + 1}</span></article>)}</div>
  </div></section>;
}

function Architecture() {
  const layers = [
    ['L1', 'Audio transport', 'TCP · sample-indexed chunks'],
    ['L2', 'Clock distribution', 'UDP · four timestamps'],
    ['L3', 'Group playout', 'One common deadline'],
    ['L4', 'DAC clock mapping', 'Output frame timestamps'],
    ['L5', 'Drift correction', 'Bounded playout adjustment'],
    ['L6', 'Acoustic alignment', 'Chirp + microphone calibration'],
    ['L7', 'Resilience', 'Mute · recover · rejoin'],
  ];
  return <section className="section container" id="architecture"><div className="architecture-layout">
    <div><SectionHeading tag="UNDER THE HOOD" title="A timing engine, not a speaker clone." description="A portable C++ core handles timing logic while native Android and iOS layers handle device audio, lifecycle and permissions." />
      <div className="stack-tags"><span><Code2 size={14} /> C++17 core</span><span><Smartphone size={14} /> Kotlin + Oboe</span><span><Activity size={14} /> Swift + AVAudioEngine</span><span><Activity size={14} /> Python simulator</span></div>
    </div>
    <div className="layer-stack">{layers.map(([id, title, detail], i) => <div className="layer-row" key={id}><span className="layer-id">{id}</span><b>{title}</b><small>{detail}</small><span className="layer-pulse" style={{ animationDelay: i * 0.18 + 's' }} /></div>)}</div>
  </div></section>;
}

function Targets() {
  const tiers = [['T1', '≤20 ms', 'Initial practical target', '100%'], ['T2', '≤10 ms', 'Strong mixed-device target', '50%'], ['T3', '≤5 ms', 'Stretch goal at listening position', '25%']];
  return <section className="section section-tint"><div className="container target-layout">
    <SectionHeading tag="MEASURED, NOT PROMISED" title="Precision is a test result." description="Different phone clocks, output pipelines and speaker distances make perfect zero-millisecond synchronization unrealistic. These are engineering targets—not guarantees." />
    <div className="target-panel">{tiers.map(([label, value, note, width]) => <div className="tier" key={label}><div className="tier-head"><span>{label}</span><b>{value}</b></div><div className="tier-track"><span style={{ width }} /></div><p>{note}</p></div>)}<div className="target-foot"><ShieldCheck size={15} /> Every result will include mean, p95 and maximum error.</div></div>
  </div></section>;
}

function Roadmap() {
  return <section className="section container" id="roadmap">
    <SectionHeading tag="DEVELOPMENT ROADMAP" title="Built in measurable milestones." description="Synchronization is validated layer by layer. A milestone is complete only when its physical or simulated acceptance gate passes." />
    <div className="roadmap-grid">{milestones.map(([id, title, detail]) => <article className="roadmap-card" key={id}><div className="roadmap-card-top"><span>{id}</span><em><i /> PLANNED</em></div><h3>{title}</h3><p>{detail}</p></article>)}</div>
  </section>;
}

function CTA({ navigate }: { navigate: (page: Page) => void }) {
  return <section className="container cta-section"><div className="cta-card"><div className="cta-glow" /><div className="cta-content"><div className="section-tag">OPEN DEVELOPMENT</div><h2>Help us get the timing right.</h2><p>Explore the architecture, follow the milestones or review the source as the system takes shape.</p><div className="hero-actions"><a className="button button-primary" href={REPO} target="_blank" rel="noreferrer">Explore on GitHub <Github size={16} /></a><button className="button button-ghost" onClick={() => navigate('downloads')}>Visit downloads <ArrowRight size={16} /></button></div></div><div className="cta-emblem"><img src="/images/sync_in_it.png" alt="" /></div></div></section>;
}

function Downloads({ navigate }: { navigate: (page: Page) => void }) {
  return <main className="container download-page">
    <div className="download-hero"><div className="section-tag">DOWNLOAD CENTER</div><h1>Everything you need.<br /><span className="gradient-text">When it’s ready.</span></h1><p>Sync In It is currently in the design and research phase. No installable builds have been published yet. This page will host verified releases when the first prototype is ready.</p><div className="release-state"><span className="status-orb" /><div><b>No public builds yet</b><small>Development status: pre-release</small></div></div></div>
    <div className="download-grid">
      <article className="download-card"><div className="download-card-icon android-icon"><Smartphone size={24} /></div><div className="download-card-top"><span>ANDROID</span><em>COMING LATER</em></div><h2>Sync In It for Android</h2><p>Master and receiver modes. Local file playback first, followed by supported live capture.</p><button className="button button-disabled" disabled><ArrowDownToLine size={16} /> No APK available</button><small className="platform-note">Target: Android receiver baseline and newer master features are provisional.</small></article>
      <article className="download-card"><div className="download-card-icon ios-icon"><Smartphone size={24} /></div><div className="download-card-top"><span>iOS</span><em>ROADMAP M7</em></div><h2>Sync In It for iPhone</h2><p>Receiver app following the same protocol and shared timing core as Android.</p><button className="button button-disabled" disabled><ArrowDownToLine size={16} /> No iOS build available</button><small className="platform-note">Target: iOS 15+ (provisional)</small></article>
      <article className="download-card"><div className="download-card-icon source-icon"><Github size={24} /></div><div className="download-card-top"><span>SOURCE CODE</span><em>AVAILABLE</em></div><h2>Explore the repository</h2><p>Review the current specifications, architecture decisions and development roadmap.</p><a className="button button-primary" href={REPO} target="_blank" rel="noreferrer"><Github size={16} /> View source on GitHub</a><small className="platform-note">Source repository is public; application code is not released yet.</small></article>
    </div>
    <div className="release-panel"><div className="release-panel-icon"><ArrowDownToLine size={20} /></div><div><h3>Looking for the latest release?</h3><p>Published binaries will appear in GitHub Releases with version notes, checksums and supported-device information.</p></div><a href={RELEASES} target="_blank" rel="noreferrer" className="text-link">Open releases <ArrowRight size={15} /></a></div>
    <div className="download-back"><button className="button button-ghost" onClick={() => navigate('home')}><ArrowRight className="rotate-180" size={16} /> Back to overview</button></div>
  </main>;
}

function Footer({ navigate }: { navigate: (page: Page) => void }) {
  return <footer className="footer"><div className="container footer-main"><div><Brand compact /><p>One sound. Every phone.<br />Designed to work offline.</p></div><div className="footer-links"><div><b>EXPLORE</b><a href="#how-it-works">How it works</a><a href="#architecture">Architecture</a><a href="#roadmap">Roadmap</a></div><div><b>RESOURCES</b><a href={DOCS}>Documentation</a><button onClick={() => navigate('downloads')}>Downloads</button><a href={REPO}>GitHub repository</a></div></div></div><div className="container footer-bottom"><span>© 2026 Sync In It · Research & development project</span><span>Built for local-first audio synchronization.</span></div></footer>;
}

export default function App() {
  const [page, navigate] = usePage();
  useEffect(() => { document.title = page === 'downloads' ? 'Downloads — Sync In It' : 'Sync In It — One sound. Every phone.'; }, [page]);
  return <div className="app-shell"><Background /><Header page={page} navigate={navigate} />{page === 'downloads' ? <Downloads navigate={navigate} /> : <><Hero navigate={navigate} /><StatStrip /><HowItWorks /><Features /><Architecture /><Targets /><Roadmap /><CTA navigate={navigate} /></>}<Footer navigate={navigate} /></div>;
}