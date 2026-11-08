// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ClockFit, ClockProbeSample } from './clockSync';
import { estimateClock, makeClockSample } from './clockSync';
import './browser-room.css';

type Role = 'idle' | 'host' | 'speaker';
type SourceMode = 'file' | 'microphone' | 'tab';

interface PeerView {
  id: string;
  state: string;
  channel: string;
  ready: boolean;
  packets: number;
  lost: number;
  jitterMs: number;
  jitterBufferMs: number;
  bitrateKbps: number;
  clock: ClockFit | null;
}

interface SignalPayload {
  kind: 'description' | 'candidate';
  description?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit | null;
}

interface HostPeer {
  id: string;
  pc: RTCPeerConnection;
  channel: RTCDataChannel | null;
  pendingCandidates: RTCIceCandidateInit[];
  ready: boolean;
  clock: ClockFit | null;
  statsTimer: number | null;
  lastBytes: number;
  lastStatsAt: number;
}

interface SpeakerPeer {
  hostPeerId: string;
  pc: RTCPeerConnection;
  channel: RTCDataChannel | null;
  pendingCandidates: RTCIceCandidateInit[];
  context: AudioContext;
  gain: GainNode | null;
  source: MediaStreamAudioSourceNode | null;
  samples: ClockProbeSample[];
  stableStreak: number;
  clock: ClockFit | null;
  nextSequence: number;
  probeTimer: number | null;
  statsTimer: number | null;
  packetsReceived: number;
  packetsLost: number;
  jitterMs: number;
  jitterBufferMs: number;
  bitrateKbps: number;
  lastBytes: number;
  lastStatsAt: number;
  trackUnmuted: boolean;
  readySent: boolean;
  playing: boolean;
}

const ICE_SERVERS: RTCIceServer[] = (() => {
  const configured = import.meta.env.VITE_ICE_SERVERS as string | undefined;
  if (configured) {
    try {
      const parsed = JSON.parse(configured) as RTCIceServer[];
      if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    } catch {
      console.warn('VITE_ICE_SERVERS must be valid JSON; using the public STUN fallback.');
    }
  }
  return [{ urls: 'stun:stun.l.google.com:19302' }];
})();

function signalingUrl(): string | null {
  const configured = (import.meta.env.VITE_SIGNALING_URL as string | undefined)?.trim();
  if (configured) return configured;
  if (import.meta.env.DEV) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.hostname}:8787/signal`;
  }
  return null;
}

function preferOpus(transceiver: RTCRtpTransceiver | undefined) {
  if (!transceiver || typeof transceiver.setCodecPreferences !== 'function') return;
  const capabilities = RTCRtpSender.getCapabilities?.('audio');
  if (!capabilities) return;
  const codecs = capabilities.codecs;
  const opus = codecs.filter((codec) => codec.mimeType.toLowerCase() === 'audio/opus');
  if (opus.length === 0) return;
  const other = codecs.filter((codec) => codec.mimeType.toLowerCase() !== 'audio/opus');
  transceiver.setCodecPreferences([...opus, ...other]);
}

function sendJson(channel: RTCDataChannel, value: Record<string, unknown>) {
  if (channel.readyState === 'open') channel.send(JSON.stringify(value));
}

function parseMessage(data: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(data);
    if (value && typeof value === 'object') return value as Record<string, unknown>;
  } catch {
    // Ignore malformed peer-control payloads.
  }
  return null;
}

function numberValue(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function formatMs(value: number | undefined, digits = 1): string {
  return value === undefined || !Number.isFinite(value) ? '—' : `${value.toFixed(digits)} ms`;
}

function formatBitrate(value: number): string {
  return value < 1000 ? `${value.toFixed(0)} kb/s` : `${(value / 1000).toFixed(2)} Mb/s`;
}

function peerView(peer: HostPeer): PeerView {
  return {
    id: peer.id,
    state: peer.pc.connectionState,
    channel: peer.channel?.readyState ?? 'connecting',
    ready: peer.ready,
    packets: 0,
    lost: 0,
    jitterMs: 0,
    jitterBufferMs: 0,
    bitrateKbps: 0,
    clock: peer.clock,
  };
}

export default function BrowserRoom() {
  const [role, setRole] = useState<Role>('idle');
  const [sourceMode, setSourceMode] = useState<SourceMode>('file');
  const [audioFile, setAudioFile] = useState<File | null>(null);
  const [fileUrl, setFileUrl] = useState('');
  const [roomCode, setRoomCode] = useState('');
  const [joinCode, setJoinCode] = useState('');
  const [status, setStatus] = useState('Choose Host or Speaker to begin.');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [hostPeers, setHostPeers] = useState<PeerView[]>([]);
  const [speakerState, setSpeakerState] = useState<PeerView | null>(null);
  const [speakerClock, setSpeakerClock] = useState<ClockFit | null>(null);
  const [speakerStreak, setSpeakerStreak] = useState(0);
  const [scheduled, setScheduled] = useState(false);
  const [isSecure, setIsSecure] = useState(true);

  const roleRef = useRef<Role>('idle');
  const socketRef = useRef<WebSocket | null>(null);
  const hostPeersRef = useRef(new Map<string, HostPeer>());
  const speakerRef = useRef<SpeakerPeer | null>(null);
  const hostStreamRef = useRef<MediaStream | null>(null);
  const hostAudioContextRef = useRef<AudioContext | null>(null);
  const hostElementSourceRef = useRef<MediaElementAudioSourceNode | null>(null);
  const hostDestinationRef = useRef<MediaStreamAudioDestinationNode | null>(null);
  const hostAudioRef = useRef<HTMLAudioElement>(null);
  const fileUrlRef = useRef('');
  const cleanupRef = useRef(false);

  useEffect(() => {
    setIsSecure(window.isSecureContext);
  }, []);

  useEffect(() => {
    if (!audioFile) {
      setFileUrl('');
      return;
    }
    const url = URL.createObjectURL(audioFile);
    fileUrlRef.current = url;
    setFileUrl(url);
    return () => {
      URL.revokeObjectURL(url);
      if (fileUrlRef.current === url) fileUrlRef.current = '';
    };
  }, [audioFile]);

  const updateRole = useCallback((next: Role) => {
    roleRef.current = next;
    setRole(next);
  }, []);

  const patchHostPeer = useCallback((peerId: string, patch: Partial<PeerView>) => {
    setHostPeers((current) => current.map((peer) =>
      peer.id === peerId ? { ...peer, ...patch } : peer));
  }, []);

  const sendSignal = useCallback((targetPeerId: string, payload: SignalPayload) => {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'signal', targetPeerId, payload }));
    }
  }, []);

  const closeHostPeer = useCallback((peerId: string) => {
    const peer = hostPeersRef.current.get(peerId);
    if (!peer) return;
    if (peer.statsTimer !== null) window.clearInterval(peer.statsTimer);
    peer.channel?.close();
    peer.pc.close();
    hostPeersRef.current.delete(peerId);
    setHostPeers((current) => current.filter((item) => item.id !== peerId));
  }, []);

  const stopSession = useCallback((announce = true) => {
    cleanupRef.current = true;
    if (announce && socketRef.current?.readyState === WebSocket.OPEN) {
      socketRef.current.send(JSON.stringify({ type: 'leave-room' }));
    }
    socketRef.current?.close();
    socketRef.current = null;

    for (const peerId of hostPeersRef.current.keys()) closeHostPeer(peerId);
    hostPeersRef.current.clear();

    const speaker = speakerRef.current;
    if (speaker) {
      if (speaker.probeTimer !== null) window.clearInterval(speaker.probeTimer);
      if (speaker.statsTimer !== null) window.clearInterval(speaker.statsTimer);
      speaker.channel?.close();
      speaker.pc.close();
      speaker.gain?.disconnect();
      speaker.source?.disconnect();
      void speaker.context.close();
      speakerRef.current = null;
    }

    hostStreamRef.current?.getTracks().forEach((track) => track.stop());
    hostStreamRef.current = null;
    if (hostAudioRef.current) hostAudioRef.current.pause();
    if (hostDestinationRef.current) {
      hostElementSourceRef.current?.disconnect(hostDestinationRef.current);
      hostDestinationRef.current.stream.getTracks().forEach((track) => track.stop());
    }
    hostDestinationRef.current = null;

    updateRole('idle');
    setHostPeers([]);
    setSpeakerState(null);
    setSpeakerClock(null);
    setSpeakerStreak(0);
    setRoomCode('');
    setScheduled(false);
    setError('');
    setBusy(false);
    setStatus('Disconnected.');
    cleanupRef.current = false;
  }, [closeHostPeer, updateRole]);

  useEffect(() => () => {
    cleanupRef.current = true;
    socketRef.current?.close();
    for (const peer of hostPeersRef.current.values()) {
      if (peer.statsTimer !== null) window.clearInterval(peer.statsTimer);
      peer.channel?.close();
      peer.pc.close();
    }
    const speaker = speakerRef.current;
    if (speaker) {
      if (speaker.probeTimer !== null) window.clearInterval(speaker.probeTimer);
      if (speaker.statsTimer !== null) window.clearInterval(speaker.statsTimer);
      speaker.channel?.close();
      speaker.pc.close();
      speaker.gain?.disconnect();
      speaker.source?.disconnect();
      void speaker.context.close();
    }
    hostStreamRef.current?.getTracks().forEach((track) => track.stop());
    if (hostAudioRef.current) hostAudioRef.current.pause();
    hostAudioContextRef.current?.close().catch(() => undefined);
  }, []);

  const openSignaling = useCallback(async (): Promise<WebSocket> => {
    const url = signalingUrl();
    if (!url) {
      throw new Error('Production signaling is not configured. Set VITE_SIGNALING_URL to the deployed wss:// signaling endpoint.');
    }

    const socket = new WebSocket(url);
    socketRef.current = socket;

    await new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        socket.close();
        reject(new Error('Signaling connection timed out.'));
      }, 10_000);

      socket.onopen = () => {
        window.clearTimeout(timeout);
        resolve();
      };
      socket.onerror = () => {
        window.clearTimeout(timeout);
        reject(new Error('Could not connect to the signaling server. Check that the server is running and the URL is correct.'));
      };
      socket.onclose = () => {
        if (socketRef.current === socket) socketRef.current = null;
      };
    });

    socket.onmessage = (event) => {
      const message = parseMessage(String(event.data));
      if (message) void handleServerMessage(message);
    };
    socket.onclose = () => {
      if (!cleanupRef.current && roleRef.current !== 'idle') {
        setStatus('Signaling connection closed.');
        setError('The room signaling connection was lost. Existing peer media may stop.');
      }
      if (socketRef.current === socket) socketRef.current = null;
    };
    return socket;
  }, []);

  const createHostStream = useCallback(async (): Promise<MediaStream> => {
    if (sourceMode === 'microphone') {
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error('Microphone capture requires a secure HTTPS page and a supported browser.');
      }
      return navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: false,
          noiseSuppression: false,
          autoGainControl: false,
        },
        video: false,
      });
    }

    if (sourceMode === 'tab') {
      if (!navigator.mediaDevices?.getDisplayMedia) {
        throw new Error('Tab-audio capture is not supported by this browser.');
      }
      const captured = await navigator.mediaDevices.getDisplayMedia({
        video: true,
        audio: true,
      });
      const audioTracks = captured.getAudioTracks();
      captured.getVideoTracks().forEach((track) => track.stop());
      if (audioTracks.length === 0) {
        captured.getTracks().forEach((track) => track.stop());
        throw new Error('No tab audio was captured. Share a browser tab and enable its audio-sharing option.');
      }
      return new MediaStream(audioTracks);
    }

    if (!audioFile || !fileUrl || !hostAudioRef.current) {
      throw new Error('Choose an audio file before starting the Host.');
    }

    const context = hostAudioContextRef.current ?? new AudioContext({ latencyHint: 'interactive' });
    hostAudioContextRef.current = context;
    await context.resume();

    const audio = hostAudioRef.current;
    audio.src = fileUrl;
    audio.loop = false;

    if (!hostElementSourceRef.current) {
      hostElementSourceRef.current = context.createMediaElementSource(audio);
      hostElementSourceRef.current.connect(context.destination);
    }
    if (hostDestinationRef.current) {
      hostElementSourceRef.current.disconnect(hostDestinationRef.current);
      hostDestinationRef.current.stream.getTracks().forEach((track) => track.stop());
    }

    const destination = context.createMediaStreamDestination();
    hostElementSourceRef.current.connect(destination);
    hostDestinationRef.current = destination;

    await audio.play();
    return destination.stream;
  }, [audioFile, fileUrl, sourceMode]);

  const sampleStats = useCallback(async (pc: RTCPeerConnection, previous: {
    lastBytes: number;
    lastStatsAt: number;
  }) => {
    const report = await pc.getStats();
    const reports = [...report.values()] as Array<Record<string, unknown>>;
    const inbound = reports.find((item) => item.type === 'inbound-rtp' && item.kind === 'audio');
    const outbound = reports.find((item) => item.type === 'outbound-rtp' && item.kind === 'audio');
    const remoteInbound = reports.find((item) => item.type === 'remote-inbound-rtp' && item.kind === 'audio');
    const active = inbound ?? outbound;
    const bytes = numberValue(active?.bytesReceived ?? active?.bytesSent);
    const timestamp = numberValue(active?.timestamp);
    const elapsed = previous.lastStatsAt > 0 ? Math.max(0.001, (timestamp - previous.lastStatsAt) / 1000) : 0;
    const bitrateKbps = elapsed > 0 ? Math.max(0, (bytes - previous.bytes) * 8 / elapsed / 1000) : 0;
    previous.lastBytes = bytes;
    previous.lastStatsAt = timestamp;

    return {
      packets: numberValue(inbound?.packetsReceived ?? outbound?.packetsSent),
      lost: numberValue(inbound?.packetsLost ?? remoteInbound?.packetsLost),
      jitterMs: numberValue(inbound?.jitter ?? remoteInbound?.jitter) * 1000,
      jitterBufferMs: numberValue(inbound?.jitterBufferDelay) /
        Math.max(1, numberValue(inbound?.jitterBufferEmittedCount)) * 1000,
      bitrateKbps,
    };
  }, []);

  const handleHostDataMessage = useCallback((peer: HostPeer, raw: string) => {
    const message = parseMessage(raw);
    if (!message) return;

    if (message.type === 'clock-probe') {
      const t1 = numberValue(message.t1);
      const t2 = performance.now();
      const t3 = performance.now();
      if (peer.channel) sendJson(peer.channel, {
        type: 'clock-pong',
        sequence: numberValue(message.sequence),
        t1,
        t2,
        t3,
      });
      return;
    }

    if (message.type === 'clock-status') {
      const fit = message.fit as ClockFit | null;
      peer.clock = fit && typeof fit.offsetMs === 'number' ? fit : null;
      patchHostPeer(peer.id, { clock: peer.clock });
      return;
    }

    if (message.type === 'ready') {
      peer.ready = true;
      peer.clock = message.fit as ClockFit;
      patchHostPeer(peer.id, { ready: true, clock: peer.clock });
      setStatus('Speaker ready. Start the group when every connected Speaker is ready.');
      return;
    }

    if (message.type === 'not-ready') {
      peer.ready = false;
      patchHostPeer(peer.id, { ready: false });
    }
  }, [patchHostPeer]);

  const createHostPeer = useCallback(async (peerId: string) => {
    const stream = hostStreamRef.current;
    if (!stream || hostPeersRef.current.has(peerId)) return;

    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    const peer: HostPeer = {
      id: peerId,
      pc,
      channel: null,
      pendingCandidates: [],
      ready: false,
      clock: null,
      statsTimer: null,
      lastBytes: 0,
      lastStatsAt: 0,
    };
    hostPeersRef.current.set(peerId, peer);
    setHostPeers((current) => [...current.filter((item) => item.id !== peerId), peerView(peer)]);

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        sendSignal(peerId, { kind: 'candidate', candidate: event.candidate.toJSON() });
      }
    };
    pc.onconnectionstatechange = () => {
      patchHostPeer(peerId, { state: pc.connectionState });
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        peer.ready = false;
        patchHostPeer(peerId, { ready: false });
        closeHostPeer(peerId);
      }
    };
    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'closed') {
        peer.ready = false;
        closeHostPeer(peerId);
      }
    };

    const channel = pc.createDataChannel('sync-control', { ordered: true });
    peer.channel = channel;
    channel.onopen = () => patchHostPeer(peerId, { channel: 'open' });
    channel.onclose = () => patchHostPeer(peerId, { channel: 'closed', ready: false });
    channel.onmessage = (event) => handleHostDataMessage(peer, String(event.data));

    const track = stream.getAudioTracks()[0];
    if (!track) {
      setError('The selected source did not provide an audio track.');
      closeHostPeer(peerId);
      return;
    }

    const sender = pc.addTrack(track, stream);
    const transceiver = pc.getTransceivers().find((item) => item.sender === sender);
    preferOpus(transceiver);

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    sendSignal(peerId, {
      kind: 'description',
      description: pc.localDescription ?? offer,
    });

    peer.statsTimer = window.setInterval(async () => {
      if (pc.connectionState === 'closed') return;
      try {
        const stats = await sampleStats(pc, peer);
        patchHostPeer(peerId, {
          packets: stats.packets,
          lost: stats.lost,
          jitterMs: stats.jitterMs,
          jitterBufferMs: stats.jitterBufferMs,
          bitrateKbps: stats.bitrateKbps,
          state: pc.connectionState,
          channel: peer.channel?.readyState ?? 'closed',
          ready: peer.ready,
          clock: peer.clock,
        });
      } catch {
        // Stats are diagnostic. A failed poll must not interrupt media.
      }
    }, 1000);
  }, [closeHostPeer, handleHostDataMessage, patchHostPeer, sampleStats, sendSignal]);

  const handleSpeakerDataMessage = useCallback((speaker: SpeakerPeer, raw: string) => {
    const message = parseMessage(raw);
    if (!message) return;

    if (message.type === 'clock-pong') {
      const t4 = performance.now();
      const sample = makeClockSample(
        numberValue(message.t1),
        numberValue(message.t2),
        numberValue(message.t3),
        t4,
      );
      if (!sample) return;

      speaker.samples.push(sample);
      if (speaker.samples.length > 120) speaker.samples.shift();
      const fit = estimateClock(speaker.samples, t4);
      speaker.clock = fit;
      const stable = fit !== null &&
        fit.samplesSeen >= 32 &&
        fit.samplesUsed >= 8 &&
        fit.selectedSpanMs >= 2500 &&
        fit.residualMs <= 3.0;
      speaker.stableStreak = stable ? Math.min(3, speaker.stableStreak + 1) : 0;

      setSpeakerClock(fit);
      setSpeakerStreak(speaker.stableStreak);

      if (speaker.channel && fit && message.sequence && numberValue(message.sequence) % 4 === 0) {
        sendJson(speaker.channel, { type: 'clock-status', fit });
      }
      maybeSendReady(speaker);
      return;
    }

    if (message.type === 'start-at') {
      const hostStartAtMs = numberValue(message.hostStartAtMs);
      if (!speaker.clock || !speaker.gain || speaker.context.state === 'closed') return;
      const localStartAtMs = hostStartAtMs - speaker.clock.offsetMs;
      const deltaMs = localStartAtMs - performance.now();
      const context = speaker.context;
      const output = typeof context.getOutputTimestamp === 'function'
        ? context.getOutputTimestamp()
        : null;
      const targetContextTime = output && output.performanceTime !== undefined &&
        output.contextTime !== undefined && output.performanceTime > 0
        ? output.contextTime + (localStartAtMs - output.performanceTime) / 1000
        : context.currentTime + Math.max(0, deltaMs) / 1000;

      speaker.gain.gain.cancelScheduledValues(context.currentTime);
      speaker.gain.gain.setValueAtTime(0, context.currentTime);
      speaker.gain.gain.setValueAtTime(0, Math.max(context.currentTime, targetContextTime));
      speaker.gain.gain.linearRampToValueAtTime(1, Math.max(context.currentTime, targetContextTime) + 0.012);
      speaker.playing = true;
      setScheduled(true);
      setStatus(deltaMs < 0 ? 'Start command arrived late; output was released immediately.' : 'Group start scheduled.');
      return;
    }

    if (message.type === 'stop-output') {
      const context = speaker.context;
      if (speaker.gain) {
        speaker.gain.gain.cancelScheduledValues(context.currentTime);
        speaker.gain.gain.setTargetAtTime(0, context.currentTime, 0.015);
      }
      speaker.playing = false;
      setScheduled(false);
      setStatus('Output muted by the Host.');
    }
  }, []);

  const maybeSendReady = useCallback((speaker: SpeakerPeer) => {
    const clockReady = speaker.clock !== null &&
      speaker.clock.samplesSeen >= 32 &&
      speaker.clock.samplesUsed >= 8 &&
      speaker.clock.selectedSpanMs >= 2500 &&
      speaker.clock.residualMs <= 3.0 &&
      speaker.stableStreak >= 3;
    const mediaReady = speaker.trackUnmuted && speaker.packetsReceived >= 8;
    if (!clockReady || !mediaReady || speaker.pc.connectionState !== 'connected' ||
        speaker.channel?.readyState !== 'open' || speaker.readySent) return;

    speaker.readySent = true;
    sendJson(speaker.channel, {
      type: 'ready',
      fit: speaker.clock,
      packetsReceived: speaker.packetsReceived,
      packetsLost: speaker.packetsLost,
    });
    setStatus('Ready. Waiting for the Host to schedule the group start.');
    setSpeakerState({
      id: speaker.hostPeerId,
      state: speaker.pc.connectionState,
      channel: speaker.channel.readyState,
      ready: true,
      packets: speaker.packetsReceived,
      lost: speaker.packetsLost,
      jitterMs: speaker.jitterMs,
      jitterBufferMs: speaker.jitterBufferMs,
      bitrateKbps: speaker.bitrateKbps,
      clock: speaker.clock,
    });
  }, []);

  const createSpeakerPeer = useCallback((hostPeerId: string) => {
    if (speakerRef.current) return;
    const context = new AudioContext({ latencyHint: 'interactive' });
    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    const speaker: SpeakerPeer = {
      hostPeerId,
      pc,
      channel: null,
      pendingCandidates: [],
      context,
      gain: null,
      source: null,
      samples: [],
      stableStreak: 0,
      clock: null,
      nextSequence: 0,
      probeTimer: null,
      statsTimer: null,
      packetsReceived: 0,
      packetsLost: 0,
      jitterMs: 0,
      jitterBufferMs: 0,
      bitrateKbps: 0,
      lastBytes: 0,
      lastStatsAt: 0,
      trackUnmuted: false,
      readySent: false,
      playing: false,
    };
    speakerRef.current = speaker;

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        sendSignal(hostPeerId, { kind: 'candidate', candidate: event.candidate.toJSON() });
      }
    };
    pc.onconnectionstatechange = () => {
      setSpeakerState((current) => current ? { ...current, state: pc.connectionState } : current);
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        speaker.readySent = false;
        speaker.playing = false;
        if (speaker.gain) {
          speaker.gain.gain.cancelScheduledValues(context.currentTime);
          speaker.gain.gain.setTargetAtTime(0, context.currentTime, 0.015);
        }
        setScheduled(false);
        setStatus(`Peer connection ${pc.connectionState}. Rejoin the room to restore playback.`);
      }
    };
    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'closed') {
        speaker.readySent = false;
        speaker.playing = false;
        if (speaker.gain) {
          speaker.gain.gain.cancelScheduledValues(context.currentTime);
          speaker.gain.gain.setTargetAtTime(0, context.currentTime, 0.015);
        }
        setScheduled(false);
        setStatus(`ICE connection ${pc.iceConnectionState}. Rejoin the room to restore playback.`);
      }
    };
    pc.ondatachannel = (event) => {
      if (event.channel.label !== 'sync-control') {
        event.channel.close();
        return;
      }
      speaker.channel = event.channel;
      event.channel.onopen = () => {
        setStatus('Control channel connected. Acquiring the Host clock…');
        speaker.probeTimer = window.setInterval(() => {
          if (event.channel.readyState !== 'open') return;
          const sequence = speaker.nextSequence++;
          sendJson(event.channel, {
            type: 'clock-probe',
            sequence,
            t1: performance.now(),
          });
        }, 250);
      };
      event.channel.onmessage = (messageEvent) => {
        handleSpeakerDataMessage(speaker, String(messageEvent.data));
      };
      event.channel.onclose = () => setStatus('Host control channel closed.');
    };
    pc.ontrack = (event) => {
      const stream = event.streams[0] ?? new MediaStream([event.track]);
      const source = context.createMediaStreamSource(stream);
      const gain = context.createGain();
      gain.gain.value = 0;
      source.connect(gain);
      gain.connect(context.destination);
      speaker.source = source;
      speaker.gain = gain;
      event.track.onunmute = () => {
        speaker.trackUnmuted = true;
        setStatus('RTP audio packets are arriving. Waiting for clock and buffer readiness…');
        maybeSendReady(speaker);
      };
      event.track.onmute = () => {
        speaker.trackUnmuted = false;
        speaker.readySent = false;
        if (speaker.channel) sendJson(speaker.channel, { type: 'not-ready' });
      };
    };

    const transceiver = pc.addTransceiver('audio', { direction: 'recvonly' });
    preferOpus(transceiver);

    speaker.statsTimer = window.setInterval(async () => {
      if (pc.connectionState === 'closed') return;
      try {
        const report = await pc.getStats();
        const reports = [...report.values()] as Array<Record<string, unknown>>;
        const inbound = reports.find((item) => item.type === 'inbound-rtp' && item.kind === 'audio');
        if (!inbound) return;
        const bytes = numberValue(inbound.bytesReceived);
        const timestamp = numberValue(inbound.timestamp);
        const elapsed = speaker.lastStatsAt > 0
          ? Math.max(0.001, (timestamp - speaker.lastStatsAt) / 1000)
          : 0;
        speaker.bitrateKbps = elapsed > 0
          ? Math.max(0, (bytes - speaker.lastBytes) * 8 / elapsed / 1000)
          : 0;
        speaker.lastBytes = bytes;
        speaker.lastStatsAt = timestamp;
        speaker.packetsReceived = numberValue(inbound.packetsReceived);
        speaker.packetsLost = numberValue(inbound.packetsLost);
        speaker.jitterMs = numberValue(inbound.jitter) * 1000;
        speaker.jitterBufferMs = numberValue(inbound.jitterBufferDelay) /
          Math.max(1, numberValue(inbound.jitterBufferEmittedCount)) * 1000;

        setSpeakerState({
          id: hostPeerId,
          state: pc.connectionState,
          channel: speaker.channel?.readyState ?? 'connecting',
          ready: speaker.readySent,
          packets: speaker.packetsReceived,
          lost: speaker.packetsLost,
          jitterMs: speaker.jitterMs,
          jitterBufferMs: speaker.jitterBufferMs,
          bitrateKbps: speaker.bitrateKbps,
          clock: speaker.clock,
        });
        maybeSendReady(speaker);
      } catch {
        // A stats failure must not interrupt the RTP receive path.
      }
    }, 1000);
  }, [handleSpeakerDataMessage, maybeSendReady, sendSignal]);

  async function handleServerMessage(message: Record<string, unknown>) {
    switch (message.type) {
      case 'room-created':
      case 'room-joined': {
        const code = String(message.roomCode ?? '');
        setRoomCode(code);
        setStatus(message.type === 'room-created'
          ? 'Room created. Share the code with your Speaker browsers.'
          : 'Joined the room. Waiting for the Host offer…');
        if (message.type === 'room-joined') {
          const hostPeerId = String(message.hostPeerId ?? '');
          createSpeakerPeer(hostPeerId);
        }
        break;
      }
      case 'peer-joined': {
        const peerId = String(message.peerId ?? '');
        setStatus('A Speaker joined. Negotiating the WebRTC audio and control channels…');
        await createHostPeer(peerId);
        break;
      }
      case 'peer-left': {
        const peerId = String(message.peerId ?? '');
        closeHostPeer(peerId);
        setStatus('A Speaker left the room.');
        break;
      }
      case 'room-closed':
        setError('The Host closed the room.');
        setStatus('Room closed.');
        stopSession(false);
        break;
      case 'signal': {
        const peerId = String(message.fromPeerId ?? '');
        const payload = message.payload as SignalPayload | undefined;
        if (!payload) break;
        if (roleRef.current === 'host') {
          const peer = hostPeersRef.current.get(peerId);
          if (!peer) break;
          if (payload.kind === 'description' && payload.description) {
            await peer.pc.setRemoteDescription(payload.description);
            for (const candidate of peer.pendingCandidates.splice(0)) {
              await peer.pc.addIceCandidate(candidate);
            }
          } else if (payload.kind === 'candidate') {
            const candidate = payload.candidate ?? null;
            if (peer.pc.remoteDescription) await peer.pc.addIceCandidate(candidate);
            else if (candidate) peer.pendingCandidates.push(candidate);
          }
        } else {
          const speaker = speakerRef.current;
          if (!speaker || peerId !== speaker.hostPeerId) break;
          if (payload.kind === 'description' && payload.description) {
            await speaker.pc.setRemoteDescription(payload.description);
            for (const candidate of speaker.pendingCandidates.splice(0)) {
              await speaker.pc.addIceCandidate(candidate);
            }
            const answer = await speaker.pc.createAnswer();
            await speaker.pc.setLocalDescription(answer);
            sendSignal(peerId, {
              kind: 'description',
              description: speaker.pc.localDescription ?? answer,
            });
          } else if (payload.kind === 'candidate') {
            const candidate = payload.candidate ?? null;
            if (speaker.pc.remoteDescription) await speaker.pc.addIceCandidate(candidate);
            else if (candidate) speaker.pendingCandidates.push(candidate);
          }
        }
        break;
      }
      case 'error':
        setError(String(message.message ?? 'Signaling server error.'));
        setStatus('Signaling error.');
        if (message.code === 'room_not_found' || message.code === 'room_full' ||
            message.code === 'already_in_room' || message.code === 'rate_limited') {
          setBusy(false);
        }
        break;
      case 'left-room':
        setStatus('Left the room.');
        break;
      default:
        break;
    }
  }

  async function startHost() {
    setError('');
    setBusy(true);
    setStatus('Starting the Host audio source…');
    try {
      const stream = await createHostStream();
      hostStreamRef.current = stream;
      const socket = await openSignaling();
      updateRole('host');
      socket.send(JSON.stringify({ type: 'create-room' }));
      setStatus('Connecting to signaling and creating a room…');
    } catch (cause) {
      hostStreamRef.current?.getTracks().forEach((track) => track.stop());
      hostStreamRef.current = null;
      setError(cause instanceof Error ? cause.message : 'Could not start the Host.');
      setStatus('Host setup failed.');
    } finally {
      setBusy(false);
    }
  }

  async function joinRoom() {
    const code = joinCode.trim().toUpperCase();
    if (!/^[A-Z2-9]{8}$/.test(code)) {
      setError('Enter the 8-character room code shown on the Host.');
      return;
    }

    setError('');
    setBusy(true);
    setStatus('Preparing the Speaker audio output…');
    try {
      if (!window.isSecureContext) {
        throw new Error('Open the browser room over HTTPS. Mobile browsers restrict WebRTC/audio capabilities on insecure LAN pages.');
      }
      const context = new AudioContext({ latencyHint: 'interactive' });
      await context.resume();
      const socket = await openSignaling();
      updateRole('speaker');
      setJoinCode(code);
      socket.send(JSON.stringify({ type: 'join-room', roomCode: code }));
      setStatus('Joining the room…');
      // The Speaker context is created from the Join button gesture so playback is allowed later.
      speakerContextRef.current = context;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not join the room.');
      setStatus('Join failed.');
    } finally {
      setBusy(false);
    }
  }

  const hostReadyCount = hostPeers.filter((peer) => peer.ready).length;
  const allHostPeersReady = hostPeers.length > 0 && hostReadyCount === hostPeers.length;
  const speakerContextRef = useRef<AudioContext | null>(null);

  // The context is created in the Join gesture. createSpeakerPeer consumes it once the room response arrives.
  const createSpeakerPeerWithContext = createSpeakerPeer;
  void createSpeakerPeerWithContext;

  async function startGroup() {
    const notReady = hostPeers.filter((peer) => !peer.ready || peer.channel !== 'open');
    if (hostPeers.length === 0 || notReady.length > 0) return;
    const hostStartAtMs = performance.now() + 2500;
    for (const peer of hostPeersRef.current.values()) {
      if (peer.ready && peer.channel) {
        sendJson(peer.channel, { type: 'start-at', hostStartAtMs });
      }
    }
    setScheduled(true);
    setStatus('A common future output-unmute time was sent to the connected Speakers.');
  }

  function stopOutput() {
    for (const peer of hostPeersRef.current.values()) {
      if (peer.channel) sendJson(peer.channel, { type: 'stop-output' });
    }
    setScheduled(false);
    setStatus('Output muted on connected Speakers.');
  }

  const joinDisabled = busy || !joinCode.trim();
  const serverUrl = signalingUrl();

  return (
    <main id="main-content" className="browser-room-page">
      <section className="browser-room-hero shell">
        <p className="eyebrow"><span className="eyebrow-mark" /> BROWSER / WEBRTC RTP PROTOTYPE</p>
        <div className="browser-room-heading">
          <div>
            <h1>One Host.<br /><span className="hero-gradient">Browser Speakers.</span></h1>
            <p>Live audio distribution using WebRTC media transport (Opus over RTP/RTCP), with a separate room-control and clock-probe channel.</p>
          </div>
          <div className="browser-protocol-tag"><span className="status-point" /> RTP MEDIA / RTCP CONTROL</div>
        </div>
        <div className="browser-room-layout">
          <section className="browser-room-panel browser-room-main">
            {role === 'idle' && (
              <>
                <div className="browser-room-role-grid">
                  <button className="browser-role-card" onClick={() => { setError(''); setStatus('Choose a source and create a Host room.'); updateRole('idle'); }}>
                    <span className="browser-role-number">01 / TRANSMIT</span>
                    <strong>Host a room</strong>
                    <small>Broadcast audio from this browser to up to eight Speakers.</small>
                  </button>
                  <button className="browser-role-card" onClick={() => { setError(''); setStatus('Enter the room code from the Host.'); }}>
                    <span className="browser-role-number">02 / RECEIVE</span>
                    <strong>Join as Speaker</strong>
                    <small>Receive the Host audio stream through a WebRTC peer connection.</small>
                  </button>
                </div>
                <div className="browser-room-divider" />
                <div className="browser-room-host-form">
                  <div className="browser-section-title">
                    <span>HOST SETUP</span>
                    <h2>Choose the audio source</h2>
                    <p>The Host’s browser encodes the audio track and sends it separately to every connected Speaker.</p>
                  </div>
                  <div className="browser-source-options">
                    <label><input type="radio" checked={sourceMode === 'file'} onChange={() => setSourceMode('file')} /> Local audio file</label>
                    <label><input type="radio" checked={sourceMode === 'microphone'} onChange={() => setSourceMode('microphone')} /> Microphone</label>
                    <label><input type="radio" checked={sourceMode === 'tab'} onChange={() => setSourceMode('tab')} /> Shared tab audio</label>
                  </div>
                  {sourceMode === 'file' && (
                    <div className="browser-field">
                      <label htmlFor="browser-audio-file">Audio file</label>
                      <input id="browser-audio-file" type="file" accept="audio/*" onChange={(event) => setAudioFile(event.target.files?.[0] ?? null)} />
                      {audioFile && <small className="browser-field-hint">{audioFile.name}</small>}
                      <audio ref={hostAudioRef} className="browser-host-audio" controls src={fileUrl || undefined} />
                    </div>
                  )}
                  {sourceMode === 'microphone' && (
                    <p className="browser-inline-note">Microphone capture requires HTTPS and browser permission. Use headphones to avoid acoustic feedback.</p>
                  )}
                  {sourceMode === 'tab' && (
                    <p className="browser-inline-note">Choose a browser tab in the sharing dialog and enable “Share tab audio”. Desktop Chrome/Edge is the recommended test environment.</p>
                  )}
                  <button className="button button-primary browser-primary-button" disabled={busy || (sourceMode === 'file' && !audioFile)} onClick={() => void startHost()}>
                    {busy ? 'Starting…' : 'Create Host room'} <span aria-hidden="true">→</span>
                  </button>
                </div>
                <div className="browser-room-divider" />
                <div className="browser-room-join-form">
                  <div className="browser-section-title">
                    <span>SPEAKER SETUP</span>
                    <h2>Join an existing room</h2>
                    <p>Open this page on a second browser or phone and enter the Host’s room code.</p>
                  </div>
                  <div className="browser-join-row">
                    <input aria-label="Room code" maxLength={8} autoCapitalize="characters" placeholder="8-character room code" value={joinCode} onChange={(event) => setJoinCode(event.target.value.toUpperCase().replace(/[^A-Z2-9]/g, ''))} />
                    <button className="button button-secondary" disabled={joinDisabled} onClick={() => void joinRoom()}>Join room</button>
                  </div>
                </div>
              </>
            )}

            {role === 'host' && (
              <div className="browser-active-room">
                <div className="browser-active-topline">
                  <div>
                    <span className="browser-role-number">HOST / LIVE ROOM</span>
                    <h2>Share this room code</h2>
                  </div>
                  <span className="browser-live-indicator"><i /> BROADCASTING</span>
                </div>
                <div className="browser-room-code">
                  <strong>{roomCode || '--------'}</strong>
                  <button className="button button-secondary" onClick={() => void navigator.clipboard?.writeText(roomCode)}>Copy code</button>
                </div>
                <p className="browser-inline-note">The audio source is live. Speakers connect independently; their WebRTC jitter buffers are managed by the browser.</p>
                <div className="browser-peer-list">
                  <div className="browser-peer-list-heading"><span>CONNECTED SPEAKERS</span><b>{hostPeers.length} / 8</b></div>
                  {hostPeers.length === 0 && <p className="browser-empty-peers">Waiting for a browser Speaker to join…</p>}
                  {hostPeers.map((peer) => (
                    <article className="browser-peer-row" key={peer.id}>
                      <div className="browser-peer-identity">
                        <span className={peer.state === 'connected' ? 'browser-peer-dot is-connected' : 'browser-peer-dot'} />
                        <div><strong>Speaker {hostPeers.findIndex((item) => item.id === peer.id) + 1}</strong><small>{peer.id.slice(0, 8)} · {peer.state} · data channel {peer.channel}</small></div>
                      </div>
                      <div className="browser-peer-metrics">
                        <span>{peer.ready ? 'READY' : 'SYNCING'}</span>
                        <small>{formatMs(peer.clock?.residualMs, 2)} fit · {peer.packets} RTP packets</small>
                      </div>
                    </article>
                  ))}
                </div>
                <div className="browser-room-actions">
                  <button className="button button-primary" disabled={!allHostPeersReady || scheduled} onClick={() => void startGroup()}>
                    {scheduled ? 'Start scheduled' : 'Schedule group output'} <span aria-hidden="true">→</span>
                  </button>
                  <button className="button button-secondary" disabled={!scheduled} onClick={stopOutput}>Mute Speakers</button>
                  <button className="button button-quiet" onClick={() => stopSession()}>End room</button>
                </div>
              </div>
            )}

            {role === 'speaker' && (
              <div className="browser-active-room">
                <div className="browser-active-topline">
                  <div>
                    <span className="browser-role-number">SPEAKER / RECEIVER</span>
                    <h2>Connected to Host</h2>
                  </div>
                  <span className="browser-live-indicator"><i /> {scheduled ? 'PLAYING' : 'CONNECTED'}</span>
                </div>
                <div className="browser-room-code"><strong>{roomCode || joinCode}</strong><span className="browser-room-code-label">ROOM CODE</span></div>
                <div className="browser-speaker-status">
                  <div><span>Peer connection</span><strong>{speakerState?.state ?? 'Negotiating'}</strong></div>
                  <div><span>Control channel</span><strong>{speakerState?.channel ?? 'Connecting'}</strong></div>
                  <div><span>Clock fit residual</span><strong>{formatMs(speakerClock?.residualMs, 2)}</strong></div>
                  <div><span>Stable updates</span><strong>{speakerStreak} / 3</strong></div>
                  <div><span>RTP packets received</span><strong>{speakerState?.packets ?? 0}</strong></div>
                  <div><span>Packet loss</span><strong>{speakerState?.lost ?? 0}</strong></div>
                  <div><span>Jitter</span><strong>{formatMs(speakerState?.jitterMs, 2)}</strong></div>
                  <div><span>Mean jitter-buffer delay</span><strong>{formatMs(speakerState?.jitterBufferMs, 2)}</strong></div>
                  <div><span>Incoming bitrate</span><strong>{formatBitrate(speakerState?.bitrateKbps ?? 0)}</strong></div>
                </div>
                <div className="browser-speaker-readiness">
                  <span className={speakerState?.ready ? 'browser-readiness-dot is-ready' : 'browser-readiness-dot'} />
                  <p>{speakerState?.ready ? 'Ready. Waiting for the Host’s scheduled output release.' : 'Acquiring the Host clock and waiting for live RTP audio packets.'}</p>
                </div>
                <button className="button button-quiet" onClick={() => stopSession()}>Leave room</button>
              </div>
            )}
          </section>

          <aside className="browser-room-panel browser-room-side">
            <div className="browser-section-title">
              <span>TRANSPORT / NOTES</span>
              <h2>What runs in the browser</h2>
            </div>
            <ul className="browser-transport-list">
              <li><b>Media plane</b><span>WebRTC audio tracks, Opus codec preference, RTP/SRTP and RTCP reports.</span></li>
              <li><b>Control plane</b><span>WebSocket room signaling plus a WebRTC data channel for clock probes and start commands.</span></li>
              <li><b>Network</b><span>ICE with STUN by default. Add TURN for networks that block direct peer-to-peer paths.</span></li>
              <li><b>Topology</b><span>One-to-one peer connections from Host to each Speaker; up to eight Speakers in this prototype.</span></li>
            </ul>
            <div className="browser-room-warning">
              <strong>Synchronization limitation</strong>
              <p>The browser owns each peer’s RTP jitter buffer and normal audio playout. The scheduled start gates output at a common estimated time, but it does not expose sample-level control of each hidden jitter buffer or guarantee acoustic alignment.</p>
            </div>
            <div className="browser-room-connection">
              <span>SIGNALING</span>
              <b className={serverUrl ? 'is-configured' : 'is-missing'}>{serverUrl ? 'Configured' : 'Not configured'}</b>
              <small>{serverUrl ?? 'Set VITE_SIGNALING_URL to a wss:// endpoint for the deployed site.'}</small>
              {!isSecure && <small className="browser-connection-alert">This page is not a secure context. Use HTTPS for microphone and tab capture, and for mobile browser testing.</small>}
            </div>
          </aside>
        </div>
        {(error || status) && (
          <div className={error ? 'browser-room-feedback has-error' : 'browser-room-feedback'} role="status" aria-live="polite">
            <span>{error ? 'ERROR' : 'STATUS'}</span>
            <p>{error || status}</p>
          </div>
        )}
      </section>
    </main>
  );
}
